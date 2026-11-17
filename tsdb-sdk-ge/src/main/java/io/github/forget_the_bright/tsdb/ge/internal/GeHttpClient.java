package io.github.forget_the_bright.tsdb.ge.internal;

import io.github.forget_the_bright.tsdb.common.internal.http.BasicAuth;
import io.github.forget_the_bright.tsdb.common.internal.http.HttpErrors;
import io.github.forget_the_bright.tsdb.common.internal.http.HttpResult;
import io.github.forget_the_bright.tsdb.common.internal.http.HttpTransport;
import io.github.forget_the_bright.tsdb.common.internal.http.TrustAllSsl;
import io.github.forget_the_bright.tsdb.ge.GeConfig;

import java.util.Map;

/**
 * GE Historian 的 HTTP 传输层，构建在公共层 {@link HttpTransport}（Hutool）之上。
 *
 * <p>职责：为 {@link GeNativeApi} 与 {@link GeTokenManager} 提供统一的
 * {@code get/post/put/delete} 文本请求能力，并把非 2xx 状态码翻译为公共层的
 * {@link io.github.forget_the_bright.tsdb.common.exception.TsdbException} 家族
 * （认证、超时、连接）。</p>
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>收发细节（超时、重定向、Accept、UTF-8 请求体、错误流读取）由
 *       {@link HttpTransport} 统一实现，本类只保留 GE 特有的头与状态映射；</li>
 *   <li>有请求体时默认 {@code Content-Type: application/json; charset=UTF-8}，
 *       {@code Accept} 固定为 {@code application/json}；</li>
 *   <li>不自动跟随重定向，3xx 会被当作失败返回，防止凭据被转发到未预期的主机；</li>
 *   <li>关闭主机名校验时（配置显式要求）安装恒真主机名校验器；显式开启
 *       {@code options.trust-all-certificates} 时使用 {@link TrustAllSsl}，
 *       构造时会打印 WARN；默认关闭；</li>
 *   <li>错误响应体截断到 512 字符后再拼进异常消息，控制日志体积并降低
 *       敏感数据外泄风险。</li>
 * </ul>
 *
 * <p>线程安全性：本类只有 {@code final} 的配置字段与不可变的传输实例，每个请求
 * 独立执行，可被多线程同时使用。</p>
 *
 * <p>与原生层的关系：只负责“把字节搬进搬出”，不理解 GE 的业务语义
 * （OAuth、端点路径、JSON 结构均由上层处理）。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class GeHttpClient implements AutoCloseable {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(GeHttpClient.class);

    /** 带请求体时的默认 {@code Content-Type}。 */
    private static final String JSON = "application/json; charset=UTF-8";

    /** 公共传输层，承载超时、重定向、TLS 与请求体的统一语义。 */
    private final HttpTransport transport;

    /**
     * 用给定配置构造传输层。
     *
     * <p>构造期不建立任何连接；超时与安全选项在此固化到 {@link HttpTransport}，
     * 因此后续行为不受外部配置对象变化影响。</p>
     *
     * @param config 连接配置，不能为 {@code null}
     */
    public GeHttpClient(GeConfig config) {
        if (config.isTrustAllCertificates()) {
            LOG.warn("GE HTTPS certificate validation is DISABLED for instance '{}'; "
                    + "use only for self-signed intranet endpoints and re-enable it afterwards",
                    config.getInstanceName());
        }
        this.transport = new HttpTransport(
                "GE",
                config.getConnectTimeoutMillis(),
                config.getReadTimeoutMillis(),
                config.isVerifyHostname(),
                config.isTrustAllCertificates() ? TrustAllSsl.factory() : null);
    }

    /**
     * 发送 GET 请求。
     *
     * @param absoluteUrl 完整 URL（已包含主机、端口与路径），不能为 {@code null}
     * @param headers    附加请求头，可为 {@code null}；键或值为 {@code null} 的条目会被跳过
     * @return 响应正文（UTF-8），2xx 时返回，可能为空串
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbAuthenticationException 服务端返回 401/403
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbTimeoutException        连接/读取超时，或服务端返回 408/504
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbConnectionException     其他非 2xx 状态，或网络层失败
     */
    public String get(String absoluteUrl, Map<String, String> headers) {
        return request("GET", absoluteUrl, headers, null, null);
    }

    /**
     * 发送 POST 请求。
     *
     * @param absoluteUrl 完整 URL，不能为 {@code null}
     * @param headers     附加请求头，可为 {@code null}
     * @param body        请求体文本，可为 {@code null}（此时不发送请求体）
     * @param contentType 请求体类型；为 {@code null} 时使用
     *                    {@code application/json; charset=UTF-8}
     * @return 响应正文（UTF-8），可能为空串
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbAuthenticationException 服务端返回 401/403
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbTimeoutException        超时或 408/504
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbConnectionException     其他非 2xx 状态或网络失败
     */
    public String post(String absoluteUrl, Map<String, String> headers, String body, String contentType) {
        return request("POST", absoluteUrl, headers, body, contentType == null ? JSON : contentType);
    }

    /**
     * 发送 PUT 请求，请求体固定按 JSON 声明。
     *
     * @param absoluteUrl 完整 URL，不能为 {@code null}
     * @param headers     附加请求头，可为 {@code null}
     * @param body        请求体文本，可为 {@code null}
     * @return 响应正文（UTF-8），可能为空串
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbAuthenticationException 服务端返回 401/403
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbTimeoutException        超时或 408/504
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbConnectionException     其他非 2xx 状态或网络失败
     */
    public String put(String absoluteUrl, Map<String, String> headers, String body) {
        return request("PUT", absoluteUrl, headers, body, JSON);
    }

    /**
     * 发送 DELETE 请求。GE 的删除类接口允许在 DELETE 请求中携带 JSON 请求体，
     * 因此这里保留 body 参数；不需要请求体时传 {@code null}。
     *
     * @param absoluteUrl 完整 URL，不能为 {@code null}
     * @param headers     附加请求头，可为 {@code null}
     * @param body        JSON 请求体，可为 {@code null}
     * @return 响应正文（UTF-8），可能为空串
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbAuthenticationException 服务端返回 401/403
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbTimeoutException        超时或 408/504
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbConnectionException     其他非 2xx 状态或网络失败
     */
    public String delete(String absoluteUrl, Map<String, String> headers, String body) {
        return request("DELETE", absoluteUrl, headers, body, JSON);
    }

    /**
     * 执行一次请求并把非 2xx 状态翻译为公共异常。
     *
     * @param method       HTTP 方法名
     * @param absoluteUrl  完整 URL
     * @param headers      附加请求头，可为 {@code null}
     * @param body         请求体，可为 {@code null}
     * @param contentType  请求体类型，可为 {@code null}
     * @return 2xx 响应正文（UTF-8）
     */
    private String request(String method, String absoluteUrl, Map<String, String> headers,
                           String body, String contentType) {
        HttpResult result = transport.execute(method, absoluteUrl, headers, body, contentType);
        if (result.isSuccessful()) {
            return result.getBody();
        }
        throw HttpErrors.map("GE", method, absoluteUrl, result.getStatus(), result.getBody());
    }

    /**
     * 生成 HTTP Basic 认证头的值。
     *
     * <p>按 {@code user:password} 拼接后做 Base64（UTF-8 字节），{@code null}
     * 参数按空串处理。GE 的 OAuth 令牌端点要求客户端凭据以此方式传递。</p>
     *
     * @param user     用户名/客户端标识，可为 {@code null}
     * @param password 口令/客户端密钥，可为 {@code null}
     * @return 形如 {@code "Basic xxxxx"} 的请求头值，非 {@code null}
     */
    public static String basic(String user, String password) {
        return BasicAuth.header(user, password);
    }

    /**
     * 释放传输层资源。
     *
     * <p>本类不持有连接池或后台线程，因此为空操作；保留它是为了支持
     * try-with-resources 写法。重复调用无副作用。</p>
     */
    @Override
    public void close() {
        // 无池化资源。
    }
}
