package io.github.forget_the_bright.tsdb.common.exception;

/**
 * 连接错误异常：建连失败、连接被断开、连接池耗尽、网络不可达等。
 *
 * <p>固定使用错误码 {@link TsdbErrorCode#CONNECTION}（字符串码 {@code "TSDB-CONN"}），
 * 属于<b>可重试</b>错误：通常由网络抖动、服务端重启或临时过载引起，
 * 按退避策略重试有机会成功。是否重试以及重试次数由配置层的重试配置决定。</p>
 *
 * <p>注意：本异常表示“没能建立或维持连接”，与
 * {@link TsdbTimeoutException}（连上了但等不到响应）语义不同；
 * 对于写入操作，连接中断时数据可能已部分落盘，重试前需评估幂等性。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public class TsdbConnectionException extends TsdbException {

    /**
     * 序列化版本号。
     */
    private static final long serialVersionUID = 1L;

    /**
     * 构造连接错误异常。
     *
     * @param message 错误描述，建议包含目标地址与失败阶段
     *                （建连/握手/断链），允许为 {@code null}
     */
    public TsdbConnectionException(String message) {
        super(TsdbErrorCode.CONNECTION, message);
    }

    /**
     * 构造连接错误异常并保留底层原因。
     *
     * @param message 错误描述，允许为 {@code null}
     * @param cause   导致连接失败的底层异常（如 {@code IOException}），
     *                允许为 {@code null}；保留它才能看到真实的网络错误
     */
    public TsdbConnectionException(String message, Throwable cause) {
        super(TsdbErrorCode.CONNECTION, message, cause);
    }
}
