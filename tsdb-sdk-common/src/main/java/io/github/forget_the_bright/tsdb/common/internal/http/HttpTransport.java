package io.github.forget_the_bright.tsdb.common.internal.http;

import cn.hutool.core.exceptions.UtilException;
import cn.hutool.core.io.IORuntimeException;
import cn.hutool.http.HttpException;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.Method;
import io.github.forget_the_bright.tsdb.common.exception.TsdbConnectionException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbTimeoutException;

import javax.net.ssl.SSLSocketFactory;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * 基于 Hutool 的共享 HTTP 传输层。
 *
 * <p>只负责“发请求、读响应”，返回 {@link HttpResult}；不跟随重定向、不解释业务
 * 状态，也不做 JSON 解析。状态码到公共异常的映射由调用方通过
 * {@link HttpErrors} 完成，便于各驱动保留自己的认证码规则。</p>
 *
 * <p>固定语义：</p>
 * <ul>
 *   <li>{@code Accept: application/json}；</li>
 *   <li>不自动跟随重定向，3xx 作为普通响应返回；</li>
 *   <li>连接/读取超时从 {@code long} 毫秒安全收敛到 {@code int}；</li>
 *   <li>有请求体时按 UTF-8 编码；{@code contentType} 非空时精确写入，
 *       不会追加 charset（HLS 现场要求严格的 {@code application/json}）；</li>
 *   <li>{@code verifyHostname=false} 时安装恒真主机名校验器；</li>
 *   <li>显式传入 trust-all 工厂时使用它，否则沿用 JVM 默认信任链；</li>
 *   <li>响应正文按 UTF-8 解码；非 2xx 的错误流由 Hutool 统一读取。</li>
 * </ul>
 *
 * <p>实例不可变，可被多线程共享。底层是否为连接池由 Hutool 决定，本类不管理
 * 连接生命周期。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class HttpTransport {

    private static final String ACCEPT = "application/json";

    private final String vendor;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final boolean verifyHostname;
    private final SSLSocketFactory sslSocketFactory;

    /**
     * 创建传输层。
     *
     * @param vendor               异常消息中的厂商前缀，如 {@code "GE"}，不能为 {@code null}
     * @param connectTimeoutMillis 连接超时（毫秒），负数按下限 0 处理，超过
     *                             {@link Integer#MAX_VALUE} 截断
     * @param readTimeoutMillis    读取超时（毫秒），收敛规则同上
     * @param verifyHostname       是否校验 HTTPS 主机名
     * @param sslSocketFactory     显式 SSL 工厂；{@code null} 表示使用 JVM 默认
     */
    public HttpTransport(String vendor, long connectTimeoutMillis, long readTimeoutMillis,
                         boolean verifyHostname, SSLSocketFactory sslSocketFactory) {
        if (vendor == null) {
            throw new IllegalArgumentException("vendor must not be null");
        }
        this.vendor = vendor;
        this.connectTimeoutMillis = clampTimeout(connectTimeoutMillis);
        this.readTimeoutMillis = clampTimeout(readTimeoutMillis);
        this.verifyHostname = verifyHostname;
        this.sslSocketFactory = sslSocketFactory;
    }

    /**
     * 执行一次 HTTP 请求。
     *
     * @param method      HTTP 方法，大小写不敏感，如 {@code "GET"}、{@code "POST"}
     * @param url         完整 URL
     * @param headers     附加请求头，可为 {@code null}；键或值为 {@code null} 的项被跳过
     * @param body        请求体文本，可为 {@code null}（不发送请求体）
     * @param contentType 请求体类型；非 {@code null} 时精确写入，即使没有请求体
     * @return 响应状态码与 UTF-8 正文
     * @throws TsdbTimeoutException    连接或读取超时
     * @throws TsdbConnectionException 网络失败或 URL/协议错误
     */
    public HttpResult execute(String method, String url, Map<String, String> headers,
                              String body, String contentType) {
        try {
            HttpRequest request = HttpRequest.of(url)
                    .method(Method.valueOf(method.toUpperCase(Locale.ROOT)))
                    .setConnectionTimeout(connectTimeoutMillis)
                    .setReadTimeout(readTimeoutMillis)
                    .setFollowRedirects(false)
                    .header("Accept", ACCEPT);
            if (!verifyHostname) {
                request.setHostnameVerifier(TrustAllSsl.trustAllHostnames());
            }
            if (sslSocketFactory != null) {
                request.setSSLSocketFactory(sslSocketFactory);
            }
            if (headers != null) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    if (header.getKey() != null && header.getValue() != null) {
                        request.header(header.getKey(), header.getValue());
                    }
                }
            }
            if (contentType != null) {
                request.header("Content-Type", contentType);
            }
            if (body != null) {
                request.body(body.getBytes(StandardCharsets.UTF_8));
            }
            try (HttpResponse response = request.execute()) {
                byte[] bytes = response.bodyBytes();
                String text = bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
                return new HttpResult(response.getStatus(), text);
            }
        } catch (TsdbException e) {
            throw e;
        } catch (HttpException e) {
            throw translate(e, method, url);
        } catch (IORuntimeException e) {
            throw translate(e, method, url);
        } catch (UtilException e) {
            throw translate(e, method, url);
        }
    }

    private TsdbException translate(Throwable error, String method, String url) {
        String detail = vendor + " " + method + " " + url;
        if (causedByTimeout(error)) {
            return new TsdbTimeoutException(detail + " timed out", error);
        }
        return new TsdbConnectionException(detail + " failed: " + error.getMessage(), error);
    }

    private static boolean causedByTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static int clampTimeout(long millis) {
        if (millis <= 0) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, millis);
    }
}
