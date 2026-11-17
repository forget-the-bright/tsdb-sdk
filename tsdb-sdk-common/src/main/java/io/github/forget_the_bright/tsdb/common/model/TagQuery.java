package io.github.forget_the_bright.tsdb.common.model;

/**
 * 位号目录查询条件。
 *
 * <p>由 {@code TagCatalog#listTags(TagQuery)} 消费，用于把“查哪些位号、取多少条、
 * 从第几条开始”描述清楚。所有字段都是可选的：不设置任何过滤条件时，
 * 表示按默认分页列出位号目录。</p>
 *
 * <p>名称通配符语义：{@link #getNameMask()} 使用 shell 风格通配符，
 * {@code *} 匹配任意长度（含 0）的字符序列，{@code ?} 匹配恰好一个字符。
 * 例如 {@code "TMP_*"} 匹配所有以 {@code TMP_} 开头的位号，
 * {@code "AI_?_01"} 匹配 {@code AI_1_01}、{@code AI_A_01} 但不匹配
 * {@code AI_10_01}。通配符的匹配是否区分大小写由驱动遵守厂商语义，
 * 但同一驱动内必须保持一致。</p>
 *
 * <p>分页语义：{@link #getOffset()} 是跳过的条数，{@link #getLimit()}
 * 是最多返回的条数。{@code limit = 0} 表示“只要总数/不做条数限制”，
 * 具体行为由驱动解释（{@code TagCatalog#getTag} 的默认实现正是借助
 * {@code limit = 0} 来避免分页截断）。offset 与 limit 都必须
 * {@code >= 0}，否则 {@link Builder#build()} 抛
 * {@link IllegalArgumentException}。</p>
 *
 * <p>不可变性与线程安全：所有字段为 {@code final}，构造后不可变，
 * 可安全地在多线程间共享。</p>
 *
 * <p>构造请使用 {@link #builder()}。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class TagQuery {

    /**
     * 默认分页大小：{@code 100} 条。
     *
     * <p>调用方未显式设置 {@link Builder#limit(int)} 时使用，
     * 避免一次性拉取过大的位号目录。</p>
     */
    public static final int DEFAULT_LIMIT = 100;

    /**
     * 名称通配符，可能为 {@code null}（表示不做名称过滤）。
     */
    private final String nameMask;

    /**
     * 最多返回条数，{@code >= 0}。
     */
    private final int limit;

    /**
     * 跳过的条数，{@code >= 0}。
     */
    private final int offset;

    /**
     * 按值类型过滤，可能为 {@code null}（表示不过滤）。
     */
    private final ValueType valueType;

    /**
     * 按工程单位过滤，可能为 {@code null}（表示不过滤）。
     */
    private final String unit;

    /**
     * 私有构造器，仅由 {@link Builder#build()} 调用，并在此完成参数校验。
     *
     * @param builder 已填充完毕的构造器，不能为 {@code null}
     * @throws IllegalArgumentException 当 {@code limit} 或 {@code offset} 为负数时
     */
    private TagQuery(Builder builder) {
        if (builder.limit < 0) {
            throw new IllegalArgumentException("limit must be >= 0");
        }
        if (builder.offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0");
        }
        this.nameMask = builder.nameMask;
        this.limit = builder.limit;
        this.offset = builder.offset;
        this.valueType = builder.valueType;
        this.unit = builder.unit;
    }

    /**
     * 创建查询构造器。
     *
     * <p>初始状态：{@code nameMask = null}、{@code limit = 100}、
     * {@code offset = 0}、{@code valueType = null}、{@code unit = null}。
     * 构造器可变且非线程安全，不应跨线程共享。</p>
     *
     * @return 新的构造器实例，非 {@code null}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 返回名称通配符。
     *
     * @return 通配符表达式，可能为 {@code null}（不做名称过滤）；
     *         语法见类说明（{@code *} 与 {@code ?}）
     */
    public String getNameMask() {
        return nameMask;
    }

    /**
     * 返回最多返回的条数。
     *
     * @return 非负整数，默认 {@value #DEFAULT_LIMIT}；
     *         {@code 0} 的语义由驱动解释，通常表示不限条数
     */
    public int getLimit() {
        return limit;
    }

    /**
     * 返回跳过的条数（分页偏移量）。
     *
     * @return 非负整数，默认 {@code 0}
     */
    public int getOffset() {
        return offset;
    }

    /**
     * 返回值类型过滤条件。
     *
     * @return 值类型，可能为 {@code null}（表示不按类型过滤）；
     *         驱动若不支持该过滤条件应抛 {@code TSDB-UNSUPPORTED}
     */
    public ValueType getValueType() {
        return valueType;
    }

    /**
     * 返回工程单位过滤条件。
     *
     * @return 单位文本，可能为 {@code null}（表示不按单位过滤）；
     *         匹配为精确相等（不区分前后空白由驱动决定）；
     *         驱动若不支持该过滤条件应抛 {@code TSDB-UNSUPPORTED}
     */
    public String getUnit() {
        return unit;
    }

    /**
     * {@link TagQuery} 的链式构造器。
     *
     * <p>可变、<b>非线程安全</b>；构建出的 {@link TagQuery} 不可变。</p>
     *
     * @author wanghao
     * @since 0.1.0
     */
    public static final class Builder {
        /**
         * 名称通配符，默认 {@code null}。
         */
        private String nameMask;

        /**
         * 最多返回条数，默认 {@link TagQuery#DEFAULT_LIMIT}。
         */
        private int limit = DEFAULT_LIMIT;

        /**
         * 分页偏移，默认 {@code 0}。
         */
        private int offset;

        /**
         * 值类型过滤，默认 {@code null}。
         */
        private ValueType valueType;

        /**
         * 单位过滤，默认 {@code null}。
         */
        private String unit;

        /**
         * 私有构造器，请通过 {@link TagQuery#builder()} 获取。
         */
        private Builder() {
        }

        /**
         * 设置名称通配符。
         *
         * @param nameMask 通配符表达式，允许为 {@code null}（不过滤）
         * @return {@code this}，便于链式调用
         */
        public Builder nameMask(String nameMask) {
            this.nameMask = nameMask;
            return this;
        }

        /**
         * 设置最多返回条数。
         *
         * @param limit 条数上限，必须 {@code >= 0}；
         *              负数将在 {@link #build()} 时抛
         *              {@link IllegalArgumentException}
         * @return {@code this}，便于链式调用
         */
        public Builder limit(int limit) {
            this.limit = limit;
            return this;
        }

        /**
         * 设置分页偏移量（跳过的条数）。
         *
         * @param offset 偏移量，必须 {@code >= 0}；
         *               负数将在 {@link #build()} 时抛
         *               {@link IllegalArgumentException}
         * @return {@code this}，便于链式调用
         */
        public Builder offset(int offset) {
            this.offset = offset;
            return this;
        }

        /**
         * 设置值类型过滤条件。
         *
         * @param valueType 值类型，允许为 {@code null}（不过滤）
         * @return {@code this}，便于链式调用
         */
        public Builder valueType(ValueType valueType) {
            this.valueType = valueType;
            return this;
        }

        /**
         * 设置工程单位过滤条件。
         *
         * @param unit 单位文本，允许为 {@code null}（不过滤）
         * @return {@code this}，便于链式调用
         */
        public Builder unit(String unit) {
            this.unit = unit;
            return this;
        }

        /**
         * 校验当前参数并构建不可变的 {@link TagQuery}。
         *
         * @return 非 {@code null} 的查询条件对象
         * @throws IllegalArgumentException 当 {@code limit} 或 {@code offset} 为负数时
         */
        public TagQuery build() {
            return new TagQuery(this);
        }
    }
}
