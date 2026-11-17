package io.github.forget_the_bright.tsdb.iotdb.internal.query;

import io.github.forget_the_bright.tsdb.common.model.Aggregation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.github.forget_the_bright.tsdb.iotdb.internal.IotdbModels;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.Grouping;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.IotdbSql;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.IotdbTagPath;
import io.github.forget_the_bright.tsdb.iotdb.internal.transport.IotdbResultTable;

/**
 * IoTDB 整窗增量与目录类查询族。
 *
 * <p>整窗累计增量用 {@code SELECT first_value(m), last_value(m)} 取回窗口首末值，
 * 在内存里用 {@link BigDecimal} 相减，每个位号产出单点序列、时间戳取窗口起点。
 * 这与差分族的“相邻桶末值差 + 基线”是两套不同语义：前者回答“这段时间一共走了
 * 多少”，后者回答“每个桶各走了多少”，不得互相替代。</p>
 *
 * <p><b>为什么不伪造 0</b>：GE 侧同名接口在坏点或空结果时历史上有过“补 0”的兜底，
 * 但在 IoTDB 上补 0 会把“没有数据”伪装成“这一段没有消耗”，报表无法分辨。
 * 锚点缺失时返回 {@code null}，由调用方决定如何呈现。</p>
 *
 * <p><b>已知底层差异</b>：GE 的 first/last 只统计质量为 GOOD 的采样点，IoTDB
 * 没有质量码，坏值会以合法数值参与计算并锚定首末读数。该差异属底层数据层，
 * 不能通过调整容差消除。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
final class IotdbAggregationQueries {

    private final IotdbQueryCore core;

    /**
     * @param core 共享执行内核，不能为 {@code null}
     */
    IotdbAggregationQueries(IotdbQueryCore core) {
        this.core = core;
    }

    /**
     * 批量计算整窗累计增量（多位号版 {@link #intervalSum}）。
     *
     * <p>一个位号发射 {@code first_value}/{@code last_value} 两列，不带
     * {@code GROUP BY} 时整条语句只返回 1 个逻辑行，因此整批一次往返即可。
     * 两列都按列名括号内的绝对路径定位，{@code first}/{@code last} 不会互相错位，
     * 也不会因为某个位号的序列不存在而串到别的位置上。</p>
     *
     * @param tags  位号列表
     * @param start 窗口起点（含）
     * @param end   窗口终点（不含）
     * @return 位号绝对路径 → 单点增量（时间戳为 {@code start}）；锚点缺失时为 {@code null}
     */
    Map<String, IotdbModels.Point> intervalSumBatch(List<IotdbTagPath> tags, Instant start, Instant end) {
        long begin = start.toEpochMilli();
        long finish = end.toEpochMilli();
        Map<String, IotdbModels.Point> result = new LinkedHashMap<String, IotdbModels.Point>();
        Map<String, List<List<IotdbModels.Point>>> columns = core.batchColumns(
                core.config().getQueryBatchScope(), tags, 2,
                Arrays.asList("first_value", "last_value"),
                (grouping, chunk) -> IotdbSql.intervalSum(grouping, chunk, begin, finish));
        for (Map.Entry<String, List<List<IotdbModels.Point>>> entry : columns.entrySet()) {
            result.put(entry.getKey(), core.math().intervalSumOf(entry.getValue(), begin));
        }
        return result;
    }

    /**
     * 计算单个位号的整窗累计增量。
     *
     * @param path  完整序列路径
     * @param start 窗口起点（含）
     * @param end   窗口终点（不含）
     * @return 单点增量，时间戳为 {@code start}；首值或末值任一缺失时返回 {@code null}
     */
    IotdbModels.Point intervalSum(String path, Instant start, Instant end) {
        IotdbResultTable table = core.executor().query(IotdbSql.intervalSum(Grouping.DEVICE,
                IotdbQueryCore.singlePath(path), start.toEpochMilli(), end.toEpochMilli()));
        if (table.getRows().isEmpty()) {
            return null;
        }
        Object[] values = table.getRows().get(0).getValues();
        BigDecimal first = core.math().decimal(values.length > 0 ? values[0] : null);
        BigDecimal last = core.math().decimal(values.length > 1 ? values[1] : null);
        if (first == null || last == null) {
            return null;
        }
        return core.columns().point(start.toEpochMilli(), last.subtract(first));
    }
}