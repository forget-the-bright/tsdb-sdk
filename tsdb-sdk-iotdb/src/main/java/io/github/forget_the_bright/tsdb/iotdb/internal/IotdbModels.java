package io.github.forget_the_bright.tsdb.iotdb.internal;

import io.github.forget_the_bright.tsdb.common.config.TimeConfig;

import java.time.Instant;
import java.util.Date;
import io.github.forget_the_bright.tsdb.iotdb.internal.transport.IotdbRestApi;

/**
 * IoTDB 厂商形态的数据载体，位于归一化之前。
 *
 * <p><b>职责</b>：以最贴近 REST v2 响应的形状保存“一个位号”和“一个点位”，
 * 只做字段承载，不包含任何校验、计算或公共模型依赖。这样
 * {@link IotdbRestApi} 解析 JSON 后可以直接填充，再由
 * {@link io.github.forget_the_bright.tsdb.iotdb.IotdbMapper} 转换为
 * {@code TagInfo}/{@code Sample}，两层职责清晰分离。</p>
 *
 * <p><b>设计要点</b>：所有字段 {@code final}、无 setter，构造后不可变；
 * 容器类只提供私有构造器，真正的载体是嵌套的 {@link Tag} 与 {@link Point}。
 * 字段允许为 {@code null}（例如 IoTDB 未配置 alias、或查询结果没有数据库归属），
 * 由使用方按需判空。</p>
 *
 * <p><b>线程安全</b>：不可变对象，构造完成后可安全地在多个线程间传递。</p>
 *
 * <p><b>与公共层/原生层的关系</b>：属于 {@code internal} 包，不对外暴露；
 * 是 REST 响应与公共模型之间的中间层，刻意不引用公共层模型以免循环依赖。</p>
 *
 * <p><b>限制</b>：不携带质量码（IoTDB 无此概念）、不携带数据类型以外的元数据，
 * 也不做时间单位换算——{@link Point#getTimestamp()} 已是 {@link Instant}，
 * 换算责任在解析阶段。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class IotdbModels {

    /**
     * 私有构造器：本类只是嵌套载体的命名空间，不允许实例化。
     */
    private IotdbModels() {
    }

    /**
     * 一个 IoTDB 时间序列（位号）的元数据快照。
     *
     * <p>直接对应 {@code SHOW TIMESERIES} 返回的一行：全路径名、别名、
     * 数据类型与所属数据库。除 {@code name} 外均可能为 {@code null}。</p>
     */
    public static final class Tag {
        /**
         * 时间序列全路径，如 {@code root.factory.device1.temperature}；
         * 正常响应中非 {@code null}。
         */
        private final String name;

        /**
         * 别名（{@code alias}），可为 {@code null}；映射为公共层的描述字段。
         */
        private final String alias;

        /**
         * IoTDB 数据类型字符串，如 {@code INT64}、{@code DOUBLE}、{@code TEXT}，
         * 可为 {@code null}；由映射器推断公共值类型。
         */
        private final String dataType;

        /**
         * 所属数据库名，可为 {@code null}；非空时作为 {@code database} 属性保留。
         */
        private final String database;

        /**
         * 创建位号元数据。
         *
         * @param name     时间序列全路径，正常响应中非 {@code null}
         * @param alias    别名，可为 {@code null}
         * @param dataType 数据类型字符串，可为 {@code null}
         * @param database 所属数据库，可为 {@code null}
         */
        public Tag(String name, String alias, String dataType, String database) {
            this.name = name;
            this.alias = alias;
            this.dataType = dataType;
            this.database = database;
        }

        /**
         * 返回时间序列全路径。
         *
         * @return 位号名，可能为 {@code null}
         */
        public String getName() {
            return name;
        }

        /**
         * 返回别名。
         *
         * @return 别名，可能为 {@code null}
         */
        public String getAlias() {
            return alias;
        }

        /**
         * 返回 IoTDB 原始数据类型。
         *
         * @return 类型字符串，可能为 {@code null}
         */
        public String getDataType() {
            return dataType;
        }

        /**
         * 返回所属数据库名。
         *
         * @return 数据库名，可能为 {@code null}
         */
        public String getDatabase() {
            return database;
        }
    }

    /**
     * 一个 IoTDB 数据点：时间戳 + 任意类型的值。
     *
     * <p>{@code value} 的运行时类型取决于 JSON 反序列化结果，
     * 可能是 {@link Boolean}、{@link Long}、{@link Integer}、{@link Double}、
     * {@link String} 或 {@code null}；类型分派由
     * {@link io.github.forget_the_bright.tsdb.iotdb.IotdbMapper#toSample} 负责。</p>
     */
    public static final class Point {
        /**
         * 采样时刻，非 {@code null}（解析阶段已由毫秒时间戳换算）。
         */
        private final Instant timestamp;

        /**
         * 采样时刻的 Date 视图，便于前端直接序列化。
         */
        private final Date timestampDate;

        /**
         * 已格式化的采样时间文本，前端可直接展示。
         */
        private final String timestampText;

        /**
         * 采样值，可为 {@code null}；表示该时刻无有效值。
         */
        private final Object value;

        /**
         * 创建数据点。
         *
         * @param timestamp 采样时刻，不能为 {@code null}
         * @param value     采样值，可为 {@code null}
         */
        public Point(Instant timestamp, Object value) {
            this(timestamp, value, null);
        }

        /**
         * 创建数据点，并按实例时间配置生成 Date/展示文本。
         *
         * @param timestamp  采样时刻，不能为 {@code null}
         * @param value      采样值，可为 {@code null}
         * @param timeConfig 实例时间配置，可为 {@code null}
         */
        public Point(Instant timestamp, Object value, TimeConfig timeConfig) {
            this.timestamp = timestamp;
            this.timestampDate = timestamp == null ? null : Date.from(timestamp);
            this.timestampText = timestamp == null ? null
                    : (timeConfig == null ? timestamp.toString() : timeConfig.formatResponse(timestamp));
            this.value = value;
        }

        /**
         * 返回采样时刻。
         *
         * @return 非 {@code null} 的时间戳
         */
    public Instant getTimestamp() {
            return timestamp;
        }

        /**
         * 返回采样时刻的 {@link Date} 视图。
         *
         * @return Date，可能为 {@code null}
         */
        public Date getTimestampDate() {
            return timestampDate == null ? null : new Date(timestampDate.getTime());
        }

        /**
         * 返回格式化后的采样时间文本。
         *
         * @return 展示文本，可能为 {@code null}
         */
        public String getTimestampText() {
            return timestampText;
        }

        /**
         * 返回采样值。
         *
         * @return 原始值对象，可能为 {@code null}
         */
        public Object getValue() {
            return value;
        }
    }
}
