/**
 * 查询编排层：七种查询族的语义、分块并行执行、结果列对齐与内存数值推导。
 *
 * <p><b>非公共 API</b>：不承诺兼容。本包只有
 * {@link io.github.forget_the_bright.tsdb.iotdb.internal.query.IotdbQueryService} 与
 * {@link io.github.forget_the_bright.tsdb.iotdb.internal.query.SeriesKeyIndex} 是 {@code public}
 * （前者是 {@code unwrap(IotdbQueryService.class)} 的逃生入口），其余类为包私有，
 * 靠同包互访协作，<b>整簇不可拆散到别的包</b>。</p>
 *
 * <h2>本包类清单与「改什么找哪个类」</h2>
 * <ul>
 *   <li>{@code IotdbQueryService} —— 统一 SQL 查询门户，只做门面与能力分派；
 *       整窗累计增量等 IoTDB 无原生对应物的语义也在这里对外承诺。</li>
 *   <li>{@code IotdbQueryCore} —— 共用执行内核：分块（{@code query-batch-size} 与
 *       {@code MAX_SQL_BYTES} 先到者生效）、语句间并行、异常包装、RAW 补采判定
 *       （{@code rawShapedBatch}）。改往返次数与块边界在这里。</li>
 *   <li>{@code IotdbCurrentQueries} —— 批量/单点 LAST；行序不保证、缺测点整行丢弃，
 *       必须按名索引回填，返回长度恒等于入参长度。</li>
 *   <li>{@code IotdbRawQueries} —— 原始点；多列 RAW 的 {@code LIMIT} 是合并行数预算，
 *       密集位号会挤掉稀疏位号，故需补采。</li>
 *   <li>{@code IotdbSampledQueries} —— 等间隔分桶与聚合历史；自然粒度起点按
 *       {@code options.time.zone} 归整，{@code FILL(PREVIOUS)} 按列独立填充。</li>
 *   <li>{@code IotdbAggregationQueries} —— 整窗增量（first/last 相减）与目录类查询；
 *       锚点缺失返回 {@code null}，<b>不补 0</b>。</li>
 *   <li>{@code IotdbInterpolationQueries} —— 固定网格线性插值；用真实原始点而非
 *       {@code FILL(LINEAR)}，因为桶内无采样点就不产生该桶，网格会错格。</li>
 *   <li>{@code IotdbDeltaQueries} —— 分桶末值 + 起点前基线，每块 2 次往返；
 *       与整窗增量是两套语义，不得互相替代。</li>
 *   <li>{@code PointMath} —— 纯内存数值学：插值网格、差分、{@code reset}/{@code missing}
 *       判定。不可运算一律 {@code null}，不编造数字。改数值口径在这里。</li>
 *   <li>{@code ResultColumns} —— 按<b>列名</b>定位结果列并读成点；列数与发射表达式数
 *       不符即抛 {@code TsdbQueryException}，绝不把别人的值挂到当前位号名下。</li>
 *   <li>{@code SeriesKeyIndex} —— 按序列路径建行索引，键由
 *       {@code IotdbTagPath.normalizeKey} 生成，<b>只做精确匹配</b>（节点名大小写敏感）。</li>
 * </ul>
 *
 * <h2>依赖方向</h2>
 * <p>本包依赖 {@code internal.sql}（语句与路径）、{@code internal.transport}
 * （{@code IotdbQueryExecutor}/{@code IotdbResultTable}）、{@code internal.IotdbModels}
 * 与 {@code common}；被 {@code internal.adapter} 调用。查询语义与通道无关：
 * 三种通道执行同一份 SQL，切换 transport 不改变业务结果。</p>
 *
 * @since 0.1.0
 */
package io.github.forget_the_bright.tsdb.iotdb.internal.query;