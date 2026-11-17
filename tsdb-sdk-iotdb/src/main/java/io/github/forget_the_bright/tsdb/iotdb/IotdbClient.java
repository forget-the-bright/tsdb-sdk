package io.github.forget_the_bright.tsdb.iotdb;

import io.github.forget_the_bright.tsdb.common.api.CurrentValueReader;
import io.github.forget_the_bright.tsdb.common.api.DeltaReader;
import io.github.forget_the_bright.tsdb.common.api.HistoryReader;
import io.github.forget_the_bright.tsdb.common.api.TagCatalog;
import io.github.forget_the_bright.tsdb.common.api.TsdbClient;
import io.github.forget_the_bright.tsdb.common.api.WriteSession;
import io.github.forget_the_bright.tsdb.common.config.TimeConfig;
import io.github.forget_the_bright.tsdb.common.exception.TsdbConfigurationException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException;
import io.github.forget_the_bright.tsdb.common.model.Capability;
import io.github.forget_the_bright.tsdb.common.model.CapabilitySet;
import io.github.forget_the_bright.tsdb.common.model.FillPolicy;
import io.github.forget_the_bright.tsdb.common.model.HealthStatus;
import io.github.forget_the_bright.tsdb.common.model.QueryMode;
import io.github.forget_the_bright.tsdb.common.model.Sample;
import io.github.forget_the_bright.tsdb.iotdb.internal.query.IotdbQueryService;
import io.github.forget_the_bright.tsdb.iotdb.internal.transport.IotdbRestApi;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.IotdbTagResolver;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.TagPathMapper;

import java.util.EnumSet;
import io.github.forget_the_bright.tsdb.iotdb.internal.adapter.IotdbCurrentValueAdapter;
import io.github.forget_the_bright.tsdb.iotdb.internal.adapter.IotdbDeltaAdapter;
import io.github.forget_the_bright.tsdb.iotdb.internal.adapter.IotdbHistoryAdapter;
import io.github.forget_the_bright.tsdb.iotdb.internal.adapter.IotdbSeriesAssembler;
import io.github.forget_the_bright.tsdb.iotdb.internal.adapter.IotdbSupport;
import io.github.forget_the_bright.tsdb.iotdb.internal.adapter.IotdbTagCatalogAdapter;
import io.github.forget_the_bright.tsdb.iotdb.internal.adapter.IotdbWriteAdapter;

/**
 * Apache IoTDB 客户端：把公共层 {@link TsdbClient} 契约适配到 IoTDB REST v2（树模型）接口。
 *
 * <p><b>职责</b>：本类是门户，负责能力声明、健康检查、时间配置、映射统计、
 * {@code unwrap} 与生命周期；位号目录、当前值、历史查询、累计量差分与写入分别
 * 委托给 {@link IotdbTagCatalogAdapter}、{@link IotdbCurrentValueAdapter}、
 * {@link IotdbHistoryAdapter}、{@link IotdbDeltaAdapter} 与
 * {@link IotdbWriteAdapter}。适配器都在本包内可见，不扩大公开面。</p>
 *
 * <p>HTTP 细节、SQL 拼接和 JSON 解析下沉到 {@link IotdbRestApi}，厂商结构体由
 * {@link io.github.forget_the_bright.tsdb.iotdb.internal.IotdbModels} 承载，
 * 再经 {@link IotdbMapper} 归一化为公共模型。</p>
 *
 * <p><b>能力集</b>在构造时一次算好：固定包含 {@code TAG_BROWSE}、
 * {@code CURRENT_VALUE}、{@code RAW_HISTORY}、{@code AGGREGATED_HISTORY}；
 * 装配了统一 SQL 查询服务时追加 {@code SAMPLED_HISTORY}、
 * {@code INTERPOLATED_HISTORY}、{@code CUMULATIVE_DELTA}；仅当
 * {@link IotdbConfig#isWriteAllowed()} 为 {@code true} 时追加 {@code WRITE}。
 * 名称通配符过滤与分页在客户端完成，因此不声明 {@code TAG_SEARCH}。</p>
 *
 * <p><b>线程安全</b>：所有字段均为 {@code final} 且构造后不再修改；适配器不持有
 * 可变状态；{@link IotdbRestApi} 每次请求独立建立连接。因此本类可被多个线程
 * 并发调用。</p>
 *
 * <p><b>限制</b>：</p>
 * <ul>
 *   <li>{@link QueryMode#SAMPLED}、{@link QueryMode#INTERPOLATED} 与累计增量
 *       依赖统一 SQL 查询服务；用不提供该服务的旧构造器创建时按
 *       {@link TsdbUnsupportedException} 明确拒绝，绝不降级成别的模式。
 *       而这样的精简实例默认根本创建不出来——{@code options.require-query-service}
 *       缺省为 {@code true}，构造期即抛 {@link TsdbConfigurationException}；</li>
 *   <li>分桶聚合仅支持 {@code AVG/MIN/MAX/SUM/COUNT/FIRST/LAST}；
 *       {@code INTERVAL_SUM} 有专用的整窗“末值减首值”单点路径；
 *       {@code RANGE}、{@code BOUND} 抛 {@link TsdbUnsupportedException}；</li>
 *   <li>位号目录不支持按工程单位过滤（IoTDB 没有单位元数据）；</li>
 *   <li>查询按 {@code options.query-batch-scope} 的档位合批，一条语句只覆盖同一分组；
 *       多列 RAW 的 {@code LIMIT} 是“合并时间戳行数”预算，不足的位号逐位号补采；</li>
 *   <li>失败分两种粒度：位号解析失败只影响该位号；一条批量语句失败影响整块，
 *       块内位号带 {@code vendorErrorScope=chunk}；</li>
 *   <li>写入逐条顺序提交，不具备跨位号事务性；IoTDB 不返回逐点质量码。</li>
 * </ul>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class IotdbClient implements TsdbClient {

    /** 驱动配置快照，非 {@code null}。 */
    private final IotdbConfig config;

    /** 原生 REST v2 客户端，非 {@code null}。 */
    private final IotdbRestApi api;

    /** 三通道统一查询服务；精简实例才为 {@code null}。 */
    private final IotdbQueryService queryService;

    /** 位号路径解析器。 */
    private final IotdbTagResolver tagResolver;

    /** 能力集快照，构造时计算一次。 */
    private final CapabilitySet capabilities;

    /** 适配器共享的配置、客户端、查询服务与解析器。 */
    private final IotdbSupport support;

    /** 位号目录子接口，长期复用。 */
    private final TagCatalog tagCatalog;

    /** 当前值读取子接口，长期复用。 */
    private final CurrentValueReader currentValues;

    /** 历史读取子接口，长期复用。 */
    private final HistoryReader history;

    /** 累计量差分读取器；精简实例为 {@code null}，退回公共层默认实现。 */
    private final DeltaReader deltaHistory;

    /** 写入会话子接口，长期复用。 */
    private final WriteSession writeSession;

    /**
     * 创建只有 REST 直连能力的客户端（不装配统一 SQL 查询服务）。
     *
     * <p>默认（{@code options.require-query-service} 缺省为 {@code true}）在构造期
     * 直接抛 {@link TsdbConfigurationException}；确实需要这条受限路径时，显式配置
     * {@code require-query-service=false} 再调用本构造器。</p>
     *
     * @param config 驱动配置快照，不能为 {@code null}
     * @param api    原生 REST 客户端，不能为 {@code null}
     * @throws TsdbConfigurationException 当配置未关闭 {@code require-query-service} 时
     */
    public IotdbClient(IotdbConfig config, IotdbRestApi api) {
        this(config, api, null);
    }

    /**
     * 创建带三通道查询服务的客户端（不使用位号映射表）。
     *
     * @param config       驱动配置快照，不能为 {@code null}
     * @param api          原生 REST 客户端，不能为 {@code null}
     * @param queryService 统一 SQL 查询服务；传 {@code null} 前必须显式放行
     * @throws TsdbConfigurationException 当 {@code queryService} 为 {@code null}
     *                                    且配置未关闭 {@code require-query-service} 时
     */
    public IotdbClient(IotdbConfig config, IotdbRestApi api, IotdbQueryService queryService) {
        this(config, api, queryService, null);
    }

    /**
     * 创建带位号映射表的客户端。
     *
     * <p>位号到 IoTDB 规范路径的解析委托给 {@link TagPathMapper}：命中映射表时
     * 直接使用映射值；未命中时退回“补默认库”的兜底清洗，并累计映射未命中统计。</p>
     *
     * @param config        驱动配置快照，不能为 {@code null}
     * @param api           原生 REST 客户端，不能为 {@code null}
     * @param queryService  统一 SQL 查询服务；{@code null} 表示只保留 REST 直连能力
     * @param tagPathMapper 位号映射表；为 {@code null} 时不启用映射
     * @throws TsdbConfigurationException 当 {@code queryService} 为 {@code null}
     *                                    且配置未关闭 {@code require-query-service} 时
     */
    public IotdbClient(IotdbConfig config, IotdbRestApi api, IotdbQueryService queryService,
                       TagPathMapper tagPathMapper) {
        if (queryService == null && config.isRequireQueryService()) {
            throw new TsdbConfigurationException("IoTDB client requires an IotdbQueryService; sampled, "
                    + "interpolated and interval-sum history are only implemented on that path. "
                    + "Wire it in, or set options.require-query-service=false to accept the reduced "
                    + "REST-direct client");
        }
        this.config = config;
        this.api = api;
        this.queryService = queryService;
        this.tagResolver = new IotdbTagResolver(config, tagPathMapper);
        EnumSet<Capability> caps = EnumSet.of(Capability.TAG_BROWSE,
                Capability.CURRENT_VALUE,
                Capability.RAW_HISTORY,
                Capability.AGGREGATED_HISTORY);
        if (queryService != null) {
            caps.add(Capability.SAMPLED_HISTORY);
            caps.add(Capability.INTERPOLATED_HISTORY);
            caps.add(Capability.CUMULATIVE_DELTA);
        }
        if (config.isWriteAllowed()) {
            caps.add(Capability.WRITE);
        }
        this.capabilities = CapabilitySet.of(caps);
        this.support = new IotdbSupport(config, api, queryService, tagResolver);
        IotdbSeriesAssembler assembler = new IotdbSeriesAssembler(support);
        this.tagCatalog = new IotdbTagCatalogAdapter(support);
        this.currentValues = new IotdbCurrentValueAdapter(support);
        this.history = new IotdbHistoryAdapter(support, assembler);
        this.deltaHistory = queryService == null ? null : new IotdbDeltaAdapter(support);
        this.writeSession = new IotdbWriteAdapter(support);
    }

    /**
     * 返回实例的逻辑名称。
     *
     * @return 实例名；创建时传入，可能为 {@code null}
     */
    @Override
    public String instanceName() {
        return config.getInstanceName();
    }

    /**
     * 返回驱动类型标识。
     *
     * @return 常量 {@code "iotdb"}，非 {@code null}
     */
    @Override
    public String type() {
        return "iotdb";
    }

    /**
     * 返回构造时计算好的能力集快照。
     *
     * @return 非 {@code null} 的不可变能力集合
     */
    @Override
    public CapabilitySet capabilities() {
        return capabilities;
    }

    /**
     * 返回位号目录子接口。
     *
     * @return 非 {@code null} 的位号目录接口
     */
    @Override
    public TagCatalog tagCatalog() {
        return tagCatalog;
    }

    /**
     * 返回当前值读取子接口。
     *
     * @return 非 {@code null} 的当前值读取接口
     */
    @Override
    public CurrentValueReader currentValues() {
        return currentValues;
    }

    /**
     * 返回历史数据读取子接口。
     *
     * @return 非 {@code null} 的历史读取接口
     */
    @Override
    public HistoryReader history() {
        return history;
    }

    /**
     * 返回累计量差分读取器。
     *
     * <p>差分依赖统一 SQL 查询服务：先按粒度取每个桶的末值，再取起点前最后一个
     * 值作为基线，最后在内存中相邻相减。位号按 {@code query-batch-scope} 切块，
     * 一个块两条 SQL。精简实例退回公共层默认实现，抛出“不支持累计量差分”的异常。</p>
     *
     * @return 非 {@code null} 的差分读取器
     */
    @Override
    public DeltaReader deltaHistory() {
        if (deltaHistory == null) {
            return TsdbClient.super.deltaHistory();
        }
        return deltaHistory;
    }

    /**
     * 返回写入会话。
     *
     * <p>是否可写由 {@link WriteSession#isWritable()} 表示，它直接读取
     * {@link IotdbConfig#isWriteAllowed()}。未开启写入时调用
     * {@link WriteSession#write(java.util.List)} 会抛 {@link TsdbUnsupportedException}。</p>
     *
     * @return 非 {@code null} 的写入会话；只读实例返回的会话不可写
     */
    @Override
    public WriteSession writeSession() {
        return writeSession;
    }

    /**
     * 执行一次轻量健康探测。
     *
     * <p>REST 传输走 {@link IotdbRestApi#checkHealth()}；JDBC/native 传输通过
     * 查询服务取版本号。探测失败不抛异常，折算为 {@code DOWN}。</p>
     *
     * @return 非 {@code null} 的健康状态，包含状态值与往返耗时
     */
    @Override
    public HealthStatus health() {
        if (queryService == null || config.getTransport() == IotdbTransport.REST) {
            return api.checkHealth();
        }
        long started = System.nanoTime();
        try {
            String version = queryService.serverVersion();
            long elapsed = (System.nanoTime() - started) / 1_000_000L;
            if (version == null || "unknown".equals(version)) {
                return HealthStatus.down("IoTDB " + config.getTransport().name().toLowerCase()
                        + " health check did not return a version", elapsed);
            }
            return HealthStatus.up(elapsed).withTimeFormat(config.getTimeConfig());
        } catch (RuntimeException e) {
            long elapsed = (System.nanoTime() - started) / 1_000_000L;
            return HealthStatus.down("IoTDB health check failed: " + e.getMessage(), elapsed);
        }
    }

    /**
     * 返回位号映射统计快照。
     *
     * <p>映射表命中与兜底清洗分别累计计数，并保留去重后的兜底位号清单。
     * 切换目标是 {@code fallbackTotal == 0}。</p>
     *
     * @return 非 {@code null} 的统计快照
     */
    public IotdbTagResolver.TagMappingStats tagMappingStats() {
        return tagResolver.mappingStats();
    }

    /**
     * 返回本实例的时间解析与展示配置。
     *
     * @return 非 {@code null} 的不可变时间配置
     */
    public TimeConfig timeConfig() {
        return config.getTimeConfig();
    }

    /**
     * 按本实例配置解析一个时间文本。
     *
     * @param text 时间文本，可为 {@code null}
     * @return 解析后的绝对时刻；空白返回 {@code null}
     */
    public java.time.Instant parseTimestamp(String text) {
        return timeConfig().parseResponse(text);
    }

    /**
     * 按本实例配置格式化一个绝对时刻。
     *
     * @param instant 绝对时刻，可为 {@code null}
     * @return 格式化文本；入参为 {@code null} 时返回 {@code null}
     */
    public String formatTimestamp(java.time.Instant instant) {
        return timeConfig().formatResponse(instant);
    }

    /**
     * 返回给定时刻所属的班次窗口。
     *
     * <p>时区取 {@code options.zone}（默认东八区），换班时刻取
     * {@code options.shift-hour}（默认 08:00），窗口左闭右开、长 24 小时。</p>
     *
     * @param at 班次内的任意时刻，不能为 {@code null}
     * @return 该时刻所属的班次窗口，非 {@code null}
     * @throws IllegalArgumentException 当 {@code at} 为 {@code null} 时
     */
    public IotdbConfig.ShiftWindow shiftWindow(java.time.Instant at) {
        return config.shiftWindow(at);
    }

    /**
     * 取出本驱动持有的原生对象，用于访问公共层未抽象的能力。
     *
     * <p>按顺序匹配：本类、{@link IotdbRestApi}、{@link IotdbQueryService}、
     * {@link IotdbTagResolver}、{@link IotdbConfig}。使用后代码不再具备跨厂商
     * 可移植性。</p>
     *
     * @param nativeType 期望的原生类型，不能为 {@code null}
     * @param <T>        原生类型
     * @return 非 {@code null} 的原生对象
     * @throws IllegalArgumentException 当 {@code nativeType} 为 {@code null} 时
     * @throws TsdbUnsupportedException 当本驱动不暴露该原生类型时
     */
    @SuppressWarnings("unchecked")
    @Override
    public <T> T unwrap(Class<T> nativeType) {
        if (nativeType == null) {
            throw new IllegalArgumentException("nativeType must not be null");
        }
        if (nativeType.isInstance(this)) {
            return (T) this;
        }
        if (nativeType.isInstance(api)) {
            return (T) api;
        }
        if (queryService != null && nativeType.isInstance(queryService)) {
            return (T) queryService;
        }
        if (nativeType.isInstance(tagResolver)) {
            return (T) tagResolver;
        }
        if (nativeType.isInstance(config)) {
            return (T) config;
        }
        throw new TsdbUnsupportedException("IoTDB client does not expose " + nativeType.getName());
    }

    /**
     * 关闭客户端。
     *
     * <p>REST 传输不维护连接池或后台线程，因此空操作；装配了查询服务时关闭它。
     * 重复调用无副作用。</p>
     */
    @Override
    public void close() {
        if (queryService != null) {
            queryService.close();
        }
    }
}