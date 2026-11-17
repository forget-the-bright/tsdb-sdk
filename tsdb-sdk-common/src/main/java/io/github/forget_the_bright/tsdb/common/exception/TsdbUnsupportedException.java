package io.github.forget_the_bright.tsdb.common.exception;

/**
 * 不支持异常：驱动未实现该能力，或厂商不支持所请求的操作/参数组合。
 *
 * <p>固定使用错误码 {@link TsdbErrorCode#UNSUPPORTED}（字符串码
 * {@code "TSDB-UNSUPPORTED"}），属于<b>不可重试</b>错误：重试不会让能力凭空出现。</p>
 *
 * <p>设计约束：SDK 约定“未声明支持的能力必须显式抛出本异常，而不是静默降级
 * 或返回空结果”。因此调用方在收到本异常时，应改用
 * {@code TsdbClient#capabilities()} 判断能力集合后再决定替代方案
 * （例如放弃聚合、改用原始历史查询自行聚合）。</p>
 *
 * <p>使用场景：向只支持原始历史的驱动请求聚合历史、对不支持删除的厂商调用
 * 删除接口、请求的填充策略/聚合函数在该厂商没有等价实现、
 * {@code unwrap} 请求一个驱动无法暴露的底层对象类型等。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public class TsdbUnsupportedException extends TsdbException {

    /**
     * 序列化版本号。
     */
    private static final long serialVersionUID = 1L;

    /**
     * 构造不支持异常。
     *
     * @param message 错误描述，建议写明“驱动类型 + 不支持的能力名”，
     *                便于调用方判断替代方案，允许为 {@code null}
     */
    public TsdbUnsupportedException(String message) {
        super(TsdbErrorCode.UNSUPPORTED, message);
    }

    /**
     * 构造不支持异常并保留底层原因。
     *
     * @param message 错误描述，允许为 {@code null}
     * @param cause   导致判定为不支持的底层异常，允许为 {@code null}
     */
    public TsdbUnsupportedException(String message, Throwable cause) {
        super(TsdbErrorCode.UNSUPPORTED, message, cause);
    }
}
