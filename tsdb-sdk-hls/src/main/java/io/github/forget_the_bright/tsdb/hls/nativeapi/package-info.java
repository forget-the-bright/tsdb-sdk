/**
 * HLS 原生 API 层的命名空间：本包自身不含类，请求对象都在子包
 * {@link io.github.forget_the_bright.tsdb.hls.nativeapi.dto} 里。
 *
 * <p>与 GE 的 {@code ge.nativeapi}（92 个端点用枚举描述）不同，HLS 只有少量
 * 实时/历史接口，因此没有端点枚举，端点路径直接写在
 * {@code hls.internal.HlsNativeApi} 的方法里；本层只保留与原 SDK 同名同字段的
 * 请求 DTO，方便熟悉原 SDK 的开发者按原方式组装请求体。</p>
 *
 * <h2>「改什么找哪里」</h2>
 * <ul>
 *   <li>改请求体字段、JSON 名称、MAX/MIN 组装 —— {@code nativeapi.dto}；</li>
 *   <li>改端点路径、认证、{@code Content-Type}、响应解析 —— {@code hls.internal.HlsNativeApi}；</li>
 *   <li>改公共模型到原生请求的换算 —— {@code hls.internal.HlsModels}。</li>
 * </ul>
 *
 * <p>原生层是逃生通道：公共接口覆盖不到的能力经 {@code HlsClient.unwrap} 取用，
 * 返回厂商原始结构，不做裁剪也不做静默降级。</p>
 *
 * @since 0.1.0
 */
package io.github.forget_the_bright.tsdb.hls.nativeapi;