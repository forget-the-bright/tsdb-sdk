package io.github.forget_the_bright.tsdb.common.model;

/**
 * 区间聚合函数，用于 {@link QueryMode#AGGREGATED} 模式的历史查询。
 *
 * <p>聚合查询会按 {@link HistoryQuery#getInterval()} 指定的时间跨度把
 * {@code [start, end)} 区间切成若干等宽的时间片，对每个时间片内落入的原始采样点
 * 施加本枚举所表示的聚合运算，每个时间片产出一个结果点。因此该枚举仅在
 * {@code AGGREGATED} 模式下有意义；在 {@code RAW}、{@code INTERPOLATED}、
 * {@code SAMPLED}、{@code TREND} 模式下驱动应忽略它（{@code HistoryQuery}
 * 在 {@code AGGREGATED} 模式下强制要求非空，其他模式不做要求）。</p>
 *
 * <p>语义约定（驱动实现必须遵守，避免不同厂商结果不可比）：</p>
 * <ul>
 *   <li>空时间片（片内无任何有效采样点）不产出结果点，而不是产出 0 或 NaN；
 *       需要补点请使用 {@link QueryMode#INTERPOLATED} 配合 {@link FillPolicy}。</li>
 *   <li>{@link #COUNT} 统计的是片内有效采样点的个数，与点的值无关。</li>
 *   <li>{@link #FIRST} / {@link #LAST} 取的是片内按时间戳排序后的第一个 / 最后一个
 *       采样点的值，而不是片边界时刻插值出来的值。</li>
 *   <li>{@link #RANGE} 定义为片内最大值减最小值（{@code MAX - MIN}）。</li>
 *   <li>{@link #INTERVAL_SUM} 定义为片内末值减首值（{@code LAST - FIRST}），
 *       面向累计计数器类位号（如电能表、流量累积表）的区间增量；
 *       驱动若无法精确实现该语义，必须抛出 {@code TsdbUnsupportedException}，
 *       不得用算术累加和或插值近似冒充。产出的<b>片数由 {@code count} 与
 *       {@code interval} 决定</b>，与聚合方式无关：{@code count=1} 时整个查询窗
 *       就是一格，即“首末差的整窗单值”，这正是 GE
 *       {@code getIntervalValueBySumTag} 的实际形态。另需注意
 *       <b>GE 的 first/last 只统计质量为 GOOD 的采样点</b>，无质量码概念的库
 *       （如 IoTDB）兑现不了该前提，坏值会以合法数值参与首末相减。</li>
 *   <li>质量码非 {@link Quality#GOOD} 的点是否参与聚合由驱动自行决定，但必须在
 *       驱动文档中说明；默认实现只对 {@code GOOD} 点做聚合。</li>
 *   <li>聚合结果的采样点时间戳统一取所在时间片的起始时刻（左闭边界），
 *       便于按时间对齐和二次计算。</li>
 * </ul>
 *
 * <p>本枚举是不可变的值类型，天然线程安全，可安全地在多线程间共享。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public enum Aggregation {
    /**
     * 算术平均值：片内所有有效点值之和除以点的个数。
     * 片内无有效点时不出点。
     */
    AVG,

    /**
     * 最小值：片内所有有效点中的最小数值。
     */
    MIN,

    /**
     * 最大值：片内所有有效点中的最大数值。
     */
    MAX,

    /**
     * 求和：片内所有有效点值的累加和。
     */
    SUM,

    /**
     * 计数：片内有效采样点的个数（整数，与点值大小无关）。
     */
    COUNT,

    /**
     * 首值：片内时间戳最小的那个采样点的值（不插值）。
     */
    FIRST,

    /**
     * 末值：片内时间戳最大的那个采样点的值（不插值）。
     */
    LAST,

    /**
     * 极差：片内最大值与最小值之差，即 {@code MAX - MIN}。
     */
    RANGE,

    /**
     * 边界值：取时间片边界处（片起点）的值，通常由驱动用片边界时刻的
     * 保持值或插值结果填充；具体取左边界还是右边界由驱动文档说明，
     * 默认取左边界。
     */
    BOUND,

    /**
     * 区间增量：片内末值减首值（{@code LAST - FIRST}），用于累计计数器类位号
     * （如电能表读数、流量累积量）在一个时间片内的增量计算。
     *
     * <p>与 {@link #SUM} 的区别必须严格区分：{@code SUM} 把片内所有有效点值
     * 做算术累加（适用于瞬时量求总量），{@code INTERVAL_SUM} 只取片内按时间戳
     * 排序后的首、末两个原始采样点作差（适用于读数单调递增的累计表）。
     * 两者结果通常完全不同，驱动不得互相替代。</p>
     *
     * <p>语义约定：首末值取片内实际存在的原始采样点，不做边界插值；
     * 片内无有效点或只有一个点时不产出结果点；计数器回落（末值小于首值）
     * 属于现场真实工况，驱动应原样返回负差值并在文档中说明，
     * 是否按复位处理由调用方决定。</p>
     *
     * <p>驱动若无法精确实现 {@code LAST - FIRST}（例如厂商只提供算术和），
     * 必须抛出 {@code TsdbUnsupportedException}，不得用近似值冒充。</p>
     */
    INTERVAL_SUM
}
