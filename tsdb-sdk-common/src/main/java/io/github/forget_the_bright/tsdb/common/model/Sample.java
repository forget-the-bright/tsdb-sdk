package io.github.forget_the_bright.tsdb.common.model;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.ObjectUtil;
import io.github.forget_the_bright.tsdb.common.config.TimeConfig;

import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.Objects;

/**
 * 一个归一化后的采样点（时间戳 + 值 + 质量）。
 *
 * <p>这是 SDK 数据平面的最小单位：不管是当前值、历史值还是写回服务端的数据，
 * 都统一用本类表达，从而让上层代码不必关心厂商各自的数据结构。</p>
 *
 * <p><b>类型与取值</b>：值本身以 {@code Object} 保存，实际类型由
 * {@link #getValueType()} 声明。由于值可能是 {@code null}（表示“该时刻存在
 * 这个点，但没有有效值”），{@link #getValueType()} 与 {@link #isNull()} 必须
 * 分开判断：类型描述“应该是什么类型”，{@code isNull()} 描述“有没有值”。</p>
 *
 * <p><b>强类型访问器</b>：{@link #getDoubleValue()}、{@link #getLongValue()}、
 * {@link #getBooleanValue()}、{@link #getTextValue()}、{@link #getBytesValue()}
 * 都是“类型不匹配就返回 {@code null}”的宽松语义，不会做隐式数值转换，
 * 也不会抛 {@link ClassCastException}。例如一个 {@link ValueType#LONG} 的点调用
 * {@code getDoubleValue()} 会得到 {@code null} 而不是转成 double，
 * 调用方应先用 {@link #getValueType()} 分派。</p>
 *
 * <p><b>不可变性与线程安全</b>：时间戳、类型、质量在构造时做非空校验；
 * 值为 {@code byte[]} 时在构造与每次读取时都做防御性拷贝，因此外部无法通过
 * 修改原数组或返回的数组影响本对象。所有字段均为 {@code final}，
 * 实例完全不可变，可安全地在多线程间共享。</p>
 *
 * <p>构造请使用静态工厂方法（{@link #ofDouble}、{@link #ofLong}、
 * {@link #ofBoolean}、{@link #ofString}、{@link #ofBytes}、{@link #ofNull}），
 * 构造器为私有。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class Sample {

    /**
     * 采样时刻，非 {@code null}。
     */
    private final Instant timestamp;

    /**
     * 与 {@link #timestamp} 等价的 {@link Date} 视图，便于 Java/前端框架直接序列化。
     */
    private final Date timestampDate;

    /**
     * 已按实例 {@code time.response.*} 配置格式化好的时间文本，前端可直接展示。
     */
    private final String timestampText;

    /**
     * 值类型，非 {@code null}。
     */
    private final ValueType valueType;

    /**
     * 实际值，可能为 {@code null}（表示该点无有效值）。
     */
    private Object value;

    /**
     * 归一化质量等级，非 {@code null}，默认 {@link Quality#UNKNOWN}。
     */
    private final Quality quality;

    /**
     * 厂商原始质量码，可能为 {@code null}（驱动未提供时）。
     */
    private final Integer vendorQualityCode;

    /**
     * 私有构造器，由各静态工厂方法调用。
     *
     * @param timestamp         采样时刻，不能为 {@code null}
     * @param valueType         值类型，不能为 {@code null}
     * @param value             实际值，允许为 {@code null}
     * @param quality           质量等级，允许为 {@code null}（归一化为
     *                          {@link Quality#UNKNOWN}）
     * @param vendorQualityCode 厂商原始质量码，允许为 {@code null}
     * @throws NullPointerException 当 {@code timestamp} 或 {@code valueType}
     *                              为 {@code null} 时
     */
    private Sample(Instant timestamp, ValueType valueType, Object value,
                   Quality quality, Integer vendorQualityCode) {

        this(timestamp, Date.from(timestamp), timestamp.toString(),
                valueType, value, quality, vendorQualityCode);
    }

    /**
     * 完整构造器，额外接收已经计算好的 Date/展示文本。
     *
     * @param timestamp         采样时刻，不能为 {@code null}
     * @param timestampDate     采样时刻的 Date 视图，不能为 {@code null}
     * @param timestampText     已格式化的展示文本，不能为 {@code null}
     * @param valueType         值类型，不能为 {@code null}
     * @param value             实际值，允许为 {@code null}
     * @param quality           质量等级，允许为 {@code null}
     * @param vendorQualityCode 厂商原始质量码，允许为 {@code null}
     */
    private Sample(Instant timestamp, Date timestampDate, String timestampText,
                   ValueType valueType, Object value,
                   Quality quality, Integer vendorQualityCode) {
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
        this.timestampDate = Objects.requireNonNull(timestampDate, "timestampDate");
        this.timestampText = Objects.requireNonNull(timestampText, "timestampText");
        this.valueType = Objects.requireNonNull(valueType, "valueType");
        this.value = value;
        this.quality = quality == null ? Quality.UNKNOWN : quality;
        this.vendorQualityCode = vendorQualityCode;
    }

    /**
     * 创建双精度浮点类型的采样点。
     *
     * @param timestamp         采样时刻，不能为 {@code null}
     * @param value             采样值
     * @param quality           归一化质量等级，允许为 {@code null}
     *                          （归一化为 {@link Quality#UNKNOWN}）
     * @param vendorQualityCode 厂商原始质量码，允许为 {@code null}
     * @return 非 {@code null} 的采样点，{@link #getValueType()} 为
     * {@link ValueType#DOUBLE}
     * @throws NullPointerException 当 {@code timestamp} 为 {@code null} 时
     */
    public static Sample ofDouble(Instant timestamp, double value, Quality quality, Integer vendorQualityCode) {
        if (timestamp == null) timestamp = Instant.now();
        return new Sample(timestamp, ValueType.DOUBLE, value, quality, vendorQualityCode);
    }

    /**
     * 创建 64 位长整数类型的采样点。
     *
     * @param timestamp         采样时刻，不能为 {@code null}
     * @param value             采样值
     * @param quality           归一化质量等级，允许为 {@code null}
     *                          （归一化为 {@link Quality#UNKNOWN}）
     * @param vendorQualityCode 厂商原始质量码，允许为 {@code null}
     * @return 非 {@code null} 的采样点，{@link #getValueType()} 为
     * {@link ValueType#LONG}
     * @throws NullPointerException 当 {@code timestamp} 为 {@code null} 时
     */
    public static Sample ofLong(Instant timestamp, long value, Quality quality, Integer vendorQualityCode) {
        if (timestamp == null) timestamp = Instant.now();
        return new Sample(timestamp, ValueType.LONG, value, quality, vendorQualityCode);
    }

    /**
     * 创建布尔类型的采样点（开关量/数字量）。
     *
     * @param timestamp         采样时刻，不能为 {@code null}
     * @param value             采样值
     * @param quality           归一化质量等级，允许为 {@code null}
     *                          （归一化为 {@link Quality#UNKNOWN}）
     * @param vendorQualityCode 厂商原始质量码，允许为 {@code null}
     * @return 非 {@code null} 的采样点，{@link #getValueType()} 为
     * {@link ValueType#BOOLEAN}
     * @throws NullPointerException 当 {@code timestamp} 为 {@code null} 时
     */
    public static Sample ofBoolean(Instant timestamp, boolean value, Quality quality, Integer vendorQualityCode) {
        if (timestamp == null) timestamp = Instant.now();
        return new Sample(timestamp, ValueType.BOOLEAN, value, quality, vendorQualityCode);
    }

    /**
     * 创建字符串类型的采样点。
     *
     * @param timestamp         采样时刻，不能为 {@code null}
     * @param value             采样值，允许为 {@code null}（此时等价于
     *                          {@link #ofNull(Instant, ValueType, Quality, Integer)}
     *                          且类型为 {@link ValueType#STRING}）
     * @param quality           归一化质量等级，允许为 {@code null}
     *                          （归一化为 {@link Quality#UNKNOWN}）
     * @param vendorQualityCode 厂商原始质量码，允许为 {@code null}
     * @return 非 {@code null} 的采样点，{@link #getValueType()} 为
     * {@link ValueType#STRING}
     * @throws NullPointerException 当 {@code timestamp} 为 {@code null} 时
     */
    public static Sample ofString(Instant timestamp, String value, Quality quality, Integer vendorQualityCode) {
        if (timestamp == null) timestamp = Instant.now();
        return new Sample(timestamp, ValueType.STRING, value, quality, vendorQualityCode);
    }

    /**
     * 创建字节数组类型的采样点。
     *
     * <p>入参会在此处立即被复制一份，调用方之后修改传入数组不会影响本对象。</p>
     *
     * @param timestamp         采样时刻，不能为 {@code null}
     * @param value             采样值，允许为 {@code null}
     * @param quality           归一化质量等级，允许为 {@code null}
     *                          （归一化为 {@link Quality#UNKNOWN}）
     * @param vendorQualityCode 厂商原始质量码，允许为 {@code null}
     * @return 非 {@code null} 的采样点，{@link #getValueType()} 为
     * {@link ValueType#BYTES}
     * @throws NullPointerException 当 {@code timestamp} 为 {@code null} 时
     */
    public static Sample ofBytes(Instant timestamp, byte[] value, Quality quality, Integer vendorQualityCode) {
        if (timestamp == null) timestamp = Instant.now();
        return new Sample(timestamp, ValueType.BYTES,
                value == null ? null : Arrays.copyOf(value, value.length), quality, vendorQualityCode);
    }

    /**
     * 创建一个“有类型但没有值”的采样点。
     *
     * <p>用于表达“该时刻确实存在一个点，但值为空”（例如插值补点、写入 null），
     * 与“该时刻根本没有点”（不返回任何 {@code Sample}）是两种不同语义。
     * 构造结果的 {@link #isNull()} 恒为 {@code true}，
     * 所有强类型访问器返回 {@code null}。</p>
     *
     * @param timestamp         采样时刻，不能为 {@code null}
     * @param valueType         该点本应具有的值类型，不能为 {@code null}
     * @param quality           归一化质量等级，允许为 {@code null}
     *                          （归一化为 {@link Quality#UNKNOWN}）
     * @param vendorQualityCode 厂商原始质量码，允许为 {@code null}
     * @return 非 {@code null} 的空值采样点
     * @throws NullPointerException 当 {@code timestamp} 或 {@code valueType}
     *                              为 {@code null} 时
     */
    public static Sample ofNull(Instant timestamp, ValueType valueType, Quality quality, Integer vendorQualityCode) {
        if (timestamp == null) timestamp = Instant.now();
        return new Sample(timestamp, valueType, null, quality, vendorQualityCode);
    }

    /**
     * 返回采样时刻。
     *
     * @return 非 {@code null} 的时刻；其精度取决于厂商，通常是毫秒或微秒
     */
    public Instant getTimestamp() {
        return timestamp;
    }

    /**
     * 返回采样时刻的 {@link Date} 视图。
     *
     * <p>该字段与 {@link #getTimestamp()} 表示同一绝对时刻，只是换成
     * {@link Date} 方便 Java 老代码、JSP/模板引擎或前端 JSON 序列化直接使用。
     * 它不会改变时区语义——时区仍由 {@link #getTimestampText()} 负责展示。</p>
     *
     * @return 非 {@code null} 的 Date 视图
     */
    public Date getTimestampDate() {
        return new Date(timestampDate.getTime());
    }

    /**
     * 返回按实例时间配置格式化好的时间文本。
     *
     * <p>驱动在归一化时会用当前实例的 {@code time.response.target-zone} 与
     * {@code time.response.pattern} 填充该字段，因此前端可以直接展示，不需要
     * 再在 JavaScript 里做时区转换。若驱动没有传入时间配置，则退化为
     * {@link Instant#toString()} 的 UTC ISO 文本。</p>
     *
     * @return 非 {@code null} 的展示文本
     */
    public String getTimestampText() {
        return timestampText;
    }

    /**
     * 返回一个使用指定时间配置重新格式化的副本。
     *
     * <p>公共模型本身不持有配置；驱动在把厂商数据交给上层之前调用本方法，
     * 用该实例的 {@code time.response.*} 生成 {@link #getTimestampText()}。
     * 原始 {@link #getTimestamp()} 与 {@link #getTimestampDate()} 不变。</p>
     *
     * @param timeConfig 实例时间配置；为 {@code null} 时返回当前对象
     * @return 重新格式化后的采样点；{@code timeConfig} 为 {@code null} 时返回自身
     */
    public Sample withTimeFormat(TimeConfig timeConfig) {
        if (timeConfig == null) {
            return this;
        }
        return new Sample(timestamp, timestampDate, timeConfig.formatResponse(timestamp),
                valueType, getValue(), quality, vendorQualityCode);
    }

    /**
     * 返回值类型。
     *
     * @return 非 {@code null} 的类型，用于决定应当调用哪个强类型访问器
     */
    public ValueType getValueType() {
        return valueType;
    }

    /**
     * 返回以 {@code Object} 形式表示的原始值。
     *
     * <p>若值是 {@code byte[]}，返回的是副本而非内部数组，调用方可以随意修改；
     * 其他情况下返回的是不可变对象（{@link Double}、{@link Long}、
     * {@link Boolean}、{@link String}）的引用。值为空时返回 {@code null}。</p>
     *
     * @return 采样值，可能为 {@code null}（表示该点无有效值）
     */
    public Object getValue() {
        if (value instanceof byte[]) {
            return Arrays.copyOf((byte[]) value, ((byte[]) value).length);
        }
        return value;
    }

    /**
     * 以 {@link Double} 形式取值。
     *
     * <p>仅当内部值确实是 {@link Double} 时返回该值；类型不匹配或值为空时
     * 返回 {@code null}，不做数值转换，也不抛异常。</p>
     *
     * @return 双精度值，或 {@code null}
     */
    public Double getDoubleValue() {
        return Convert.toDouble(value);
    }

    /**
     * 以 {@link Long} 形式取值。
     *
     * <p>仅当内部值确实是 {@link Long} 时返回该值；类型不匹配或值为空时
     * 返回 {@code null}，不做数值转换（{@link ValueType#DOUBLE} 的点不会
     * 被截断成 long）。</p>
     *
     * @return 长整数值，或 {@code null}
     */
    public Long getLongValue() {
        return Convert.toLong(value);
    }

    /**
     * 以 {@link Boolean} 形式取值。
     *
     * @return 布尔值；类型不匹配或值为空时返回 {@code null}
     */
    public Boolean getBooleanValue() {
        return Convert.toBool(value);
    }

    /**
     * 以 {@link String} 形式取文本值。
     *
     * @return 文本值；类型不匹配或值为空时返回 {@code null}
     */
    public String getTextValue() {
        return Convert.toStr(value);
    }

    /**
     * 以 {@code byte[]} 形式取二进制值。
     *
     * <p>每次调用都返回新的数组副本，调用方修改返回数组不会影响本对象。</p>
     *
     * @return 字节数组副本；类型不匹配或值为空时返回 {@code null}
     */
    public byte[] getBytesValue() {
        return value instanceof byte[] ? Arrays.copyOf((byte[]) value, ((byte[]) value).length) : null;
    }

    /**
     * 返回归一化质量等级。
     *
     * @return 非 {@code null} 的质量等级；驱动未提供时为 {@link Quality#UNKNOWN}
     */
    public Quality getQuality() {
        return quality;
    }

    /**
     * 返回厂商原始质量码。
     *
     * <p>这是未经归一化的原始值，用于排查厂商特有问题；需要跨厂商通用判断时
     * 请使用 {@link #getQuality()}。</p>
     *
     * @return 厂商质量码，可能为 {@code null}（驱动未提供或无质量概念）
     */
    public Integer getVendorQualityCode() {
        return vendorQualityCode;
    }

    /**
     * 判断该点是否没有值。
     *
     * <p>注意“值为空”与“类型未知”是两件事：本方法只判断值是否为
     * {@code null}，与 {@link #getValueType()} 是否为
     * {@link ValueType#UNKNOWN} 无关。</p>
     *
     * @return 当值为 {@code null} 时返回 {@code true}
     */
    public boolean isNull() {
        return value == null;
    }

    /**
     * 返回便于日志排查的字符串表示，格式为
     * {@code Sample{时间戳, 类型, 值, 质量}}。
     *
     * <p>该格式仅用于调试输出，不构成稳定的序列化契约；
     * 字节数组值不会被展开，只输出其默认 {@code toString()} 形式。</p>
     *
     * @return 非 {@code null} 的字符串表示
     */
    @Override
    public String toString() {
        return "Sample{" + timestamp + ", " + valueType + ", " + value + ", " + quality + '}';
    }

    /**
     * 设置采样值。
     *
     * <p>直接替换内部值引用，不改动 {@link #getValueType()}；调用方应确保
     * 传入值的类型与点的类型一致。传入 {@code byte[]} 时保存的是引用本身，
     * 而非副本（读取时由 {@link #getValue()} 返回副本）。</p>
     *
     * @param value 采样值，可为 {@code null}（表示该点无有效值）
     */
    public void setValue(Object value) {
        this.value = value;
    }
}
