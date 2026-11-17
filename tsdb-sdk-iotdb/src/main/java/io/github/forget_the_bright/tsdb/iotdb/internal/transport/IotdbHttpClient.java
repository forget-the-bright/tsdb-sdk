package io.github.forget_the_bright.tsdb.iotdb.internal.transport;

import io.github.forget_the_bright.tsdb.common.internal.http.BasicAuth;
import io.github.forget_the_bright.tsdb.common.internal.http.HttpErrors;
import io.github.forget_the_bright.tsdb.common.internal.http.HttpResult;
import io.github.forget_the_bright.tsdb.common.internal.http.HttpTransport;
import io.github.forget_the_bright.tsdb.iotdb.IotdbConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * IoTDB REST 服务的 HTTP 传输层，构建在公共层 {@link HttpTransport}（Hutool）之上。
 *
 * <p><b>职责</b>：拼装 Basic 认证头，调用共享传输层收发，并把 HTTP 状态码映射为
 * 公共层异常。它不关心 SQL、JSON 结构或业务语义，因此上层 {@link IotdbRestApi}
 * 只需拼 URL 与解析字符串。</p>
 *
 * <p><b>设计要点</b>：</p>
 * <ul>
 *   <li>超时、重定向、Accept、UTF-8 请求体、错误流读取由 {@link HttpTransport}
 *       统一实现，本类只保留 IoTDB 特有的认证头与状态码规则；</li>
 *   <li>{@link #post} 自动附加 {@code Authorization: Basic ...} 头，凭据来自
 *       {@link IotdbConfig}，按 UTF-8 拼接后 Base64 编码；</li>
 *   <li>{@link #get} 接受调用方显式传入的头部，传 {@code null} 时完全不发认证头
 *       —— 这正是 {@code /ping} 探测的用法，因此 ping 成功不代表凭据有效；</li>
 *   <li>状态码 {@code 401/403} 以及 IoTDB 自定义的 {@code 600/603} 视为认证失败；
 *       {@code 408/504} 视为超时；其余视为连接/服务异常。</li>
 * </ul>
 *
 * <p><b>线程安全</b>：唯一字段是不可变的配置与传输实例，每次调用独立执行，
 * 不共享可变状态，可被多线程并发使用。</p>
 *
 * <p><b>与公共层/原生层的关系</b>：本类属于 {@code internal} 包，不是公共 API；
 * 只被 {@link IotdbRestApi} 使用，异常类型来自公共层。</p>
 *
 * <p><b>限制</b>：无重试、无 gzip 压缩、无代理配置；当
 * {@code verifyHostname=false} 时会接受任意主机名，存在中间人风险，
 * 仅应在自签名证书的测试环境使用；明文 {@code http} 下 Basic 凭据可被旁路窃听。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class IotdbHttpClient implements AutoCloseable {

    /**
     * 请求体媒体类型，带 UTF-8 字符集声明，仅用于 {@link #post}。
     */
    private static final String JSON = "application/json; charset=UTF-8";

    /**
     * IoTDB 在 HTTP 层表达认证失败的自定义状态码。
     */
    private static final int[] AUTH_STATUSES = {600, 603};

    /**
     * 传输配置，非 {@code null}；提供凭据。
     */
    private final IotdbConfig config;

    /** 公共传输层，承载超时、重定向、TLS 与请求体的统一语义。 */
    private final HttpTransport transport;

    /**
     * 创建 HTTP 传输层。
     *
     * @param config 驱动配置快照，不能为 {@code null}
     */
    public IotdbHttpClient(IotdbConfig config) {
        this.config = config;
        this.transport = new HttpTransport(
                "IoTDB",
                config.getConnectTimeoutMillis(),
                config.getReadTimeoutMillis(),
                config.isVerifyHostname(),
                null);
    }

    /**
     * 发起 GET 请求。
     *
     * <p>不会自动附加认证头：需要认证时由调用方通过 {@code headers} 传入；
     * 传 {@code null} 表示不带任何自定义头（{@code /ping} 即如此）。
     * 仅当状态码为 2xx 时返回响应体，否则抛出映射后的异常。</p>
     *
     * @param absoluteUrl 完整 URL，必须包含 scheme 与主机
     * @param headers     附加请求头，可为 {@code null}
     * @return 非 {@code null} 的响应体（UTF-8 解码；无响应体时为空串）
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbAuthenticationException
     *         当状态码为 401/403/600/603 时
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbTimeoutException
     *         当连接或读取超时，或状态码为 408/504 时
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbConnectionException
     *         当网络失败或返回其他非 2xx 状态码时
     */
    public String get(String absoluteUrl, Map<String, String> headers) {
        return request("GET", absoluteUrl, headers, null, null);
    }

    /**
     * 发起带 JSON 请求体的 POST 请求。
     *
     * <p>自动附加 Basic 认证头与 {@code Content-Type: application/json; charset=UTF-8}，
     * 请求体按 UTF-8 编码。调用方无法覆盖这两个头，因为本方法不暴露 header 参数。</p>
     *
     * @param absoluteUrl 完整 URL，必须包含 scheme 与主机
     * @param body        JSON 请求体，不能为 {@code null}
     * @return 非 {@code null} 的响应体（UTF-8 解码；无响应体时为空串）
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbAuthenticationException
     *         当状态码为 401/403/600/603 时
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbTimeoutException
     *         当连接或读取超时，或状态码为 408/504 时
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbConnectionException
     *         当网络失败或返回其他非 2xx 状态码时
     */
    public String post(String absoluteUrl, String body) {
        return request("POST", absoluteUrl, headers(), body, JSON);
    }

    /**
     * 执行一次 HTTP 调用并完成异常归一化。
     *
     * @param method      HTTP 方法，如 {@code "GET"}、{@code "POST"}
     * @param absoluteUrl 完整 URL
     * @param headers     自定义头，可为 {@code null}
     * @param body        请求体，为 {@code null} 时不发送请求体
     * @param contentType 请求体媒体类型，可为 {@code null}
     * @return 非 {@code null} 的 2xx 响应体
     */
    private String request(String method, String absoluteUrl, Map<String, String> headers,
                           String body, String contentType) {
        HttpResult result = transport.execute(method, absoluteUrl, headers, body, contentType);
        if (result.isSuccessful()) {
            return result.getBody();
        }
        throw HttpErrors.map("IoTDB", method, absoluteUrl, result.getStatus(), result.getBody(),
                AUTH_STATUSES);
    }

    /**
     * 构造 Basic 认证头。
     *
     * <p>把 {@code username:password} 按 UTF-8 编码后做 Base64，前缀
     * {@code "Basic "}，符合 RFC 7617 的 Basic 认证格式。IoTDB 只支持这一种
     * 认证方式，因此没有 Bearer 或自定义 token 的分支。</p>
     *
     * @return 仅含 {@code Authorization} 一项的可变头映射，非 {@code null}
     */
    private Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Authorization", BasicAuth.header(config.getUsername(), config.getPassword()));
        return headers;
    }

    /**
     * 关闭传输层。
     *
     * <p>本实现不持有连接池或后台线程，因此为空操作；保留
     * {@link AutoCloseable} 只是为了与上层资源管理方式一致。</p>
     */
    @Override
    public void close() {
        // 无池化资源，无需释放。
    }
}
