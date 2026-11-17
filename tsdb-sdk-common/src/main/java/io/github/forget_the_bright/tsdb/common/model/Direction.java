package io.github.forget_the_bright.tsdb.common.model;

/**
 * 历史数据读取方向。
 *
 * <p>用于 {@link HistoryQuery#getDirection()}，决定驱动在时间轴上遍历采样点的
 * 方向，进而决定结果列表中采样点的排列顺序，以及
 * {@link HistoryQuery#getMaxPoints()} 截断时优先保留哪一端的数据。</p>
 *
 * <p>取值只影响返回结果的顺序与截断策略，不影响查询的时间范围本身：
 * 时间范围始终是半开区间 {@code [start, end)}，两个方向的查询范围完全一致。</p>
 *
 * <p>本枚举不可变、线程安全。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public enum Direction {
    /**
     * 正序：从 {@code start} 向 {@code end} 方向推进，结果按时间戳升序排列。
     * 当结果点数超过 {@code maxPoints} 而需要截断时，保留时间上最早的一段。
     * 这是默认方向。
     */
    FORWARD,

    /**
     * 倒序：从 {@code end} 向 {@code start} 方向推进，结果按时间戳降序排列
     * （最新的点在前）。当结果点数超过 {@code maxPoints} 而需要截断时，
     * 保留时间上最新的一段，适合“取最近 N 个点”的场景。
     */
    BACKWARD
}
