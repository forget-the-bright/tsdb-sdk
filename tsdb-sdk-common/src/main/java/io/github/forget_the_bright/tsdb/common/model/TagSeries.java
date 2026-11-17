package io.github.forget_the_bright.tsdb.common.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 单个位号的历史数据序列。
 *
 * <p>这是 {@code HistoryReader#read(HistoryQuery)} 的元素类型，
 * 一次查询返回多个位号时会得到多个本对象，每个对象对应
 * {@link HistoryQuery#getTags()} 中的一个位号，且顺序与请求顺序一致。</p>
 *
 * <p>字段语义：</p>
 * <ul>
 *   <li>{@link #getTagName()}：所属位号名，必填，用于把结果与请求对应起来；</li>
 *   <li>{@link #getSamples()}：按时间排好序的采样点列表，顺序由
 *       {@link HistoryQuery#getDirection()} 决定（正序升序、倒序降序）。
 *       可能为空（该区间内无数据），但永不为 {@code null}；</li>
 *   <li>{@link #isTruncated()}：结果是否被截断。为 {@code true} 表示服务端
 *       返回的点数达到了 {@link HistoryQuery#getMaxPoints()} 上限，
 *       实际数据可能更多，不应据此断言“区间内数据已取全”；</li>
 *   <li>{@link #getAttributes()}：该位号的附加属性（量程、设备、采集周期等），
 *       保持插入顺序，可能为空但不会为 {@code null}。</li>
 * </ul>
 *
 * <p>不可变性与线程安全：采样点列表与属性表都在构造时被复制并包装为不可修改视图，
 * 其余字段为 {@code final}，实例完全不可变，可安全地在多线程间共享。
 * 注意列表内元素 {@link Sample} 本身也是不可变的。</p>
 *
 * <p>构造请使用 {@link #builder(String)}。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class TagSeries {

    /**
     * 位号名称，非 {@code null}。
     */
    private final String tagName;

    /**
     * 采样点列表，非 {@code null}、不可修改、已按查询方向排序。
     */
    private final List<Sample> samples;

    /**
     * 结果是否被截断。
     */
    private final boolean truncated;

    /**
     * 位号附加属性，非 {@code null}、不可修改、保持插入顺序。
     */
    private final Map<String, String> attributes;

    /**
     * 私有构造器，仅由 {@link Builder#build()} 调用。
     *
     * @param builder 已填充完毕的构造器，不能为 {@code null}
     * @throws NullPointerException 当位号名为 {@code null} 时
     */
    private TagSeries(Builder builder) {
        this.tagName = Objects.requireNonNull(builder.tagName, "tagName");
        this.samples = Collections.unmodifiableList(new ArrayList<Sample>(builder.samples));
        this.truncated = builder.truncated;
        this.attributes = builder.attributes.isEmpty()
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(builder.attributes));
    }

    /**
     * 以位号名创建构造器。
     *
     * <p>位号名在此处不做非空校验，而是在 {@link Builder#build()} 时校验。</p>
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
     * 返回该位号的采样点列表。
     *
     * <p>顺序由查询的 {@link HistoryQuery#getDirection()} 决定；
     * 返回的是不可修改视图，对其做增删会抛
     * {@link UnsupportedOperationException}。</p>
     *
     * @return 非 {@code null} 的只读列表；该区间无数据时为空列表
     */
    public List<Sample> getSamples() {
        return samples;
    }

    /**
     * 判断结果是否被截断。
     *
     * <p>为 {@code true} 表示返回点数已达到查询上限
     * （通常是 {@link HistoryQuery#getMaxPoints()}），并不代表数据有错误。
     * 需要完整数据时应缩小时间区间或提高上限后重新查询。</p>
     *
     * @return 结果被截断返回 {@code true}，否则 {@code false}（默认）
     */
    public boolean isTruncated() {
        return truncated;
    }

    /**
     * 返回该位号的附加属性。
     *
     * @return 非 {@code null} 的只读映射，保持插入顺序；无属性时为空映射，
     *         修改会抛 {@link UnsupportedOperationException}
     */
    public Map<String, String> getAttributes() {
        return attributes;
    }

    /**
     * {@link TagSeries} 的链式构造器。
     *
     * <p>可变、<b>非线程安全</b>，不应跨线程共享；构建出的
     * {@link TagSeries} 不可变且线程安全。</p>
     *
     * <p>注意 {@link #add(Sample)} 是追加语义，而 {@link #samples(List)}
     * 是整体替换语义。</p>
     *
     * @author wanghao
     * @since 0.1.0
     */
    public static final class Builder {
        /**
         * 位号名称，构造时确定。
         */
        private final String tagName;

        /**
         * 采样点列表，可变。
         */
        private final List<Sample> samples = new ArrayList<Sample>();

        /**
         * 截断标记，默认 {@code false}。
         */
        private boolean truncated;

        /**
         * 附加属性，保持插入顺序。
         */
        private final Map<String, String> attributes = new LinkedHashMap<String, String>();

        /**
         * 私有构造器，请通过 {@link TagSeries#builder(String)} 获取。
         *
         * @param tagName 位号名称，允许为 {@code null}（延迟到 {@code build()} 校验）
         */
        private Builder(String tagName) {
            this.tagName = tagName;
        }

        /**
         * 追加一个采样点。
         *
         * <p>传入 {@code null} 会被静默忽略（不抛异常），便于在循环中
         * 直接添加可能为空的结果。本方法不做时间排序，调用方需自行保证
         * 添加顺序与 {@link HistoryQuery#getDirection()} 一致。</p>
         *
         * @param sample 采样点，允许为 {@code null}
         * @return {@code this}，便于链式调用
         */
        public Builder add(Sample sample) {
            if (sample != null) {
                this.samples.add(sample);
            }
            return this;
        }

        /**
         * 整体替换采样点列表。
         *
         * <p>会先清空已有采样点，再逐个通过 {@link #add(Sample)} 添加，
         * 因此 {@code null} 元素会被过滤掉。传入 {@code null} 等价于清空。</p>
         *
         * @param samples 采样点列表，允许为 {@code null}
         * @return {@code this}，便于链式调用
         */
        public Builder samples(List<Sample> samples) {
            this.samples.clear();
            if (samples != null) {
                for (Sample sample : samples) {
                    add(sample);
                }
            }
            return this;
        }

        /**
         * 设置截断标记。
         *
         * @param truncated {@code true} 表示结果因达到查询上限而被截断
         * @return {@code this}，便于链式调用
         */
        public Builder truncated(boolean truncated) {
            this.truncated = truncated;
            return this;
        }

        /**
         * 追加一个附加属性；同名键会被后写入的值覆盖。
         *
         * @param key   属性名，{@code null} 时本次调用被静默忽略
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
         * 构建不可变的 {@link TagSeries}。
         *
         * <p>会复制采样点列表与属性表，构建后继续修改本构造器不会影响
         * 已构建的实例。</p>
         *
         * @return 非 {@code null} 的历史序列对象
         * @throws NullPointerException 当位号名为 {@code null} 时
         */
        public TagSeries build() {
            return new TagSeries(this);
        }
    }
}
