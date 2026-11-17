package io.github.forget_the_bright.tsdb.iotdb.internal.transport;

import io.github.forget_the_bright.tsdb.common.exception.TsdbConnectionException;
import io.github.forget_the_bright.tsdb.iotdb.IotdbConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 JDBC 的 SQL 执行器。
 *
 * <p>连接串固定为 {@code jdbc:iotdb://host:port/}，驱动类
 * {@code org.apache.iotdb.jdbc.IoTDBDriver} 由依赖自动注册；每次查询独立建连，
 * 避免把连接生命周期泄漏给调用方。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class IotdbJdbcQueryExecutor implements IotdbQueryExecutor {

    private final IotdbConfig config;

    /**
     * 创建 JDBC 执行器。
     *
     * @param config 驱动配置，不能为 {@code null}
     */
    public IotdbJdbcQueryExecutor(IotdbConfig config) {
        this.config = config;
    }

    @Override
    public IotdbResultTable query(String sql) {
        String url = "jdbc:iotdb://" + config.getHost() + ":" + config.getRpcPort() + "/";
        try {
            Class.forName("org.apache.iotdb.jdbc.IoTDBDriver");
        } catch (ClassNotFoundException e) {
            throw new TsdbConnectionException("IoTDB JDBC driver is not on classpath", e);
        }
        try (Connection connection = DriverManager.getConnection(url, config.getUsername(), config.getPassword());
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout((int) Math.max(1L, config.getReadTimeoutMillis() / 1000L));
            try (ResultSet resultSet = statement.executeQuery(sql)) {
                ResultSetMetaData meta = resultSet.getMetaData();
                boolean skipTime = "Time".equalsIgnoreCase(meta.getColumnLabel(1));
                List<String> columns = new ArrayList<String>();
                for (int i = skipTime ? 2 : 1; i <= meta.getColumnCount(); i++) {
                    columns.add(meta.getColumnLabel(i));
                }
                List<IotdbResultTable.Row> rows = new ArrayList<IotdbResultTable.Row>();
                while (resultSet.next()) {
                    Object[] values = new Object[columns.size()];
                    int firstValueColumn = skipTime ? 2 : 1;
                    for (int i = 0; i < values.length; i++) {
                        values[i] = resultSet.getObject(firstValueColumn + i);
                    }
                    rows.add(new IotdbResultTable.Row(skipTime ? resultSet.getLong(1) : 0L, values));
                }
                return new IotdbResultTable(columns, rows);
            }
        } catch (Exception e) {
            throw new TsdbConnectionException("IoTDB JDBC query failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String transport() {
        return "jdbc";
    }

    @Override
    public void close() {
        // 每次查询独立建连，无池化资源。
    }
}
