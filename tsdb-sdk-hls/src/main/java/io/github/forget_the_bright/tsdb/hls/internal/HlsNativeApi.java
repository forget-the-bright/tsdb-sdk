package io.github.forget_the_bright.tsdb.hls.internal;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.github.forget_the_bright.tsdb.common.exception.TsdbAuthenticationException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbConnectionException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbDataException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbQueryException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbTimeoutException;
import io.github.forget_the_bright.tsdb.common.internal.json.JsonText;
import io.github.forget_the_bright.tsdb.common.model.Aggregation;
import io.github.forget_the_bright.tsdb.hls.HlsConfig;
import io.github.forget_the_bright.tsdb.hls.nativeapi.dto.HistorianRequest;
import io.github.forget_the_bright.tsdb.hls.nativeapi.dto.TagNameListRequest;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 和利时 HLS 原生 REST API 客户端。
 *
 * <p>本类直接对应原 HLS_REST_SDK 的 4 个接口：
 * <code>GET /ddb/read_alltagname</code>、
 * <code>POST /ddb/read_ddbtagvalue</code>、
 * <code>POST /hdb/read_hdbtagvalue</code>、
 * <code>GET /user/get_token</code>。它不做公共模型归一化，
 * 返回厂商原始 JSON 结构，保证原生能力完整可用。</p>
 *
 * <p>同时保留少量类型化便捷方法（{@link #listTags()}、
 * {@link #readCurrent(Collection)}、{@link #readHistory}）供内部驱动使用；
 * 需要完全按厂商字段取值时，使用返回 {@link JSONObject} 的
 * <code>queryAllTags()</code>/<code>getDDBTagValue</code>/<code>getHDBTagValue</code>。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class HlsNativeApi {

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final HlsConfig config;
    private final HlsHttpClient http;
    private final HlsTokenManager tokens;

    public HlsNativeApi(HlsConfig config) {
        this.config = config;
        this.http = new HlsHttpClient(config);
        this.tokens = new HlsTokenManager(config, http);
    }

    public Object rawClient() {
        return http;
    }

    /**
     * 查询全部标签，返回 HLS 原始 JSON。
     *
     * <p>对应 <code>GET /ddb/read_alltagname</code>，响应形如：
     * <code>{"code":0,"msg":"...","Data":{"TagNameList":[{"TagName":"...","TagDes":"..."}]}}</code>。
     * 原 SDK 的 {@code queryAllTags()} 返回 {@code TagsResult}，本方法保留
     * 同样的接口语义，但返回未裁剪的 JSON，调用方可读取任意扩展字段。</p>
     *
     * @return 原始响应 JSON
     */
    public JSONObject queryAllTags() {
        return parse(execute("GET", "/ddb/read_alltagname", null));
    }

    /**
     * 读取标签实时值，返回 HLS 原始 JSON。
     *
     * <p>对应 <code>POST /ddb/read_ddbtagvalue</code>。请求体使用
     * {@link TagNameListRequest} 精确复刻厂商结构；响应包含
     * <code>Data.DDBTagValueList[]</code>，每项含 Quality、TagType、
     * TagValue、TagValueTime 等字段。</p>
     *
     * @param request 标签列表请求
     * @return 原始响应 JSON
     */
    public JSONObject getDDBTagValue(TagNameListRequest request) {
        return postJson("/ddb/read_ddbtagvalue", request);
    }

    /**
     * 读取标签实时值（字符串集合便捷重载）。
     *
     * @param tagNames 标签名集合
     * @return 原始响应 JSON
     */
    public JSONObject getDDBTagValue(Collection<String> tagNames) {
        return getDDBTagValue(new TagNameListRequest(tagNames));
    }

    /**
     * 读取标签历史聚合值，返回 HLS 原始 JSON。
     *
     * <p>对应 <code>POST /hdb/read_hdbtagvalue</code>。响应包含
     * <code>Data.HDBTagValueList[]</code>，每项含
     * <code>OneTagHDBValueList[]</code>，聚合列为
     * <code>TagValue_AVG/Bound/MIN/MAX</code>，质量列为
     * <code>Quality_Bound/MIN/MAX</code>（厂商未提供 Quality_AVG）。</p>
     *
     * @param request 历史查询请求
     * @return 原始响应 JSON
     */
    public JSONObject getHDBTagValue(HistorianRequest request) {
        return postJson("/hdb/read_hdbtagvalue", request);
    }

    /**
     * 读取历史聚合值（参数化便捷重载）。
     *
     * @param start          开始时间
     * @param end            结束时间
     * @param intervalSeconds 聚合间隔（秒）
     * @param needQueryBound 是否需要边界值
     * @param needQueryAVG   是否需要平均值
     * @param needQueryMIN   是否需要最小值
     * @param needQueryMAX   是否需要最大值
     * @param tagNames       标签名
     * @return 原始响应 JSON
     */
    public JSONObject getHDBTagValue(Instant start, Instant end, Long intervalSeconds,
                                   Boolean needQueryBound, Boolean needQueryAVG,
                                   Boolean needQueryMIN, Boolean needQueryMAX,
                                   String... tagNames) {
        return getHDBTagValue(HistorianRequest.buildRequest(start, end, intervalSeconds,
                needQueryAVG, needQueryMIN, needQueryMAX, needQueryBound, tagNames));
    }

    /**
     * 查询边界值。
     *
     * @param start           开始时间
     * @param end             结束时间
     * @param intervalSeconds 聚合间隔（秒）
     * @param tagNames        标签名
     * @return 原始响应 JSON
     */
    public JSONObject getHDBTagValueBound(Instant start, Instant end, Long intervalSeconds, String... tagNames) {
        return getHDBTagValue(start, end, intervalSeconds, true, false, false, false, tagNames);
    }

    /**
     * 查询边界值（集合重载）。
     *
     * @param start           开始时间
     * @param end             结束时间
     * @param intervalSeconds 聚合间隔（秒）
     * @param tagNames        标签名集合
     * @return 原始响应 JSON
     */
    public JSONObject getHDBTagValueBound(Instant start, Instant end, Long intervalSeconds, Collection<String> tagNames) {
        return getHDBTagValue(HistorianRequest.buildRequest(start, end, intervalSeconds,
                false, false, false, true, tagNames));
    }

    /**
     * 查询最大值。
     *
     * @param start           开始时间
     * @param end             结束时间
     * @param intervalSeconds 聚合间隔（秒）
     * @param tagNames        标签名
     * @return 原始响应 JSON
     */
    public JSONObject getHDBTagValueMax(Instant start, Instant end, Long intervalSeconds, String... tagNames) {
        return getHDBTagValue(start, end, intervalSeconds, false, false, false, true, tagNames);
    }

    /**
     * 查询最大值（集合重载）。
     *
     * @param start           开始时间
     * @param end             结束时间
     * @param intervalSeconds 聚合间隔（秒）
     * @param tagNames        标签名集合
     * @return 原始响应 JSON
     */
    public JSONObject getHDBTagValueMax(Instant start, Instant end, Long intervalSeconds, Collection<String> tagNames) {
        return getHDBTagValue(HistorianRequest.buildRequest(start, end, intervalSeconds,
                false, false, true, false, tagNames));
    }

    /**
     * 查询最小值。
     *
     * @param start           开始时间
     * @param end             结束时间
     * @param intervalSeconds 聚合间隔（秒）
     * @param tagNames        标签名
     * @return 原始响应 JSON
     */
    public JSONObject getHDBTagValueMin(Instant start, Instant end, Long intervalSeconds, String... tagNames) {
        return getHDBTagValue(start, end, intervalSeconds, false, false, true, false, tagNames);
    }

    /**
     * 查询最小值（集合重载）。
     *
     * @param start           开始时间
     * @param end             结束时间
     * @param intervalSeconds 聚合间隔（秒）
     * @param tagNames        标签名集合
     * @return 原始响应 JSON
     */
    public JSONObject getHDBTagValueMin(Instant start, Instant end, Long intervalSeconds, Collection<String> tagNames) {
        return getHDBTagValue(HistorianRequest.buildRequest(start, end, intervalSeconds,
                false, true, false, false, tagNames));
    }

    /**
     * 查询平均值。
     *
     * @param start           开始时间
     * @param end             结束时间
     * @param intervalSeconds 聚合间隔（秒）
     * @param tagNames        标签名
     * @return 原始响应 JSON
     */
    public JSONObject getHDBTagValueAvg(Instant start, Instant end, Long intervalSeconds, String... tagNames) {
        return getHDBTagValue(start, end, intervalSeconds, false, true, false, false, tagNames);
    }

    /**
     * 查询平均值（集合重载）。
     *
     * @param start           开始时间
     * @param end             结束时间
     * @param intervalSeconds 聚合间隔（秒）
     * @param tagNames        标签名集合
     * @return 原始响应 JSON
     */
    public JSONObject getHDBTagValueAvg(Instant start, Instant end, Long intervalSeconds, Collection<String> tagNames) {
        return getHDBTagValue(HistorianRequest.buildRequest(start, end, intervalSeconds,
                true, false, false, false, tagNames));
    }

    /**
     * 查询全部聚合列（AVG/MIN/MAX/Bound）。
     *
     * @param start           开始时间
     * @param end             结束时间
     * @param intervalSeconds 聚合间隔（秒）
     * @param tagNames        标签名
     * @return 原始响应 JSON
     */
    public JSONObject getHDBTagValueAll(Instant start, Instant end, Long intervalSeconds, String... tagNames) {
        return getHDBTagValue(start, end, intervalSeconds, true, true, true, true, tagNames);
    }

    /**
     * 查询全部聚合列（集合重载）。
     *
     * @param start           开始时间
     * @param end             结束时间
     * @param intervalSeconds 聚合间隔（秒）
     * @param tagNames        标签名集合
     * @return 原始响应 JSON
     */
    public JSONObject getHDBTagValueAll(Instant start, Instant end, Long intervalSeconds, Collection<String> tagNames) {
        return getHDBTagValue(HistorianRequest.buildRequest(start, end, intervalSeconds,
                true, true, true, true, tagNames));
    }

    /**
     * 通用原生调用入口，用于后续新增端点或厂商扩展。
     *
     * @param method HTTP 方法，支持 GET/POST
     * @param path   相对 baseUrl 的路径
     * @param body   请求体对象，GET 时可传 null
     * @return 原始响应 JSON
     */
    public Object rawRequest(String method, String path, Object body) {
        String payload = body == null ? null : toJson(body);
        return JsonText.parse(execute(method, path, payload));
    }

    private JSONObject postJson(String path, Object body) {
        return parse(execute("POST", path, toJson(body)));
    }

    private String toJson(Object value) {
        return JsonText.toJson(value);
    }

    /**
     * 执行一次轻量的连通性/鉴权探测。
     *
     * <p>使用静态 token 时没有可刷新的凭证，退化为调用位号目录接口验证服务可用；
     * 使用动态 token 时刷新一次 token，验证 {@code userid}/{@code secretkey}
     * 仍然有效。两种方式都不写数据，可频繁调用。</p>
     *
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbException
     *         当网络、认证或响应解析失败时
     */
    public void checkHealth() {
        if (config.getStaticToken() != null) {
            listTags();
        } else {
            tokens.refresh();
        }
    }

    /**
     * 读取全部位号（标签）目录。
     *
     * <p>对应厂商接口 {@code GET /ddb/read_alltagname}。响应中的
     * {@code Data.TagNameList[]} 被解析为 {@link HlsModels.Tag}，保留
     * {@code TagName} 与 {@code TagDes}；缺失或为空的位号会被跳过。HLS 没有
     * 服务端分页/掩码过滤，调用方需要自行缓存或过滤。</p>
     *
     * @return 位号列表；厂商返回空集合时返回空列表，不返回 {@code null}
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbException
     *         网络、认证或响应解析失败时
     */
    public List<HlsModels.Tag> listTags() {
        String body = execute("GET", "/ddb/read_alltagname", null);
        JSONObject root = parse(body);
        Object data = data(root);
        Object listValue = data == null ? null : first(data, "TagNameList");
        if (listValue == null && data instanceof JSONArray) {
            listValue = data;
        }
        List<HlsModels.Tag> tags = new ArrayList<HlsModels.Tag>();
        if (!(listValue instanceof JSONArray)) {
            return tags;
        }
        for (Object itemValue : (JSONArray) listValue) {
            JSONObject item = JsonText.object(itemValue);
            String name = text(item, "TagName");
            if (name == null || name.trim().isEmpty()) {
                continue;
            }
            tags.add(new HlsModels.Tag(name, text(item, "TagDes")));
        }
        return tags;
    }

    /**
     * 批量读取位号当前值（DDB 实时库）。
     *
     * <p>对应厂商接口 {@code POST /ddb/read_ddbtagvalue}，请求体为
     * {@code {"TagNameList":[{"TagName":"..."}]}}。响应按请求顺序给出
     * {@code DDBTagValueList[]}；若厂商未回填 {@code TagName}，按请求索引补齐。
     * 质量码、类型码与时间戳保持厂商原值，由调用方解释。</p>
     *
     * @param tagNames 位号集合，不能为 {@code null}；空集合会发送空请求并返回空列表
     * @return 与响应顺序一致的当前值列表；无数据时为空列表
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbException
     *         网络、认证或响应解析失败时
     */
    public List<HlsModels.CurrentValue> readCurrent(Collection<String> tagNames) {
        JSONObject request = new JSONObject();
        JSONArray list = new JSONArray();
        for (String tagName : tagNames) {
            JSONObject item = new JSONObject();
            item.put("TagName", tagName);
            list.add(item);
        }
        request.put("TagNameList", list);
        String body = execute("POST", "/ddb/read_ddbtagvalue", request.toJSONString());
        JSONObject root = parse(body);
        Object data = data(root);
        Object listValue = data == null ? null : first(data, "DDBTagValueList");
        if (listValue == null && data instanceof JSONArray) {
            listValue = data;
        }
        List<String> requested = new ArrayList<String>(tagNames);
        List<HlsModels.CurrentValue> result = new ArrayList<HlsModels.CurrentValue>();
        if (!(listValue instanceof JSONArray)) {
            return result;
        }
        int index = 0;
        for (Object itemValue : (JSONArray) listValue) {
            JSONObject item = JsonText.object(itemValue);
            String tagName = text(item, "TagName");
            if (tagName == null && index < requested.size()) {
                tagName = requested.get(index);
            }
            result.add(new HlsModels.CurrentValue(
                    tagName,
                    integer(item, "Quality"),
                    integer(item, "TagType"),
                    text(item, "TagValue"),
                    instant(first(item, "TagValueTime")),
                    config.getTimeConfig()));
            index++;
        }
        return result;
    }

    /**
     * 读取区间聚合历史值（HDB 历史库，宽表）。
     *
     * <p>对应厂商接口 {@code POST /hdb/read_hdbtagvalue}。一次请求只打开一个
     * 聚合开关（{@code NeedQueryAVG/MIN/MAX/Bound}），返回的
     * {@code OneTagHDBValueList[]} 仍包含四列值；本方法按传入的
     * {@code aggregation} 取对应列，其余列忽略。质量码同样按列取
     * {@code Quality_AVG} 不存在，AVG 查询的质量为 {@code null}（由映射层
     * 解释为 UNKNOWN），不要在这里猜测。</p>
     *
     * @param start           区间起点（含）
     * @param end             区间终点（不含）
     * @param intervalSeconds 聚合间隔，单位秒；厂商按秒解释
     * @param aggregation     聚合类型，仅支持 AVG/MIN/MAX/BOUND
     * @param tagNames        位号集合
     * @return 按位号组织的宽表序列
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbException
     *         网络、认证或响应解析失败时
     */
    public List<HlsModels.Series> readHistory(Instant start, Instant end, long intervalSeconds,
                                              Aggregation aggregation, Collection<String> tagNames) {
        JSONObject request = new JSONObject();
        request.put("startTime", start.getEpochSecond());
        request.put("endTime", end.getEpochSecond());
        request.put("interval", intervalSeconds);
        request.put("NeedQueryAVG", aggregation == Aggregation.AVG);
        request.put("NeedQueryMIN", aggregation == Aggregation.MIN);
        request.put("NeedQueryMAX", aggregation == Aggregation.MAX);
        request.put("NeedQueryBound", aggregation == Aggregation.BOUND);
        JSONArray list = new JSONArray();
        for (String tagName : tagNames) {
            JSONObject item = new JSONObject();
            item.put("TagName", tagName);
            list.add(item);
        }
        request.put("TagNameList", list);
        String body = execute("POST", "/hdb/read_hdbtagvalue", request.toJSONString());
        JSONObject root = parse(body);
        Object data = data(root);
        Object listValue = data == null ? null : first(data, "HDBTagValueList");
        if (listValue == null && data instanceof JSONArray) {
            listValue = data;
        }
        List<String> requested = new ArrayList<String>(tagNames);
        List<HlsModels.Series> series = new ArrayList<HlsModels.Series>();
        if (!(listValue instanceof JSONArray)) {
            return series;
        }
        for (Object itemValue : (JSONArray) listValue) {
            JSONObject item = JsonText.object(itemValue);
            String tagName = text(item, "TagName");
            if (tagName == null) {
                Integer idx = integer(item, "Index");
                if (idx != null) {
                    if (idx >= 0 && idx < requested.size()) {
                        tagName = requested.get(idx);
                    } else if (idx >= 1 && idx <= requested.size()) {
                        tagName = requested.get(idx - 1);
                    }
                }
            }
            if (tagName == null) {
                continue;
            }
            HlsModels.Series one = new HlsModels.Series(tagName, integer(item, "TagType"));
            Object pointsValue = first(item, "OneTagHDBValueList");
            if (pointsValue instanceof JSONArray) {
                for (Object pointValue : (JSONArray) pointsValue) {
                    JSONObject point = JsonText.object(pointValue);
                    one.points.add(new HlsModels.Point(
                            instant(first(point, "TagValueTime")),
                            text(point, "TagValue_AVG"),
                            text(point, "TagValue_Bound"),
                            text(point, "TagValue_MIN"),
                            text(point, "TagValue_MAX"),
                            integer(point, "Quality_Bound"),
                            integer(point, "Quality_MIN"),
                            integer(point, "Quality_MAX"),
                            config.getTimeConfig()));
                }
            }
            series.add(one);
        }
        return series;
    }

    private String execute(String method, String path, String requestBody) {
        TsdbException last = null;
        for (int attempt = 1; attempt <= config.getMaxReadAttempts(); attempt++) {
            try {
                Map<String, String> headers = tokens.tokenHeader();
                if ("GET".equals(method)) {
                    return http.get(path, headers);
                }
                return http.post(path, headers, requestBody);
            } catch (TsdbAuthenticationException e) {
                tokens.invalidate();
                last = e;
            } catch (TsdbConnectionException | TsdbTimeoutException e) {
                last = e;
            }
            if (attempt < config.getMaxReadAttempts()) {
                sleep(config.getRetryBackoffMillis());
            }
        }
        throw last == null ? new TsdbConnectionException("HLS request failed") : last;
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static JSONObject parse(String body) {
        return JsonText.parseObject(body);
    }

    private static Object data(Object root) {
        return first(root, "Data");
    }

    /**
     * 按字段名读取原始值，忽略大小写。
     *
     * <p>父节点不是对象（例如数组或标量）时返回 {@code null}，与迁移前的
     * JSON 树行为一致：数组没有命名字段，不存在“取不到就报错”的语义。</p>
     */
    private static Object first(Object parent, String field) {
        if (!(parent instanceof Map)) {
            return null;
        }
        return JsonText.valueIgnoreCase(parent, field);
    }

    private static String text(Object parent, String field) {
        Object value = first(parent, field);
        if (value == null) {
            return null;
        }
        if (value instanceof Map || value instanceof Collection) {
            return "";
        }
        return String.valueOf(value);
    }

    private static Integer integer(Object parent, String field) {
        Object value = first(parent, field);
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof Map || value instanceof Collection) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Instant instant(Object node) {
        if (node == null) {
            return null;
        }
        if (node instanceof Number) {
            return fromEpoch(((Number) node).longValue());
        }
        if (node instanceof Map || node instanceof Collection) {
            return null;
        }
        String text = String.valueOf(node).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return fromEpoch(Long.parseLong(text));
        } catch (NumberFormatException ignored) {
            // 不是 epoch 数字，继续尝试格式化时间字符串。
        }
        try {
            LocalDateTime dateTime = LocalDateTime.parse(text, DATE_TIME);
            return dateTime.atZone(ZoneId.systemDefault()).toInstant();
        } catch (RuntimeException e) {
            throw new TsdbDataException("HLS timestamp is not supported: " + text);
        }
    }

    private static Instant fromEpoch(long value) {
        if (value == 0L) {
            return null;
        }
        long abs = Math.abs(value);
        if (abs < 100000000000L) {
            return Instant.ofEpochSecond(value);
        }
        return Instant.ofEpochMilli(value);
    }

    /**
     * 释放底层连接资源。
     *
     * <p>关闭后本对象不应继续使用；由 {@code HlsClient} 持有的实例会在客户端
     * 关闭时一并关闭。重复调用是安全的（底层关闭幂等）。</p>
     */
    public void close() {
        http.close();
    }
}
