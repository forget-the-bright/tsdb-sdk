package io.github.forget_the_bright.tsdb.common.exception;

/**
 * 配置错误异常：配置项缺失、取值非法、类型无法转换、必填项为空等。
 *
 * <p>固定使用错误码 {@link TsdbErrorCode#CONFIG}（字符串码 {@code "TSDB-CONFIG"}），
 * 属于<b>不可重试</b>错误：这是使用方的问题，重试没有意义，必须修正配置
 * （例如配置文件、环境变量或代码里传入的参数）后重建客户端。</p>
 *
 * <p>使用场景：配置文件解析失败、{@code host} 为空、端口超出 1~65535、
 * 超时时间为负数、驱动类型未注册等。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public class TsdbConfigurationException extends TsdbException {

    /**
     * 序列化版本号。
     */
    private static final long serialVersionUID = 1L;

    /**
     * 构造配置错误异常。
     *
     * @param message 错误描述，建议指出具体是哪个配置项、期望什么、
     *                实际得到什么，允许为 {@code null}
     */
    public TsdbConfigurationException(String message) {
        super(TsdbErrorCode.CONFIG, message);
    }

    /**
     * 构造配置错误异常并保留底层原因。
     *
     * @param message 错误描述，允许为 {@code null}
     * @param cause   导致配置解析失败的底层异常（如 YAML 语法错误），
     *                允许为 {@code null}
     */
    public TsdbConfigurationException(String message, Throwable cause) {
        super(TsdbErrorCode.CONFIG, message, cause);
    }
}
