package io.github.forget_the_bright.tsdb.pspace.internal.adapter;

import io.github.forget_the_bright.tsdb.common.api.DeltaReader;
import io.github.forget_the_bright.tsdb.common.exception.TsdbException;
import io.github.forget_the_bright.tsdb.common.model.Aggregation;
import io.github.forget_the_bright.tsdb.common.model.DeltaPoint;
import io.github.forget_the_bright.tsdb.common.model.DeltaSeries;
import io.github.forget_the_bright.tsdb.common.model.Direction;
import io.github.forget_the_bright.tsdb.common.model.FillPolicy;
import io.github.forget_the_bright.tsdb.common.model.TimeGranularity;
import io.github.forget_the_bright.tsdb.pspace.internal.PspaceModels;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * pSpace 累计量差分适配器：用 {@code /HistAggreDataPage} + {@code aggfunc=endval}
 * 做基线折叠（与 HLS/GE/IoTDB 已定稿算法一致）。
 *
 * <p>算法：</p>
 * <ol>
 *   <li>桶网格锚定 {@code start}；多取 1 桶（{@code btime = start - interval}）
 *       作为第一段的基线，请求次数仍是 O(1)；</li>
 *   <li>逐桶末值相减；首桶没有基线值（或该桶末值缺失）时该点 {@code missing}；</li>
 *   <li>末值回落视为点位清零，差值取本桶末值并置 {@code reset=true}；</li>
 *   <li>输出只含 {@code [start, end)} 内的桶；{@code maxPoints} 截断；
 *       不支持 {@link TimeGranularity#MONTH} 与非整秒间隔。</li>
 * </ol>
 *
 * <p><b>为什么不用厂商 {@code delta}：</b>{@code aggfunc=delta} 返回的是
 * “每桶末值−每桶首值”的桶内增量，不是 {@link DeltaReader} 契约的
 * “本桶末值−上一桶末值”。累计量每秒累加时，桶内首尾相减会漏掉
 * “上桶末到本桶首”的跨边界增量（每桶只有一个采样点时恒为 0），
 * 也无法表达首桶无基线的 {@code missing} 与回落 {@code reset}；
 * 且 HLS/GE/IoTDB 都是 LAST + 上一桶末值基线，换用 {@code delta}
 * 会让同一接口在不同驱动下口径不一致。改为 {@code delta} 也不会减少请求次数
 * （本适配器只发 1 次 {@code /HistAggreDataPage}，仅多扩一个基线桶）。</p>
 *
 * <p>{@code endval}/{@code staval} 返回的是桶内最后一次采样的真实时刻（如
 * {@code 13:20:34.412}），不是桶起点。适配器按
 * {@code btime + floor((tm - btime) / interval) * interval} 把这些时刻归一到
 * 桶键，再交给折叠算法；其它聚合函数本身已返回桶起点，不受影响。</p>
 *
 * <p><b>非公共 API</b>：门户装配层实现，签名随内部重构变化，宿主应用请勿直接依赖；
 * 稳定契约请使用 {@link io.github.forget_the_bright.tsdb.pspace.PspaceClient}。</p>
 *
 * @author wanghao
 * @since 0.1.2
 */
public final class PspaceDeltaAdapter implements DeltaReader {

    private final PspaceSupport support;

    /**
     * @param support 共享依赖
     */
    public PspaceDeltaAdapter(PspaceSupport support) {
        this.support = support;
    }

    /**
     * 按时间粒度读取累计量差分。
     *
     * @param tags        位号集合
     * @param start       查询起点（含），也是分桶网格锚点
     * @param end         查询终点（不含）
     * @param granularity 时间粒度
     * @param aggregation 聚合方式，必须为 {@code null} 或 {@link Aggregation#LAST}
     * @param fill        补点策略，只支持 {@link FillPolicy#NONE}
     * @param direction   返回方向
     * @param maxPoints   每个位号最多返回的点数
     * @return 与请求位号顺序一致的差分序列
     */
    @Override
    public List<DeltaSeries> read(Collection<String> tags, Instant start, Instant end,
                                  TimeGranularity granularity, Aggregation aggregation,
                                  FillPolicy fill, Direction direction, int maxPoints) {
        support.ensureOpen();
        if (granularity == null) {
            throw new IllegalArgumentException("granularity must not be null");
        }
        if (granularity.isCalendarMonth()) {
            throw support.unsupported("pSpace does not support calendar-month delta", "delta", "MONTH");
        }
        return readByInterval(tags, start, end, granularity.getMillis(), aggregation,
                fill, direction, maxPoints);
    }

    /**
     * 按任意毫秒间隔读取累计量差分（只接受整秒）。
     *
     * @param tags           位号集合
     * @param start          查询起点（含），也是分桶网格锚点
     * @param end            查询终点（不含）
     * @param intervalMillis 单段间隔毫秒数，必须是 1000 的整数倍
     * @param aggregation    聚合方式，必须为 {@code null} 或 {@link Aggregation#LAST}
     * @param fill           补点策略，只支持 {@link FillPolicy#NONE}
     * @param direction      返回方向
     * @param maxPoints      每个位号最多返回的点数
     * @return 与请求位号顺序一致的差分序列
     */
    @Override
    public List<DeltaSeries> readByInterval(Collection<String> tags, Instant start, Instant end,
                                            long intervalMillis, Aggregation aggregation,
                                            FillPolicy fill, Direction direction, int maxPoints) {
        support.ensureOpen();
        if (tags == null || tags.isEmpty()) {
            throw new IllegalArgumentException("tags must not be empty");
        }
        if (start == null || end == null || !start.isBefore(end)) {
            throw new IllegalArgumentException("start must be before end");
        }
        if (maxPoints <= 0) {
            throw new IllegalArgumentException("maxPoints must be > 0");
        }
        if (aggregation != null && aggregation != Aggregation.LAST) {
            throw support.unsupported("pSpace delta supports only Aggregation.LAST, but was "
                    + aggregation, "delta", String.valueOf(aggregation));
        }
        if (fill != null && fill != FillPolicy.NONE) {
            throw support.unsupported("pSpace delta supports only FillPolicy.NONE, but was " + fill,
                    "delta", String.valueOf(fill));
        }
        if (intervalMillis < 1000L || intervalMillis % 1000L != 0L) {
            throw support.unsupported("pSpace delta interval must be whole seconds, but was "
                    + intervalMillis + "ms", "delta", String.valueOf(intervalMillis));
        }
        int seconds = (int) (intervalMillis / 1000L);
        long span = Duration.between(start, end).toMillis();
        int buckets = (int) (span / intervalMillis);
        if (buckets <= 0) {
            return emptySeries(tags);
        }
        Instant gridStart = start;
        Instant btime = start.minusMillis(intervalMillis);
        Instant etime = start.plusMillis(span - span % intervalMillis);
        try {
            Map<String, PspaceModels.Point> points = support.resolver().resolveAll(tags);
            List<Long> ids = new ArrayList<Long>();
            for (String tag : tags) {
                PspaceModels.Point point = points.get(tag);
                if (point != null) {
                    ids.add(Long.valueOf(point.id));
                }
            }
            Map<Long, Map<Instant, Double>> values = ids.isEmpty()
                    ? Collections.<Long, Map<Instant, Double>>emptyMap()
                    : loadEndValues(ids, seconds, btime, etime, intervalMillis);
            List<DeltaSeries> result = new ArrayList<DeltaSeries>(tags.size());
            for (String tag : tags) {
                PspaceModels.Point point = points.get(tag);
                Map<Instant, Double> series = point == null
                        ? Collections.<Instant, Double>emptyMap()
                        : values.get(Long.valueOf(point.id));
                result.add(new DeltaSeries(tag,
                        fold(series == null ? Collections.<Instant, Double>emptyMap() : series,
                                gridStart, intervalMillis, buckets, btime, direction, maxPoints)));
            }
            return result;
        } catch (TsdbException e) {
            throw support.context(e, "delta");
        }
    }

    /**
     * 读取 {@code [btime, etime)} 内每个桶的末值，按桶键归一。
     *
     * <p>{@code endval} 返回桶内真实采样时刻，这里用
     * {@code btime + floor((tm - btime) / interval) * interval} 把同一桶的多个值
     * 折到桶键；后到的值覆盖先到的，因此每桶保留服务端顺序上的最后一个。
     * 落在 {@code btime} 之前或 {@code etime} 及之后的值直接丢弃。</p>
     *
     * @param ids            点位 id 列表，非空
     * @param seconds        单桶秒数
     * @param btime          基线桶起点（{@code start - interval}）
     * @param etime          查询终点，不含
     * @param intervalMillis 单桶毫秒数
     * @return 点位 id 到“桶键 → 末值”的映射
     */
    private Map<Long, Map<Instant, Double>> loadEndValues(List<Long> ids, int seconds,
                                                          Instant btime, Instant etime,
                                                          long intervalMillis) {
        int pageSize = support.config().getPageSize();
        Map<Long, Map<Instant, Double>> result =
                new LinkedHashMap<Long, Map<Instant, Double>>();
        int pageNumber = 1;
        while (true) {
            PspaceModels.Page page = support.nativeApi().histAggreDataPage(ids, "endval", seconds,
                    btime, etime, pageSize, pageNumber);
            for (PspaceModels.HistRow row : support.nativeApi().parseHistRows(page.values)) {
                Map<Instant, Double> values = result.get(Long.valueOf(row.id));
                if (values == null) {
                    values = new LinkedHashMap<Instant, Double>();
                    result.put(Long.valueOf(row.id), values);
                }
                for (PspaceModels.HistValue value : row.values) {
                    Double numeric = toDouble(value.value);
                    if (numeric == null) {
                        continue;
                    }
                    long offsetMillis = Duration.between(btime, value.timestamp).toMillis();
                    if (offsetMillis < 0L || !value.timestamp.isBefore(etime)) {
                        continue;
                    }
                    Instant bucket = btime.plusMillis((offsetMillis / intervalMillis) * intervalMillis);
                    values.put(bucket, numeric);
                }
            }
            if (!support.hasMorePages("/HistAggreDataPage", page, pageSize, pageNumber)) {
                break;
            }
            pageNumber++;
        }
        return result;
    }

    /**
     * 把“桶键 → 末值”折叠成差分点。
     *
     * <p>第一桶用 {@code btime} 的末值作基线；无基线、当前桶无末值时置
     * {@code missing}。当前末值小于上一末值视为清零，差值取本桶末值并置
     * {@code reset=true}。输出只覆盖 {@code [start, end)} 的整桶。</p>
     *
     * @param values         桶键到末值的映射
     * @param start          查询起点，也是分桶网格锚点
     * @param intervalMillis 单桶毫秒数
     * @param buckets        输出桶数
     * @param btime          基线桶起点
     * @param direction      返回方向
     * @param maxPoints      最多保留的点数
     * @return 差分点列表
     */
    private List<DeltaPoint> fold(Map<Instant, Double> values, Instant start,
                                  long intervalMillis, int buckets, Instant btime,
                                  Direction direction, int maxPoints) {
        Double baseline = values.get(btime);
        Double prev = baseline;
        List<DeltaPoint> points = new ArrayList<DeltaPoint>(buckets);
        for (int i = 0; i < buckets; i++) {
            Instant timestamp = start.plusMillis(i * intervalMillis);
            Double current = values.get(timestamp);
            boolean missing = false;
            boolean reset = false;
            Double delta = null;
            if (current == null) {
                missing = true;
            } else if (prev == null) {
                missing = true;
                prev = current;
            } else if (current < prev) {
                delta = current;
                reset = true;
                prev = current;
            } else {
                delta = current - prev;
                prev = current;
            }
            points.add(deltaPoint(timestamp, delta, reset, missing));
        }
        if (direction == Direction.BACKWARD) {
            Collections.reverse(points);
        }
        if (points.size() > maxPoints) {
            points = new ArrayList<DeltaPoint>(points.subList(0, maxPoints));
        }
        return points;
    }

    /**
     * 构造一个差分点：桶起点时间同时提供 {@link Instant}/{@link Date}/格式化文本。
     *
     * @param timestamp 桶起点
     * @param delta     差分值，缺数据时为 {@code null}
     * @param reset     是否发生清零/回落
     * @param missing   是否缺数据
     * @return 差分点
     */
    private DeltaPoint deltaPoint(Instant timestamp, Double delta, boolean reset, boolean missing) {
        return new DeltaPoint(timestamp, Date.from(timestamp),
                support.mapper().timeConfig().formatResponse(timestamp), delta, reset, missing);
    }

    /**
     * 把厂商返回的末值转成 {@code Double}；无法解析时返回 {@code null}
     * （该桶按缺数据处理，不中断整个查询）。
     *
     * @param raw 原始值，可为 {@code null}
     * @return 数值或 {@code null}
     */
    private static Double toDouble(Object raw) {
        if (raw instanceof Number) {
            return Double.valueOf(((Number) raw).doubleValue());
        }
        if (raw == null) {
            return null;
        }
        try {
            return Double.valueOf(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 查询窗口不足一个整桶时，为每个位号返回空差分序列，保持请求顺序。
     *
     * @param tags 请求位号
     * @return 与请求顺序一致的空序列
     */
    private static List<DeltaSeries> emptySeries(Collection<String> tags) {
        List<DeltaSeries> result = new ArrayList<DeltaSeries>(tags.size());
        for (String tag : tags) {
            result.add(new DeltaSeries(tag, Collections.<DeltaPoint>emptyList()));
        }
        return result;
    }
}