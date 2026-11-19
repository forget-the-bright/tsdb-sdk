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
 * IoTDB 累计量差分族：扩展一个间隔的分桶末值序列，在内存里相邻相减。
 *
 * <p>查询起点向前扩一个间隔，第一个桶就是「起点前最后一个桶」：{@code GROUP BY}
 * 返回桶首时间戳，命中即可精确判定，因此<b>每块一次往返</b>就能同时拿到桶序列与
 * 基线。只有扩展桶为空（稀疏位号、或 {@code FILL} 填不出更早的值）时，
 * 才为这些位号补一条批量 {@code LAST_VALUE … WHERE time < start}，每块最坏 2 条，
 * 与改造前持平。桶边界与自然粒度的聚合族共用同一套日历口径
 * （{@code options.time.zone}），否则差分序列的起点会与聚合序列错开。
 * 计数器归零（{@code reset}）与缺数（{@code missing}）的判定仍由
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
     * 批量计算累计量差分：每块一条扩展窗的桶序列 SQL。
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
        if (tags == null || tags.isEmpty()) {
            return new LinkedHashMap<String, List<DeltaPoint>>();
        }
        Instant normalized = granularity.normalizeStart(start, core.config().getZone());
        return deltasBatchInternal(tags, normalized, extendedBegin(normalized, granularity),
                end, granularity.getLiteral(), fill, direction, maxPoints);
    }

    /**
     * 批量计算指定毫秒间隔的累计量差分：不归整起点，字面量按毫秒换算。
     *
     * @param tags           位号列表
     * @param start          起点（含），同时是分桶网格锚点
     * @param end            终点（不含）
     * @param intervalMillis 间隔毫秒数，必须大于 0
     * @param fill           空桶填充策略
     * @param direction      返回方向
     * @param maxPoints      每位号最大点数
     * @return 位号绝对路径 → 差分点列表
     * @throws IllegalArgumentException 间隔不大于 0
     */
    Map<String, List<DeltaPoint>> deltasBatchByInterval(List<IotdbTagPath> tags, Instant start, Instant end,
                                                        long intervalMillis, FillPolicy fill,
                                                        Direction direction, int maxPoints) {
        if (tags == null || tags.isEmpty()) {
            return new LinkedHashMap<String, List<DeltaPoint>>();
        }
        if (intervalMillis <= 0L) {
            throw new IllegalArgumentException("intervalMillis must be greater than 0");
        }
        return deltasBatchInternal(tags, start, start.minusMillis(intervalMillis), end,
                IotdbSql.intervalLiteral(intervalMillis), fill, direction, maxPoints);
    }

    /**
     * 把归一后的起点再向前推一个间隔，作为基线桶的起点。
     *
     * <p>固定粒度按毫秒减；月粒度用业务时区做日历减月——{@code normalizeStart} 已把
     * 起点落在当月 1 号 00:00，减一月仍在自然月边界上，与 {@code 1mo} 分组边界一致。</p>
     *
     * @param normalized  已按业务时区归整的起点
     * @param granularity 时间粒度
     * @return 扩展起点
     */
    private Instant extendedBegin(Instant normalized, TimeGranularity granularity) {
        if (granularity.isCalendarMonth()) {
            return normalized.atZone(core.config().getZone()).minusMonths(1).toInstant();
        }
        return normalized.minusMillis(granularity.getMillis());
    }

    /**
     * 批量差分公共实现：每块一条扩展窗 SQL，首桶命中即作基线。
     *
     * <p>未命中的位号在块内收集，第一轮结束后每块补一条批量
     * {@code LAST_VALUE … WHERE time < start}；不会退化成逐位号请求。</p>
     *
     * @param tags            位号列表
     * @param bucketStart     分桶起点（含）
     * @param extendedBegin   扩展起点（含），即基线桶起点
     * @param end             终点（不含）
     * @param intervalLiteral GROUP BY 间隔字面量
     * @param fill            空桶填充策略
     * @param direction       返回方向
     * @param maxPoints       每位号最大点数
     * @return 位号绝对路径 → 差分点列表
     */
    private Map<String, List<DeltaPoint>> deltasBatchInternal(List<IotdbTagPath> tags, Instant bucketStart,
                                                              Instant extendedBegin, Instant end,
                                                              String intervalLiteral, FillPolicy fill,
                                                              Direction direction, int maxPoints) {
        Map<String, List<DeltaPoint>> result = new LinkedHashMap<String, List<DeltaPoint>>();
        Grouping grouping = core.config().getQueryBatchScope();
        long begin = bucketStart.toEpochMilli();
        long extended = extendedBegin.toEpochMilli();
        long finish = end.toEpochMilli();
        List<List<IotdbTagPath>> chunks = core.chunks(grouping, tags);
        List<String> sqls = new ArrayList<String>(chunks.size());
        for (List<IotdbTagPath> chunk : chunks) {
            sqls.add(IotdbSql.interval(grouping, chunk, Aggregation.LAST, extended, finish,
                    intervalLiteral, fill));
        }
        List<IotdbResultTable> tables = core.queryAll(sqls, core.config().getQueryParallelism());

        Map<String, List<IotdbModels.Point>> seriesByTag = new LinkedHashMap<String, List<IotdbModels.Point>>();
        Map<String, IotdbModels.Point> anchors = new LinkedHashMap<String, IotdbModels.Point>();
        List<List<IotdbTagPath>> fallbackChunks = new ArrayList<List<IotdbTagPath>>();
        List<String> fallbackSqls = new ArrayList<String>();
        for (int i = 0; i < chunks.size(); i++) {
            List<IotdbTagPath> chunk = chunks.get(i);
            IotdbResultTable table = tables.get(i);
            List<String> keys = new ArrayList<String>(chunk.size());
            for (IotdbTagPath path : chunk) {
                keys.add(path.getKey());
            }
            int[] index = core.columns().alignColumns(table, keys, null, sqls.get(i));
            List<IotdbTagPath> missing = new ArrayList<IotdbTagPath>();
            for (int j = 0; j < chunk.size(); j++) {
                String key = chunk.get(j).getAbsolutePath();
                List<IotdbModels.Point> series = core.columns().columnSeries(table, index[j]);
                int from = 0;
                if (!series.isEmpty() && series.get(0).getTimestamp().toEpochMilli() == extended) {
                    // 扩展桶存在但值为空（FILL 填不出更早的值）时也必须丢弃：
                    // 这个点是基线桶的位置，留着会被当成窗口缺数，整条序列错一格。
                    from = 1;
                    if (core.math().numeric(series.get(0).getValue()) != null) {
                        anchors.put(key, series.get(0));
                    } else {
                        missing.add(chunk.get(j));
                    }
                } else if (!series.isEmpty()) {
                    missing.add(chunk.get(j));
                }
                seriesByTag.put(key, from == 0 ? series : series.subList(1, series.size()));
            }
            if (!missing.isEmpty()) {
                fallbackChunks.add(missing);
                fallbackSqls.add(IotdbSql.baseline(grouping, missing, begin));
            }
        }
        if (!fallbackChunks.isEmpty()) {
            List<IotdbResultTable> baselineTables = core.queryAll(fallbackSqls,
                    core.config().getQueryParallelism());
            for (int i = 0; i < fallbackChunks.size(); i++) {
                List<IotdbTagPath> chunk = fallbackChunks.get(i);
                List<String> keys = new ArrayList<String>(chunk.size());
                for (IotdbTagPath path : chunk) {
                    keys.add(path.getKey());
                }
                int[] index = core.columns().alignColumns(baselineTables.get(i), keys,
                        core.columns().repeat("LAST_VALUE", keys.size()), fallbackSqls.get(i));
                for (int j = 0; j < chunk.size(); j++) {
                    List<IotdbModels.Point> column = core.columns().columnSeries(baselineTables.get(i), index[j]);
                    if (!column.isEmpty()) {
                        anchors.put(chunk.get(j).getAbsolutePath(), column.get(0));
                    }
                }
            }
        }
        for (IotdbTagPath path : tags) {
            String key = path.getAbsolutePath();
            result.put(key, core.math().deltaSeries(seriesByTag.get(key), anchors.get(key),
                    direction, maxPoints));
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
        return deltasInternal(path, normalized, extendedBegin(normalized, granularity), end,
                granularity.getLiteral(), fill, direction, maxPoints);
    }

    /**
     * 计算单个位号指定毫秒间隔的累计量差分。
     *
     * @param path           完整序列路径
     * @param start          起点（含），同时是分桶网格锚点
     * @param end            终点（不含）
     * @param intervalMillis 间隔毫秒数，必须大于 0
     * @param fill           空桶填充策略
     * @param direction      返回方向
     * @param maxPoints      最大点数
     * @return 差分点列表，非 {@code null}
     * @throws IllegalArgumentException 间隔不大于 0
     */
    List<DeltaPoint> deltasByInterval(String path, Instant start, Instant end,
                                      long intervalMillis, FillPolicy fill,
                                      Direction direction, int maxPoints) {
        if (intervalMillis <= 0L) {
            throw new IllegalArgumentException("intervalMillis must be greater than 0");
        }
        return deltasInternal(path, start, start.minusMillis(intervalMillis), end,
                IotdbSql.intervalLiteral(intervalMillis), fill, direction, maxPoints);
    }

    /**
     * 单个位号差分的公共实现：扩展窗首桶命中即作基线，否则懒查一次基线。
     *
     * @param path            完整序列路径
     * @param bucketStart     分桶起点（含）
     * @param extendedBegin   扩展起点（含），即基线桶起点
     * @param end             终点（不含）
     * @param intervalLiteral GROUP BY 间隔字面量
     * @param fill            空桶填充策略
     * @param direction       返回方向
     * @param maxPoints       最大点数
     * @return 差分点列表，非 {@code null}
     */
    private List<DeltaPoint> deltasInternal(String path, Instant bucketStart, Instant extendedBegin,
                                            Instant end, String intervalLiteral, FillPolicy fill,
                                            Direction direction, int maxPoints) {
        List<IotdbModels.Point> series = core.columns().singleColumn(core.executor().query(
                IotdbSql.interval(Grouping.DEVICE, IotdbQueryCore.singlePath(path), Aggregation.LAST,
                        extendedBegin.toEpochMilli(), end.toEpochMilli(), intervalLiteral, fill)));
        IotdbModels.Point anchor = null;
        int from = 0;
        if (!series.isEmpty() && series.get(0).getTimestamp().toEpochMilli() == extendedBegin.toEpochMilli()) {
            // 扩展桶在就一律丢弃；值为空时只是没拿到基线，仍要补查一次。
            from = 1;
            if (core.math().numeric(series.get(0).getValue()) != null) {
                anchor = series.get(0);
            }
        }
        if (anchor == null && !series.isEmpty()) {
            anchor = baseline(path, bucketStart);
        }
        return core.math().deltaSeries(from == 0 ? series : series.subList(1, series.size()),
                anchor, direction, maxPoints);
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