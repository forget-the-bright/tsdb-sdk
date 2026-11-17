/**
 * HLS 原生请求对象。
 *
 * <p>原 SDK 通过 {@code TagNameListRequest} 和 {@code HistorianRequest} 组装
 * 请求体；为了让熟悉原 SDK 的开发者可以按原方式调用，这里保留同名 DTO 与
 * JSON 字段名，而不是强迫他们改走公共模型。</p>
 *
 * <p>{@link io.github.forget_the_bright.tsdb.hls.nativeapi.dto.HistorianRequest}
 * 的 {@code buildRequest} 修正了原 SDK 把 MAX/MIN 参数写反的缺陷；四个
 * {@code NeedQuery*} 标志仍要求至少开启一个，因为 HLS 历史接口只返回区间
 * 聚合，不返回原始点。</p>
 *
 * @since 0.1.0
 */
package io.github.forget_the_bright.tsdb.hls.nativeapi.dto;
