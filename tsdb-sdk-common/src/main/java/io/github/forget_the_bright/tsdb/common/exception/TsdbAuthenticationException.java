package io.github.forget_the_bright.tsdb.common.exception;

/**
 * 鉴权失败异常：凭证缺失、凭证错误、凭证过期、或账号权限不足。
 *
 * <p>固定使用错误码 {@link TsdbErrorCode#AUTH}（字符串码 {@code "TSDB-AUTH"}），
 * 属于<b>不可重试</b>错误：原样重试不会改变结果，需要检查用户名/密码、
 * 令牌有效期或服务端授权配置。</p>
 *
 * <p>使用场景：登录接口返回 401/403、令牌校验失败、访问了当前账号无权访问的
 * 位号或数据库。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public class TsdbAuthenticationException extends TsdbException {

    /**
     * 序列化版本号。
     */
    private static final long serialVersionUID = 1L;

    /**
     * 构造鉴权失败异常。
     *
     * @param message 错误描述，建议包含“哪个实例、哪个账号、服务端返回了什么”，
     *                允许为 {@code null}
     */
    public TsdbAuthenticationException(String message) {
        super(TsdbErrorCode.AUTH, message);
    }

    /**
     * 构造鉴权失败异常并保留底层原因。
     *
     * @param message 错误描述，允许为 {@code null}
     * @param cause   导致鉴权失败的底层异常（如 HTTP 401 响应解析异常），
     *                允许为 {@code null}
     */
    public TsdbAuthenticationException(String message, Throwable cause) {
        super(TsdbErrorCode.AUTH, message, cause);
    }
}
