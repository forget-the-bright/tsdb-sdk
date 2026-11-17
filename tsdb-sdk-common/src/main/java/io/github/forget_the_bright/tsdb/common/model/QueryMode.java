package io.github.forget_the_bright.tsdb.common.model;

/**
 * 历史查询模式。
 *
 * <p>取值决定 {@link HistoryQuery} 中哪些参数是必填的，以及驱动应当如何组装
 * 服务端查询：</p>
 * <ul>
 *   <li>{@link #RAW}：原始点直读，只需时间范围，不使用聚合与间隔；</li>
 *   <li>{@link #AGGREGATED}：必须指定 {@link Aggregation}，且正的间隔
 *       （{@code interval}）或正的服务端点数（{@code count}）至少设置一个；</li>
 *   <li>{@link #INTERPOLATED}、{@link #SAMPLED}：正的间隔或正的服务端点数
 *       至少设置一个；</li>
 *   <li>{@link #TREND}：趋势查询，间隔是否必填由驱动决定。</li>
 * </ul>
 *
 * <p>约束：驱动不支持某个模式时必须抛出 {@code TSDB-UNSUPPORTED}
 * （{@code TsdbUnsupportedException}），绝不能静默降级成 {@link #RAW}
 * 或改变模式语义。调用方可以通过
 * {@link CapabilitySet#supports(Capability)} 提前判断能力，
 * 对应的能力项为 {@link Capability#RAW_HISTORY}、
 * {@link Capability#AGGREGATED_HISTORY}、{@link Capability#INTERPOLATED_HISTORY}、
 * {@link Capability#SAMPLED_HISTORY} 与 {@link Capability#TREND}。</p>
 *
 * <p>本枚举不可变、线程安全。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public enum QueryMode {
    /**
     * 原始数据：按查询区间逐点返回存储中的原始采样点，不做任何聚合、插值或采样。
     * 这是默认模式，也是唯一不要求间隔参数的模式。
     */
    RAW,

    /**
     * 区间聚合：按间隔切片后对每片施加 {@link Aggregation} 运算，
     * 每片产出一个结果点。必须提供 {@link Aggregation}，且正的间隔或
     * 正的服务端点数（{@code count}）至少设置一个，否则
     * {@link HistoryQuery.Builder#build()} 会抛
     * {@link IllegalArgumentException}。
     */
    AGGREGATED,

    /**
     * 插值：按间隔在指定时刻取点，缺失数据按 {@link FillPolicy} 补齐。
     * 必须提供正的间隔或正的服务端点数（{@code count}）至少一个。
     */
    INTERPOLATED,

    /**
     * 等间隔取点：按间隔在指定时刻取点，片内具体取哪个点
     * （前值保持、边界值、插值等）由驱动按厂商语义实现，
     * 并在各驱动文档中说明。必须提供正的间隔或正的服务端点数
     * （{@code count}）至少一个。
     */
    SAMPLED,

    /**
     * 趋势查询：返回用于绘制趋势图的数据，通常由服务端做有损压缩
     * （例如保留拐点）以降低传输量。此类结果不适合用于精确计算。
     */
    TREND
}
