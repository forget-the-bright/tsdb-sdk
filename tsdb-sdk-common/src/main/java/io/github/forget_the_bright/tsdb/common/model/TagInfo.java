package io.github.forget_the_bright.tsdb.common.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 归一化后的位号元数据。
 *
 * <p>描述一个时间序列位号“是什么”：名称、描述、值类型、工程单位、厂商原始类型
 * 以及一组自定义属性。由 {@code TagCatalog#listTags} / {@code TagCatalog#getTag}
 * 返回，用于位号浏览、点位初始化和数据解释（例如根据 {@link #getUnit()}
 * 给曲线标注单位）。</p>
 *
 * <p>字段语义：</p>
 * <ul>
 *   <li>{@link #getTagName()}：位号唯一名称，必填，是 SDK 内引用位号的键；</li>
 *   <li>{@link #getDescription()}：人类可读描述，可能为 {@code null}；</li>
 *   <li>{@link #getValueType()}：归一化值类型，非 {@code null}，未设置时为
 *       {@link ValueType#UNKNOWN}；</li>
 *   <li>{@link #getUnit()}：工程单位（如 {@code "℃"}、{@code "MPa"}），
 *       可能为 {@code null}（无量纲或厂商未提供）；</li>
 *   <li>{@link #getVendorType()}：厂商原始类型标识（如 {@code "Float"}、
 *       {@code "Digital"}），用于对接厂商专有逻辑，可能为 {@code null}；</li>
 *   <li>{@link #getAttributes()}：附加属性键值对，如量程、采集周期、所属设备等，
 *       保持插入顺序，可能为空但不会为 {@code null}。</li>
 * </ul>
 *
 * <p>不可变性与线程安全：属性表在构造时被复制并包装为不可修改的
 * {@link LinkedHashMap}，其余字段为 {@code final}，实例完全不可变，
 * 可安全地在多线程间共享。</p>
 *
 * <p>构造请使用 {@link #builder(String)}。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class TagInfo {

    /**
     * 位号名称，非 {@code null}。
     */
    private final String tagName;

    /**
     * 位号描述，可能为 {@code null}。
     */
    private final String description;

    /**
     * 归一化值类型，非 {@code null}。
     */
    private final ValueType valueType;

    /**
     * 工程单位，可能为 {@code null}。
     */
    private final String unit;

    /**
     * 厂商原始类型标识，可能为 {@code null}。
     */
    private final String vendorType;

    /**
     * 附加属性，非 {@code null}、不可修改、保持插入顺序。
     */
    private final Map<String, String> attributes;

    /**
     * 私有构造器，仅由 {@link Builder#build()} 调用。
     *
     * @param builder 已填充完毕的构造器，不能为 {@code null}
     * @throws NullPointerException 当 {@code builder.tagName} 为 {@code null} 时
     */
    private TagInfo(Builder builder) {
        this.tagName = Objects.requireNonNull(builder.tagName, "tagName");
        this.description = builder.description;
        this.valueType = builder.valueType == null ? ValueType.UNKNOWN : builder.valueType;
        this.unit = builder.unit;
        this.vendorType = builder.vendorType;
        this.attributes = builder.attributes.isEmpty()
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(builder.attributes));
    }

    /**
     * 以位号名创建构造器。
     *
     * <p>位号名在此处不做非空校验，而是在 {@link Builder#build()} 时由
     * {@link Objects#requireNonNull} 校验并抛 {@link NullPointerException}。</p>
     *
     * @param tagName 位号名称，不能为 {@code null}
     * @return 新的构造器实例，非 {@code null}
     */
    public static Builder builder(String tagName) {
        return new Builder(tagName);
    }

    /**
     * 返回位号名称。
     *
     * @return 非 {@code null} 的位号名
     */
    public String getTagName() {
        return tagName;
    }

    /**
     * 返回位号的人类可读描述。
     *
     * @return 描述文本，可能为 {@code null}（厂商未提供）
     */
    public String getDescription() {
        return description;
    }

    /**
     * 返回归一化值类型。
     *
     * @return 非 {@code null} 的值类型；构造时未指定则为
     *         {@link ValueType#UNKNOWN}
     */
    public ValueType getValueType() {
        return valueType;
    }

    /**
     * 返回工程单位。
     *
     * @return 单位文本，可能为 {@code null}（无量纲或厂商未提供）；
     *         不做任何规范化，原样返回厂商提供的内容
     */
    public String getUnit() {
        return unit;
    }

    /**
     * 返回厂商原始类型标识。
     *
     * <p>该值不做归一化，仅用于需要区分厂商专有语义的场景；
     * 通用逻辑应使用 {@link #getValueType()}。</p>
     *
     * @return 厂商类型标识，可能为 {@code null}
     */
    public String getVendorType() {
        return vendorType;
    }

    /**
     * 返回附加属性表。
     *
     * @return 非 {@code null} 的只读映射，保持插入顺序；无属性时为空映射
     *         （而非 {@code null}），对其做修改会抛
     *         {@link UnsupportedOperationException}
     */
    public Map<String, String> getAttributes() {
        return attributes;
    }

    /**
     * {@link TagInfo} 的链式构造器。
     *
     * <p>可变、<b>非线程安全</b>，不应跨线程共享；由它构建出的
     * {@link TagInfo} 不可变且线程安全。</p>
     *
     * @author wanghao
     * @since 0.1.0
     */
    public static final class Builder {
        /**
         * 位号名称，构造时确定，不可修改。
         */
        private final String tagName;

        /**
         * 描述，默认 {@code null}。
         */
        private String description;

        /**
         * 值类型，默认 {@link ValueType#UNKNOWN}。
         */
        private ValueType valueType = ValueType.UNKNOWN;

        /**
         * 工程单位，默认 {@code null}。
         */
        private String unit;

        /**
         * 厂商原始类型，默认 {@code null}。
         */
        private String vendorType;

        /**
         * 附加属性，保持插入顺序。
         */
        private final Map<String, String> attributes = new LinkedHashMap<String, String>();

        /**
         * 私有构造器，请通过 {@link TagInfo#builder(String)} 获取。
         *
         * @param tagName 位号名称，允许为 {@code null}（延迟到 {@code build()} 校验）
         */
        private Builder(String tagName) {
            this.tagName = tagName;
        }

        /**
         * 设置位号描述。
         *
         * @param description 描述文本，允许为 {@code null}
         * @return {@code this}，便于链式调用
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * 设置值类型。
         *
         * @param valueType 值类型，允许为 {@code null}（构建时按
         *                  {@link ValueType#UNKNOWN} 处理）
         * @return {@code this}，便于链式调用
         */
        public Builder valueType(ValueType valueType) {
            this.valueType = valueType;
            return this;
        }

        /**
         * 设置工程单位。
         *
         * @param unit 单位文本，允许为 {@code null}
         * @return {@code this}，便于链式调用
         */
        public Builder unit(String unit) {
            this.unit = unit;
            return this;
        }

        /**
         * 设置厂商原始类型标识。
         *
         * @param vendorType 厂商类型标识，允许为 {@code null}
         * @return {@code this}，便于链式调用
         */
        public Builder vendorType(String vendorType) {
            this.vendorType = vendorType;
            return this;
        }

        /**
         * 追加一个附加属性；同名键会被后写入的值覆盖。
         *
         * @param key   属性名，{@code null} 时本次调用被静默忽略（不抛异常）
         * @param value 属性值，允许为 {@code null}
         * @return {@code this}，便于链式调用
         */
        public Builder attribute(String key, String value) {
            if (key != null) {
                this.attributes.put(key, value);
            }
            return this;
        }

        /**
         * 构建不可变的 {@link TagInfo}。
         *
         * <p>会复制属性表，因此构建后继续修改本构造器不会影响已构建的实例。</p>
         *
         * @return 非 {@code null} 的位号元数据对象
         * @throws NullPointerException 当位号名为 {@code null} 时
         */
        public TagInfo build() {
            return new TagInfo(this);
        }
    }
}
