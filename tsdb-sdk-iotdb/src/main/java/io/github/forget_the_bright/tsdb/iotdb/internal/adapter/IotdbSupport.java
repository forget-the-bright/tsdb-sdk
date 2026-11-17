package io.github.forget_the_bright.tsdb.iotdb.internal.adapter;

import io.github.forget_the_bright.tsdb.common.exception.TsdbQueryException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException;
import io.github.forget_the_bright.tsdb.iotdb.internal.query.IotdbQueryService;
import io.github.forget_the_bright.tsdb.iotdb.internal.transport.IotdbRestApi;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.IotdbTagResolver;
import io.github.forget_the_bright.tsdb.iotdb.IotdbClient;
import io.github.forget_the_bright.tsdb.iotdb.IotdbConfig;

/**
 * IoTDB 适配器共享的配置、原生客户端、查询服务、位号解析器与异常上下文。
 *
 * <p>适配器不直接持有客户端，避免门户与子接口互相引用；异常上下文的
 * 实例名、驱动类型 {@code "iotdb"} 与操作名也只在 {@link IotdbClient} 中
 * 通过这里统一补齐。</p>
 *
 * <p><b>非公共 API</b>：门户装配层实现，签名随内部重构变化，宿主应用请勿直接依赖；
 * 稳定契约请使用 {@link io.github.forget_the_bright.tsdb.iotdb.IotdbClient}。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class IotdbSupport {

    private final IotdbConfig config;
    private final IotdbRestApi api;
    private final IotdbQueryService queryService;
    private final IotdbTagResolver tagResolver;

    /**
     * @param config       驱动配置，不能为 {@code null}
     * @param api          原生 REST 客户端，不能为 {@code null}
     * @param queryService 统一 SQL 查询服务，可为 {@code null}（精简实例）
     * @param tagResolver  位号解析器，不能为 {@code null}
     */
    public IotdbSupport(IotdbConfig config, IotdbRestApi api, IotdbQueryService queryService,
                 IotdbTagResolver tagResolver) {
        this.config = config;
        this.api = api;
        this.queryService = queryService;
        this.tagResolver = tagResolver;
    }

    /**
     * @return 驱动配置
     */
    IotdbConfig config() {
        return config;
    }

    /**
     * @return 原生 REST 客户端
     */
    IotdbRestApi api() {
        return api;
    }

    /**
     * @return 统一 SQL 查询服务；精简实例为 {@code null}
     */
    IotdbQueryService queryService() {
        return queryService;
    }

    /**
     * @return 位号路径解析器
     */
    IotdbTagResolver tagResolver() {
        return tagResolver;
    }

    /**
     * 给“不支持”类异常补充实例、驱动与操作上下文。
     *
     * @param e         原始异常
     * @param operation 操作名
     * @return 同一个异常对象
     */
    TsdbUnsupportedException unsupported(TsdbUnsupportedException e, String operation) {
        e.withContext(config.getInstanceName(), "iotdb", operation, null);
        return e;
    }

    /**
     * 给查询类异常补充实例、驱动与操作上下文。
     *
     * @param e         原始异常
     * @param operation 操作名
     * @return 同一个异常对象
     */
    TsdbQueryException query(TsdbQueryException e, String operation) {
        e.withContext(config.getInstanceName(), "iotdb", operation, null);
        return e;
    }
}