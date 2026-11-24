package io.github.forget_the_bright.tsdb.pspace.internal;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.github.forget_the_bright.tsdb.common.exception.TsdbException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbQueryException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException;
import io.github.forget_the_bright.tsdb.common.internal.http.HttpErrors;
import io.github.forget_the_bright.tsdb.common.internal.http.HttpResult;
import io.github.forget_the_bright.tsdb.common.internal.http.HttpTransport;
import io.github.forget_the_bright.tsdb.common.internal.json.JsonText;
import io.github.forget_the_bright.tsdb.pspace.PspaceConfig;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * pSpace 的 HTTP 协议层：编码、信封解析与错误映射。
 *
 * <p>统一的协议处理：</p>
 * <ul>
 *   <li>请求体、响应正文与 URL 参数统一按 {@link PspaceConfig#getCharset()} 编码
 *       （默认 GBK）；{@code Content-Type} 固定为 {@code application/json}，
 *       不追加 charset；</li>
 *   <li>响应兼容两种外层：{@code {code,mesg,data}} 信封（0 成功、-1 失败）与
 *       第 2 章多个端点直接返回的裸数据。信封成功时返回 {@code data} 的 JSON 文本，
 *       裸数据原样返回；</li>
 *   <li>信封语义：{@code code=0} 成功并返回 {@code data}；{@code code=1} 表示
 *       “时间范围内没有数据”，归一化为空文本而不是异常；其它非 0 码（含缺失
 *       {@code code}）抛 {@link TsdbQueryException}，并把 {@code code} 写入异常的
 *       厂商错误码；</li>
 *   <li>非 2xx 状态码交给 {@link HttpErrors} 统一映射；</li>
 *   <li>{@code archived=1}（zlib）本期不支持，出现即抛
 *       {@link TsdbUnsupportedException}，不静默退回未压缩读取。</li>
 * </ul>
 *
 * <p><b>非公共 API</b>：随内部重构变化，宿主应用请勿直接依赖。</p>
 *
 * @author wanghao
 * @since 0.1.2
 */
public final class PspaceHttp {

    /** 异常消息中的厂商前缀。 */
    private static final String VENDOR = "pSpace";

    /** 请求固定内容类型：pSpace 现场要求严格的 application/json。 */
    private static final String CONTENT_TYPE = "application/json";

    /** 请求固定请求体：POST /Refresh 需要。 */
    public static final String EMPTY_JSON = "{}";

    private final PspaceConfig config;
    private final HttpTransport transport;

    /**
     * @param config 已校验的配置，不能为 {@code null}
     */
    public PspaceHttp(PspaceConfig config) {
        this.config = config;
        this.transport = new HttpTransport(VENDOR, config.getConnectTimeoutMillis(),
                config.getReadTimeoutMillis(), config.isVerifyHostname(), null);
    }

    /**
     * 返回配置。
     *
     * @return 非 {@code null} 的配置
     */
    public PspaceConfig config() {
        return config;
    }

    /**
     * 执行 GET 请求。
     *
     * @param path  相对路径，如 {@code "/ServerInfo"}
     * @param query 查询参数，顺序保留；允许为 {@code null}
     * @return 解包后的数据文本（信封 {@code data} 的 JSON 或裸响应正文）
     */
    public String get(String path, Map<String, String> query) {
        return execute("GET", path, query, null);
    }

    /**
     * 执行 POST 请求。
     *
     * @param path 相对路径
     * @param body JSON 请求体，允许为 {@code null}
     * @return 解包后的数据文本
     */
    public String post(String path, String body) {
        return execute("POST", path, null, body);
    }

    /**
     * 执行 PUT 请求。
     *
     * @param path  相对路径
     * @param query 查询参数，允许为 {@code null}
     * @param body  JSON 请求体，允许为 {@code null}
     * @return 解包后的数据文本
     */
    public String put(String path, Map<String, String> query, String body) {
        return execute("PUT", path, query, body);
    }

    /**
     * 执行 DELETE 请求。
     *
     * @param path  相对路径
     * @param query 查询参数，允许为 {@code null}
     * @return 解包后的数据文本
     */
    public String delete(String path, Map<String, String> query) {
        return execute("DELETE", path, query, null);
    }

    /**
     * 执行 GET 并把结果解析为 JSON 对象。
     *
     * @param path  相对路径
     * @param query 查询参数，允许为 {@code null}
     * @return JSON 对象；空响应返回空对象，非对象响应抛 {@link TsdbQueryException}
     */
    public JSONObject getObject(String path, Map<String, String> query) {
        return asObject(get(path, query), "GET", path);
    }

    /**
     * 执行任意方法的 HTTP 调用并解包。
     *
     * @param method HTTP 方法
     * @param path   相对路径
     * @param query  查询参数，允许为 {@code null}
     * @param body   JSON 请求体，允许为 {@code null}
     * @return 解包后的数据文本
     * @throws TsdbUnsupportedException 当查询参数带 {@code archived=1} 时
     * @throws TsdbException            传输或协议失败时
     */
    public String execute(String method, String path, Map<String, String> query, String body) {
        if (query != null && "1".equals(query.get("archived"))) {
            throw new TsdbUnsupportedException(
                    "pSpace archived=1 (zlib compressed response) is not supported");
        }
        String url = buildUrl(path, query);
        HttpResult result = transport.execute(method, url, new LinkedHashMap<String, String>(),
                body, CONTENT_TYPE, config.getCharset(), config.getCharset());
        if (!result.isSuccessful()) {
            throw HttpErrors.map(VENDOR, method, url, result.getStatus(), result.getBody());
        }
        return unwrap(method, path, result.getBody());
    }

    /**
     * 把响应正文解包成数据文本。
     *
     * <p>裸数组/裸对象直接返回；只有同时含 {@code code} 的对象才按信封处理。
     * {@code code=0} 返回 {@code data} 的 JSON 文本，{@code code=1} 返回空文本，
     * 其它码抛异常。{@code data} 为字符串时原样返回，避免二次序列化。</p>
     *
     * @param method HTTP 方法（用于异常消息）
     * @param path   相对路径（用于异常消息）
     * @param body   响应正文，可为 {@code null}
     * @return 数据文本，非 {@code null}
     */
    private String unwrap(String method, String path, String body) {
        if (body == null || body.trim().isEmpty()) {
            return "";
        }
        String text = body.trim();
        if (!text.startsWith("{")) {
            return text;
        }
        JSONObject object;
        try {
            object = JsonText.parseObject(text);
        } catch (RuntimeException e) {
            return text;
        }
        if (object == null || !object.containsKey("code")) {
            return text;
        }
        Integer code = JsonText.integer(object, "code");
        // 7.1 读接口在“时间范围内没有数据”时返回 code=1 并附空 data，
        // 这是业务空结果而不是失败；只有其它非 0 码才映射为异常。
        if (code == null || (code.intValue() != 0 && code.intValue() != 1)) {
            TsdbQueryException e = new TsdbQueryException(VENDOR + " " + method + " " + path
                    + " failed: code=" + code + ", mesg=" + JsonText.text(object, "mesg"));
            e.withContext(null, "pspace", path, code == null ? null : String.valueOf(code));
            throw e;
        }
        if (!object.containsKey("data")) {
            return code.intValue() == 1 ? "" : text;
        }
        Object data = object.get("data");
        if (data == null) {
            return "";
        }
        if (data instanceof String) {
            return (String) data;
        }
        return JsonText.toJson(data);
    }

    /**
     * 判断异常或消息是否属于“pSpace 缓存未热”的表现。
     *
     * <p>已知表现：{@code /PointChildren} 报 {@code Parent (0) , Not find children}，
     * {@code /RealData} 报 {@code No Valid Data}，或目录查询只返回根节点。</p>
     *
     * @param message 异常消息，可为 {@code null}
     * @return 命中返回 {@code true}
     */
    public static boolean looksCold(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("not find children")
                || lower.contains("no valid data")
                || lower.contains("not found point");
    }

    /**
     * 把数据文本解析为 JSON 对象。
     *
     * @param text   数据文本，可为 {@code null}
     * @param method 方法名（异常消息）
     * @param path   路径（异常消息）
     * @return 非 {@code null} 的对象；空文本返回空对象
     */
    public static JSONObject asObject(String text, String method, String path) {
        if (text == null || text.trim().isEmpty()) {
            return new JSONObject();
        }
        try {
            return JsonText.parseObject(text);
        } catch (RuntimeException e) {
            throw new TsdbQueryException(VENDOR + " " + method + " " + path
                    + " returned non-object JSON: " + text);
        }
    }

    /**
     * 把数据文本解析为 JSON 数组。
     *
     * @param text 数据文本，可为 {@code null}
     * @return 非 {@code null} 的数组；空文本返回空数组
     */
    public static JSONArray asArray(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new JSONArray();
        }
        try {
            return JsonText.parseArray(text);
        } catch (RuntimeException e) {
            JSONObject object = JsonText.parseObject(text);
            if (object != null && object.containsKey("values")) {
                return JsonText.array(object.get("values"));
            }
            throw new TsdbQueryException(VENDOR + " returned non-array JSON: " + text);
        }
    }

    /**
     * 拼接请求 URL：保留参数顺序，键值按配置字符集编码，
     * {@code null} 值跳过；路径自带 {@code ?} 时改用 {@code &} 续接。
     *
     * @param path  相对路径
     * @param query 查询参数，可为 {@code null}
     * @return 完整 URL
     */
    private String buildUrl(String path, Map<String, String> query) {
        String base = config.url(path);
        if (query == null || query.isEmpty()) {
            return base;
        }
        StringBuilder sb = new StringBuilder(base);
        char separator = base.indexOf('?') >= 0 ? '&' : '?';
        for (Map.Entry<String, String> entry : query.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            sb.append(separator)
                    .append(encode(entry.getKey()))
                    .append('=')
                    .append(encode(entry.getValue()));
            separator = '&';
        }
        return sb.toString();
    }

    /**
     * 按配置字符集对 URL 参数做百分号编码（pSpace 现场为 GBK）。
     *
     * @param value 原值，非 {@code null}
     * @return 编码后的文本
     */
    private String encode(String value) {
        try {
            return URLEncoder.encode(value, config.getCharset().name());
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("unsupported charset: " + config.getCharset(), e);
        }
    }
}