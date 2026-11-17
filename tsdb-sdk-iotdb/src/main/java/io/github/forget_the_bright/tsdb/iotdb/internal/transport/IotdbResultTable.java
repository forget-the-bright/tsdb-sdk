package io.github.forget_the_bright.tsdb.iotdb.internal.transport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 三种 IoTDB 通道共用的查询结果表。
 *
 * <p>列顺序与 SQL 的 select 列表一致；时间列由 {@link Row#getTimestamp()}
 * 单独承载，不混在值数组里，避免 REST 的列式响应、JDBC 的首列 Time 和
 * 原生 RowRecord 的字段布局差异泄漏到上层。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class IotdbResultTable {

    private final List<String> columns;
    private final List<Row> rows;

    /**
     * 创建结果表。
     *
     * @param columns 列名，可为 {@code null}
     * @param rows    行数据，可为 {@code null}
     */
    public IotdbResultTable(List<String> columns, List<Row> rows) {
        this.columns = columns == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(columns));
        this.rows = rows == null
                ? Collections.<Row>emptyList()
                : Collections.unmodifiableList(new ArrayList<Row>(rows));
    }

    /**
     * 返回列名。
     *
     * @return 不可修改列表，非 {@code null}
     */
    public List<String> getColumns() {
        return columns;
    }

    /**
     * 返回数据行。
     *
     * @return 不可修改列表，非 {@code null}
     */
    public List<Row> getRows() {
        return rows;
    }

    /**
     * 返回第一行第一列；无数据时返回 {@code null}。
     *
     * @return 标量值或 {@code null}
     */
    public Object firstValue() {
        return rows.isEmpty() || rows.get(0).values.length == 0 ? null : rows.get(0).values[0];
    }

    /**
     * 一行查询结果。
     */
    public static final class Row {
        private final long timestamp;
        private final Object[] values;

        /**
         * 创建一行。
         *
         * @param timestamp epoch 毫秒；无时间列时为 0
         * @param values    值数组，可为 {@code null}
         */
        public Row(long timestamp, Object[] values) {
            this.timestamp = timestamp;
            this.values = values == null ? new Object[0] : values;
        }

        /**
         * 返回时间戳。
         *
         * @return epoch 毫秒
         */
        public long getTimestamp() {
            return timestamp;
        }

        /**
         * 返回值数组。
         *
         * @return 非 {@code null} 的数组
         */
        public Object[] getValues() {
            return values;
        }
    }
}
