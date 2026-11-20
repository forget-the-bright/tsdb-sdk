package io.github.forget_the_bright.tsdb.ge.internal;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.github.forget_the_bright.tsdb.common.exception.TsdbAuthenticationException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbDataException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbQueryException;
import io.github.forget_the_bright.tsdb.common.internal.json.JsonText;
import io.github.forget_the_bright.tsdb.common.model.Direction;
import io.github.forget_the_bright.tsdb.common.model.HealthStatus;
import io.github.forget_the_bright.tsdb.ge.GeConfig;
import io.github.forget_the_bright.tsdb.ge.nativeapi.GeEndpoint;

import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GE Historian 原生 REST API 客户端。
 *
 * <p>本类是“原生能力层”的核心：它不经过公共模型归一化，直接以 GE 的
 * HTTP 接口、参数和 JSON 结构工作，因此业务代码可以在需要时使用 GE 的
 * 全部能力，而不受公共层抽象范围限制。</p>
 *
 * <p>本类负责：URL 拼接、OAuth Bearer 头、401 时刷新令牌并重试、
 * JSON 编解码、GE 业务错误码校验。数据归一化由
 * {@link io.github.forget_the_bright.tsdb.ge.GeMapper} 完成，二者职责分离。</p>
 *
 * <p><strong>时间：</strong>请求的 {@code start/end} 与写入点的 {@code TimeStamp}
 * 统一走 {@link io.github.forget_the_bright.tsdb.common.config.TimeConfig}
 * 的 {@code formatRequest}；响应 {@code TimeStamp} 走 {@code parseResponse}。
 * 因此同一套代码可通过 {@code options.time.*} 适配“GE 用 UTC”与“GE 用本地时区”
 * 两类现场，详见 {@link GeConfig#getTimeConfig()}。</p>
 *
 * <p><strong>全量端点：</strong>除下方少量类型化便捷方法外，
 * {@link #invoke(GeEndpoint, Map, Map, Object)} 可以调用
 * {@link GeEndpoint} 中列出的全部 92 个 GE REST 端点（Data 22、Tags 13、
 * Systems 25、Collectors 27、Alarms 5）。这是保证原生能力不被阉割的
 * 逃生口；方法名、路径与参数位置均与原 SDK 的枚举一一对应。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class GeNativeApi {

    private static final int UNLIMITED_TAGS = 100000;

    private final GeConfig config;
    private final GeHttpClient http;
    private final GeTokenManager tokens;

    /**
     * 创建原生 API 客户端。
     *
     * @param config 连接配置，不能为 {@code null}
     * @param http   传输层，不能为 {@code null}
     * @param tokens 令牌管理，不能为 {@code null}
     */
    public GeNativeApi(GeConfig config, GeHttpClient http, GeTokenManager tokens) {
        this.config = config;
        this.http = http;
        this.tokens = tokens;
    }

    /**
     * 调用一个无需路径变量与查询参数的 GE 端点，例如
     * {@link GeEndpoint#GET_SERVER_PROPERTIES}。
     *
     * @param endpoint 端点
     * @return GE 返回的 JSON 根节点（fastjson {@link JSONObject}）
     */
    public Object invoke(GeEndpoint endpoint) {
        return invoke(endpoint, Collections.<String, Object>emptyMap(),
                Collections.<String, Object>emptyMap(), null);
    }

    /**
     * 调用一个只需要查询参数（或只需要请求体）的 GE 端点。
     *
     * @param endpoint  端点
     * @param arguments 查询参数；当端点的主参数位置是 BODY 时，该对象会作为 JSON 请求体
     * @return GE 返回的 JSON 根节点（fastjson {@link JSONObject}）
     */
    public Object invoke(GeEndpoint endpoint, Map<String, Object> arguments) {
        if (endpoint.primary() == GeEndpoint.Position.BODY) {
            return invoke(endpoint, Collections.<String, Object>emptyMap(),
                    Collections.<String, Object>emptyMap(), arguments);
        }
        if (endpoint.primary() == GeEndpoint.Position.PATH) {
            return invoke(endpoint, arguments,
                    Collections.<String, Object>emptyMap(), null);
        }
        return invoke(endpoint, Collections.<String, Object>emptyMap(), arguments, null);
    }

    /**
     * 调用任意 GE REST 端点——这是原生 API 的完整逃生口。
     *
     * <p>路径模板中的 <code>{name}</code> 会从 {@code pathVariables} 取值并做
     * URL 编码；查询参数会按键名排序后拼到 URL；请求体支持
     * {@link JSONObject}/{@link JSONArray}、{@link String}（原样发送）以及普通
     * Java Bean（由 fastjson 序列化）。</p>
     *
     * <p>示例：调用“按路径查询原始数据”：
     * <pre>{@code
     * Map<String,Object> path = new LinkedHashMap<>();
     * path.put("tagNames", "AI-101,AI-102");
     * path.put("start", "2025-01-01T00:00:00Z");
     * path.put("end", "2025-01-01T01:00:00Z");
     * path.put("direction", 1);
     * path.put("count", 100);
     * JSONObject json = (JSONObject) nativeApi.invoke(
     *         GeEndpoint.GET_RAW_DATA_BY_PATH_VARIABLE, path);
     * }</pre>
     *
     * @param endpoint      端点
     * @param pathVariables 路径变量，键名与路径模板中的占位符一致
     * @param queryParams   查询参数，值会经 {@link String#valueOf(Object)} 后 URL 编码
     * @param body          JSON 请求体；可为 fastjson 树类型、JSON 字符串或 Bean
     * @return GE 返回的 JSON 根节点（fastjson {@link JSONObject}）
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbDataException  路径变量缺失或响应不可解析
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbQueryException GE 业务错误码非 0
     */
    public Object invoke(GeEndpoint endpoint, Map<String, Object> pathVariables,
                         Map<String, Object> queryParams, Object body) {
        String path = endpoint.path();
        if (pathVariables != null) {
            for (Map.Entry<String, Object> entry : pathVariables.entrySet()) {
                String token = "{" + entry.getKey() + "}";
                path = path.replace(token, encode(String.valueOf(entry.getValue())));
            }
        }
        if (path.indexOf('{') >= 0) {
            throw new TsdbDataException("GE endpoint " + endpoint.name()
                    + " is missing path variable(s): " + path);
        }
        String url = config.apiUrl(path);
        if (queryParams != null && !queryParams.isEmpty()) {
            StringBuilder query = new StringBuilder();
            for (Map.Entry<String, Object> entry : queryParams.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                query.append(query.length() == 0 ? '?' : '&');
                query.append(encode(entry.getKey())).append('=').append(encode(String.valueOf(entry.getValue())));
            }
            url = url + query;
        }
        String payload = encodeBody(body);
        return execute(endpoint.method(), url, payload, endpoint.name());
    }

    /**
     * 以任意 HTTP 方法、任意相对路径调用 GE，用于枚举尚未覆盖的新版本端点。
     *
     * @param method       HTTP 方法，支持 GET/POST/PUT/DELETE
     * @param relativePath 相对 /historian-rest-api 的路径，可包含查询串
     * @param body         JSON 请求体，可为 null
     * @return GE 返回的 JSON 根节点（fastjson {@link JSONObject}）
     */
    public Object rawRequest(String method, String relativePath, Object body) {
        return execute(method, config.apiUrl(relativePath), encodeBody(body), "rawRequest");
    }

    public List<String> listTags(int maxNumber, String nameMask) {
        StringBuilder url = new StringBuilder(config.apiUrl("/v1/tags"));
        url.append("?maxNumber=").append(maxNumber > 0 ? maxNumber : UNLIMITED_TAGS);
        if (nameMask != null && !nameMask.trim().isEmpty()) {
            url.append("&nameMask=").append(encode(nameMask.trim()));
        }
        JSONObject root = read(url.toString(), "listTags");
        List<String> tags = new ArrayList<String>();
        Object arrayValue = JsonText.value(root, "Tags");
        if (arrayValue instanceof JSONArray) {
            for (Object tag : (JSONArray) arrayValue) {
                if (tag != null) {
                    tags.add(String.valueOf(tag));
                }
            }
        }
        return tags;
    }

    /**
     * 批量读取当前值（POST {@code /v1/datapoints/sampled}，samplingMode=1）。
     *
     * <p>线缆格式与旧 SDK 完全一致：请求体为纯 JSON 对象，
     * {@code samplingMode} 为<b>字符串</b> {@code "1"}（旧 SDK fastjson
     * {@code WriteEnumUsingToString} 把枚举序列化为数值字符串），
     * {@code queryModifier} 为数字 {@code 1}，{@code tagNames} 为
     * <b>明文</b>分号拼接（不做 URL 编码）。位号数量不受 URL 长度限制。</p>
     *
     * @param tagNames 位号集合，元素不能为 {@code null}
     * @return 每个位号一个序列（含 per-tag 错误码与工程单位），非 {@code null}
     */
    public List<GeModels.Series> readCurrentRawValue(Collection<String> tagNames) {
        LinkedHashMap<String, Object> sampledEntity = new LinkedHashMap<>();
        sampledEntity.put("samplingMode", "1");
        sampledEntity.put("queryModifier", 1L);
        sampledEntity.put("tagNames", joinTagsPlain(tagNames));
        Object response = invoke(GeEndpoint.GET_SAMPLED_BY_REQUEST_PARAM_POST, null,
                null, sampledEntity);
        return parseData(response);
    }

    /**
     * 批量读取当前值（遗留 GET {@code /v1/datapoints/currentvalue}）。
     *
     * <p>保留作为原生逃生口与生产对比基准；位号拼在 URL 查询串里，
     * 大批量位号请改用 {@link #readCurrentRawValue(Collection)}（POST）。</p>
     *
     * @param tagNames 位号集合
     * @return 每个位号一个序列，非 {@code null}
     */
    public List<GeModels.Series> readCurrent(Collection<String> tagNames) {
        String url = config.apiUrl("/v1/datapoints/currentvalue")
                + "?tagNames=" + encodeTags(tagNames);
        return parseData(read(url, "readCurrent"));
    }

    /**
     * 批量读取原始数据（POST {@code /v1/datapoints/raw}）。
     *
     * <p>线缆格式：标量参数 {@code start/end/direction/count} 走 URL 查询串，
     * 位号列表走请求体 {@code {"tagNames":"a;b;c"}}（明文分号拼接）。
     * 与旧 SDK {@code getRawDataByRequestParamPost} 一致，
     * 大批量位号不再受 URL 长度限制。</p>
     *
     * @param tagNames  位号集合，元素不能为 {@code null}
     * @param start     区间起始时刻（含）
     * @param end       区间结束时刻（不含）
     * @param direction 读取方向，{@code null} 按 FORWARD 处理
     * @param count     服务端返回点数上限，小于 1 时按 1 处理
     * @return 每个位号一个序列，非 {@code null}
     */
    public List<GeModels.Series> readRaw(Collection<String> tagNames, Instant start, Instant end,
                                         Direction direction, int count) {
        LinkedHashMap<String, Object> query = new LinkedHashMap<>();
        query.put("start", config.getTimeConfig().formatRequest(start));
        query.put("end", config.getTimeConfig().formatRequest(end));
        query.put("direction", direction == Direction.BACKWARD ? 0 : 1);
        query.put("count", Math.max(1, count));
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("tagNames", joinTagsPlain(tagNames));
        return parseData(invoke(GeEndpoint.GET_RAW_DATA_BY_REQUEST_PARAM_POST, null, query, body));
    }

    /**
     * 批量读取原始数据（遗留 GET 版本）。
     *
     * <p>保留作为原生逃生口与生产对比基准；位号拼在 URL 查询串里，
     * 大批量位号请改用 {@link #readRaw(Collection, Instant, Instant, Direction, int)}。</p>
     *
     * @param tagNames  位号集合
     * @param start     区间起始时刻（含）
     * @param end       区间结束时刻（不含）
     * @param direction 读取方向，{@code null} 按 FORWARD 处理
     * @param count     服务端返回点数上限，小于 1 时按 1 处理
     * @return 每个位号一个序列，非 {@code null}
     */
    public List<GeModels.Series> readRawGet(Collection<String> tagNames, Instant start, Instant end,
                                            Direction direction, int count) {
        String url = config.apiUrl("/v1/datapoints/raw")
                + "?tagNames=" + encodeTags(tagNames)
                + "&start=" + encode(config.getTimeConfig().formatRequest(start))
                + "&end=" + encode(config.getTimeConfig().formatRequest(end))
                + "&direction=" + (direction == Direction.BACKWARD ? 0 : 1)
                + "&count=" + Math.max(1, count);
        return parseData(read(url, "readRawGet"));
    }

    /**
     * 批量读取区间计算数据（POST {@code /v1/datapoints/calculated}）。
     *
     * <p>线缆格式：标量参数 {@code count/start/end/calculationMode/intervalMs}
     * 走 URL 查询串，位号列表走请求体 {@code {"tagNames":"a;b;c"}}（明文）。
     * 与旧 SDK {@code getCalculatedByRequestParamPost} 一致。</p>
     *
     * @param tagNames        位号集合，元素不能为 {@code null}
     * @param start           区间起始时刻（含）
     * @param end             区间结束时刻（不含）
     * @param calculationMode GE 计算模式编号（如 1=Average、9=RawTotal、
     *                        17=FirstRawValue、19=LastRawValue、24=SumValue）
     * @param count           区间（桶）个数，即每个间隔出一个点，共取几个间隔；小于 1 时按 1 处理
     * @param intervalMillis  间隔毫秒数，小于 1 时按 1 处理
     * @return 每个位号一个序列，非 {@code null}
     */
    public List<GeModels.Series> readAggregated(Collection<String> tagNames, Instant start, Instant end,
                                                int calculationMode, int count, long intervalMillis) {
        LinkedHashMap<String, Object> query = new LinkedHashMap<>();
        query.put("count", Math.max(1, count));
        query.put("start", config.getTimeConfig().formatRequest(start));
        query.put("end", config.getTimeConfig().formatRequest(end));
        query.put("calculationMode", calculationMode);
        query.put("intervalMs", Math.max(1L, intervalMillis));
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("tagNames", joinTagsPlain(tagNames));
        return parseData(invoke(GeEndpoint.GET_CALCULATED_BY_REQUEST_PARAM_POST, null, query, body));
    }

    /**
     * 批量读取区间计算数据（遗留 GET 版本）。
     *
     * <p>保留作为原生逃生口与生产对比基准；大批量位号请改用
     * {@link #readAggregated(Collection, Instant, Instant, int, int, long)}。</p>
     *
     * @param tagNames        位号集合
     * @param start           区间起始时刻（含）
     * @param end             区间结束时刻（不含）
     * @param calculationMode GE 计算模式编号
     * @param count           区间（桶）个数，小于 1 时按 1 处理
     * @param intervalMillis  间隔毫秒数，小于 1 时按 1 处理
     * @return 每个位号一个序列，非 {@code null}
     */
    public List<GeModels.Series> readAggregatedGet(Collection<String> tagNames, Instant start, Instant end,
                                                   int calculationMode, int count, long intervalMillis) {
        String url = config.apiUrl("/v1/datapoints/calculated")
                + "?tagNames=" + encodeTags(tagNames)
                + "&count=" + Math.max(1, count)
                + "&start=" + encode(config.getTimeConfig().formatRequest(start))
                + "&end=" + encode(config.getTimeConfig().formatRequest(end))
                + "&calculationMode=" + calculationMode
                + "&intervalMs=" + Math.max(1L, intervalMillis);
        return parseData(read(url, "readAggregatedGet"));
    }

    /**
     * 批量读取插值数据（POST {@code /v1/datapoints/interpolated}）。
     *
     * <p>线缆格式：标量参数 {@code start/end/count/intervalMs} 走 URL 查询串，
     * 位号列表走请求体 {@code {"tagNames":"a;b;c"}}（明文）。
     * 与旧 SDK {@code getInterpolatedByRequestParamPost} 一致。</p>
     *
     * @param tagNames       位号集合，元素不能为 {@code null}
     * @param start          区间起始时刻（含）
     * @param end            区间结束时刻（不含）
     * @param count          服务端返回点数，小于 1 时按 1 处理
     * @param intervalMillis 间隔毫秒数，小于 1 时按 1 处理
     * @return 每个位号一个序列，非 {@code null}
     */
    public List<GeModels.Series> readInterpolated(Collection<String> tagNames, Instant start, Instant end,
                                                  int count, long intervalMillis, Long queryModifier) {
        LinkedHashMap<String, Object> query = new LinkedHashMap<>();
        query.put("start", config.getTimeConfig().formatRequest(start));
        query.put("end", config.getTimeConfig().formatRequest(end));
        query.put("count", Math.max(1, count));
        query.put("intervalMs", Math.max(1L, intervalMillis));
        if (queryModifier != null){
            query.put("queryModifier", queryModifier);
        }
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("tagNames", joinTagsPlain(tagNames));
        return parseData(invoke(GeEndpoint.GET_INTERPOLATED_BY_REQUEST_PARAM_POST, null, query, body));
    }

    /**
     * 批量读取插值数据（遗留 GET 版本）。
     *
     * <p>保留作为原生逃生口与生产对比基准；大批量位号请改用
     * {@link #readInterpolated(Collection, Instant, Instant, int, long, Long)}。</p>
     *
     * @param tagNames       位号集合
     * @param start          区间起始时刻（含）
     * @param end            区间结束时刻（不含）
     * @param count          服务端返回点数，小于 1 时按 1 处理
     * @param intervalMillis 间隔毫秒数，小于 1 时按 1 处理
     * @return 每个位号一个序列，非 {@code null}
     */
    public List<GeModels.Series> readInterpolatedGet(Collection<String> tagNames, Instant start, Instant end,
                                                     int count, long intervalMillis) {
        String url = config.apiUrl("/v1/datapoints/interpolated")
                + "?tagNames=" + encodeTags(tagNames)
                + "&start=" + encode(config.getTimeConfig().formatRequest(start))
                + "&end=" + encode(config.getTimeConfig().formatRequest(end))
                + "&count=" + Math.max(1, count)
                + "&intervalMs=" + Math.max(1L, intervalMillis);
        return parseData(read(url, "readInterpolatedGet"));
    }

    /**
     * 批量读取采样数据（POST {@code /v1/datapoints/sampled}，全参数便捷方法）。
     *
     * <p>线缆格式与旧 SDK {@code getSampledByRequestParamPost(SampledEntity)}
     * 一致：纯请求体，枚举类参数（{@code samplingMode}/{@code calculationMode}/
     * {@code direction}）序列化为<b>数值字符串</b>（如 {@code "2"}），
     * {@code queryModifier}/{@code count}/{@code intervalMs} 为数字，
     * {@code tagNames} 为明文分号拼接。值为 {@code null} 的参数不会出现在
     * 请求体中（与旧 SDK fastjson 默认跳过 null 字段一致）。</p>
     *
     * <p>常用组合：当前值 {@code samplingMode=1, queryModifier=1}；
     * 插值曲线 {@code samplingMode=2, queryModifier=1} + 间隔或点数。</p>
     *
     * @param tagNames        位号集合，元素不能为 {@code null}
     * @param start           区间起始时刻，允许为 {@code null}（当前值模式忽略时间）
     * @param end             区间结束时刻，允许为 {@code null}
     * @param samplingMode    GE 采样模式编号（1=CurrentValue、2=Interpolated、
     *                        3=Trend、6=Calculated 等）
     * @param queryModifier   查询修饰符（1=补全边界点）
     * @param intervalMs      间隔毫秒数，允许为 {@code null}
     * @param count           服务端点数，允许为 {@code null}
     * @param calculationMode GE 计算模式编号，允许为 {@code null}
     * @param direction       读取方向，允许为 {@code null}
     * @return 每个位号一个序列，非 {@code null}
     */
    public List<GeModels.Series> readSampled(Collection<String> tagNames, Instant start, Instant end,
                                             int samplingMode, long queryModifier, Long intervalMs,
                                             Integer count, Integer calculationMode, Direction direction) {
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("samplingMode", String.valueOf(samplingMode));
        body.put("queryModifier", queryModifier);
        if (start != null) {
            body.put("start", config.getTimeConfig().formatRequest(start));
        }
        if (end != null) {
            body.put("end", config.getTimeConfig().formatRequest(end));
        }
        if (intervalMs != null) {
            body.put("intervalMs", intervalMs);
        }
        if (count != null) {
            body.put("count", count);
        }
        if (calculationMode != null) {
            body.put("calculationMode", String.valueOf(calculationMode));
        }
        if (direction != null) {
            body.put("direction", String.valueOf(direction == Direction.BACKWARD ? 0 : 1));
        }
        body.put("tagNames", joinTagsPlain(tagNames));
        return parseData(invoke(GeEndpoint.GET_SAMPLED_BY_REQUEST_PARAM_POST, null, null, body));
    }

    /**
     * 区间增量（复刻旧 SDK {@code getIntervalValueBySumTag}）。
     *
     * <p>算法与旧 SDK 逐行对齐：发两次 POST calculated 请求——
     * {@code calculationMode=17}（FirstRawValue）与 {@code 19}（LastRawValue），
     * 均为 {@code count=1, intervalMs=1}（取整窗首/末值的惯用技巧）——
     * 然后对每个位号用 {@link BigDecimal} 计算 <b>末值 − 首值</b>。</p>
     *
     * <p>边界语义同样复刻旧 SDK {@code convertOneDataByTagNames}：
     * 位号无样本或首样本质量为 BAD（厂商质量码 0）时按 {@code 0} 参与计算；
     * 值文本不可解析为数字时抛 {@link TsdbDataException}；响应中缺失请求位号
     * 时同样抛 {@link TsdbDataException}（旧 SDK 此处为 NPE）。
     * 注意“BAD/空样本按 0”是旧 SDK 的业务转换层语义，与公共层
     * {@code Aggregation.INTERVAL_SUM} 的理想契约（空片不出点）存在差异，
     * 替换文档中作为已知语义差异说明。</p>
     *
     * @param tagNames 位号集合，元素不能为 {@code null}
     * @param start    区间起始时刻（含）
     * @param end      区间结束时刻（不含）
     * @return 位号 → 区间增量字符串（{@link BigDecimal#toPlainString()}），
     * 顺序与请求一致，非 {@code null}
     * @throws TsdbDataException 值不可解析或响应缺失位号时
     */
    public Map<String, String> readIntervalSum(Collection<String> tagNames, Instant start, Instant end) {
        List<GeModels.Series> firstSeries = readAggregated(tagNames, start, end, 17, 1, 1L);
        List<GeModels.Series> lastSeries = readAggregated(tagNames, start, end, 19, 1, 1L);
        Map<String, BigDecimal> firstValues = intervalSumValues(firstSeries, tagNames);
        Map<String, BigDecimal> lastValues = intervalSumValues(lastSeries, tagNames);
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (String tag : tagNames) {
            BigDecimal begin = firstValues.get(tag);
            BigDecimal finish = lastValues.get(tag);
            if (begin == null || finish == null) {
                throw new TsdbDataException("GE readIntervalSum response is missing tag: " + tag);
            }
            result.put(tag, finish.subtract(begin).toPlainString());
        }
        return result;
    }

    /**
     * 把 calculated 响应整理为“位号 → 首样本值”的 BigDecimal 映射，
     * 复刻旧 SDK {@code convertOneDataByTagNames} 的 BAD/空样本按 0 语义。
     *
     * @param series   厂商序列列表
     * @param tagNames 请求位号（用于响应未带 TagName 时按顺序兜底）
     * @return 位号 → 首样本值，非 {@code null}
     */
    private Map<String, BigDecimal> intervalSumValues(List<GeModels.Series> series, Collection<String> tagNames) {
        List<String> requested = new ArrayList<String>(tagNames);
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        for (int i = 0; i < series.size(); i++) {
            GeModels.Series one = series.get(i);
            String tag = one.getTagName() == null || one.getTagName().isEmpty()
                    ? (i < requested.size() ? requested.get(i) : null)
                    : one.getTagName();
            if (tag == null) {
                throw new TsdbDataException("GE readIntervalSum response item has no TagName at index " + i);
            }
            List<GeModels.Point> points = one.getPoints();
            if (points.isEmpty()) {
                values.put(tag, BigDecimal.ZERO);
                continue;
            }
            GeModels.Point first = points.get(0);
            if (first.getQuality() == 0) {
                values.put(tag, BigDecimal.ZERO);
                continue;
            }
            String text = first.getValue() == null ? "" : first.getValue().trim();
            try {
                values.put(tag, new BigDecimal(text));
            } catch (NumberFormatException e) {
                throw new TsdbDataException("GE readIntervalSum got non-numeric value '" + text
                        + "' for tag " + tag, e);
            }
        }
        return values;
    }

    public void write(String tagName, List<GeModels.WritePoint> points) {
        if (points == null || points.isEmpty()) {
            return;
        }
        JSONObject root = new JSONObject();
        root.put("TagName", tagName);
        JSONArray samples = new JSONArray();
        root.put("samples", samples);
        for (GeModels.WritePoint point : points) {
            JSONObject sample = new JSONObject();
            sample.put("Quality", point.getQuality());
            sample.put("TimeStamp", config.getTimeConfig().formatRequest(point.getTimestamp()));
            sample.put("Value", point.getValue() == null ? "" : point.getValue());
            samples.add(sample);
        }
        String body = root.toJSONString();
        String response = http.post(config.apiUrl("/v1/datapoints/create"),
                authHeaders(tokens.getToken()), body, null);
        validate(response, "write");
    }

    public HealthStatus checkHealth() {
        long start = System.nanoTime();
        try {
            listTags(1, null);
            return HealthStatus.up((System.nanoTime() - start) / 1000000L)
                    .withTimeFormat(config.getTimeConfig());
        } catch (RuntimeException e) {
            return HealthStatus.down(e.getMessage(), (System.nanoTime() - start) / 1000000L)
                    .withTimeFormat(config.getTimeConfig());
        }
    }

    private JSONObject read(String url, String operation) {
        int attempts = Math.max(1, config.getMaxReadAttempts());
        TsdbAuthenticationException last = null;
        for (int i = 0; i < attempts; i++) {
            String token = tokens.getToken();
            try {
                String body = http.get(url, authHeaders(token));
                return validate(body, operation);
            } catch (TsdbAuthenticationException e) {
                last = e;
                tokens.invalidate(token);
                if (config.getStaticToken() != null) {
                    break;
                }
            }
        }
        if (last != null) {
            throw last;
        }
        throw new TsdbAuthenticationException("GE " + operation + " failed to authenticate");
    }

    private JSONObject validate(String body, String operation) {
        Object parsed = JsonText.parse(body);
        if (!(parsed instanceof JSONObject)) {
            throw new TsdbDataException("GE " + operation + " returned a non-object response");
        }
        JSONObject root = (JSONObject) parsed;
        Integer parsedCode = JsonText.integer(root, "ErrorCode");
        int errorCode = parsedCode == null ? 0 : parsedCode;
        if (errorCode != 0) {
            // GE 在标签检索命中数量超过 maxNumber 时返回 ErrorCode=11
            //（"Refine Search Results."），但 Tags 中仍带有已截断的结果；
            // 数据查询也可能以 11 表示部分结果。只要响应里确实有数据，就按成功处理。
            if (errorCode == 11 && hasPayload(root)) {
                return root;
            }
            String message = JsonText.text(root, "ErrorMessage");
            throw new TsdbQueryException("GE " + operation + " failed: ErrorCode=" + errorCode
                    + (message == null || message.isEmpty() ? "" : " " + message));
        }
        return root;
    }

    private boolean hasPayload(Object root) {
        Object tags = JsonText.value(root, "Tags");
        if (tags instanceof JSONArray && !((JSONArray) tags).isEmpty()) {
            return true;
        }
        Object data = JsonText.value(root, "Data");
        return data instanceof JSONArray && !((JSONArray) data).isEmpty();
    }

    private List<GeModels.Series> parseData(Object rootValue) {
        JSONObject root = JsonText.object(rootValue);
        Object dataValue = JsonText.value(root, "Data");
        if (!(dataValue instanceof JSONArray)) {
            return Collections.emptyList();
        }
        JSONArray data = (JSONArray) dataValue;
        List<GeModels.Series> series = new ArrayList<GeModels.Series>(data.size());
        for (Object itemValue : data) {
            JSONObject item = JsonText.object(itemValue);
            String tagName = text(item, "TagName");
            String dataType = text(item, "DataType");
            Integer parsedErrorCode = JsonText.integer(item, "ErrorCode");
            int errorCode = parsedErrorCode == null ? 0 : parsedErrorCode;
            String errorMessage = text(item, "ErrorMessage");
            String units = text(item, "EngineeringUnits");
            String hiUnits = text(item, "HiEngineeringUnits");
            String loUnits = text(item, "LoEngineeringUnits");
            List<GeModels.Point> points = new ArrayList<GeModels.Point>();
            Object samplesValue = JsonText.value(item, "Samples");
            if (samplesValue instanceof JSONArray) {
                for (Object sampleValue : (JSONArray) samplesValue) {
                    JSONObject sample = JsonText.object(sampleValue);
                    String rawTime = text(sample, "TimeStamp");
                    Instant timestamp;
                    try {
                        timestamp = config.getTimeConfig().parseResponse(rawTime);
                    } catch (TsdbDataException e) {
                        throw new TsdbDataException("GE response sample has unparseable TimeStamp '"
                                + rawTime + "' for tag " + tagName, e);
                    }
                    if (timestamp == null) {
                        // 公共 Sample 要求非空时间戳；静默丢点会掩盖数据不一致，
                        // 因此这里显式失败，把原始时间文本暴露给调用方。
                        throw new TsdbDataException("GE response sample has unparseable TimeStamp '"
                                + rawTime + "' for tag " + tagName);
                    }
                    Integer parsedQuality = JsonText.integer(sample, "Quality");
                    points.add(new GeModels.Point(timestamp, text(sample, "Value"),
                            parsedQuality == null ? 0 : parsedQuality, dataType, config.getTimeConfig()));
                }
            }
            series.add(new GeModels.Series(tagName, dataType, points,
                    errorCode == 0 ? null : errorCode, errorMessage, units, hiUnits, loUnits));
        }
        return series;
    }

    private Map<String, String> authHeaders(String token) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Authorization", "Bearer " + token);
        return headers;
    }

    /**
     * 执行一次带认证的请求；401 时刷新 token 后重试。
     */
    private JSONObject execute(String method, String url, String body, String operation) {
        int attempts = Math.max(1, config.getMaxReadAttempts());
        TsdbAuthenticationException last = null;
        for (int i = 0; i < attempts; i++) {
            String token = tokens.getToken();
            try {
                String response;
                if ("GET".equalsIgnoreCase(method)) {
                    response = http.get(url, authHeaders(token));
                } else if ("POST".equalsIgnoreCase(method)) {
                    response = http.post(url, authHeaders(token), body, null);
                } else if ("PUT".equalsIgnoreCase(method)) {
                    response = http.put(url, authHeaders(token), body);
                } else if ("DELETE".equalsIgnoreCase(method)) {
                    response = http.delete(url, authHeaders(token), body);
                } else {
                    throw new TsdbDataException("unsupported GE HTTP method: " + method);
                }
                return validate(response, operation);
            } catch (TsdbAuthenticationException e) {
                last = e;
                tokens.invalidate(token);
                if (config.getStaticToken() != null) {
                    break;
                }
            }
        }
        if (last != null) {
            throw last;
        }
        throw new TsdbAuthenticationException("GE " + operation + " failed to authenticate");
    }

    /**
     * 将请求体编码为 JSON 字符串。String 视为已经序列化好的 JSON，
     * fastjson 树对象直接输出，其余对象交给 fastjson。
     */
    private String encodeBody(Object body) {
        if (body == null) {
            return null;
        }
        if (body instanceof String) {
            return (String) body;
        }
        return JsonText.toJson(body);
    }

    private static String text(Object node, String field) {
        return JsonText.text(node, field);
    }

    /**
     * 把位号集合拼成<b>明文</b>分号分隔字符串，专供 POST 请求体的
     * {@code tagNames} 字段使用（与旧 SDK {@code TagNamesEntity} 一致，
     * 不做 URL 编码——JSON 体里的位号无需编码）。
     *
     * @param tags 位号集合，元素不能为 {@code null}
     * @return 明文分号拼接结果；集合为空时返回空串
     */
    private static String joinTagsPlain(Collection<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String tag : tags) {
            if (out.length() > 0) {
                out.append(';');
            }
            out.append(tag);
        }
        return out.toString();
    }

    /**
     * 把位号集合拼成 URL 编码的分号分隔字符串，仅供 GET 查询串使用。
     *
     * @param tags 位号集合
     * @return URL 编码后的分号拼接结果；集合为空时返回空串
     */
    private static String encodeTags(Collection<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String tag : tags) {
            if (out.length() > 0) {
                out.append(';');
            }
            out.append(encode(tag));
        }
        return out.toString();
    }

    private static String encode(String value) {
        if (value == null) {
            return "";
        }
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new TsdbDataException("UTF-8 is unavailable", e);
        }
    }
}
