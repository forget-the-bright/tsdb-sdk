package io.github.forget_the_bright.tsdb.iotdb.internal.adapter;

import io.github.forget_the_bright.tsdb.common.exception.TsdbQueryException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException;
import io.github.forget_the_bright.tsdb.common.model.Aggregation;
import io.github.forget_the_bright.tsdb.common.model.Direction;
import io.github.forget_the_bright.tsdb.common.model.HistoryQuery;
import io.github.forget_the_bright.tsdb.common.model.QueryMode;
import io.github.forget_the_bright.tsdb.common.model.TagSeries;
import io.github.forget_the_bright.tsdb.iotdb.internal.IotdbModels;
import io.github.forget_the_bright.tsdb.iotdb.internal.query.IotdbQueryService;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.IotdbTagPath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import io.github.forget_the_bright.tsdb.iotdb.IotdbMapper;

/**
 * 历史结果的组装器：失败占位、REST 直连、整窗增量与插值网格。
 *
 * <p>把“取数”与“把结果摆回入参顺序”分开：本类只负责后者，取数由
 * {@link IotdbHistoryAdapter} 完成。因此失败归因（位号级 vs 块级）、
 * 整窗累计增量与插值网格的推导都集中在这里，避免散落在多个适配器中。</p>
 *
 * <p><b>非公共 API</b>：门户装配层实现，签名随内部重构变化，宿主应用请勿直接依赖；
 * 稳定契约请使用 {@link io.github.forget_the_bright.tsdb.iotdb.IotdbClient}。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class IotdbSeriesAssembler {

    private static final int DEFAULT_MAX_POINTS = 1;

    private final IotdbSupport support;

    /**
     * @param support 共享依赖
     */
    public IotdbSeriesAssembler(IotdbSupport support) {
        this.support = support;
    }

    /**
     * 按入参顺序组装序列列表：先回填失败占位，再交 {@code success} 生成正常序列。
     *
     * @param query         历史查询条件
     * @param paths         与入参同序的路径，可为 {@code null} 元素
     * @param unresolved    解析失败账本（按位号原文）
     * @param chunkFailures 块级失败账本（按位号绝对路径）
     * @param success       下标 → 该位号的序列
     * @return 与入参位号同序、同长度的序列列表
     */
    List<TagSeries> assemble(HistoryQuery query, List<IotdbTagPath> paths,
                             Map<String, String> unresolved, Map<String, ChunkFailure> chunkFailures,
                             IntFunction<TagSeries> success) {
        List<TagSeries> result = new ArrayList<TagSeries>(query.getTags().size());
        for (int i = 0; i < query.getTags().size(); i++) {
            String tag = query.getTags().get(i);
            String error = unresolved.get(tag);
            if (error != null) {
                result.add(failedSeries(tag, error));
                continue;
            }
            IotdbTagPath path = paths.get(i);
            ChunkFailure chunk = path == null ? null : chunkFailures.get(path.getAbsolutePath());
            if (chunk == null) {
                result.add(success.apply(i));
            } else {
                // 一条 SQL 只覆盖一个位号时（query-batch-size: 1 就是这个回退档）失败
                // 天然是逐位号粒度，标成 chunk 就是把影响范围说大，现场会照这个数去核对。
                result.add(chunk.tags > 1 ? failedChunkSeries(tag, chunk.error) : failedSeries(tag, chunk.error));
            }
        }
        return result;
    }

    /**
     * 未装配统一 SQL 查询服务时的逐位号 REST 直连路径。
     *
     * <p>只覆盖 {@code RAW} 与 {@code AGGREGATED}；一位号一次请求，失败天然是
     * 逐位号粒度，因此不写 {@code vendorErrorScope}。</p>
     *
     * @param query      历史查询条件
     * @param paths      与入参同序的路径
     * @param unresolved 解析失败账本（按位号原文）
     * @return 与入参位号同序的序列列表
     */
    List<TagSeries> restSeries(HistoryQuery query, List<IotdbTagPath> paths,
                               Map<String, String> unresolved) {
        List<TagSeries> result = new ArrayList<TagSeries>(query.getTags().size());
        for (int i = 0; i < query.getTags().size(); i++) {
            String tag = query.getTags().get(i);
            String error = unresolved.get(tag);
            if (error != null) {
                result.add(failedSeries(tag, error));
                continue;
            }
            try {
                String path = paths.get(i).getAbsolutePath();
                List<IotdbModels.Point> points = query.getMode() == QueryMode.RAW
                        ? support.api().readRaw(path, query.getStart(), query.getEnd(), query.getDirection(),
                        query.getMaxPoints() + 1)
                        : support.api().readAggregated(path, query.getAggregation(), query.getStart(),
                        query.getEnd(), query.getInterval().toMillis(), query.getDirection(),
                        query.getMaxPoints() + 1);
                result.add(toSeries(tag, points, query.getMaxPoints(), false));
            } catch (RuntimeException e) {
                if (!query.isPartialResultAllowed()) {
                    throw e instanceof TsdbQueryException
                            ? support.query((TsdbQueryException) e, "readHistory")
                            : e;
                }
                result.add(failedSeries(tag, e.getMessage()));
            }
        }
        return result;
    }

    /**
     * 读取整窗累计增量并组装成单点序列。
     *
     * <p>锚点缺失时返回空序列而不是 0，并标记
     * {@code intervalSumMissingAnchor=true}，由业务显式决定如何呈现。</p>
     *
     * @param query         历史查询条件，模式为 {@code AGGREGATED}、聚合为 {@code INTERVAL_SUM}
     * @param paths         与入参同序的路径
     * @param unresolved    解析失败账本（按位号原文）
     * @param chunkFailures 块级失败账本（按位号绝对路径）
     * @param deltas        成功块的位号绝对路径 → 单点增量
     * @return 非 {@code null} 的序列列表，顺序与入参位号一致
     */
    List<TagSeries> intervalSumSeries(HistoryQuery query, List<IotdbTagPath> paths,
                                      Map<String, String> unresolved,
                                      Map<String, ChunkFailure> chunkFailures,
                                      Map<String, IotdbModels.Point> deltas) {
        return assemble(query, paths, unresolved, chunkFailures, index -> {
            IotdbModels.Point point = deltas.get(paths.get(index).getAbsolutePath());
            TagSeries.Builder builder = TagSeries.builder(query.getTags().get(index));
            if (point == null) {
                builder.attribute("intervalSumMissingAnchor", "true");
            } else {
                builder.add(IotdbMapper.toSample(point, support.config().getTimeConfig()));
            }
            return builder.build();
        });
    }

    /**
     * 由 {@code interval} 与 {@code count} 推导插值网格。
     *
     * <p>给了 {@code interval} 就用它当槽位间隔；只给 {@code count} 时按
     * {@code span / (count - 1)} 等分，使首点落在 {@code start}、末点落在
     * {@code end}。两者都没有、或间隔为 0 却要多个槽位，直接拒绝——猜一个网格
     * 会让报表按索引取点时静默错位。</p>
     *
     * @param query 历史查询条件，模式为 {@link QueryMode#INTERPOLATED}
     * @return 非 {@code null} 的网格描述
     * @throws TsdbUnsupportedException 网格无法确定时
     */
    Grid interpolationGrid(HistoryQuery query) {
        long span = Math.max(0L, query.getEnd().toEpochMilli() - query.getStart().toEpochMilli());
        java.time.Duration interval = query.getInterval();
        Integer count = query.getCount();
        if (interval == null && (count == null || count.intValue() < 1)) {
            throw support.unsupported(new TsdbUnsupportedException("IoTDB interpolated history requires "
                    + "an interval or a positive count"), "readHistory");
        }
        long step = interval != null ? Math.max(0L, interval.toMillis())
                : (count != null && count.intValue() > 1 && span > 0
                ? span / (count.intValue() - 1L) : 0L);
        long requested = count != null && count.intValue() > 0
                ? count.longValue()
                : (step > 0 ? (span + step - 1L) / step : 1L);
        if (step <= 0 && requested > 1) {
            throw support.unsupported(new TsdbUnsupportedException("IoTDB interpolated history needs "
                    + "a positive interval for " + requested + " grid slots"), "readHistory");
        }
        int maxPoints = Math.max(DEFAULT_MAX_POINTS, query.getMaxPoints());
        boolean truncated = requested > maxPoints;
        return new Grid(step, (int) Math.min(Math.max(1L, requested), (long) maxPoints), truncated);
    }

    /**
     * 把插值网格结果组装为公共序列。
     *
     * <p>网格点数恒等于槽位数，{@code truncated} 只反映槽位被 {@code maxPoints}
     * 裁剪；窗口内原始点被 {@code interpolation-raw-limit} 截断是另一件事，
     * 写入 {@code interpolationRawTruncated} 属性。</p>
     *
     * @param tag           位号原名
     * @param interpolation 插值结果
     * @param grid          网格描述
     * @param direction     返回顺序
     * @param rawCompleted  窗内原始点是否走过逐位号补采
     * @return 非 {@code null} 的序列
     */
    TagSeries interpolatedSeries(String tag, IotdbQueryService.Interpolation interpolation,
                                 Grid grid, Direction direction, boolean rawCompleted) {
        List<IotdbModels.Point> points = interpolation.getPoints();
        TagSeries.Builder builder = TagSeries.builder(tag).truncated(grid.truncated);
        if (interpolation.isRawTruncated()) {
            builder.attribute("interpolationRawTruncated", "true");
        }
        if (rawCompleted) {
            builder.attribute("rawBatchCompleted", "true");
        }
        if (direction == Direction.BACKWARD) {
            for (int i = points.size() - 1; i >= 0; i--) {
                builder.add(IotdbMapper.toSample(points.get(i), support.config().getTimeConfig()));
            }
        } else {
            for (IotdbModels.Point point : points) {
                builder.add(IotdbMapper.toSample(point, support.config().getTimeConfig()));
            }
        }
        return builder.build();
    }

    /**
     * 把厂商点位列表裁剪并转换为公共序列。
     *
     * <p>服务端被要求多取一个点，因此 {@code points.size() > maxPoints} 即说明
     * 结果被截断：只保留前 {@code maxPoints} 个点并置 {@code truncated=true}。</p>
     *
     * @param tag          位号全路径，作为序列名
     * @param points       服务端返回的点列表，非 {@code null}
     * @param maxPoints    允许返回的最大点数
     * @param rawCompleted 该位号的原始点是否走过逐位号补采（仅 RAW 批量有意义）
     * @return 非 {@code null} 的序列，可能为空
     */
    TagSeries toSeries(String tag, List<IotdbModels.Point> points, int maxPoints, boolean rawCompleted) {
        boolean truncated = points.size() > maxPoints;
        int size = Math.min(points.size(), maxPoints);
        TagSeries.Builder builder = TagSeries.builder(tag).truncated(truncated);
        if (rawCompleted) {
            builder.attribute("rawBatchCompleted", "true");
        }
        for (int i = 0; i < size; i++) {
            builder.add(IotdbMapper.toSample(points.get(i), support.config().getTimeConfig()));
        }
        return builder.build();
    }

    /**
     * 判断聚合函数是否在 IoTDB 驱动的白名单内。
     *
     * <p>白名单仅 {@code AVG/MIN/MAX/SUM/COUNT/FIRST/LAST} 七种：
     * 它们能被一条 {@code GROUP BY} 聚合 SQL 直接下推。{@code INTERVAL_SUM}
     * 返回 {@code false}，但它在入口已被提前拦截并交给专用 SQL，
     * 绝不静默退化成 {@code SUM}。</p>
     *
     * @param aggregation 待判断的聚合函数，可为 {@code null}
     * @return {@code true} 表示支持
     */
    static boolean supported(Aggregation aggregation) {
        if (aggregation == null) {
            return false;
        }
        switch (aggregation) {
            case AVG:
            case MIN:
            case MAX:
            case SUM:
            case COUNT:
            case FIRST:
            case LAST:
                return true;
            case INTERVAL_SUM:
                return false;
            default:
                return false;
        }
    }

    /**
     * 单个位号失败的占位序列。
     *
     * @param tag   位号原文
     * @param error 失败原因
     * @return 空序列，带 {@code vendorError} 属性
     */
    static TagSeries failedSeries(String tag, String error) {
        return TagSeries.builder(tag).attribute("vendorError", error).build();
    }

    /**
     * 整块失败的占位序列。
     *
     * @param tag   位号原文
     * @param error 失败原因（含失败语句）
     * @return 空序列，带 {@code vendorError} 与 {@code vendorErrorScope} 属性
     */
    static TagSeries failedChunkSeries(String tag, String error) {
        return TagSeries.builder(tag)
                .attribute("vendorError", error)
                .attribute("vendorErrorScope", "chunk")
                .build();
    }

    /**
     * 一条批量语句的失败记账。
     *
     * <p>{@code tags} 记录那条语句当时覆盖了几个位号：大于 1 才是“整块失败”，
     * 等于 1 就是逐位号失败。分块尺寸由配置决定，只能失败时才知道。</p>
     */
    static final class ChunkFailure {
        final String error;
        final int tags;

        ChunkFailure(String error, int tags) {
            this.error = error;
            this.tags = tags;
        }
    }

    /**
     * 插值网格描述：槽位间隔、槽位数与是否被 {@code maxPoints} 裁剪。
     */
    static final class Grid {

        /** 槽位间隔（毫秒）；单槽位时可为 {@code 0}。 */
        final long stepMillis;

        /** 槽位数量，恒大于等于 1。 */
        final int slots;

        /** 槽位是否因超出 {@code maxPoints} 而被裁剪。 */
        final boolean truncated;

        private Grid(long stepMillis, int slots, boolean truncated) {
            this.stepMillis = stepMillis;
            this.slots = slots;
            this.truncated = truncated;
        }
    }
}