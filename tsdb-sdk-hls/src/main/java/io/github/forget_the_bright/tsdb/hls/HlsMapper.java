package io.github.forget_the_bright.tsdb.hls;

import io.github.forget_the_bright.tsdb.common.config.TimeConfig;
import io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException;
import io.github.forget_the_bright.tsdb.common.model.Aggregation;
import io.github.forget_the_bright.tsdb.common.model.Quality;
import io.github.forget_the_bright.tsdb.common.model.Sample;
import io.github.forget_the_bright.tsdb.common.model.TagInfo;
import io.github.forget_the_bright.tsdb.common.model.TagSeries;
import io.github.forget_the_bright.tsdb.common.model.ValueType;
import io.github.forget_the_bright.tsdb.hls.internal.HlsModels;

import java.time.Instant;
import java.util.List;

/**
 * HLS 厂商模型到公共 API 模型的映射器。
 *
 * <p>职责：把 {@link HlsModels} 中的原始结构（目录项、当前值、历史宽表点）
 * 转换为 {@link TagInfo}、{@link Sample}、{@link TagSeries}，并集中处理厂商
 * 语义差异。本类不做 IO，也不持有任何状态。</p>
 *
 * <p>关键映射规则：</p>
 * <ul>
 *   <li>目录项不含值类型与单位，因此值类型统一为 {@link ValueType#UNKNOWN}，
 *       单位留空，{@code vendorType} 固定为 {@code "HLS"}；</li>
 *   <li>质量码：0 → {@link Quality#BAD}，1 → {@link Quality#GOOD}，其余取值
 *       （含缺失）→ {@link Quality#UNKNOWN}；原始质量码不会被丢弃，仍写入
 *       {@code Sample#getVendorQualityCode()}；</li>
 *   <li>历史宽表只有 {@code Quality_Bound}/{@code Quality_MIN}/
 *       {@code Quality_MAX} 三列，<b>没有 Quality_AVG</b>，因此 AVG 序列的
 *       每个点质量都是 {@code UNKNOWN}、原始质量码为 {@code null}，并在
 *       序列属性中标注 {@code qualitySource=hls_not_provided}；</li>
 *   <li>文本解析失败时不抛异常：布尔解析失败退化为数值，数值解析失败退化为
 *       字符串，保证原始文本始终可追溯；</li>
 *   <li>厂商未提供时间戳的点无法构成公共采样点，会被跳过或映射为
 *       {@code null}，绝不伪造时间。</li>
 * </ul>
 *
 * <p>线程安全：本类没有可变字段，所有方法都是纯函数，可安全地被多线程共享
 * （{@code HlsClient} 内部即复用同一个实例）。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class HlsMapper {

    /**
     * 实例级时间配置；为 {@code null} 时使用 UTC ISO 文本。
     */
    private final TimeConfig timeConfig;

    /**
     * 创建不绑定时间配置的映射器（展示文本退化为 UTC ISO）。
     */
    public HlsMapper() {
        this(null);
    }

    /**
     * 创建绑定实例时间配置的映射器。
     *
     * @param timeConfig 实例时间配置，允许为 {@code null}
     */
    public HlsMapper(TimeConfig timeConfig) {
        this.timeConfig = timeConfig;
    }

    /**
     * 给公共采样点补上实例级格式化时间文本。
     *
     * @param sample 原始映射结果，允许为 {@code null}
     * @return 带 Date/展示文本的采样点，入参为 {@code null} 时返回 {@code null}
     */
    private Sample format(Sample sample) {
        return sample == null ? null : sample.withTimeFormat(timeConfig);
    }

    /**
     * 把 HLS 目录项转换为公共位号信息。
     *
     * <p>目录接口（{@code GET /ddb/read_alltagname}）只返回 {@code TagName}
     * 与 {@code TagDes}，不含值类型和工程单位，因此值类型固定为
     * {@link ValueType#UNKNOWN}、单位为空；{@code vendorType} 固定为
     * {@code "HLS"}，便于调用方区分数据来源。</p>
     *
     * @param tag 厂商目录项，不能为 {@code null}
     * @return 非 {@code null} 的公共位号信息
     */
    public TagInfo toTagInfo(HlsModels.Tag tag) {
        return TagInfo.builder(tag.name)
                .description(tag.description)
                .valueType(ValueType.UNKNOWN)
                .vendorType("HLS")
                .build();
    }

    /**
     * 把 HLS 当前值转换为公共采样点。
     *
     * <p>转换规则：</p>
     * <ul>
     *   <li>厂商未返回时间戳时返回 {@code null}——公共模型不允许无时间的采样点，
     *       调用方（{@code HlsCurrentValues}）会据此填充空的 {@code TagSample}；</li>
     *   <li>值为 {@code null} 时返回 {@link Sample#ofNull}，保留时间、类型与质量；</li>
     *   <li>{@link ValueType#STRING} 直接按字符串返回；</li>
     *   <li>{@link ValueType#BOOLEAN} 先按 {@code "1"/"0"/"true"/"false"}
     *       解析（大小写不敏感），失败则尝试数值，再失败退化为字符串；</li>
     *   <li>其他类型（含 {@link ValueType#UNKNOWN}）先尝试数值，失败退化为字符串，
     *       避免因类型信息缺失而丢值。</li>
     * </ul>
     *
     * <p>质量按 {@link #quality(Integer)} 归一化，原始质量码同时保留在
     * {@code Sample#getVendorQualityCode()} 中。</p>
     *
     * @param value 厂商当前值，不能为 {@code null}
     * @return 采样点；当厂商未提供时间戳时返回 {@code null}
     */
    public Sample toSample(HlsModels.CurrentValue value) {
        if (value.timestamp == null) {
            return null;
        }
        ValueType type = valueType(value.tagType);
        Quality quality = quality(value.quality);
        if (value.value == null) {
            return format(Sample.ofNull(value.timestamp, type, quality, value.quality));
        }
        if (type == ValueType.STRING) {
            return format(Sample.ofString(value.timestamp, value.value, quality, value.quality));
        }
        if (type == ValueType.BOOLEAN) {
            Boolean bool = parseBoolean(value.value);
            if (bool != null) {
                return format(Sample.ofBoolean(value.timestamp, bool, quality, value.quality));
            }
        }
        Double number = parseDouble(value.value);
        if (number != null) {
            return format(Sample.ofDouble(value.timestamp, number, quality, value.quality));
        }
        return format(Sample.ofString(value.timestamp, value.value, quality, value.quality));
    }

    /**
     * 把 HLS 历史宽表序列转换为公共序列。
     *
     * <p>列与聚合函数的对应关系：{@link Aggregation#AVG} 取
     * {@code TagValue_AVG}，{@link Aggregation#MIN} 取 {@code TagValue_MIN}，
     * {@link Aggregation#MAX} 取 {@code TagValue_MAX}，{@link Aggregation#BOUND}
     * 取 {@code TagValue_Bound}。厂商只提供 MIN/MAX/Bound 三列的质量，
     * 没有 AVG 质量列，因此 AVG 序列会额外写入
     * {@code qualitySource=hls_not_provided} 属性。</p>
     *
     * <p>点数控制：按厂商返回顺序最多输出 {@code maxPoints} 个点，超出时截断并
     * 置 {@code truncated=true}；{@code maxPoints} 小于等于 0 时不输出任何点，
     * 同样标记为已截断。时间戳为 {@code null} 的点会被跳过。</p>
     *
     * @param series      厂商历史序列，不能为 {@code null}
     * @param aggregation 查询使用的聚合函数，决定读取哪一列；未知取值按 AVG 分支处理
     * @param maxPoints   单序列允许输出的最大点数
     * @return 非 {@code null} 的公共序列，可能为空序列
     */
    public TagSeries toSeries(HlsModels.Series series, Aggregation aggregation, int maxPoints) {
        TagSeries.Builder builder = TagSeries.builder(series.tagName);
        if (aggregation == Aggregation.AVG) {
            builder.attribute("qualitySource", "hls_not_provided");
        }
        int emitted = 0;
        boolean truncated = false;
        for (HlsModels.Point point : series.points) {
            if (point.timestamp == null) {
                continue;
            }
            if (emitted >= maxPoints) {
                truncated = true;
                break;
            }
            Sample sample = toSeriesSample(point, aggregation);
            if (sample != null) {
                builder.add(sample);
                emitted++;
            }
        }
        return builder.truncated(truncated).build();
    }

    /**
     * 按聚合函数从宽表点中取出对应列并转换为采样点。
     *
     * <p>各分支读取的列不同：MIN → {@code min}/{@code qualityMin}，
     * MAX → {@code max}/{@code qualityMax}，BOUND → {@code bound}/
     * {@code qualityBound}，AVG（及未知取值）→ {@code avg}。由于没有
     * {@code Quality_AVG}，AVG 分支的原始质量码恒为 {@code null}、质量恒为
     * {@link Quality#UNKNOWN}。{@link Aggregation#INTERVAL_SUM} 没有对应列，
     * 显式抛 {@link TsdbUnsupportedException}，绝不静默按 AVG 处理。</p>
     *
     * <p>列值为空时返回 {@link Sample#ofNull}；数值解析失败时退化为字符串，
     * 不抛异常。</p>
     *
     * @param point       厂商宽表点，不能为 {@code null}
     * @param aggregation 聚合函数，决定读取的列
     * @return 非 {@code null} 的采样点（时间来自 {@code point.timestamp}）
     * @throws TsdbUnsupportedException 当聚合函数为 {@link Aggregation#INTERVAL_SUM} 时
     */
    private Sample toSeriesSample(HlsModels.Point point, Aggregation aggregation) {
        String raw;
        Integer vendorQuality;
        Quality quality;
        switch (aggregation) {
            case MIN:
                raw = point.min;
                vendorQuality = point.qualityMin;
                quality = quality(vendorQuality);
                break;
            case MAX:
                raw = point.max;
                vendorQuality = point.qualityMax;
                quality = quality(vendorQuality);
                break;
            case BOUND:
                raw = point.bound;
                vendorQuality = point.qualityBound;
                quality = quality(vendorQuality);
                break;
            case INTERVAL_SUM:
                throw new TsdbUnsupportedException("HLS does not support aggregation " + aggregation);
            case AVG:
            default:
                raw = point.avg;
                vendorQuality = null;
                quality = Quality.UNKNOWN;
                break;
        }
        if (raw == null) {
            return format(Sample.ofNull(point.timestamp, ValueType.DOUBLE, quality, vendorQuality));
        }
        Double number = parseDouble(raw);
        if (number != null) {
            return format(Sample.ofDouble(point.timestamp, number, quality, vendorQuality));
        }
        return format(Sample.ofString(point.timestamp, raw, quality, vendorQuality));
    }

    /**
     * 把 HLS 原始质量码归一化为公共质量枚举。
     *
     * <p>厂商语义：0 表示坏值（BAD），1 表示好值（GOOD），其余取值（例如 -3
     * 或其它扩展码）没有标准含义，统一视为 {@link Quality#UNKNOWN}。原始码
     * 由调用方另行保留，不在此处丢弃。</p>
     *
     * @param code 原始质量码，可为 {@code null}
     * @return 归一化后的质量，永不为 {@code null}
     */
    public static Quality quality(Integer code) {
        if (code == null) {
            return Quality.UNKNOWN;
        }
        if (code == 0) {
            return Quality.BAD;
        }
        if (code == 1) {
            return Quality.GOOD;
        }
        return Quality.UNKNOWN;
    }

    /**
     * 把 HLS 的 {@code TagType} 转换为公共值类型。
     *
     * <p>映射表：-3 → {@link ValueType#UNKNOWN}；0 → {@link ValueType#BOOLEAN}；
     * 18 → {@link ValueType#STRING}；1..8 → {@link ValueType#DOUBLE}；
     * 其余取值（含 {@code null}）→ {@link ValueType#UNKNOWN}。</p>
     *
     * @param tagType 厂商类型码，可为 {@code null}
     * @return 公共值类型，永不为 {@code null}
     */
    public static ValueType valueType(Integer tagType) {
        if (tagType == null) {
            return ValueType.UNKNOWN;
        }
        switch (tagType) {
            case -3:
                return ValueType.UNKNOWN;
            case 0:
                return ValueType.BOOLEAN;
            case 18:
                return ValueType.STRING;
            case 1:
            case 2:
            case 3:
            case 4:
            case 5:
            case 6:
            case 7:
            case 8:
                return ValueType.DOUBLE;
            default:
                return ValueType.UNKNOWN;
        }
    }

    /**
     * 解析 HLS 的布尔文本。
     *
     * <p>厂商可能返回 {@code "1"/"0"} 或 {@code "true"/"false"}（大小写不敏感），
     * 两种形式都接受；其余文本返回 {@code null}，由调用方决定退化策略。</p>
     *
     * @param value 原始文本，不能为 {@code null}
     * @return 解析出的布尔值；无法识别时返回 {@code null}
     */
    private static Boolean parseBoolean(String value) {
        String text = value.trim();
        if ("1".equals(text) || "true".equalsIgnoreCase(text)) {
            return Boolean.TRUE;
        }
        if ("0".equals(text) || "false".equalsIgnoreCase(text)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * 宽松解析浮点数。
     *
     * <p>先去首尾空白再调用 {@link Double#valueOf(String)}；解析失败返回
     * {@code null} 而不抛异常，因为厂商的数值列在异常情况下可能返回空串或
     * 非数字文本，此时应保留原始字符串而不是让整次查询失败。</p>
     *
     * @param value 原始文本，不能为 {@code null}
     * @return 解析出的数值；无法解析时返回 {@code null}
     */
    private static Double parseDouble(String value) {
        try {
            return Double.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }


}
