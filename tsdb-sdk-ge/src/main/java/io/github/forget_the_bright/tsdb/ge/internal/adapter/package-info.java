/**
 * GE 驱动的门户装配层：把公共接口的每个能力接到原生 API 上。
 *
 * <p><b>非公共 API</b>：本包类是 {@code public} 仅为让根包门户 {@code ge.GeClient} 能构造它们，
 * 不代表对外承诺。签名随内部重构变化，宿主应用请勿直接依赖或自行实例化；
 * 请通过 {@code ge.GeClient} 的 {@code tagCatalog()/currentValues()/history()/deltaHistory()}
 * 取得能力对象。</p>
 *
 * <h2>本包类清单与「改什么找哪个类」</h2>
 * <ul>
 *   <li>{@code GeTagCatalogAdapter} —— 标签目录与单标签查询（{@code GET /v1/tags} 系列）。</li>
 *   <li>{@code GeCurrentValueAdapter} —— 最新值：一次请求取回全部位号，按入参顺序重建结果，
 *       厂商未返回的位号用占位样本补齐。改“缺失位号怎么表示”在这里。</li>
 *   <li>{@code GeHistoryAdapter} —— RAW / AGGREGATED / INTERPOLATED / SAMPLED 四种历史模式
 *       与区间聚合。改时间窗、方向、条数截断、聚合函数映射在这里。</li>
 *   <li>{@code GeDeltaAdapter} —— 累计量差分：{@code calculated} 分桶 + 一次批量基线，
 *       相邻相减、负差判清零。改差分口径在这里。</li>
 *   <li>{@code GeSupport} —— 四个适配器共享的配置、原生客户端、令牌与异常上下文；
 *       实例名/驱动类型/操作名只在这里统一补齐，适配器不直接持有门户。</li>
 * </ul>
 *
 * <h2>依赖方向</h2>
 * <p>本包依赖 {@code ge.internal}（原生 API/模型）、根包 {@code GeConfig}/{@code GeMapper}
 * 与 {@code common}；不依赖其他驱动，也不被 {@code ge.internal} 反向依赖。</p>
 *
 * @since 0.1.0
 */
package io.github.forget_the_bright.tsdb.ge.internal.adapter;