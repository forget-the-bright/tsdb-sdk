/**
 * Spring Boot 自动装配。
 *
 * <p>starter 只做两件事：把 {@code tsdb.*} 配置绑定成公共层配置对象；创建一个
 * 单例 {@link io.github.forget_the_bright.tsdb.common.TsdbManager} 并在容器关闭
 * 时调用其 {@code close()}。驱动发现仍走 {@code ServiceLoader}，starter 不硬编码
 * 任何厂商。</p>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * @Service
 * public class QueryService {
 *     private final TsdbManager manager;
 *
 *     public QueryService(TsdbManager manager) {
 *         this.manager = manager;
 *     }
 *
 *     public List<TagSample> latest(Collection<String> tags) {
 *         return manager.client().currentValues().read(tags);
 *     }
 * }
 * }</pre>
 *
 * <p>配置示例见根目录 {@code tsdb.example.yml}。切换时序库只需改
 * {@code tsdb.default-instance}，业务代码不动。</p>
 *
 * <h2>覆盖默认 Bean</h2>
 * <p>自动配置使用 {@code @ConditionalOnMissingBean}；如果应用自己声明了
 * {@code TsdbManager}，starter 不会覆盖。需要自定义驱动注册时，可在自己的配置
 * 类里构造 manager 并注册为 Bean。</p>
 *
 * @since 0.1.0
 */
package io.github.forget_the_bright.tsdb.spring;
