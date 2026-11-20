package io.github.forget_the_bright.tsdb.common.model;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * 不可变的历史数据查询条件。
 *
 * <p>这是 {@code HistoryReader#read(HistoryQuery)} 的唯一入参，把“查哪些位号、
 * 查哪段时间、用哪种模式、怎么聚合/插值、最多要多少个点”等信息一次性描述清楚，
 * 由各驱动翻译成对应厂商的查询语句。构造必须通过 {@link #builder()}，
 * 构造器私有，因此实例一旦创建就不可变。</p>
 *
 * <p>时间区间语义：<b>左闭右开</b>，即 {@code [start, end)}。时间戳恰好等于
 * {@code start} 的点会被包含，恰好等于 {@code end} 的点会被排除。这样相邻两个
 * 区间首尾相接时不会重复取到边界点。</p>
 *
 * <p>构造期校验（在 {@link Builder#build()} 中执行，违反则抛
 * {@link IllegalArgumentException}）：</p>
 * <ul>
 *   <li>位号列表不能为空，且每个位号不能为 {@code null} 或全空白；</li>
 *   <li>{@code start}、{@code end} 都必填，且必须满足 {@code start < end}
 *       （相等也不允许，即不允许零长度区间）；</li>
 *   <li>{@code maxPoints} 必须为正数；</li>
 *   <li>{@code mode} 为 {@code null} 时按 {@link QueryMode#RAW} 处理；</li>
 *   <li>{@link QueryMode#AGGREGATED} 模式必须指定 {@link Aggregation}；</li>
 *   <li>{@link QueryMode#AGGREGATED}、{@link QueryMode#INTERPOLATED}、
 *       {@link QueryMode#SAMPLED} 三种模式必须指定正的间隔
 *       （{@code interval} 非空且非零、非负）<b>或</b>正的服务端点数
 *       （{@code count} 非空且为正），二者至少设置一个，可同时设置；
 *       <b>例外</b>：{@link QueryMode#AGGREGATED} + {@link Aggregation#INTERVAL_SUM}
 *       是整窗“末值减首值”的单点区间增量，不分片，{@code interval} 与
 *       {@code count} 均非必填；</li>
 *   <li>{@code count} 若设置则必须为正数。设置了 {@code interval} 时，
 *       需要下发服务端点数的驱动（当前为 GE）会用
 *       {@link HistorianUnit#resolveCount(Integer, java.time.Instant, java.time.Instant, long)}
 *       推导点数，此时 {@code count} 可以省略；一旦给出就必须等于推导值，否则抛
 *       {@link IllegalArgumentException}。真正必须显式 {@code count} 的只有“没有间隔”的场景
 *       （纯 count 等分网格）与 RAW 的点数上限。</li>
 * </ul>
 *
 * <p>默认值：{@code mode = RAW}、{@code maxPoints = 10000}、
 * {@code direction = FORWARD}、{@code fill = NONE}、
 * {@code partialResultAllowed = true}、{@code includeQuality = true}。</p>
 *
 * <p>不可变性与线程安全：位号列表以
 * {@link Collections#unmodifiableList} 包装的防御性副本保存，
 * 所有其他字段均为 {@code final} 的不可变值（{@link Instant}、
 * {@link Duration}、枚举），因此本类实例完全不可变，可安全地在多线程间共享。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class HistoryQuery {

    /**
     * 单次查询默认返回的最大采样点数：{@code 10000}。
     *
     * <p>用于在调用方未显式设置 {@link Builder#maxPoints(int)} 时兜底，
     * 防止一次误查把海量历史点全部拉进内存。该值只是默认值，
     * 不构成服务端上限。</p>
     */
    public static final int DEFAULT_MAX_POINTS = 10000;

    /**
     * 待查询的位号名列表，非空、元素非空白、不可修改。
     */
    private final List<String> tags;

    /**
     * 查询区间起始时刻（含），非 {@code null}。
     */
    private final Instant start;

    /**
     * 查询区间结束时刻（不含），非 {@code null}。
     */
    private final Instant end;

    /**
     * 查询模式，非 {@code null}，默认 {@link QueryMode#RAW}。
     */
    private final QueryMode mode;

    /**
     * 聚合函数，仅在 {@link QueryMode#AGGREGATED} 模式下必填，
     * 其他模式下允许为 {@code null}。
     */
    private final Aggregation aggregation;

    /**
     * 聚合/插值/采样的时间片宽度，可能为 {@code null}。
     * 设置时保证为正的 {@link Duration}；在需要间隔的模式下，
     * 若未设置 interval 则必须设置正的 {@link #count} 作为替代。
     */
    private final Duration interval;

    /**
     * 服务端点数参数，可能为 {@code null}（未设置）。
     *
     * <p>与 {@code interval} 描述的是同一件事的两个维度：interval 指定
     * “每隔多久取一个点”，count 指定“一共取多少个点”。设置了 interval 时，
     * 点数可由 {@link HistorianUnit#resolveCount(Integer, java.time.Instant,
     * java.time.Instant, long)} 精确推导，调用方不必再填；若仍填写，必须与推导值
     * 一致，否则驱动抛 {@link IllegalArgumentException}。当前只有 GE 走这条推导，
     * HLS / IoTDB 直接按 {@code interval} 下发、不校验 {@code count}。部分厂商接口
     * （如 GE 的 calculated/interpolated）在只有 count 时会配合
     * {@code intervalMs=1}，用“count=1”复刻取整窗首/末值的惯用法，这类
     * <b>没有间隔</b>的用法仍需显式给出 count。</p>
     *
     * <p>注意与 {@code maxPoints} 的区别：count 是<b>服务端查询参数</b>，
     * 影响厂商实际计算与返回；maxPoints 是<b>客户端兜底截断</b>，
     * 只在结果超限时裁剪，不下发给厂商（驱动另有约定时除外）。</p>
     */
    private final Integer count;

    /**
     * 允许返回的最大点数，恒为正数。
     */
    private final int maxPoints;

    /**
     * 读取方向，非 {@code null}，默认 {@link Direction#FORWARD}。
     */
    private final Direction direction;

    /**
     * 插值补点策略，非 {@code null}，默认 {@link FillPolicy#NONE}。
     */
    private final FillPolicy fill;

    /**
     * 是否允许返回不完整（被截断/部分失败）的结果。
     */
    private final boolean partialResultAllowed;

    /**
     * 是否要求结果携带质量码信息。
     */
    private final boolean includeQuality;

    /**
     * 私有构造器，仅由 {@link Builder#build()} 调用，并在其中完成全部校验与默认值填充。
     *
     * @param builder 已填充完毕的构造器，不能为 {@code null}
     * @throws IllegalArgumentException 当位号列表为空、位号为空或全空白、
     *                                  {@code start}/{@code end} 缺失或顺序错误、
     *                                  {@code maxPoints} 非正、{@code count} 非正、
     *                                  {@code AGGREGATED} 模式缺少聚合函数、
     *                                  或需要间隔的模式同时缺少正的间隔与正的 count 时
     */
    private HistoryQuery(Builder builder) {
        if (builder.tags.isEmpty()) {
            throw new IllegalArgumentException("tags must not be empty");
        }
        List<String> copy = new ArrayList<String>(builder.tags.size());
        for (String tag : builder.tags) {
            if (tag == null || tag.trim().isEmpty()) {
                throw new IllegalArgumentException("tag name must not be blank");
            }
            copy.add(tag);
        }
        if (builder.start == null || builder.end == null) {
            throw new IllegalArgumentException("start and end are required");
        }
        if (!builder.start.isBefore(builder.end)) {
            throw new IllegalArgumentException("start must be before end");
        }
        if (builder.maxPoints <= 0) {
            throw new IllegalArgumentException("maxPoints must be > 0");
        }
        QueryMode mode = builder.mode == null ? QueryMode.RAW : builder.mode;
        if (mode == QueryMode.AGGREGATED && builder.aggregation == null) {
            throw new IllegalArgumentException("aggregation is required for AGGREGATED mode");
        }
        if (builder.count != null && builder.count <= 0) {
            throw new IllegalArgumentException("count must be > 0");
        }
        boolean positiveInterval = builder.interval != null
                && !builder.interval.isZero() && !builder.interval.isNegative();
        boolean positiveCount = builder.count != null;
        // INTERVAL_SUM 是整窗“末值减首值”的单点区间增量，不分片，故 interval 与 count 均非必填。
        boolean intervalSum = mode == QueryMode.AGGREGATED
                && builder.aggregation == Aggregation.INTERVAL_SUM;
        if ((mode == QueryMode.AGGREGATED || mode == QueryMode.INTERPOLATED || mode == QueryMode.SAMPLED)
                && !intervalSum && !positiveInterval && !positiveCount) {
            throw new IllegalArgumentException("positive interval or count is required for " + mode + " mode");
        }
        this.tags = Collections.unmodifiableList(copy);
        this.start = builder.start;
        this.end = builder.end;
        this.mode = mode;
        this.aggregation = builder.aggregation;
        this.interval = builder.interval;
        this.count = builder.count;
        this.maxPoints = builder.maxPoints;
        this.direction = builder.direction == null ? Direction.FORWARD : builder.direction;
        this.fill = builder.fill == null ? FillPolicy.NONE : builder.fill;
        this.partialResultAllowed = builder.partialResultAllowed;
        this.includeQuality = builder.includeQuality;
    }

    /**
     * 创建查询构造器。
     *
     * <p>构造器初始状态：位号列表为空、{@code mode = RAW}、
     * {@code maxPoints = 10000}、{@code direction = FORWARD}、
     * {@code fill = NONE}、{@code partialResultAllowed = true}、
     * {@code includeQuality = true}；{@code start}、{@code end}、
     * {@code aggregation}、{@code interval}、{@code count} 未设置。
     * 构造器不是线程安全的，请勿在多线程间共享同一个 {@code Builder}。</p>
     *
     * @return 新的构造器实例，非 {@code null}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 返回待查询的位号名列表。
     *
     * @return 非 {@code null} 的只读列表，至少包含一个元素，元素均非空白；
     * 顺序即调用方设置时的顺序，驱动返回结果时通常也按该顺序排列
     */
    public List<String> getTags() {
        return tags;
    }

    /**
     * 返回查询区间的起始时刻（包含该时刻的点）。
     *
     * @return 非 {@code null} 的起始时刻
     */
    public Instant getStart() {
        return start;
    }

    /**
     * 返回查询区间的结束时刻（不包含该时刻的点）。
     *
     * @return 非 {@code null} 的结束时刻，且严格晚于 {@link #getStart()}
     */
    public Instant getEnd() {
        return end;
    }

    /**
     * 返回查询模式。
     *
     * @return 非 {@code null} 的查询模式，默认 {@link QueryMode#RAW}
     */
    public QueryMode getMode() {
        return mode;
    }

    /**
     * 返回聚合函数。
     *
     * <p>仅在 {@link QueryMode#AGGREGATED} 模式下保证非 {@code null}；
     * 其他模式下可能为 {@code null}（未设置时），调用方使用前应先判断模式。</p>
     *
     * @return 聚合函数，可能为 {@code null}
     */
    public Aggregation getAggregation() {
        return aggregation;
    }

    /**
     * 返回聚合/插值/采样的时间片宽度。
     *
     * <p>设置时保证为正；{@link QueryMode#AGGREGATED}、
     * {@link QueryMode#INTERPOLATED}、{@link QueryMode#SAMPLED} 模式下
     * 可能为 {@code null}——此时调用方必然设置了正的 {@link #getCount()}
     * 作为替代；{@link QueryMode#RAW} 与 {@link QueryMode#TREND} 模式下
     * 也可能为 {@code null}。</p>
     *
     * @return 时间片宽度，可能为 {@code null}
     */
    public Duration getInterval() {
        return interval;
    }

    /**
     * 返回服务端点数参数。
     *
     * <p>非 {@code null} 时保证为正。该值直接下发给厂商接口（如 GE 的
     * {@code count} 查询参数），与客户端兜底截断 {@link #getMaxPoints()}
     * 相互独立：前者决定厂商算多少点，后者决定本地最多保留多少点。
     * 未设置（{@code null}）时由驱动按自身约定决定服务端点数。</p>
     *
     * @return 服务端点数，可能为 {@code null}（未设置）
     */
    public Integer getCount() {
        return count;
    }

    /**
     * 返回允许返回的最大采样点数。
     *
     * <p>驱动在实际结果超过该值时应当截断，并（若厂商支持）通过
     * {@link TagSeries#isTruncated()} 标记截断状态；是否允许返回截断结果
     * 由 {@link #isPartialResultAllowed()} 决定。</p>
     *
     * @return 恒为正的整数，默认 {@value #DEFAULT_MAX_POINTS}
     */
    public int getMaxPoints() {
        return maxPoints;
    }

    /**
     * 返回读取方向。
     *
     * @return 非 {@code null} 的方向，默认 {@link Direction#FORWARD}；
     * 同时决定结果顺序与截断时保留哪一端
     */
    public Direction getDirection() {
        return direction;
    }

    /**
     * 返回插值补点策略。
     *
     * @return 非 {@code null} 的补点策略，默认 {@link FillPolicy#NONE}；
     * 仅在 {@link QueryMode#INTERPOLATED} 模式下真正生效，
     * 其他模式驱动可忽略
     */
    public FillPolicy getFill() {
        return fill;
    }

    /**
     * 是否允许返回不完整的结果。
     *
     * <p>为 {@code true} （默认）时，驱动在结果被 {@code maxPoints} 截断、或部分位号
     * 查询失败的情况下可以返回已拿到的数据；为 {@code false}时，
     * 驱动应把这些情况视为失败并抛出 {@code TsdbQueryException}，
     * 而不是悄悄返回残缺数据。</p>
     *
     * @return 允许部分结果返回 {@code true}，否则 {@code false}
     */
    public boolean isPartialResultAllowed() {
        return partialResultAllowed;
    }

    /**
     * 是否要求结果携带质量码信息。
     *
     * <p>为 {@code true}（默认）时，驱动应尽量填充
     * {@link Sample#getQuality()} 与 {@link Sample#getVendorQualityCode()}；
     * 为 {@code false} 时驱动可以跳过质量码的解析以提升吞吐，
     * 此时返回的采样点质量通常为 {@link Quality#UNKNOWN}。</p>
     *
     * @return 需要质量码信息返回 {@code true}，否则 {@code false}
     */
    public boolean isIncludeQuality() {
        return includeQuality;
    }

    /**
     * {@link HistoryQuery} 的链式构造器。
     *
     * <p>所有 setter 都返回 {@code this} 以便串联调用，最终由
     * {@link #build()} 一次性校验并生成不可变实例。构造器本身是可变的、
     * <b>非线程安全</b>的，不应在多个线程间共享；但由它构建出的
     * {@link HistoryQuery} 是线程安全的。</p>
     *
     * <p>注意 {@link #tags(String...)} 与 {@link #tags(Collection)} 是
     * “整体替换”语义（先清空再填充），而 {@link #addTag(String)} 是追加语义。</p>
     *
     * @author wanghao
     * @since 0.1.0
     */
    public static final class Builder {
        /**
         * 待构建的位号列表，可变。
         */
        private final List<String> tags = new ArrayList<String>();

        /**
         * 区间起始时刻，未设置时为 {@code null}。
         */
        private Instant start;

        /**
         * 区间结束时刻，未设置时为 {@code null}。
         */
        private Instant end;

        /**
         * 查询模式，默认 {@link QueryMode#RAW}。
         */
        private QueryMode mode = QueryMode.RAW;

        /**
         * 聚合函数，默认 {@code null}。
         */
        private Aggregation aggregation;

        /**
         * 时间片宽度，默认 {@code null}。
         */
        private Duration interval;

        /**
         * 服务端点数参数，默认 {@code null}（未设置）。
         */
        private Integer count;

        /**
         * 最大点数，默认 {@link HistoryQuery#DEFAULT_MAX_POINTS}。
         */
        private int maxPoints = DEFAULT_MAX_POINTS;

        /**
         * 读取方向，默认 {@link Direction#FORWARD}。
         */
        private Direction direction = Direction.FORWARD;

        /**
         * 补点策略，默认 {@link FillPolicy#NONE}。
         */
        private FillPolicy fill = FillPolicy.NONE;

        /**
         * 是否允许部分结果，默认 {@code true}。
         */
        private boolean partialResultAllowed = true;

        /**
         * 是否包含质量码，默认 {@code true}。
         */
        private boolean includeQuality = true;

        /**
         * 私有构造器，请通过 {@link HistoryQuery#builder()} 获取实例。
         */
        private Builder() {
        }

        /**
         * 整体替换待查询的位号列表。
         *
         * <p>会先清空已有位号再写入本次传入的内容；传入 {@code null} 等价于
         * 清空为一个空列表（此时 {@link #build()} 会因位号为空而失败）。
         * 元素级别的校验（非空、非空白）延迟到 {@link #build()} 执行。</p>
         *
         * @param tags 位号名数组，允许为 {@code null}
         * @return {@code this}，便于链式调用
         */
        public Builder tags(String... tags) {
            this.tags.clear();
            if (tags != null) {
                this.tags.addAll(Arrays.asList(tags));
            }
            return this;
        }

        /**
         * 整体替换待查询的位号列表（集合版本）。
         *
         * <p>会先清空已有位号再写入本次传入的内容；传入 {@code null} 等价于
         * 清空。集合中的 {@code null} 元素不会被本方法拒绝，
         * 而是在 {@link #build()} 时触发校验异常。</p>
         *
         * @param tags 位号名集合，允许为 {@code null}
         * @return {@code this}，便于链式调用
         */
        public Builder tags(Collection<String> tags) {
            this.tags.clear();
            if (tags != null) {
                this.tags.addAll(tags);
            }
            return this;
        }

        /**
         * 追加一个待查询的位号，保留已有位号。
         *
         * @param tag 位号名，允许为 {@code null}（将在 {@link #build()} 时被拒绝）
         * @return {@code this}，便于链式调用
         */
        public Builder addTag(String tag) {
            this.tags.add(tag);
            return this;
        }

        /**
         * 同时设置查询区间的起止时刻（{@code [start, end)}）。
         *
         * <p>等价于连续调用 {@link #start(Instant)} 与 {@link #end(Instant)}。
         * 两个参数都允许为 {@code null}，但 {@link #build()} 要求二者均非空。</p>
         *
         * @param start 起始时刻（含），允许为 {@code null}
         * @param end   结束时刻（不含），允许为 {@code null}
         * @return {@code this}，便于链式调用
         */
        public Builder range(Instant start, Instant end) {
            this.start = start;
            this.end = end;
            return this;
        }

        /**
         * 同时设置查询区间的起止时刻（{@code [start, end)}），{@link Date} 版本。
         *
         * <p>内部通过 {@link Date#toInstant()} 转换为 {@link Instant} 后
         * 委托给 {@link #range(Instant, Instant)}；两个参数都允许为 {@code null}，
         * 但 {@link #build()} 要求二者均非空。</p>
         *
         * @param start 起始时刻（含），允许为 {@code null}
         * @param end   结束时刻（不含），允许为 {@code null}
         * @return {@code this}，便于链式调用
         */
        public Builder range(Date start, Date end) {
            return range(toInstant(start), toInstant(end));
        }

        /**
         * 单独设置查询区间的起始时刻（含）。
         *
         * @param start 起始时刻，允许为 {@code null}（{@link #build()} 会拒绝）
         * @return {@code this}，便于链式调用
         */
        public Builder start(Instant start) {
            this.start = start;
            return this;
        }

        /**
         * 单独设置查询区间的结束时刻（不含）。
         *
         * @param end 结束时刻，允许为 {@code null}（{@link #build()} 会拒绝）
         * @return {@code this}，便于链式调用
         */
        public Builder end(Instant end) {
            this.end = end;
            return this;
        }

        /**
         * 单独设置查询区间的结束时刻（不含），{@link Date} 版本。
         *
         * <p>内部通过 {@link Date#toInstant()} 转换为 {@link Instant} 后
         * 委托给 {@link #end(Instant)}；参数允许为 {@code null}，
         * 但 {@link #build()} 要求非空。</p>
         *
         * @param end 结束时刻，允许为 {@code null}
         * @return {@code this}，便于链式调用
         */
        public Builder end(Date end) {
            return end(toInstant(end));
        }

        /**
         * 将 {@link Date} 空安全地转换为 {@link Instant}。
         *
         * @param date 待转换时刻，允许为 {@code null}
         * @return {@code date} 为 {@code null} 时返回 {@code null}，
         * 否则返回 {@link Date#toInstant()} 的转换结果
         */
        private static Instant toInstant(Date date) {
            return date == null ? null : date.toInstant();
        }

        /**
         * 设置查询模式。
         *
         * @param mode 查询模式，允许为 {@code null}（{@link #build()} 时按
         *             {@link QueryMode#RAW} 处理）
         * @return {@code this}，便于链式调用
         */
        public Builder mode(QueryMode mode) {
            this.mode = mode;
            return this;
        }

        /**
         * 设置聚合函数。
         *
         * <p>仅在 {@link QueryMode#AGGREGATED} 模式下必需；其他模式下设置了也会
         * 被保留但不生效。</p>
         *
         * @param aggregation 聚合函数，允许为 {@code null}
         * @return {@code this}，便于链式调用
         */
        public Builder aggregation(Aggregation aggregation) {
            this.aggregation = aggregation;
            return this;
        }

        /**
         * 设置聚合/插值/采样的时间片宽度。
         *
         * <p>{@link QueryMode#AGGREGATED}、{@link QueryMode#INTERPOLATED}、
         * {@link QueryMode#SAMPLED} 三种模式要求 {@code interval} 或
         * {@link #count(Integer)} 至少一个为正，否则 {@link #build()} 抛异常；
         * 设置了非正的间隔同样会在 {@link #build()} 时被拒绝。</p>
         *
         * @param interval 时间片宽度，允许为 {@code null}
         * @return {@code this}，便于链式调用
         */
        public Builder interval(Duration interval) {
            this.interval = interval;
            return this;
        }

        /**
         * 设置服务端返回的点数（服务端参数，与 {@link #maxPoints(int)} 的
         * 客户端截断语义相互独立）。
         *
         * <p>与 {@link #interval(Duration)} 至少设置一个：
         * {@link QueryMode#AGGREGATED}、{@link QueryMode#INTERPOLATED}、
         * {@link QueryMode#SAMPLED} 三种模式在 {@link #build()} 时要求
         * 正的间隔或正的点数二者至少其一。{@code count} 必须为正数，
         * 非正数会在 {@link #build()} 时抛 {@link IllegalArgumentException}；
         * 传 {@code null} 表示清除该参数。</p>
         *
         * <p>设置了 {@code interval} 时，点数可以由区间精确推导，<b>不需要</b>再填
         * {@code count}：需要下发服务端点数的驱动（当前为 GE）会用
         * {@link HistorianUnit#resolveCount(Integer, java.time.Instant, java.time.Instant, long)}
         * 计算 {@code floor((end - start) / interval)}。若同时给出 {@code count}，
         * 它必须等于该推导值，否则抛 {@link IllegalArgumentException}——不让同一个查询
         * 按文档写和按代码跑出现两种结果。</p>
         *
         * <p>必须显式填写 {@code count} 的只剩两类：没有 {@code interval} 的纯 count
         * 等分网格（{@link QueryMode#INTERPOLATED} 等），以及 {@link QueryMode#RAW}
         * 的点数上限——这两者都无法从时间窗推导。</p>
         *
         * @param count 服务端点数，必须为正数或 {@code null}
         * @return {@code this}，便于链式调用
         */
        public Builder count(Integer count) {
            this.count = count;
            return this;
        }

        /**
         * 设置允许返回的最大采样点数。
         *
         * @param maxPoints 最大点数，必须为正数；非正数将在 {@link #build()}
         *                  时触发 {@link IllegalArgumentException}
         * @return {@code this}，便于链式调用
         */
        public Builder maxPoints(int maxPoints) {
            this.maxPoints = maxPoints;
            return this;
        }

        /**
         * 设置读取方向。
         *
         * @param direction 读取方向，允许为 {@code null}（{@link #build()} 时按
         *                  {@link Direction#FORWARD} 处理）
         * @return {@code this}，便于链式调用
         */
        public Builder direction(Direction direction) {
            this.direction = direction;
            return this;
        }

        /**
         * 设置插值补点策略。
         *
         * @param fill 补点策略，允许为 {@code null}（{@link #build()} 时按
         *             {@link FillPolicy#NONE} 处理）
         * @return {@code this}，便于链式调用
         */
        public Builder fill(FillPolicy fill) {
            this.fill = fill;
            return this;
        }

        /**
         * 设置是否允许返回不完整的结果。
         *
         * @param partialResultAllowed {@code true} 表示结果被截断或部分位号失败时
         *                             允许返回已有数据；{@code false} 表示此类情况
         *                             应作为查询失败处理
         * @return {@code this}，便于链式调用
         */
        public Builder partialResultAllowed(boolean partialResultAllowed) {
            this.partialResultAllowed = partialResultAllowed;
            return this;
        }

        /**
         * 设置是否要求结果携带质量码信息。
         *
         * @param includeQuality {@code true} 表示驱动应填充质量码（默认），
         *                       {@code false} 表示可跳过质量码解析以提升吞吐
         * @return {@code this}，便于链式调用
         */
        public Builder includeQuality(boolean includeQuality) {
            this.includeQuality = includeQuality;
            return this;
        }

        /**
         * 校验当前参数并构建不可变的 {@link HistoryQuery}。
         *
         * <p>本方法不会消费构造器（构造器可以继续修改并再次构建），
         * 每次调用都会生成新的独立实例，并对位号列表做防御性拷贝。</p>
         *
         * @return 非 {@code null} 的不可变查询对象
         * @throws IllegalArgumentException 当位号列表为空、存在空白位号、
         *                                  {@code start}/{@code end} 缺失或
         *                                  {@code start >= end}、
         *                                  {@code maxPoints <= 0}、
         *                                  {@code count <= 0}、
         *                                  {@code AGGREGATED} 模式缺少聚合函数、
         *                                  或需要间隔/点数的模式既无正的间隔
         *                                  也无正的点数时
         */
        public HistoryQuery build() {
            return new HistoryQuery(this);
        }
    }
}
