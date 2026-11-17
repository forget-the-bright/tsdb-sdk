package io.github.forget_the_bright.tsdb.iotdb.internal.query;

import io.github.forget_the_bright.tsdb.common.model.Aggregation;
import io.github.forget_the_bright.tsdb.common.model.DeltaPoint;
import io.github.forget_the_bright.tsdb.common.model.Direction;
import io.github.forget_the_bright.tsdb.common.model.FillPolicy;
import io.github.forget_the_bright.tsdb.common.model.TimeGranularity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.github.forget_the_bright.tsdb.iotdb.internal.IotdbModels;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.Grouping;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.IotdbSql;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.IotdbTagPath;
import io.github.forget_the_bright.tsdb.iotdb.internal.transport.IotdbResultTable;

/**
 * IoTDB 累计量差分族：分桶末值序列 + 起点前基线，在内存里相邻相减。
 *
 * <p>桶序列走 {@code GROUP BY} 末值，基线走
 * {@code LAST_VALUE … WHERE time < start}，两者合不成一条语句（列形态不同），
 * 因此每块 2 次往返。桶边界与自然粒度的聚合族共用同一套日历口径
 * （{@code options.time.zone}），否则差分序列的起点会与聚合序列错开。
 * 计数器归零（{@code reset}）与缺数（{@code missing}）的判定由
 * {@link PointMath#deltaSeries} 统一负责。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
final class IotdbDeltaQueries {

    private final IotdbQueryCore core;

    /**
     * @param core 共享执行内核，不能为 {@code null}
     */
    IotdbDeltaQueries(IotdbQueryCore core) {
        this.core = core;
    }

    /**
     * 批量计算累计量差分：一个分块恰好两条 SQL（桶序列 + 基线）。
     *
     * @param tags        位号列表
     * @param start       起点（含）；按业务时区归整到粒度边界
     * @param end         终点（不含）
     * @param granularity 时间粒度
     * @param fill        空桶填充策略
     * @param direction   返回方向
     * @param maxPoints   每位号最大点数
     * @return 位号绝对路径 → 差分点列表
     */
    Map<String, List<DeltaPoint>> deltasBatch(List<IotdbTagPath> tags, Instant start, Instant end,
                                              TimeGranularity granularity, FillPolicy fill,
                                              Direction direction, int maxPoints) {
        Map<String, List<DeltaPoint>> result = new LinkedHashMap<String, List<DeltaPoint>>();
        if (tags == null || tags.isEmpty()) {
            return result;
        }
        Grouping grouping = core.config().getQueryBatchScope();
        Instant normalized = granularity.normalizeStart(start, core.config().getZone());
        long begin = normalized.toEpochMilli();
        long finish = end.toEpochMilli();
        List<List<IotdbTagPath>> chunks = core.chunks(grouping, tags);
        List<String> sqls = new ArrayList<String>(chunks.size() * 2);
        for (List<IotdbTagPath> chunk : chunks) {
            sqls.add(IotdbSql.interval(grouping, chunk, Aggregation.LAST, begin, finish,
                    granularity.getLiteral(), fill));
            sqls.add(IotdbSql.baseline(grouping, chunk, begin));
        }
        List<IotdbResultTable> tables = core.queryAll(sqls, core.config().getQueryParallelism());
        for (int i = 0; i < chunks.size(); i++) {
            List<IotdbTagPath> chunk = chunks.get(i);
            IotdbResultTable bucketTable = tables.get(i * 2);
            IotdbResultTable baselineTable = tables.get(i * 2 + 1);
            List<String> keys = new ArrayList<String>(chunk.size());
            for (IotdbTagPath path : chunk) {
                keys.add(path.getKey());
            }
            int[] bucketIndex = core.columns().alignColumns(bucketTable, keys, null, sqls.get(i * 2));
            int[] baselineIndex = core.columns().alignColumns(baselineTable, keys,
                    core.columns().repeat("LAST_VALUE", keys.size()), sqls.get(i * 2 + 1));
            for (int j = 0; j < chunk.size(); j++) {
                List<IotdbModels.Point> baselineSeries = core.columns().columnSeries(
                        baselineTable, baselineIndex[j]);
                IotdbModels.Point baseline = baselineSeries.isEmpty() ? null : baselineSeries.get(0);
                result.put(chunk.get(j).getAbsolutePath(), core.math().deltaSeries(
                        core.columns().columnSeries(bucketTable, bucketIndex[j]), baseline,
                        direction, maxPoints));
            }
        }
        return result;
    }

    /**
     * 计算单个位号的累计量差分。
     *
     * @param path        完整序列路径
     * @param start       起点（含）；按业务时区归整到粒度边界
     * @param end         终点（不含）
     * @param granularity 时间粒度
     * @param fill        空桶填充策略
     * @param direction   返回方向
     * @param maxPoints   最大点数
     * @return 差分点列表，非 {@code null}
     */
    List<DeltaPoint> deltas(String path, Instant start, Instant end,
                            TimeGranularity granularity, FillPolicy fill,
                            Direction direction, int maxPoints) {
        Instant normalized = granularity.normalizeStart(start, core.config().getZone());
        List<IotdbModels.Point> buckets = core.columns().singleColumn(core.executor().query(
                IotdbSql.interval(Grouping.DEVICE, IotdbQueryCore.singlePath(path), Aggregation.LAST,
                        normalized.toEpochMilli(), end.toEpochMilli(), granularity.getLiteral(), fill)));
        return core.math().deltaSeries(buckets, baseline(path, normalized), direction, maxPoints);
    }

    /**
     * 取查询起点之前最近的末值，作为差分的基线。
     *
     * @param path  完整序列路径
     * @param start 查询起点（不含）
     * @return 基线点；不存在时返回 {@code null}
     */
    IotdbModels.Point baseline(String path, Instant start) {
        IotdbResultTable table = core.executor().query(IotdbSql.baseline(Grouping.DEVICE,
                IotdbQueryCore.singlePath(path), start.toEpochMilli()));
        if (table.getRows().isEmpty()) {
            return null;
        }
        IotdbResultTable.Row row = table.getRows().get(0);
        return core.columns().point(row.getTimestamp(),
                row.getValues().length == 0 ? null : row.getValues()[0]);
    }
}