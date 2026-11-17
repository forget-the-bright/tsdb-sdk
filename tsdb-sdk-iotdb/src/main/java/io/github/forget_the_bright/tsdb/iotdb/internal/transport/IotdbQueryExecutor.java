package io.github.forget_the_bright.tsdb.iotdb.internal.transport;
import io.github.forget_the_bright.tsdb.iotdb.internal.query.IotdbQueryService;

/**
 * IoTDB SQL 执行通道抽象。
 *
 * <p>原生 Session、JDBC、REST 三种实现都只接收一条完整 SQL，返回统一的
 * {@link IotdbResultTable}。查询语义、分桶、差分等逻辑放在
 * {@link IotdbQueryService}，不在各通道重复实现。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public interface IotdbQueryExecutor extends AutoCloseable {

    /**
     * 执行一条查询 SQL。
     *
     * @param sql 完整 SQL，不能为 {@code null}
     * @return 非 {@code null} 的结果表
     */
    IotdbResultTable query(String sql);

    /**
     * 返回通道名称，用于日志与诊断。
     *
     * @return {@code native}、{@code jdbc} 或 {@code rest}
     */
    String transport();

    /**
     * 释放连接资源；实现必须幂等。
     */
    @Override
    void close();
}
