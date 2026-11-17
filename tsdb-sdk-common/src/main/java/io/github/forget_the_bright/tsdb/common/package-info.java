/**
 * 统一时序数据库接入层的公共入口。
 *
 * <h2>设计目标</h2>
 * <p>本包不绑定任何厂商，只定义“业务代码如何切换时序库”和“公共层暴露哪些
 * 归一化能力”。典型用法：</p>
 * <pre>{@code
 * TsdbManager manager = TsdbClients.fromYaml(Paths.get("tsdb.yml"));
 * TsdbClient client = manager.client();          // 默认实例
 * List<TagSample> values = client.currentValues().read(tags);
 * manager.switchTo("ge_main");                    // 策略切换
 * }</pre>
 *
 * <h2>两类调用路径</h2>
 * <ul>
 *   <li><b>公共接入层</b>：{@link io.github.forget_the_bright.tsdb.common.api.TsdbClient}
 *       及其子接口，返回归一化的
 *       {@link io.github.forget_the_bright.tsdb.common.model.TagSample} /
 *       {@link io.github.forget_the_bright.tsdb.common.model.TagSeries}。
 *       切换厂商时业务代码不变。</li>
 *   <li><b>原生能力层</b>：通过
 *       {@link io.github.forget_the_bright.tsdb.common.api.TsdbClient#unwrap(Class)}
 *       取得厂商原生 API，直接使用原 SDK 的端点、参数和返回结构，不受公共层
 *       能力裁剪影响。</li>
 * </ul>
 *
 * <h2>关键约束</h2>
 * <ul>
 *   <li>同一时刻只保留一个活动客户端；{@code switchTo} 先建立新连接，成功后再
 *       关闭旧连接，失败时旧客户端仍可用。</li>
 *   <li>公共层默认只读；写入必须显式开启 {@code tsdb.security.allow-write}。</li>
 *   <li>公共层不依赖 Spring，Spring Boot 自动装配放在 starter 模块。</li>
 * </ul>
 *
 * @since 0.1.0
 */
package io.github.forget_the_bright.tsdb.common;
