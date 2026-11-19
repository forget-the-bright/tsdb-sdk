package io.github.forget_the_bright.tsdb.hls;

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
import io.github.forget_the_bright.tsdb.hls.internal.HlsNativeApi;

import java.util.concurrent.atomic.AtomicBoolean;
import io.github.forget_the_bright.tsdb.hls.internal.adapter.HlsCurrentValueAdapter;
import io.github.forget_the_bright.tsdb.hls.internal.adapter.HlsDeltaAdapter;
import io.github.forget_the_bright.tsdb.hls.internal.adapter.HlsHistoryAdapter;
import io.github.forget_the_bright.tsdb.hls.internal.adapter.HlsSupport;
import io.github.forget_the_bright.tsdb.hls.internal.adapter.HlsTagCatalogAdapter;
import io.github.forget_the_bright.tsdb.hls.internal.adapter.HlsWriteAdapter;

/**
 * HLS（和利时）时序库在公共 {@link TsdbClient} 门面上的实现。
 *
 * <p>本类是门户：能力声明、时间配置、健康检查、{@code unwrap} 与生命周期留在
 * 这里，位号目录、当前值、历史查询、累计量差分与写入分别委托给
 * {@link HlsTagCatalogAdapter}、{@link HlsCurrentValueAdapter}、
 * {@link HlsHistoryAdapter}、{@link HlsDeltaAdapter} 与 {@link HlsWriteAdapter}。
 * 适配器都在本包内可见，不扩大公开面，公共层接口的签名与语义保持不变。</p>
 *
 * <p>能力边界：仅支持 {@link Capability#TAG_BROWSE}、
 * {@link Capability#CURRENT_VALUE}、{@link Capability#RAW_HISTORY}、
 * {@link Capability#AGGREGATED_HISTORY}、
 * {@link Capability#SAMPLED_HISTORY} 与 {@link Capability#CUMULATIVE_DELTA}。
 * 不支持服务端位号检索、按值类型或单位过滤、插值、趋势、倒序查询、补点策略以及
 * 写入；命中这些能力时统一抛出 {@link TsdbUnsupportedException}，不会静默降级
 * 或返回伪造数据。</p>
 *
 * <p>HLS 没有原生原始点接口，{@link Capability#RAW_HISTORY} 由历史值接口的
 * 1 秒 {@code BOUND} 桶近似（现场生产同款方案），仅在结果属性
 * {@code rawSource=hls_history_1s_bound} 下声明，详见
 * {@code docs/10} §8.1 与 {@code docs/15}。</p>
 *
 * <p>线程安全：本类字段全部为 {@code final}（关闭标记使用 {@link AtomicBoolean}），
 * 适配器无状态，因此客户端可被多线程并发使用。</p>
 *
 * <p>生命周期：构造时不建立网络连接，首次请求时才建连；{@link #close()} 幂等，
 * 关闭后再调用任何子接口都会抛 {@link IllegalStateException}。</p>
 *
 * <p>与原生层的关系：{@link #unwrap(Class)} 支持取回本客户端自身、
 * {@link HlsNativeApi}（可直接拿厂商原始 JSON）与 {@link HlsConfig} 三类对象。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class HlsClient implements TsdbClient {

    /** 能力快照：构造后不再变化。 */
    private static final CapabilitySet CAPABILITIES = CapabilitySet.of(
            Capability.TAG_BROWSE,
            Capability.CURRENT_VALUE,
            Capability.RAW_HISTORY,
            Capability.AGGREGATED_HISTORY,
            Capability.SAMPLED_HISTORY,
            Capability.CUMULATIVE_DELTA);

    /** 实例逻辑名，用于日志与异常上下文。 */
    private final String instanceName;

    /** 已校验的 HLS 配置，供 {@link #unwrap(Class)} 暴露。 */
    private final HlsConfig config;

    /** 厂商原生 API 客户端。 */
    private final HlsNativeApi nativeApi;

    /** 适配器共享的配置、原生客户端、映射器与关闭标记。 */
    private final HlsSupport support;

    /** 位号目录视图，分页在内存中模拟。 */
    private final TagCatalog tagCatalog;

    /** 当前值视图，一次请求取全部位号。 */
    private final CurrentValueReader currentValues;

    /** 历史视图，只支持聚合与采样模式。 */
    private final HistoryReader history;

    /** 累计量差分视图，用 BOUND 列在内存中做差。 */
    private final DeltaReader deltaHistory;

    /** 只读写入会话。 */
    private final WriteSession writeSession;

    /** 关闭标记，保证 {@link #close()} 幂等。 */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 创建 HLS 客户端。
     *
     * <p>构造过程不做任何网络 I/O，只保存引用并构建原生 API 与映射器；
     * 配置本身应已由 {@link HlsConfig} 完成校验。</p>
     *
     * @param instanceName 实例逻辑名，用于日志与异常上下文；允许为 {@code null}
     * @param config       已校验的 HLS 配置，不能为 {@code null}
     * @throws IllegalArgumentException 当实现内部依赖的配置为空时（由
     *         {@link HlsNativeApi} 间接抛出）
     */
    public HlsClient(String instanceName, HlsConfig config) {
        this.instanceName = instanceName;
        this.config = config;
        this.nativeApi = new HlsNativeApi(config);
        this.support = new HlsSupport(instanceName, config, nativeApi,
                new HlsMapper(config.getTimeConfig()), closed);
        this.tagCatalog = new HlsTagCatalogAdapter(support);
        this.currentValues = new HlsCurrentValueAdapter(support);
        this.history = new HlsHistoryAdapter(support);
        this.deltaHistory = new HlsDeltaAdapter(support);
        this.writeSession = new HlsWriteAdapter(support);
    }

    /**
     * 返回实例逻辑名。
     *
     * @return 创建时传入的实例名，可能为 {@code null}
     */
    @Override
    public String instanceName() {
        return instanceName;
    }

    /**
     * 返回驱动类型标识。
     *
     * @return 固定字符串 {@code "hls"}
     */
    @Override
    public String type() {
        return "hls";
    }

    /**
     * 返回该客户端支持的能力快照。
     *
     * @return 非 {@code null} 的不可变能力集合
     */
    @Override
    public CapabilitySet capabilities() {
        return CAPABILITIES;
    }

    /**
     * 返回位号目录操作接口。
     *
     * @return 非 {@code null} 的单例适配器
     * @throws IllegalStateException 当客户端已关闭时
     */
    @Override
    public TagCatalog tagCatalog() {
        ensureOpen();
        return tagCatalog;
    }

    /**
     * 返回当前值读取接口。
     *
     * @return 非 {@code null} 的单例适配器
     * @throws IllegalStateException 当客户端已关闭时
     */
    @Override
    public CurrentValueReader currentValues() {
        ensureOpen();
        return currentValues;
    }

    /**
     * 返回历史数据读取接口。
     *
     * <p>{@code AGGREGATED}、{@code SAMPLED} 走历史宽表接口。HLS 没有原生原始点
     * 接口，{@code RAW} 由该接口的 1 秒 {@code BOUND} 桶近似（现场生产同款方案），
     * 结果带 {@code rawSource=hls_history_1s_bound}、{@code rawApproximate=true}
     * 属性；仅支持 {@code FORWARD}。厂商升级或换现场时按 {@code docs/10} §8.1 复核。</p>
     *
     * @return 非 {@code null} 的单例适配器
     * @throws IllegalStateException 当客户端已关闭时
     */
    @Override
    public HistoryReader history() {
        ensureOpen();
        return history;
    }

    /**
     * 返回累计量差分视图。
     *
     * <p>HLS 没有 {@code LAST} 聚合，也没有取单点原始值的接口，本实现使用厂商
     * {@code BOUND} 列作为每个时间桶的边界值，基线取起点前一个桶，再在内存中
     * 相邻相减。这是现场生产同款迂回方案，适用于累计流量、累计电量等单调点位；
     * 普通瞬时量点位会得到无业务意义的差值。{@code BOUND} 语义随厂商版本变化，
     * 升级或换现场时按 {@code docs/10} §8.1 复核。</p>
     *
     * @return 非 {@code null} 的单例适配器
     * @throws IllegalStateException 当客户端已关闭时
     */
    @Override
    public DeltaReader deltaHistory() {
        ensureOpen();
        return deltaHistory;
    }

    /**
     * 返回写入会话。
     *
     * <p>HLS 为只读数据源，返回的会话 {@code isWritable()} 恒为 {@code false}，
     * 调用 {@code write} 会抛 {@link TsdbUnsupportedException}。</p>
     *
     * @return 非 {@code null} 的只读会话
     * @throws IllegalStateException 当客户端已关闭时
     */
    @Override
    public WriteSession writeSession() {
        ensureOpen();
        return writeSession;
    }

    /**
     * 执行一次轻量健康探测。
     *
     * <p>探测失败不抛异常，而是返回 {@code DOWN} 并携带异常消息。</p>
     *
     * @return 非 {@code null} 的健康状态快照
     * @throws IllegalStateException 当客户端已关闭时
     */
    @Override
    public HealthStatus health() {
        ensureOpen();
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
     * 取出原生对象，用于访问公共层未抽象的 HLS 能力。
     *
     * <p>支持本客户端自身、{@link HlsNativeApi} 与 {@link HlsConfig}。</p>
     *
     * @param nativeType 期望的原生类型，不能为 {@code null}
     * @param <T>        原生类型
     * @return 非 {@code null} 的原生对象
     * @throws IllegalArgumentException {@code nativeType} 为 {@code null} 时
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
        throw new TsdbUnsupportedException("HLS client does not expose " + nativeType.getName());
    }

    /**
     * 关闭客户端并释放底层 HTTP 资源。
     *
     * <p>实现幂等：只有第一次调用会真正释放。关闭后再调用任何子接口会抛
     * {@link IllegalStateException}。</p>
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            nativeApi.close();
        }
    }

    /**
     * 检查客户端是否仍处于打开状态。
     *
     * @throws IllegalStateException 当客户端已关闭时
     */
    private void ensureOpen() {
        support.ensureOpen();
    }
}