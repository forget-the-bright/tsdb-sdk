package io.github.forget_the_bright.tsdb.common.internal.http;

/**
 * 一次 HTTP 调用的原始响应。
 *
 * <p>只承载状态码与 UTF-8 正文，不判断业务成败；状态码到公共异常的映射由
 * {@link HttpErrors} 完成。实例不可变，可安全跨线程读取。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class HttpResult {

    private final int status;
    private final String body;

    /**
     * 创建响应快照。
     *
     * @param status HTTP 状态码
     * @param body   响应正文，{@code null} 归一化为空串
     */
    public HttpResult(int status, String body) {
        this.status = status;
        this.body = body == null ? "" : body;
    }

    /**
     * 返回 HTTP 状态码。
     *
     * @return 状态码，例如 {@code 200}
     */
    public int getStatus() {
        return status;
    }

    /**
     * 返回 UTF-8 解码后的响应正文。
     *
     * @return 非 {@code null} 的正文；无正文时为空串
     */
    public String getBody() {
        return body;
    }

    /**
     * 判断是否为 2xx。
     *
     * @return 2xx 返回 {@code true}
     */
    public boolean isSuccessful() {
        return status >= 200 && status < 300;
    }
}
