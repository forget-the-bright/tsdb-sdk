/**
 * 跨驱动共享的文本工具（本层根包）。
 *
 * <p><b>非公共 API</b>：本包及子包都是实现细节，类与方法签名随内部重构变化。
 * 驱动模块可以使用，宿主应用不应直接依赖；需要稳定契约请使用 {@code common.api}
 * 与 {@code common.model}。</p>
 *
 * <h2>本包类清单</h2>
 * <ul>
 *   <li>{@link io.github.forget_the_bright.tsdb.common.internal.Texts} ——
 *       {@code trimToNull}（空白归一为 {@code null}，配置解析统一入口）与
 *       {@code abbreviate}（错误消息里的响应体截断，避免把整页 HTML 塞进异常）。
 *       改「配置空值怎么算」「报错文本截多长」在这里。</li>
 * </ul>
 *
 * <h2>按职责找子包</h2>
 * <ul>
 *   <li>HTTP 收发、认证头、状态码到异常的映射、TLS 策略 ——
 *       {@link io.github.forget_the_bright.tsdb.common.internal.http}；</li>
 *   <li>JSON 解析与 fastjson 安全初始化 ——
 *       {@link io.github.forget_the_bright.tsdb.common.internal.json}。</li>
 * </ul>
 *
 * <p>本层根包只留文本工具，是因为它被 http/json 与各驱动配置共同使用，
 * 放进任一子包都会造成子包之间的横向依赖。</p>
 *
 * @since 0.1.0
 */
package io.github.forget_the_bright.tsdb.common.internal;