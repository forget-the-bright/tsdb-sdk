package io.github.forget_the_bright.tsdb.iotdb.internal.transport;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import io.github.forget_the_bright.tsdb.common.config.SecurityConfig;
import io.github.forget_the_bright.tsdb.common.config.TsdbInstanceConfig;
import io.github.forget_the_bright.tsdb.iotdb.IotdbConfig;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IotdbRestQueryExecutor#parse} 的单元测试：用生产实测到的三种 REST 响应
 * 形态验证结果表转置，不连接数据库。
 *
 * <p>响应形态取自 2026-11-13 对生产 2.0.11 的只读实测：
 * ①{@code SELECT LAST} 回 {@code expressions=["Timeseries","Value","DataType"]}
 * 且 {@code timestamps} 逐序列给出；②无时间列的聚合（{@code COUNT}、
 * {@code first_value}/{@code last_value} 组合）回 {@code timestamps:[]} 但值列
 * 有元素；③列式（{@code column_names}）形态里若存在 {@code Time} 列，它必须是
 * 该行的采样时刻。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
class IotdbRestQueryExecutorTest {

    @Test
    void readsPerSeriesTimestampsFromLastQueryShape() {
        IotdbResultTable table = IotdbRestQueryExecutor.parse(json(
                "{\"expressions\":[\"Timeseries\",\"Value\",\"DataType\"],"
                        + "\"timestamps\":[1700000100000,1700000200000],"
                        + "\"values\":[[\"root.sg.d1.temperature\",\"root.sg.d1.humidity\"],"
                        + "[\"26.5\",\"48.2\"],[\"FLOAT\",\"FLOAT\"]]}"), null);

        assertEquals(2, table.getRows().size());
        assertEquals("Timeseries", table.getColumns().get(0));
        assertEquals(1700000100000L, table.getRows().get(0).getTimestamp());
        assertEquals("root.sg.d1.humidity", table.getRows().get(1).getValues()[0]);
        assertEquals("48.2", table.getRows().get(1).getValues()[1]);
    }

    @Test
    void keepsOneRowWhenAggregationHasNoTimeColumn() {
        // 实测：COUNT 与 first_value/last_value 返回 timestamps:[]，值列各有一个元素；
        // 若按 timestamps 长度算行数，这类结果会被解析成 0 行。
        IotdbResultTable count = IotdbRestQueryExecutor.parse(json(
                "{\"expressions\":[\"COUNT(root.simdemo.boiler_01.temperature)\"],"
                        + "\"timestamps\":[],\"values\":[[412]]}"), null);
        assertEquals(1, count.getRows().size());
        assertEquals(412L, count.getRows().get(0).getValues()[0]);
        assertEquals(0L, count.getRows().get(0).getTimestamp());

        IotdbResultTable anchors = IotdbRestQueryExecutor.parse(json(
                "{\"expressions\":[\"first_value(root.simdemo.boiler_01.temperature)\","
                        + "\"last_value(root.simdemo.boiler_01.temperature)\"],"
                        + "\"timestamps\":[],\"values\":[[20.0],[95.0]]}"), null);
        assertEquals(1, anchors.getRows().size());
        assertEquals(20.0, ((Number) anchors.getRows().get(0).getValues()[0]).doubleValue(), 0.0001);
        assertEquals(95.0, ((Number) anchors.getRows().get(0).getValues()[1]).doubleValue(), 0.0001);
    }

    @Test
    void parsesTimeColumnOfColumnarResponse() {
        IotdbResultTable table = IotdbRestQueryExecutor.parse(json(
                "{\"column_names\":[\"Time\",\"device\",\"temperature\"],"
                        + "\"values\":[[1700000100000,1700000200000],"
                        + "[\"d1\",\"d1\"],[26.5,27.5]]}"), null);

        // 列式分支过去把时间硬置 0L，最新值与曲线的采样时刻会全部退化成 1970
        assertEquals(2, table.getRows().size());
        assertEquals(1700000100000L, table.getRows().get(0).getTimestamp());
        assertEquals(1700000200000L, table.getRows().get(1).getTimestamp());
        assertEquals("d1", table.getRows().get(1).getValues()[1]);
    }

    @Test
    void parsesFormattedTimeColumnWithInstanceTimeConfig() {
        IotdbResultTable table = IotdbRestQueryExecutor.parse(json(
                "{\"column_names\":[\"Time\",\"temperature\"],"
                        + "\"values\":[[\"2023-11-15 06:13:21\"],[26.5]]}"),
                eastEightConfig().getTimeConfig());

        assertEquals(Instant.parse("2023-11-14T22:13:21Z").toEpochMilli(),
                table.getRows().get(0).getTimestamp());
    }

    @Test
    void keepsZeroTimestampForShowStatementsWithoutTimeColumn() {
        IotdbResultTable table = IotdbRestQueryExecutor.parse(json(
                "{\"column_names\":[\"Timeseries\",\"Alias\",\"Database\",\"DataType\"],"
                        + "\"values\":[[\"root.sg.d1.temperature\"],[\"temperature\"],"
                        + "[\"root.sg\"],[\"DOUBLE\"]]}"), null);

        assertEquals(1, table.getRows().size());
        assertEquals(0L, table.getRows().get(0).getTimestamp());
        assertEquals("DOUBLE", table.getRows().get(0).getValues()[3]);
    }

    @Test
    void toleratesUnparsableTimeColumnInsteadOfFailingTheQuery() {
        IotdbResultTable table = IotdbRestQueryExecutor.parse(json(
                "{\"column_names\":[\"Time\",\"temperature\"],"
                        + "\"values\":[[\"not-a-time\"],[26.5]]}"), null);

        // 时间读不出来不该让整个查询失败：置 0 并保持行内容，由上层按时间未知处理
        assertEquals(1, table.getRows().size());
        assertEquals(0L, table.getRows().get(0).getTimestamp());
        assertTrue(table.getRows().get(0).getValues()[1] != null);
    }

    private static IotdbConfig eastEightConfig() {
        TsdbInstanceConfig instance = new TsdbInstanceConfig();
        instance.setBaseUrl("http://127.0.0.1:18080");
        instance.getAuth().setUsername("root");
        instance.getAuth().setPassword("root");
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("source-zone", "GMT+8");
        response.put("target-zone", "GMT+8");
        response.put("pattern", "yyyy-MM-dd HH:mm:ss");
        Map<String, Object> time = new LinkedHashMap<String, Object>();
        time.put("response", response);
        instance.getOptions().put("time", time);
        SecurityConfig security = new SecurityConfig();
        security.setAllowInsecureTransport(true);
        return new IotdbConfig("iotdb-parse", instance, security);
    }

    private static JSONObject json(String text) {
        return JSON.parseObject(text);
    }
}
