package io.github.forget_the_bright.tsdb.pspace.internal;

import io.github.forget_the_bright.tsdb.common.config.TimeConfig;
import io.github.forget_the_bright.tsdb.common.exception.TsdbDataException;
import io.github.forget_the_bright.tsdb.common.model.Quality;
import io.github.forget_the_bright.tsdb.common.model.Sample;
import io.github.forget_the_bright.tsdb.common.model.TagInfo;
import io.github.forget_the_bright.tsdb.common.model.ValueType;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

/**
 * pSpace 数据类型、质量码、值与时间的映射器。
 *
 * <p>映射规则（详见 {@code docs/16}）：</p>
 * <ul>
 *   <li><b>DataType</b>：BOOL→{@link ValueType#BOOLEAN}；
 *       INT8/16/32/64、UINT8/16/32/64 与 TIME→{@link ValueType#LONG}；
 *       FLOAT/DOUBLE→{@link ValueType#DOUBLE}；STRING/WSTRING→
 *       {@link ValueType#STRING}；BLOB→{@link ValueType#BYTES}；
 *       VARIANT 按运行时值推断；其余未知码保留厂商类型并标
 *       {@code unknownType=true}，值按字符串处理，不猜数值；</li>
 *   <li><b>质量</b>：192→{@link Quality#GOOD}、0→{@link Quality#BAD}、
 *       其它→{@link Quality#UNCERTAIN}，缺失→{@link Quality#UNKNOWN}；
 *       厂商原始码始终保留在 {@code vendorQualityCode}。手册没有给出码表，
 *       192 只是服务端缺省“好值”约定，不是权威定义；</li>
 *   <li><b>时间</b>：支持 {@code yyyy-MM-dd HH:mm:ss.SSS} 文本与 epoch 毫秒数字；
 *       服务端偶见 {@code 24:00:00} 表示次日零点，解析时归一到次日
 *       {@code 00:00:00}。解析失败抛 {@link TsdbDataException}，不兜底当前时间。</li>
 * </ul>
 *
 * <p><b>非公共 API</b>：随内部重构变化，宿主应用请勿直接依赖。</p>
 *
 * @author wanghao
 * @since 0.1.2
 */
public final class PspaceMapper {

    /** DataType：布尔。 */
    public static final int DT_BOOL = 1;
    /** DataType：8 位有符号整数。 */
    public static final int DT_INT8 = 2;
    /** DataType：8 位无符号整数。 */
    public static final int DT_UINT8 = 3;
    /** DataType：16 位有符号整数。 */
    public static final int DT_INT16 = 4;
    /** DataType：16 位无符号整数。 */
    public static final int DT_UINT16 = 5;
    /** DataType：32 位有符号整数。 */
    public static final int DT_INT32 = 6;
    /** DataType：32 位无符号整数。 */
    public static final int DT_UINT32 = 7;
    /** DataType：64 位有符号整数。 */
    public static final int DT_INT64 = 8;
    /** DataType：64 位无符号整数。 */
    public static final int DT_UINT64 = 9;
    /** DataType：单精度浮点。 */
    public static final int DT_FLOAT = 10;
    /** DataType：双精度浮点。 */
    public static final int DT_DOUBLE = 11;
    /** DataType：时间（epoch 毫秒）。 */
    public static final int DT_TIME = 12;
    /** DataType：字符串。 */
    public static final int DT_STRING = 13;
    /** DataType：宽字符串。 */
    public static final int DT_WSTRING = 14;
    /** DataType：二进制块。 */
    public static final int DT_BLOB = 15;
    /** DataType：变体，按运行时值推断。 */
    public static final int DT_VARIANT = 16;

    /** 质量码：好值（服务端缺省约定）。 */
    public static final int QUALITY_GOOD = 192;
    /** 质量码：坏值。 */
    public static final int QUALITY_BAD = 0;

    /** 兜底时间格式：带可选毫秒。 */
    private static final DateTimeFormatter FALLBACK =
            new DateTimeFormatterBuilder()
                    .appendPattern("yyyy-MM-dd HH:mm:ss")
                    .optionalStart()
                    .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
                    .optionalEnd()
                    .toFormatter();

    /** 时间解析与展示配置。 */
    private final TimeConfig timeConfig;

    /**
     * @param timeConfig 实例时间配置，不能为 {@code null}
     */
    public PspaceMapper(TimeConfig timeConfig) {
        if (timeConfig == null) {
            throw new IllegalArgumentException("timeConfig must not be null");
        }
        this.timeConfig = timeConfig;
    }

    /**
     * 返回时间配置。
     *
     * @return 非 {@code null} 的时间配置
     */
    public TimeConfig timeConfig() {
        return timeConfig;
    }

    /**
     * 按请求配置格式化时间，用于 {@code btime}/{@code etime}/写入 {@code tm}。
     *
     * @param instant 时刻，可为 {@code null}
     * @return 厂商文本；入参为 {@code null} 时返回 {@code null}
     */
    public String formatRequestTime(Instant instant) {
        return timeConfig.formatRequest(instant);
    }

    /**
     * 解析服务端时间字段（文本或 epoch 毫秒）。
     *
     * @param raw 原始字段，可为 {@code null}
     * @return 绝对时刻；入参为 {@code null} 或空白时返回 {@code null}
     * @throws TsdbDataException 文本无法解析时
     */
    public Instant parseTimestamp(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number) {
            return Instant.ofEpochMilli(((Number) raw).longValue());
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            if (text.contains("T") || text.endsWith("Z")) {
                return timeConfig.parseResponse(text);
            }
            return parseLocal(text);
        } catch (RuntimeException e) {
            throw new TsdbDataException("pSpace timestamp cannot be parsed: " + text, e);
        }
    }

    private Instant parseLocal(String text) {
        ZoneId zone = timeConfig.getResponseSourceZone();
        boolean nextDay = false;
        String normalized = text;
        int space = text.indexOf(' ');
        if (space > 0 && text.startsWith("24:", space + 1)) {
            normalized = text.substring(0, space + 1) + "00:" + text.substring(space + 4);
            nextDay = true;
        }
        LocalDateTime dateTime = LocalDateTime.parse(normalized, FALLBACK);
        if (nextDay) {
            dateTime = dateTime.plusDays(1);
        }
        return dateTime.atZone(zone).toInstant();
    }

    /**
     * 质量码映射。
     *
     * @param code 厂商质量码，可为 {@code null}
     * @return 非 {@code null} 的归一化质量
     */
    public Quality quality(Integer code) {
        if (code == null) {
            return Quality.UNKNOWN;
        }
        if (code == QUALITY_GOOD) {
            return Quality.GOOD;
        }
        if (code == QUALITY_BAD) {
            return Quality.BAD;
        }
        return Quality.UNCERTAIN;
    }

    /**
     * 映射 DataType 到公共值类型。
     *
     * @param dataType 厂商 DataType 码
     * @return 非 {@code null} 的值类型；未知码返回 {@link ValueType#STRING}，
     *         由调用方结合 {@link #isUnknownDataType(int)} 标属性
     */
    public ValueType valueType(int dataType) {
        switch (dataType) {
            case DT_BOOL:
                return ValueType.BOOLEAN;
            case DT_INT8:
            case DT_UINT8:
            case DT_INT16:
            case DT_UINT16:
            case DT_INT32:
            case DT_UINT32:
            case DT_INT64:
            case DT_UINT64:
            case DT_TIME:
                return ValueType.LONG;
            case DT_FLOAT:
            case DT_DOUBLE:
                return ValueType.DOUBLE;
            case DT_STRING:
            case DT_WSTRING:
                return ValueType.STRING;
            case DT_BLOB:
                return ValueType.BYTES;
            case DT_VARIANT:
            default:
                return ValueType.STRING;
        }
    }

    /**
     * 判断是否为未识别的 DataType。
     *
     * @param dataType 厂商 DataType 码
     * @return 未识别返回 {@code true}
     */
    public boolean isUnknownDataType(int dataType) {
        return dataType < DT_BOOL || dataType > DT_VARIANT;
    }

    /**
     * 返回 DataType 名称。
     *
     * @param dataType 厂商 DataType 码
     * @return 名称；未知返回 {@code null}
     */
    public String dataTypeName(int dataType) {
        switch (dataType) {
            case DT_BOOL:
                return "BOOL";
            case DT_INT8:
                return "INT8";
            case DT_UINT8:
                return "UINT8";
            case DT_INT16:
                return "INT16";
            case DT_UINT16:
                return "UINT16";
            case DT_INT32:
                return "INT32";
            case DT_UINT32:
                return "UINT32";
            case DT_INT64:
                return "INT64";
            case DT_UINT64:
                return "UINT64";
            case DT_FLOAT:
                return "FLOAT";
            case DT_DOUBLE:
                return "DOUBLE";
            case DT_TIME:
                return "TIME";
            case DT_STRING:
                return "STRING";
            case DT_WSTRING:
                return "WSTRING";
            case DT_BLOB:
                return "BLOB";
            case DT_VARIANT:
                return "VARIANT";
            default:
                return null;
        }
    }

    /**
     * 返回 TagType 名称。
     *
     * @param tagType 厂商 TagType 码
     * @return 名称；未知返回 {@code null}
     */
    public String tagTypeName(int tagType) {
        switch (tagType) {
            case 0:
                return "NODE";
            case 1:
                return "DIG";
            case 2:
                return "ANA";
            case 3:
                return "STR";
            case 4:
                return "DGS";
            default:
                return null;
        }
    }

    /**
     * 把一个原始值转换成采样点。
     *
     * @param timestamp     样本时刻，不能为 {@code null}
     * @param rawValue      原始值，可为 {@code null}
     * @param qualityCode   厂商质量码，可为 {@code null}
     * @param dataType      厂商 DataType 码
     * @param includeQuality 是否保留质量信息
     * @return 非 {@code null} 的采样点
     * @throws TsdbDataException 值与声明类型明显不匹配时
     */
    public Sample toSample(Instant timestamp, Object rawValue, Integer qualityCode,
                           int dataType, boolean includeQuality) {
        if (timestamp == null) {
            throw new IllegalArgumentException("timestamp must not be null");
        }
        Quality quality = includeQuality ? quality(qualityCode) : Quality.UNKNOWN;
        Integer vendorCode = includeQuality ? qualityCode : null;
        if (rawValue == null) {
            return Sample.ofNull(timestamp, valueType(dataType), quality, vendorCode);
        }
        ValueType type = valueType(dataType);
        if (dataType == DT_VARIANT || isUnknownDataType(dataType)) {
            type = infer(rawValue);
        }
        switch (type) {
            case DOUBLE:
                return Sample.ofDouble(timestamp, toDouble(rawValue), quality, vendorCode);
            case LONG:
                return Sample.ofLong(timestamp, toLong(rawValue), quality, vendorCode);
            case BOOLEAN:
                return Sample.ofBoolean(timestamp, toBoolean(rawValue), quality, vendorCode);
            case BYTES:
                return Sample.ofBytes(timestamp, toBytes(rawValue), quality, vendorCode);
            case STRING:
            default:
                return Sample.ofString(timestamp, String.valueOf(rawValue), quality, vendorCode);
        }
    }

    /**
     * 按运行时值推断值类型。
     *
     * @param raw 原始值，不能为 {@code null}
     * @return 非 {@code null} 的推断类型
     */
    public ValueType infer(Object raw) {
        if (raw instanceof Boolean) {
            return ValueType.BOOLEAN;
        }
        if (raw instanceof Byte || raw instanceof Short || raw instanceof Integer
                || raw instanceof Long || raw instanceof BigInteger) {
            return ValueType.LONG;
        }
        if (raw instanceof Float || raw instanceof Double || raw instanceof BigDecimal) {
            return ValueType.DOUBLE;
        }
        if (raw instanceof byte[]) {
            return ValueType.BYTES;
        }
        return ValueType.STRING;
    }

    /**
     * 把点位元数据映射成公共模型。
     *
     * @param point 已解析的点位，不能为 {@code null}
     * @return 非 {@code null} 的位号元数据
     */
    public TagInfo toTagInfo(PspaceModels.Point point) {
        String name = point.longName != null ? point.longName : point.name;
        TagInfo.Builder builder = TagInfo.builder(name == null ? String.valueOf(point.id) : name)
                .description(point.description)
                .unit(point.unit)
                .valueType(valueType(point.dataType))
                .vendorType(tagTypeName(point.tagType))
                .attribute("id", String.valueOf(point.id))
                .attribute("parentId", String.valueOf(point.parentId))
                .attribute("dataType", String.valueOf(point.dataType))
                .attribute("tagType", String.valueOf(point.tagType))
                .attribute("isNode", String.valueOf(point.node));
        String dataTypeName = dataTypeName(point.dataType);
        if (dataTypeName != null) {
            builder.attribute("dataTypeName", dataTypeName);
        }
        String tagTypeName = tagTypeName(point.tagType);
        if (tagTypeName != null) {
            builder.attribute("tagTypeName", tagTypeName);
        }
        if (point.archive != null) {
            builder.attribute("hisIsSave", String.valueOf(point.archive));
        }
        if (isUnknownDataType(point.dataType)) {
            builder.attribute("unknownType", "true");
        }
        return builder.build();
    }

    private static double toDouble(Object raw) {
        if (raw instanceof Number) {
            return ((Number) raw).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new TsdbDataException("pSpace value is not numeric: " + raw, e);
        }
    }

    private static long toLong(Object raw) {
        if (raw instanceof Number) {
            return ((Number) raw).longValue();
        }
        String text = String.valueOf(raw).trim();
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            try {
                return new BigDecimal(text).longValue();
            } catch (NumberFormatException e) {
                throw new TsdbDataException("pSpace value is not an integer: " + raw, e);
            }
        }
    }

    private static boolean toBoolean(Object raw) {
        if (raw instanceof Boolean) {
            return (Boolean) raw;
        }
        if (raw instanceof Number) {
            return ((Number) raw).doubleValue() != 0d;
        }
        String text = String.valueOf(raw).trim();
        if ("true".equalsIgnoreCase(text) || "1".equals(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text) || "0".equals(text)) {
            return false;
        }
        throw new TsdbDataException("pSpace value is not boolean: " + raw);
    }

    private static byte[] toBytes(Object raw) {
        if (raw instanceof byte[]) {
            return (byte[]) raw;
        }
        throw new TsdbDataException("pSpace BLOB value must be byte[]");
    }
}