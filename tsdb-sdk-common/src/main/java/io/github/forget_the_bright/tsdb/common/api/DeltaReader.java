package io.github.forget_the_bright.tsdb.common.api;

import io.github.forget_the_bright.tsdb.common.model.Aggregation;
import io.github.forget_the_bright.tsdb.common.model.DeltaSeries;
import io.github.forget_the_bright.tsdb.common.model.Direction;
import io.github.forget_the_bright.tsdb.common.model.FillPolicy;
import io.github.forget_the_bright.tsdb.common.model.TimeGranularity;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * 累计量差分读取接口。
 *
 * <p>语义：对每个位号，先按 {@code granularity} 取每个区间的末值（累计值），
 * 再与上一个区间的末值相减，得到该区间的增量。第一个区间会额外取查询起点
 * 之前最近的一个末值作为基线；没有基线时该点标记为缺数据。</p>
 *
 * <p>若某区间末值小于上一区间末值，视为点位清零/重置，该区间差值取本区间末值，
 * 并置 {@code reset=true}。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public interface DeltaReader {

    /**
     * 读取累计量差分。
     *
     * @param tags         位号集合，不能为空
     * @param start        查询起点（含）
     * @param end          查询终点（不含）
     * @param granularity  时间粒度，不能为 {@code null}
     * @param aggregation  聚合方式，通常为 {@code LAST}
     * @param fill         空桶填充策略
     * @param direction    返回方向
     * @param maxPoints    每个位号最多返回的点数
     * @return 与请求位号顺序一致的差分序列
     */
    List<DeltaSeries> read(Collection<String> tags, Instant start, Instant end,
                           TimeGranularity granularity, Aggregation aggregation,
                           FillPolicy fill, Direction direction, int maxPoints);
}
