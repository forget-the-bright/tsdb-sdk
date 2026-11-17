/**
 * 驱动扩展点（SPI）。
 *
 * <p>新增一个时序库时，只需实现
 * {@link io.github.forget_the_bright.tsdb.common.spi.TsdbDriver}，在
 * {@code META-INF/services/} 下注册实现类，公共层即可通过
 * {@code ServiceLoader} 发现它。驱动模块之间互不依赖，也不依赖 Spring。</p>
 *
 * <h2>实现要求</h2>
 * <ul>
 *   <li>{@code type()} 返回稳定的小写标识，例如 {@code hls}、{@code ge}、
 *       {@code iotdb}，与配置中的 {@code type} 一致。</li>
 *   <li>{@code capabilities()} 声明真实支持的能力，不能把未实现的能力标为
 *       支持；公共层据此做能力协商。</li>
 *   <li>{@code validate(config)} 只做结构校验，不发网络请求，便于启动前快速
 *       失败。</li>
 *   <li>{@code create(config, context)} 返回的客户端由调用方负责关闭；实现
 *       内部不得使用静态可变字段，否则同一 JVM 无法并存多个实例。</li>
 *   <li>{@code probe(context)} 可选，用于启动时探测服务端真实能力。</li>
 * </ul>
 *
 * <p>原生能力不要求出现在 SPI 中：客户端实现应通过
 * {@code unwrap(Class)} 暴露自己的 native API，公共层不限制其接口面。</p>
 *
 * @since 0.1.0
 */
package io.github.forget_the_bright.tsdb.common.spi;
