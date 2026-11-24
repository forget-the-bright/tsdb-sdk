package io.github.forget_the_bright.tsdb.pspace;

import io.github.forget_the_bright.tsdb.common.api.TsdbClient;
import io.github.forget_the_bright.tsdb.common.config.SecurityConfig;
import io.github.forget_the_bright.tsdb.common.config.TsdbInstanceConfig;
import io.github.forget_the_bright.tsdb.common.model.Capability;
import io.github.forget_the_bright.tsdb.common.spi.DriverContext;
import io.github.forget_the_bright.tsdb.common.spi.TsdbDriver;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * pSpace 驱动的 SPI 入口。
 *
 * <p>职责：实现 {@link TsdbDriver}，通过 Java SPI（{@code META-INF/services}）
 * 被公共层发现，声明驱动类型 {@code pspace} 与能力集，并把公共层的
 * {@link TsdbInstanceConfig} 翻译成 {@link PspaceConfig} 后组装
 * {@link PspaceClient}。本类不持有可变状态、不缓存客户端，天然线程安全。</p>
 *
 * <p>能力声明：位号浏览、位号检索、当前值、区间聚合、插值、等间隔采样、
 * 累计量差分与写入。原始历史是基线能力（不需要声明）。不声明：位号 CRUD
 * （公共 API 无对应方法，建/改/删点走原生 API）、趋势、历史删除、报警订阅。</p>
 *
 * <p>配置校验：{@link #validate} 使用“宽松传输策略”构造一次 {@link PspaceConfig}，
 * 只校验结构与取值合法性（字符集、分页、archived、鉴权误配等），不会因为
 * {@code base-url} 是明文 http 而失败；真正的传输安全策略在 {@link #create}
 * 时按根配置执行。</p>
 *
 * @author wanghao
 * @since 0.1.2
 */
public final class PspaceDriver implements TsdbDriver {

    /**
     * 驱动能力集：不可修改，防止调用方误改全局能力判断。
     */
    private static final Set<Capability> CAPABILITIES = Collections.unmodifiableSet(
            EnumSet.of(Capability.TAG_BROWSE,
                    Capability.TAG_SEARCH,
                    Capability.CURRENT_VALUE,
                    Capability.AGGREGATED_HISTORY,
                    Capability.INTERPOLATED_HISTORY,
                    Capability.SAMPLED_HISTORY,
                    Capability.CUMULATIVE_DELTA,
                    Capability.WRITE));

    /**
     * 返回驱动类型标识。
     *
     * @return 固定字符串 {@code "pspace"}
     */
    @Override
    public String type() {
        return "pspace";
    }

    /**
     * 返回该驱动支持的能力集合。
     *
     * @return 非 {@code null} 的不可修改能力集合
     */
    @Override
    public Set<Capability> capabilities() {
        return CAPABILITIES;
    }

    /**
     * 校验单实例配置是否可用于 pSpace 驱动。
     *
     * <p>以宽松传输策略构造一次 {@link PspaceConfig}：结构与取值不合法会直接抛出
     * {@link io.github.forget_the_bright.tsdb.common.exception.TsdbConfigurationException}，
     * 但明文 http 不在此处拦截。本方法不发起网络请求，也不修改传入的配置对象。</p>
     *
     * @param config 待校验的单实例配置，不能为 {@code null}
     */
    @Override
    public void validate(TsdbInstanceConfig config) {
        SecurityConfig permissive = new SecurityConfig();
        permissive.setAllowInsecureTransport(true);
        new PspaceConfig("validate", config, permissive);
    }

    /**
     * 按配置创建 pSpace 客户端。
     *
     * <p>创建过程不探测服务端连通性；连接失败会在首次实际请求时以
     * {@code TsdbConnectionException} 暴露。缓存未热时的自动刷新也不在创建阶段执行，
     * 由第一次真实读请求按需触发。</p>
     *
     * @param config  单实例配置，不能为 {@code null}
     * @param context 驱动上下文，提供实例名与根配置；不能为 {@code null}
     * @return 非 {@code null} 的 pSpace 客户端，调用方负责关闭
     */
    @Override
    public TsdbClient create(TsdbInstanceConfig config, DriverContext context) {
        SecurityConfig security = context.getRootConfig() == null
                ? new SecurityConfig()
                : context.getRootConfig().getSecurity();
        PspaceConfig pspaceConfig = new PspaceConfig(context.getInstanceName(), config, security);
        return new PspaceClient(context.getInstanceName(), pspaceConfig);
    }
}