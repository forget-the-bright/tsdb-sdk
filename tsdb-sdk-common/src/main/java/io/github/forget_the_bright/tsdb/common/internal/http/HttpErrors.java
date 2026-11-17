package io.github.forget_the_bright.tsdb.common.internal.http;

import io.github.forget_the_bright.tsdb.common.exception.TsdbAuthenticationException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbConnectionException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbTimeoutException;
import io.github.forget_the_bright.tsdb.common.internal.Texts;

/**
 * 非 2xx 状态码到公共异常的映射。
 *
 * <p>默认规则：{@code 401/403} → 认证异常；{@code 408/504} → 超时异常；
 * 其余（含未跟随的 3xx）→ 连接异常。驱动可通过
 * {@link #map(String, String, String, int, String, int...)} 追加厂商自定义的
 * 认证状态码，例如 IoTDB 的 {@code 600/603}。</p>
 *
 * <p>异常消息格式为 {@code "<厂商> <方法> <URL> returned HTTP <状态>[ : <截断正文>]"}，
 * 与迁移前三个驱动客户端的消息保持一致。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class HttpErrors {

    private HttpErrors() {
    }

    /**
     * 按默认规则映射状态码。
     *
     * @param vendor 厂商前缀，如 {@code "GE"}
     * @param method HTTP 方法
     * @param url    请求地址或路径
     * @param status HTTP 状态码
     * @param body   响应正文，可为 {@code null}
     * @return 待抛出的异常实例
     */
    public static TsdbException map(String vendor, String method, String url,
                                    int status, String body) {
        return map(vendor, method, url, status, body, new int[0]);
    }

    /**
     * 映射状态码，并追加厂商自定义的认证失败码。
     *
     * @param vendor         厂商前缀，如 {@code "IoTDB"}
     * @param method         HTTP 方法
     * @param url            请求地址或路径
     * @param status         HTTP 状态码
     * @param body           响应正文，可为 {@code null}
     * @param authStatuses   额外的认证失败状态码，可为空
     * @return 待抛出的异常实例
     */
    public static TsdbException map(String vendor, String method, String url,
                                    int status, String body, int... authStatuses) {
        String detail = vendor + " " + method + " " + url + " returned HTTP " + status
                + (body == null || body.isEmpty() ? "" : ": " + Texts.abbreviate(body));
        if (status == 401 || status == 403 || contains(authStatuses, status)) {
            return new TsdbAuthenticationException(detail);
        }
        if (status == 408 || status == 504) {
            return new TsdbTimeoutException(detail);
        }
        return new TsdbConnectionException(detail);
    }

    private static boolean contains(int[] values, int target) {
        if (values == null) {
            return false;
        }
        for (int value : values) {
            if (value == target) {
                return true;
            }
        }
        return false;
    }
}
