package io.github.forget_the_bright.tsdb.iotdb;

import io.github.forget_the_bright.tsdb.common.api.TsdbClient;
import io.github.forget_the_bright.tsdb.common.config.SecurityConfig;
import io.github.forget_the_bright.tsdb.common.config.TsdbInstanceConfig;
import io.github.forget_the_bright.tsdb.common.model.Capability;
import io.github.forget_the_bright.tsdb.common.spi.DriverContext;
import io.github.forget_the_bright.tsdb.common.spi.TsdbDriver;
import io.github.forget_the_bright.tsdb.iotdb.internal.transport.IotdbHttpClient;
import io.github.forget_the_bright.tsdb.iotdb.internal.transport.IotdbJdbcQueryExecutor;
import io.github.forget_the_bright.tsdb.iotdb.internal.transport.IotdbNativeQueryExecutor;
import io.github.forget_the_bright.tsdb.iotdb.internal.transport.IotdbQueryExecutor;
import io.github.forget_the_bright.tsdb.iotdb.internal.query.IotdbQueryService;
import io.github.forget_the_bright.tsdb.iotdb.internal.transport.IotdbRestApi;
import io.github.forget_the_bright.tsdb.iotdb.internal.transport.IotdbRestQueryExecutor;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.TagPathMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Apache IoTDB 的驱动服务提供者入口（SPI），基于 REST v2 与树模型实现。
 *
 * <p><b>职责</b>：向公共层注册 {@code "iotdb"} 这一驱动类型，声明静态能力集，
 * 在启动阶段校验实例配置，并按需装配出一套可用的 {@link TsdbClient}。
 * 本类是 {@code ServiceLoader} 的加载点，实例由框架创建，调用方通常不直接
 * {@code new} 它。</p>
 *
 * <p><b>设计要点</b>：</p>
 * <ul>
 *   <li>能力集是类级常量，与具体实例配置无关，因此可以回答“这个驱动整体能做什么”；
 *       单个客户端是否可写由 {@link IotdbClient#capabilities()} 结合安全开关决定；</li>
 *   <li>{@link #validate} 只做结构校验、不建立连接，可在配置加载阶段安全调用；
 *       它使用一份临时放宽的安全配置（允许明文传输），以免仅仅因为
 *       {@code http://} 地址就让“配置格式对不对”的校验失败；真正的安全约束
 *       仍由 {@link #create} 按全局配置执行；</li>
 *   <li>{@link #create} 每次调用都新建配置快照、HTTP 客户端、REST 客户端与
 *       {@link IotdbClient}，不做实例缓存，避免不同实例之间互相影响。</li>
 * </ul>
 *
 * <p><b>线程安全</b>：本类无可变状态，能力集为不可变集合，因此可被多线程并发调用；
 * 每次 {@code create} 产生独立的客户端对象。</p>
 *
 * <p><b>与公共层/原生层的关系</b>：实现公共层 {@link TsdbDriver} 契约；
 * 向下依次装配 {@link IotdbConfig}、{@link IotdbHttpClient}、
 * {@link IotdbRestApi} 与 {@link IotdbClient}，是唯一了解这套对象图的入口。</p>
 *
 * <p><b>限制</b>：{@code validate} 不验证网络可达性与凭据有效性；
 * 驱动不支持连接池复用、会话保持或批量写入优化。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class IotdbDriver implements TsdbDriver {

    /**
     * 驱动声明的静态能力集，非 {@code null} 且不可修改：
     * 位号浏览、当前值、原始历史、聚合历史、插值历史、等间隔采样与累计量差分。
     * 写入能力不在此声明，因为写开关是实例级安全策略，需在 {@link IotdbClient}
     * 构造时按配置追加；插值、采样与累计差分还额外依赖客户端装配了统一 SQL
     * 查询服务——{@link #create} 总是注入它，只有显式配置
     * {@code options.require-query-service=false} 走兼容构造器时才会缺少这三项。
     */
    private static final Set<Capability> CAPABILITIES = Collections.unmodifiableSet(
            EnumSet.of(Capability.TAG_BROWSE,
                    Capability.CURRENT_VALUE,
                    Capability.RAW_HISTORY,
                    Capability.AGGREGATED_HISTORY,
                    Capability.INTERPOLATED_HISTORY,
                    Capability.SAMPLED_HISTORY,
                    Capability.CUMULATIVE_DELTA));

    private static final Logger LOG = LoggerFactory.getLogger(IotdbDriver.class);

    /**
     * 返回驱动类型标识。
     *
     * <p>固定为 {@code "iotdb"}，用于与配置中的 {@code type} 字段匹配，
     * 也是 {@code ServiceLoader} 选择本实现的依据。</p>
     *
     * @return 常量 {@code "iotdb"}，非 {@code null}
     */
    @Override
    public String type() {
        return "iotdb";
    }

    /**
     * 返回驱动整体支持的静态能力集。
     *
     * <p>该集合与实例无关，且不包含 {@code WRITE}；实际客户端的能力集
     * 会在其构造时按 {@code tsdb.security.allow-write} 追加写能力。</p>
     *
     * @return 非 {@code null} 的不可变能力集合
     */
    @Override
    public Set<Capability> capabilities() {
        return CAPABILITIES;
    }

    /**
     * 校验实例配置是否可用于创建 IoTDB 客户端。
     *
     * <p>实现方式是尝试构造一份 {@link IotdbConfig}：只做必填项、URL scheme、
     * 认证信息、超时与重试参数的格式校验，不发起任何网络请求。校验时临时
     * 允许明文传输，因此 {@code http://} 地址在这里不会报错；
     * 若全局安全配置禁止明文，最终仍会在 {@link #create} 阶段被拒绝。</p>
     *
     * @param config 待校验的实例配置，不能为 {@code null}
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbConfigurationException
     *         当配置缺失或非法时（异常信息会指明具体字段）
     */
    @Override
    public void validate(TsdbInstanceConfig config) {
        SecurityConfig permissive = new SecurityConfig();
        permissive.setAllowInsecureTransport(true);
        new IotdbConfig("validate", config, permissive);
    }

    /**
     * 按实例配置创建 IoTDB 客户端。
     *
     * <p>装配顺序：从 {@link DriverContext} 取实例名与全局安全配置
     * （根配置缺失时退回默认安全配置）→ 构造 {@link IotdbConfig} →
     * 构造 {@link IotdbHttpClient} → 构造 {@link IotdbRestApi} →
     * 包装为 {@link IotdbClient}。全程不发起网络请求，连接在首次数据访问时建立。</p>
     *
     * @param config  实例配置，不能为 {@code null}
     * @param context 驱动上下文，提供实例名与根配置，不能为 {@code null}
     * @return 非 {@code null} 的 IoTDB 客户端
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbConfigurationException
     *         当配置非法，或全局安全配置禁止明文传输而地址为 {@code http://} 时
     */
    @Override
    public TsdbClient create(TsdbInstanceConfig config, DriverContext context) {
        SecurityConfig security = context.getRootConfig() == null
                ? new SecurityConfig()
                : context.getRootConfig().getSecurity();
        IotdbConfig iotdbConfig = new IotdbConfig(context.getInstanceName(), config, security);
        IotdbHttpClient http = new IotdbHttpClient(iotdbConfig);
        IotdbRestApi api = new IotdbRestApi(iotdbConfig, http);
        IotdbQueryExecutor executor = executor(iotdbConfig);
        return new IotdbClient(iotdbConfig, api, new IotdbQueryService(iotdbConfig, executor),
                tagPathMapper(iotdbConfig));
    }

    /**
     * 按配置装载位号映射表。
     *
     * <p>文件读取放在装配阶段而不是 {@link IotdbConfig} 构造阶段，是为了保持
     * 配置类“只做结构校验、不发起任何外部访问”的语义（{@code validate} 会在
     * 配置加载期被调用，那时不应触碰文件系统）。</p>
     *
     * <p>装载失败（文件缺失、映射值不是完整 {@code root.} 路径）直接抛异常，
     * 让应用在启动期就失败：IoTDB 对不存在的序列返回空结果，带着坏映射表启动
     * 只会让报表静默缺数。</p>
     *
     * @param config 驱动配置，不能为 {@code null}
     * @return 非 {@code null} 的映射器；未配置 {@code tag-mapping-file} 时是空映射器
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbDataException
     *         文件不可读或存在非法映射值
     */
    private static TagPathMapper tagPathMapper(IotdbConfig config) {
        String file = config.getTagMappingFile();
        if (file == null) {
            LOG.info("IoTDB tag mapping file not configured, every tag falls back to prefix completion: "
                    + "instance={}, defaultDatabase={}", config.getInstanceName(), config.getDefaultDatabase());
            return TagPathMapper.none();
        }
        TagPathMapper mapper = TagPathMapper.fromPropertiesFile(file);
        LOG.info("IoTDB tag mapping loaded: instance={}, file={}, entries={}, defaultDatabase={}",
                config.getInstanceName(), file, mapper.size(), config.getDefaultDatabase());
        return mapper;
    }

    private static IotdbQueryExecutor executor(IotdbConfig config) {
        switch (config.getTransport()) {
            case NATIVE:
                return new IotdbNativeQueryExecutor(config);
            case JDBC:
                return new IotdbJdbcQueryExecutor(config);
            case REST:
            default:
                return new IotdbRestQueryExecutor(config);
        }
    }
}
