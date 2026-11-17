package io.github.forget_the_bright.tsdb.ge.internal.adapter;

import io.github.forget_the_bright.tsdb.common.exception.TsdbQueryException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException;
import io.github.forget_the_bright.tsdb.ge.internal.GeNativeApi;
import io.github.forget_the_bright.tsdb.ge.GeConfig;

/**
 * GE 各适配器共享的配置、原生客户端与异常上下文。
 *
 * <p>适配器只持有本对象，不再各自保存 {@code GeConfig}/{@code GeNativeApi}，
 * 这样“给异常补实例名、驱动名与操作名”只有一处实现，也不会出现某个适配器
 * 漏填上下文导致日志无法定位实例的情况。</p>
 *
 * <p><b>非公共 API</b>：门户装配层实现，签名随内部重构变化，宿主应用请勿直接依赖；
 * 稳定契约请使用 {@link io.github.forget_the_bright.tsdb.ge.GeClient}。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class GeSupport {

    private final GeConfig config;
    private final GeNativeApi nativeApi;
    private final String instanceName;

    /**
     * @param config       已校验的配置，不能为 {@code null}
     * @param nativeApi    原生 REST 客户端，不能为 {@code null}
     * @param instanceName 实例逻辑名，可为 {@code null}
     */
    public GeSupport(GeConfig config, GeNativeApi nativeApi, String instanceName) {
        this.config = config;
        this.nativeApi = nativeApi;
        this.instanceName = instanceName;
    }

    /**
     * @return 驱动配置
     */
    GeConfig config() {
        return config;
    }

    /**
     * @return 原生 REST 客户端
     */
    GeNativeApi nativeApi() {
        return nativeApi;
    }

    /**
     * 给不支持类异常补充实例名、驱动名与操作名。
     *
     * @param e         原始异常
     * @param operation 操作名
     * @return 同一个异常对象
     */
    public TsdbUnsupportedException unsupported(TsdbUnsupportedException e, String operation) {
        e.withContext(instanceName, "ge", operation, null);
        return e;
    }

    /**
     * 给查询类异常补充实例名、驱动名与操作名。
     *
     * @param e         原始异常
     * @param operation 操作名
     * @return 同一个异常对象
     */
    TsdbQueryException query(TsdbQueryException e, String operation) {
        e.withContext(instanceName, "ge", operation, null);
        return e;
    }
}