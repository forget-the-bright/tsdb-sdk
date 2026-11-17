package io.github.forget_the_bright.tsdb.spring;

import io.github.forget_the_bright.tsdb.common.config.SecurityConfig;
import io.github.forget_the_bright.tsdb.common.config.TsdbConfig;
import io.github.forget_the_bright.tsdb.common.config.TsdbInstanceConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code tsdb.*} 配置树与纯 Java 配置对象之间的绑定载体。
 *
 * <p>本类字段与 YAML 配置一一对应，由 Spring Boot 的松散绑定规则填充，再通过
 * {@link #toConfig()} 转成 SDK 真正使用的 {@link TsdbConfig}。之所以不让
 * {@link TsdbConfig} 直接带 {@code @ConfigurationProperties}，是为了让 common 模块
 * 不依赖 Spring，从而同时支持无容器场景。</p>
 *
 * <p>典型配置：</p>
 *
 * <pre>{@code
 * tsdb:
 *   default-instance: primary
 *   fail-fast: true
 *   security:
 *     allow-write: false
 *     allow-insecure-transport: false
 *     verify-hostname: true
 *     audit-enabled: true
 *   instances:
 *     primary:
 *       type: hls
 *       ...
 * }</pre>
 *
 * <p>字段默认值：{@code fail-fast} 为 {@code true}；{@code security} 不为 {@code null}
 * （默认即“只读 + 强制 TLS”的安全缺省）；{@code instances} 为空 {@link LinkedHashMap}。
 * 每个实例的具体字段（地址、认证、超时、重试等）见 {@link TsdbInstanceConfig}。</p>
 *
 * <p>线程安全：本类不是线程安全的，仅用于启动阶段的属性绑定；绑定完成后应视为只读。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
@ConfigurationProperties(prefix = "tsdb")
public class TsdbProperties {

    /** 默认实例名，对应 {@code tsdb.default-instance}；未配置时为 {@code null}。 */
    private String defaultInstance;
    /** 是否快速失败，对应 {@code tsdb.fail-fast}，默认 {@code true}。 */
    private boolean failFast = true;
    /** 全局安全开关，对应 {@code tsdb.security.*}，默认非 {@code null}。 */
    private SecurityConfig security = new SecurityConfig();
    /** 实例表，对应 {@code tsdb.instances.*}，保持声明顺序，默认空 Map。 */
    private Map<String, TsdbInstanceConfig> instances = new LinkedHashMap<String, TsdbInstanceConfig>();

    /**
     * 转换为 common 模块使用的根配置对象。
     *
     * <p>这是一次浅拷贝：{@code security} 与 {@code instances} 都是直接传递引用，
     * 之后修改 {@link #getInstances()} 返回的 Map 会同时影响已生成的 {@link TsdbConfig}。
     * 转换本身不做校验（例如不检查默认实例是否存在、实例名是否为空），这些校验
     * 发生在首次获取客户端时。容器通常只在创建 Bean 时调用一次。</p>
     *
     * @return 与当前属性内容一致的根配置，非 {@code null}
     */
    public TsdbConfig toConfig() {
        TsdbConfig config = new TsdbConfig();
        config.setDefaultInstance(defaultInstance);
        config.setFailFast(failFast);
        config.setSecurity(security);
        config.setInstances(instances);
        return config;
    }

    /**
     * 返回默认实例名。
     *
     * @return 默认实例名；未配置时为 {@code null}
     */
    public String getDefaultInstance() {
        return defaultInstance;
    }

    /**
     * 设置默认实例名。
     *
     * <p>此处不校验名称是否存在，写错会在首次 {@code client()} 时以配置异常暴露；
     * 名称区分大小写，必须与 {@code tsdb.instances} 下的键完全一致。</p>
     *
     * @param defaultInstance 默认实例名，可为 {@code null}（表示按声明顺序取第一个启用实例）
     */
    public void setDefaultInstance(String defaultInstance) {
        this.defaultInstance = defaultInstance;
    }

    /**
     * 返回是否快速失败。
     *
     * @return {@code true} 表示配置/连接错误立即抛出；默认 {@code true}
     */
    public boolean isFailFast() {
        return failFast;
    }

    /**
     * 设置是否快速失败。
     *
     * @param failFast {@code true} 立即失败；默认 {@code true}
     */
    public void setFailFast(boolean failFast) {
        this.failFast = failFast;
    }

    /**
     * 返回全局安全开关。
     *
     * @return 安全配置，不为 {@code null}
     */
    public SecurityConfig getSecurity() {
        return security;
    }

    /**
     * 设置全局安全开关。
     *
     * <p>传入 {@code null} 时等价于恢复默认安全配置（只读 + 强制 TLS），
     * 由 {@link TsdbConfig#setSecurity(SecurityConfig)} 完成归一化。</p>
     *
     * @param security 安全配置，可为 {@code null}
     */
    public void setSecurity(SecurityConfig security) {
        this.security = security;
    }

    /**
     * 返回实例配置表（实例名 -&gt; 实例配置）。
     *
     * <p>返回的是可变的内部 Map，按声明顺序迭代；Spring Boot 绑定时会向其中填充条目。</p>
     *
     * @return 实例配置映射，不为 {@code null}
     */
    public Map<String, TsdbInstanceConfig> getInstances() {
        return instances;
    }

    /**
     * 设置实例配置表。
     *
     * <p>传入 {@code null} 时替换为空的可变 {@link LinkedHashMap}，因此之后仍可正常绑定
     * 或向其添加实例。直接持有传入的 Map 引用，不做拷贝。</p>
     *
     * @param instances 实例配置映射，可为 {@code null}
     */
    public void setInstances(Map<String, TsdbInstanceConfig> instances) {
        this.instances = instances == null
                ? new LinkedHashMap<String, TsdbInstanceConfig>()
                : instances;
    }
}
