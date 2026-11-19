package io.github.forget_the_bright.tsdb.hls.internal.adapter;

import io.github.forget_the_bright.tsdb.common.api.HistoryReader;
import io.github.forget_the_bright.tsdb.common.exception.TsdbException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException;
import io.github.forget_the_bright.tsdb.common.model.Aggregation;
import io.github.forget_the_bright.tsdb.common.model.Direction;
import io.github.forget_the_bright.tsdb.common.model.FillPolicy;
import io.github.forget_the_bright.tsdb.common.model.HistoryQuery;
import io.github.forget_the_bright.tsdb.common.model.Quality;
import io.github.forget_the_bright.tsdb.common.model.QueryMode;
import io.github.forget_the_bright.tsdb.common.model.Sample;
import io.github.forget_the_bright.tsdb.common.model.TagSeries;
import io.github.forget_the_bright.tsdb.hls.internal.HlsModels;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HLS 历史查询适配器：聚合/采样/原始点三种模式，按固定步长返回宽表结果。
 *
 * <p>支持范围：模式为 {@link QueryMode#AGGREGATED}、{@link QueryMode#SAMPLED}
 * 或 {@link QueryMode#RAW}，聚合为 {@code AVG/MIN/MAX/BOUND}，方向为
 * {@link Direction#FORWARD}，填充为 {@link FillPolicy#NONE}；其余组合一律抛
 * {@link TsdbUnsupportedException}。
 * {@link Aggregation#INTERVAL_SUM} 是例外：只允许 {@code AGGREGATED + NONE}，
 * 用两侧各 1 分钟的 {@code BOUND} 探测实现整窗末−首，固定在窗口起点输出单点。</p>
 *
 * <p><b>{@code RAW} 的替代方案</b>：HLS 没有原生原始点接口，也不支持 {@code LAST}。
 * 本适配器按现场生产同款做法，改用历史值接口 {@code POST /hdb/read_hdbtagvalue}：
 * {@code interval=1}、{@code NeedQueryBound=true}，每个 1 秒桶取
 * {@code TagValue_Bound} 作为该秒的代表值。这是<b>秒级近似</b>而非协议级原始点，
 * 时间戳与数值精度受 {@code BOUND} 列和秒桶限制；结果序列额外带
 * {@code rawSource=hls_history_1s_bound} 与 {@code rawApproximate=true} 属性，
 * 调用方在数据里即可识别。{@code RAW} 只支持 {@code FORWARD}（厂商历史接口不
 * 支持倒序，不在内存回拉全窗），且忽略 {@code aggregation}/{@code interval}/{@code count}。</p>
 *
 * <p>步长单位：厂商只接受整数秒，因此按 {@code (toMillis() + 999) / 1000} 向上取整；
 * 取整后小于 1 秒（含未设置 interval）视为不支持。厂商未返回的位号用空
 * {@link TagSeries} 占位；点数超过 {@code maxPoints} 由映射层截断。</p>
 *
 * <p><b>非公共 API</b>：门户装配层实现，签名随内部重构变化，宿主应用请勿直接依赖；
 * 稳定契约请使用 {@link io.github.forget_the_bright.tsdb.hls.HlsClient}。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class HlsHistoryAdapter implements HistoryReader {

    private final HlsSupport support;

    /**
     * @param support 共享依赖
     */
    public HlsHistoryAdapter(HlsSupport support) {
        this.support = support;
    }

    /**
     * 读取区间历史（聚合、采样或原始点近似）。
     *
     * <p>{@link QueryMode#RAW} 走 {@link #rawSeries(HistoryQuery)} 的 1 秒
     * {@code BOUND} 桶近似；其余模式按聚合宽表读取。</p>
     *
     * @param query 历史查询条件，不能为 {@code null}
     * @return 非 {@code null} 的序列列表，顺序与请求位号一致
     */
    @Override
    public List<TagSeries> read(HistoryQuery query) {
        support.ensureOpen();
        if (query == null) {
            throw new IllegalArgumentException("query is required");
        }
        if (query.getMode() != QueryMode.AGGREGATED
                && query.getMode() != QueryMode.SAMPLED
                && query.getMode() != QueryMode.RAW) {
            throw support.unsupported("HLS only supports AGGREGATED, SAMPLED or RAW history",
                    "history", query.getMode().name());
        }
        if (query.getMode() == QueryMode.RAW) {
            return rawSeries(query);
        }
        if (query.getMode() == QueryMode.AGGREGATED
                && query.getAggregation() == Aggregation.INTERVAL_SUM) {
            if (query.getFill() != FillPolicy.NONE) {
                throw support.unsupported("HLS interval sum does not support fill policies",
                        "history", query.getFill().name());
            }
            return intervalSumSeries(query);
        }
        Aggregation aggregation = query.getMode() == QueryMode.SAMPLED
                ? Aggregation.BOUND : query.getAggregation();
        if (!isSupported(aggregation)) {
            throw support.unsupported("HLS does not support aggregation " + aggregation,
                    "history", aggregation == null ? null : aggregation.name());
        }
        if (query.getDirection() != Direction.FORWARD) {
            throw support.unsupported("HLS only supports FORWARD history",
                    "history", query.getDirection().name());
        }
        if (query.getFill() != FillPolicy.NONE) {
            throw support.unsupported("HLS history does not support fill policies",
                    "history", query.getFill().name());
        }
        Duration interval = query.getInterval();
        long seconds = interval == null ? 0L : (interval.toMillis() + 999L) / 1000L;
        if (seconds <= 0L) {
            throw support.unsupported("HLS aggregation interval must be at least 1 second",
                    "history", "interval");
        }
        List<HlsModels.Series> series;
        try {
            series = support.nativeApi().readHistory(query.getStart(), query.getEnd(), seconds,
                    aggregation, query.getTags());
        } catch (TsdbException e) {
            throw support.context(e, "history");
        }
        Map<String, TagSeries> byName = new LinkedHashMap<String, TagSeries>();
        for (HlsModels.Series one : series) {
            byName.put(one.tagName, support.mapper().toSeries(one, aggregation, query.getMaxPoints()));
        }
        List<TagSeries> result = new ArrayList<TagSeries>(query.getTags().size());
        for (String tagName : query.getTags()) {
            TagSeries one = byName.get(tagName);
            result.add(one == null ? TagSeries.builder(tagName).build() : one);
        }
        return result;
    }

    /**
     * 用历史值接口的 1 秒 {@code BOUND} 桶近似原始点。
     *
     * <p>HLS 没有原生原始点接口。现场生产环境同样采用「历史值接口 + 最小桶」
     * 迂回：{@code POST /hdb/read_hdbtagvalue}，{@code interval=1}、
     * {@code NeedQueryBound=true}，取 {@code TagValue_Bound} 作为每秒的代表值。
     * 因此结果<b>不是</b>协议级原始点：时间戳是 1 秒桶的样本时间，数值是
     * {@code BOUND} 列，精度受二者限制。属性 {@code rawSource=hls_history_1s_bound}
     * 与 {@code rawApproximate=true} 会把这一事实带进数据本身。</p>
     *
     * <p>限制：只支持 {@link Direction#FORWARD}（厂商历史接口不支持倒序，
     * 不在内存回拉全窗），只支持 {@link FillPolicy#NONE}；{@code aggregation}、
     * {@code interval}、{@code count} 在该模式下忽略。点数超过
     * {@code maxPoints} 由映射层截断并置 {@code truncated=true}。</p>
     *
     * @param query 历史查询条件
     * @return 每个位号一条带来源属性的序列；厂商未返回的位号为空序列占位
     */
    private List<TagSeries> rawSeries(HistoryQuery query) {
        if (query.getDirection() != Direction.FORWARD) {
            throw support.unsupported(
                    "HLS RAW history approximates raw points with 1-second BOUND buckets"
                            + " and only supports FORWARD direction",
                    "history", query.getDirection().name());
        }
        if (query.getFill() != FillPolicy.NONE) {
            throw support.unsupported("HLS RAW history does not support fill policies",
                    "history", query.getFill().name());
        }
        List<HlsModels.Series> series;
        try {
            series = support.nativeApi().readHistory(query.getStart(), query.getEnd(), 1L,
                    Aggregation.BOUND, query.getTags());
        } catch (TsdbException e) {
            throw support.context(e, "history");
        }
        Map<String, TagSeries> byName = new LinkedHashMap<String, TagSeries>();
        for (HlsModels.Series one : series) {
            byName.put(one.tagName,
                    approximateToRaw(support.mapper().toSeries(one, Aggregation.BOUND, query.getMaxPoints())));
        }
        List<TagSeries> result = new ArrayList<TagSeries>(query.getTags().size());
        for (String tagName : query.getTags()) {
            TagSeries one = byName.get(tagName);
            result.add(one == null
                    ? approximateToRaw(TagSeries.builder(tagName).build())
                    : one);
        }
        return result;
    }

    /**
     * 给序列补上「原始点来自 1 秒 {@code BOUND} 桶」的来源属性。
     *
     * @param series 映射层产出的序列
     * @return 复制了样本、截断标记与既有属性，并追加来源属性的新序列
     */
    private TagSeries approximateToRaw(TagSeries series) {
        TagSeries.Builder builder = TagSeries.builder(series.getTagName())
                .samples(series.getSamples())
                .truncated(series.isTruncated());
        for (Map.Entry<String, String> attribute : series.getAttributes().entrySet()) {
            builder.attribute(attribute.getKey(), attribute.getValue());
        }
        return builder.attribute("rawSource", "hls_history_1s_bound")
                .attribute("rawApproximate", "true")
                .build();
    }

    /**
     * 计算整窗累计增量（{@link Aggregation#INTERVAL_SUM}）：窗口末值减窗口首值。
     *
     * <p>HLS 没有“取首/尾原始值”的接口，也不扫描整个区间：只在起点后最多 1 分钟、
     * 终点前最多 1 分钟内各取一次 1 秒 {@code BOUND} 桶，取起点窗内第一个、终点窗内
     * 最后一个可解析且质量不为 {@code BAD} 的值。这样每次查询固定 2 次请求、
     * 每条最多 60 个桶。用 1 秒 {@code BOUND} 桶替代原始首/末值是现场生产同款方案，
     * 与 {@code RAW} 的做法一致；{@code BOUND} 语义随厂商版本变化，升级或换现场时
     * 按 {@code docs/10} §8.1 复核。</p>
     *
     * <p>任一侧缺值时不按 0 参与计算，直接返回空序列（兑现「空片不出点」）。
     * 单点结果无方向意义，{@code direction} 被忽略；时间戳与 GE/IoTDB 一致，
     * 取窗口起点。</p>
     *
     * @param query 历史查询条件
     * @return 每个位号一条单点序列；缺首值或末值时为空的 {@link TagSeries}
     */
    private List<TagSeries> intervalSumSeries(HistoryQuery query) {
        List<String> tags = query.getTags();
        Instant start = query.getStart().truncatedTo(ChronoUnit.SECONDS);
        Instant end = query.getEnd().truncatedTo(ChronoUnit.SECONDS);
        List<TagSeries> result = new ArrayList<TagSeries>(tags.size());
        if (!end.isAfter(start)) {
            for (String tag : tags) {
                result.add(TagSeries.builder(tag).build());
            }
            return result;
        }
        long probeMillis = Math.min(60_000L, Duration.between(start, end).toMillis());
        Map<String, Double> firstValues = edgeValues(tags, start, start.plusMillis(probeMillis), true);
        Map<String, Double> lastValues = edgeValues(tags, end.minusMillis(probeMillis), end, false);
        for (String tag : tags) {
            Double first = firstValues.get(tag);
            Double last = lastValues.get(tag);
            if (first == null || last == null) {
                result.add(TagSeries.builder(tag).build());
                continue;
            }
            double value = BigDecimal.valueOf(last)
                    .subtract(BigDecimal.valueOf(first))
                    .doubleValue();
            Sample sample = Sample.ofDouble(query.getStart(), value, Quality.GOOD, null)
                    .withTimeFormat(support.config().getTimeConfig());
            result.add(TagSeries.builder(tag).add(sample).build());
        }
        return result;
    }

    /**
     * 在窄窗口内取每个位号的首个或末个有效 {@code BOUND} 值。
     *
     * <p>HLS 没有「取窗口首/末原始值」的接口，这里用历史值接口的 1 秒
     * {@code BOUND} 桶替代，是现场生产同款迂回方案：整数秒桶保证窗口边缘
     * 至少有一个样本，且固定只需 1 次请求。</p>
     *
     * @param tags  位号集合
     * @param start 窗口起点（含，整秒）
     * @param end   窗口终点（不含，整秒）
     * @param first {@code true} 取首个值；{@code false} 取末个值
     * @return 位号 → 值；无有效值的位号不在表中
     */
    private Map<String, Double> edgeValues(List<String> tags, Instant start, Instant end, boolean first) {
        List<HlsModels.Series> series;
        try {
            series = support.nativeApi().readHistory(start, end, 1L, Aggregation.BOUND, tags);
        } catch (TsdbException e) {
            throw support.context(e, "history");
        }
        Map<String, Double> values = new LinkedHashMap<String, Double>();
        for (HlsModels.Series one : series) {
            List<Sample> samples = support.mapper()
                    .toSeries(one, Aggregation.BOUND, Integer.MAX_VALUE)
                    .getSamples();
            Double picked = null;
            if (first) {
                for (Sample sample : samples) {
                    if (usable(sample)) {
                        picked = sample.getDoubleValue();
                        break;
                    }
                }
            } else {
                for (int i = samples.size() - 1; i >= 0; i--) {
                    Sample sample = samples.get(i);
                    if (usable(sample)) {
                        picked = sample.getDoubleValue();
                        break;
                    }
                }
            }
            if (picked != null && one.tagName != null) {
                values.put(one.tagName, picked);
            }
        }
        return values;
    }

    /**
     * 判断样本是否可作为整窗端点：有数值且质量不是 {@code BAD}。
     *
     * @param sample 样本
     * @return 可用时返回 {@code true}
     */
    private boolean usable(Sample sample) {
        return sample.getDoubleValue() != null && sample.getQuality() != Quality.BAD;
    }

    /**
     * 判断聚合函数是否被 HLS 宽表接口支持。
     *
     * @param aggregation 待判断的聚合函数，可为 {@code null}
     * @return 当且仅当为 AVG/MIN/MAX/BOUND 之一时返回 {@code true}
     */
    private boolean isSupported(Aggregation aggregation) {
        return aggregation == Aggregation.AVG
                || aggregation == Aggregation.MIN
                || aggregation == Aggregation.MAX
                || aggregation == Aggregation.BOUND;
    }
}