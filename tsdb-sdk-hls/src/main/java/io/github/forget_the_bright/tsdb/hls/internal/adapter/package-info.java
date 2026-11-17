/**
 * HLS 驱动的门户装配层：把公共接口的每个能力接到原生 API 上。
 *
 * <p><b>非公共 API</b>：本包类是 {@code public} 仅为让根包门户 {@code hls.HlsClient} 能构造它们，
 * 不代表对外承诺。签名随内部重构变化，宿主应用请勿直接依赖或自行实例化；
 * 请通过 {@code hls.HlsClient} 取得能力对象。</p>
 *
 * <h2>本包类清单与「改什么找哪个类」</h2>
 * <ul>
 *   <li>{@code HlsTagCatalogAdapter} —— 标签目录浏览与单标签查询。</li>
 *   <li>{@code HlsCurrentValueAdapter} —— 最新值（实时库 {@code /ddb/read_ddbtagvalue}）。</li>
 *   <li>{@code HlsHistoryAdapter} —— 历史库查询与各历史模式；改时间窗、方向、条数在这里。</li>
 *   <li>{@code HlsDeltaAdapter} —— 累计量差分，按 {@code DeltaReader} 契约用 {@code BOUND}
 *       近似桶末值；该语义仍待现场确认（见 {@code docs/10}），不要当成已裁决口径。</li>
 *   <li>{@code HlsWriteAdapter} —— 只读占位实现：{@code isWritable()} 恒 {@code false}，
 *       {@code write} 一律抛 {@code TsdbUnsupportedException}。保留实例是为了让公共层
 *       统一探测可写性，而不是把 {@code null} 传给调用方。</li>
 *   <li>{@code HlsSupport} —— 五个适配器共享的配置、原生客户端、映射器、开闭状态
 *       （{@code ensureOpen()}）与异常上下文。</li>
 * </ul>
 *
 * <h2>依赖方向</h2>
 * <p>本包依赖 {@code hls.internal}、{@code hls.nativeapi.dto}、根包 {@code HlsConfig}/{@code HlsMapper}
 * 与 {@code common}；不依赖其他驱动。</p>
 *
 * @since 0.1.0
 */
package io.github.forget_the_bright.tsdb.hls.internal.adapter;