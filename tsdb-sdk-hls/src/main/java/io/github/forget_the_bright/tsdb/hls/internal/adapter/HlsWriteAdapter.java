package io.github.forget_the_bright.tsdb.hls.internal.adapter;

import io.github.forget_the_bright.tsdb.common.api.WriteSession;
import io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException;
import io.github.forget_the_bright.tsdb.common.model.WriteRequest;
import io.github.forget_the_bright.tsdb.common.model.WriteResult;

import java.util.List;

/**
 * HLS 只读写入会话占位实现。
 *
 * <p>HLS 是只读数据源，没有写入接口。仍然实现 {@link WriteSession} 并返回实例，
 * 是为了让公共层用统一方式探测可写性，而不是把 {@code null} 传播给调用方；
 * 真正调用 {@code write} 时显式抛 {@link TsdbUnsupportedException}。</p>
 *
 * <p><b>非公共 API</b>：门户装配层实现，签名随内部重构变化，宿主应用请勿直接依赖；
 * 稳定契约请使用 {@link io.github.forget_the_bright.tsdb.hls.HlsClient}。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class HlsWriteAdapter implements WriteSession {

    private final HlsSupport support;

    /**
     * @param support 共享依赖
     */
    public HlsWriteAdapter(HlsSupport support) {
        this.support = support;
    }

    /**
     * @return 恒为 {@code false}
     */
    @Override
    public boolean isWritable() {
        return false;
    }

    /**
     * 尝试写入数据；本实现不会发起任何请求。
     *
     * @param requests 待写入的请求列表（本实现不会读取其内容）
     * @return 不会正常返回
     * @throws TsdbUnsupportedException 始终抛出，表示驱动只读
     */
    @Override
    public WriteResult write(List<WriteRequest> requests) {
        throw support.unsupported("HLS driver is read-only", "write", null);
    }
}