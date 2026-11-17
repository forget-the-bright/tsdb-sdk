package io.github.forget_the_bright.tsdb.hls.internal.adapter;

import io.github.forget_the_bright.tsdb.common.api.DeltaReader;
import io.github.forget_the_bright.tsdb.common.config.TimeConfig;
import io.github.forget_the_bright.tsdb.common.exception.TsdbException;
import io.github.forget_the_bright.tsdb.common.model.Aggregation;
import io.github.forget_the_bright.tsdb.common.model.DeltaPoint;
import io.github.forget_the_bright.tsdb.common.model.DeltaSeries;
import io.github.forget_the_bright.tsdb.common.model.Direction;
import io.github.forget_the_bright.tsdb.common.model.FillPolicy;
import io.github.forget_the_bright.tsdb.common.model.Sample;
import io.github.forget_the_bright.tsdb.common.model.TimeGranularity;
import io.github.forget_the_bright.tsdb.hls.internal.HlsModels;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HLS 累计量差分适配器。
 *
 * <p>HLS 的宽表接口没有 {@code LAST} 聚合；这里用 {@code BOUND}（边界值）作为
 * 每个桶的末值。先取查询起点之前一个桶的边界值作为基线，再与后续桶相邻相减。
 * 适用于累计量点位，普通瞬时量点位使用会得到无业务意义的差值。</p>
 *
 * <p><b>非公共 API</b>：门户装配层实现，签名随内部重构变化，宿主应用请勿直接依赖；
 * 稳定契约请使用 {@link io.github.forget_the_bright.tsdb.hls.HlsClient}。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class HlsDeltaAdapter implements DeltaReader {

    private final HlsSupport support;

    /**
     * @param support 共享依赖
     */
    public HlsDeltaAdapter(HlsSupport support) {
        this.support = support;
    }

    /**
     * 读取累计量差分。
     *
     * @param tagNames    位号集合
     * @param start       查询起点
     * @param end         查询终点
     * @param granularity 时间粒度
     * @param aggregation 必须为 {@code null} 或 {@link Aggregation#LAST}
     * @param fill        仅支持 {@link FillPolicy#NONE} 或 {@link FillPolicy#PREVIOUS}
     * @param direction   排序方向
     * @param maxPoints   最大点数
     * @return 每个位号一条差分序列
     */
    @Override
    public List<DeltaSeries> read(Collection<String> tagNames, Instant start, Instant end,
                                  TimeGranularity granularity, Aggregation aggregation,
                                  FillPolicy fill, Direction direction, int maxPoints) {
        support.ensureOpen();
        if (tagNames == null || tagNames.isEmpty()) {
            throw new IllegalArgumentException("tagNames must not be empty");
        }
        if (granularity == null || granularity == TimeGranularity.MONTH) {
            throw support.unsupported("HLS cumulative delta requires a fixed granularity",
                    "deltaHistory", granularity == null ? null : granularity.name());
        }
        if (aggregation != null && aggregation != Aggregation.LAST) {
            throw support.unsupported("HLS cumulative delta requires LAST aggregation",
                    "deltaHistory", aggregation.name());
        }
        if (fill != null && fill != FillPolicy.NONE && fill != FillPolicy.PREVIOUS) {
            throw support.unsupported("HLS cumulative delta supports NONE or PREVIOUS fill",
                    "deltaHistory", fill.name());
        }

        long seconds = Math.max(1L, granularity.getMillis() / 1000L);
        int limit = maxPoints <= 0 ? 10000 : maxPoints;
        ZoneId zone = support.config().getTimeConfig().getResponseTargetZone();
        Instant normalized = granularity.normalizeStart(start, zone);

        List<HlsModels.Series> sampled;
        try {
            sampled = support.nativeApi().readHistory(normalized, end, seconds,
                    Aggregation.BOUND, tagNames);
        } catch (TsdbException e) {
            throw support.context(e, "deltaHistory");
        }
        Map<String, HlsModels.Series> byName = new LinkedHashMap<String, HlsModels.Series>();
        for (HlsModels.Series one : sampled) {
            byName.put(one.tagName, one);
        }

        List<DeltaSeries> result = new ArrayList<DeltaSeries>(tagNames.size());
        for (String tag : tagNames) {
            Double previous = baseline(tag, normalized, seconds);
            List<DeltaPoint> points = new ArrayList<DeltaPoint>();
            HlsModels.Series one = byName.get(tag);
            if (one != null) {
                List<Sample> samples = support.mapper().toSeries(one, Aggregation.BOUND, limit + 1)
                        .getSamples();
                for (Sample sample : samples) {
                    Double current = sample.getDoubleValue();
                    if (current == null) {
                        if (fill == FillPolicy.PREVIOUS && previous != null) {
                            points.add(point(sample.getTimestamp(), 0.0d, false, false));
                        } else {
                            points.add(point(sample.getTimestamp(), null, false, true));
                        }
                        continue;
                    }
                    if (previous == null) {
                        points.add(point(sample.getTimestamp(), null, false, true));
                    } else {
                        double delta = current - previous;
                        boolean reset = delta < 0;
                        points.add(point(sample.getTimestamp(), reset ? current : delta, reset, false));
                    }
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
     * 取查询起点前一个桶的边界值作为基线。
     *
     * @param tag     位号
     * @param start   查询起点
     * @param seconds 桶长度（秒）
     * @return 基线值；无数据时返回 {@code null}
     */
    private Double baseline(String tag, Instant start, long seconds) {
        try {
            List<HlsModels.Series> series = support.nativeApi().readHistory(
                    start.minusSeconds(seconds), start, seconds,
                    Aggregation.BOUND, Collections.singletonList(tag));
            if (series.isEmpty()) {
                return null;
            }
            List<Sample> samples = support.mapper().toSeries(series.get(0), Aggregation.BOUND, 10)
                    .getSamples();
            for (int i = samples.size() - 1; i >= 0; i--) {
                Double value = samples.get(i).getDoubleValue();
                if (value != null) {
                    return value;
                }
            }
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 构造带展示字段的差分点。
     *
     * @param timestamp 时间
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