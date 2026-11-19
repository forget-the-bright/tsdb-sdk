package io.github.forget_the_bright.tsdb.ge.internal.adapter;

import io.github.forget_the_bright.tsdb.common.api.DeltaReader;
import io.github.forget_the_bright.tsdb.common.config.TimeConfig;
import io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException;
import io.github.forget_the_bright.tsdb.common.model.Aggregation;
import io.github.forget_the_bright.tsdb.common.model.DeltaPoint;
import io.github.forget_the_bright.tsdb.common.model.DeltaSeries;
import io.github.forget_the_bright.tsdb.common.model.Direction;
import io.github.forget_the_bright.tsdb.common.model.FillPolicy;
import io.github.forget_the_bright.tsdb.common.model.Sample;
import io.github.forget_the_bright.tsdb.common.model.TimeGranularity;
import io.github.forget_the_bright.tsdb.ge.internal.GeModels;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.github.forget_the_bright.tsdb.ge.GeMapper;

/**
 * GE 累计量差分适配器：扩展一个间隔取回基线桶，再在内存中相邻相减。
 *
 * <p>查询起点向前推一个间隔、服务端 {@code count} 补 1，第 0 个样本就是
 * 「起点前最后一个桶」的 {@code LastRawValue}，直接作基线；稠密响应下整批位号
 * <b>只发 1 次请求</b>。首值为空或无样本的位号，才补一条批量反向 {@code raw}
 * （count=1，回看 366 天）兜底，仍是常数次。累计值回落时把 {@code reset} 置真、
 * 该桶差值取本桶末值，避免清零后出现巨大负数。</p>
 *
 * <p>{@link #read} 走 {@link TimeGranularity}（月不支持）；
 * {@link #readByInterval} 直接下发毫秒间隔，分桶网格锚定在传入起点。</p>
 *
 * <p><b>非公共 API</b>：门户装配层实现，签名随内部重构变化，宿主应用请勿直接依赖；
 * 稳定契约请使用 {@link io.github.forget_the_bright.tsdb.ge.GeClient}。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class GeDeltaAdapter implements DeltaReader {

    private final GeSupport support;

    /**
     * @param support 共享配置与原生客户端
     */
    public GeDeltaAdapter(GeSupport support) {
        this.support = support;
    }

    /**
     * 查询累计量差分。
     *
     * @param tagNames    位号集合，不能为空
     * @param start       查询起点（含）；按自然粒度归整
     * @param end         查询终点（不含）
     * @param granularity 时间粒度，GE 不支持日历月
     * @param aggregation 必须为 {@code null} 或 {@link Aggregation#LAST}
     * @param fill        必须为 {@code null} 或 {@link FillPolicy#NONE}
     * @param direction   排序方向
     * @param maxPoints   最大差分点数
     * @return 每个位号一条差分序列，顺序与入参一致
     */
    @Override
    public List<DeltaSeries> read(Collection<String> tagNames, Instant start, Instant end,
                                  TimeGranularity granularity, Aggregation aggregation,
                                  FillPolicy fill, Direction direction, int maxPoints) {
        if (granularity == null) {
            throw new IllegalArgumentException("granularity must not be null");
        }
        if (granularity == TimeGranularity.MONTH) {
            throw support.unsupported(new TsdbUnsupportedException(
                    "GE cumulative delta does not support calendar month granularity"), "deltaHistory");
        }
        validate(tagNames, aggregation, fill);
        requireRange(start, end);
        ZoneId zone = support.config().getTimeConfig().getResponseTargetZone();
        Instant normalizedStart = granularity.normalizeStart(start, zone);
        return readInternal(tagNames, normalizedStart, end, granularity.getMillis(),
                fill, direction, maxPoints);
    }

    /**
     * 按任意毫秒间隔查询累计量差分。
     *
     * <p>直接以 {@code intervalMillis} 作为 {@code calculated} 请求的
     * {@code intervalMs}，不取整、不归整起点；查询起点同样向前扩一个间隔，
     * 由扩展桶提供基线。</p>
     *
     * @param tagNames       位号集合，不能为空
     * @param start          查询起点（含），同时是分桶网格锚点
     * @param end            查询终点（不含）
     * @param intervalMillis 间隔毫秒数，必须大于 0
     * @param aggregation    必须为 {@code null} 或 {@link Aggregation#LAST}
     * @param fill           必须为 {@code null} 或 {@link FillPolicy#NONE}
     * @param direction      排序方向
     * @param maxPoints      最大差分点数
     * @return 每个位号一条差分序列，顺序与入参一致
     */
    @Override
    public List<DeltaSeries> readByInterval(Collection<String> tagNames, Instant start, Instant end,
                                            long intervalMillis, Aggregation aggregation, FillPolicy fill,
                                            Direction direction, int maxPoints) {
        validate(tagNames, aggregation, fill);
        requireRange(start, end);
        if (intervalMillis <= 0L) {
            throw new IllegalArgumentException("intervalMillis must be greater than 0");
        }
        return readInternal(tagNames, start, end, intervalMillis, fill, direction, maxPoints);
    }

    /**
     * 校验位号、聚合与填充的公共约束。
     *
     * @param tagNames    位号集合
     * @param aggregation 聚合方式
     * @param fill        填充策略
     */
    private void validate(Collection<String> tagNames, Aggregation aggregation, FillPolicy fill) {
        if (tagNames == null || tagNames.isEmpty()) {
            throw new IllegalArgumentException("tagNames must not be empty");
        }
        if (aggregation != null && aggregation != Aggregation.LAST) {
            throw support.unsupported(new TsdbUnsupportedException(
                    "GE cumulative delta requires LAST aggregation"), "deltaHistory");
        }
        if (fill != null && fill != FillPolicy.NONE) {
            throw support.unsupported(new TsdbUnsupportedException(
                    "GE cumulative delta does not support fill policy " + fill), "deltaHistory");
        }
    }

    /**
     * 校验查询区间。
     *
     * @param start 查询起点
     * @param end   查询终点
     */
    private void requireRange(Instant start, Instant end) {
        if (start == null || end == null) {
            throw new IllegalArgumentException("start and end must not be null");
        }
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end must be after start");
        }
    }

    /**
     * 按给定毫秒间隔取桶末值并相减。
     *
     * <p>查询范围是 {@code [bucketStart - intervalMillis, end)}，服务端 {@code count}
     * 为「窗口桶数 + 1」（受 {@code maxPoints} 封顶）：多出来的那个桶就是基线，
     * 返回后不输出，因此输出点数与用户请求的窗口点数一致。首值为空的位号才走一次
     * 批量 raw 兜底。</p>
     *
     * @param tagNames       位号集合
     * @param bucketStart    分桶起点（含）
     * @param end            查询终点（不含）
     * @param intervalMillis 桶长度（毫秒）
     * @param fill           填充策略（仅 NONE）
     * @param direction      返回方向
     * @param maxPoints      最大点数
     * @return 每个位号一条差分序列
     */
    private List<DeltaSeries> readInternal(Collection<String> tagNames, Instant bucketStart, Instant end,
                                           long intervalMillis, FillPolicy fill,
                                           Direction direction, int maxPoints) {
        int limit = maxPoints <= 0 ? 10000 : maxPoints;
        Instant extendedStart = bucketStart.minusMillis(intervalMillis);
        long windowBuckets = Math.max(0L,
                (end.toEpochMilli() - bucketStart.toEpochMilli()) / intervalMillis);
        int serverCount = (int) Math.min(Math.min(windowBuckets, (long) limit) + 1L,
                (long) Integer.MAX_VALUE);
        List<GeModels.Series> sampled = support.nativeApi().readAggregated(tagNames, extendedStart, end,
                19, serverCount, intervalMillis);
        Map<String, GeModels.Series> byName = new LinkedHashMap<String, GeModels.Series>();
        for (GeModels.Series item : sampled) {
            if (item.getTagName() != null) {
                byName.put(item.getTagName(), item);
            }
        }

        Map<String, Double> baselines = new LinkedHashMap<String, Double>();
        Map<String, List<Sample>> windows = new LinkedHashMap<String, List<Sample>>();
        List<String> fallback = new ArrayList<String>();
        for (String tag : tagNames) {
            GeModels.Series item = byName.get(tag);
            List<Sample> samples = item == null || item.getErrorCode() != null
                    ? Collections.<Sample>emptyList()
                    : GeMapper.toSeries(item, tag, support.config().getTimeConfig()).getSamples();
            Double baseline = null;
            int from = 0;
            if (!samples.isEmpty()) {
                // 服务端按桶回样本，第 0 个样本就是基线桶：有值即作基线，
                // 无值也照样丢弃——留着会被当成窗口首桶的缺数，整条序列错一格。
                from = 1;
                if (samples.get(0).getDoubleValue() != null) {
                    baseline = samples.get(0).getDoubleValue();
                } else {
                    fallback.add(tag);
                }
            }
            baselines.put(tag, baseline);
            int to = (int) Math.min((long) samples.size(), (long) from + limit);
            windows.put(tag, from >= to
                    ? Collections.<Sample>emptyList() : samples.subList(from, to));
        }
        if (!fallback.isEmpty()) {
            Map<String, Double> extra = baselines(fallback, bucketStart);
            for (Map.Entry<String, Double> entry : extra.entrySet()) {
                baselines.put(entry.getKey(), entry.getValue());
            }
        }

        List<DeltaSeries> result = new ArrayList<DeltaSeries>(tagNames.size());
        for (String tag : tagNames) {
            Double previous = baselines.get(tag);
            List<DeltaPoint> points = new ArrayList<DeltaPoint>();
            for (Sample sample : windows.get(tag)) {
                Double current = sample.getDoubleValue();
                DeltaPoint point;
                if (current == null) {
                    point = point(sample.getTimestamp(), null, false, true);
                } else if (previous == null) {
                    point = point(sample.getTimestamp(), null, false, true);
                } else {
                    double delta = current - previous;
                    boolean reset = delta < 0;
                    point = point(sample.getTimestamp(), reset ? current : delta, reset, false);
                }
                points.add(point);
                if (current != null) {
                    previous = current;
                }
            }
            if (direction == Direction.BACKWARD) {
                Collections.reverse(points);
            }
            result.add(new DeltaSeries(tag, points));
        }
        return result;
    }

    /**
     * 批量取查询起点之前的最后一个原始值，仅供扩展桶缺失的位号兜底。
     *
     * <p>单个位号带错误码或无数据时该位号基线为 {@code null}（差分首点记为缺数据）；
     * 整个请求失败时返回空表，兜底位号基线均为 {@code null}，与旧实现的容错语义一致。</p>
     *
     * @param tags  位号集合
     * @param start 查询起点
     * @return 位号 → 基线值，缺失/失败的位号不在表中
     */
    private Map<String, Double> baselines(Collection<String> tags, Instant start) {
        Map<String, Double> result = new LinkedHashMap<String, Double>();
        try {
            List<GeModels.Series> series = support.nativeApi().readRaw(tags,
                    start.minusSeconds(366L * 24 * 3600), start, Direction.BACKWARD, 1);
            for (GeModels.Series item : series) {
                if (item.getTagName() == null || item.getErrorCode() != null) {
                    continue;
                }
                List<Sample> samples = GeMapper.toSeries(item, item.getTagName(),
                        support.config().getTimeConfig()).getSamples();
                if (!samples.isEmpty() && samples.get(0).getDoubleValue() != null) {
                    result.put(item.getTagName(), samples.get(0).getDoubleValue());
                }
            }
        } catch (RuntimeException e) {
            return Collections.emptyMap();
        }
        return result;
    }

    /**
     * 构造带展示时间字段的差分点。
     *
     * @param timestamp 区间结束时刻
     * @param delta     差值
     * @param reset     是否回落
     * @param missing   是否缺数据
     * @return 差分点
     */
    private DeltaPoint point(Instant timestamp, Double delta, boolean reset, boolean missing) {
        TimeConfig time = support.config().getTimeConfig();
        return new DeltaPoint(timestamp, Date.from(timestamp), time.formatResponse(timestamp),
                delta, reset, missing);
    }
}