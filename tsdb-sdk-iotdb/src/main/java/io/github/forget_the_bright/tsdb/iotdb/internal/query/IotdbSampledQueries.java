package io.github.forget_the_bright.tsdb.iotdb.internal.query;

import io.github.forget_the_bright.tsdb.common.model.Aggregation;
import io.github.forget_the_bright.tsdb.common.model.Direction;
import io.github.forget_the_bright.tsdb.common.model.FillPolicy;
import io.github.forget_the_bright.tsdb.common.model.TimeGranularity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import io.github.forget_the_bright.tsdb.iotdb.internal.IotdbModels;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.Grouping;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.IotdbSql;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.IotdbTagPath;
import io.github.forget_the_bright.tsdb.iotdb.internal.transport.IotdbResultTable;

/**
 * IoTDB 采样/聚合历史查询族：等间隔分桶与聚合历史。
 *
 * <p>批量与逐位号给出同一套取值口径：{@code GROUP BY} 的行数只等于桶数、与列数
 * 无关，因此批量不会额外消耗行预算，也不会改变截断口径；{@code FILL(PREVIOUS)}
 * 按列独立填充，不会把一个位号的末值填进另一个位号的空桶。</p>
 *
 * <p>自然粒度路径的起点按业务时区（{@code options.time.zone}）归整，与差分族
 * 共用同一套日历口径，否则同一粒度下的聚合序列与差分序列会整体错开一格。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
final class IotdbSampledQueries {

    private final IotdbQueryCore core;

    /**
     * @param core 共享执行内核，不能为 {@code null}
     */
    IotdbSampledQueries(IotdbQueryCore core) {
        this.core = core;
    }

    /**
     * 按任意毫秒间隔批量取等间隔分桶值。
     *
     * @param tags        位号列表，按各自分组切块
     * @param start       起点（含），同时作为第一个桶的左边界
     * @param end         终点（不含）
     * @param stepMillis  桶宽（毫秒）
     * @param aggregation 聚合方式，{@code INTERVAL_SUM} 走 {@link IotdbAggregationQueries}
     * @param fill        空桶填充策略
     * @return 位号绝对路径 → 分桶点序列，键覆盖全部入参位号
     */
    Map<String, List<IotdbModels.Point>> intervalBatch(List<IotdbTagPath> tags, Instant start, Instant end,
                                                       long stepMillis, Aggregation aggregation,
                                                       FillPolicy fill) {
        long begin = start.toEpochMilli();
        long finish = end.toEpochMilli();
        String literal = IotdbSql.intervalLiteral(stepMillis);
        return core.batchSeries(core.config().getQueryBatchScope(), tags, null,
                (grouping, chunk) -> IotdbSql.interval(grouping, chunk, aggregation, begin, finish, literal, fill));
    }

    /**
     * 按自然粒度批量取等间隔分桶值，起点按业务时区归整。
     *
     * @param tags        位号列表
     * @param start       起点（含）；天/月粒度归整到边界
     * @param end         终点（不含）
     * @param granularity 时间粒度
     * @param aggregation 聚合方式
     * @param fill        空桶填充策略
     * @return 位号绝对路径 → 分桶点序列
     */
    Map<String, List<IotdbModels.Point>> intervalBatch(List<IotdbTagPath> tags, Instant start, Instant end,
                                                       TimeGranularity granularity, Aggregation aggregation,
                                                       FillPolicy fill) {
        Instant normalized = granularity.normalizeStart(start, core.config().getZone());
        long begin = normalized.toEpochMilli();
        long finish = end.toEpochMilli();
        String literal = granularity.getLiteral();
        return core.batchSeries(core.config().getQueryBatchScope(), tags, null,
                (grouping, chunk) -> IotdbSql.interval(grouping, chunk, aggregation, begin, finish, literal, fill));
    }

    /**
     * 按配置的档位批量查询聚合历史（带服务端方向与条数限制）。
     *
     * @param tags        位号列表
     * @param aggregation 聚合方式
     * @param start       起点（含）
     * @param end         终点（不含）
     * @param stepMillis  桶宽（毫秒）
     * @param direction   返回方向
     * @param maxPoints   服务端最多返回的桶数
     * @return 位号绝对路径 → 分桶点序列
     */
    Map<String, List<IotdbModels.Point>> aggregatedBatch(List<IotdbTagPath> tags, Aggregation aggregation,
                                                         Instant start, Instant end, long stepMillis,
                                                         Direction direction, int maxPoints) {
        long begin = start.toEpochMilli();
        long finish = end.toEpochMilli();
        return core.batchSeries(core.config().getQueryBatchScope(), tags, null,
                (grouping, chunk) -> IotdbSql.aggregated(grouping, chunk, aggregation, begin, finish,
                        stepMillis, direction, maxPoints));
    }

    /**
     * 按任意毫秒间隔查询单个位号的等间隔值。
     *
     * <p>不做起点归整：分桶原点就是 {@code start}。等分网格一旦挪动起点，
     * 按索引取点的报表就会整体错位。</p>
     *
     * @param path        完整序列路径
     * @param start       起点（含）
     * @param end         终点（不含）
     * @param stepMillis  桶宽（毫秒），非正数按 1 处理
     * @param aggregation 聚合方式
     * @param fill        空桶填充策略
     * @return 分桶点列表，非 {@code null}；空桶值为 {@code null}
     */
    List<IotdbModels.Point> interval(String path, Instant start, Instant end, long stepMillis,
                                     Aggregation aggregation, FillPolicy fill) {
        IotdbResultTable table = core.executor().query(IotdbSql.interval(Grouping.DEVICE,
                IotdbQueryCore.singlePath(path), aggregation, start.toEpochMilli(), end.toEpochMilli(),
                IotdbSql.intervalLiteral(stepMillis), fill));
        return core.columns().singleColumn(table);
    }

    /**
     * 按自然粒度查询单个位号的等间隔值，起点按业务时区归整。
     *
     * @param path        完整序列路径
     * @param start       起点（含）；天/月粒度会按业务时区归整到边界
     * @param end         终点（不含）
     * @param granularity 时间粒度
     * @param aggregation 聚合方式
     * @param fill        空桶填充策略
     * @return 分桶点列表，非 {@code null}
     */
    List<IotdbModels.Point> interval(String path, Instant start, Instant end,
                                     TimeGranularity granularity, Aggregation aggregation,
                                     FillPolicy fill) {
        Instant normalized = granularity.normalizeStart(start, core.config().getZone());
        IotdbResultTable table = core.executor().query(IotdbSql.interval(Grouping.DEVICE,
                IotdbQueryCore.singlePath(path), aggregation, normalized.toEpochMilli(),
                end.toEpochMilli(), granularity.getLiteral(), fill));
        return core.columns().singleColumn(table);
    }

    /**
     * 查询单个位号的聚合历史，带服务端方向与条数限制。
     *
     * @param path        完整序列路径
     * @param aggregation 聚合方式，{@code INTERVAL_SUM} 由
     *                    {@link IotdbAggregationQueries#intervalSum} 承担
     * @param start       起点（含）
     * @param end         终点（不含）
     * @param stepMillis  桶宽（毫秒）
     * @param direction   返回方向
     * @param limit       服务端最多返回的桶数，至少 1
     * @return 分桶点列表，非 {@code null}
     */
    List<IotdbModels.Point> aggregated(String path, Aggregation aggregation, Instant start, Instant end,
                                       long stepMillis, Direction direction, int limit) {
        IotdbResultTable table = core.executor().query(IotdbSql.aggregated(Grouping.DEVICE,
                IotdbQueryCore.singlePath(path), aggregation, start.toEpochMilli(), end.toEpochMilli(),
                stepMillis, direction, limit));
        return core.columns().singleColumn(table);
    }
}