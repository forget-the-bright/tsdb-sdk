package io.github.forget_the_bright.tsdb.common.model;

/**
 * 位号或采样点的归一化值类型。
 *
 * <p>用于标识一个采样点实际携带的是哪种 Java 类型的数据。由于
 * {@link Sample#getValue()} 返回的是 {@code Object}，读取方必须先看
 * {@link Sample#getValueType()} 再决定调用哪个强类型访问器
 * （{@link Sample#getDoubleValue()}、{@link Sample#getLongValue()} 等），
 * 不要直接对 {@code Object} 做强制类型转换。</p>
 *
 * <p>约定：</p>
 * <ul>
 *   <li>{@link #DOUBLE} 对应 {@code java.lang.Double}，用于模拟量；</li>
 *   <li>{@link #LONG} 对应 {@code java.lang.Long}，用于计数、累计量、
 *       以及需要精确整数语义的量（避免 double 精度丢失）；</li>
 *   <li>{@link #BOOLEAN} 对应 {@code java.lang.Boolean}，用于开关量/数字量；</li>
 *   <li>{@link #STRING} 对应 {@code java.lang.String}，用于文本型位号；</li>
 *   <li>{@link #BYTES} 对应 {@code byte[]}，用于二进制块（如波形、报文）。</li>
 * </ul>
 *
 * <p>本枚举不可变、线程安全。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public enum ValueType {
    /**
     * 双精度浮点数，对应 {@code java.lang.Double}。
     */
    DOUBLE,

    /**
     * 64 位有符号长整数，对应 {@code java.lang.Long}。
     */
    LONG,

    /**
     * 布尔值，对应 {@code java.lang.Boolean}。
     */
    BOOLEAN,

    /**
     * 字符串，对应 {@code java.lang.String}。
     */
    STRING,

    /**
     * 字节数组，对应 {@code byte[]}。注意该类型在 {@link Sample} 中是
     * 防御性拷贝的，每次读取都会得到新的数组副本。
     */
    BYTES,

    /**
     * 未知类型：驱动无法确定位号的值类型，或位号元数据缺失。
     * 此时 {@link Sample#getValue()} 可能为 {@code null}。
     */
    UNKNOWN
}
