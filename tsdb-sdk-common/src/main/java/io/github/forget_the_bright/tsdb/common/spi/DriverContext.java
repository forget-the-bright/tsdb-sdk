package io.github.forget_the_bright.tsdb.common.spi;

import io.github.forget_the_bright.tsdb.common.config.TsdbConfig;

/**
 * 驱动创建客户端时传入的运行时上下文，封装“当前是哪个实例、根配置是什么、指标出口在哪”。
 *
 * <p>{@code TsdbManager} 在打开连接时构造本对象并交给
 * {@link TsdbDriver#create(io.github.forget_the_bright.tsdb.common.config.TsdbInstanceConfig, DriverContext)}，
 * 驱动从中读取实例名（用于日志、指标标签与错误信息）和根配置（用于拿到全局
 * {@link io.github.forget_the_bright.tsdb.common.config.SecurityConfig} 等跨实例设置）。</p>
 *
 * <p>不可变：三个字段均为 {@code final}，构造后不可修改；{@link #getMetrics()} 在构造时
 * 已把 {@code null} 归一化为 {@link Metrics#NOOP}，因此返回值永不为 {@code null}，
 * 驱动可以直接调用而无需判空。线程安全性取决于传入的
 * {@link TsdbConfig} 与 {@link Metrics} 实现是否线程安全（SDK 约定二者在运行期只读）。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class DriverContext {

    /** 实例名，对应配置中 {@code tsdb.instances} 下的键，非 {@code null}。 */
    private final String instanceName;
    /** 根配置，可能为 {@code null}（例如驱动被单独测试时）。 */
    private final TsdbConfig rootConfig;
    /** 指标出口，构造时已归一化，永不为 {@code null}。 */
    private final Metrics metrics;

    /**
     * 创建驱动上下文。
     *
     * @param instanceName 实例名，用于日志与指标标签；允许为 {@code null}（驱动应做好兜底）
     * @param rootConfig   根配置，可为 {@code null}；驱动使用前应判空
     * @param metrics      指标出口，可为 {@code null}，此时自动替换为 {@link Metrics#NOOP}
     */
    public DriverContext(String instanceName, TsdbConfig rootConfig, Metrics metrics) {
        this.instanceName = instanceName;
        this.rootConfig = rootConfig;
        this.metrics = metrics == null ? Metrics.NOOP : metrics;
    }

    /**
     * 返回实例名。
     *
     * @return 实例名，可能为 {@code null}
     */
    public String getInstanceName() {
        return instanceName;
    }

    /**
     * 返回根配置。
     *
     * <p>驱动通常只需要其中的 {@code security} 开关；实例自身的配置已通过
     * {@code create} 的另一个参数单独传入，无需在此重复查找。</p>
     *
     * @return 根配置，可能为 {@code null}
     */
    public TsdbConfig getRootConfig() {
        return rootConfig;
    }

    /**
     * 返回指标出口。
     *
     * @return 指标出口，永不为 {@code null}；未配置时为不做任何事的 {@link Metrics#NOOP}
     */
    public Metrics getMetrics() {
        return metrics;
    }
}
