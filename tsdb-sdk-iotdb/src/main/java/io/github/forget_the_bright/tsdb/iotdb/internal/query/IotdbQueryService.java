package io.github.forget_the_bright.tsdb.iotdb.internal.query;

import io.github.forget_the_bright.tsdb.common.model.Aggregation;
import io.github.forget_the_bright.tsdb.common.model.DeltaPoint;
import io.github.forget_the_bright.tsdb.common.model.Direction;
import io.github.forget_the_bright.tsdb.common.model.FillPolicy;
import io.github.forget_the_bright.tsdb.common.model.TimeGranularity;
import io.github.forget_the_bright.tsdb.iotdb.IotdbConfig;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import io.github.forget_the_bright.tsdb.iotdb.internal.IotdbModels;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.Grouping;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.IotdbSql;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.IotdbTagPath;
import io.github.forget_the_bright.tsdb.iotdb.internal.transport.IotdbQueryExecutor;
import io.github.forget_the_bright.tsdb.iotdb.internal.transport.IotdbResultTable;

/**
 * IoTDB 的统一 SQL 查询门户。
 *
 * <p>所有查询只依赖 {@link IotdbQueryExecutor}，因此 native、JDBC、REST
 * 三种通道执行同一份 SQL、返回同一种结果，切换 transport 不改变业务语义。</p>
 *
 * <p><b>本类只做门面</b>：分块、并行与结果列对齐在 {@link IotdbQueryCore}，
 * 各查询族的 SQL 与解析分别落在 {@link IotdbCurrentQueries}、{@link IotdbRawQueries}、
 * {@link IotdbSampledQueries}、{@link IotdbAggregationQueries}、
 * {@link IotdbInterpolationQueries}、{@link IotdbDeltaQueries}。公开方法签名与语义
 * 保持不变，{@code unwrap(IotdbQueryService.class)} 的调用方无需改动。</p>
 *
 * <p>本类还承载两套 IoTDB <b>没有原生对应物</b>的语义：整窗累计增量
 * （{@link #intervalSum}，取首末锚点后相减）与固定网格插值
 * （{@link #interpolate}，取回原始采样点后在内存内插）。它们放在这一层而
 * 不是客户端，是因为都要发起多次 SQL 往返并做行级解析，属于“怎么查”的问题；
 * 客户端只负责模式判定、点数裁剪和公共模型组装。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class IotdbQueryService implements AutoCloseable {

    private final IotdbQueryCore core;
    private final IotdbCurrentQueries current;
    private final IotdbRawQueries raw;
    private final IotdbSampledQueries sampled;
    private final IotdbAggregationQueries aggregation;
    private final IotdbInterpolationQueries interpolation;
    private final IotdbDeltaQueries delta;

    /**
     * 创建查询服务。
     *
     * @param config   驱动配置，不能为 {@code null}
     * @param executor SQL 执行通道，不能为 {@code null}
     */
    public IotdbQueryService(IotdbConfig config, IotdbQueryExecutor executor) {
        this.core = new IotdbQueryCore(config, executor);
        this.current = new IotdbCurrentQueries(core);
        this.raw = new IotdbRawQueries(core);
        this.sampled = new IotdbSampledQueries(core);
        this.aggregation = new IotdbAggregationQueries(core);
        this.interpolation = new IotdbInterpolationQueries(core);
        this.delta = new IotdbDeltaQueries(core);
    }

    /**
     * 按配置的分组档位批量读取最新值，返回与入参同序的点列表。
     *
     * <p>返回列表长度恒等于入参长度，无数据的位号对应 {@code null}；缺失是按
     * {@code Timeseries} 列回填的，不能按列序对齐。详见
     * {@link IotdbCurrentQueries#currentBatch(List)}。</p>
     *
     * @param paths 已解析的位号路径，按返回顺序排列，可为空
     * @return 与 {@code paths} 同序的点列表，元素可能为 {@code null}
     */
    public List<IotdbModels.Point> currentBatch(List<IotdbTagPath> paths) {
        return current.currentBatch(paths);
    }

    /**
     * 把一批位号按配置的档位与上限切成若干块，每块对应一条 SQL。
     *
     * <p>对外可见是为了失败归因：调用方按块逐次查询时，一条 SQL 的服务端错误
     * 只影响这一块里的位号。分块是幂等的。</p>
     *
     * @param grouping 分组档位
     * @param tags     位号列表，可为空
     * @return 块列表，保持分组首次出现顺序与块内入参顺序
     */
    public List<List<IotdbTagPath>> chunks(Grouping grouping, List<IotdbTagPath> tags) {
        return core.chunks(grouping, tags);
    }

    /**
     * 按指定批量上限切块，与 {@link #chunks(Grouping, List)} 共用同一套分块规则。
     *
     * @param grouping  分组档位
     * @param tags      位号列表，可为空
     * @param batchSize 单条 SQL 的表达式个数上限，非正数时按 1 处理
     * @return 块列表
     */
    public List<List<IotdbTagPath>> chunks(Grouping grouping, List<IotdbTagPath> tags, int batchSize) {
        return core.chunks(grouping, tags, batchSize);
    }

    /**
     * 按任意毫秒间隔批量取等间隔分桶值。
     *
     * @param tags        位号列表
     * @param start       起点（含），同时作为第一个桶的左边界
     * @param end         终点（不含）
     * @param stepMillis  桶宽（毫秒）
     * @param aggregation 聚合方式
     * @param fill        空桶填充策略
     * @return 位号绝对路径 → 分桶点序列
     */
    public Map<String, List<IotdbModels.Point>> intervalBatch(List<IotdbTagPath> tags, Instant start, Instant end,
                                                              long stepMillis, Aggregation aggregation,
                                                              FillPolicy fill) {
        return sampled.intervalBatch(tags, start, end, stepMillis, aggregation, fill);
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
    public Map<String, List<IotdbModels.Point>> intervalBatch(List<IotdbTagPath> tags, Instant start, Instant end,
                                                              TimeGranularity granularity, Aggregation aggregation,
                                                              FillPolicy fill) {
        return sampled.intervalBatch(tags, start, end, granularity, aggregation, fill);
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
    public Map<String, List<IotdbModels.Point>> aggregatedBatch(List<IotdbTagPath> tags, Aggregation aggregation,
                                                                Instant start, Instant end, long stepMillis,
                                                                Direction direction, int maxPoints) {
        return sampled.aggregatedBatch(tags, aggregation, start, end, stepMillis, direction, maxPoints);
    }

    /**
     * 批量读取原始点并保证与逐位号查询逐点相同。
     *
     * @param tags      位号列表
     * @param start     起点（含）
     * @param end       终点（不含）
     * @param direction 返回方向
     * @param maxPoints 每位号需要的点数
     * @param completed 输出参数：走过补采的位号绝对路径，可为 {@code null}
     * @return 位号绝对路径 → 原始点序列
     */
    public Map<String, List<IotdbModels.Point>> rawBatchCompleted(List<IotdbTagPath> tags, Instant start,
                                                                  Instant end, Direction direction, int maxPoints,
                                                                  Set<String> completed) {
        return raw.rawBatchCompleted(tags, start, end, direction, maxPoints, completed);
    }

    /**
     * 批量计算整窗累计增量。
     *
     * @param tags  位号列表
     * @param start 窗口起点（含）
     * @param end   窗口终点（不含）
     * @return 位号绝对路径 → 单点增量
     */
    public Map<String, IotdbModels.Point> intervalSumBatch(List<IotdbTagPath> tags, Instant start, Instant end) {
        return aggregation.intervalSumBatch(tags, start, end);
    }

    /**
     * 批量计算累计量差分。
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
    public Map<String, List<DeltaPoint>> deltasBatch(List<IotdbTagPath> tags, Instant start, Instant end,
                                                     TimeGranularity granularity, FillPolicy fill,
                                                     Direction direction, int maxPoints) {
        return delta.deltasBatch(tags, start, end, granularity, fill, direction, maxPoints);
    }

    /**
     * 按任意毫秒间隔批量计算累计量差分。
     *
     * <p>起点不归整，作为分桶网格锚点；间隔以毫秒换算成 {@code 5m}/{@code 250ms}
     * 等字面量。其余分块、基线与失败上抛语义同 {@link #deltasBatch}。</p>
     *
     * @param tags           位号列表
     * @param start          起点（含），同时是分桶网格锚点
     * @param end            终点（不含）
     * @param intervalMillis 间隔毫秒数，必须大于 0
     * @param fill           空桶填充策略
     * @param direction      返回方向
     * @param maxPoints      每位号最大点数
     * @return 位号绝对路径 → 差分点列表
     */
    public Map<String, List<DeltaPoint>> deltasByIntervalBatch(List<IotdbTagPath> tags, Instant start,
                                                               Instant end, long intervalMillis,
                                                               FillPolicy fill, Direction direction,
                                                               int maxPoints) {
        return delta.deltasBatchByInterval(tags, start, end, intervalMillis, fill, direction, maxPoints);
    }

    /**
     * 读取单个位号的原始点。
     *
     * @param path      完整序列路径
     * @param start     起点（含）
     * @param end       终点（不含）
     * @param direction 返回方向
     * @param limit     最大点数
     * @return 点列表，非 {@code null}
     */
    public List<IotdbModels.Point> raw(String path, Instant start, Instant end,
                                       Direction direction, int limit) {
        return raw.raw(path, start, end, direction, limit);
    }

    /**
     * 按任意毫秒间隔查询单个位号的等间隔值。
     *
     * @param path        完整序列路径
     * @param start       起点（含），同时作为第一个桶的左边界
     * @param end         终点（不含）
     * @param stepMillis  桶宽（毫秒），非正数按 1 处理
     * @param aggregation 聚合方式
     * @param fill        空桶填充策略
     * @return 分桶点列表，非 {@code null}
     */
    public List<IotdbModels.Point> interval(String path, Instant start, Instant end, long stepMillis,
                                            Aggregation aggregation, FillPolicy fill) {
        return sampled.interval(path, start, end, stepMillis, aggregation, fill);
    }

    /**
     * 查询单个位号的聚合历史，带服务端方向与条数限制。
     *
     * @param path        完整序列路径
     * @param aggregation 聚合方式
     * @param start       起点（含）
     * @param end         终点（不含）
     * @param stepMillis  桶宽（毫秒）
     * @param direction   返回方向
     * @param limit       服务端最多返回的桶数
     * @return 分桶点列表，非 {@code null}
     */
    public List<IotdbModels.Point> aggregated(String path, Aggregation aggregation, Instant start, Instant end,
                                              long stepMillis, Direction direction, int limit) {
        return sampled.aggregated(path, aggregation, start, end, stepMillis, direction, limit);
    }

    /**
     * 按自然粒度查询单个位号的等间隔值，起点按业务时区归整。
     *
     * @param path        完整序列路径
     * @param start       起点（含）
     * @param end         终点（不含）
     * @param granularity 时间粒度
     * @param aggregation 聚合方式
     * @param fill        空桶填充策略
     * @return 分桶点列表，非 {@code null}
     */
    public List<IotdbModels.Point> interval(String path, Instant start, Instant end,
                                            TimeGranularity granularity, Aggregation aggregation,
                                            FillPolicy fill) {
        return sampled.interval(path, start, end, granularity, aggregation, fill);
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
    public List<DeltaPoint> deltas(String path, Instant start, Instant end,
                                   TimeGranularity granularity, FillPolicy fill,
                                   Direction direction, int maxPoints) {
        return delta.deltas(path, start, end, granularity, fill, direction, maxPoints);
    }

    /**
     * 按任意毫秒间隔计算单个位号的累计量差分。
     *
     * @param path           完整序列路径
     * @param start          起点（含），同时是分桶网格锚点
     * @param end            终点（不含）
     * @param intervalMillis 间隔毫秒数，必须大于 0
     * @param fill           空桶填充策略
     * @param direction      返回方向
     * @param maxPoints      最大点数
     * @return 差分点列表，非 {@code null}
     */
    public List<DeltaPoint> deltasByInterval(String path, Instant start, Instant end,
                                             long intervalMillis, FillPolicy fill,
                                             Direction direction, int maxPoints) {
        return delta.deltasByInterval(path, start, end, intervalMillis, fill, direction, maxPoints);
    }

    /**
     * 计算单个位号的整窗累计增量。
     *
     * @param path  完整序列路径
     * @param start 窗口起点（含）
     * @param end   窗口终点（不含）
     * @return 单点增量；首值或末值任一缺失时返回 {@code null}
     */
    public IotdbModels.Point intervalSum(String path, Instant start, Instant end) {
        return aggregation.intervalSum(path, start, end);
    }

    /**
     * 按固定网格在内存中做线性插值。
     *
     * @param path            完整序列路径
     * @param gridStart       网格起点
     * @param stepMillis      槽位间隔（毫秒）
     * @param slots           槽位数量，小于等于 0 时返回空结果
     * @param toleranceMillis 单侧外推容差（毫秒），{@code 0} 表示禁止外推
     * @param rawLimit        窗口内原始点拉取上限
     * @return 非 {@code null} 的插值结果
     */
    public Interpolation interpolate(String path, Instant gridStart, long stepMillis, int slots,
                                     long toleranceMillis, int rawLimit) {
        return interpolation.interpolate(path, gridStart, stepMillis, slots, toleranceMillis, rawLimit);
    }

    /**
     * 按配置的档位批量插值。
     *
     * @param tags            位号列表
     * @param gridStart       网格起点
     * @param stepMillis      槽位间隔（毫秒）
     * @param slots           槽位数量
     * @param toleranceMillis 单侧外推容差（毫秒）
     * @param rawLimit        窗内原始点拉取上限
     * @param completed       输出参数：走过补采的位号绝对路径，可为 {@code null}
     * @return 位号绝对路径 → 插值结果
     */
    public Map<String, Interpolation> interpolateBatch(List<IotdbTagPath> tags, Instant gridStart, long stepMillis,
                                                       int slots, long toleranceMillis, int rawLimit,
                                                       Set<String> completed) {
        return interpolation.interpolateBatch(tags, gridStart, stepMillis, slots,
                toleranceMillis, rawLimit, completed);
    }

    /**
     * 按前缀列出序列。
     *
     * @param prefix 设备或数据库前缀
     * @return 位号列表，非 {@code null}
     */
    public List<IotdbModels.Tag> listTags(String prefix) {
        String sql = prefix == null || prefix.trim().isEmpty()
                ? "SHOW TIMESERIES"
                : IotdbSql.showTimeseries(prefix.trim());
        IotdbResultTable table = core.executor().query(sql);
        ResultColumns columns = core.columns();
        List<IotdbModels.Tag> tags = new ArrayList<IotdbModels.Tag>();
        int nameIndex = columns.column(table, "Timeseries", 0);
        int aliasIndex = columns.column(table, "Alias", -1);
        int typeIndex = columns.column(table, "DataType", -1);
        int databaseIndex = columns.column(table, "Database", -1);
        for (IotdbResultTable.Row row : table.getRows()) {
            Object[] values = row.getValues();
            if (nameIndex < 0 || nameIndex >= values.length) {
                continue;
            }
            tags.add(new IotdbModels.Tag(columns.text(values[nameIndex]),
                    columns.value(values, aliasIndex), columns.value(values, typeIndex),
                    columns.value(values, databaseIndex)));
        }
        return tags;
    }

    /**
     * 返回服务端版本。
     *
     * @return 版本文本；查询失败时返回 {@code "unknown"}
     */
    public String serverVersion() {
        try {
            IotdbResultTable table = core.executor().query("SHOW VERSION");
            Object value = table.firstValue();
            return value == null ? "unknown" : String.valueOf(value);
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    /**
     * {@link #interpolate} 的结果载体。
     *
     * <p>插值的网格槽位数由调用方决定，返回点数恒等于槽位数，因此公共层
     * {@code TagSeries.isTruncated()} 表达不了这里真正的风险：窗口内原始点被
     * {@code rawLimit} 截断，后续槽位的包围点可能不完整。该事实必须由
     * {@link #isRawTruncated()} 单独如实上报，否则“数据偏小”会被当成
     * “这段时间确实没怎么用”。</p>
     */
    public static final class Interpolation {

        private final List<IotdbModels.Point> points;
        private final boolean rawTruncated;

        /**
         * 创建插值结果。
         *
         * @param points       按槽位顺序排列的点，非 {@code null}
         * @param rawTruncated 窗口内原始点是否因触达上限而停止拉取
         */
        public Interpolation(List<IotdbModels.Point> points, boolean rawTruncated) {
            this.points = points == null
                    ? Collections.<IotdbModels.Point>emptyList()
                    : Collections.unmodifiableList(new ArrayList<IotdbModels.Point>(points));
            this.rawTruncated = rawTruncated;
        }

        /**
         * 返回插值点列表。
         *
         * @return 不可修改列表，时间戳恰为网格时刻
         */
        public List<IotdbModels.Point> getPoints() {
            return points;
        }

        /**
         * 返回原始点是否被截断。
         *
         * @return {@code true} 表示达到 {@code rawLimit} 后仍有原始点未取回
         */
        public boolean isRawTruncated() {
            return rawTruncated;
        }
    }

    @Override
    public void close() {
        core.executor().close();
    }
}