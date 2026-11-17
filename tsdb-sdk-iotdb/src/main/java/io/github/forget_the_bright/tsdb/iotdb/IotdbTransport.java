package io.github.forget_the_bright.tsdb.iotdb;

/**
 * IoTDB 访问通道。
 *
 * <p>三种通道最终都执行同一条 SQL，因此查询语义由 SQL 层保证一致；
 * 通道只影响连接方式、性能与部署依赖。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public enum IotdbTransport {

    /** 原生 Java Session/SessionPool，性能最好，需要 {@code iotdb-session} 依赖。 */
    NATIVE,

    /** JDBC，使用 {@code iotdb-jdbc} 驱动，适合已有 JDBC 基础设施的项目。 */
    JDBC,

    /** REST v2，只依赖 JDK HTTP，部署最轻，但有 JSON 开销。 */
    REST
}
