package io.github.forget_the_bright.tsdb.common.api;

import io.github.forget_the_bright.tsdb.common.model.HistoryQuery;
import io.github.forget_the_bright.tsdb.common.model.TagSeries;

import java.util.List;

/**
 * 历史数据读取操作。
 *
 * <p>在 SDK 中承担“按时间区间取数”的角色：接收一个已经过校验的
 * {@link HistoryQuery}，返回每个位号的时间序列。原始读取、区间聚合、插值、
 * 等间隔采样、趋势查询都走这一个入口，由查询对象的
 * {@link HistoryQuery#getMode()} 区分。</p>
 *
 * <p>能力要求：实现必须根据模式检查客户端声明的能力
 * （{@code RAW_HISTORY}、{@code AGGREGATED_HISTORY}、
 * {@code INTERPOLATED_HISTORY}、{@code SAMPLED_HISTORY}、{@code TREND}），
 * 不支持时必须抛 {@code TSDB-UNSUPPORTED}，不能静默降级为原始查询。</p>
 *
 * <p>实现约束：</p>
 * <ul>
 *   <li>不得返回 {@code null}，无数据时返回空列表或元素内采样点为空列表；</li>
 *   <li>返回列表顺序应与 {@link HistoryQuery#getTags()} 的顺序一致；</li>
 *   <li>结果点数受 {@link HistoryQuery#getMaxPoints()} 约束，被截断时
 *       应在 {@link TagSeries#isTruncated()} 上标记；</li>
 *   <li>实现必须线程安全，可并发调用。</li>
 * </ul>
 *
 * @author wanghao
 * @since 0.1.0
 */
public interface HistoryReader {

    /**
     * 按查询条件读取全部位号的历史数据。
     *
     * <p>查询的时间区间是左闭右开的 {@code [start, end)}；返回的每个
     * {@link TagSeries} 内采样点顺序由
     * {@link HistoryQuery#getDirection()} 决定。若
     * {@link HistoryQuery#isPartialResultAllowed()} 为 {@code false}
     * 而结果被截断或部分位号失败，实现应当抛
     * {@link io.github.forget_the_bright.tsdb.common.exception.TsdbQueryException}
     * 而不是返回残缺数据。</p>
     *
     * @param query 历史查询条件，不能为 {@code null}；
     *              其内部参数已由 {@code HistoryQuery.Builder#build()}
     *              完成合法性校验
     * @return 非 {@code null} 的序列列表，顺序与 {@code query.getTags()} 一致；
     *         区间内无数据时对应元素的采样点列表为空
     * @throws IllegalArgumentException 当 {@code query} 为 {@code null} 时
     *                                  （由实现决定是否显式校验）
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException
     *         当驱动不支持所请求的查询模式时
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbQueryException
     *         当查询语法错误、服务端拒绝、或不允许返回部分结果却只拿到部分数据时
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbTimeoutException
     *         当查询超时时
     */
    List<TagSeries> read(HistoryQuery query);
}
