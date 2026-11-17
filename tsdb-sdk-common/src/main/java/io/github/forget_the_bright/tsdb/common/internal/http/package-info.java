/**
 * 跨驱动共享的 HTTP 传输工具（Hutool 实现）。
 *
 * <p><b>非公共 API</b>：本包是 {@code common.internal} 的 HTTP 子包，类与方法签名可能随
 * 内部重构变化。驱动模块可以使用，宿主应用不应直接依赖；需要稳定契约的调用方请使用
 * {@code common.api} 下的公开接口。</p>
 *
 * <h2>本包类清单与「改什么找哪个类」</h2>
 * <ul>
 *   <li>{@code HttpTransport} —— 唯一的收发入口。改超时、重定向策略、请求头、UTF-8
 *       请求体/响应体、TLS 开关都在这里；它只负责“发请求、读响应”，不解释业务状态码。</li>
 *   <li>{@code HttpResult} —— 传输结果值对象：HTTP 状态码 + UTF-8 响应体。</li>
 *   <li>{@code HttpErrors} —— 状态码到 SDK 异常的映射（401/403 认证、408/504 超时、
 *       其余连接异常），并按 {@code Texts.abbreviate} 截断错误体。改异常分级在这里。</li>
 *   <li>{@code BasicAuth} —— HTTP Basic 认证头构造（Hutool Base64）。</li>
 *   <li>{@code TrustAllSsl} —— 仅在实例显式开启 {@code trust-all-certificates} 时使用的
 *       trust-all SSLSocketFactory 与主机名放行器。</li>
 * </ul>
 *
 * <h2>依赖方向</h2>
 * <p>本包只依赖 JDK、Hutool 与 {@code common.internal.Texts}、{@code common.exception}；
 * 不依赖任何驱动，也不依赖 {@code common.internal.json}。三个驱动的
 * {@code *HttpClient} 都是本包的薄适配器。</p>
 *
 * @since 0.1.0
 */
package io.github.forget_the_bright.tsdb.common.internal.http;