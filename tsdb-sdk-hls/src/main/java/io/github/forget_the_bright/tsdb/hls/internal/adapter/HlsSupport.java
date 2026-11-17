package io.github.forget_the_bright.tsdb.hls.internal.adapter;

import io.github.forget_the_bright.tsdb.common.exception.TsdbException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException;
import io.github.forget_the_bright.tsdb.hls.internal.HlsNativeApi;

import java.util.concurrent.atomic.AtomicBoolean;
import io.github.forget_the_bright.tsdb.hls.HlsConfig;
import io.github.forget_the_bright.tsdb.hls.HlsMapper;

/**
 * HLS 适配器共享的配置、原生客户端、映射器与生命周期检查。
 *
 * <p>四个查询适配器与写入会话都通过本对象访问 {@code ensureOpen()} 与异常上下文，
 * 保证“关闭后再调用任何子接口都抛 {@link IllegalStateException}”这条语义
 * 只在一处实现。</p>
 *
 * <p><b>非公共 API</b>：门户装配层实现，签名随内部重构变化，宿主应用请勿直接依赖；
 * 稳定契约请使用 {@link io.github.forget_the_bright.tsdb.hls.HlsClient}。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class HlsSupport {

    private final String instanceName;
    private final HlsConfig config;
    private final HlsNativeApi nativeApi;
    private final HlsMapper mapper;
    private final AtomicBoolean closed;

    /**
     * @param instanceName 实例逻辑名，可为 {@code null}
     * @param config       已校验的配置，不能为 {@code null}
     * @param nativeApi    原生 API 客户端，不能为 {@code null}
     * @param mapper       厂商模型映射器，不能为 {@code null}
     * @param closed       客户端的关闭标记，不能为 {@code null}
     */
    public HlsSupport(String instanceName, HlsConfig config, HlsNativeApi nativeApi,
               HlsMapper mapper, AtomicBoolean closed) {
        this.instanceName = instanceName;
        this.config = config;
        this.nativeApi = nativeApi;
        this.mapper = mapper;
        this.closed = closed;
    }

    /**
     * @return 驱动配置
     */
    HlsConfig config() {
        return config;
    }

    /**
     * @return 原生 API 客户端
     */
    HlsNativeApi nativeApi() {
        return nativeApi;
    }

    /**
     * @return 厂商模型映射器
     */
    HlsMapper mapper() {
        return mapper;
    }

    /**
     * 检查客户端是否仍处于打开状态。
     *
     * @throws IllegalStateException 当客户端已关闭时
     */
    public void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("HLS client is closed: " + instanceName);
        }
    }

    /**
     * 给异常补充实例名、驱动类型与操作名。
     *
     * <p>HLS 厂商接口不返回机器可读错误码，因此第四项上下文留空。</p>
     *
     * @param e         原始异常
     * @param operation 操作名
     * @return 同一个异常对象
     */
    TsdbException context(TsdbException e, String operation) {
        e.withContext(instanceName, "hls", operation, null);
        return e;
    }

    /**
     * 构造并补充上下文的不支持异常。
     *
     * @param message  异常消息
     * @param operation 操作名
     * @param detail   厂商侧取值（可空），写入第四项上下文
     * @return 已补充上下文的异常
     */
    TsdbUnsupportedException unsupported(String message, String operation, String detail) {
        TsdbUnsupportedException e = new TsdbUnsupportedException(message);
        e.withContext(instanceName, "hls", operation, detail);
        return e;
    }
}