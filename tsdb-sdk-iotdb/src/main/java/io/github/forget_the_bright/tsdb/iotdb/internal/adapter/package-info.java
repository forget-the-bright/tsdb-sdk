/**
 * IoTDB 驱动的门户装配层：把公共接口的每个能力接到查询服务与写入通道上。
 *
 * <p><b>非公共 API</b>：本包类是 {@code public} 仅为让根包门户 {@code iotdb.IotdbClient}
 * 能构造它们，不代表对外承诺。签名随内部重构变化，宿主应用请勿直接依赖或自行实例化。</p>
 *
 * <h2>本包类清单与「改什么找哪个类」</h2>
 * <ul>
 *   <li>{@code IotdbTagCatalogAdapter} —— 位号目录浏览（数据库/设备/测点树）。</li>
 *   <li>{@code IotdbCurrentValueAdapter} —— 最新值，含批量最新值。</li>
 *   <li>{@code IotdbHistoryAdapter} —— RAW / SAMPLED / AGGREGATED / INTERPOLATED /
 *       INTERVAL_SUM 等历史模式的入口分派。</li>
 *   <li>{@code IotdbDeltaAdapter} —— 累计量差分；块级化与失败归因在这里落地。</li>
 *   <li>{@code IotdbWriteAdapter} —— 写入会话。默认只读，未开启
 *       {@code tsdb.security.allow-write=true} 时 {@code write} 直接抛
 *       {@code TsdbUnsupportedException}，不静默丢弃。</li>
 *   <li>{@code IotdbSeriesAssembler} —— 把结果表组装成公共 {@code Series}/{@code Point}
 *       序列；改序列形状、时间戳与质量码落点在这里。</li>
 *   <li>{@code IotdbSupport} —— 六个类共享的配置、查询服务、执行器、映射器、开闭状态与异常上下文。</li>
 * </ul>
 *
 * <p>本包不含查询算法：语义与 SQL 编排都在 {@code iotdb.internal.query} 与
 * {@code iotdb.internal.sql}，本包只做「接口方法 → 内部调用 → 公共模型」的转接。</p>
 *
 * <h2>依赖方向</h2>
 * <p>本包依赖 {@code internal.query}、{@code internal.sql}、{@code internal.transport}、
 * {@code internal.IotdbModels} 与根包 {@code IotdbConfig}/{@code IotdbMapper}；不依赖其他驱动。</p>
 *
 * @since 0.1.0
 */
package io.github.forget_the_bright.tsdb.iotdb.internal.adapter;