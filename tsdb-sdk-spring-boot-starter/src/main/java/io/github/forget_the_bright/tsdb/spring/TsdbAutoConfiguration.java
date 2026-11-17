package io.github.forget_the_bright.tsdb.spring;

import io.github.forget_the_bright.tsdb.common.TsdbClients;
import io.github.forget_the_bright.tsdb.common.TsdbManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot 自动配置：把 {@code tsdb.*} 属性翻译成一个受容器托管的
 * {@link TsdbManager} 单例。
 *
 * <p>本类只在 classpath 上存在 {@link TsdbManager}（即引入了 common 模块）时生效，
 * 并通过 {@link EnableConfigurationProperties} 激活 {@link TsdbProperties} 的属性绑定。
 * 配置类使用 {@code proxyBeanMethods = false}，避免 CGLIB 代理开销，因此
 * {@code @Bean} 方法之间不能互相调用。</p>
 *
 * <p>消费方式：业务代码直接注入 {@link TsdbManager}：</p>
 *
 * <pre>{@code
 * @Autowired
 * private TsdbManager tsdbManager;
 * }</pre>
 *
 * <p>依赖 Spring 的 Bean 生命周期完成关闭：{@code @Bean(destroyMethod = "close")}
 * 使容器在销毁阶段调用 {@link TsdbManager#close()}，无需业务手工释放连接。
 * 注意本自动配置只创建管理器，首次建连仍然延迟到第一次调用
 * {@link TsdbManager#client()}，因此应用启动不会因为数据库暂时不可达而失败
 * （除非业务自己在启动阶段主动取客户端）。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(TsdbManager.class)
@EnableConfigurationProperties(TsdbProperties.class)
public class TsdbAutoConfiguration {

    /**
     * 创建并注册唯一的 {@link TsdbManager} Bean。
     *
     * <p>装配过程：{@link TsdbProperties#toConfig()} 转换为纯 Java 配置对象 →
     * 通过 {@code discoverDrivers()} 从 {@link java.util.ServiceLoader} 发现 classpath
     * 上的全部驱动 → 构建管理器。此处显式调用发现方法只是把“驱动为空时自动发现”
     * 这一隐式行为写明，效果与不调用相同。</p>
     *
     * <p>{@link ConditionalOnMissingBean} 保证用户自定义的 {@link TsdbManager} Bean
     * 优先生效——只要容器里已有同类 Bean，本方法就不会执行，便于测试或特殊场景覆盖。
     * 由于该方法只做对象组装、不发起网络连接，通常不会抛出连接类异常。</p>
     *
     * @param properties 已由 Spring Boot 绑定的 {@code tsdb.*} 配置，非 {@code null}
     * @return 新建的管理器，非 {@code null}；由容器负责调用其 {@code close()}
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbConfigurationException
     *         配置中没有任何实例（例如完全没有写 {@code tsdb.instances.*}）时抛出
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public TsdbManager tsdbManager(TsdbProperties properties) {
        return TsdbClients.manager()
                .config(properties.toConfig())
                .discoverDrivers()
                .build();
    }
}
