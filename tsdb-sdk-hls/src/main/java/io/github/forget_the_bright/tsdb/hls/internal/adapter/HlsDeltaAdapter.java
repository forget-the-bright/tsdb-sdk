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
 * 每个桶的末值。查询范围向前扩一个桶，把「起点前最后一个桶」一并取回，稠密网格时
 * 第 0 个样本就是基线，与后续桶相邻相减即可，<b>整个查询只要 1 次请求</b>；
 * 只有样本数对不上或首值为空时，才为缺桶位号补一条批量基线请求（常数次）。
 * 适用于累计量点位，普通瞬时量点位使用会得到无业务意义的差值。</p>
 *
 * <p>支持两种入口：{@link #read} 走自然粒度（1 秒起，月不支持）；
 * {@link #readByInterval} 走任意毫秒间隔，但厂商只接受整数秒，因此间隔必须
 * 大于等于 1000 且能被 1000 整除，否则抛不支持，不做取整。</p>
 *
 * <p><b>为什么这样处理</b>：HLS 没有 {@code LAST}，也没有取单点原始值的接口。
 * {@code BOUND} 是厂商宽表自带的桶边界列，现场生产环境同样用它做累计量差分；
 * 基线就是「起点前一个桶」的 {@code BOUND}，扩窗取回而不是回拉全窗，也不逐位号
 * 单独请求。{@code BOUND} 语义随厂商版本可能变化，升级或换现场时按
 * {@code docs/10} §8.1 的复核步骤确认，但不构成使用前置阻塞。</p>
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
     * @param start       查询起点；按自然粒度归整
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
        if (granularity == null || granularity == TimeGranularity.MONTH) {
            throw support.unsupported("HLS cumulative delta requires a fixed granularity",
                    "deltaHistory", granularity == null ? null : granularity.name());
        }
        validate(tagNames, aggregation, fill);
        requireRange(start, end);
        long seconds = Math.max(1L, granularity.getMillis() / 1000L);
        ZoneId zone = support.config().getTimeConfig().getResponseTargetZone();
        Instant normalized = granularity.normalizeStart(start, zone);
        return readSeconds(tagNames, normalized, end, seconds, fill, direction, maxPoints);
    }

    /**
     * 按任意毫秒间隔读取累计量差分。
     *
     * <p>分桶网格锚定在传入的 {@code start}，不按自然粒度归整；基线取
     * {@code start} 之前一个间隔的 {@code BOUND}。厂商只支持整数秒间隔，
     * 子秒或非整秒间隔抛 {@link io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException}。</p>
     *
     * @param tagNames       位号集合
     * @param start          查询起点（含），同时是分桶网格锚点
     * @param end            查询终点（不含）
     * @param intervalMillis 间隔毫秒数，必须为 1000 的正整数倍
     * @param aggregation    必须为 {@code null} 或 {@link Aggregation#LAST}
     * @param fill           仅支持 {@link FillPolicy#NONE} 或 {@link FillPolicy#PREVIOUS}
     * @param direction      排序方向
     * @param maxPoints      最大点数
     * @return 每个位号一条差分序列
     */
    @Override
    public List<DeltaSeries> readByInterval(Collection<String> tagNames, Instant start, Instant end,
                                            long intervalMillis, Aggregation aggregation, FillPolicy fill,
                                            Direction direction, int maxPoints) {
        support.ensureOpen();
        validate(tagNames, aggregation, fill);
        requireRange(start, end);
        if (intervalMillis < 1000L || intervalMillis % 1000L != 0L) {
            throw support.unsupported("HLS delta interval must be a whole number of seconds",
                    "deltaHistory", String.valueOf(intervalMillis));
        }
        return readSeconds(tagNames, start, end, intervalMillis / 1000L, fill, direction, maxPoints);
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
            throw support.unsupported("HLS cumulative delta requires LAST aggregation",
                    "deltaHistory", aggregation.name());
        }
        if (fill != null && fill != FillPolicy.NONE && fill != FillPolicy.PREVIOUS) {
            throw support.unsupported("HLS cumulative delta supports NONE or PREVIOUS fill",
                    "deltaHistory", fill.name());
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
     * 按整秒桶读取并计算差分。
     *
     * <p>查询范围向前扩一个桶，把「起点前最后一个桶」一并取回：稠密网格时第 0 个
     * 样本就是基线，直接从下标 1 开始相邻相减，<b>一次请求</b>即可；只有样本数
     * 对不上或首值为空时，才为缺桶位号补一条批量基线请求。厂商只接受整数秒，
     * 扩窗不改变桶边界。</p>
     *
     * @param tagNames 位号集合
     * @param start    分桶起点（含）
     * @param end      查询终点（不含）
     * @param seconds  桶长度（秒），至少为 1
     * @param fill     填充策略
     * @param direction 返回方向
     * @param maxPoints 最大点数
     * @return 每个位号一条差分序列
     */
    private List<DeltaSeries> readSeconds(Collection<String> tagNames, Instant start, Instant end,
                                          long seconds, FillPolicy fill, Direction direction, int maxPoints) {
        int limit = maxPoints <= 0 ? 10000 : maxPoints;
        long windowBuckets = Math.max(0L,
                (end.toEpochMilli() - start.toEpochMilli()) / (seconds * 1000L));
        int sampleLimit = (int) Math.min(Math.min(windowBuckets, (long) limit) + 1L,
                (long) Integer.MAX_VALUE);
        Instant extendedStart = start.minusSeconds(seconds);
        List<HlsModels.Series> sampled;
        try {
            sampled = support.nativeApi().readHistory(extendedStart, end, seconds,
                    Aggregation.BOUND, tagNames);
        } catch (TsdbException e) {
            throw support.context(e, "deltaHistory");
        }
        Map<String, HlsModels.Series> byName = new LinkedHashMap<String, HlsModels.Series>();
        for (HlsModels.Series one : sampled) {
            if (one.tagName != null) {
                byName.put(one.tagName, one);
            }
        }

        Map<String, Double> baselines = new LinkedHashMap<String, Double>();
        Map<String, List<Sample>> windows = new LinkedHashMap<String, List<Sample>>();
        List<String> fallback = new ArrayList<String>();
        for (String tag : tagNames) {
            HlsModels.Series one = byName.get(tag);
            List<Sample> samples = one == null
                    ? Collections.<Sample>emptyList()
                    : support.mapper().toSeries(one, Aggregation.BOUND, sampleLimit).getSamples();
            int from = 0;
            Double baseline = null;
            // 样本数正好等于「基线桶 + 窗口桶」时第 0 个样本就是扩展桶；
            // 数量对不上或首值为空，就不猜桶标签口径，改成一条批量基线兜底。
            if (samples.size() == sampleLimit && samples.get(0).getDoubleValue() != null) {
                baseline = samples.get(0).getDoubleValue();
                from = 1;
            } else {
                while (from < samples.size() && !samples.get(from).getTimestamp().isAfter(start)) {
                    from++;
                }
                fallback.add(tag);
            }
            baselines.put(tag, baseline);
            int to = (int) Math.min((long) samples.size(), (long) from + limit);
            windows.put(tag, from >= to
                    ? Collections.<Sample>emptyList() : samples.subList(from, to));
        }
        if (!fallback.isEmpty()) {
            Map<String, Double> extra = baselines(fallback, start, seconds);
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
            if (direction == Direction.BACKWARD) {
                Collections.reverse(points);
            }
            result.add(new DeltaSeries(tag, points));
        }
        return result;
    }

    /**
     * 批量取查询起点前一个桶的边界值作为基线，只用于扩展桶缺失的位号。
     *
     * <p>HLS 没有取单点原始值的接口；基线仍是「起点前一个桶的 {@code BOUND}」，
     * 与主查询同一列、同一语义。这里把缺桶位号合成一次请求，保持 O(1)；
     * 整个请求失败时返回空表，相关位号首点按缺数据呈现（与逐位号旧实现一致）。</p>
     *
     * @param tags    需要兜底的位号集合
     * @param start   查询起点
     * @param seconds 桶长度（秒）
     * @return 位号 → 基线值；无数据或失败的位号不在表中
     */
    private Map<String, Double> baselines(Collection<String> tags, Instant start, long seconds) {
        Map<String, Double> result = new LinkedHashMap<String, Double>();
        try {
            List<HlsModels.Series> series = support.nativeApi().readHistory(
                    start.minusSeconds(seconds), start, seconds,
                    Aggregation.BOUND, tags);
            for (HlsModels.Series one : series) {
                if (one.tagName == null) {
                    continue;
                }
                List<Sample> samples = support.mapper()
                        .toSeries(one, Aggregation.BOUND, 10).getSamples();
                for (int i = samples.size() - 1; i >= 0; i--) {
                    Double value = samples.get(i).getDoubleValue();
                    if (value != null) {
                        result.put(one.tagName, value);
                        break;
                    }
                }
            }
        } catch (RuntimeException e) {
            return Collections.emptyMap();
        }
        return result;
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