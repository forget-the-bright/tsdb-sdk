package io.github.forget_the_bright.tsdb.common.exception;

/**
 * 数据错误异常：数据内容非法或响应数据无法解析。
 *
 * <p>固定使用错误码 {@link TsdbErrorCode#DATA}（字符串码 {@code "TSDB-DATA"}），
 * 属于<b>不可重试</b>错误：同样的数据再发一次仍会失败，需要修正数据或修复解析逻辑。</p>
 *
 * <p>使用场景：写入的点类型与位号声明类型不匹配、时间戳超出可表示范围、
 * 质量码无法映射、服务端返回的报文结构不符合预期（字段缺失、JSON 解析失败）等。</p>
 *
 * <p>与 {@link TsdbQueryException} 的区别：本异常强调的是“数据/报文本身有问题”，
 * 而查询异常强调的是“查询条件或服务端执行有问题”。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public class TsdbDataException extends TsdbException {

    /**
     * 序列化版本号。
     */
    private static final long serialVersionUID = 1L;

    /**
     * 构造数据错误异常。
     *
     * @param message 错误描述，建议指出哪个位号/哪个字段的数据有问题，
     *                允许为 {@code null}
     */
    public TsdbDataException(String message) {
        super(TsdbErrorCode.DATA, message);
    }

    /**
     * 构造数据错误异常并保留底层原因。
     *
     * @param message 错误描述，允许为 {@code null}
     * @param cause   导致解析失败的底层异常（如 {@code JsonProcessingException}），
     *                允许为 {@code null}
     */
    public TsdbDataException(String message, Throwable cause) {
        super(TsdbErrorCode.DATA, message, cause);
    }
}
