package io.github.forget_the_bright.tsdb.hls.internal;

import io.github.forget_the_bright.tsdb.common.internal.http.HttpErrors;
import io.github.forget_the_bright.tsdb.common.internal.http.HttpResult;
import io.github.forget_the_bright.tsdb.common.internal.http.HttpTransport;
import io.github.forget_the_bright.tsdb.hls.HlsConfig;

import java.util.Map;

/**
 * HLS REST 接口的 HTTP 传输层，构建在公共层 {@link HttpTransport}（Hutool）之上。
 *
 * <p>职责：把相对路径交给 {@link HlsConfig#url(String)} 拼成完整地址，调用共享
 * 传输层收发，并把非 2xx 状态翻译为公共异常。本类不做业务字段拼装或 JSON 解析。</p>
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>收发细节（超时、重定向、Accept、UTF-8 请求体、错误流读取）统一由
 *       {@link HttpTransport} 实现；</li>
 *   <li>所有请求（含无请求体的 GET）都显式写入精确的
 *       {@code Content-Type: application/json}——部分现场代理只接受不带 charset
 *       的精确值，请求体本身仍按 UTF-8 编码；</li>
 *   <li>不自动跟随重定向，避免携带 token 的请求被 3xx 引导到非预期主机；</li>
 *   <li>配置 {@code verify-hostname=false} 时安装恒真主机名校验器，仅用于自签
 *       证书或内网地址场景；</li>
 *   <li>错误响应体进入异常消息前截断到 512 字符。</li>
 * </ul>
 *
 * <p>线程安全：本类只有 {@code final} 的配置与传输字段，所有请求状态都在方法局部
 * 变量中，可被多线程并发使用。</p>
 *
 * <p>与原生层的关系：{@link HlsNativeApi} 持有本类实例完成实际网络调用，
 * {@link HlsTokenManager} 也复用同一实例获取 token。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class HlsHttpClient implements AutoCloseable {

    /**
     * 严格 JSON 的 {@code Content-Type}。
     *
     * <p>部分 HLS 现场（或现场反向代理）只接受精确的
     * {@code application/json}，带上 {@code charset=UTF-8} 反而返回
     * {@code code=4002 Content type is not json}。所有 GET/POST 统一使用该值；
     * JSON 本身按 UTF-8 编码，中文位号不会因此乱码。</p>
     */
    static final String STRICT_JSON_CONTENT_TYPE = "application/json";

    /**
     * 驱动配置，提供基地址、超时与主机名校验开关；构造后不再变化。
     */
    private final HlsConfig config;

    /** 公共传输层，承载超时、重定向、TLS 与请求体的统一语义。 */
    private final HttpTransport transport;

    /**
     * 创建传输层实例。
     *
     * @param config 驱动配置，不能为 {@code null}，需已完成必填项校验
     */
    public HlsHttpClient(HlsConfig config) {
        this.config = config;
        this.transport = new HttpTransport(
                "HLS",
                config.getConnectTimeoutMillis(),
                config.getReadTimeoutMillis(),
                config.isVerifyHostname(),
                null);
    }

    /**
     * 发起 GET 请求并返回原始响应体。
     *
     * <p>适用于 HLS 的目录查询与 token 获取接口。请求会自动附加
     * {@code Accept: application/json} 与精确的 {@code Content-Type:
     * application/json}，调用方只需提供业务头（例如 token 头）。</p>
     *
     * @param path    相对路径（例如 {@code "/ddb/read_alltagname"}），由
     *                {@link HlsConfig#url(String)} 拼接为完整 URL
     * @param headers 额外请求头，可为 {@code null}；键或值为 {@code null} 的项
     *                会被忽略
     * @return 非 {@code null} 的响应体文本（UTF-8 解码），2xx 时返回
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbTimeoutException        当连接或读取超时时
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbAuthenticationException 当服务端返回 401/403 时
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbConnectionException     当网络失败或返回其它非 2xx 状态码时
     */
    public String get(String path, Map<String, String> headers) {
        return request("GET", path, headers, null, STRICT_JSON_CONTENT_TYPE);
    }

    /**
     * 发起 POST 请求并返回原始响应体。
     *
     * <p>请求体按 UTF-8 编码写出，并自动附加精确的
     * {@code Content-Type: application/json}（会覆盖调用方传入的同名头）。</p>
     *
     * @param path    相对路径（例如 {@code "/hdb/read_hdbtagvalue"}）
     * @param headers 额外请求头，可为 {@code null}；键或值为 {@code null} 的项
     *                会被忽略
     * @param body    请求体 JSON；为 {@code null} 时不发送请求体
     * @return 非 {@code null} 的响应体文本（UTF-8 解码），2xx 时返回
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbTimeoutException        当连接或读取超时时
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbAuthenticationException 当服务端返回 401/403 时
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbConnectionException     当网络失败或返回其它非 2xx 状态码时
     */
    public String post(String path, Map<String, String> headers, String body) {
        return request("POST", path, headers, body, STRICT_JSON_CONTENT_TYPE);
    }

    /**
     * 使用显式 {@code Content-Type} 发起 POST 请求。
     *
     * <p>用于对 {@code Content-Type} 敏感的老服务或反向代理：必须使用精确的
     * {@code application/json}，不能带 charset。业务接口默认已使用该值，
     * 本重载主要用于需要显式指定头的场景。</p>
     *
     * @param path        相对路径
     * @param headers     额外请求头，可为 {@code null}
     * @param body        请求体 JSON
     * @param contentType 完整 Content-Type；为 {@code null} 时不写该头
     * @return 非 {@code null} 的响应体文本
     */
    public String post(String path, Map<String, String> headers, String body, String contentType) {
        return request("POST", path, headers, body, contentType);
    }

    /**
     * 执行一次 HTTP 请求并完成异常归一化。
     *
     * <p>2xx 直接返回正文；非 2xx 交给 {@link HttpErrors} 映射为认证、超时或
     * 连接异常。异常消息中的路径保持相对路径写法，便于与现场日志对照。</p>
     *
     * @param method      HTTP 方法，仅使用 {@code "GET"} 或 {@code "POST"}
     * @param path        相对路径
     * @param headers     额外请求头，可为 {@code null}
     * @param body        请求体，为 {@code null} 时不发送
     * @param contentType 请求体类型，可为 {@code null}
     * @return 非 {@code null} 的响应体文本
     */
    private String request(String method, String path, Map<String, String> headers, String body,
                           String contentType) {
        HttpResult result = transport.execute(method, config.url(path), headers, body, contentType);
        if (result.isSuccessful()) {
            return result.getBody();
        }
        throw HttpErrors.map("HLS", method, path, result.getStatus(), result.getBody());
    }

    /**
     * 释放资源。
     *
     * <p>本实现不维护连接池或后台线程，因此本方法为空实现。保留它是为了满足
     * {@link AutoCloseable} 契约，让上层可以用统一方式关闭原生资源。</p>
     */
    @Override
    public void close() {
        // 无连接池等需要释放的资源。
    }
}
