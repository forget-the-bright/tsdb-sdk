package io.github.forget_the_bright.tsdb.common.spi;

import io.github.forget_the_bright.tsdb.common.api.TsdbClient;
import io.github.forget_the_bright.tsdb.common.config.TsdbInstanceConfig;
import io.github.forget_the_bright.tsdb.common.model.Capability;

import java.util.Set;

/**
 * 服务提供者接口（SPI）：每种时序库实现一次，负责声明自身类型与能力，并按配置创建客户端。
 *
 * <p>实现类必须满足以下约定，{@code TsdbManager} 依赖它们完成驱动查找与连接管理：</p>
 * <ul>
 *   <li>{@link #type()} 返回稳定、全小写的类型标识（如 {@code hls}、{@code ge}、{@code iotdb}），
 *       它是 {@code tsdb.instances.*.type} 的匹配键；同名驱动只保留一个，后者覆盖前者。</li>
 *   <li>{@link #validate(TsdbInstanceConfig)} 必须只做配置校验、不发起网络连接，
 *       以便框架启动阶段快速暴露配置错误。</li>
 *   <li>{@link #create(TsdbInstanceConfig, DriverContext)} 返回的客户端由调用方（{@code TsdbManager}）
 *       负责关闭；{@code TsdbManager} 同一时刻只保留一个活动客户端。</li>
 *   <li>实现类应有无参构造器，否则 {@link java.util.ServiceLoader} 无法实例化。</li>
 * </ul>
 *
 * <p>发现机制：{@code TsdbManager} 默认通过 {@link java.util.ServiceLoader} 从
 * {@code META-INF/services/io.github.forget_the_bright.tsdb.common.spi.TsdbDriver}
 * 读取实现，也可以由业务显式注册。驱动实现本身建议无状态（状态放在客户端里），
 * 从而被多个实例安全复用。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public interface TsdbDriver {

    /**
     * 返回驱动类型标识，用于匹配 {@code tsdb.instances.*.type}，例如 {@code "hls"}。
     *
     * <p>该值会被直接用作 Map 的键：必须非 {@code null}、区分大小写且全局唯一；
     * 返回 {@code null} 的驱动会被 {@code TsdbManager} 静默忽略。实现应返回常量，
     * 不要依赖配置或外部状态。</p>
     *
     * @return 稳定的类型标识，非 {@code null}
     */
    String type();

    /**
     * 返回驱动静态声明的能力集合，用于调用方在连接前判断功能是否可用。
     *
     * <p>例如不支持历史明细查询的驱动不应包含 {@code RAW_HISTORY}。约定返回不可变集合
     * （如 {@code Collections.unmodifiableSet}），避免调用方误改；本方法会被频繁调用，
     * 实现宜缓存结果。</p>
     *
     * @return 能力集合，建议不可变且非 {@code null}
     */
    Set<Capability> capabilities();

    /**
     * 校验实例配置，不打开任何网络连接。
     *
     * <p>默认实现什么都不做，适合配置项极少或校验成本高的驱动；推荐覆盖本方法，
     * 在 {@code create} 之前就把地址格式错误、必填凭据缺失、超时非正数等问题以
     * {@link io.github.forget_the_bright.tsdb.common.exception.TsdbConfigurationException}
     * 抛出来，让错误发生在启动阶段而不是首次查询时。</p>
     *
     * @param config 待校验的实例配置，通常非 {@code null}；实现方应自行判空
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbConfigurationException
     *         配置缺失或取值非法时抛出（由实现决定）
     */
    default void validate(TsdbInstanceConfig config) {
    }

    /**
     * 按配置创建客户端（可能建立连接）。
     *
     * <p>返回值的所有权转移给调用方：使用完毕必须调用 {@link TsdbClient#close()}，
     * 否则会泄漏连接或后台线程。实现应保证失败时不留残余资源——构造过程中的异常
     * 不应留下已打开的连接。</p>
     *
     * @param config  实例配置，通常已通过 {@link #validate(TsdbInstanceConfig)} 校验，非 {@code null}
     * @param context 运行时上下文，提供实例名、根配置与指标出口，非 {@code null}
     * @return 新建的客户端，非 {@code null}，由调用方负责关闭
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbConfigurationException
     *         配置非法时抛出
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbConnectionException
     *         建连或认证失败时抛出（由具体驱动决定）
     */
    TsdbClient create(TsdbInstanceConfig config, DriverContext context);

    /**
     * 可选的“连接后能力探测”，用于发现服务端版本差异带来的能力变化。
     *
     * <p>默认直接返回 {@link #capabilities()}（静态声明）。若驱动实现探测，应保证
     * 探测失败不影响客户端可用性——通常返回静态集合或保守子集，而不是抛异常。
     * 本方法可能在每次建立连接后被调用一次，不应做重量级操作。</p>
     *
     * @param context 运行时上下文，非 {@code null}
     * @return 实际可用的能力集合，非 {@code null}
     */
    default Set<Capability> probe(DriverContext context) {
        return capabilities();
    }
}
