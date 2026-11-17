package io.github.forget_the_bright.tsdb.hls.internal.adapter;

import io.github.forget_the_bright.tsdb.common.api.HistoryReader;
import io.github.forget_the_bright.tsdb.common.exception.TsdbException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException;
import io.github.forget_the_bright.tsdb.common.model.Aggregation;
import io.github.forget_the_bright.tsdb.common.model.Direction;
import io.github.forget_the_bright.tsdb.common.model.FillPolicy;
import io.github.forget_the_bright.tsdb.common.model.HistoryQuery;
import io.github.forget_the_bright.tsdb.common.model.QueryMode;
import io.github.forget_the_bright.tsdb.common.model.TagSeries;
import io.github.forget_the_bright.tsdb.hls.internal.HlsModels;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HLS 历史查询适配器：只支持聚合/采样模式，按固定步长返回宽表结果。
 *
 * <p>支持范围：模式为 {@link QueryMode#AGGREGATED} 或 {@link QueryMode#SAMPLED}，
 * 聚合为 {@code AVG/MIN/MAX/BOUND}，方向为 {@link Direction#FORWARD}，填充为
 * {@link FillPolicy#NONE}；其余组合一律抛 {@link TsdbUnsupportedException}。</p>
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
     * 读取区间聚合历史。
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
        if (query.getMode() != QueryMode.AGGREGATED && query.getMode() != QueryMode.SAMPLED) {
            throw support.unsupported("HLS only supports AGGREGATED or SAMPLED history",
                    "history", query.getMode().name());
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