package io.github.forget_the_bright.tsdb.hls.internal;

import io.github.forget_the_bright.tsdb.common.config.TimeConfig;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * HLS REST 响应的轻量模型载体。
 *
 * <p>职责：在 {@code HlsNativeApi} 完成 JSON 解析后，用一个不依赖具体 JSON
 * 库树模型的纯 Java 结构承载厂商数据，再交给 {@code HlsMapper} 转换为公共模型。这样
 * 映射层与解析层解耦，也便于单元测试直接构造对象。</p>
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>所有字段都是 {@code public final}，构造后不可重新赋值，天然具备
 *       安全发布的前提；</li>
 *   <li>数值一律保留厂商原始文本（{@code String}），由映射层决定解析为
 *       {@code double} 还是退化为字符串，避免解析层提前丢精度或抛异常；</li>
 *   <li>时间戳已由解析层统一转换为 {@link Instant}，本类不再处理时区；
 *       厂商字段缺失时对应值为 {@code null}；</li>
 *   <li>质量保留原始整型码（而不是公共枚举），以便映射层同时输出公共质量
 *       与 {@code vendorQualityCode}。</li>
 * </ul>
 *
 * <p>线程安全：单个实例构造完成后可安全地被多线程只读访问。唯一例外是
 * {@link Series#points}——它是可变列表，约定由解析层填充完毕后不再修改；
 * 若需跨线程共享，调用方应保证发布前已完成填充。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class HlsModels {

    /**
     * 工具类，禁止实例化。
     */
    private HlsModels() {
    }

    /**
     * 位号目录项，对应 {@code GET /ddb/read_alltagname} 响应中的
     * {@code TagNameList} 元素。
     */
    public static final class Tag {
        /**
         * 位号名（厂商字段 {@code TagName}），在 HLS 内唯一。
         */
        public final String name;

        /**
         * 位号描述（厂商字段 {@code TagDes}），可能为 {@code null} 或空串。
         */
        public final String description;

        /**
         * 创建目录项。
         *
         * @param name        位号名，不能为 {@code null}
         * @param description 位号描述，可为 {@code null}
         */
        public Tag(String name, String description) {
            this.name = name;
            this.description = description;
        }
    }

    /**
     * 当前值，对应 {@code POST /ddb/read_ddbtagvalue} 响应中的
     * {@code DDBTagValueList} 元素。
     */
    public static final class CurrentValue {
        /**
         * 位号名（厂商字段 {@code TagName}）。
         */
        public final String tagName;

        /**
         * 原始质量码（厂商字段 {@code Quality}）：0 表示坏值、1 表示好值，
         * 其余取值无标准含义；缺失时为 {@code null}。
         */
        public final Integer quality;

        /**
         * 原始类型码（厂商字段 {@code TagType}）：-3 未知、0 布尔、18 字符串、
         * 1..8 数值；缺失时为 {@code null}。
         */
        public final Integer tagType;

        /**
         * 原始值文本（厂商字段 {@code TagValue}），未做任何类型转换；
         * 缺失时为 {@code null}。
         */
        public final String value;

        /**
         * 采样时间（厂商字段 {@code TagValueTime} 解析结果）；厂商未提供或
         * 无法解析时为 {@code null}。
         */
        public final Instant timestamp;

        /**
         * 采样时间的 {@link Date} 视图；{@code timestamp} 为 {@code null} 时也为
         * {@code null}。
         */
        public final Date timestampDate;

        /**
         * 已格式化的采样时间文本；{@code timestamp} 为 {@code null} 时为
         * {@code null}。
         */
        public final String timestampText;

        /**
         * 创建当前值。
         *
         * @param tagName   位号名，不能为 {@code null}
         * @param quality   原始质量码，可为 {@code null}
         * @param tagType   原始类型码，可为 {@code null}
         * @param value     原始值文本，可为 {@code null}
         * @param timestamp 采样时间，可为 {@code null}
         */
        public CurrentValue(String tagName, Integer quality, Integer tagType, String value, Instant timestamp) {
            this(tagName, quality, tagType, value, timestamp, null);
        }

        /**
         * 创建当前值，并按实例时间配置生成 Date/展示文本。
         *
         * @param tagName    位号名，不能为 {@code null}
         * @param quality    原始质量码，可为 {@code null}
         * @param tagType    原始类型码，可为 {@code null}
         * @param value      原始值文本，可为 {@code null}
         * @param timestamp  采样时间，可为 {@code null}
         * @param timeConfig 实例时间配置，可为 {@code null}
         */
        public CurrentValue(String tagName, Integer quality, Integer tagType, String value,
                            Instant timestamp, TimeConfig timeConfig) {
            this.tagName = tagName;
            this.quality = quality;
            this.tagType = tagType;
            this.value = value;
            this.timestamp = timestamp;
            this.timestampDate = timestamp == null ? null : Date.from(timestamp);
            this.timestampText = timestamp == null ? null
                    : (timeConfig == null ? timestamp.toString() : timeConfig.formatResponse(timestamp));
        }
    }

    /**
     * 历史宽表中的一个时间点，对应 {@code POST /hdb/read_hdbtagvalue} 响应中
     * {@code OneTagHDBValueList} 的元素。
     *
     * <p>厂商按固定步长同时返回多列聚合结果：{@code TagValue_AVG}、
     * {@code TagValue_Bound}、{@code TagValue_MIN}、{@code TagValue_MAX}，
     * 以及 {@code Quality_Bound}/{@code Quality_MIN}/{@code Quality_MAX} 三列
     * 质量。<b>没有 {@code Quality_AVG}</b>，因此本类不设 {@code qualityAvg}
     * 字段；AVG 的质量在映射层固定为 {@code UNKNOWN}。</p>
     */
    public static final class Point {
        /**
         * 该聚合点的时间戳（区间起点）；缺失时为 {@code null}，映射层会跳过该点。
         */
        public final Instant timestamp;

        /**
         * 聚合点时间的 {@link Date} 视图；{@code timestamp} 为 {@code null} 时也为
         * {@code null}。
         */
        public final Date timestampDate;

        /**
         * 已格式化的聚合点时间文本；{@code timestamp} 为 {@code null} 时为
         * {@code null}。
         */
        public final String timestampText;

        /**
         * 平均值列原始文本（{@code TagValue_AVG}），未请求 AVG 或该点无数据时
         * 为 {@code null}。
         */
        public final String avg;

        /**
         * 边界值列原始文本（{@code TagValue_Bound}），未请求 Bound 或该点无数据时
         * 为 {@code null}。
         */
        public final String bound;

        /**
         * 最小值列原始文本（{@code TagValue_MIN}）。
         */
        public final String min;

        /**
         * 最大值列原始文本（{@code TagValue_MAX}）。
         */
        public final String max;

        /**
         * Bound 列原始质量码（{@code Quality_Bound}）；缺失时为 {@code null}。
         */
        public final Integer qualityBound;

        /**
         * MIN 列原始质量码（{@code Quality_MIN}）；缺失时为 {@code null}。
         */
        public final Integer qualityMin;

        /**
         * MAX 列原始质量码（{@code Quality_MAX}）；缺失时为 {@code null}。
         */
        public final Integer qualityMax;

        /**
         * 创建历史宽表点。
         *
         * <p>参数顺序与厂商响应列顺序保持一致；各列可独立为 {@code null}，
         * 表示该列未被请求或该区间无数据。</p>
         *
         * @param timestamp    聚合点时间戳，可为 {@code null}
         * @param avg          AVG 列原始文本，可为 {@code null}
         * @param bound        Bound 列原始文本，可为 {@code null}
         * @param min          MIN 列原始文本，可为 {@code null}
         * @param max          MAX 列原始文本，可为 {@code null}
         * @param qualityBound Bound 列质量码，可为 {@code null}
         * @param qualityMin   MIN 列质量码，可为 {@code null}
         * @param qualityMax   MAX 列质量码，可为 {@code null}
         */
        public Point(Instant timestamp, String avg, String bound, String min, String max,
                     Integer qualityBound, Integer qualityMin, Integer qualityMax) {
            this(timestamp, avg, bound, min, max, qualityBound, qualityMin, qualityMax, null);
        }

        /**
         * 创建历史宽表点，并按实例时间配置生成 Date/展示文本。
         *
         * @param timestamp    聚合点时间戳，可为 {@code null}
         * @param avg          AVG 列原始文本，可为 {@code null}
         * @param bound        Bound 列原始文本，可为 {@code null}
         * @param min          MIN 列原始文本，可为 {@code null}
         * @param max          MAX 列原始文本，可为 {@code null}
         * @param qualityBound Bound 列质量码，可为 {@code null}
         * @param qualityMin   MIN 列质量码，可为 {@code null}
         * @param qualityMax   MAX 列质量码，可为 {@code null}
         * @param timeConfig   实例时间配置，可为 {@code null}
         */
        public Point(Instant timestamp, String avg, String bound, String min, String max,
                     Integer qualityBound, Integer qualityMin, Integer qualityMax,
                     TimeConfig timeConfig) {
            this.timestamp = timestamp;
            this.timestampDate = timestamp == null ? null : Date.from(timestamp);
            this.timestampText = timestamp == null ? null
                    : (timeConfig == null ? timestamp.toString() : timeConfig.formatResponse(timestamp));
            this.avg = avg;
            this.bound = bound;
            this.min = min;
            this.max = max;
            this.qualityBound = qualityBound;
            this.qualityMin = qualityMin;
            this.qualityMax = qualityMax;
        }
    }

    /**
     * 单个位号的历史宽表序列，对应 {@code POST /hdb/read_hdbtagvalue} 响应中的
     * 一个 {@code HDBTagValueList} 元素。
     */
    public static final class Series {
        /**
         * 位号名（厂商字段 {@code TagName}）。
         */
        public final String tagName;

        /**
         * 原始类型码（厂商字段 {@code TagType}）；历史接口通常不返回值类型，
         * 因此多为 {@code null}。
         */
        public final Integer tagType;

        /**
         * 聚合点列表，按时间升序排列（由解析层保证），初始为空列表。
         *
         * <p>该列表可变，约定由解析层独占填充，填充完成后不再修改；外部读取
         * 时应只读遍历。</p>
         */
        public final List<Point> points = new ArrayList<Point>();

        /**
         * 创建历史序列。
         *
         * @param tagName 位号名，不能为 {@code null}
         * @param tagType 原始类型码，可为 {@code null}
         */
        public Series(String tagName, Integer tagType) {
            this.tagName = tagName;
            this.tagType = tagType;
        }
    }
}
