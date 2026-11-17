package io.github.forget_the_bright.tsdb.iotdb;

import io.github.forget_the_bright.tsdb.common.config.TimeConfig;
import io.github.forget_the_bright.tsdb.common.model.Quality;
import io.github.forget_the_bright.tsdb.common.model.Sample;
import io.github.forget_the_bright.tsdb.common.model.TagInfo;
import io.github.forget_the_bright.tsdb.common.model.ValueType;
import io.github.forget_the_bright.tsdb.iotdb.internal.IotdbModels;

/**
 * 把 IoTDB 的元数据与点位值映射为厂商无关的公共模型。
 *
 * <p><b>职责</b>：集中承担 {@link IotdbModels.Tag} → {@link TagInfo}、
 * {@link IotdbModels.Point} → {@link Sample} 的转换，以及 IoTDB 数据类型字符串
 * → {@link ValueType} 的推断，避免映射规则散落在客户端各处。</p>
 *
 * <p><b>设计要点</b>：</p>
 * <ul>
 *   <li>纯静态工具类，无状态、无外部依赖，便于单独测试与复用；</li>
 *   <li>位号映射保留原始类型（{@code vendorType}）与别名（{@code description}），
 *       并在存在数据库归属时写入 {@code database} 属性，不丢失厂商信息；</li>
 *   <li>类型推断是对大写后的类型串做子串匹配，顺序为
 *       {@code BOOLEAN → INT → FLOAT/DOUBLE → TEXT/STRING → BLOB}，
 *       因此 {@code INT64}、{@code BIGINT} 归为 {@code LONG}，
 *       {@code FLOAT}、{@code DOUBLE} 归为 {@code DOUBLE}；</li>
 *   <li>点位映射依据 JSON 反序列化后的运行时类型，而非位号声明的类型：
 *       布尔→{@code BOOLEAN}，{@code Long}/{@code Integer}→{@code LONG}，
 *       其他 {@code Number}→{@code DOUBLE}，其余一律 {@code String.valueOf} 后
 *       作为 {@code STRING}。</li>
 * </ul>
 *
 * <p><b>质量码语义（重要）</b>：IoTDB 不返回逐点质量码，因此本映射无法还原真实质量。
 * 约定为非空值一律标记 {@link Quality#GOOD}，空值标记
 * {@link Quality#UNKNOWN} 并使用 {@code null} 值；调用方不能据此区分
 * “设备正常上报”与“驱动推断”，也不存在 {@code BAD}、{@code STALE} 等状态。</p>
 *
 * <p><b>线程安全</b>：全部方法为静态无状态方法，可被任意线程并发调用。</p>
 *
 * <p><b>限制</b>：类型推断是启发式的，无法识别 IoTDB 后续新增的类型；
 * {@code BLOB} 字段的实际值若以 base64 字符串下发，会按 {@code STRING} 处理而非
 * {@code BYTES}；时间戳统一使用 {@link java.time.Instant}，由调用方保证时区语义。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class IotdbMapper {

    /**
     * 私有构造器：本类是纯静态工具类，不允许实例化。
     */
    private IotdbMapper() {
    }

    /**
     * 把 IoTDB 位号元数据转换为公共位号描述。
     *
     * <p>字段对应关系：{@code name} → 位号名，{@code alias} → 描述
     * （可能为 {@code null}），{@code dataType} → 原始类型，并由
     * {@link #valueType(String)} 推断公共值类型；{@code database} 非空时
     * 额外写入名为 {@code database} 的属性，供调用方区分同名的多库部署。</p>
     *
     * @param tag 厂商位号对象，不能为 {@code null}
     * @return 非 {@code null} 的公共位号描述
     */
    public static TagInfo toTagInfo(IotdbModels.Tag tag) {
        TagInfo.Builder builder = TagInfo.builder(tag.getName())
                .description(tag.getAlias())
                .vendorType(tag.getDataType())
                .valueType(valueType(tag.getDataType()));
        if (tag.getDatabase() != null) {
            builder.attribute("database", tag.getDatabase());
        }
        return builder.build();
    }

    /**
     * 把 IoTDB 数据类型字符串推断为公共值类型。
     *
     * <p>匹配前先去除首尾空白并转为大写，再做子串包含判断，因此
     * {@code "int64"}、{@code "INT32"}、{@code "BIGINT"} 都会命中 {@code INT}；
     * 判断顺序决定优先级，例如 {@code "DOUBLE"} 不含 {@code INT}，不受影响。
     * 无法识别时返回 {@link ValueType#UNKNOWN}，而不是抛异常。</p>
     *
     * @param dataType 厂商类型名，可为 {@code null} 或空白
     * @return 非 {@code null} 的公共值类型；无法识别时为 {@code UNKNOWN}
     */
    public static ValueType valueType(String dataType) {
        if (dataType == null) {
            return ValueType.UNKNOWN;
        }
        String type = dataType.trim().toUpperCase();
        if (type.contains("BOOLEAN")) {
            return ValueType.BOOLEAN;
        }
        if (type.contains("INT")) {
            return ValueType.LONG;
        }
        if (type.contains("FLOAT") || type.contains("DOUBLE")) {
            return ValueType.DOUBLE;
        }
        if (type.contains("TEXT") || type.contains("STRING")) {
            return ValueType.STRING;
        }
        if (type.contains("BLOB")) {
            return ValueType.BYTES;
        }
        return ValueType.UNKNOWN;
    }

    /**
     * 把 IoTDB 单个点位转换为公共样本。
     *
     * <p>按运行时类型分派：{@code null} → 空值样本（类型、质量均为
     * {@code UNKNOWN}）；{@link Boolean} → {@code BOOLEAN}；
     * {@link Long}/{@link Integer} → {@code LONG}；其他 {@link Number} →
     * {@code DOUBLE}；剩余类型（含字符串、时间戳文本等）→ {@code STRING}。
     * 除空值外质量一律为 {@link Quality#GOOD}，因为 IoTDB 不提供逐点质量码。</p>
     *
     * @param point 厂商点位，不能为 {@code null}
     * @return 非 {@code null} 的公共样本，时间戳原样保留
     */
    public static Sample toSample(IotdbModels.Point point) {
        return toSample(point, null);
    }

    /**
     * 把 IoTDB 单个点位转换为公共样本，并用实例时间配置生成展示时间。
     *
     * @param point      厂商点位，不能为 {@code null}
     * @param timeConfig 实例时间配置，允许为 {@code null}
     * @return 非 {@code null} 的公共样本
     */
    public static Sample toSample(IotdbModels.Point point, TimeConfig timeConfig) {
        Object value = point.getValue();
        if (value == null) {
            return Sample.ofNull(point.getTimestamp(), ValueType.UNKNOWN, Quality.UNKNOWN, null)
                    .withTimeFormat(timeConfig);
        }
        if (value instanceof Boolean) {
            return Sample.ofBoolean(point.getTimestamp(), (Boolean) value, Quality.GOOD, null)
                    .withTimeFormat(timeConfig);
        }
        if (value instanceof Long || value instanceof Integer) {
            return Sample.ofLong(point.getTimestamp(), ((Number) value).longValue(), Quality.GOOD, null)
                    .withTimeFormat(timeConfig);
        }
        if (value instanceof Number) {
            return Sample.ofDouble(point.getTimestamp(), ((Number) value).doubleValue(), Quality.GOOD, null)
                    .withTimeFormat(timeConfig);
        }
        String text = String.valueOf(value).trim();
        try {
            if (text.indexOf('.') < 0 && text.indexOf('e') < 0 && text.indexOf('E') < 0) {
                return Sample.ofLong(point.getTimestamp(), Long.parseLong(text), Quality.GOOD, null)
                        .withTimeFormat(timeConfig);
            }
            return Sample.ofDouble(point.getTimestamp(), Double.parseDouble(text), Quality.GOOD, null)
                    .withTimeFormat(timeConfig);
        } catch (NumberFormatException ignored) {
            return Sample.ofString(point.getTimestamp(), text, Quality.GOOD, null)
                    .withTimeFormat(timeConfig);
        }
    }
}
