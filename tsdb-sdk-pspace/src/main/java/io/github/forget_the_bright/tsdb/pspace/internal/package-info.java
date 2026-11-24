/**
 * pSpace 驱动的内部实现：HTTP 协议、响应解析、模型映射与位号解析。
 *
 * <p>类清单与职责：</p>
 * <table border="1">
 *   <caption>internal 包</caption>
 *   <tr><th>类</th><th>职责</th></tr>
 *   <tr><td>{@link io.github.forget_the_bright.tsdb.pspace.internal.PspaceNativeApi}</td>
 *       <td>23 个 REST 端点、自动刷新/重试、请求体拼装</td></tr>
 *   <tr><td>{@link io.github.forget_the_bright.tsdb.pspace.internal.PspaceResponseParser}</td>
 *       <td>点位/实时/历史/分页响应解析（包内可见）</td></tr>
 *   <tr><td>{@link io.github.forget_the_bright.tsdb.pspace.internal.PspaceHttp}</td>
 *       <td>URL 编码、信封解包、错误映射、{@code archived=1} 拒绝</td></tr>
 *   <tr><td>{@link io.github.forget_the_bright.tsdb.pspace.internal.PspaceModels}</td>
 *       <td>未映射的原生值载体（Point/RealValue/HistValue/HistRow/Page）</td></tr>
 *   <tr><td>{@link io.github.forget_the_bright.tsdb.pspace.internal.PspaceMapper}</td>
 *       <td>DataType/TagType/质量/值/时间映射</td></tr>
 *   <tr><td>{@link io.github.forget_the_bright.tsdb.pspace.internal.PspaceTagResolver}</td>
 *       <td>LongName → 点位 的进程内缓存与回退检索</td></tr>
 *   <tr><td>{@link io.github.forget_the_bright.tsdb.pspace.internal.PspaceSubscription}</td>
 *       <td>8081 WebSocket 订阅门面</td></tr>
 * </table>
 *
 * <p>依赖方向（单向，禁止反向）：
 * {@code adapter → PspaceNativeApi → PspaceHttp / PspaceResponseParser → PspaceModels / PspaceMapper}。
 * {@code PspaceTagResolver} 只依赖 {@code PspaceNativeApi}；订阅门面不参与查询链。</p>
 *
 * <p><b>非公共 API</b>：本包及其子包是门户装配层的内部实现，签名随内部重构变化，
 * 宿主应用请勿直接依赖；原生接口请通过
 * {@link io.github.forget_the_bright.tsdb.pspace.PspaceClient#unwrap(Class)} 获取
 * {@code PspaceNativeApi}。稳定契约请使用
 * {@link io.github.forget_the_bright.tsdb.pspace.PspaceClient}。</p>
 *
 * @author wanghao
 * @since 0.1.2
 */
package io.github.forget_the_bright.tsdb.pspace.internal;