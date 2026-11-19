package io.github.forget_the_bright.tsdb.ge.internal.adapter;

import io.github.forget_the_bright.tsdb.common.api.HistoryReader;
import io.github.forget_the_bright.tsdb.common.exception.TsdbQueryException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException;
import io.github.forget_the_bright.tsdb.common.model.Aggregation;
import io.github.forget_the_bright.tsdb.common.model.FillPolicy;
import io.github.forget_the_bright.tsdb.common.model.HistoryQuery;
import io.github.forget_the_bright.tsdb.common.model.HistorianUnit;
import io.github.forget_the_bright.tsdb.common.model.Quality;
import io.github.forget_the_bright.tsdb.common.model.QueryMode;
import io.github.forget_the_bright.tsdb.common.model.Sample;
import io.github.forget_the_bright.tsdb.common.model.TagSeries;
import io.github.forget_the_bright.tsdb.ge.internal.GeModels;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.github.forget_the_bright.tsdb.ge.GeMapper;

/**
 * GE 历史查询适配器：按 {@link QueryMode} 分派到原始/聚合/插值/采样端点。
 *
 * <p>模式语义与旧 SDK 线缆路径一一对齐；{@link Aggregation#INTERVAL_SUM} 不走
 * 单端点映射，而是调用 {@code readIntervalSum} 的 17/19 双请求末减首，每个位号
 * 产出单点序列。结果按请求标签顺序输出，厂商未返回的标签给空序列；某标签带
 * 错误码时，未允许部分结果则整体抛 {@link TsdbQueryException}，否则降级为只带
 * {@code vendorErrorCode} 属性的空序列。</p>
 *
 * <p><b>非公共 API</b>：门户装配层实现，签名随内部重构变化，宿主应用请勿直接依赖；
 * 稳定契约请使用 {@link io.github.forget_the_bright.tsdb.ge.GeClient}。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class GeHistoryAdapter implements HistoryReader {

    private final GeSupport support;

    /**
     * @param support 共享配置与原生客户端
     */
    public GeHistoryAdapter(GeSupport support) {
        this.support = support;
    }

    /**
     * 读取历史数据。
     *
     * @param query 历史查询条件，不能为 {@code null}
     * @return 与请求标签顺序一致的序列列表，非 {@code null}
     */
    @Override
    public List<TagSeries> read(HistoryQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        if (query.getFill() != null && query.getFill() != FillPolicy.NONE) {
            throw support.unsupported(new TsdbUnsupportedException("GE history does not support fill policy "
                    + query.getFill()), "readHistory");
        }
        QueryMode mode = query.getMode();
        if (mode == QueryMode.AGGREGATED && query.getAggregation() == Aggregation.INTERVAL_SUM) {
            return intervalSumSeries(query);
        }
        Integer count = query.getCount();
        Long intervalMillis = query.getInterval() == null ? null : query.getInterval().toMillis();
        int serverCount = count != null ? count : query.getMaxPoints();
        long serverInterval = intervalMillis != null ? intervalMillis : 1L;
        if (intervalMillis != null && mode != QueryMode.RAW) {
            // 区间已知时点数可从时间窗精确推导：调用方不必再填 count，
            // 一旦填写就必须与推导值一致，否则这里直接抛错而不是静默采用。
            serverCount = HistorianUnit.resolveCount(count, query.getStart(), query.getEnd(), intervalMillis);
        }
        List<GeModels.Series> raw;
        if (mode == QueryMode.RAW) {
            raw = support.nativeApi().readRaw(query.getTags(), query.getStart(), query.getEnd(),
                    query.getDirection(), serverCount);
        } else if (mode == QueryMode.AGGREGATED) {
            int calculationMode = calculationMode(query.getAggregation());
            raw = support.nativeApi().readAggregated(query.getTags(), query.getStart(), query.getEnd(),
                    calculationMode, serverCount, serverInterval);
        } else if (mode == QueryMode.SAMPLED) {
            raw = support.nativeApi().readSampled(query.getTags(), query.getStart(), query.getEnd(),
                    2, 1L, intervalMillis, intervalMillis == null ? count : Integer.valueOf(serverCount),
                    null, query.getDirection());
        } else if (mode == QueryMode.INTERPOLATED) {
            raw = support.nativeApi().readInterpolated(query.getTags(), query.getStart(), query.getEnd(),
                    serverCount, serverInterval);
        } else {
            throw support.unsupported(new TsdbUnsupportedException(
                    "GE driver does not support history mode " + mode), "readHistory");
        }

        Map<String, GeModels.Series> byName = new LinkedHashMap<String, GeModels.Series>();
        for (GeModels.Series item : raw) {
            if (item.getTagName() != null) {
                byName.put(item.getTagName(), item);
            }
        }
        List<TagSeries> result = new ArrayList<TagSeries>(query.getTags().size());
        for (String tag : query.getTags()) {
            GeModels.Series item = byName.get(tag);
            if (item == null) {
                result.add(TagSeries.builder(tag).build());
                continue;
            }
            if (item.getErrorCode() != null) {
                if (!query.isPartialResultAllowed()) {
                    throw support.query(new TsdbQueryException("GE query failed for tag " + tag
                            + ": ErrorCode=" + item.getErrorCode()
                            + (item.getErrorMessage() == null ? "" : " " + item.getErrorMessage())),
                            "readHistory");
                }
                result.add(TagSeries.builder(tag)
                        .attribute("vendorErrorCode", String.valueOf(item.getErrorCode()))
                        .build());
                continue;
            }
            result.add(limit(GeMapper.toSeries(item, tag, support.config().getTimeConfig()),
                    query.getMaxPoints()));
        }
        return result;
    }

    /**
     * 以旧 SDK {@code getIntervalValueBySumTag} 算法产出 INTERVAL_SUM 单点序列。
     *
     * @param query 历史查询条件，聚合必为 {@link Aggregation#INTERVAL_SUM}
     * @return 与请求标签顺序一致的单点序列列表
     */
    private List<TagSeries> intervalSumSeries(HistoryQuery query) {
        Map<String, String> diffs = support.nativeApi().readIntervalSum(query.getTags(),
                query.getStart(), query.getEnd());
        List<TagSeries> result = new ArrayList<TagSeries>(query.getTags().size());
        for (String tag : query.getTags()) {
            BigDecimal value = new BigDecimal(diffs.get(tag));
            Sample sample = Sample.ofDouble(query.getStart(), value.doubleValue(), Quality.GOOD, null)
                    .withTimeFormat(support.config().getTimeConfig());
            result.add(TagSeries.builder(tag).add(sample).build());
        }
        return result;
    }

    /**
     * 按最大点数截断序列。
     *
     * <p>仅在样本数严格大于 {@code maxPoints} 时重建序列，并把
     * {@code truncated} 置为 {@code true}。</p>
     *
     * @param series    原始序列
     * @param maxPoints 允许的最大样本数
     * @return 未超限时原样返回入参，否则返回新的截断序列
     */
    private static TagSeries limit(TagSeries series, int maxPoints) {
        if (series.getSamples().size() <= maxPoints) {
            return series;
        }
        TagSeries.Builder builder = TagSeries.builder(series.getTagName()).truncated(true);
        for (Map.Entry<String, String> attribute : series.getAttributes().entrySet()) {
            builder.attribute(attribute.getKey(), attribute.getValue());
        }
        for (int i = 0; i < maxPoints; i++) {
            builder.add(series.getSamples().get(i));
        }
        return builder.build();
    }

    /**
     * 把公共层的聚合类型映射为 GE 的计算模式编号。
     *
     * <p>{@code AVG=1}、{@code MIN=4}、{@code MAX=5}、{@code COUNT=6}、
     * {@code SUM=9}、{@code FIRST=17}、{@code LAST=19}。这些编号是 Historian 的
     * {@code CalculationMode} 常量，不是枚举序数，不可自行推导。{@code SUM(9=RawTotal)}
     * 是片内算术和，{@code INTERVAL_SUM} 是片末减片首，两者不得互换。</p>
     *
     * @param aggregation 聚合类型
     * @return GE 计算模式编号
     */
    private static int calculationMode(Aggregation aggregation) {
        if (aggregation == null) {
            throw new TsdbUnsupportedException("GE aggregated query requires an aggregation");
        }
        switch (aggregation) {
            case AVG:
                return 1;
            case MIN:
                return 4;
            case MAX:
                return 5;
            case COUNT:
                return 6;
            case SUM:
                return 9;
            case FIRST:
                return 17;
            case LAST:
                return 19;
            case INTERVAL_SUM:
                throw new TsdbUnsupportedException("GE INTERVAL_SUM is served by readIntervalSum, "
                        + "not a single calculationMode");
            default:
                throw new TsdbUnsupportedException("GE does not support aggregation " + aggregation);
        }
    }
}