package io.github.forget_the_bright.tsdb.hls;

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
 * HLS 驱动的 SPI 入口。
 *
 * <p>职责：实现 {@link TsdbDriver}，通过 Java SPI（{@code META-INF/services}）
 * 被公共层发现，负责声明驱动类型与能力集，并在创建客户端时把公共层的
 * {@link TsdbInstanceConfig} 翻译成 {@link HlsConfig}。本类不持有可变状态、
 * 不缓存客户端实例，因此天然线程安全，可被多个线程同时调用。</p>
 *
 * <p>能力声明：HLS 仅支持位号浏览、当前值与区间聚合历史三项
 * （{@link Capability#TAG_BROWSE}、{@link Capability#CURRENT_VALUE}、
 * {@link Capability#AGGREGATED_HISTORY}）。原始历史、插值、趋势、写入等
 * 能力均未声明，调用对应接口会得到 {@code TSDB-UNSUPPORTED}。</p>
 *
 * <p>与公共层/原生层的关系：{@link #create} 只负责组装
 * {@link HlsConfig} 与 {@link HlsClient}，真正的 HTTP 协议交互与 JSON 解析
 * 在 {@code HlsNativeApi} 中完成；需要厂商原始字段时可通过
 * {@code HlsClient#unwrap} 取出原生 API。</p>
 *
 * <p>配置校验：{@link #validate} 使用“宽松安全策略”构造一次
 * {@link HlsConfig}，只校验结构与取值合法性，不会因为 {@code base-url}
 * 是明文 http 而失败；真正的传输安全策略在 {@link #create} 时按根配置执行。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class HlsDriver implements TsdbDriver {

    /**
     * 驱动能力集：只读，且只包含位号浏览、当前值、聚合历史三项。
     *
     * <p>使用不可修改集合，防止调用方误改而影响全局能力判断。</p>
     */
    private static final Set<Capability> CAPABILITIES = Collections.unmodifiableSet(
            EnumSet.of(Capability.TAG_BROWSE,
                    Capability.CURRENT_VALUE,
                    Capability.AGGREGATED_HISTORY));

    /**
     * 返回驱动类型标识。
     *
     * <p>该值必须与配置中 {@code type: hls} 一致，也是 SPI 查找与日志中
     * 使用的短名；不随实例配置变化。</p>
     *
     * @return 固定字符串 {@code "hls"}
     */
    @Override
    public String type() {
        return "hls";
    }

    /**
     * 返回该驱动支持的能力集合。
     *
     * <p>返回的是静态不可修改集合，可安全缓存与并发读取。调用方在使用
     * 可选功能前应先通过 {@code CapabilitySet#supports} 或
     * {@code contains} 判断，而不是依赖异常。</p>
     *
     * @return 非 {@code null} 的不可修改能力集合，包含
     *         {@link Capability#TAG_BROWSE}、{@link Capability#CURRENT_VALUE}、
     *         {@link Capability#AGGREGATED_HISTORY}
     */
    @Override
    public Set<Capability> capabilities() {
        return CAPABILITIES;
    }

    /**
     * 校验单实例配置是否可用于 HLS 驱动。
     *
     * <p>实现方式是以宽松安全策略构造一次 {@link HlsConfig}：结构与取值
     * （地址格式、认证信息、超时、重试次数等）不合法会直接抛出异常，
     * 但明文 http 不在此处拦截，避免“校验阶段通过、创建阶段却因安全
     * 策略失败”造成的困惑。</p>
     *
     * <p>本方法不发起网络请求，也不修改传入的配置对象。</p>
     *
     * @param config 待校验的单实例配置，不能为 {@code null}
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbConfigurationException
     *         当配置缺失或取值非法时（例如 {@code base-url} 缺失/协议不支持、
     *         认证信息不完整、超时不为正、重试次数小于 1）
     */
    @Override
    public void validate(TsdbInstanceConfig config) {
        SecurityConfig permissive = new SecurityConfig();
        permissive.setAllowInsecureTransport(true);
        new HlsConfig("validate", config, permissive);
    }

    /**
     * 按配置创建 HLS 客户端。
     *
     * <p>创建过程包含三步：从驱动上下文取出实例名与根配置中的安全选项、
     * 构造 {@link HlsConfig}（会完整校验配置）、组装 {@link HlsClient}。
     * 此方法不预先探测服务端连通性，连接失败会在首次实际请求时以
     * {@code TsdbConnectionException} 暴露。</p>
     *
     * @param config  单实例配置，不能为 {@code null}
     * @param context 驱动上下文，提供实例名与根配置；不能为 {@code null}，
     *                其 {@code getRootConfig()} 允许返回 {@code null}
     *                （此时按默认安全策略处理）
     * @return 非 {@code null} 的 HLS 客户端，调用方负责在结束时关闭
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbConfigurationException
     *         当配置不合法，或明文 http 未被根配置的安全策略允许时
     */
    @Override
    public TsdbClient create(TsdbInstanceConfig config, DriverContext context) {
        SecurityConfig security = context.getRootConfig() == null
                ? new SecurityConfig()
                : context.getRootConfig().getSecurity();
        HlsConfig hlsConfig = new HlsConfig(context.getInstanceName(), config, security);
        return new HlsClient(context.getInstanceName(), hlsConfig);
    }
}
