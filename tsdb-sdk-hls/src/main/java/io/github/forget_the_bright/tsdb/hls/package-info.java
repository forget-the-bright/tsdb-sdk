/**
 * 和利时（HLS）时序库驱动。
 *
 * <h2>协议特征</h2>
 * <p>HLS 的接口面很小：实时值（DDB）、区间聚合（HDB）、标签目录、令牌，共四类。
 * 它没有原始点、插值、采样、趋势和写入接口，因此公共层只声明
 * {@code TAG_BROWSE / CURRENT_VALUE / AGGREGATED_HISTORY} 三项能力。历史查询的
 * 返回值是“宽表”：一行同时带 AVG/MIN/MAX/Bound 多列，驱动按
 * {@code HistoryQuery.aggregation} 选择对应列，而不是固定取 AVG。</p>
 *
 * <h2>认证</h2>
 * <p>{@code GET /user/get_token}，请求头 {@code userid}、{@code secretkey}，
 * 返回 {@code Data.Token}；后续业务请求把原始 token 放进 {@code token} 请求头
 * （没有 Bearer 前缀）。令牌默认缓存 1800 秒，提前 5 秒刷新；也支持配置静态
 * token。</p>
 *
 * <h2>质量码陷阱</h2>
 * <p>HLS 的 GOOD 是 1、BAD 是 0，与 GE 的 GOOD=3 不同；
 * {@code OneTagHDBValue} 只有 {@code Quality_Bound/MAX/MIN}，<b>没有
 * Quality_AVG</b>。因此 AVG 结果的质量固定为 {@code UNKNOWN}，并在
 * {@code TagSeries.attributes} 里标注 {@code qualitySource=hls_not_provided}，
 * 绝不猜测为 GOOD。</p>
 *
 * <h2>原生能力</h2>
 * <p>{@link io.github.forget_the_bright.tsdb.hls.internal.HlsNativeApi} 保留原
 * SDK 的方法名与全部重载，并通过 {@code nativeapi.dto} 下的请求对象支持原生的
 * DTO 调用方式；同时修正了原 SDK 中 MAX/MIN 参数写反的缺陷。</p>
 *
 * @since 0.1.0
 */
package io.github.forget_the_bright.tsdb.hls;
