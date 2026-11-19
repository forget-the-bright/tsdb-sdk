package io.github.forget_the_bright.tsdb.iotdb.internal.adapter;

import io.github.forget_the_bright.tsdb.common.api.DeltaReader;
import io.github.forget_the_bright.tsdb.common.model.Aggregation;
import io.github.forget_the_bright.tsdb.common.model.DeltaPoint;
import io.github.forget_the_bright.tsdb.common.model.DeltaSeries;
import io.github.forget_the_bright.tsdb.common.model.Direction;
import io.github.forget_the_bright.tsdb.common.model.FillPolicy;
import io.github.forget_the_bright.tsdb.common.model.TimeGranularity;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.IotdbTagPath;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * IoTDB 累计量差分适配器。
 *
 * <p>先按粒度取每个桶的末值，再取起点前最后一个值作为基线，最后在内存中相邻
 * 相减。位号按 {@code query-batch-scope} 切块，<b>一个块两条 SQL</b>（块内多列的
 * 桶序列 + 块内多列的基线）。差分没有“部分结果”的概念，整块失败直接上抛，
 * 不伪装成单个位号失败。</p>
 *
 * <p>{@link #read} 走自然粒度；{@link #readByInterval} 走任意毫秒间隔，起点不归整，
 * 由 {@code IotdbSql.intervalLiteral} 生成 {@code 5m}/{@code 250ms} 等字面量。</p>
 *
 * <p><b>非公共 API</b>：门户装配层实现，签名随内部重构变化，宿主应用请勿直接依赖；
 * 稳定契约请使用 {@link io.github.forget_the_bright.tsdb.iotdb.IotdbClient}。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class IotdbDeltaAdapter implements DeltaReader {

    private final IotdbSupport support;

    /**
     * @param support 共享依赖
     */
    public IotdbDeltaAdapter(IotdbSupport support) {
        this.support = support;
    }

    /**
     * 读取累计量差分。
     *
     * @param tags        位号集合
     * @param start       查询起点
     * @param end         查询终点
     * @param granularity 时间粒度
     * @param aggregation 聚合方式
     * @param fill        空桶填充策略
     * @param direction   返回方向
     * @param maxPoints   最大点数
     * @return 与入参同序的差分序列列表
     */
    @Override
    public List<DeltaSeries> read(Collection<String> tags, Instant start, Instant end,
                                  TimeGranularity granularity, Aggregation aggregation,
                                  FillPolicy fill, Direction direction, int maxPoints) {
        List<IotdbTagPath> paths = paths(tags);
        Map<String, List<DeltaPoint>> deltas = support.queryService().deltasBatch(paths, start, end,
                granularity, fill, direction, maxPoints);
        return assemble(tags, paths, deltas);
    }

    /**
     * 按任意毫秒间隔读取累计量差分。
     *
     * @param tags           位号集合
     * @param start          查询起点（含），同时是分桶网格锚点
     * @param end            查询终点（不含）
     * @param intervalMillis 间隔毫秒数，必须大于 0
     * @param aggregation    必须为 {@code null} 或 {@link Aggregation#LAST}
     * @param fill           空桶填充策略
     * @param direction      返回方向
     * @param maxPoints      最大点数
     * @return 与入参同序的差分序列列表
     */
    @Override
    public List<DeltaSeries> readByInterval(Collection<String> tags, Instant start, Instant end,
                                            long intervalMillis, Aggregation aggregation, FillPolicy fill,
                                            Direction direction, int maxPoints) {
        List<IotdbTagPath> paths = paths(tags);
        Map<String, List<DeltaPoint>> deltas = support.queryService().deltasByIntervalBatch(
                paths, start, end, intervalMillis, fill, direction, maxPoints);
        return assemble(tags, paths, deltas);
    }

    /**
     * 解析位号对应的序列路径。
     *
     * @param tags 位号集合
     * @return 与入参同序的路径列表
     */
    private List<IotdbTagPath> paths(Collection<String> tags) {
        if (tags == null || tags.isEmpty()) {
            throw new IllegalArgumentException("tags must not be empty");
        }
        List<IotdbTagPath> paths = new ArrayList<IotdbTagPath>(tags.size());
        for (String tag : tags) {
            paths.add(support.tagResolver().resolvePath(tag));
        }
        return paths;
    }

    /**
     * 按入参位号顺序组装差分序列。
     *
     * @param tags   位号集合
     * @param paths  与位号同序的路径
     * @param deltas 路径 → 差分点
     * @return 差分序列列表
     */
    private List<DeltaSeries> assemble(Collection<String> tags, List<IotdbTagPath> paths,
                                       Map<String, List<DeltaPoint>> deltas) {
        List<DeltaSeries> result = new ArrayList<DeltaSeries>(tags.size());
        int index = 0;
        for (String tag : tags) {
            List<DeltaPoint> points = deltas.get(paths.get(index++).getAbsolutePath());
            result.add(new DeltaSeries(tag,
                    points == null ? Collections.<DeltaPoint>emptyList() : points));
        }
        return result;
    }
}