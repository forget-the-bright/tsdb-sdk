package io.github.forget_the_bright.tsdb.pspace.internal.adapter;

import io.github.forget_the_bright.tsdb.common.api.HistoryReader;
import io.github.forget_the_bright.tsdb.common.exception.TsdbException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbQueryException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException;
import io.github.forget_the_bright.tsdb.common.model.Aggregation;
import io.github.forget_the_bright.tsdb.common.model.Direction;
import io.github.forget_the_bright.tsdb.common.model.FillPolicy;
import io.github.forget_the_bright.tsdb.common.model.HistoryQuery;
import io.github.forget_the_bright.tsdb.common.model.QueryMode;
import io.github.forget_the_bright.tsdb.common.model.Sample;
import io.github.forget_the_bright.tsdb.common.model.TagSeries;
import io.github.forget_the_bright.tsdb.pspace.internal.PspaceModels;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * pSpace 历史数据适配器：RAW 走 {@code /HistDataPage}，聚合/插值/采样走
 * {@code /HistAggreDataPage}。
 *
 * <p>模式映射：</p>
 * <ul>
 *   <li>{@link QueryMode#RAW}：原始点，按 pid 分组，方向与 {@code maxPoints}
 *       截断在适配器内完成，截断置 {@code truncated}；</li>
 *   <li>{@link QueryMode#AGGREGATED}：聚合函数映射到厂商函数名
 *       （{@code AVG→avg}、{@code MIN→minim}、{@code MAX→maxim}、
 *       {@code SUM→total}、{@code COUNT→count}、{@code FIRST→staval}、
 *       {@code LAST→endval}、{@code RANGE→range}、{@code INTERVAL_SUM→delta}、
 *       {@code BOUND→intve}），其余取值抛不支持；</li>
 *   <li>{@link QueryMode#INTERPOLATED}：{@code intve}，
 *       {@link QueryMode#SAMPLED}：{@code orgintve}，两者都只支持
 *       {@link FillPolicy#NONE}；</li>
 *   <li>{@link QueryMode#TREND}：不支持。</li>
 * </ul>
 *
 * <p>间隔单位：pSpace 只接受整秒。{@code interval} 设置时必须为整秒；
 * 只有 {@code count} 时按 {@code (end-start)/count} 推导，小于 1 秒抛
 * {@link TsdbUnsupportedException}，不做静默取整。</p>
 *
 * <p>{@link Aggregation#INTERVAL_SUM} 例外：它是“整窗求和”，必须整窗单桶。
 * 适配器会忽略调用方传入的 {@code interval/count}，固定用
 * {@code (end-start)} 作为桶宽，此时厂商 {@code delta} 等于
 * “窗口末值−窗口首值”，符合契约。其它聚合仍按 {@code interval/count} 分桶。</p>
 *
 * <p>厂商 {@code delta} 是“桶内末值−桶内首值”，不是
 * {@code DeltaReader} 的“本桶末值−上一桶末值”，因此只用于整窗
 * {@code INTERVAL_SUM}；累计量差分请走 {@link PspaceDeltaAdapter} 的基线折叠。</p>
 *
 * <p>时间戳：{@code avg/minim/maxim/total/count/range/delta/intve/orgintve}
 * 返回桶起点；{@code staval}/{@code endval} 返回桶内真实采样时刻，适配器原样保留。</p>
 *
 * <p>空桶：pSpace 对没有数据的桶只返回 {@code tm} 与 {@code qy=0}，不含 {@code pv}。
 * 非 RAW 模式下这类桶<strong>不进入</strong> {@link TagSeries#getSamples()}，改为在
 * {@code attributes} 中记录 {@code valueMissingReason}、{@code valueMissingCount}、
 * {@code valueMissingFirstAt}、{@code valueMissingLastAt}，全部为空时再记
 * {@code valueMissingAll=true}。RAW 模式的 null 值仍保留为 {@code Sample.ofNull}。</p>
 *
 * <p><b>非公共 API</b>：门户装配层实现，签名随内部重构变化，宿主应用请勿直接依赖；
 * 稳定契约请使用 {@link io.github.forget_the_bright.tsdb.pspace.PspaceClient}。</p>
 *
 * @author wanghao
 * @since 0.1.2
 */
public final class PspaceHistoryAdapter implements HistoryReader {

    private final PspaceSupport support;

    /**
     * @param support 共享依赖
     */
    public PspaceHistoryAdapter(PspaceSupport support) {
        this.support = support;
    }

    /**
     * 按查询条件读取历史数据。
     *
     * @param query 查询条件，不能为 {@code null}
     * @return 与 {@code query.getTags()} 顺序一致的序列列表
     * @throws IllegalArgumentException {@code query} 为 {@code null} 时
     * @throws TsdbUnsupportedException 模式、聚合、间隔或补点策略不支持时
     * @throws TsdbQueryException       不允许部分结果但结果被截断时
     * @throws TsdbException            查询失败时
     */
    @Override
    public List<TagSeries> read(HistoryQuery query) {
        support.ensureOpen();
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        QueryMode mode = query.getMode();
        if (mode == QueryMode.TREND) {
            throw support.unsupported("pSpace does not support TREND queries", "history", "TREND");
        }
        if ((mode == QueryMode.INTERPOLATED || mode == QueryMode.SAMPLED)
                && query.getFill() != FillPolicy.NONE) {
            throw support.unsupported("pSpace " + mode + " supports only FillPolicy.NONE, but was "
                    + query.getFill(), "history", mode.name());
        }
        try {
            Map<String, PspaceModels.Point> points = support.resolver().resolveAll(query.getTags());
            List<String> unresolved = unresolved(query.getTags(), points);
            if (!unresolved.isEmpty() && !query.isPartialResultAllowed()) {
                throw new TsdbQueryException("pSpace could not resolve tags: " + unresolved);
            }
            Map<Long, String> names = new LinkedHashMap<Long, String>();
            List<Long> ids = new ArrayList<Long>();
            for (String tag : query.getTags()) {
                PspaceModels.Point point = points.get(tag);
                if (point != null) {
                    ids.add(Long.valueOf(point.id));
                    names.put(Long.valueOf(point.id), tag);
                }
            }
            Map<Long, List<PspaceModels.HistValue>> rows = ids.isEmpty()
                    ? Collections.<Long, List<PspaceModels.HistValue>>emptyMap()
                    : load(mode, query, ids);
            return build(query, points, names, rows);
        } catch (TsdbException e) {
            throw support.context(e, "history");
        }
    }

    /**
     * 按模式选择端点并读取所有页。
     *
     * @param mode  查询模式
     * @param query 查询条件
     * @param ids   已解析的点位 id
     * @return 点位 id 到值的映射
     */
    private Map<Long, List<PspaceModels.HistValue>> load(QueryMode mode, HistoryQuery query,
                                                         List<Long> ids) {
        if (mode == QueryMode.RAW) {
            return loadPages(pageNumber -> support.nativeApi().histDataPage(ids,
                    query.getStart(), query.getEnd(), support.config().getPageSize(), pageNumber),
                    "/HistDataPage");
        }
        String aggfunc;
        if (mode == QueryMode.INTERPOLATED) {
            aggfunc = "intve";
        } else if (mode == QueryMode.SAMPLED) {
            aggfunc = "orgintve";
        } else {
            aggfunc = aggfunc(query.getAggregation());
        }
        int seconds = intervalSeconds(query, mode == QueryMode.AGGREGATED
                && query.getAggregation() == Aggregation.INTERVAL_SUM);
        return loadPages(pageNumber -> support.nativeApi().histAggreDataPage(ids, aggfunc, seconds,
                query.getStart(), query.getEnd(), support.config().getPageSize(), pageNumber),
                "/HistAggreDataPage");
    }

    /**
     * 逐页读取一个历史端点，并把每页的行合并成 {@code pid → values}。
     *
     * <p>页数与终止条件完全交给 {@link PspaceSupport#hasMorePages}，不设本地页数上限；
     * 合并保持服务端返回顺序，排序由调用方按方向决定。</p>
     *
     * @param fetcher  按页号取一页（页号从 1 开始）
     * @param endpoint 端点名，仅用于异常消息
     * @return 点位 id 到值的映射，非 {@code null}
     */
    private Map<Long, List<PspaceModels.HistValue>> loadPages(
            IntFunction<PspaceModels.Page> fetcher, String endpoint) {
        int pageSize = support.config().getPageSize();
        Map<Long, List<PspaceModels.HistValue>> result =
                new LinkedHashMap<Long, List<PspaceModels.HistValue>>();
        int pageNumber = 1;
        while (true) {
            PspaceModels.Page page = fetcher.apply(pageNumber);
            collect(page, result);
            if (!support.hasMorePages(endpoint, page, pageSize, pageNumber)) {
                return result;
            }
            pageNumber++;
        }
    }

    /**
     * 把一页的行追加到结果表，同一 pid 跨页时按服务端顺序拼接。
     *
     * @param page   当前页
     * @param result 累积结果
     */
    private void collect(PspaceModels.Page page, Map<Long, List<PspaceModels.HistValue>> result) {
        List<PspaceModels.HistRow> rows = rowsOf(page);
        for (PspaceModels.HistRow row : rows) {
            Long id = Long.valueOf(row.id);
            List<PspaceModels.HistValue> values = result.get(id);
            if (values == null) {
                values = new ArrayList<PspaceModels.HistValue>();
                result.put(id, values);
            }
            values.addAll(row.values);
        }
    }

    /**
     * 分页元素可能是历史行（含 {@code items}），也可能是直接的值；
     * 原生层已提供统一解析，这里复用其格式约定。
     */
    private List<PspaceModels.HistRow> rowsOf(PspaceModels.Page page) {
        if (page.values.isEmpty()) {
            return Collections.emptyList();
        }
        return support.nativeApi().parseHistRows(page.values);
    }

    /**
     * 把 {@code pid → values} 回填成按请求顺序排列的系列。
     *
     * <p>RAW 与聚合都在这里做方向、{@code maxPoints} 截断与异常属性；截断时
     * 是否允许部分结果由 {@code query.isPartialResultAllowed()} 决定。
     * 未解析的位号返回空系列并带 {@code unresolved=true}，未知数据类型带
     * {@code unknownType=true}，不会用默认类型硬转。</p>
     *
     * @param query  查询条件
     * @param points 位号到点位的映射
     * @param names  pid 到请求位号名的映射
     * @param rows   pid 到值的映射
     * @return 与 {@code query.getTags()} 顺序一致的系列
     * @throws TsdbQueryException 截断且不允许部分结果时
     */
    private List<TagSeries> build(HistoryQuery query, Map<String, PspaceModels.Point> points,
                                  Map<Long, String> names,
                                  Map<Long, List<PspaceModels.HistValue>> rows) {
        Map<String, List<PspaceModels.HistValue>> byName =
                new LinkedHashMap<String, List<PspaceModels.HistValue>>();
        for (Map.Entry<Long, String> entry : names.entrySet()) {
            List<PspaceModels.HistValue> values = rows.get(entry.getKey());
            if (values != null) {
                byName.put(entry.getValue(), values);
            }
        }
        int limit = query.getMaxPoints();
        List<TagSeries> result = new ArrayList<TagSeries>(query.getTags().size());
        for (String tag : query.getTags()) {
            PspaceModels.Point point = points.get(tag);
            TagSeries.Builder builder = TagSeries.builder(tag);
            List<PspaceModels.HistValue> values = byName.get(tag);
            if (point == null) {
                builder.attribute("unresolved", "true");
            } else if (support.mapper().isUnknownDataType(point.dataType)) {
                builder.attribute("unknownType", "true");
            }
            if (values != null && !values.isEmpty()) {
                if (query.getMode() == QueryMode.RAW) {
                    List<PspaceModels.HistValue> ordered = order(values, query.getDirection());
                    boolean truncated = ordered.size() > limit;
                    int size = Math.min(ordered.size(), limit);
                    for (int i = 0; i < size; i++) {
                        Sample sample = sampleOf(ordered.get(i), point, query.isIncludeQuality());
                        if (sample != null) {
                            builder.add(sample);
                        }
                    }
                    builder.truncated(truncated);
                } else {
                    appendAggregation(builder, values, point, query);
                }
            }
            result.add(builder.build());
        }
        for (TagSeries series : result) {
            if (series.isTruncated() && !query.isPartialResultAllowed()) {
                throw new TsdbQueryException("pSpace history truncated at maxPoints=" + limit
                        + " and partial results are not allowed");
            }
        }
        return result;
    }

    /**
     * 追加非 RAW 聚合结果：空桶（无 {@code pv}）不进入样本列表，改为系列级诊断属性。
     *
     * <p>{@code valueMissingCount} 统计服务端返回的整个查询区间内的空桶数，
     * 不受 {@code maxPoints} 截断影响；首末时间按时间正序取值。</p>
     */
    private void appendAggregation(TagSeries.Builder builder, List<PspaceModels.HistValue> values,
                                   PspaceModels.Point point, HistoryQuery query) {
        List<PspaceModels.HistValue> chronological = order(values, Direction.FORWARD);
        List<PspaceModels.HistValue> present = new ArrayList<PspaceModels.HistValue>();
        PspaceModels.HistValue firstMissing = null;
        PspaceModels.HistValue lastMissing = null;
        int missing = 0;
        for (PspaceModels.HistValue value : chronological) {
            if (value.value == null) {
                missing++;
                if (firstMissing == null) {
                    firstMissing = value;
                }
                lastMissing = value;
            } else {
                present.add(value);
            }
        }
        List<PspaceModels.HistValue> displayed = present;
        if (query.getDirection() == Direction.BACKWARD) {
            displayed = new ArrayList<PspaceModels.HistValue>(present);
            Collections.reverse(displayed);
        }
        int limit = query.getMaxPoints();
        boolean truncated = displayed.size() > limit;
        int size = Math.min(displayed.size(), limit);
        for (int i = 0; i < size; i++) {
            Sample sample = sampleOf(displayed.get(i), point, query.isIncludeQuality());
            if (sample != null) {
                builder.add(sample);
            }
        }
        builder.truncated(truncated);
        if (missing > 0) {
            builder.attribute("valueMissingReason", "pspace_aggregation_bucket_without_pv");
            builder.attribute("valueMissingCount", Integer.toString(missing));
            builder.attribute("valueMissingFirstAt",
                    support.mapper().timeConfig().formatResponse(firstMissing.timestamp));
            builder.attribute("valueMissingLastAt",
                    support.mapper().timeConfig().formatResponse(lastMissing.timestamp));
            if (present.isEmpty()) {
                builder.attribute("valueMissingAll", "true");
            }
        }
    }

    /**
     * 把一个历史值映射成公共样本；时间戳原样保留（{@code staval/endval}
     * 返回桶内真实采样时刻，不做桶起点归一）。
     *
     * @param value          历史值
     * @param point          点位元数据，可为 {@code null}（未解析）
     * @param includeQuality 是否保留质量码
     * @return 样本，时间戳缺失时为 {@code null}
     */
    private Sample sampleOf(PspaceModels.HistValue value, PspaceModels.Point point,
                            boolean includeQuality) {
        int dataType = point == null ? -1 : point.dataType;
        return support.mapper().toSample(value.timestamp, value.value, value.quality,
                dataType, includeQuality);
    }

    /**
     * 复制并按时间戳排序；{@link Direction#BACKWARD} 时再整体反转。
     *
     * @param values    原始值
     * @param direction 返回方向
     * @return 新列表，不修改入参
     */
    private static List<PspaceModels.HistValue> order(List<PspaceModels.HistValue> values,
                                                      Direction direction) {
        List<PspaceModels.HistValue> copy = new ArrayList<PspaceModels.HistValue>(values);
        copy.sort(Comparator.comparing(v -> v.timestamp));
        if (direction == Direction.BACKWARD) {
            Collections.reverse(copy);
        }
        return copy;
    }

    /**
     * 找出未解析到点位的位号，保持请求顺序。
     *
     * @param tags   请求位号
     * @param points 已解析结果
     * @return 未解析的位号，可能为空
     */
    private static List<String> unresolved(List<String> tags, Map<String, PspaceModels.Point> points) {
        List<String> result = new ArrayList<String>();
        for (String tag : tags) {
            if (!points.containsKey(tag)) {
                result.add(tag);
            }
        }
        return result;
    }

    /**
     * 把公共聚合枚举映射成 pSpace 函数名。
     *
     * <p>{@link Aggregation#MIN} → {@code minim}、{@code MAX} → {@code maxim}、
     * {@code SUM} → {@code total}、{@code FIRST} → {@code staval}、
     * {@code LAST} → {@code endval}、{@code BOUND} → {@code intve}，
     * 这些都是厂商命名而非笔误。</p>
     *
     * <p>{@link Aggregation#INTERVAL_SUM} → {@code delta} 只在整窗单桶时成立：
     * 厂商 {@code delta} 是“桶内末值−桶内首值”，整窗时正好等于窗口末值减首值；
     * 若按分钟分桶，它既不是 {@code SUM} 也不是累计差分。分桶参数由
     * {@link #intervalSeconds} 强制为整窗。</p>
     *
     * @param aggregation 公共聚合方式
     * @return 厂商函数名
     * @throws TsdbUnsupportedException 聚合为空或不支持
     */
    private static String aggfunc(Aggregation aggregation) {
        if (aggregation == null) {
            throw new TsdbUnsupportedException("pSpace aggregated history requires an aggregation");
        }
        switch (aggregation) {
            case AVG:
                return "avg";
            case MIN:
                return "minim";
            case MAX:
                return "maxim";
            case SUM:
                return "total";
            case COUNT:
                return "count";
            case FIRST:
                return "staval";
            case LAST:
                return "endval";
            case RANGE:
                return "range";
            case INTERVAL_SUM:
                return "delta";
            case BOUND:
                return "intve";
            default:
                throw new TsdbUnsupportedException("pSpace does not support aggregation " + aggregation);
        }
    }

    /**
     * 计算聚合请求的单桶秒数。
     *
     * <p>{@code wholeWindow} 优先（{@link Aggregation#INTERVAL_SUM}）：直接取
     * {@code end-start} 的整秒数，忽略调用方的 {@code interval/count}，
     * 保证厂商 {@code delta} 等于整窗末值减首值。否则按 {@code interval}
     * 或 {@code count} 换算，拒绝小于 1 秒或非整秒的间隔，不做取整。</p>
     *
     * @param query       查询条件
     * @param wholeWindow 是否强制整窗单桶
     * @return 单桶秒数，至少 1
     * @throws TsdbUnsupportedException 间隔缺失、小于 1 秒或不是整秒
     */
    private static int intervalSeconds(HistoryQuery query, boolean wholeWindow) {
        if (wholeWindow) {
            long span = Duration.between(query.getStart(), query.getEnd()).toMillis();
            return (int) Math.max(1L, span / 1000L);
        }
        Duration interval = query.getInterval();
        if (interval != null) {
            return wholeSeconds(interval.toMillis());
        }
        Integer count = query.getCount();
        if (count != null && count.intValue() > 0) {
            long span = Duration.between(query.getStart(), query.getEnd()).toMillis();
            long millis = span / count.intValue();
            if (millis < 1000L) {
                throw new TsdbUnsupportedException("pSpace aggregated interval is below 1 second: "
                        + millis + "ms; pSpace supports whole seconds only");
            }
            return (int) (millis / 1000L);
        }
        throw new TsdbUnsupportedException("pSpace aggregated history requires interval or count");
    }

    /**
     * 把毫秒间隔换算成 pSpace 要求的整秒数。
     *
     * @param millis 间隔毫秒数
     * @return 整秒数
     * @throws TsdbUnsupportedException 小于 1 秒、不是整秒或超出 {@code int} 范围
     */
    private static int wholeSeconds(long millis) {
        if (millis < 1000L || millis % 1000L != 0L) {
            throw new TsdbUnsupportedException("pSpace aggregated interval must be whole seconds, but was "
                    + millis + "ms");
        }
        long seconds = millis / 1000L;
        if (seconds > Integer.MAX_VALUE) {
            throw new TsdbUnsupportedException("pSpace aggregated interval is too large: " + seconds + "s");
        }
        return (int) seconds;
    }
}