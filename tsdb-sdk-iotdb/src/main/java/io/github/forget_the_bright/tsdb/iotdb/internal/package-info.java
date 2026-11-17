/**
 * IoTDB 驱动的实现层根包：只放跨子包共享的厂商 DTO，其余实现按职责分到子包。
 *
 * <p><b>非公共 API</b>：本包及其所有子包都不承诺兼容，签名与包路径随内部重构变化。
 * 宿主应用请使用 {@code iotdb.IotdbClient} 与 {@code common.api}；确需原生能力时经
 * {@code IotdbClient.unwrap(...)} 取用，并在升级时复核该处代码。</p>
 *
 * <h2>本包类清单</h2>
 * <ul>
 *   <li>{@link io.github.forget_the_bright.tsdb.iotdb.internal.IotdbModels} ——
 *       REST v2 响应 DTO 与公共模型之间的换算（时间戳、质量码、值类型、列名）。
 *       它被 {@code sql}/{@code query}/{@code transport}/{@code adapter} 四个子包共用，
 *       所以留在本层根包，避免任一子包反向依赖别的子包。</li>
 * </ul>
 *
 * <h2>按职责找子包（「我要改什么」）</h2>
 * <ul>
 *   <li>改 SQL 文本、位号解析、路径映射、按库分组 ——
 *       {@link io.github.forget_the_bright.tsdb.iotdb.internal.sql}；</li>
 *   <li>改查询编排、七种查询族的语义与算法、结果列与位号索引 ——
 *       {@link io.github.forget_the_bright.tsdb.iotdb.internal.query}；</li>
 *   <li>改通道选择与执行（NATIVE / JDBC / REST）、HTTP 细节、结果表 ——
 *       {@link io.github.forget_the_bright.tsdb.iotdb.internal.transport}；</li>
 *   <li>改公共接口到内部实现的装配、序列组装、开闭与异常上下文 ——
 *       {@link io.github.forget_the_bright.tsdb.iotdb.internal.adapter}。</li>
 * </ul>
 *
 * <h2>依赖方向</h2>
 * <p>{@code adapter} → {@code query} → {@code sql} / {@code transport}，四者都可依赖本包的
 * {@code IotdbModels} 与根包 {@code IotdbConfig}/{@code IotdbMapper}；子包之间不互相回指。
 * 根包门户 {@code IotdbClient} 会构造 {@code adapter} 的类，{@code adapter} 又读根包配置，
 * 这是门面模式的固有双向引用，模块级依赖仍无环。</p>
 *
 * @since 0.1.0
 */
package io.github.forget_the_bright.tsdb.iotdb.internal;