package io.github.forget_the_bright.tsdb.common.config;

import io.github.forget_the_bright.tsdb.common.exception.TsdbConfigurationException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SDK 根配置对象：声明本次运行可以访问哪些时序库实例、默认使用哪一个，
 * 以及作用于全部实例的安全开关。
 *
 * <p>一个进程通常只对接一套时序库，因此“当前实例”由 {@code TsdbManager} 统一管理：
 * 本类只描述“有哪些实例可用”，真正的单活切换由
 * {@link io.github.forget_the_bright.tsdb.common.TsdbManager#switchTo(String)} 完成。
 * 实例名是业务代码引用实例的唯一键，必须唯一；{@link #getInstances()} 使用
 * {@link LinkedHashMap} 保存，迭代顺序即配置声明顺序，这决定了未显式指定
 * {@code default-instance} 时“第一个启用的实例”是谁。</p>
 *
 * <p>本类由 {@link TsdbConfigLoader} 从 YAML 构建，或由 Spring Boot 的
 * {@code TsdbProperties#toConfig()} 转换而来，两条路径产出的对象语义一致。</p>
 *
 * <p>线程安全：本类不是线程安全的。约定在构建阶段填充、之后只读；运行期修改
 * {@link #getInstances()} 返回的可变 Map 可能导致并发问题。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public class TsdbConfig {

    /** 默认实例名；为 {@code null} 或空白时取配置中第一个 enabled 的实例。 */
    private String defaultInstance;
    /** 启动阶段是否快速失败，默认 {@code true}。 */
    private boolean failFast = true;
    /** 全局安全开关，永不为 {@code null}。 */
    private SecurityConfig security = new SecurityConfig();
    /** 实例名到实例配置的映射，保持声明顺序，永不为 {@code null}。 */
    private Map<String, TsdbInstanceConfig> instances = new LinkedHashMap<String, TsdbInstanceConfig>();

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
     * <p>不会校验该名称是否存在于 {@link #getInstances()} 中，真正的校验发生在
     * 首次获取客户端时（{@code TsdbManager} 会抛出配置异常）。</p>
     *
     * @param defaultInstance 默认实例名，可为 {@code null} 表示按声明顺序取第一个启用实例
     */
    public void setDefaultInstance(String defaultInstance) {
        this.defaultInstance = defaultInstance;
    }

    /**
     * 返回是否在启动/首次连接时快速失败。
     *
     * @return {@code true} 表示配置或连接错误立即抛给调用方；默认 {@code true}
     */
    public boolean isFailFast() {
        return failFast;
    }

    /**
     * 设置是否在启动/首次连接时快速失败。
     *
     * <p>置为 {@code false} 时，框架集成方（如 Spring 容器）可以选择记录错误后继续启动，
     * 由业务在真正调用 {@code client()} 时再感知失败。</p>
     *
     * @param failFast {@code true} 立即失败，默认 {@code true}
     */
    public void setFailFast(boolean failFast) {
        this.failFast = failFast;
    }

    /**
     * 返回全局安全开关。
     *
     * @return 安全配置，永不为 {@code null}
     */
    public SecurityConfig getSecurity() {
        return security;
    }

    /**
     * 设置全局安全开关。
     *
     * @param security 安全配置；传入 {@code null} 时替换为默认的安全配置（只读 + 强制 TLS）
     */
    public void setSecurity(SecurityConfig security) {
        this.security = security == null ? new SecurityConfig() : security;
    }

    /**
     * 返回全部实例配置（实例名 -&gt; 配置）。
     *
     * <p>返回的是内部可变 Map 本身，按声明顺序迭代，永不为 {@code null}；
     * {@code TsdbManager} 构造时要求该 Map 至少包含一个元素。</p>
     *
     * @return 可变的实例配置映射，非 {@code null}
     */
    public Map<String, TsdbInstanceConfig> getInstances() {
        return instances;
    }

    /**
     * 设置全部实例配置。
     *
     * <p>直接持有传入的 Map 引用，不做拷贝；传入 {@code null} 时替换为空的
     * {@link LinkedHashMap}。调用方需保证实例名唯一，后写同名键会覆盖先前配置。</p>
     *
     * @param instances 实例配置映射，可为 {@code null}
     */
    public void setInstances(Map<String, TsdbInstanceConfig> instances) {
        this.instances = instances == null
                ? new LinkedHashMap<String, TsdbInstanceConfig>()
                : instances;
    }

    /**
     * 按实例名取出实例配置，不存在时立即失败。
     *
     * <p>用于“配置里写错实例名”这类错误的尽早暴露：不做大小写兼容、不做前缀匹配，
     * 名称必须与配置中的键完全一致（区分大小写）。</p>
     *
     * @param name 实例名，建议非 {@code null}
     * @return 对应的实例配置，非 {@code null}
     * @throws TsdbConfigurationException 当该名称未在 {@link #getInstances()} 中定义时抛出，
     *                                    消息格式为 {@code "unknown instance: <name>"}
     */
    public TsdbInstanceConfig instance(String name) {
        TsdbInstanceConfig config = instances.get(name);
        if (config == null) {
            throw new TsdbConfigurationException("unknown instance: " + name);
        }
        return config;
    }
}
