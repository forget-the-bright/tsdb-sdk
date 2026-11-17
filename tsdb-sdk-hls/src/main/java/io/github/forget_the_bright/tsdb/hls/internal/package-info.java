/**
 * 力控 HLS Historian 驱动的内部实现：原生 API、传输、令牌与厂商模型。
 *
 * <p><b>非公共 API</b>：本包不承诺兼容（见 {@code docs/03} 包名规则）。类是 {@code public}
 * 只因为门户与 {@code unwrap} 逃生口需要跨包访问；宿主应用不应直接依赖本包类型，
 * 稳定契约请使用 {@code hls.HlsClient} 与 {@code common.api}。</p>
 *
 * <h2>本包类清单与「改什么找哪个类」</h2>
 * <ul>
 *   <li>{@code HlsNativeApi} —— 原生 REST 能力面（{@code /ddb/read_ddbtagvalue}、
 *       {@code /hdb/read_hdbtagvalue} 等）与 {@code rawRequest} 逃生口。
 *       HLS 响应字段大小写不敏感，取值走 {@code JsonText.textIgnoreCase}。</li>
 *   <li>{@code HlsHttpClient} —— {@code common.internal.http.HttpTransport} 的薄适配器：
 *       严格 {@code application/json} 请求头（不追加 charset）、令牌头、异常消息 {@code HLS} 前缀。</li>
 *   <li>{@code HlsTokenManager} —— 令牌获取与缓存刷新。</li>
 *   <li>{@code HlsModels} —— 厂商响应 DTO，由根包 {@code HlsMapper} 转换为公共模型。</li>
 * </ul>
 *
 * <h2>子包与相关包</h2>
 * <ul>
 *   <li>{@code hls.internal.adapter} —— 门户装配层：五个适配器（含写入）与 {@code HlsSupport}。</li>
 *   <li>{@code hls.nativeapi.dto} —— 原生请求体 DTO（{@code HistorianRequest} 等）。</li>
 * </ul>
 *
 * <h2>依赖方向</h2>
 * <p>本包依赖 {@code common} 与根包 {@code HlsConfig}；不依赖其他驱动。
 * 现场经反向代理访问，代理偶发 502 属环境问题，不在本包重试或掩盖。</p>
 *
 * @since 0.1.0
 */
package io.github.forget_the_bright.tsdb.hls.internal;