package io.github.forget_the_bright.tsdb.pspace;

import io.github.forget_the_bright.tsdb.common.api.CurrentValueReader;
import io.github.forget_the_bright.tsdb.common.api.DeltaReader;
import io.github.forget_the_bright.tsdb.common.api.HistoryReader;
import io.github.forget_the_bright.tsdb.common.api.TagCatalog;
import io.github.forget_the_bright.tsdb.common.api.TsdbClient;
import io.github.forget_the_bright.tsdb.common.api.WriteSession;
import io.github.forget_the_bright.tsdb.common.config.TimeConfig;
import io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException;
import io.github.forget_the_bright.tsdb.common.model.Capability;
import io.github.forget_the_bright.tsdb.common.model.CapabilitySet;
import io.github.forget_the_bright.tsdb.common.model.HealthStatus;
import io.github.forget_the_bright.tsdb.pspace.internal.PspaceMapper;
import io.github.forget_the_bright.tsdb.pspace.internal.PspaceNativeApi;
import io.github.forget_the_bright.tsdb.pspace.internal.PspaceTagResolver;
import io.github.forget_the_bright.tsdb.pspace.internal.adapter.PspaceCurrentValueAdapter;
import io.github.forget_the_bright.tsdb.pspace.internal.adapter.PspaceDeltaAdapter;
import io.github.forget_the_bright.tsdb.pspace.internal.adapter.PspaceHistoryAdapter;
import io.github.forget_the_bright.tsdb.pspace.internal.adapter.PspaceSupport;
import io.github.forget_the_bright.tsdb.pspace.internal.adapter.PspaceTagCatalogAdapter;
import io.github.forget_the_bright.tsdb.pspace.internal.adapter.PspaceWriteAdapter;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 力控 pSpace 在公共 {@link TsdbClient} 门面上的实现。
 *
 * <p>本类是门户：能力声明、时间配置、健康检查、{@code unwrap} 与生命周期留在
 * 这里，位号目录、当前值、历史查询、累计量差分与写入分别委托给
 * {@link PspaceTagCatalogAdapter}、{@link PspaceCurrentValueAdapter}、
 * {@link PspaceHistoryAdapter}、{@link PspaceDeltaAdapter} 与
 * {@link PspaceWriteAdapter}。适配器位于 {@code internal.adapter}，不扩大公开面。</p>
 *
 * <p>能力边界：声明 {@link Capability#TAG_BROWSE}、{@link Capability#TAG_SEARCH}、
 * {@link Capability#CURRENT_VALUE}、{@link Capability#AGGREGATED_HISTORY}、
 * {@link Capability#INTERPOLATED_HISTORY}、{@link Capability#SAMPLED_HISTORY}、
 * {@link Capability#CUMULATIVE_DELTA} 与 {@link Capability#WRITE}。原始历史是基线能力；
 * 趋势、历史删除、位号 CRUD（走原生 API）与报警订阅不在公共层声明。</p>
 *
 * <p>写入受根配置 {@code security.allow-write} 门禁：未开启时
 * {@code writeSession().isWritable()} 返回 {@code false}，调用 {@code write}
 * 抛 {@link TsdbUnsupportedException}，不会静默丢弃数据。</p>
 *
 * <p>缓存未热：服务刚启动时目录与实时值可能取不到。驱动在首次读空或命中
 * “缓存未热”错误时会自动 POST 一次 {@code /Refresh} 并重试原请求一次
 * （受 {@code refresh-min-interval-ms} 限速），调用方无需手工处理。</p>
 *
 * <p>线程安全：字段全部为 {@code final}（关闭标记使用 {@link AtomicBoolean}），
 * 适配器无状态，可被多线程并发使用。{@link #close()} 幂等。</p>
 *
 * <p>与原生层的关系：{@link #unwrap(Class)} 支持取回本客户端自身、
 * {@link PspaceNativeApi}（23 个 REST 端点、{@code refresh()} 与 WebSocket 订阅）
 * 与 {@link PspaceConfig}。</p>
 *
 * @author wanghao
 * @since 0.1.2
 */
public final class PspaceClient implements TsdbClient {

    /** 能力快照：构造后不再变化。 */
    private static final CapabilitySet CAPABILITIES = CapabilitySet.of(
            Capability.TAG_BROWSE,
            Capability.TAG_SEARCH,
            Capability.CURRENT_VALUE,
            Capability.AGGREGATED_HISTORY,
            Capability.INTERPOLATED_HISTORY,
            Capability.SAMPLED_HISTORY,
            Capability.CUMULATIVE_DELTA,
            Capability.WRITE);

    private final String instanceName;
    private final PspaceConfig config;
    private final PspaceNativeApi nativeApi;
    private final PspaceMapper mapper;
    private final PspaceSupport support;

    private final TagCatalog tagCatalog;
    private final CurrentValueReader currentValues;
    private final HistoryReader history;
    private final DeltaReader deltaHistory;
    private final WriteSession writeSession;

    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 创建 pSpace 客户端。
     *
     * <p>构造过程不做网络 I/O，也不触发 {@code /Refresh}；首次真实查询需要时
     * 才由自动刷新机制处理。</p>
     *
     * @param instanceName 实例逻辑名，用于日志与异常上下文；允许为 {@code null}
     * @param config       已校验的配置，不能为 {@code null}
     */
    public PspaceClient(String instanceName, PspaceConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        this.instanceName = instanceName;
        this.config = config;
        this.mapper = new PspaceMapper(config.getTimeConfig());
        this.nativeApi = new PspaceNativeApi(config, mapper);
        PspaceTagResolver resolver = new PspaceTagResolver(nativeApi);
        this.support = new PspaceSupport(instanceName, config, nativeApi, mapper, resolver, closed);
        this.tagCatalog = new PspaceTagCatalogAdapter(support);
        this.currentValues = new PspaceCurrentValueAdapter(support);
        this.history = new PspaceHistoryAdapter(support);
        this.deltaHistory = new PspaceDeltaAdapter(support);
        this.writeSession = new PspaceWriteAdapter(support);
    }

    /**
     * @return 实例逻辑名，可能为 {@code null}
     */
    @Override
    public String instanceName() {
        return instanceName;
    }

    /**
     * @return 固定字符串 {@code "pspace"}
     */
    @Override
    public String type() {
        return "pspace";
    }

    /**
     * @return 非 {@code null} 的不可变能力集合
     */
    @Override
    public CapabilitySet capabilities() {
        return CAPABILITIES;
    }

    /**
     * @return 非 {@code null} 的单例位号目录
     * @throws IllegalStateException 客户端已关闭时
     */
    @Override
    public TagCatalog tagCatalog() {
        ensureOpen();
        return tagCatalog;
    }

    /**
     * @return 非 {@code null} 的单例当前值读取器
     * @throws IllegalStateException 客户端已关闭时
     */
    @Override
    public CurrentValueReader currentValues() {
        ensureOpen();
        return currentValues;
    }

    /**
     * @return 非 {@code null} 的单例历史读取器
     * @throws IllegalStateException 客户端已关闭时
     */
    @Override
    public HistoryReader history() {
        ensureOpen();
        return history;
    }

    /**
     * 返回累计量差分视图。
     *
     * <p>底层用 {@code endval} 聚合逐桶取末值，再在内存中做比已定稿的基线折叠；
     * 只支持整秒间隔与非日历月粒度。</p>
     *
     * @return 非 {@code null} 的单例差分读取器
     * @throws IllegalStateException 客户端已关闭时
     */
    @Override
    public DeltaReader deltaHistory() {
        ensureOpen();
        return deltaHistory;
    }

    /**
     * 返回写入会话；是否可写由 {@link WriteSession#isWritable()} 决定，
     * 受 {@code security.allow-write} 门禁。
     *
     * @return 非 {@code null} 的单例写入会话
     * @throws IllegalStateException 客户端已关闭时
     */
    @Override
    public WriteSession writeSession() {
        ensureOpen();
        return writeSession;
    }

    /**
     * 执行一次轻量健康探测（{@code /ServerInfo}）。
     *
     * <p>不触发 {@code /Refresh}，失败不抛异常，返回 {@code DOWN} 并携带消息。</p>
     *
     * @return 非 {@code null} 的健康状态快照
     * @throws IllegalStateException 客户端已关闭时
     */
    @Override
    public HealthStatus health() {
        ensureOpen();
        long started = System.currentTimeMillis();
        try {
            nativeApi.serverInfo();
            return HealthStatus.up(System.currentTimeMillis() - started)
                    .withTimeFormat(config.getTimeConfig());
        } catch (RuntimeException e) {
            return HealthStatus.down(e.getMessage(), System.currentTimeMillis() - started)
                    .withTimeFormat(config.getTimeConfig());
        }
    }

    /**
     * @return 实例时间解析与展示配置
     */
    public TimeConfig timeConfig() {
        return config.getTimeConfig();
    }

    /**
     * 按实例配置解析一个响应时间文本（文本或 epoch 毫秒）。
     *
     * @param text 时间文本，可为 {@code null}
     * @return 绝对时刻；{@code null} 或空白返回 {@code null}
     */
    public Instant parseTimestamp(String text) {
        return mapper.parseTimestamp(text);
    }

    /**
     * 按实例配置格式化一个绝对时刻。
     *
     * @param instant 时刻，可为 {@code null}
     * @return 展示文本；入参为 {@code null} 时返回 {@code null}
     */
    public String formatTimestamp(Instant instant) {
        return timeConfig().formatResponse(instant);
    }

    /**
     * 取出原生对象。
     *
     * <p>支持本客户端自身、{@link PspaceNativeApi} 与 {@link PspaceConfig}。</p>
     *
     * @param nativeType 期望的原生类型，不能为 {@code null}
     * @param <T>        原生类型
     * @return 非 {@code null} 的原生对象
     * @throws IllegalArgumentException  {@code nativeType} 为 {@code null} 时
     * @throws TsdbUnsupportedException 当本客户端不提供该类型时
     */
    @Override
    public <T> T unwrap(Class<T> nativeType) {
        if (nativeType == null) {
            throw new IllegalArgumentException("nativeType is required");
        }
        if (nativeType.isInstance(this)) {
            return nativeType.cast(this);
        }
        if (nativeType.isInstance(nativeApi)) {
            return nativeType.cast(nativeApi);
        }
        if (nativeType.isInstance(config)) {
            return nativeType.cast(config);
        }
        throw new TsdbUnsupportedException("pSpace client does not expose " + nativeType.getName());
    }

    /**
     * 关闭客户端；幂等，重复调用无副作用。
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            nativeApi.close();
        }
    }

    private void ensureOpen() {
        support.ensureOpen();
    }
}