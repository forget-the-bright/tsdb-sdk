package io.github.forget_the_bright.tsdb.ge;

import io.github.forget_the_bright.tsdb.common.api.TsdbClient;
import io.github.forget_the_bright.tsdb.common.config.SecurityConfig;
import io.github.forget_the_bright.tsdb.common.config.TsdbInstanceConfig;
import io.github.forget_the_bright.tsdb.common.model.Capability;
import io.github.forget_the_bright.tsdb.common.spi.DriverContext;
import io.github.forget_the_bright.tsdb.common.spi.TsdbDriver;
import io.github.forget_the_bright.tsdb.ge.internal.GeHttpClient;
import io.github.forget_the_bright.tsdb.ge.internal.GeNativeApi;
import io.github.forget_the_bright.tsdb.ge.internal.GeTokenManager;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * GE Proficy Historian 驱动的 SPI 入口。
 *
 * <p>职责：实现公共层的 {@link TsdbDriver} 契约，负责“校验配置”和“装配客户端”两件事，
 * 是上层只依赖 {@code tsdb-sdk-common} 时唯一会直接接触到的 GE 类。通过
 * {@code META-INF/services/io.github.forget_the_bright.tsdb.common.spi.TsdbDriver}
 * 以 {@link java.util.ServiceLoader} 方式注册，驱动类型标识为 {@code "ge"}。</p>
 *
 * <p>设计要点：装配顺序固定为“配置归一化 → HTTP 传输 → JSON 映射 → 令牌管理 →
 * 原生 API → 公共客户端”，每层只依赖前一层，且构造期不做网络 I/O，因此
 * {@link #create} 失败只会暴露配置错误，连接与鉴权错误延迟到首次请求。</p>
 *
 * <p>限制：{@link #validate} 刻意使用放行明文传输的 {@link SecurityConfig}，
 * 所以它不会因 {@code http://} 地址而报错——真正的“是否允许明文”仍由
 * {@link #create} 阶段的严格配置决定。校验也不探测连通性与凭据有效性。</p>
 *
 * <p>线程安全性：本类无可变状态，能力集合是不可变视图，实例可被并发复用；
 * 每次 {@link #create} 都会产生互相独立的客户端。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class GeDriver implements TsdbDriver {

    /**
     * 驱动声明的能力集合，类型为 {@link Capability} 的不可变枚举视图。
     *
     * <p>不包含写入能力；与 {@code GeClient} 使用的是同一组语义（两处需同步维护）。</p>
     */
    private static final Set<Capability> CAPABILITIES = Collections.unmodifiableSet(
            EnumSet.of(Capability.TAG_BROWSE,
                    Capability.CURRENT_VALUE,
                    Capability.RAW_HISTORY,
                    Capability.AGGREGATED_HISTORY,
                    Capability.INTERPOLATED_HISTORY,
                    Capability.SAMPLED_HISTORY,
                    Capability.CUMULATIVE_DELTA));

    /**
     * 返回驱动类型标识。
     *
     * <p>该字符串用于配置中的 {@code type} 匹配和工厂路由，必须与
     * {@code GeClient#type()} 保持一致。</p>
     *
     * @return 恒为 {@code "ge"}
     */
    @Override
    public String type() {
        return "ge";
    }

    /**
     * 返回驱动支持的能力集合。
     *
     * @return 不可变的能力集合，非 {@code null}
     */
    @Override
    public Set<Capability> capabilities() {
        return CAPABILITIES;
    }

    /**
     * 校验实例配置是否可被 GE 驱动接受。
     *
     * <p>实现方式是用一个“放行明文传输”的安全配置构造一次 {@link GeConfig}，
     * 从而只校验结构性约束（必填项、URL 形态、鉴权组合、超时等），
     * <strong>不建立连接、不校验凭据</strong>。之所以放宽明文限制，是为了让
     * {@code validate} 只回答“配置结构是否完整”，把安全策略的判断留给
     * {@link #create}，避免同一份配置在预检与创建两个阶段给出不一致的结论。</p>
     *
     * @param config 公共层实例配置，不能为 {@code null}
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbConfigurationException
     *         配置缺少必填项、URL 非法、鉴权参数不完整或超时非正
     */
    @Override
    public void validate(TsdbInstanceConfig config) {
        SecurityConfig permissive = new SecurityConfig();
        permissive.setAllowInsecureTransport(true);
        new GeConfig("validate", config, permissive);
    }

    /**
     * 装配一个可用的 GE 客户端。
     *
     * <p>安全配置取自根配置（{@link DriverContext#getRootConfig()}）；根配置缺失时
     * 使用严格默认值（只允许 https、校验主机名）。实例名取自
     * {@link DriverContext#getInstanceName()}，仅用于日志与异常上下文。</p>
     *
     * <p>注意：本方法不发起任何请求，返回的客户端首次调用才会触发 OAuth 令牌获取；
     * 调用方用完后应调用 {@link TsdbClient#close()}（当前为空操作，但应保持习惯）。</p>
     *
     * @param config  实例配置，不能为 {@code null}
     * @param context 驱动上下文，提供实例名与根配置，不能为 {@code null}
     * @return 新建的 GE 客户端，非 {@code null}
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbConfigurationException
     *         配置不合法（校验规则同 {@link #validate}，但额外执行明文传输与主机名校验策略）
     */
    @Override
    public TsdbClient create(TsdbInstanceConfig config, DriverContext context) {
        SecurityConfig security = context.getRootConfig() == null
                ? new SecurityConfig()
                : context.getRootConfig().getSecurity();
        GeConfig geConfig = new GeConfig(context.getInstanceName(), config, security);
        GeHttpClient http = new GeHttpClient(geConfig);
        GeTokenManager tokens = new GeTokenManager(geConfig, http);
        GeNativeApi nativeApi = new GeNativeApi(geConfig, http, tokens);
        return new GeClient(geConfig, nativeApi);
    }
}
