/**
 * 位号 → 路径 → SQL 文本：IoTDB 树模型的「名字与语句」层，不发起任何查询。
 *
 * <p><b>非公共 API</b>：不承诺兼容。但 {@link io.github.forget_the_bright.tsdb.iotdb.internal.sql.TagPathMapper}
 * 是留给宿主实现的<b>扩展插口</b>（经 {@code IotdbConfig} 注入），实现它时请按本包 Javadoc 的
 * 规范路径约定返回，升级时复核该实现。</p>
 *
 * <h2>本包类清单与「改什么找哪个类」</h2>
 * <ul>
 *   <li>{@link io.github.forget_the_bright.tsdb.iotdb.internal.sql.IotdbTagPath} ——
 *       模块内<b>唯一</b>的位号路径解析与校验入口：两级数据库切分、设备层级、测点、
 *       字符白名单、逐节点反引号引用、{@code normalizeKey}。改路径合法性规则在这里。</li>
 *   <li>{@link io.github.forget_the_bright.tsdb.iotdb.internal.sql.IotdbTagResolver} ——
 *       「这个业务名字该落到哪条路径」：映射表优先、清洗兜底并计数报警、默认库。
 *       改位号解析策略在这里，不要改 {@code IotdbTagPath} 的字符校验。</li>
 *   <li>{@link io.github.forget_the_bright.tsdb.iotdb.internal.sql.TagPathMapper} ——
 *       宿主提供的显式映射表接口。现场位号与树路径不是机械变换关系，
 *       清洗改写会指向不存在的序列，而 IoTDB 对不存在的序列<b>返回空而不报错</b>。</li>
 *   <li>{@link io.github.forget_the_bright.tsdb.iotdb.internal.sql.Grouping} ——
 *       批量分组档位（DATABASE / DEVICE / …）：分组键、{@code FROM} 前缀、SELECT 表达式
 *       三种写法必须同源同档。2026-11-13 生产 2.0.11 实测：层级错配<b>静默返回空</b>，
 *       表达式带 {@code root.} 被 {@code code=701} 拒绝（见 {@code docs/14} §1 M1）。</li>
 *   <li>{@link io.github.forget_the_bright.tsdb.iotdb.internal.sql.IotdbSql} ——
 *       SQL 文本生成：LAST / RAW / GROUP BY 聚合 / 基线 / 写入等语句。
 *       只接已解析的路径对象，不做第二遍字符串校验。改 SQL 形态在这里。</li>
 * </ul>
 *
 * <h2>依赖方向</h2>
 * <p>本包被 {@code internal.query} 与 {@code internal.adapter} 依赖，自身只依赖
 * {@code common} 与根包 {@code IotdbConfig}；不依赖 {@code query}/{@code transport}，
 * 因此可以脱离服务端做纯单测（{@code IotdbSqlTest}、{@code IotdbTagPathTest} 等）。</p>
 *
 * @since 0.1.0
 */
package io.github.forget_the_bright.tsdb.iotdb.internal.sql;