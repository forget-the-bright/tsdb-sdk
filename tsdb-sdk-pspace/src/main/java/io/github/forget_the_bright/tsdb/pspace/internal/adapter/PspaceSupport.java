package io.github.forget_the_bright.tsdb.pspace.internal.adapter;

import io.github.forget_the_bright.tsdb.common.exception.TsdbException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbQueryException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException;
import io.github.forget_the_bright.tsdb.pspace.PspaceConfig;
import io.github.forget_the_bright.tsdb.pspace.internal.PspaceMapper;
import io.github.forget_the_bright.tsdb.pspace.internal.PspaceModels;
import io.github.forget_the_bright.tsdb.pspace.internal.PspaceNativeApi;
import io.github.forget_the_bright.tsdb.pspace.internal.PspaceTagResolver;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * pSpace 五个适配器共享的配置、原生客户端、映射器、解析缓存与生命周期检查。
 *
 * <p>职责边界：</p>
 * <ul>
 *   <li><b>共享依赖</b>：向适配器暴露 config/nativeApi/mapper/resolver，
 *       不在这里发起任何 HTTP 请求；</li>
 *   <li><b>生命周期</b>：{@link #ensureOpen()} 统一关闭检查；</li>
 *   <li><b>分页判定</b>：{@link #hasMorePages} 以服务端 {@code page.count} 为准，
 *       缺页数且满页时抛异常而不是静默截断；</li>
 *   <li><b>异常上下文</b>：{@link #context}/{@link #unsupported} 统一补实例名、
 *       驱动类型、操作名与厂商细节，保证各适配器的错误信息一致。</li>
 * </ul>
 *
 * <p><b>非公共 API</b>：门户装配层实现，签名随内部重构变化，宿主应用请勿直接依赖；
 * 稳定契约请使用 {@link io.github.forget_the_bright.tsdb.pspace.PspaceClient}。</p>
 *
 * @author wanghao
 * @since 0.1.2
 */
public final class PspaceSupport {

    private final String instanceName;
    private final PspaceConfig config;
    private final PspaceNativeApi nativeApi;
    private final PspaceMapper mapper;
    private final PspaceTagResolver resolver;
    private final AtomicBoolean closed;

    /**
     * @param instanceName 实例逻辑名，可为 {@code null}
     * @param config       已校验的配置，不能为 {@code null}
     * @param nativeApi    原生 API，不能为 {@code null}
     * @param mapper       映射器，不能为 {@code null}
     * @param resolver     位号解析缓存，不能为 {@code null}
     * @param closed       客户端的关闭标记，不能为 {@code null}
     */
    public PspaceSupport(String instanceName, PspaceConfig config, PspaceNativeApi nativeApi,
                         PspaceMapper mapper, PspaceTagResolver resolver, AtomicBoolean closed) {
        this.instanceName = instanceName;
        this.config = config;
        this.nativeApi = nativeApi;
        this.mapper = mapper;
        this.resolver = resolver;
        this.closed = closed;
    }

    /**
     * @return 驱动配置
     */
    PspaceConfig config() {
        return config;
    }

    /**
     * @return 原生 API
     */
    PspaceNativeApi nativeApi() {
        return nativeApi;
    }

    /**
     * @return 映射器
     */
    PspaceMapper mapper() {
        return mapper;
    }

    /**
     * @return 位号解析缓存
     */
    PspaceTagResolver resolver() {
        return resolver;
    }

    /**
     * 检查客户端是否仍处于打开状态。
     *
     * @throws IllegalStateException 当客户端已关闭时
     */
    public void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("pSpace client is closed: " + instanceName);
        }
    }

    /**
     * 给异常补充实例名、驱动类型与操作名。
     *
     * @param e         原始异常
     * @param operation 操作名
     * @return 同一个异常对象
     */
    TsdbException context(TsdbException e, String operation) {
        e.withContext(instanceName, "pspace", operation, null);
        return e;
    }

    /**
     * 判断分页是否还有下一页。
     *
     * <p>完全以服务端 {@code page.count}（总页数）为准，不设任何人为页数上限。
     * 若服务端没有返回 {@code page.count} 且当前页已满，无法证明数据已经取全，
     * 直接抛 {@link TsdbQueryException}，避免静默返回部分结果。</p>
     *
     * @param endpoint   端点名，仅用于异常消息
     * @param page       当前页
     * @param pageSize   请求的页大小
     * @param pageNumber 当前页号（从 1 开始）
     * @return 还有下一页返回 {@code true}
     * @throws TsdbQueryException {@code page.count} 缺失且当前页已满时
     */
    boolean hasMorePages(String endpoint, PspaceModels.Page page, int pageSize, int pageNumber) {
        if (page.values.isEmpty()) {
            return false;
        }
        if (page.pageCount > 0) {
            return pageNumber < page.pageCount;
        }
        if (page.values.size() < pageSize) {
            return false;
        }
        TsdbQueryException e = new TsdbQueryException("pSpace " + endpoint + " page " + pageNumber
                + " is full but page.count is missing; refusing to return a silent partial result");
        e.withContext(instanceName, "pspace", "paging", endpoint);
        throw e;
    }

    /**
     * 构造并补充上下文的不支持异常。
     *
     * @param message   异常消息
     * @param operation 操作名
     * @param detail    厂商侧取值（可空）
     * @return 已补充上下文的异常
     */
    TsdbUnsupportedException unsupported(String message, String operation, String detail) {
        TsdbUnsupportedException e = new TsdbUnsupportedException(message);
        e.withContext(instanceName, "pspace", operation, detail);
        return e;
    }
}