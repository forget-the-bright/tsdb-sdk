package io.github.forget_the_bright.tsdb.pspace.internal.adapter;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.github.forget_the_bright.tsdb.common.api.WriteSession;
import io.github.forget_the_bright.tsdb.common.exception.TsdbException;
import io.github.forget_the_bright.tsdb.common.model.Quality;
import io.github.forget_the_bright.tsdb.common.model.Sample;
import io.github.forget_the_bright.tsdb.common.model.ValueType;
import io.github.forget_the_bright.tsdb.common.model.WriteRequest;
import io.github.forget_the_bright.tsdb.common.model.WriteResult;
import io.github.forget_the_bright.tsdb.pspace.internal.PspaceMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * pSpace 实时值写入适配器（{@code PUT /UpdRealData}）。
 *
 * <p>语义约束（详见 {@code docs/16}）：</p>
 * <ul>
 *   <li>受 {@code security.allow-write} 门禁：未开启时 {@link #isWritable()} 返回
 *       {@code false}，{@link #write(List)} 抛 {@code TSDB-UNSUPPORTED}，不静默丢弃；</li>
 *   <li>质量只接受 {@link Quality#GOOD}（192）与 {@link Quality#BAD}（0）：
 *       手册没有给出其余码表，{@code UNCERTAIN/UNKNOWN} 一律按条拒绝而不是猜；</li>
 *   <li>值按 {@link Sample#getValueType()} 分派：DOUBLE/LONG 送 JSON 数字，
 *       BOOLEAN 送布尔，STRING 送字符串；{@code NULL}/BYTES 没有明确的服务端约定，
 *       按条拒绝；</li>
 *   <li>位号解析失败（目录中找不到）按条拒绝并记录原因；</li>
 *   <li>统计单位是<b>数据点（sample）</b>：{@link WriteResult#getAccepted()} +
 *       {@link WriteResult#getRejected()} 等于本次提交的采样点总数。</li>
 * </ul>
 *
 * <p>服务端逐条返回 {@code idx/pid/code/mesg}，{@code code != 0} 即失败；
 * 响应中没有逐条结果时（部分版本只回 envelope），视为整批被接受。</p>
 *
 * <p><b>非公共 API</b>：门户装配层实现，签名随内部重构变化，宿主应用请勿直接依赖；
 * 稳定契约请使用 {@link io.github.forget_the_bright.tsdb.pspace.PspaceClient#writeSession()}。</p>
 *
 * @author wanghao
 * @since 0.1.2
 */
public final class PspaceWriteAdapter implements WriteSession {

    private final PspaceSupport support;

    /**
     * @param support 共享依赖
     */
    public PspaceWriteAdapter(PspaceSupport support) {
        this.support = support;
    }

    /**
     * 是否允许写入，取决于配置项 {@code security.allow-write}。
     *
     * @return 允许写入返回 {@code true}
     */
    @Override
    public boolean isWritable() {
        return support.config().isAllowWrite();
    }

    /**
     * 批量写入实时值。
     *
     * @param requests 每个元素对应一个位号及其采样点
     * @return 以采样点为单位的写入结果
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException
     *         未开启 {@code security.allow-write} 时
     */
    @Override
    public WriteResult write(List<WriteRequest> requests) {
        support.ensureOpen();
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("requests must not be empty");
        }
        if (!isWritable()) {
            throw support.unsupported(
                    "pSpace write is disabled; set security.allow-write=true to enable",
                    "write", null);
        }
        List<String> errors = new ArrayList<String>();
        JSONArray values = new JSONArray();
        int rejected = 0;
        for (WriteRequest request : requests) {
            String tagName = request.getTagName();
            Long tagId;
            try {
                tagId = support.resolver().idOf(tagName);
            } catch (TsdbException e) {
                throw support.context(e, "write");
            }
            if (tagId == null) {
                rejected += request.getSamples().size();
                errors.add(tagName + ": tag not found in pSpace directory");
                continue;
            }
            int index = 0;
            for (Sample sample : request.getSamples()) {
                String problem = append(tagName, index++, sample, tagId, values);
                if (problem != null) {
                    rejected++;
                    errors.add(problem);
                }
            }
        }
        if (values.isEmpty()) {
            return new WriteResult(0, rejected, errors);
        }
        JSONObject body = new JSONObject();
        body.put("count", Integer.valueOf(values.size()));
        body.put("values", values);
        String response;
        try {
            response = support.nativeApi().updRealData(body.toJSONString());
        } catch (TsdbException e) {
            throw support.context(e, "write");
        }
        return merge(response, values.size(), rejected, errors);
    }

    /**
     * 校验并追加一条写入项。
     *
     * <p>单条校验失败只拒绝该 sample，不中断整批；只有位号解析本身失败
     * （目录不可用）才向上抛异常。</p>
     *
     * @param tagName 位号名，仅用于错误消息
     * @param index   该位号内 sample 的下标
     * @param sample  待写入样本
     * @param tagId   已解析的点位 id
     * @param values  已通过校验、待发送的请求项
     * @return 失败原因；成功时返回 {@code null}
     */
    private String append(String tagName, int index, Sample sample, Long tagId, JSONArray values) {
        if (sample == null) {
            return tagName + "[" + index + "]: sample is null";
        }
        Instant timestamp = sample.getTimestamp();
        if (timestamp == null) {
            return tagName + "[" + index + "]: timestamp is null";
        }
        Integer qualityCode = qualityCode(sample.getQuality());
        if (qualityCode == null) {
            return tagName + "[" + index + "]: quality " + sample.getQuality()
                    + " has no pSpace code (only GOOD/BAD are supported)";
        }
        ValueType type = sample.getValueType();
        Object raw = sample.getValue();
        Object pv;
        if (type == null || raw == null) {
            return tagName + "[" + index + "]: null value cannot be written";
        }
        switch (type) {
            case DOUBLE:
                pv = Double.valueOf(sample.getDoubleValue());
                break;
            case LONG:
                pv = Long.valueOf(sample.getLongValue());
                break;
            case BOOLEAN:
                pv = Boolean.valueOf(sample.getBooleanValue());
                break;
            case STRING:
                pv = sample.getTextValue();
                break;
            case BYTES:
            default:
                return tagName + "[" + index + "]: value type " + type
                        + " is not writable to pSpace";
        }
        JSONObject item = new JSONObject();
        item.put("tagid", tagId);
        item.put("pv", pv);
        item.put("tm", support.mapper().formatRequestTime(timestamp));
        item.put("qy", qualityCode);
        values.add(item);
        return null;
    }

    /**
     * 合并服务端逐条结果与本地拒绝项。
     *
     * <p>响应没有 {@code values} 数组（部分版本只回信封）时，视为整批接受：
     * {@code accepted = sent}。有逐条结果时 {@code code != 0} 计入
     * {@code rejected}，并在错误列表中带上 {@code idx/pid/mesg}。</p>
     *
     * @param response 写入响应正文
     * @param sent     实际发送的 sample 数
     * @param rejected 本地已拒绝的 sample 数
     * @param errors   错误列表，会被追加
     * @return 以 sample 为单位的写入结果
     */
    private WriteResult merge(String response, int sent, int rejected, List<String> errors) {
        JSONArray results = null;
        try {
            JSONObject root = JSON.parseObject(response);
            if (root != null) {
                results = root.getJSONArray("values");
            }
        } catch (RuntimeException ignored) {
            results = null;
        }
        if (results == null) {
            return new WriteResult(sent, rejected, errors);
        }
        int accepted = 0;
        int failures = 0;
        for (int i = 0; i < results.size(); i++) {
            JSONObject item = results.getJSONObject(i);
            if (item == null) {
                continue;
            }
            Integer code = null;
            try {
                code = item.getInteger("code");
            } catch (RuntimeException ignored) {
                code = null;
            }
            if (code != null && code.intValue() != 0) {
                failures++;
                errors.add("pSpace write item " + describe(item) + " rejected with code "
                        + code + ": " + item.getString("mesg"));
            } else {
                accepted++;
            }
        }
        return new WriteResult(accepted, rejected + failures, errors);
    }

    /**
     * 把服务端返回项描述成 {@code #idx (pid=x)} 或 {@code pid=x}，
     * 用于错误消息定位。
     *
     * @param item 单条结果
     * @return 可读定位串
     */
    private static String describe(JSONObject item) {
        Object idx = item.get("idx");
        Object pid = item.get("pid");
        if (idx != null) {
            return "#" + idx + (pid != null ? " (pid=" + pid + ")" : "");
        }
        return pid != null ? "pid=" + pid : "?";
    }

    /**
     * 把公共质量枚举映射成厂商码；手册只给出 GOOD/BAD 两个确定值，
     * 其余一律返回 {@code null} 由调用方按条拒绝，不做猜测。
     *
     * @param quality 公共质量
     * @return 厂商质量码，未知时 {@code null}
     */
    private static Integer qualityCode(Quality quality) {
        if (quality == Quality.GOOD) {
            return Integer.valueOf(PspaceMapper.QUALITY_GOOD);
        }
        if (quality == Quality.BAD) {
            return Integer.valueOf(PspaceMapper.QUALITY_BAD);
        }
        return null;
    }
}