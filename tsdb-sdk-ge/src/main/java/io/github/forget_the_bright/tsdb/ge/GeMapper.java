package io.github.forget_the_bright.tsdb.ge;

import io.github.forget_the_bright.tsdb.common.config.TimeConfig;
import io.github.forget_the_bright.tsdb.common.model.Quality;
import io.github.forget_the_bright.tsdb.common.model.Sample;
import io.github.forget_the_bright.tsdb.common.model.TagInfo;
import io.github.forget_the_bright.tsdb.common.model.TagSeries;
import io.github.forget_the_bright.tsdb.common.model.ValueType;
import io.github.forget_the_bright.tsdb.ge.internal.GeModels;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 GE 原生模型（{@link GeModels}）归一化为公共层模型。
 *
 * <p>职责：集中处理 GE Historian 与公共 SDK 之间的三处语义差异——
 * <strong>质量码</strong>、<strong>值字符串到强类型的推断</strong>、
 * <strong>标签名缺失时的兜底</strong>，使 {@code GeClient} 只关心流程编排。</p>
 *
 * <p>设计要点：GE 的 REST 响应把所有值都编码成字符串，且不保证提供可靠的
 * 值类型，因此除标签的 {@code DataType} 能给出提示外，数字/布尔识别都是
 * 尽力而为的启发式推断（见 {@link #toSample(GeModels.Point)}）。原始质量码
 * 会随 {@link Sample} 一起保留，不做有损压缩。本类无状态、全部方法为静态，
 * 可安全并发调用。</p>
 *
 * <p>与原生层的关系：只读取 {@link GeModels.Point}/{@link GeModels.Series}
 * 的 getter，不修改入参，也不发起任何 I/O。</p>
 *
 * <p>限制：推断结果不保证与 Historian 中登记的标签类型一致；若调用方依赖精确
 * 类型，应通过 {@code unwrap} 取原生对象自行判断，或明确使用带类型提示的接口。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class GeMapper {

    /**
     * 工具类，禁止实例化。
     */
    private GeMapper() {
    }

    /**
     * 把 GE 的厂商质量码映射为公共层枚举。
     *
     * <p>厂商语义：{@code 0=BAD}（坏值/采集失败）、{@code 1=UNCERTAIN}
     * （不确定/被替代）、{@code 2=NA}（无质量信息，公共层记为
     * {@link Quality#UNKNOWN}）、{@code 3=GOOD}（正常）。其他取值一律
     * {@link Quality#UNKNOWN}，避免把未知状态误判为可用。</p>
     *
     * @param vendorCode 厂商原始质量码
     * @return 对应的公共质量枚举，非 {@code null}
     */
    public static Quality quality(int vendorCode) {
        switch (vendorCode) {
            case 0:
                return Quality.BAD;
            case 1:
                return Quality.UNCERTAIN;
            case 2:
                return Quality.UNKNOWN;
            case 3:
                return Quality.GOOD;
            default:
                return Quality.UNKNOWN;
        }
    }

    /**
     * 由标签名构造最小可用的标签信息。
     *
     * <p>GE 的标签浏览接口只返回名称，不返回描述、工程单位或数据类型，
     * 因此这里显式把值类型置为 {@link ValueType#UNKNOWN}，而不是猜测。</p>
     *
     * @param tagName 标签名
     * @return 仅含名称与 {@code UNKNOWN} 类型的标签信息，非 {@code null}
     */
    public static TagInfo toTagInfo(String tagName) {
        return TagInfo.builder(tagName).valueType(ValueType.UNKNOWN).build();
    }

    /**
     * 把原生序列转换为公共标签序列。
     *
     * <p>标签名优先取原生值，缺失或空串时回退到 {@code fallbackTagName}
     * （通常是调用方请求里的名字）。原生 {@code DataType} 与厂商错误码
     * 会写入序列属性：{@code dataType}、{@code vendorErrorCode}、
     * {@code vendorErrorMessage}（后者仅在存在时写入）。样本逐点经
     * {@link #toSample(GeModels.Point)} 转换。</p>
     *
     * @param series          原生序列，不能为 {@code null}
     * @param fallbackTagName 原生标签名缺失时使用的名称
     * @return 公共标签序列，非 {@code null}
     */
    public static TagSeries toSeries(GeModels.Series series, String fallbackTagName) {
        return toSeries(series, fallbackTagName, null);
    }

    /**
     * 把原生序列转换为公共标签序列，并用实例时间配置格式化每个点。
     *
     * @param series          原生序列，不能为 {@code null}
     * @param fallbackTagName 原生标签名缺失时使用的名称
     * @param timeConfig      实例时间配置，允许为 {@code null}
     * @return 公共标签序列，非 {@code null}
     */
    public static TagSeries toSeries(GeModels.Series series, String fallbackTagName,
                                     TimeConfig timeConfig) {
        String tagName = series.getTagName() == null || series.getTagName().isEmpty()
                ? fallbackTagName
                : series.getTagName();
        TagSeries.Builder builder = TagSeries.builder(tagName);
        if (series.getDataType() != null) {
            builder.attribute("dataType", series.getDataType());
        }
        if (series.getErrorCode() != null) {
            builder.attribute("vendorErrorCode", String.valueOf(series.getErrorCode()));
            if (series.getErrorMessage() != null) {
                builder.attribute("vendorErrorMessage", series.getErrorMessage());
            }
        }
        for (GeModels.Point point : series.getPoints()) {
            builder.add(toSample(point, timeConfig));
        }
        return builder.build();
    }

    /**
     * 把原生序列的全部点转换为样本列表。
     *
     * <p>与 {@link #toSeries(GeModels.Series, String)} 的区别是不携带任何属性、
     * 也不关心标签名，仅做逐点转换；返回的是可变 {@link ArrayList}，
     * 调用方可以继续加工。</p>
     *
     * @param series 原生序列，不能为 {@code null}
     * @return 与原生点顺序一致的样本列表，非 {@code null}，可能为空
     */
    public static List<Sample> toSamples(GeModels.Series series) {
        return toSamples(series, null);
    }

    /**
     * 把原生序列的全部点转换为样本列表，并用实例时间配置格式化。
     *
     * @param series     原生序列，不能为 {@code null}
     * @param timeConfig 实例时间配置，允许为 {@code null}
     * @return 与原生点顺序一致的样本列表
     */
    public static List<Sample> toSamples(GeModels.Series series, TimeConfig timeConfig) {
        List<Sample> samples = new ArrayList<Sample>(series.getPoints().size());
        for (GeModels.Point point : series.getPoints()) {
            samples.add(toSample(point, timeConfig));
        }
        return samples;
    }

    /**
     * 把一个原生点转换为公共样本，按固定优先级推断值类型。
     *
     * <p>推断顺序（先命中者生效）：</p>
     * <ol>
     *   <li>值为 {@code null}：返回空样本，类型 {@link ValueType#UNKNOWN}；</li>
     *   <li>{@code DataType} 提示为字符串：原样保留为 {@link ValueType#STRING}
     *       （不 trim，保留前导空格等原始内容）；</li>
     *   <li>提示为布尔，或类型未知但文本是
     *       {@code true/false/yes/no/on/off}（忽略大小写）：转为布尔；</li>
     *   <li>提示为整数/浮点，或类型未知且文本是纯整数：先转 {@link ValueType#LONG}，
     *       {@code long} 溢出时退化为 {@link ValueType#DOUBLE}；</li>
     *   <li>其余可解析为有限浮点数：转为 {@link ValueType#DOUBLE}
     *       （{@code NaN}、{@code Infinity} 一律拒绝）；</li>
     *   <li>仍不成功：按原始字符串保存。</li>
     * </ol>
     *
     * <p>无论走哪条分支，厂商质量码都会作为 {@code vendorQualityCode} 保留在
     * 样本上，供需要精确质量语义的调用方使用。</p>
     *
     * @param point 原生数据点，不能为 {@code null}
     * @return 公共样本，非 {@code null}
     */
    public static Sample toSample(GeModels.Point point) {
        return toSample(point, null);
    }

    /**
     * 把原生点转换为公共样本，并用实例时间配置生成展示时间。
     *
     * @param point      原生数据点，不能为 {@code null}
     * @param timeConfig 实例时间配置，允许为 {@code null}
     * @return 公共样本，非 {@code null}
     */
    public static Sample toSample(GeModels.Point point, TimeConfig timeConfig) {
        Quality quality = quality(point.getQuality());
        String raw = point.getValue();
        if (raw == null) {
            return format(Sample.ofNull(point.getTimestamp(), ValueType.UNKNOWN, quality,
                    point.getQuality()), timeConfig);
        }
        ValueType hinted = fromDataType(point.getDataType());
        String text = raw.trim();
        if (hinted == ValueType.STRING) {
            return format(Sample.ofString(point.getTimestamp(), raw, quality, point.getQuality()),
                    timeConfig);
        }
        if (hinted == ValueType.BOOLEAN || (hinted == ValueType.UNKNOWN && isBooleanLiteral(text))) {
            Boolean bool = parseBoolean(text);
            if (bool != null) {
                return format(Sample.ofBoolean(point.getTimestamp(), bool, quality,
                        point.getQuality()), timeConfig);
            }
        }
        if (hinted == ValueType.LONG || hinted == ValueType.DOUBLE || hinted == ValueType.UNKNOWN) {
            if (isInteger(text)) {
                try {
                    return format(Sample.ofLong(point.getTimestamp(), Long.parseLong(text), quality,
                            point.getQuality()), timeConfig);
                } catch (NumberFormatException ignored) {
                    // fall through
                }
            }
            Double number = parseDouble(text);
            if (number != null) {
                return format(Sample.ofDouble(point.getTimestamp(), number, quality,
                        point.getQuality()), timeConfig);
            }
        }
        return format(Sample.ofString(point.getTimestamp(), raw, quality, point.getQuality()),
                timeConfig);
    }

    /**
     * 给公共样本补上实例级格式化时间文本。
     *
     * @param sample     原始样本
     * @param timeConfig 实例时间配置，允许为 {@code null}
     * @return 带展示时间的样本
     */
    private static Sample format(Sample sample, TimeConfig timeConfig) {
        return sample.withTimeFormat(timeConfig);
    }

    /**
     * 依据 GE 的 {@code DataType} 字符串推断公共值类型。
     *
     * <p>匹配方式为小写后的子串包含，覆盖 Historian 常见类型名：</p>
     * <ul>
     *   <li>{@code string/char/text} → {@link ValueType#STRING}；</li>
     *   <li>{@code bool/digital/discrete} → {@link ValueType#BOOLEAN}；</li>
     *   <li>{@code int/word/long} → {@link ValueType#LONG}；</li>
     *   <li>{@code real/float/double/single} → {@link ValueType#DOUBLE}。</li>
     * </ul>
     *
     * <p>使用子串而非全等匹配是为了兼容带前缀/后缀的厂商类型名（例如
     * {@code System.Int32}），代价是可能过宽匹配；无法识别时返回
     * {@link ValueType#UNKNOWN}，由值内容继续推断。</p>
     *
     * @param dataType 原生类型名，可为 {@code null}
     * @return 推断出的类型，未知时为 {@link ValueType#UNKNOWN}
     */
    private static ValueType fromDataType(String dataType) {
        if (dataType == null) {
            return ValueType.UNKNOWN;
        }
        String type = dataType.trim().toLowerCase();
        if (type.isEmpty()) {
            return ValueType.UNKNOWN;
        }
        if (type.contains("string") || type.contains("char") || type.contains("text")) {
            return ValueType.STRING;
        }
        if (type.contains("bool") || type.contains("digital") || type.contains("discrete")) {
            return ValueType.BOOLEAN;
        }
        if (type.contains("int") || type.contains("word") || type.contains("long")) {
            return ValueType.LONG;
        }
        if (type.contains("real") || type.contains("float") || type.contains("double") || type.contains("single")) {
            return ValueType.DOUBLE;
        }
        return ValueType.UNKNOWN;
    }

    /**
     * 判断文本是否为布尔字面量（忽略大小写）。
     *
     * <p>接受 {@code true/false}、{@code yes/no}、{@code on/off} 三组写法，
     * 对应 Historian 中数字量/离散量常见的表示法。</p>
     *
     * @param text 已 trim 的文本
     * @return 是布尔字面量返回 {@code true}
     */
    private static boolean isBooleanLiteral(String text) {
        String value = text.toLowerCase();
        return "true".equals(value) || "false".equals(value)
                || "yes".equals(value) || "no".equals(value)
                || "on".equals(value) || "off".equals(value);
    }

    /**
     * 把布尔字面量解析为 {@link Boolean}。
     *
     * @param text 已 trim 的文本
     * @return 对应布尔值；无法识别时返回 {@code null}
     */
    private static Boolean parseBoolean(String text) {
        String value = text.toLowerCase();
        if ("true".equals(value) || "yes".equals(value) || "on".equals(value)) {
            return Boolean.TRUE;
        }
        if ("false".equals(value) || "no".equals(value) || "off".equals(value)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * 判断文本是否为可带符号的纯十进制整数。
     *
     * <p>只接受可选的正负号加数字，不接受小数点、指数、空白或千分位，
     * 以免把 {@code 1.5}、{@code 1e3} 误判为整数而丢失精度。</p>
     *
     * @param text 已 trim 的文本
     * @return 是整数形式返回 {@code true}
     */
    private static boolean isInteger(String text) {
        if (text.isEmpty()) {
            return false;
        }
        int start = (text.charAt(0) == '-' || text.charAt(0) == '+') ? 1 : 0;
        if (start == text.length()) {
            return false;
        }
        for (int i = start; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 把文本解析为有限双精度值。
     *
     * <p>拒绝空串、{@code NaN} 以及 {@code Infinity}/溢出的结果——这些在
     * Historian 中通常代表坏值或未初始化，转成 {@code double} 会污染后续计算，
     * 因此返回 {@code null} 让调用方退化为字符串。</p>
     *
     * @param text 已 trim 的文本
     * @return 有限双精度值；不可解析或非有限时返回 {@code null}
     */
    private static Double parseDouble(String text) {
        if (text.isEmpty() || "NaN".equalsIgnoreCase(text)) {
            return null;
        }
        try {
            double value = Double.parseDouble(text);
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
