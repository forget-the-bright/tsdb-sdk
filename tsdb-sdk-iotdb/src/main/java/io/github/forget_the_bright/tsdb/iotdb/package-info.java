/**
 * Apache IoTDB 驱动（树模型）：公共入口 + 三种可切换的 SQL 执行通道。
 *
 * <h2>本包类清单（宿主应用只需要这几个）</h2>
 * <ul>
 *   <li>{@link io.github.forget_the_bright.tsdb.iotdb.IotdbClient} —— 门户，实现
 *       {@code common.api} 的各位能力接口；原生能力经 {@code unwrap(...)} 取用。</li>
 *   <li>{@link io.github.forget_the_bright.tsdb.iotdb.IotdbConfig} —— 校验后不可变的配置快照。</li>
 *   <li>{@link io.github.forget_the_bright.tsdb.iotdb.IotdbDriver} —— SPI 驱动入口，由
 *       {@code TsdbClients} 按 {@code type=iotdb} 装配。</li>
 *   <li>{@link io.github.forget_the_bright.tsdb.iotdb.IotdbMapper} —— 位号/质量码/值类型映射，
 *       可由宿主替换。</li>
 *   <li>{@link io.github.forget_the_bright.tsdb.iotdb.IotdbTransport} —— 通道枚举。</li>
 * </ul>
 *
 * <h2>三种通道（{@code transport} 选项，默认 {@code REST}）</h2>
 * <p>三种通道执行<b>同一份 SQL</b>、返回同一种结果表，切换通道不改变业务语义
 * （见 {@code docs/11}）。查询语义只实现一次，在 {@code internal.query}。</p>
 * <ul>
 *   <li>{@code NATIVE} —— {@code iotdb-session} 的 SessionPool，自带连接复用与故障转移，
 *       吞吐最高；需要 {@code host}/{@code port}（RPC 6667）。</li>
 *   <li>{@code JDBC} —— {@code iotdb-jdbc}，{@code jdbc:iotdb://host:port/}，每次查询独立建连；
 *       同样需要 {@code host}/{@code port}。</li>
 *   <li>{@code REST} —— REST v2（{@code /rest/v2}）+ HTTP Basic，走公共层 Hutool 传输与
 *       fastjson 解析；部署简单、便于 Mock Server 契约测试，但有 JSON/HTTP 开销，
 *       适合集成与中低吞吐场景。默认档位，与改造前行为兼容。</li>
 * </ul>
 * <p>选 {@code NATIVE}/{@code JDBC} 而未给 {@code host} 时，构造期即抛
 * {@code TsdbConfigurationException}，不静默退回 REST。</p>
 *
 * <h2>启用 REST 通道</h2>
 * <p>IoTDB 默认关闭 REST 服务，需要在 DataNode 的 {@code conf/iotdb-system.properties} 设置：</p>
 * <pre>
 * enable_rest_service=true
 * rest_service_port=18080
 * </pre>
 *
 * <h2>REST 协议要点</h2>
 * <ul>
 *   <li>树模型前缀 {@code /rest/v2}；认证为 HTTP Basic。</li>
 *   <li>查询 {@code POST /query}，请求 {@code {"sql":"..."}}；响应是列式的
 *       {@code expressions/timestamps/values}，元数据语句用 {@code column_names}。</li>
 *   <li>写入 {@code POST /insertTablet}，按设备组织 timestamps/measurements/
 *       data_types/values。</li>
 *   <li>服务端成功码是 {@code code=200}；HTTP 600/603 也表示认证错误，必须同时
 *       检查 HTTP 状态和响应体。</li>
 * </ul>
 *
 * <h2>SQL 安全</h2>
 * <p>SQL 通道只接受 SQL 文本，位号路径会被拼进语句。路径解析与字符白名单集中在
 * {@link io.github.forget_the_bright.tsdb.iotdb.internal.sql.IotdbTagPath}，语句生成在
 * {@link io.github.forget_the_bright.tsdb.iotdb.internal.sql.IotdbSql}：逐节点反引号引用，
 * 拒绝引号、分号、空白等元字符。这不是格式检查，而是 SQL 注入防线。</p>
 *
 * <h2>质量码</h2>
 * <p>IoTDB 不返回质量码；驱动把非空值标为 {@code GOOD}、空值标为
 * {@code UNKNOWN}，不伪造其他状态。</p>
 *
 * <h2>实现层导航（非公共 API）</h2>
 * <p>改实现请按职责进子包，不要从根包翻找：</p>
 * <ul>
 *   <li>{@link io.github.forget_the_bright.tsdb.iotdb.internal.sql} —— 位号解析、路径映射、SQL 文本；</li>
 *   <li>{@link io.github.forget_the_bright.tsdb.iotdb.internal.query} —— 查询族语义、分块并行、列对齐、数值推导；</li>
 *   <li>{@link io.github.forget_the_bright.tsdb.iotdb.internal.transport} —— 三通道执行器、REST 客户端、结果表；</li>
 *   <li>{@link io.github.forget_the_bright.tsdb.iotdb.internal.adapter} —— 门户装配层（适配器 + 支撑 + 序列组装）；</li>
 *   <li>{@link io.github.forget_the_bright.tsdb.iotdb.internal} —— 跨子包共享的厂商 DTO 换算。</li>
 * </ul>
 *
 * @since 0.1.0
 */
package io.github.forget_the_bright.tsdb.iotdb;