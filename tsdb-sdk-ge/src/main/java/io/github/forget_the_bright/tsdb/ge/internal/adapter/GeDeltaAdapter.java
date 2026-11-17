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
 * GE 累计量差分适配器：取每个时间桶的末值，再与起点前基线在内存中相减。
 *
 * <p>桶末值走 {@code calculated} + {@code LastRawValue=19}；基线走反向
 * {@code raw}（count=1，回看 366 天）一次取回全部位号，避免 N+1。累计值回落时
 * 把 {@code reset} 置真、该桶差值取本桶末值，避免清零后出现巨大负数。</p>
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
     * @param start       查询起点（含）
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
        if (tagNames == null || tagNames.isEmpty()) {
            throw new IllegalArgumentException("tagNames must not be empty");
        }
        if (granularity == null) {
            throw new IllegalArgumentException("granularity must not be null");
        }
        if (granularity == TimeGranularity.MONTH) {
            throw support.unsupported(new TsdbUnsupportedException(
                    "GE cumulative delta does not support calendar month granularity"), "deltaHistory");
        }
        if (aggregation != null && aggregation != Aggregation.LAST) {
            throw support.unsupported(new TsdbUnsupportedException(
                    "GE cumulative delta requires LAST aggregation"), "deltaHistory");
        }
        if (fill != null && fill != FillPolicy.NONE) {
            throw support.unsupported(new TsdbUnsupportedException(
                    "GE cumulative delta does not support fill policy " + fill), "deltaHistory");
        }

        int limit = maxPoints <= 0 ? 10000 : maxPoints;
        ZoneId zone = support.config().getTimeConfig().getResponseTargetZone();
        Instant normalizedStart = granularity.normalizeStart(start, zone);
        long intervalMillis = granularity.getMillis();

        List<GeModels.Series> sampled = support.nativeApi().readAggregated(tagNames, normalizedStart, end,
                19, limit + 1, intervalMillis);
        Map<String, GeModels.Series> byName = new LinkedHashMap<String, GeModels.Series>();
        for (GeModels.Series item : sampled) {
            if (item.getTagName() != null) {
                byName.put(item.getTagName(), item);
            }
        }

        List<DeltaSeries> result = new ArrayList<DeltaSeries>(tagNames.size());
        Map<String, Double> baselines = baselines(tagNames, normalizedStart);
        for (String tag : tagNames) {
            GeModels.Series item = byName.get(tag);
            Double previous = baselines.get(tag);
            List<DeltaPoint> points = new ArrayList<DeltaPoint>();
            if (item != null && item.getErrorCode() == null) {
                List<Sample> samples = GeMapper.toSeries(item, tag,
                        support.config().getTimeConfig()).getSamples();
                for (Sample sample : samples) {
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
            }
            if (direction == Direction.BACKWARD) {
                Collections.reverse(points);
            }
            result.add(new DeltaSeries(tag, points));
        }
        return result;
    }

    /**
     * 批量取查询起点之前的最后一个原始值作为差分基线。
     *
     * <p>单个位号带错误码或无数据时该位号基线为 {@code null}（差分首点记为缺数据）；
     * 整个请求失败时返回空表，所有位号基线均为 {@code null}，与旧实现的容错语义一致。</p>
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