package io.github.forget_the_bright.tsdb.ge.internal;

import io.github.forget_the_bright.tsdb.common.config.TimeConfig;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * GE Historian 线上格式的内存模型（原生层专用）。
 *
 * <p>职责：承载 JSON 反序列化后的原始数值，作为 {@code GeNativeApi}、
 * {@code GeTokenManager} 与上层 {@code GeMapper} 之间的数据载体。</p>
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li><strong>值一律保存为字符串</strong>：GE 不返回可靠的值类型判别字段，
 *       提前转换成数字/布尔会丢失原始精度与格式，因此类型推断集中交给
 *       {@code GeMapper} 处理；</li>
 *   <li>全部字段 {@code final}、无 setter，构造后不可变；</li>
 *   <li>{@link Series#getPoints()} 返回不可修改的防御性拷贝，外部列表的后续改动
 *       不会影响已构造的实例；</li>
 *   <li>不依赖 JSON 库注解，由上层显式组装，避免把传输格式耦合进模型。</li>
 * </ul>
 *
 * <p>线程安全性：所有嵌套类都是不可变对象，构造完成后可安全地在多线程间共享。</p>
 *
 * <p>可见性：本类位于 {@code internal} 包，属于实现细节，公共 API 不暴露这些类型；
 * 需要原生数据时通过 {@code GeClient#unwrap} 取得 {@code GeNativeApi}。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class GeModels {

    /**
     * 模型容器类，禁止实例化。
     */
    private GeModels() {
    }

    /**
     * 单个历史/当前值数据点。
     *
     * <p>对应 Historian 响应中的一项采样：时间戳、值文本、质量码与可选的数据类型。
     * 质量码保持厂商原始整数（{@code 0=BAD}、{@code 1=UNCERTAIN}、{@code 2=NA}、
     * {@code 3=GOOD}），由 {@code GeMapper} 再映射为公共枚举。</p>
     */
    public static final class Point {
        /** 采样时间，已从厂商时间字符串解析为 UTC 时刻。 */
        private final Instant timestamp;

        /** 采样时间的 Date 视图；时间为 null 时也为 null。 */
        private final Date timestampDate;

        /** 已格式化的采样时间文本；时间为 null 时为 null。 */
        private final String timestampText;

        /** 原始值文本，未做 trim 或类型转换，可能为 {@code null}。 */
        private final String value;

        /** 厂商质量码原始值。 */
        private final int quality;

        /** 厂商声明的数据类型（如 {@code System.Int32}），可为 {@code null}。 */
        private final String dataType;

        /**
         * 构造一个数据点。
         *
         * @param timestamp 采样时间，可为 {@code null}（厂商未返回时间时）
         * @param value     原始值文本，可为 {@code null}
         * @param quality   厂商质量码
         * @param dataType  厂商数据类型，可为 {@code null}
         */
        public Point(Instant timestamp, String value, int quality, String dataType) {
            this(timestamp, value, quality, dataType, null);
        }

        /**
         * 构造一个数据点，并按实例时间配置生成 Date/展示文本。
         *
         * @param timestamp  采样时间，可为 {@code null}
         * @param value      原始值文本，可为 {@code null}
         * @param quality    厂商质量码
         * @param dataType   厂商数据类型，可为 {@code null}
         * @param timeConfig 实例时间配置，可为 {@code null}
         */
        public Point(Instant timestamp, String value, int quality, String dataType,
                     TimeConfig timeConfig) {
            this.timestamp = timestamp;
            this.timestampDate = timestamp == null ? null : Date.from(timestamp);
            this.timestampText = timestamp == null ? null
                    : (timeConfig == null ? timestamp.toString() : timeConfig.formatResponse(timestamp));
            this.value = value;
            this.quality = quality;
            this.dataType = dataType;
        }

        /**
         * 返回采样时间。
         *
         * @return 采样时刻，可能为 {@code null}
         */
    public Instant getTimestamp() {
            return timestamp;
        }

        /**
         * 返回采样时间的 {@link Date} 视图，便于前端直接序列化。
         *
         * @return Date，可能为 {@code null}（厂商未返回时间）
         */
        public Date getTimestampDate() {
            return timestampDate == null ? null : new Date(timestampDate.getTime());
        }

        /**
         * 返回格式化后的采样时间文本，前端可直接展示。
         *
         * @return 展示文本，可能为 {@code null}（厂商未返回时间）
         */
        public String getTimestampText() {
            return timestampText;
        }

        /**
         * 返回原始值文本。
         *
         * @return 未经处理的字符串值，可能为 {@code null}
         */
        public String getValue() {
            return value;
        }

        /**
         * 返回厂商质量码。
         *
         * @return 原始质量码，{@code 0=BAD}、{@code 1=UNCERTAIN}、{@code 2=NA}、{@code 3=GOOD}
         */
        public int getQuality() {
            return quality;
        }

        /**
         * 返回厂商数据类型。
         *
         * @return 类型名，未知时为 {@code null}
         */
        public String getDataType() {
            return dataType;
        }
    }

    /**
     * 单个标签的查询结果序列。
     *
     * <p>既可能表示成功结果（带 {@code points}），也可能表示失败结果
     * （{@code errorCode} 非 {@code null}）；两者由上层根据
     * {@code partialResultAllowed} 决定是抛异常还是降级为空序列。</p>
     */
    public static final class Series {
        /** 标签名，厂商可能不返回，由上层用请求名兜底。 */
        private final String tagName;

        /** 厂商数据类型，可为 {@code null}。 */
        private final String dataType;

        /** 数据点列表，永不为 {@code null}，且不可修改。 */
        private final List<Point> points;

        /** 厂商错误码，成功时为 {@code null}。 */
        private final Integer errorCode;

        /** 厂商错误描述，可为 {@code null}。 */
        private final String errorMessage;

        /** 工程单位（响应 {@code EngineeringUnits}），可为 {@code null}。 */
        private final String engineeringUnits;

        /** 工程量程上限（响应 {@code HiEngineeringUnits}）的文本形式，可为 {@code null}。 */
        private final String hiEngineeringUnits;

        /** 工程量程下限（响应 {@code LoEngineeringUnits}）的文本形式，可为 {@code null}。 */
        private final String loEngineeringUnits;

        /**
         * 构造一个序列（不带工程单位信息）。
         *
         * <p>{@code points} 会被复制成不可修改列表；传入 {@code null} 时按空列表
         * 处理，从而让“无数据”和“字段缺失”在模型层表现一致。</p>
         *
         * @param tagName      标签名，可为 {@code null}
         * @param dataType     厂商数据类型，可为 {@code null}
         * @param points       数据点列表，可为 {@code null}
         * @param errorCode    厂商错误码，可为 {@code null}
         * @param errorMessage 厂商错误描述，可为 {@code null}
         */
        public Series(String tagName, String dataType, List<Point> points,
                      Integer errorCode, String errorMessage) {
            this(tagName, dataType, points, errorCode, errorMessage, null, null, null);
        }

        /**
         * 构造一个序列（带工程单位信息）。
         *
         * <p>{@code points} 会被复制成不可修改列表；传入 {@code null} 时按空列表
         * 处理。工程单位三个字段直接保存厂商响应文本，不做数值解析，
         * 缺失时为 {@code null}。</p>
         *
         * @param tagName            标签名，可为 {@code null}
         * @param dataType           厂商数据类型，可为 {@code null}
         * @param points             数据点列表，可为 {@code null}
         * @param errorCode          厂商错误码，可为 {@code null}
         * @param errorMessage       厂商错误描述，可为 {@code null}
         * @param engineeringUnits   工程单位，可为 {@code null}
         * @param hiEngineeringUnits 工程量程上限文本，可为 {@code null}
         * @param loEngineeringUnits 工程量程下限文本，可为 {@code null}
         */
        public Series(String tagName, String dataType, List<Point> points,
                      Integer errorCode, String errorMessage,
                      String engineeringUnits, String hiEngineeringUnits, String loEngineeringUnits) {
            this.tagName = tagName;
            this.dataType = dataType;
            this.points = points == null
                    ? Collections.<Point>emptyList()
                    : Collections.unmodifiableList(new ArrayList<Point>(points));
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
            this.engineeringUnits = engineeringUnits;
            this.hiEngineeringUnits = hiEngineeringUnits;
            this.loEngineeringUnits = loEngineeringUnits;
        }

        /**
         * 返回标签名。
         *
         * @return 标签名，可能为 {@code null}
         */
        public String getTagName() {
            return tagName;
        }

        /**
         * 返回厂商数据类型。
         *
         * @return 类型名，可能为 {@code null}
         */
        public String getDataType() {
            return dataType;
        }

        /**
         * 返回数据点列表。
         *
         * @return 不可修改的列表，非 {@code null}，可能为空
         */
        public List<Point> getPoints() {
            return points;
        }

        /**
         * 返回厂商错误码。
         *
         * @return 错误码；查询成功时为 {@code null}
         */
        public Integer getErrorCode() {
            return errorCode;
        }

        /**
         * 返回厂商错误描述。
         *
         * @return 错误描述，可能为 {@code null}
         */
        public String getErrorMessage() {
            return errorMessage;
        }

        /**
         * 返回工程单位。
         *
         * @return 厂商响应 {@code EngineeringUnits} 的文本，可能为 {@code null}
         */
        public String getEngineeringUnits() {
            return engineeringUnits;
        }

        /**
         * 返回工程量程上限。
         *
         * @return 厂商响应 {@code HiEngineeringUnits} 的文本，可能为 {@code null}
         */
        public String getHiEngineeringUnits() {
            return hiEngineeringUnits;
        }

        /**
         * 返回工程量程下限。
         *
         * @return 厂商响应 {@code LoEngineeringUnits} 的文本，可能为 {@code null}
         */
        public String getLoEngineeringUnits() {
            return loEngineeringUnits;
        }
    }

    /**
     * 待写入的数据点。
     *
     * <p>供原生层写接口使用（本 SDK 构建未向公共层开放写入能力）；值与质量码
     * 都以厂商期望的形式保存，不做类型转换。</p>
     */
    public static final class WritePoint {
        /** 写入时间，UTC 时刻。 */
        private final Instant timestamp;

        /** 质量码原始值。 */
        private final int quality;

        /** 值文本。 */
        private final String value;

        /**
         * 构造一个待写入点。
         *
         * @param timestamp 写入时间，可为 {@code null}
         * @param quality   质量码
         * @param value     值文本，可为 {@code null}
         */
        public WritePoint(Instant timestamp, int quality, String value) {
            this.timestamp = timestamp;
            this.quality = quality;
            this.value = value;
        }

        /**
         * 返回写入时间。
         *
         * @return 写入时刻，可能为 {@code null}
         */
    public Instant getTimestamp() {
            return timestamp;
        }

        /**
         * 返回质量码。
         *
         * @return 原始质量码
         */
        public int getQuality() {
            return quality;
        }

        /**
         * 返回值文本。
         *
         * @return 值字符串，可能为 {@code null}
         */
        public String getValue() {
            return value;
        }
    }
}
