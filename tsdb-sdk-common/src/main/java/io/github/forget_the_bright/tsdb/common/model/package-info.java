/**
 * 跨厂商归一化数据模型。
 *
 * <h2>为什么需要归一化</h2>
 * <p>各厂商的返回结构差异很大：HLS 历史值是“宽表”，一行同时包含 AVG/MIN/MAX/
 * Bound 多列；GE 是“长表”，每个采样点一条 {@code Samples[]}；IoTDB 返回列式
 * 的 timestamps/values。公共层把它们统一成
 * {@link io.github.forget_the_bright.tsdb.common.model.TagSeries}（一个标签一条
 * 序列）和 {@link io.github.forget_the_bright.tsdb.common.model.Sample}（一个
 * 时间点一个值），业务代码不需要理解厂商的列布局。</p>
 *
 * <h2>统一语义</h2>
 * <ul>
 *   <li>时间：{@link java.time.Instant}，UTC；区间左闭右开 {@code [start, end)}。</li>
 *   <li>质量：{@link io.github.forget_the_bright.tsdb.common.model.Quality} 只有
 *       GOOD/UNCERTAIN/BAD/STALE/UNKNOWN 五档；厂商原始质量码保留在
 *       {@code Sample.vendorQualityCode()} 和 {@code TagSeries.attributes()}。</li>
 *   <li>值：{@link io.github.forget_the_bright.tsdb.common.model.ValueType} 描述
 *       类型，{@code Sample} 提供 double/long/boolean/string/bytes 多种访问器；
 *       调用方按 {@code valueType()} 选择。</li>
 *   <li>缺失：标签不存在或没有值时返回 {@code null} 元素，不伪造 0 或 GOOD。</li>
 * </ul>
 *
 * <h2>不可变性</h2>
 * <p>模型对象尽量不可变，通过 Builder 构造；集合返回只读视图，避免驱动与业务
 * 代码共享可变状态。</p>
 *
 * <h2>分类索引（20 个类，按「我要改什么」找）</h2>
 * <p>本包<b>刻意保持平铺</b>：枚举出现在几乎所有公共签名里，拆 {@code model/enums}
 * 之类子包会改掉全部调用方的 import，收益不抵代价。改用下面这张索引导航。</p>
 * <table>
 *   <caption>公共模型分类</caption>
 *   <tr><th>分类</th><th>类</th><th>改什么找这里</th></tr>
 *   <tr>
 *     <td>枚举（8）</td>
 *     <td>{@link io.github.forget_the_bright.tsdb.common.model.Quality}、
 *         {@link io.github.forget_the_bright.tsdb.common.model.ValueType}、
 *         {@link io.github.forget_the_bright.tsdb.common.model.Aggregation}、
 *         {@link io.github.forget_the_bright.tsdb.common.model.QueryMode}、
 *         {@link io.github.forget_the_bright.tsdb.common.model.TimeGranularity}、
 *         {@link io.github.forget_the_bright.tsdb.common.model.Direction}、
 *         {@link io.github.forget_the_bright.tsdb.common.model.FillPolicy}、
 *         {@link io.github.forget_the_bright.tsdb.common.model.Capability}</td>
 *     <td>质量档位、值类型、聚合函数、历史模式、时间粒度、方向、填充策略、能力项</td>
 *   </tr>
 *   <tr>
 *     <td>查询构造（3）</td>
 *     <td>{@link io.github.forget_the_bright.tsdb.common.model.HistoryQuery}、
 *         {@link io.github.forget_the_bright.tsdb.common.model.TagQuery}、
 *         {@link io.github.forget_the_bright.tsdb.common.model.HistorianUnit}</td>
 *     <td>历史查询参数与校验、位号目录查询条件、指定间隔的窗口计算</td>
 *   </tr>
 *   <tr>
 *     <td>结果容器（8）</td>
 *     <td>{@link io.github.forget_the_bright.tsdb.common.model.Sample}、
 *         {@link io.github.forget_the_bright.tsdb.common.model.TagSample}、
 *         {@link io.github.forget_the_bright.tsdb.common.model.TagSeries}、
 *         {@link io.github.forget_the_bright.tsdb.common.model.TagInfo}、
 *         {@link io.github.forget_the_bright.tsdb.common.model.DeltaPoint}、
 *         {@link io.github.forget_the_bright.tsdb.common.model.DeltaSeries}、
 *         {@link io.github.forget_the_bright.tsdb.common.model.HealthStatus}、
 *         {@link io.github.forget_the_bright.tsdb.common.model.CapabilitySet}</td>
 *     <td>单点值与访问器、带位号的点、序列、位号元数据、差分点/差分序列
 *         （含 {@code reset}/{@code missing} 标记）、健康状态、能力集合</td>
 *   </tr>
 *   <tr>
 *     <td>写入（2）</td>
 *     <td>{@link io.github.forget_the_bright.tsdb.common.model.WriteRequest}、
 *         {@link io.github.forget_the_bright.tsdb.common.model.WriteResult}</td>
 *     <td>写入请求形状与逐位号写入结果</td>
 *   </tr>
 * </table>
 *
 * @author wanghao
 * @since 0.1.0
 */
package io.github.forget_the_bright.tsdb.common.model;
