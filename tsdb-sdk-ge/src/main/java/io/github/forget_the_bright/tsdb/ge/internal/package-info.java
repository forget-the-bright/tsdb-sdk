/**
 * GE Proficy Historian 驱动的内部实现：原生 API、传输、令牌与厂商模型。
 *
 * <p><b>非公共 API</b>：本包不承诺兼容（见 {@code docs/03} 包名规则）。类是 {@code public}
 * 只因为 Java 没有包级以外的“内部”可见性，且门户与 {@code unwrap} 逃生口需要跨包访问；
 * 宿主应用不应直接依赖本包类型，稳定契约请使用 {@code ge.GeClient} 与 {@code common.api}。</p>
 *
 * <h2>本包类清单与「改什么找哪个类」</h2>
 * <ul>
 *   <li>{@code GeNativeApi} —— 原生 REST 能力面（92 个端点的调用入口、{@code invoke}、
 *       {@code rawRequest}）。改端点覆盖、请求体形态、{@code ErrorCode=11} 截断语义在这里；
 *       端点枚举在 {@code ge.nativeapi.GeEndpoint}。</li>
 *   <li>{@code GeHttpClient} —— {@code common.internal.http.HttpTransport} 的薄适配器：
 *       Basic 认证头、自签名证书开关、异常消息的 {@code GE} 前缀。</li>
 *   <li>{@code GeTokenManager} —— OAuth {@code /uaa/oauth/token} 换取与缓存刷新。</li>
 *   <li>{@code GeModels} —— 厂商响应 DTO（{@code Series}/{@code DataItem} 等），
 *       由根包 {@code GeMapper} 转换为公共模型。</li>
 * </ul>
 *
 * <h2>子包</h2>
 * <p>{@code ge.internal.adapter} —— 门户装配层：四个查询适配器与共享支撑 {@code GeSupport}。</p>
 *
 * <h2>依赖方向</h2>
 * <p>本包依赖 {@code common}（api/model/exception/internal）与根包的 {@code GeConfig}；
 * 不依赖其他驱动。根包 {@code GeClient} 构造 {@code internal.adapter} 的适配器，
 * 适配器读本包与根包配置——门面与实现的双向引用是门面模式的固有形状，模块级依赖无环。</p>
 *
 * @since 0.1.0
 */
package io.github.forget_the_bright.tsdb.ge.internal;