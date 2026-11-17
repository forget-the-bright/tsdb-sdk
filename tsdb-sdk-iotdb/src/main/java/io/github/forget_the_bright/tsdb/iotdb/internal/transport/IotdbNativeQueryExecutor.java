package io.github.forget_the_bright.tsdb.iotdb.internal.transport;

import io.github.forget_the_bright.tsdb.common.exception.TsdbConnectionException;
import io.github.forget_the_bright.tsdb.iotdb.IotdbConfig;
import org.apache.iotdb.isession.pool.SessionDataSetWrapper;
import org.apache.iotdb.session.pool.SessionPool;
import org.apache.tsfile.read.common.Field;
import org.apache.tsfile.read.common.RowRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于原生 SessionPool 的 SQL 执行器。
 *
 * <p>SessionPool 自带连接复用与故障转移，是三种通道里吞吐最高的一种；
 * 查询结果通过 {@link SessionDataSetWrapper} 读取，列名通常以 {@code Time}
 * 开头，但值列表不含时间列，因此解析时按列名判断偏移。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class IotdbNativeQueryExecutor implements IotdbQueryExecutor {

    private final IotdbConfig config;
    private volatile SessionPool pool;

    /**
     * 创建原生执行器。
     *
     * @param config 驱动配置，不能为 {@code null}
     */
    public IotdbNativeQueryExecutor(IotdbConfig config) {
        this.config = config;
    }

    @Override
    public IotdbResultTable query(String sql) {
        SessionDataSetWrapper wrapper = null;
        try {
            wrapper = pool().executeQueryStatement(sql);
            List<String> columns = wrapper.getColumnNames();
            int offset = !columns.isEmpty() && "Time".equalsIgnoreCase(columns.get(0)) ? 1 : 0;
            List<IotdbResultTable.Row> rows = new ArrayList<IotdbResultTable.Row>();
            while (wrapper.hasNext()) {
                RowRecord record = wrapper.next();
                List<Field> fields = record.getFields();
                Object[] values = new Object[fields.size()];
                for (int i = 0; i < fields.size(); i++) {
                    Field field = fields.get(i);
                    values[i] = field == null ? null : field.getObjectValue(field.getDataType());
                }
                rows.add(new IotdbResultTable.Row(record.getTimestamp(), values));
            }
            List<String> dataColumns = offset == 0 ? columns : columns.subList(1, columns.size());
            return new IotdbResultTable(dataColumns, rows);
        } catch (Exception e) {
            throw new TsdbConnectionException("IoTDB native query failed: " + e.getMessage(), e);
        } finally {
            if (wrapper != null) {
                pool.closeResultSet(wrapper);
            }
        }
    }

    private SessionPool pool() {
        if (pool == null) {
            synchronized (this) {
                if (pool == null) {
                    pool = new SessionPool.Builder()
                            .host(config.getHost())
                            .port(config.getRpcPort())
                            .user(config.getUsername())
                            .password(config.getPassword())
                            .maxSize(4)
                            .fetchSize(1024)
                            .connectionTimeoutInMs((int) Math.min(Integer.MAX_VALUE,
                                    config.getConnectTimeoutMillis()))
                            .queryTimeoutInMs(config.getReadTimeoutMillis())
                            .build();
                }
            }
        }
        return pool;
    }

    @Override
    public String transport() {
        return "native";
    }

    @Override
    public void close() {
        SessionPool current = pool;
        if (current != null) {
            current.close();
            pool = null;
        }
    }
}
