/**
 * pSpace 的公共 API 适配器（目录、当前值、历史、差分、写入）。
 *
 * <p>六个类的分工：</p>
 * <table border="1">
 *   <caption>adapter 包</caption>
 *   <tr><th>类</th><th>实现的公共接口 / 职责</th></tr>
 *   <tr><td>{@link io.github.forget_the_bright.tsdb.pspace.internal.adapter.PspaceSupport}</td>
 *       <td>不实现接口；共享依赖、关闭检查、分页判定、异常上下文</td></tr>
 *   <tr><td>{@link io.github.forget_the_bright.tsdb.pspace.internal.adapter.PspaceTagCatalogAdapter}</td>
 *       <td>{@code TagCatalog}：分页目录与名称检索</td></tr>
 *   <tr><td>{@link io.github.forget_the_bright.tsdb.pspace.internal.adapter.PspaceCurrentValueAdapter}</td>
 *       <td>{@code CurrentValueReader}：批量实时值</td></tr>
 *   <tr><td>{@link io.github.forget_the_bright.tsdb.pspace.internal.adapter.PspaceHistoryAdapter}</td>
 *       <td>{@code HistoryReader}：RAW/聚合/插值/采样</td></tr>
 *   <tr><td>{@link io.github.forget_the_bright.tsdb.pspace.internal.adapter.PspaceDeltaAdapter}</td>
 *       <td>{@code DeltaReader}：累计量差分基线折叠</td></tr>
 *   <tr><td>{@link io.github.forget_the_bright.tsdb.pspace.internal.adapter.PspaceWriteAdapter}</td>
 *       <td>{@code WriteSession}：实时值写入</td></tr>
 * </table>
 *
 * <p>分页、页数缺失保护与异常上下文统一由 {@code PspaceSupport} 承担，适配器只写
 * “取一页 + 组装结果”；新增适配器时请复用它而不是各自实现。</p>
 *
 * <p>“改什么找哪个类”：</p>
 * <ul>
 *   <li>目录分页/名称过滤 → {@code PspaceTagCatalogAdapter}</li>
 *   <li>实时值缺失/缓存未热 → {@code PspaceCurrentValueAdapter}</li>
 *   <li>空桶诊断、INTERVAL_SUM 整窗单桶 → {@code PspaceHistoryAdapter}</li>
 *   <li>差分基线、reset/missing → {@code PspaceDeltaAdapter}</li>
 *   <li>写入门禁、逐条拒绝原因 → {@code PspaceWriteAdapter}</li>
 * </ul>
 *
 * <p><b>非公共 API</b>：本包是门户装配层的内部实现，签名随内部重构变化，
 * 宿主应用请勿直接依赖；稳定契约请使用
 * {@link io.github.forget_the_bright.tsdb.pspace.PspaceClient}。</p>
 *
 * @author wanghao
 * @since 0.1.2
 */
package io.github.forget_the_bright.tsdb.pspace.internal.adapter;