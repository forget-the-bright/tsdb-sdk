package io.github.forget_the_bright.tsdb.iotdb.internal.transport;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.github.forget_the_bright.tsdb.common.config.TimeConfig;
import io.github.forget_the_bright.tsdb.common.internal.json.JsonText;
import io.github.forget_the_bright.tsdb.iotdb.IotdbConfig;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 REST v2 的 SQL 执行器。
 *
 * <p>负责把 {@code /rest/v2/query} 的列式 JSON 转成统一结果表；SQL 语义与
 * 原生/JDBC 通道完全一致。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class IotdbRestQueryExecutor implements IotdbQueryExecutor {

    private final IotdbRestApi api;
    private final TimeConfig timeConfig;

    /**
     * 创建 REST 执行器。
     *
     * @param config 驱动配置，不能为 {@code null}
     */
    public IotdbRestQueryExecutor(IotdbConfig config) {
        this.api = new IotdbRestApi(config, new IotdbHttpClient(config));
        this.timeConfig = config.getTimeConfig();
    }

    @Override
    public IotdbResultTable query(String sql) {
        return parse(api.query(sql), timeConfig);
    }

    @Override
    public String transport() {
        return "rest";
    }

    @Override
    public void close() {
        // REST 每次请求独立建连，无池化资源。
    }

    /**
     * 解析 REST 查询响应。
     *
     * <p><b>两种响应形态</b>（2026-11-13 生产 2.0.11 实测）：带时间列的数据集回
     * {@code expressions} + {@code timestamps} + 列优先的 {@code values}，
     * 其中 {@code SELECT LAST} 的 {@code expressions} 是固定的
     * {@code ["Timeseries","Value","DataType"]}，{@code timestamps} 逐序列给出；
     * {@code SHOW} 语句回 {@code column_names} + {@code values}。</p>
     *
     * <p><b>时间列必须解析而不是置 0</b>：列式形态里只要存在 {@code Time} 列，
     * 它就是该行的采样时刻；早先硬置 {@code 0L} 会让批量最新值的时间全部变成
     * 1970。反过来，{@code COUNT(...)} 与 {@code SELECT first_value(m),
     * last_value(m)} 这类聚合回 {@code timestamps:[]} 但值列有元素，行数必须
     * 按值列长度算，否则结果会被解析成 0 行。</p>
     *
     * @param root       响应 JSON，不能为 {@code null}
     * @param timeConfig 实例时间配置，用于把文本时间列解析回瞬时时刻，可为 {@code null}
     * @return 统一结果表
     */
    static IotdbResultTable parse(JSONObject root, TimeConfig timeConfig) {
        List<String> columns = new ArrayList<String>();
        List<IotdbResultTable.Row> rows = new ArrayList<IotdbResultTable.Row>();
        JSONArray columnNames = array(JsonText.value(root, "column_names"));
        JSONArray expressions = array(JsonText.value(root, "expressions"));
        JSONArray timestamps = array(JsonText.value(root, "timestamps"));
        JSONArray values = array(JsonText.value(root, "values"));

        if (columnNames != null && !columnNames.isEmpty()) {
            for (Object column : columnNames) {
                columns.add(String.valueOf(column));
            }
            // SHOW 语句同样是列优先：values 的每个元素是一整列，需要转置成行。
            int rowCount = 0;
            if (values != null) {
                for (Object columnValue : values) {
                    JSONArray column = array(columnValue);
                    rowCount = Math.max(rowCount, column == null ? 0 : column.size());
                }
            }
            int timeIndex = indexOf(columns, "Time");
            if (timeIndex < 0) {
                timeIndex = indexOf(columns, "Timestamp");
            }
            for (int i = 0; i < rowCount; i++) {
                Object[] rowValues = new Object[columns.size()];
                if (values != null) {
                    for (int c = 0; c < columns.size() && c < values.size(); c++) {
                        JSONArray column = array(values.get(c));
                        rowValues[c] = column != null && i < column.size() ? toObject(column.get(i)) : null;
                    }
                }
                long timestamp = timeIndex >= 0 && timeIndex < rowValues.length
                        ? toMillis(rowValues[timeIndex], timeConfig)
                        : 0L;
                rows.add(new IotdbResultTable.Row(timestamp, rowValues));
            }
            return new IotdbResultTable(columns, rows);
        }

        if (expressions != null) {
            for (Object expression : expressions) {
                columns.add(String.valueOf(expression));
            }
        }
        if (values == null) {
            return new IotdbResultTable(columns, rows);
        }
        int rowCount = timestamps == null ? 0 : timestamps.size();
        if (rowCount == 0) {
            // 无时间列的聚合（COUNT、first_value/last_value 组合）只有一个逻辑行
            for (Object columnValue : values) {
                JSONArray column = array(columnValue);
                rowCount = Math.max(rowCount, column == null ? 0 : column.size());
            }
        }
        for (int i = 0; i < rowCount; i++) {
            Object[] rowValues = new Object[Math.max(columns.size(), values.size())];
            for (int c = 0; c < values.size(); c++) {
                JSONArray column = array(values.get(c));
                rowValues[c] = column != null && i < column.size() ? toObject(column.get(i)) : null;
            }
            rows.add(new IotdbResultTable.Row(
                    timestamps != null && i < timestamps.size() ? timestamps.getLongValue(i) : 0L,
                    rowValues));
        }
        return new IotdbResultTable(columns, rows);
    }

    private static JSONArray array(Object value) {
        return value instanceof JSONArray ? (JSONArray) value : null;
    }

    /**
     * 按名称（忽略大小写）查找列下标。
     *
     * @param columns 列名列表
     * @param name    目标列名
     * @return 下标；不存在时 {@code -1}
     */
    private static int indexOf(List<String> columns, String name) {
        for (int i = 0; i < columns.size(); i++) {
            if (name.equalsIgnoreCase(columns.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 把时间列的原始值转换为 epoch 毫秒。
     *
     * <p>数值形态直接用；文本形态先尝试当作毫秒数，再退回实例的
     * {@link TimeConfig} 解析（服务端可能按 {@code time_output_precision}
     * 输出格式化时间）。都解析不了时返回 {@code 0L} 并保持行内容不变，
     * 由上层按“时间未知”处理——解析失败不该让整个查询失败。</p>
     *
     * @param value      时间列原始值，可为 {@code null}
     * @param timeConfig 实例时间配置，可为 {@code null}
     * @return epoch 毫秒
     */
    private static long toMillis(Object value, TimeConfig timeConfig) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) {
            return 0L;
        }
        String text = String.valueOf(value).trim();
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            // 不是纯数字，继续按配置的文本时间格式解析
        }
        if (timeConfig == null) {
            return 0L;
        }
        try {
            return timeConfig.parseResponse(text).toEpochMilli();
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private static Object toObject(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean) {
            return value;
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof BigInteger) {
            return ((Number) value).longValue();
        }
        if (value instanceof Float || value instanceof Double || value instanceof BigDecimal) {
            return ((Number) value).doubleValue();
        }
        return String.valueOf(value);
    }
}
