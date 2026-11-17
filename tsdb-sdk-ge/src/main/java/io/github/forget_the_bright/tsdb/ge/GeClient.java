package io.github.forget_the_bright.tsdb.ge;

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
import io.github.forget_the_bright.tsdb.common.model.WriteRequest;
import io.github.forget_the_bright.tsdb.common.model.WriteResult;
import io.github.forget_the_bright.tsdb.ge.internal.GeNativeApi;

import java.util.List;
import io.github.forget_the_bright.tsdb.ge.internal.adapter.GeCurrentValueAdapter;
import io.github.forget_the_bright.tsdb.ge.internal.adapter.GeDeltaAdapter;
import io.github.forget_the_bright.tsdb.ge.internal.adapter.GeHistoryAdapter;
import io.github.forget_the_bright.tsdb.ge.internal.adapter.GeSupport;
import io.github.forget_the_bright.tsdb.ge.internal.adapter.GeTagCatalogAdapter;

/**
 * GE Proficy Historian 的 {@link TsdbClient} 实现。
 *
 * <p>本类是门户：能力声明、时间配置、健康检查、{@code unwrap} 与生命周期留在
 * 这里，具体查询分别委托给 {@link GeTagCatalogAdapter}、{@link GeCurrentValueAdapter}、
 * {@link GeHistoryAdapter} 与 {@link GeDeltaAdapter}。适配器都在本包内可见，
 * 不扩大公开面，公共层接口的签名与语义保持不变。</p>
 *
 * <p>能力范围（对应 {@link #capabilities()}）：标签浏览、当前值读取、原始历史、
 * 聚合历史、插值历史、采样历史、累计量差分七项。写入能力未实现：
 * {@link #writeSession()} 返回的会话 {@code isWritable()} 恒为 {@code false}，
 * 调用 {@code write} 会抛 {@link TsdbUnsupportedException}。</p>
 *
 * <p>结果按请求中的标签顺序输出，厂商未返回的标签会被补成空值占位，便于调用方
 * 依赖下标对应关系。历史查询不支持填充策略；聚合映射
 * {@code AVG/MIN/MAX/COUNT/SUM/FIRST/LAST}，{@code INTERVAL_SUM} 以 17/19
 * 双请求末减首实现，{@code RANGE/BOUND} 无对应厂商计算模式。</p>
 *
 * <p>线程安全性：所有字段均为 {@code final}，适配器无状态，因此本类可在多线程间
 * 共享。真正的并发安全取决于 {@link GeNativeApi} 与 {@code GeTokenManager} 的实现。</p>
 *
 * <p>与原生层的关系：{@link #unwrap(Class)} 可以取出本类、{@link GeNativeApi}
 * 或 {@link GeConfig}，方便调用方在需要 GE 独有能力（如 92 个原始端点）时下钻。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class GeClient implements TsdbClient {

    /**
     * 本驱动声明支持的公共能力集合。
     *
     * <p>刻意不包含 {@code WRITE} 与 {@code TAG_METADATA}：本构建未开放写入，
     * 且 GE 的标签目录接口不返回描述、工程单位等元数据。</p>
     */
    private static final CapabilitySet CAPABILITIES = CapabilitySet.of(
            Capability.TAG_BROWSE,
            Capability.CURRENT_VALUE,
            Capability.RAW_HISTORY,
            Capability.AGGREGATED_HISTORY,
            Capability.INTERPOLATED_HISTORY,
            Capability.SAMPLED_HISTORY,
            Capability.CUMULATIVE_DELTA);

    /** 已校验的连接配置，用于取实例名并在异常中补充上下文。 */
    private final GeConfig config;

    /** 原生 REST 客户端，承载全部实际请求与健康检查。 */
    private final GeNativeApi nativeApi;

    /** 适配器共享的配置、原生客户端与异常上下文。 */
    private final GeSupport support;

    /** 标签目录视图，分页在内存中模拟。 */
    private final TagCatalog tagCatalog;

    /** 当前值视图，一批标签只发一次请求，结果按入参顺序返回。 */
    private final CurrentValueReader currentValues;

    /** 历史视图，按查询模式分派到原始/聚合/插值/采样端点。 */
    private final HistoryReader history;

    /** 累计量差分视图，取区间末值后做内存差分。 */
    private final DeltaReader deltaHistory;

    /**
     * 只读写入会话占位实现。
     *
     * <p>GE Historian 具备写入能力，但本 SDK 构建未开放：声明为不可写，
     * 并在真正调用 {@code write} 时抛出带上下文的 {@link TsdbUnsupportedException}，
     * 而不是静默丢弃数据。</p>
     */
    private final WriteSession writeSession;

    /**
     * 用已构造的原生组件装配客户端。
     *
     * <p>通常由 {@link GeDriver#create} 调用；构造器不做校验、不发起网络请求，
     * 只保存引用并装配适配器，因此传入的 {@code nativeApi} 必须已经与
     * {@code config} 匹配（同一 base URL 与凭据）。</p>
     *
     * @param config    已校验的连接配置，不能为 {@code null}
     * @param nativeApi 已初始化的原生 REST 客户端，不能为 {@code null}
     */
    public GeClient(GeConfig config, GeNativeApi nativeApi) {
        this.config = config;
        this.nativeApi = nativeApi;
        this.support = new GeSupport(config, nativeApi, config.getInstanceName());
        this.tagCatalog = new GeTagCatalogAdapter(support);
        this.currentValues = new GeCurrentValueAdapter(support);
        this.history = new GeHistoryAdapter(support);
        this.deltaHistory = new GeDeltaAdapter(support);
        this.writeSession = new WriteSession() {
            @Override
            public boolean isWritable() {
                return false;
            }

            @Override
            public WriteResult write(List<WriteRequest> requests) {
                throw support.unsupported(new TsdbUnsupportedException(
                        "GE write capability is not enabled in this SDK build"), "write");
            }
        };
    }

    /**
     * 返回实例逻辑名，取自 {@link GeConfig#getInstanceName()}。
     *
     * @return 实例名，可能为 {@code null}
     */
    @Override
    public String instanceName() {
        return config.getInstanceName();
    }

    /**
     * 返回驱动类型标识。
     *
     * @return 恒为 {@code "ge"}
     */
    @Override
    public String type() {
        return "ge";
    }

    /**
     * 返回本驱动支持的能力集合。
     *
     * @return 不可变的能力集合，非 {@code null}
     */
    @Override
    public CapabilitySet capabilities() {
        return CAPABILITIES;
    }

    /**
     * 返回本实例的时间格式配置。
     *
     * <p>公共 API 的 {@code Sample.timestamp} 始终是绝对时刻 {@link java.time.Instant}，
     * 不携带时区；展示时区与模板由 {@code options.time.*} 决定。</p>
     *
     * @return 非 {@code null} 的时间配置
     */
    public TimeConfig timeConfig() {
        return config.getTimeConfig();
    }

    /**
     * 按本实例配置的响应时区与模式格式化一个时刻。
     *
     * @param instant 时刻，允许为 {@code null}
     * @return 格式化后的文本；入参为 {@code null} 时返回 {@code null}
     */
    public String formatTimestamp(java.time.Instant instant) {
        return config.getTimeConfig().formatResponse(instant);
    }

    /**
     * 按本实例配置解析一个时间文本。
     *
     * @param text 时间文本，可为 {@code null}
     * @return 解析后的绝对时刻；空白返回 {@code null}
     */
    public java.time.Instant parseTimestamp(String text) {
        return config.getTimeConfig().parseResponse(text);
    }

    /**
     * 返回标签目录接口。
     *
     * @return 非 {@code null} 的单例适配器
     */
    @Override
    public TagCatalog tagCatalog() {
        return tagCatalog;
    }

    /**
     * 返回当前值读取接口。
     *
     * @return 非 {@code null} 的单例适配器
     */
    @Override
    public CurrentValueReader currentValues() {
        return currentValues;
    }

    /**
     * 返回历史读取接口。
     *
     * @return 非 {@code null} 的单例适配器
     */
    @Override
    public HistoryReader history() {
        return history;
    }

    /**
     * 返回累计量差分视图。
     *
     * <p>差分使用 {@code LastRawValue=19} 取桶末值，再与起点前基线相减；
     * 若现场确认该聚合不是区间末值，应改用其他聚合或在网关侧做差。</p>
     *
     * @return 非 {@code null} 的单例适配器
     */
    @Override
    public DeltaReader deltaHistory() {
        return deltaHistory;
    }

    /**
     * 返回只读写入会话。
     *
     * @return 非 {@code null} 的只读会话，{@code isWritable()} 恒为 {@code false}
     */
    @Override
    public WriteSession writeSession() {
        return writeSession;
    }

    /**
     * 执行一次轻量健康探测。
     *
     * <p>探测失败不抛异常，而是返回 {@code DOWN} 并携带异常消息。</p>
     *
     * @return 非 {@code null} 的健康状态快照
     */
    @Override
    public HealthStatus health() {
        long started = System.currentTimeMillis();
        try {
            nativeApi.checkHealth();
            return HealthStatus.up(System.currentTimeMillis() - started)
                    .withTimeFormat(config.getTimeConfig());
        } catch (RuntimeException e) {
            return HealthStatus.down(e.getMessage(), System.currentTimeMillis() - started)
                    .withTimeFormat(config.getTimeConfig());
        }
    }

    /**
     * 取出原生对象，用于访问公共层未抽象的 GE 能力。
     *
     * <p>支持本客户端自身、{@link GeNativeApi} 与 {@link GeConfig}。</p>
     *
     * @param nativeType 期望的原生类型，不能为 {@code null}
     * @param <T>        原生类型
     * @return 非 {@code null} 的原生对象
     * @throws IllegalArgumentException 当 {@code nativeType} 为 {@code null} 时
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
        throw new TsdbUnsupportedException("GE client does not expose " + nativeType.getName());
    }

    /**
     * 关闭客户端。
     *
     * <p>GE 的 Hutool 传输层每次请求独立建连、没有常驻资源，因此本方法为空操作；
     * 保留它只是为了满足 {@link TsdbClient} 的生命周期约定。</p>
     */
    @Override
    public void close() {
        // 无常驻连接需要释放
    }
}