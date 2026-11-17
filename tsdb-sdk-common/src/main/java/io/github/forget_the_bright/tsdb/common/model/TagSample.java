package io.github.forget_the_bright.tsdb.common.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 单个位号的当前值查询结果。
 *
 * <p>这是 {@code CurrentValueReader#read(Collection)} 的元素类型，
 * 把“位号名”与“该位号的采样点”绑定在一起返回——因为批量读取时
 * 调用方需要知道每个结果属于哪个位号，而
 * {@link Sample} 本身并不携带位号信息。</p>
 *
 * <p>语义要点：{@link #getSample()} 允许为 {@code null}，表示该位号当前
 * 没有可用值（例如位号不存在、从未采集过数据、或已被删除）。这与
 * {@link Sample#isNull()} 为 {@code true}（点存在但值为空）是不同的情况，
 * 调用方需要分别判断。</p>
 *
 * <p>附加属性 {@link #getAttributes()}：驱动可以把厂商返回的、
 * {@link Sample} 无法承载的元信息以字符串键值对的形式放在这里。
 * 公共层约定的键（驱动应尽量填充，缺失时不出现对应键）：</p>
 * <ul>
 *   <li>{@code errorCode}：厂商侧该位号的错误码，{@code "0"} 表示正常；</li>
 *   <li>{@code errorMessage}：错误码对应的厂商错误描述；</li>
 *   <li>{@code dataType}：位号的数据类型（厂商原始字符串，如 GE 的
 *       {@code "Float"}、{@code "Double"}）；</li>
 *   <li>{@code engineeringUnits}：工程单位（如 {@code "t"}、{@code "kWh"}）；</li>
 *   <li>{@code hiEngineeringUnits}：工程量程上限；</li>
 *   <li>{@code loEngineeringUnits}：工程量程下限。</li>
 * </ul>
 *
 * <p><b>“无值”与“出错”的区分</b>：{@code sample == null} 且
 * {@code errorCode} 存在且不为 {@code "0"} 时，表示该位号<b>查询出错</b>
 * （错误详情见 {@code errorCode}/{@code errorMessage}）；
 * {@code sample == null} 且没有错误码（或错误码为 {@code "0"}）时，
 * 才表示该位号确实没有可用值。调用方在
 * {@code partialResultAllowed=true} 的场景下必须检查属性，
 * 才能区分这两种情况。</p>
 *
 * <p>不可变性与线程安全：所有字段均为 {@code final}，位号名在构造时做非空校验，
 * 属性表在构造时做防御性拷贝并以 {@link Collections#unmodifiableMap} 包装，
 * 实例构造后不可变，可安全地在多线程间共享。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class TagSample {

    /**
     * 属性键：厂商侧该位号的错误码，{@code "0"} 表示正常。
     */
    public static final String ATTR_ERROR_CODE = "errorCode";

    /**
     * 属性键：错误码对应的厂商错误描述。
     */
    public static final String ATTR_ERROR_MESSAGE = "errorMessage";

    /**
     * 属性键：位号数据类型（厂商原始字符串）。
     */
    public static final String ATTR_DATA_TYPE = "dataType";

    /**
     * 属性键：工程单位。
     */
    public static final String ATTR_ENGINEERING_UNITS = "engineeringUnits";

    /**
     * 属性键：工程量程上限。
     */
    public static final String ATTR_HI_ENGINEERING_UNITS = "hiEngineeringUnits";

    /**
     * 属性键：工程量程下限。
     */
    public static final String ATTR_LO_ENGINEERING_UNITS = "loEngineeringUnits";

    /**
     * 位号名称，非 {@code null}。
     */
    private final String tagName;

    /**
     * 该位号的当前值，可能为 {@code null}。
     */
    private final Sample sample;

    /**
     * 附加属性表，非 {@code null}、只读；无属性时为空表。
     */
    private final Map<String, String> attributes;

    /**
     * 构造一个“位号 + 当前值”的结果（不带附加属性）。
     *
     * @param tagName 位号名称，不能为 {@code null}
     * @param sample  该位号的采样点，允许为 {@code null}（表示当前无值）
     * @throws NullPointerException 当 {@code tagName} 为 {@code null} 时
     */
    public TagSample(String tagName, Sample sample) {
        this(tagName, sample, null);
    }

    /**
     * 构造一个“位号 + 当前值 + 附加属性”的结果。
     *
     * <p>属性表会做防御性拷贝；{@code null} 键或 {@code null} 值会被忽略，
     * 不会进入结果属性表。约定的键见类级文档。</p>
     *
     * @param tagName    位号名称，不能为 {@code null}
     * @param sample     该位号的采样点，允许为 {@code null}（表示当前无值或查询出错，
     *                   二者通过 {@code errorCode} 属性区分，见类级文档）
     * @param attributes 附加属性表，允许为 {@code null}（等价于空表）
     * @throws NullPointerException 当 {@code tagName} 为 {@code null} 时
     */
    public TagSample(String tagName, Sample sample, Map<String, String> attributes) {
        this.tagName = Objects.requireNonNull(tagName, "tagName");
        this.sample = sample;
        if (attributes == null || attributes.isEmpty()) {
            this.attributes = Collections.emptyMap();
        } else {
            Map<String, String> copy = new LinkedHashMap<String, String>();
            for (Map.Entry<String, String> entry : attributes.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    copy.put(entry.getKey(), entry.getValue());
                }
            }
            this.attributes = Collections.unmodifiableMap(copy);
        }
    }

    /**
     * 静态工厂，等价于 {@code new TagSample(tagName, sample)}，
     * 便于在流式代码中直接引用。
     *
     * @param tagName 位号名称，不能为 {@code null}
     * @param sample  该位号的采样点，允许为 {@code null}
     * @return 非 {@code null} 的结果对象
     * @throws NullPointerException 当 {@code tagName} 为 {@code null} 时
     */
    public static TagSample of(String tagName, Sample sample) {
        return new TagSample(tagName, sample);
    }

    /**
     * 静态工厂（带附加属性版本），等价于
     * {@code new TagSample(tagName, sample, attributes)}。
     *
     * @param tagName    位号名称，不能为 {@code null}
     * @param sample     该位号的采样点，允许为 {@code null}
     * @param attributes 附加属性表，允许为 {@code null}（等价于空表）
     * @return 非 {@code null} 的结果对象
     * @throws NullPointerException 当 {@code tagName} 为 {@code null} 时
     */
    public static TagSample of(String tagName, Sample sample, Map<String, String> attributes) {
        return new TagSample(tagName, sample, attributes);
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
     * 返回该位号的当前值采样点。
     *
     * @return 采样点，可能为 {@code null}（表示该位号当前没有可用值，
     *         或该位号查询出错——后者可通过 {@link #getAttributes()} 中的
     *         {@code errorCode} 属性区分）；非 {@code null} 时其时间戳
     *         即该值的最新采集时刻
     */
    public Sample getSample() {
        return sample;
    }

    /**
     * 返回附加属性表。
     *
     * <p>约定的键见类级文档（{@link #ATTR_ERROR_CODE} 等常量）。
     * 驱动未提供属性时返回空表，不会返回 {@code null}。</p>
     *
     * @return 非 {@code null} 的只读属性表，可能为空
     */
    public Map<String, String> getAttributes() {
        return attributes;
    }
}