package io.github.forget_the_bright.tsdb.iotdb.internal.transport;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.github.forget_the_bright.tsdb.common.exception.TsdbDataException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbQueryException;
import io.github.forget_the_bright.tsdb.common.internal.json.JsonText;
import io.github.forget_the_bright.tsdb.common.model.Aggregation;
import io.github.forget_the_bright.tsdb.common.model.Direction;
import io.github.forget_the_bright.tsdb.common.model.HealthStatus;
import io.github.forget_the_bright.tsdb.common.model.Sample;
import io.github.forget_the_bright.tsdb.common.model.ValueType;
import io.github.forget_the_bright.tsdb.iotdb.IotdbConfig;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import io.github.forget_the_bright.tsdb.iotdb.internal.IotdbModels;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.Grouping;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.IotdbSql;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.IotdbTagPath;

/**
 * IoTDB REST v2（树模型）原生 API 客户端。
 *
 * <p>本类直接封装 IoTDB 的 <code>/rest/v2/query</code>、
 * <code>/rest/v2/nonQuery</code>、<code>/rest/v2/insertTablet</code>
 * 与 <code>/ping</code>，并保留原始 JSON 返回，因此没有阉割 IoTDB 的
 * SQL 与写入能力。公共接口由
 * {@link io.github.forget_the_bright.tsdb.iotdb.IotdbClient} 实现。</p>
 *
 * <p>JSON 承载类型为 fastjson：{@link #query(String)}、{@link #nonQuery(String)}
 * 与 {@link #insertTablet(JSONObject)} 返回 {@link JSONObject}，调用方可用
 * {@code getJSONArray}/{@code getString} 直接读取任意字段。</p>
 *
 * <p>IoTDB 的 REST 能力远不止“按标签查询”：任何树模型 SQL
 * （DDL、聚合、别名、LIMIT/OFFSET、SHOW 等）都可以通过
 * {@link #query(String)} 与 {@link #nonQuery(String)} 执行；批量写入
 * 既可以使用类型化的 {@link #write(String, List)}，也可以使用
 * {@link #insertTablet(JSONObject)} 直接提交厂商格式。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class IotdbRestApi {

    private final IotdbConfig config;
    private final IotdbHttpClient http;

    /**
     * 创建原生 REST 客户端。
     *
     * @param config 连接配置，不能为 {@code null}
     * @param http   传输层，不能为 {@code null}
     */
    public IotdbRestApi(IotdbConfig config, IotdbHttpClient http) {
        this.config = config;
        this.http = http;
    }

    /**
     * 按掩码列出时间序列。
     *
     * <p>掩码到 SQL 的转换只有一处（{@link IotdbSql#showTimeseries(String)}），
     * 与统一查询服务分支共用同一份规则；不带通配符的前缀由该方法补 {@code .**}。
     * 掩码非法（含白名单外的字符）直接抛错：过去这里会退化成“列出全部序列再在内存
     * 里比对”，看起来有结果，实际是把一个写错的掩码伪装成合法查询。</p>
     *
     * @param nameMask 掩码或路径前缀，如 {@code root.sg.d1}、{@code root.sg.*}；空表示全部
     * @return 位号列表，非 {@code null}
     * @throws TsdbDataException 掩码含非法字符
     */
    public List<IotdbModels.Tag> listTags(String nameMask) {
        String mask = nameMask == null ? "" : nameMask.trim();
        String sql = mask.isEmpty() ? "SHOW TIMESERIES" : IotdbSql.showTimeseries(mask);
        JSONObject root = query(sql);
        Object columnsValue = JsonText.value(root, "column_names");
        Object rowsValue = JsonText.value(root, "values");
        if (!(columnsValue instanceof JSONArray) || !(rowsValue instanceof JSONArray)) {
            return Collections.emptyList();
        }
        JSONArray columns = (JSONArray) columnsValue;
        JSONArray rows = (JSONArray) rowsValue;
        int nameIndex = indexOf(columns, "Timeseries");
        int aliasIndex = indexOf(columns, "Alias");
        int typeIndex = indexOf(columns, "DataType");
        int databaseIndex = indexOf(columns, "Database");
        if (nameIndex < 0) {
            return Collections.emptyList();
        }
        List<IotdbModels.Tag> tags = new ArrayList<IotdbModels.Tag>();
        for (Object rowValue : rows) {
            if (!(rowValue instanceof JSONArray)) {
                continue;
            }
            JSONArray row = (JSONArray) rowValue;
            if (row.size() <= nameIndex) {
                continue;
            }
            String name = text(row.get(nameIndex));
            tags.add(new IotdbModels.Tag(name,
                    aliasIndex >= 0 ? text(row.get(aliasIndex)) : null,
                    typeIndex >= 0 ? text(row.get(typeIndex)) : null,
                    databaseIndex >= 0 ? text(row.get(databaseIndex)) : null));
        }
        return tags;
    }

    public IotdbModels.Point readCurrent(String path) {
        JSONObject root = query(IotdbSql.currentBatch(Grouping.DEVICE, single(path)));
        Object timestampsValue = JsonText.value(root, "timestamps");
        Object valuesValue = JsonText.value(root, "values");
        if (!(timestampsValue instanceof JSONArray) || !(valuesValue instanceof JSONArray)) {
            return null;
        }
        JSONArray timestamps = (JSONArray) timestampsValue;
        JSONArray values = (JSONArray) valuesValue;
        if (timestamps.isEmpty() || values.isEmpty()) {
            return null;
        }
        Object columnValue = values.get(0);
        if (!(columnValue instanceof JSONArray) || ((JSONArray) columnValue).isEmpty()) {
            return null;
        }
        JSONArray column = (JSONArray) columnValue;
        return point(timestamps.getLongValue(0), column.get(0));
    }

    public List<IotdbModels.Point> readRaw(String path, Instant start, Instant end,
                                           Direction direction, int limit) {
        return points(query(IotdbSql.raw(Grouping.DEVICE, single(path),
                start.toEpochMilli(), end.toEpochMilli(), direction, limit)));
    }

    public List<IotdbModels.Point> readAggregated(String path, Aggregation aggregation,
                                                  Instant start, Instant end, long intervalMillis,
                                                  Direction direction, int limit) {
        return points(query(IotdbSql.aggregated(Grouping.DEVICE, single(path), aggregation,
                start.toEpochMilli(), end.toEpochMilli(), intervalMillis, direction, limit)));
    }

    /**
     * 按单序列写入采样点。
     *
     * <p><b>与查询侧的引号规则不同源，不是疏漏</b>：{@code insertTablet} 把设备放进
     * JSON 的 {@code device} 字段、测点放进 {@code measurements} 数组，那里 IoTDB 要的是
     * <b>不带反引号</b>的纯路径文本，加引号反而会被当成名称的一部分。因此本方法只用
     * {@link IotdbTagPath} 做拆分与校验，不取它的引用形态。</p>
     *
     * @param path    完整绝对路径，形如 {@code root.factory.RL.FCS0107.TI-1071815_PV}
     * @param samples 同一测点的采样点，空集合时直接返回
     */
    public void write(String path, List<Sample> samples) {
        if (samples == null || samples.isEmpty()) {
            return;
        }
        IotdbTagPath tagPath = IotdbTagPath.ofAbsolute(path);
        String device = tagPath.getDevicePath();
        String measurement = tagPath.getMeasurement();
        ValueType type = typeOf(samples);
        JSONObject body = new JSONObject();
        body.put("device", device);
        body.put("is_aligned", false);
        JSONArray timestamps = new JSONArray();
        JSONArray values = new JSONArray();
        JSONArray column = new JSONArray();
        values.add(column);
        for (Sample sample : samples) {
            timestamps.add(sample.getTimestamp().toEpochMilli());
            putValue(column, sample, type);
        }
        body.put("timestamps", timestamps);
        body.put("values", values);
        JSONArray measurements = new JSONArray();
        measurements.add(measurement);
        body.put("measurements", measurements);
        JSONArray dataTypes = new JSONArray();
        dataTypes.add(iotdbType(type));
        body.put("data_types", dataTypes);

        String response = http.post(config.apiUrl("/insertTablet"), body.toJSONString());
        validate(response, "insertTablet");
    }

    public HealthStatus checkHealth() {
        long start = System.nanoTime();
        try {
            String body = http.get(config.rootUrl("/ping"), null);
            if (body != null && body.trim().toLowerCase().contains("ok")) {
                return HealthStatus.up((System.nanoTime() - start) / 1000000L)
                        .withTimeFormat(config.getTimeConfig());
            }
            return HealthStatus.down("IoTDB ping returned: " + body,
                    (System.nanoTime() - start) / 1000000L)
                    .withTimeFormat(config.getTimeConfig());
        } catch (RuntimeException e) {
            return HealthStatus.down(e.getMessage(), (System.nanoTime() - start) / 1000000L)
                    .withTimeFormat(config.getTimeConfig());
        }
    }

    /**
     * 执行树模型查询 SQL，返回 IoTDB 原始列式 JSON。
     *
     * <p>响应通常包含 <code>expressions</code>、<code>timestamps</code>、
     * <code>values</code>；元数据语句（SHOW ...）使用 <code>column_names</code>
     * 与行数组。本方法不解析、不裁剪任何列。</p>
     *
     * @param sql 查询 SQL，例如 <code>SELECT * FROM root.sg.d1</code>
     * @return 原始响应 JSON
     * @throws TsdbQueryException SQL 执行失败（业务 code != 200）
     */
    public JSONObject query(String sql) {
        JSONObject body = new JSONObject();
        body.put("sql", sql);
        String response = http.post(config.apiUrl("/query"), body.toJSONString());
        return validate(response, "query");
    }

    /**
     * 执行非查询 SQL（DDL/DML，例如 CREATE TIMESERIES、DELETE、INSERT）。
     *
     * @param sql 非查询 SQL
     * @return 原始响应 JSON
     * @throws TsdbQueryException SQL 执行失败（业务 code != 200）
     */
    public JSONObject nonQuery(String sql) {
        JSONObject body = new JSONObject();
        body.put("sql", sql);
        String response = http.post(config.apiUrl("/nonQuery"), body.toJSONString());
        return validate(response, "nonQuery");
    }

    /**
     * 直接提交 IoTDB <code>insertTablet</code> 原始请求体。
     *
     * <p>请求体字段：<code>device</code>、<code>is_aligned</code>、
     * <code>timestamps</code>、<code>measurements</code>、
     * <code>data_types</code>、<code>values</code>。本方法不做字段校验，
     * 适合需要一次写入多测量列、或由上层自行组织批量数据的场景。</p>
     *
     * @param request 原始 insertTablet JSON
     * @return 原始响应 JSON
     */
    public JSONObject insertTablet(JSONObject request) {
        String response = http.post(config.apiUrl("/insertTablet"), request.toJSONString());
        return validate(response, "insertTablet");
    }

    /**
     * 探测 REST 服务是否可用。
     *
     * @return <code>/ping</code> 的原始文本
     */
    public String ping() {
        return http.get(config.rootUrl("/ping"), null);
    }

    private static List<IotdbTagPath> single(String path) {
        return Collections.singletonList(IotdbTagPath.ofAbsolute(path));
    }

    private JSONObject validate(String body, String operation) {
        Object parsed = JsonText.parse(body);
        if (!(parsed instanceof JSONObject)) {
            throw new TsdbDataException("IoTDB " + operation + " returned a non-object response");
        }
        JSONObject root = (JSONObject) parsed;
        Integer parsedCode = JsonText.integer(root, "code");
        int code = parsedCode == null ? 200 : parsedCode;
        if (code != 200) {
            String message = JsonText.text(root, "message");
            throw new TsdbQueryException("IoTDB " + operation + " failed: code=" + code
                    + (message == null || message.isEmpty() ? "" : " " + message));
        }
        return root;
    }

    private List<IotdbModels.Point> points(JSONObject root) {
        Object timestampsValue = JsonText.value(root, "timestamps");
        Object valuesValue = JsonText.value(root, "values");
        if (!(timestampsValue instanceof JSONArray) || !(valuesValue instanceof JSONArray)) {
            return Collections.emptyList();
        }
        JSONArray timestamps = (JSONArray) timestampsValue;
        JSONArray values = (JSONArray) valuesValue;
        if (values.isEmpty()) {
            return Collections.emptyList();
        }
        Object columnValue = values.get(0);
        if (!(columnValue instanceof JSONArray)) {
            return Collections.emptyList();
        }
        JSONArray column = (JSONArray) columnValue;
        int size = Math.min(timestamps.size(), column.size());
        List<IotdbModels.Point> points = new ArrayList<IotdbModels.Point>(size);
        for (int i = 0; i < size; i++) {
            IotdbModels.Point point = point(timestamps.getLongValue(i), column.get(i));
            if (point != null) {
                points.add(point);
            }
        }
        return points;
    }

    private IotdbModels.Point point(long epochMillis, Object value) {
        if (value == null) {
            return null;
        }
        Object converted;
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof BigInteger) {
            converted = ((Number) value).longValue();
        } else if (value instanceof Float || value instanceof Double || value instanceof BigDecimal) {
            converted = ((Number) value).doubleValue();
        } else if (value instanceof Boolean) {
            converted = value;
        } else {
            converted = String.valueOf(value);
        }
        return new IotdbModels.Point(Instant.ofEpochMilli(epochMillis), converted,
                config.getTimeConfig());
    }

    private static ValueType typeOf(List<Sample> samples) {
        boolean hasDouble = false;
        boolean hasLong = false;
        boolean hasBoolean = false;
        boolean hasText = false;
        for (Sample sample : samples) {
            ValueType type = sample.getValueType();
            if (type == ValueType.DOUBLE) {
                hasDouble = true;
            } else if (type == ValueType.LONG) {
                hasLong = true;
            } else if (type == ValueType.BOOLEAN) {
                hasBoolean = true;
            } else if (type == ValueType.STRING || type == ValueType.BYTES) {
                hasText = true;
            }
        }
        if (hasDouble) {
            return ValueType.DOUBLE;
        }
        if (hasLong) {
            return ValueType.LONG;
        }
        if (hasBoolean) {
            return ValueType.BOOLEAN;
        }
        if (hasText) {
            return ValueType.STRING;
        }
        return ValueType.STRING;
    }

    private static String iotdbType(ValueType type) {
        switch (type) {
            case DOUBLE:
                return "DOUBLE";
            case LONG:
                return "INT64";
            case BOOLEAN:
                return "BOOLEAN";
            default:
                return "TEXT";
        }
    }

    private static void putValue(JSONArray column, Sample sample, ValueType type) {
        if (sample.isNull()) {
            column.add(null);
            return;
        }
        if (type == ValueType.DOUBLE) {
            Double value = sample.getDoubleValue();
            column.add(value == null ? sample.getLongValue().doubleValue() : value);
        } else if (type == ValueType.LONG) {
            column.add(sample.getLongValue());
        } else if (type == ValueType.BOOLEAN) {
            column.add(sample.getBooleanValue());
        } else {
            String text = sample.getTextValue();
            column.add(text == null && sample.getValue() != null ? String.valueOf(sample.getValue()) : text);
        }
    }

    private static int indexOf(JSONArray columns, String name) {
        for (int i = 0; i < columns.size(); i++) {
            if (name.equalsIgnoreCase(text(columns.get(i)))) {
                return i;
            }
        }
        return -1;
    }

    private static String text(Object node) {
        return node == null ? null : String.valueOf(node);
    }
}
