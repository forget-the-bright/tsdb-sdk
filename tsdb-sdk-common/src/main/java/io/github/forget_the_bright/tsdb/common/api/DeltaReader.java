package io.github.forget_the_bright.tsdb.common.api;

import io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException;
import io.github.forget_the_bright.tsdb.common.model.Aggregation;
import io.github.forget_the_bright.tsdb.common.model.DeltaSeries;
import io.github.forget_the_bright.tsdb.common.model.Direction;
import io.github.forget_the_bright.tsdb.common.model.FillPolicy;
import io.github.forget_the_bright.tsdb.common.model.HistorianUnit;
import io.github.forget_the_bright.tsdb.common.model.TimeGranularity;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 累计量差分读取接口。
 *
 * <p>语义：对每个位号，先按 {@code granularity} 取每个区间的末值（累计值），
 * 再与上一个区间的末值相减，得到该区间的增量。第一个区间会额外取查询起点
 * 之前最近的一个末值作为基线；没有基线时该点标记为缺数据。</p>
 *
 * <p>若某区间末值小于上一区间末值，视为点位清零/重置，该区间差值取本区间末值，
 * 并置 {@code reset=true}。</p>
 *
 * <p>{@link #read} 的时间粒度受 {@link TimeGranularity} 限制（只有 1 秒/1 分/1 小时/
 * 1 天/1 月）。现场高频的 5 分钟、30 分钟、8 小时等间隔请使用
 * {@link #readByInterval}；「结束时刻 + 总时长 + 间隔」形态请使用
 * {@link #intervalDeltas(Collection, Instant, int, TimeUnit, int, TimeUnit, FillPolicy, Direction, int)}。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public interface DeltaReader {

    /**
     * 读取累计量差分。
     *
     * @param tags         位号集合，不能为空
     * @param start        查询起点（含）
     * @param end          查询终点（不含）
     * @param granularity  时间粒度，不能为 {@code null}
     * @param aggregation  聚合方式，通常为 {@code LAST}
     * @param fill         空桶填充策略
     * @param direction    返回方向
     * @param maxPoints    每个位号最多返回的点数
     * @return 与请求位号顺序一致的差分序列
     */
    List<DeltaSeries> read(Collection<String> tags, Instant start, Instant end,
                           TimeGranularity granularity, Aggregation aggregation,
                           FillPolicy fill, Direction direction, int maxPoints);

    /**
     * 按任意毫秒间隔读取累计量差分。
     *
     * <p><b>语义与本接口 {@link #read} 完全一致</b>：每个间隔取末值
     * （等价于 {@link Aggregation#LAST}），首段用查询起点之前的最近末值作基线，
     * 差值回落时置 {@code reset}，无基线或无末值时置 {@code missing}。</p>
     *
     * <p>与 {@link #read} 的差异只有两点：</p>
     * <ul>
     *   <li>间隔是毫秒数，不受 {@link TimeGranularity} 的 1s/1m/1h/1d/1mo 限制；</li>
     *   <li>分桶网格锚定在传入的 {@code start}，不做自然边界归整。需要自然边界时，
     *       先用 {@link HistorianUnit#calculate(Instant, int, TimeUnit, int, TimeUnit,
     *       java.time.ZoneId, boolean)} 归整结束时刻，再取它的 begin/coveredEnd。</li>
     * </ul>
     *
     * <p><b>不支持日历月</b>：{@link TimeUnit} 没有月。月差分请继续使用
     * {@code read(..., TimeGranularity.MONTH, ...)}（IoTDB 支持，HLS/GE 抛不支持）。</p>
     *
     * <p><b>驱动限制</b>：HLS 只接受整秒间隔（{@code intervalMillis >= 1000} 且能被
     * 1000 整除）；GE/IoTDB 支持任意正整数毫秒。不满足时抛
     * {@link TsdbUnsupportedException}，不会静默取整。</p>
     *
     * <p><b>与 GE 参考 SDK 不同</b>：参考实现 {@code getHistorianIntervalValueBySampled}
     * 使用边界插值、实时值补齐未闭合区间、BAD 当 0、保留两位小数；本方法一条都不做，
     * 数值精度由调用方用 {@code BigDecimal} 自行处理。</p>
     *
     * <p>默认实现抛出 {@link TsdbUnsupportedException}，供尚未支持任意间隔的第三方驱动
     * 保持二进制兼容。</p>
     *
     * @param tags           位号集合，不能为空
     * @param start          查询起点（含），同时是分桶网格锚点
     * @param end            查询终点（不含）
     * @param intervalMillis 单段间隔毫秒数，必须大于 0
     * @param aggregation    聚合方式，必须为 {@code null} 或 {@link Aggregation#LAST}
     * @param fill           空桶填充策略，各驱动支持范围不同
     * @param direction      返回方向
     * @param maxPoints      每个位号最多返回的点数
     * @return 与请求位号顺序一致的差分序列
     */
    default List<DeltaSeries> readByInterval(Collection<String> tags, Instant start, Instant end,
                                             long intervalMillis, Aggregation aggregation, FillPolicy fill,
                                             Direction direction, int maxPoints) {
        throw new TsdbUnsupportedException(
                "driver does not support interval-based cumulative delta");
    }

    /**
     * 读取指定窗口内「每段一个差值」的累计量差分列表。
     *
     * <p>查询窗口取 {@code unit.begin()} 到 {@code unit.coveredEnd()}，因此：</p>
     * <ul>
     *   <li>输出点数等于 {@link HistorianUnit#getCount()}；</li>
     *   <li>时间戳是驱动返回的桶样本时间戳，<b>不做起终点偏移</b>（IoTDB {@code GROUP BY}
     *       返回桶起点；HLS/GE 桶时间落在起点还是终点尚未统一确认）；</li>
     *   <li>总时长不足一个间隔的尾巴（{@link HistorianUnit#getTailMs()}）不产出点；</li>
     *   <li>分桶网格锚定在 {@code unit.begin()}，不做自然边界归整。</li>
     * </ul>
     *
     * <p>底层调用 {@link #readByInterval}，聚合固定为 {@link Aggregation#LAST}。</p>
     *
     * @param tags      位号集合，不能为空
     * @param unit     窗口描述，不能为 {@code null}
     * @param fill      空桶填充策略
     * @param direction 返回方向
     * @param maxPoints 每个位号最多返回的点数
     * @return 与请求位号顺序一致的差分序列
     */
    default List<DeltaSeries> intervalDeltas(Collection<String> tags, HistorianUnit unit,
                                             FillPolicy fill, Direction direction, int maxPoints) {
        if (unit == null) {
            throw new IllegalArgumentException("unit must not be null");
        }
        return readByInterval(tags, unit.getBegin(), unit.getCoveredEnd(),
                unit.getIntervalMs(), Aggregation.LAST, fill, direction, maxPoints);
    }

    /**
     * 「结束时刻 + 总时长 + 间隔」形态的累计差方便利方法。
     *
     * <p>先用 {@link HistorianUnit#calculate(Instant, int, TimeUnit, int, TimeUnit)} 把
     * 参数换算成窗口，再调用 {@link #intervalDeltas(Collection, HistorianUnit, FillPolicy,
     * Direction, int)}。{@code metaTime} 为 {@code null} 时取当前时刻。</p>
     *
     * <p>窗口不平整时（总时长不是间隔的整数倍），末尾尾巴被丢弃；需要按自然边界
     * 对齐时，请自行调用带 {@code zone/round} 的 {@code HistorianUnit.calculate}，
     * 再使用上一个重载。</p>
     *
     * @param tags         位号集合，不能为空
     * @param metaTime     结束时刻（不含），可为 {@code null}
     * @param total        总时长，必须大于 0
     * @param totalUnit    总时长单位，不能为 {@code null}
     * @param interval     单段间隔，必须大于 0
     * @param intervalUnit 间隔单位，不能为 {@code null}
     * @param fill         空桶填充策略
     * @param direction    返回方向
     * @param maxPoints    每个位号最多返回的点数
     * @return 与请求位号顺序一致的差分序列
     * @throws IllegalArgumentException 窗口参数非法，或间隔大于总时长
     */
    default List<DeltaSeries> intervalDeltas(Collection<String> tags, Instant metaTime,
                                             int total, TimeUnit totalUnit, int interval, TimeUnit intervalUnit,
                                             FillPolicy fill, Direction direction, int maxPoints) {
        return intervalDeltas(tags,
                HistorianUnit.calculate(metaTime, total, totalUnit, interval, intervalUnit),
                fill, direction, maxPoints);
    }
}