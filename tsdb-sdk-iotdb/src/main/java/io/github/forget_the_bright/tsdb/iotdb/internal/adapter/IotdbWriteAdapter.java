package io.github.forget_the_bright.tsdb.iotdb.internal.adapter;

import io.github.forget_the_bright.tsdb.common.api.WriteSession;
import io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException;
import io.github.forget_the_bright.tsdb.common.model.WriteRequest;
import io.github.forget_the_bright.tsdb.common.model.WriteResult;

import java.util.List;

/**
 * IoTDB 写入会话适配器：逐条顺序提交，不具备跨位号事务性。
 *
 * <p>未开启 {@code tsdb.security.allow-write} 时 {@code write} 直接抛
 * {@link TsdbUnsupportedException}，绝不静默丢弃数据；空请求视为成功且影响
 * 0 行，不发起 HTTP 调用。中途失败时前面的请求可能已落盘，异常原样上抛，
 * 返回值只在全部成功时产生。</p>
 *
 * <p><b>非公共 API</b>：门户装配层实现，签名随内部重构变化，宿主应用请勿直接依赖；
 * 稳定契约请使用 {@link io.github.forget_the_bright.tsdb.iotdb.IotdbClient}。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class IotdbWriteAdapter implements WriteSession {

    private final IotdbSupport support;

    /**
     * @param support 共享依赖
     */
    public IotdbWriteAdapter(IotdbSupport support) {
        this.support = support;
    }

    /**
     * @return {@code true} 表示 {@code tsdb.security.allow-write} 已开启
     */
    @Override
    public boolean isWritable() {
        return support.config().isWriteAllowed();
    }

    /**
     * 逐条写入样本。
     *
     * @param requests 写入请求列表，可为 {@code null} 或空
     * @return 非 {@code null} 的写入结果；成功时影响行数等于请求条数
     * @throws TsdbUnsupportedException 当写入未开启时
     */
    @Override
    public WriteResult write(List<WriteRequest> requests) {
        if (!support.config().isWriteAllowed()) {
            throw support.unsupported(new TsdbUnsupportedException(
                    "IoTDB write is disabled; set tsdb.security.allow-write=true"), "write");
        }
        if (requests == null || requests.isEmpty()) {
            return WriteResult.success(0);
        }
        for (WriteRequest request : requests) {
            support.api().write(request.getTagName(), request.getSamples());
        }
        return WriteResult.success(requests.size());
    }
}