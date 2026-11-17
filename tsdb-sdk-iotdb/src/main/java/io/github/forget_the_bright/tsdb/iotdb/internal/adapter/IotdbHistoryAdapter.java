package io.github.forget_the_bright.tsdb.iotdb.internal.adapter;

import io.github.forget_the_bright.tsdb.common.api.HistoryReader;
import io.github.forget_the_bright.tsdb.common.exception.TsdbQueryException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException;
import io.github.forget_the_bright.tsdb.common.model.Aggregation;
import io.github.forget_the_bright.tsdb.common.model.FillPolicy;
import io.github.forget_the_bright.tsdb.common.model.HistoryQuery;
import io.github.forget_the_bright.tsdb.common.model.QueryMode;
import io.github.forget_the_bright.tsdb.common.model.TagSeries;
import io.github.forget_the_bright.tsdb.iotdb.internal.IotdbModels;
import io.github.forget_the_bright.tsdb.iotdb.internal.query.IotdbQueryService;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.IotdbTagPath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * IoTDB 历史查询适配器：模式/聚合白名单、按块取数与失败归因。
 *
 * <p>时序为：拒绝非 {@code NONE} 的填充策略（采样的 {@code FILL} 可下推，故放行）
 * → {@code AGGREGATED + INTERVAL_SUM} 提前转入整窗增量专用路径 → 只接受
 * {@code RAW}/{@code AGGREGATED}，以及查询服务可用时的 {@code SAMPLED}/
 * {@code INTERPOLATED} → 校验聚合白名单与间隔 → 位号按
 * {@code query-batch-scope} 切块取数 → 由 {@link IotdbSeriesAssembler}
 * 摆回入参顺序并标记失败。</p>
 *
 * <p><b>批量不改变结果口径</b>：返回列表的长度、顺序与每个位号的取值都与
 * 逐位号查询一致。{@code RAW} 与插值因为多列共享 {@code LIMIT}，会让稀疏位号
 * 被挤掉点（实测 M9/G6），取数不足的位号退回逐位号补采，并在属性上留
 * {@code rawBatchCompleted=true}。</p>
 *
 * <p><b>失败的两种粒度</b>：位号解析失败只属于那一个位号；一条 SQL 失败
 * 属于整块，块内每个位号都拿到同样的 {@code vendorError} 并额外带
 * {@code vendorErrorScope=chunk}。</p>
 *
 * <p><b>非公共 API</b>：门户装配层实现，签名随内部重构变化，宿主应用请勿直接依赖；
 * 稳定契约请使用 {@link io.github.forget_the_bright.tsdb.iotdb.IotdbClient}。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class IotdbHistoryAdapter implements HistoryReader {

    private final IotdbSupport support;
    private final IotdbSeriesAssembler assembler;

    /**
     * @param support   共享依赖
     * @param assembler 结果组装器
     */
    public IotdbHistoryAdapter(IotdbSupport support, IotdbSeriesAssembler assembler) {
        this.support = support;
        this.assembler = assembler;
    }

    /**
     * 执行历史查询并按入参顺序返回序列。
     *
     * @param query 历史查询条件，不能为 {@code null}
     * @return 非 {@code null} 的序列列表，顺序与 {@code query.getTags()} 一致
     * @throws IllegalArgumentException 当 {@code query} 为 {@code null} 时
     * @throws TsdbUnsupportedException 当填充策略、查询模式、聚合函数或插值网格不受支持时
     * @throws TsdbQueryException       当位号解析失败或某块查询失败且不允许部分结果时
     */
    @Override
    public List<TagSeries> read(HistoryQuery query) {
        // ===== 阶段一：前置校验（一串 guard，全部通过才允许取数）=====
        // 校验顺序即语义优先级：填充策略 → INTERVAL_SUM 专用路由 → 模式白名单
        // → 聚合白名单 → 间隔。任何一条不满足都直接抛 TsdbUnsupportedException，
        // 不会走到后面的取数逻辑。
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        QueryMode mode = query.getMode();
        // sampled / interpolated 不是单纯比较 mode：SAMPLED 与 INTERPOLATED 只有在
        // 统一 SQL 查询服务（queryService）已装配时才算“真正可用”。若 queryService
        // 缺失，这两个布尔恒为 false，mode 虽仍是 SAMPLED/INTERPOLATED，却会绕开
        // 采样/插值分支，最终落到阶段三的 REST 兜底。
        boolean sampled = mode == QueryMode.SAMPLED && support.queryService() != null;
        boolean interpolated = mode == QueryMode.INTERPOLATED && support.queryService() != null;
        // guard 1 · 填充策略：仅采样路径能把 FILL 下推给 SQL；其余路径遇到非 NONE
        // 的填充策略一律拒绝。注意这里判的是 !sampled，即“非可用采样”都拒绝。
        if (!sampled && query.getFill() != null && query.getFill() != FillPolicy.NONE) {
            throw support.unsupported(new TsdbUnsupportedException("IoTDB history does not support fill policy "
                    + query.getFill()), "readHistory");
        }
        // 专用路由 · INTERVAL_SUM：AGGREGATED + INTERVAL_SUM 不进通用网格，提前
        // 整窗返回，走 intervalSumSeries 的增量专用路径（自带分块与组装）。
        if (mode == QueryMode.AGGREGATED && query.getAggregation() == Aggregation.INTERVAL_SUM) {
            return intervalSumSeries(query);
        }
        // guard 2 · 模式白名单：到这里只接受 RAW、AGGREGATED，以及已被 queryService
        // 确认可用的 sampled / interpolated（后两者用布尔表达“模式且服务就绪”的组合）。
        // 其它模式、或 SAMPLED/INTERPOLATED 但服务缺失的，都不在此拦截 —— 服务缺失
        // 的会由 REST 兜底接管；真正不支持的模式才在这里抛错。
        if (mode != QueryMode.RAW && mode != QueryMode.AGGREGATED && !sampled && !interpolated) {
            throw support.unsupported(new TsdbUnsupportedException(
                    "IoTDB driver does not support history mode " + mode), "readHistory");
        }
        // guard 3 · 聚合白名单：AGGREGATED 必带聚合函数；采样允许带（也可不带，
        // 后者默认 LAST）。二者任一成立时，聚合函数必须在 IotdbSeriesAssembler
        // 白名单内，否则拒绝。
        if ((mode == QueryMode.AGGREGATED || (sampled && query.getAggregation() != null))
                && !IotdbSeriesAssembler.supported(query.getAggregation())) {
            throw support.unsupported(new TsdbUnsupportedException("IoTDB does not support aggregation "
                    + query.getAggregation()), "readHistory");
        }
        // guard 4 · 间隔：AGGREGATED 与采样都需要正间隔；interval 为空即“仅计数”
        // 查询，IoTDB 不支持，直接拒绝。
        if ((mode == QueryMode.AGGREGATED || sampled) && query.getInterval() == null) {
            throw support.unsupported(new TsdbUnsupportedException("IoTDB history requires a positive "
                    + "interval; count-only queries are not supported"), "readHistory");
        }
        // 插值网格：只有 INTERPOLATED 需要预先算出等间隔网格（步长 + 槽位数），
        // 供 interpolateBatch 与后续组装共用；其它模式恒为 null。
        IotdbSeriesAssembler.Grid grid = interpolated ? assembler.interpolationGrid(query) : null;

        // ===== 阶段二：位号解析 + REST 兜底 =====
        // 位号解析失败只属于那一个位号，块级失败属于整块：两本账分开记，
        // 否则一个写错的位号会让同块几十个位号都背上 "chunk" 归因。
        Map<String, String> unresolved = new LinkedHashMap<String, String>();
        List<IotdbTagPath> paths = resolveTags(query, unresolved);
        // REST 兜底：queryService 未装配时，无论 mode 是什么都进不了 SQL 采样/
        // 插值/聚合家族，这里统一改走 REST 取数并组装（unresolved 会带入结果）。
        if (support.queryService() == null) {
            return assembler.restSeries(query, paths, unresolved);
        }
        // ===== 阶段三：按块取数 + 组装（queryService 已确认可用）=====
        // chunkFailures 记账整块失败的位号；completed 记录因 LIMIT 被挤满而
        // 提前结束的位号（RAW / 插值场景），供组装阶段判断是否标记截断。
        Map<String, IotdbSeriesAssembler.ChunkFailure> chunkFailures =
                new LinkedHashMap<String, IotdbSeriesAssembler.ChunkFailure>();
        final Set<String> completed = new LinkedHashSet<String>();
        // 路由 A · 插值（INTERPOLATED）：按块调 interpolateBatch（共用 completed
        // 记账），得到每个位号的插值网格，再按网格与方向摆回入参顺序组装。
        if (interpolated) {
            final Map<String, IotdbQueryService.Interpolation> grids = readInChunks(query, paths, chunkFailures,
                    chunk -> support.queryService().interpolateBatch(chunk, query.getStart(), grid.stepMillis,
                            grid.slots, support.config().getInterpolationToleranceMillis(),
                            support.config().getInterpolationRawLimit(), completed));
            return assembler.assemble(query, paths, unresolved, chunkFailures, index -> {
                String key = paths.get(index).getAbsolutePath();
                return assembler.interpolatedSeries(query.getTags().get(index), grids.get(key), grid,
                        query.getDirection(), completed.contains(key));
            });
        }
        // 路由 B · 采样 / 原始 / 聚合：三族共用同一个 readInChunks 分块入口，
        // 在下面的取数回调里按 mode 选出各自要调的批量查询方法：
        //   - sampled      → intervalBatch（未指定聚合时默认 LAST，可下推 FILL）
        //   - RAW          → rawBatchCompleted（多列共享 LIMIT，用 completed 记账）
        //   - 其余即 AGGREGATED → aggregatedBatch（limit 多取 1 个点用于判定截断）
        final Map<String, List<IotdbModels.Point>> points = readInChunks(query, paths, chunkFailures, chunk -> {
            if (sampled) {
                Aggregation aggregation = query.getAggregation() == null
                        ? Aggregation.LAST : query.getAggregation();
                return support.queryService().intervalBatch(chunk, query.getStart(), query.getEnd(),
                        query.getInterval().toMillis(), aggregation, query.getFill());
            }
            if (mode == QueryMode.RAW) {
                return support.queryService().rawBatchCompleted(chunk, query.getStart(), query.getEnd(),
                        query.getDirection(), query.getMaxPoints(), completed);
            }
            return support.queryService().aggregatedBatch(chunk, query.getAggregation(), query.getStart(),
                    query.getEnd(), query.getInterval().toMillis(), query.getDirection(),
                    query.getMaxPoints() + 1);
        });
        // 组装：按入参位号顺序输出。仅 RAW 且落在 completed 的位号才标记“已截断”，
        // 缺失点数据的位号以空点列表兜底（不让单个失败扩散为整体异常）。
        return assembler.assemble(query, paths, unresolved, chunkFailures, index -> {
            String key = paths.get(index).getAbsolutePath();
            return assembler.toSeries(query.getTags().get(index),
                    points.get(key) == null ? Collections.<IotdbModels.Point>emptyList() : points.get(key),
                    query.getMaxPoints(), mode == QueryMode.RAW && completed.contains(key));
        });
    }

    /**
     * 按配置档位把位号切成块，逐块执行一个批量族，并把整块失败单独记账。
     *
     * <p>把“哪一块失败了”留在循环里，才能在允许部分结果时只让整个块的位号带上
     * {@code vendorErrorScope=chunk}，其余块照常返回。分块对“某一组块”再次调用
     * 结果不变（见 {@link IotdbQueryService#chunks}），因此这里的边界与查询服务
     * 内部分块完全一致。</p>
     *
     * @param <T>           该族的值类型（点序列、网格、单点增量）
     * @param query         历史查询条件，提供是否允许部分结果
     * @param paths         与入参位号同序的路径，解析失败的元素为 {@code null}
     * @param chunkFailures 输出参数：整块失败的位号绝对路径 → 失败记账
     * @param family        一个块的取数动作
     * @return 位号绝对路径 → 该族的结果，覆盖全部成功块
     * @throws TsdbQueryException 不允许部分结果且某块失败时
     */
    private <T> Map<String, T> readInChunks(HistoryQuery query, List<IotdbTagPath> paths,
                                            Map<String, IotdbSeriesAssembler.ChunkFailure> chunkFailures,
                                            Function<List<IotdbTagPath>, Map<String, T>> family) {
        List<IotdbTagPath> resolvable = new ArrayList<IotdbTagPath>(paths.size());
        for (IotdbTagPath path : paths) {
            if (path != null) {
                resolvable.add(path);
            }
        }
        Map<String, T> merged = new LinkedHashMap<String, T>();
        for (List<IotdbTagPath> chunk
                : support.queryService().chunks(support.config().getQueryBatchScope(), resolvable)) {
            try {
                merged.putAll(family.apply(chunk));
            } catch (RuntimeException e) {
                if (!query.isPartialResultAllowed()) {
                    throw e instanceof TsdbQueryException
                            ? support.query((TsdbQueryException) e, "readHistory")
                            : e;
                }
                IotdbSeriesAssembler.ChunkFailure failure =
                        new IotdbSeriesAssembler.ChunkFailure(e.getMessage(), chunk.size());
                for (IotdbTagPath path : chunk) {
                    chunkFailures.put(path.getAbsolutePath(), failure);
                }
            }
        }
        return merged;
    }

    /**
     * 解析查询里的全部位号。
     *
     * <p>解析一次、全程复用同一个 {@link IotdbTagPath}：分组、{@code FROM} 前缀与
     * 列表达式必须由同一个对象派生，否则层级错配会静默返回空结果。</p>
     *
     * @param query      历史查询条件
     * @param unresolved 输出参数：解析失败的位号原文 → 失败原因
     * @return 与 {@code query.getTags()} 同序的路径列表，解析失败的元素为 {@code null}
     */
    private List<IotdbTagPath> resolveTags(HistoryQuery query, Map<String, String> unresolved) {
        List<IotdbTagPath> paths = new ArrayList<IotdbTagPath>(query.getTags().size());
        for (String tag : query.getTags()) {
            try {
                paths.add(support.tagResolver().resolvePath(tag));
            } catch (RuntimeException e) {
                if (!query.isPartialResultAllowed()) {
                    throw e instanceof TsdbQueryException
                            ? support.query((TsdbQueryException) e, "readHistory")
                            : e;
                }
                paths.add(null);
                unresolved.put(tag, e.getMessage());
            }
        }
        return paths;
    }

    /**
     * 读取整窗累计增量并组装成单点序列。
     *
     * <p>锚点缺失时返回空序列而不是 0，并标记
     * {@code intervalSumMissingAnchor=true}。</p>
     *
     * @param query 历史查询条件，模式为 {@code AGGREGATED}、聚合为 {@code INTERVAL_SUM}
     * @return 非 {@code null} 的序列列表，顺序与入参位号一致
     * @throws TsdbUnsupportedException 当未装配统一 SQL 查询服务时
     */
    private List<TagSeries> intervalSumSeries(HistoryQuery query) {
        if (support.queryService() == null) {
            throw support.unsupported(new TsdbUnsupportedException(
                    "IoTDB INTERVAL_SUM requires the unified SQL query service"), "readHistory");
        }
        Map<String, String> unresolved = new LinkedHashMap<String, String>();
        List<IotdbTagPath> paths = resolveTags(query, unresolved);
        Map<String, IotdbSeriesAssembler.ChunkFailure> chunkFailures =
                new LinkedHashMap<String, IotdbSeriesAssembler.ChunkFailure>();
        Map<String, IotdbModels.Point> deltas = readInChunks(query, paths, chunkFailures,
                chunk -> support.queryService().intervalSumBatch(chunk, query.getStart(), query.getEnd()));
        return assembler.intervalSumSeries(query, paths, unresolved, chunkFailures, deltas);
    }
}