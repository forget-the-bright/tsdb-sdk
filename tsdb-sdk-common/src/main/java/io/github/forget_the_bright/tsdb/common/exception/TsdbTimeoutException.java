package io.github.forget_the_bright.tsdb.common.exception;

/**
 * 超时异常：请求已发出，但在约定时间内没有得到响应。
 *
 * <p>固定使用错误码 {@link TsdbErrorCode#TIMEOUT}（字符串码 {@code "TSDB-TIMEOUT"}），
 * 属于<b>可重试</b>错误：服务端可能只是暂时繁忙，退避后重试有机会成功。</p>
 *
 * <p>重要约束：超时<b>不代表请求没有生效</b>。读取类操作重试是安全的；
 * 写入类操作超时时，数据可能已经部分或全部落盘，盲目重试可能造成重复写入，
 * 调用方应结合业务幂等键或先查询确认后再决定是否重试。</p>
 *
 * <p>与 {@link TsdbConnectionException} 的区别：本异常表示连接已建立但等待
 * 响应超时；连接异常表示连不上或连接中断。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public class TsdbTimeoutException extends TsdbException {

    /**
     * 序列化版本号。
     */
    private static final long serialVersionUID = 1L;

    /**
     * 构造超时异常。
     *
     * @param message 错误描述，建议包含超时时长与具体操作，允许为 {@code null}
     */
    public TsdbTimeoutException(String message) {
        super(TsdbErrorCode.TIMEOUT, message);
    }

    /**
     * 构造超时异常并保留底层原因。
     *
     * @param message 错误描述，允许为 {@code null}
     * @param cause   导致超时的底层异常（如 {@code SocketTimeoutException}），
     *                允许为 {@code null}
     */
    public TsdbTimeoutException(String message, Throwable cause) {
        super(TsdbErrorCode.TIMEOUT, message, cause);
    }
}
