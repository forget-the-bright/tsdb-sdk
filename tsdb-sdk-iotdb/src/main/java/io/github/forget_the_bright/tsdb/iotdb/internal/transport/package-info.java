/**
 * 通道层：三种 SQL 执行通道（NATIVE / JDBC / REST）与 REST v2 原生客户端、统一结果表。
 *
 * <p><b>非公共 API</b>：不承诺兼容。{@code IotdbRestApi} 是
 * {@code unwrap(IotdbRestApi.class)} 的逃生入口，保留厂商原始 JSON 与全部端点能力；
 * 其余类只服务于本驱动内部。</p>
 *
 * <h2>本包类清单与「改什么找哪个类」</h2>
 * <ul>
 *   <li>{@link io.github.forget_the_bright.tsdb.iotdb.internal.transport.IotdbQueryExecutor} ——
 *       通道抽象：收一条完整 SQL，返回统一 {@code IotdbResultTable}。
 *       新增通道只实现本接口，不在通道里写查询语义。</li>
 *   <li>{@code IotdbNativeQueryExecutor} —— SessionPool 通道，吞吐最高；
 *       列名以 {@code Time} 开头但值列表不含时间列，按列名判断偏移。</li>
 *   <li>{@code IotdbJdbcQueryExecutor} —— {@code jdbc:iotdb://host:port/}，每次查询独立建连。</li>
 *   <li>{@code IotdbRestQueryExecutor} —— 把 {@code /rest/v2/query} 的列式 JSON 转成结果表。</li>
 *   <li>{@code IotdbRestApi} —— REST v2 原生客户端：{@code /rest/v2/query}、
 *       {@code /rest/v2/nonQuery}、{@code /rest/v2/insertTablet}、{@code /ping}；
 *       JSON 承载为 fastjson（{@code JSONObject}），只做树解析，不开 AutoType。</li>
 *   <li>{@code IotdbHttpClient} —— REST 的 HTTP 细节：Basic 认证头、状态码到公共异常的映射；
 *       超时/重定向/UTF-8/错误流由 {@code common.internal.http.HttpTransport}（Hutool）统一实现。</li>
 *   <li>{@code IotdbResultTable} —— 三通道共用结果表：列序与 select 列表一致，
 *       时间列由 {@code Row.getTimestamp()} 单独承载，屏蔽三种通道的字段布局差异。</li>
 * </ul>
 *
 * <h2>依赖方向</h2>
 * <p>本包依赖 {@code common.internal.http}、{@code common.internal.json}、
 * {@code internal.IotdbModels} 与根包 {@code IotdbConfig}/{@code IotdbTransport}；
 * 被 {@code internal.query} 依赖。<b>本包不反向依赖 {@code query} 或 {@code sql}</b>，
 * 因此通道可以独立替换而不触碰查询语义。</p>
 *
 * @since 0.1.0
 */
package io.github.forget_the_bright.tsdb.iotdb.internal.transport;