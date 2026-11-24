package io.github.forget_the_bright.tsdb.iotdb;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import io.github.forget_the_bright.tsdb.common.config.AuthConfig;
import io.github.forget_the_bright.tsdb.common.config.SecurityConfig;
import io.github.forget_the_bright.tsdb.common.config.TsdbInstanceConfig;
import io.github.forget_the_bright.tsdb.common.exception.TsdbConfigurationException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbQueryException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException;
import io.github.forget_the_bright.tsdb.common.model.Aggregation;
import io.github.forget_the_bright.tsdb.common.model.Capability;
import io.github.forget_the_bright.tsdb.common.model.Direction;
import io.github.forget_the_bright.tsdb.common.model.HistoryQuery;
import io.github.forget_the_bright.tsdb.common.model.QueryMode;
import io.github.forget_the_bright.tsdb.common.model.Quality;
import io.github.forget_the_bright.tsdb.common.model.Sample;
import io.github.forget_the_bright.tsdb.common.model.TagInfo;
import io.github.forget_the_bright.tsdb.common.model.TagQuery;
import io.github.forget_the_bright.tsdb.common.model.TagSample;
import io.github.forget_the_bright.tsdb.common.model.TagSeries;
import io.github.forget_the_bright.tsdb.common.model.ValueType;
import io.github.forget_the_bright.tsdb.common.model.WriteRequest;
import io.github.forget_the_bright.tsdb.common.model.WriteResult;
import io.github.forget_the_bright.tsdb.iotdb.internal.transport.IotdbHttpClient;
import io.github.forget_the_bright.tsdb.iotdb.internal.transport.IotdbQueryExecutor;
import io.github.forget_the_bright.tsdb.iotdb.internal.query.IotdbQueryService;
import io.github.forget_the_bright.tsdb.iotdb.internal.transport.IotdbResultTable;
import io.github.forget_the_bright.tsdb.iotdb.internal.transport.IotdbRestApi;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IotdbClientTest {

    private HttpServer server;
    private int port;
    private final AtomicReference<String> lastSql = new AtomicReference<String>();
    private final AtomicReference<String> lastWrite = new AtomicReference<String>();
    private final AtomicReference<String> lastNonQuerySql = new AtomicReference<String>();
    private IotdbClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/rest/v2/query", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String body = read(exchange.getRequestBody());
                String sql = extractSql(body);
                lastSql.set(sql);
                String response;
                if (sql.contains("SHOW TIMESERIES")) {
                    response = "{\"code\":200,\"column_names\":[\"Timeseries\",\"Alias\",\"Database\",\"DataType\"],"
                            + "\"values\":["
                            + "[\"root.sg.d1.temperature\",\"temperature\",\"root.sg\",\"DOUBLE\"],"
                            + "[\"root.sg.d1.pressure\",\"pressure\",\"root.sg\",\"FLOAT\"],"
                            + "[\"root.sg.d2.running\",\"running\",\"root.sg\",\"BOOLEAN\"]]}";
                } else if (sql.contains("GROUP BY")) {
                    response = "{\"code\":200,\"expressions\":[\"avg(root.sg.d1.temperature)\"],"
                            + "\"timestamps\":[1700000000000,1700000060000],"
                            + "\"values\":[[25.5,26.0]]}";
                } else if (sql.contains("SELECT LAST") && sql.contains("missing")) {
                    response = "{\"code\":200,\"expressions\":[],\"timestamps\":[],\"values\":[]}";
                } else if (sql.contains("SELECT LAST")) {
                    response = "{\"code\":200,\"expressions\":[\"last(root.sg.d1.temperature)\"],"
                            + "\"timestamps\":[1700000100000],\"values\":[[26.5]]}";
                } else {
                    response = "{\"code\":200,\"expressions\":[\"root.sg.d1.temperature\"],"
                            + "\"timestamps\":[1700000000000,1700000001000],\"values\":[[25.5,25.8]]}";
                }
                send(exchange, 200, response);
            }
        });
        server.createContext("/rest/v2/insertTablet", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                lastWrite.set(read(exchange.getRequestBody()));
                send(exchange, 200, "{\"code\":200,\"message\":\"SUCCESS_STATUS\"}");
            }
        });
        server.createContext("/rest/v2/nonQuery", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                lastNonQuerySql.set(extractSql(read(exchange.getRequestBody())));
                send(exchange, 200, "{\"code\":200,\"message\":\"SUCCESS_STATUS\"}");
            }
        });
        server.createContext("/ping", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                send(exchange, 200, "ok");
            }
        });
        server.start();
        client = newClient(false);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        server.stop(0);
    }

    private IotdbClient newClient(boolean allowWrite) {
        TsdbInstanceConfig instance = new TsdbInstanceConfig();
        instance.setType("iotdb");
        instance.setBaseUrl("http://127.0.0.1:" + port);
        AuthConfig auth = new AuthConfig();
        auth.setUsername("root");
        auth.setPassword("root");
        instance.setAuth(auth);
        // 本工厂专门覆盖“不装配查询服务”的 REST 直连路径，而这条路径默认在
        // 构造期就失败，所以必须显式放行；忘写这一行会让整批用例抛配置异常，
        // 而不是悄悄测到别的东西。
        instance.getOptions().put("require-query-service", "false");
        SecurityConfig security = new SecurityConfig();
        security.setAllowInsecureTransport(true);
        security.setAllowWrite(allowWrite);
        IotdbConfig config = new IotdbConfig("iotdb_main", instance, security);
        return new IotdbClient(config, new IotdbRestApi(config, new IotdbHttpClient(config)));
    }

    /**
     * 构造装配了统一 SQL 查询服务的客户端。
     *
     * <p>采样、插值与整窗增量三条路径都依赖 {@link IotdbQueryService}，因此这些
     * 用例必须走本工厂而不是 {@link #newClient(boolean)}；查询服务用脚本化
     * 执行器替掉，不触网。{@code options} 透传给实例配置，用于验证
     * {@code interpolation-raw-limit} 之类的驱动选项。</p>
     *
     * @param executor SQL 执行器替身，不能为 {@code null}
     * @param options  实例 {@code options}，可为 {@code null}
     * @return 带查询服务的客户端
     */
    private IotdbClient newClientWithQueryService(IotdbQueryExecutor executor, Map<String, Object> options) {
        TsdbInstanceConfig instance = new TsdbInstanceConfig();
        instance.setType("iotdb");
        instance.setBaseUrl("http://127.0.0.1:" + port);
        instance.getAuth().setUsername("root");
        instance.getAuth().setPassword("root");
        if (options != null) {
            instance.getOptions().putAll(options);
        }
        SecurityConfig security = new SecurityConfig();
        security.setAllowInsecureTransport(true);
        IotdbConfig config = new IotdbConfig("iotdb_service", instance, security);
        return new IotdbClient(config, new IotdbRestApi(config, new IotdbHttpClient(config)),
                new IotdbQueryService(config, executor));
    }

    @Test
    void exposesReadCapabilitiesAndSingleInstance() {
        assertEquals("iotdb_main", client.instanceName());
        assertEquals("iotdb", client.type());
        assertTrue(client.capabilities().supports(Capability.TAG_BROWSE));
        assertTrue(client.capabilities().supports(Capability.CURRENT_VALUE));
        assertTrue(client.capabilities().supports(Capability.RAW_HISTORY));
        assertTrue(client.capabilities().supports(Capability.AGGREGATED_HISTORY));
        assertFalse(client.capabilities().supports(Capability.WRITE));
    }

    @Test
    void listsTagsAndAppliesPagingAndTypeFilter() {
        List<TagInfo> all = client.tagCatalog().listTags(TagQuery.builder().limit(0).build());
        assertEquals(3, all.size());
        assertEquals("root.sg.d1.temperature", all.get(0).getTagName());
        assertEquals("temperature", all.get(0).getDescription());
        assertEquals(ValueType.DOUBLE, all.get(0).getValueType());
        assertEquals("root.sg", all.get(0).getAttributes().get("database"));

        List<TagInfo> page = client.tagCatalog().listTags(TagQuery.builder().offset(1).limit(1).build());
        assertEquals(1, page.size());
        assertEquals("root.sg.d1.pressure", page.get(0).getTagName());

        List<TagInfo> booleans = client.tagCatalog().listTags(
                TagQuery.builder().valueType(ValueType.BOOLEAN).limit(0).build());
        assertEquals(1, booleans.size());
        assertEquals(ValueType.BOOLEAN, booleans.get(0).getValueType());

        TagInfo exact = client.tagCatalog().getTag("root.sg.d1.pressure");
        assertNotNull(exact);
        assertEquals(ValueType.DOUBLE, exact.getValueType());
    }

    @Test
    void rejectsUnitFilter() {
        assertThrows(TsdbUnsupportedException.class, () ->
                client.tagCatalog().listTags(TagQuery.builder().unit("C").build()));
    }

    @Test
    void readsCurrentValuesInRequestOrder() {
        List<TagSample> values = client.currentValues().read(Arrays.asList(
                "root.sg.d1.temperature", "root.sg.d1.missing"));
        assertEquals(2, values.size());
        assertEquals("root.sg.d1.temperature", values.get(0).getTagName());
        assertNotNull(values.get(0).getSample());
        assertEquals(26.5, values.get(0).getSample().getDoubleValue(), 0.0001);
        assertEquals(Instant.ofEpochMilli(1700000100000L), values.get(0).getSample().getTimestamp());
        assertEquals(Date.from(Instant.ofEpochMilli(1700000100000L)),
                values.get(0).getSample().getTimestampDate());
        assertEquals("2023-11-14 22:15:00", values.get(0).getSample().getTimestampText());
        assertEquals(Quality.GOOD, values.get(0).getSample().getQuality());
        assertEquals("root.sg.d1.missing", values.get(1).getTagName());
        assertNull(values.get(1).getSample());
    }

    @Test
    void readsRawHistoryWithTimeRangeAndDirection() {
        HistoryQuery query = HistoryQuery.builder()
                .tags("root.sg.d1.temperature")
                .range(Instant.ofEpochMilli(1700000000000L), Instant.ofEpochMilli(1700000600000L))
                .mode(QueryMode.RAW)
                .maxPoints(2)
                .direction(Direction.FORWARD)
                .build();
        List<TagSeries> series = client.history().read(query);
        assertEquals(1, series.size());
        assertEquals(2, series.get(0).getSamples().size());
        assertEquals(25.5, series.get(0).getSamples().get(0).getDoubleValue(), 0.0001);
        assertEquals(Instant.ofEpochMilli(1700000000000L), series.get(0).getSamples().get(0).getTimestamp());
        String sql = lastSql.get();
        // 标识符逐节点加反引号，首节点 root 必须裸写（含 - 的位号不加引号会被解析成减法）
        assertTrue(sql.contains("SELECT `temperature` FROM root.`sg`.`d1`"));
        assertTrue(sql.contains("time >= 1700000000000 AND time < 1700000600000"));
        assertTrue(sql.contains("ORDER BY TIME ASC"));
        assertTrue(sql.contains("LIMIT 3"));
        assertFalse(series.get(0).isTruncated());
    }

    @Test
    void readsAggregatedHistory() {
        HistoryQuery query = HistoryQuery.builder()
                .tags("root.sg.d1.temperature")
                .range(Instant.ofEpochMilli(1700000000000L), Instant.ofEpochMilli(1700000600000L))
                .mode(QueryMode.AGGREGATED)
                .aggregation(Aggregation.AVG)
                .interval(java.time.Duration.ofMinutes(1))
                .maxPoints(10)
                .build();
        List<TagSeries> series = client.history().read(query);
        assertEquals(2, series.get(0).getSamples().size());
        String sql = lastSql.get();
        assertTrue(sql.contains("AVG(`temperature`)"));
        // 裸毫秒数会被 2.0.11 解析器拒绝，窗口长度换算成自然单位字面量
        assertTrue(sql.contains("GROUP BY ([1700000000000,1700000600000),1m)"));
    }

    @Test
    void rejectsUnsupportedModeAndAggregation() {
        assertThrows(TsdbUnsupportedException.class, () -> client.history().read(HistoryQuery.builder()
                .tags("root.sg.d1.temperature")
                .range(Instant.ofEpochMilli(1700000000000L), Instant.ofEpochMilli(1700000600000L))
                .mode(QueryMode.SAMPLED)
                .interval(java.time.Duration.ofMinutes(1))
                .build()));

        assertThrows(TsdbUnsupportedException.class, () -> client.history().read(HistoryQuery.builder()
                .tags("root.sg.d1.temperature")
                .range(Instant.ofEpochMilli(1700000000000L), Instant.ofEpochMilli(1700000600000L))
                .mode(QueryMode.AGGREGATED)
                .aggregation(Aggregation.BOUND)
                .interval(java.time.Duration.ofMinutes(1))
                .build()));
    }

    @Test
    void rejectsIntervalSumWithoutQueryService() {
        // INTERVAL_SUM 走整窗“末值减首值”的专用 SQL，该 SQL 由统一查询服务发出。
        // 这里用旧的两参构造器（无查询服务）创建客户端，必须显式拒绝，
        // 绝不静默退化成一次 SUM 查询。
        assertThrows(TsdbUnsupportedException.class, () -> client.history().read(HistoryQuery.builder()
                .tags("root.sg.d1.temperature")
                .range(Instant.ofEpochMilli(1700000000000L), Instant.ofEpochMilli(1700000600000L))
                .mode(QueryMode.AGGREGATED)
                .aggregation(Aggregation.INTERVAL_SUM)
                .interval(java.time.Duration.ofMinutes(1))
                .build()));
    }

    @Test
    void readsIntervalSumAsOnePointPerTag() {
        FixedExecutor executor = new FixedExecutor();
        // 一个块一条语句、每位号两列：4 列只有 1 个逻辑行（实测 M7）
        executor.firstLastRows = rows(resultRow(0L, 100.0, 157.5, 10.0, 20.0));
        IotdbClient serviceClient = newClientWithQueryService(executor,
                java.util.Collections.<String, Object>emptyMap());
        try {
            List<TagSeries> series = serviceClient.history().read(HistoryQuery.builder()
                    .tags("root.sg.d1.temperature", "root.sg.d1.pressure")
                    .range(Instant.ofEpochMilli(1700000000000L), Instant.ofEpochMilli(1700000600000L))
                    .mode(QueryMode.AGGREGATED)
                    .aggregation(Aggregation.INTERVAL_SUM)
                    .interval(java.time.Duration.ofMinutes(1))
                    .build());

            assertEquals(1, executor.sqls.size());
            assertEquals("SELECT first_value(`d1`.`temperature`), last_value(`d1`.`temperature`),"
                    + " first_value(`d1`.`pressure`), last_value(`d1`.`pressure`) FROM root.`sg`"
                    + " WHERE time >= 1700000000000 AND time < 1700000600000", executor.sqls.get(0));
            assertEquals(2, series.size());
            // 整窗增量是单点序列，时间戳落在窗口起点
            assertEquals(1, series.get(0).getSamples().size());
            assertEquals(57.5, series.get(0).getSamples().get(0).getDoubleValue(), 0.0001);
            assertEquals(Instant.ofEpochMilli(1700000000000L),
                    series.get(0).getSamples().get(0).getTimestamp());
            // 第二个位号读的是它自己那一对列，不是第一个位号的首末值
            assertEquals(1, series.get(1).getSamples().size());
            assertEquals(10.0, series.get(1).getSamples().get(0).getDoubleValue(), 0.0001);
            assertFalse(executor.sqls.get(0).contains("GROUP BY"));
        } finally {
            serviceClient.close();
        }
    }

    @Test
    void marksMissingIntervalSumAnchorInsteadOfReturningZero() {
        FixedExecutor executor = new FixedExecutor();
        executor.firstLastRows = java.util.Collections.singletonList(
                resultRow(0L, 100.0, null));
        IotdbClient serviceClient = newClientWithQueryService(executor,
                java.util.Collections.<String, Object>emptyMap());
        try {
            List<TagSeries> series = serviceClient.history().read(HistoryQuery.builder()
                    .tags("root.sg.d1.temperature")
                    .range(Instant.ofEpochMilli(1700000000000L), Instant.ofEpochMilli(1700000600000L))
                    .mode(QueryMode.AGGREGATED)
                    .aggregation(Aggregation.INTERVAL_SUM)
                    .interval(java.time.Duration.ofMinutes(1))
                    .build());
            // 锚点缺失只说明没有数据，补 0 会伪装成“这段时间没有消耗”
            assertTrue(series.get(0).getSamples().isEmpty());
            assertEquals("true", series.get(0).getAttributes().get("intervalSumMissingAnchor"));
        } finally {
            serviceClient.close();
        }
    }

    @Test
    void declaresInterpolatedHistoryOnlyWhenQueryServiceIsPresent() {
        assertFalse(client.capabilities().supports(Capability.INTERPOLATED_HISTORY));
        IotdbClient serviceClient = newClientWithQueryService(new FixedExecutor(),
                java.util.Collections.<String, Object>emptyMap());
        try {
            assertTrue(serviceClient.capabilities().supports(Capability.INTERPOLATED_HISTORY));
            assertTrue(serviceClient.capabilities().supports(Capability.SAMPLED_HISTORY));
        } finally {
            serviceClient.close();
        }
    }

    @Test
    void rejectsInterpolatedHistoryWithoutQueryService() {
        // 插值由统一查询服务的内存网格实现，旧构造器没有它时明确拒绝，不降级成 RAW。
        assertThrows(TsdbUnsupportedException.class, () -> client.history().read(HistoryQuery.builder()
                .tags("root.sg.d1.temperature")
                .range(Instant.ofEpochMilli(1700000000000L), Instant.ofEpochMilli(1700000600000L))
                .mode(QueryMode.INTERPOLATED)
                .interval(java.time.Duration.ofMinutes(1))
                .build()));
    }

    @Test
    void interpolatesEveryGridSlotAndIgnoresAggregation() {
        FixedExecutor executor = new FixedExecutor();
        IotdbClient serviceClient = newClientWithQueryService(executor, null);
        try {
            Instant start = Instant.ofEpochMilli(1700000000000L);
            List<TagSeries> series = serviceClient.history().read(HistoryQuery.builder()
                    .tags("root.sg.d1.temperature")
                    .range(start, start.plus(java.time.Duration.ofHours(24)))
                    .mode(QueryMode.INTERPOLATED)
                    .aggregation(Aggregation.AVG)
                    .count(480)
                    .interval(java.time.Duration.ofMinutes(3))
                    .build());
            // 480 点固定网格：逐槽产点，首点落在 start，末点落在 start + 479*step。
            // 报表按索引取点，少一个点就会整体错位，所以无数据也必须占槽。
            assertEquals(480, series.get(0).getSamples().size());
            assertEquals(start, series.get(0).getSamples().get(0).getTimestamp());
            assertEquals(start.plusMillis(479L * 180000L),
                    series.get(0).getSamples().get(479).getTimestamp());
            assertFalse(series.get(0).isTruncated());
            for (String sql : executor.sqls) {
                // 插值不用 GROUP BY：它的桶数由窗口和间隔决定，与请求槽位数不等价
                assertFalse(sql.contains("GROUP BY"));
            }
        } finally {
            serviceClient.close();
        }
    }

    @Test
    void capsInterpolatedGridAtMaxPointsAndFlagsTruncation() {
        FixedExecutor executor = new FixedExecutor();
        IotdbClient serviceClient = newClientWithQueryService(executor, null);
        try {
            List<TagSeries> series = serviceClient.history().read(HistoryQuery.builder()
                    .tags("root.sg.d1.temperature")
                    .range(Instant.ofEpochMilli(1700000000000L),
                            Instant.ofEpochMilli(1700000000000L + 24L * 3600000L))
                    .mode(QueryMode.INTERPOLATED)
                    .count(480)
                    .interval(java.time.Duration.ofMinutes(3))
                    .maxPoints(100)
                    .build());
            assertEquals(100, series.get(0).getSamples().size());
            assertTrue(series.get(0).isTruncated());
        } finally {
            serviceClient.close();
        }
    }

    @Test
    void flagsRawBudgetExhaustionSeparatelyFromGridTruncation() {
        FixedExecutor executor = new FixedExecutor();
        executor.windowRows = rows(
                resultRow(1700000000000L, 10.0),
                resultRow(1700000100000L, 11.0),
                resultRow(1700000200000L, 12.0),
                resultRow(1700000300000L, 13.0));
        IotdbClient serviceClient = newClientWithQueryService(executor,
                Collections.<String, Object>singletonMap("interpolation-raw-limit", "2"));
        try {
            List<TagSeries> series = serviceClient.history().read(HistoryQuery.builder()
                    .tags("root.sg.d1.temperature")
                    .range(Instant.ofEpochMilli(1700000000000L),
                            Instant.ofEpochMilli(1700000000000L + 3L * 3600000L))
                    .mode(QueryMode.INTERPOLATED)
                    .count(4)
                    .interval(java.time.Duration.ofHours(1))
                    .build());
            // 网格完整（4 槽），但窗口原始点只取了预算内的前两页，
            // 中间槽位的插值依据不完整——两个标志不能混用。
            assertEquals(4, series.get(0).getSamples().size());
            assertFalse(series.get(0).isTruncated());
            assertEquals("true", series.get(0).getAttributes().get("interpolationRawTruncated"));
        } finally {
            serviceClient.close();
        }
    }

    @Test
    void batchesCurrentValuesPerDeviceAndPreservesRequestOrder() {
        FixedExecutor executor = new FixedExecutor();
        // 服务端按时间序列自身的时间返回，且不存在的测点会被静默丢弃，
        // 因此返回行序与入参序不一致是这个用例要盯的重点。
        executor.lastRows = rows(
                resultRow(1700000200000L, "root.sg.d1.pressure", 3.5, "FLOAT"),
                resultRow(1700000100000L, "root.sg.d1.temperature", 26.5, "FLOAT"));
        IotdbClient serviceClient = newClientWithQueryService(executor,
                Collections.<String, Object>singletonMap("query-batch-scope", "device"));
        try {
            List<TagSample> values = serviceClient.currentValues().read(Arrays.asList(
                    "root.sg.d1.temperature", "root.sg.d1.missing", "root.sg.d1.pressure"));

            assertEquals(1, executor.sqls.size());
            assertEquals("SELECT LAST `temperature`, `missing`, `pressure` FROM root.`sg`.`d1`",
                    executor.sqls.get(0));
            assertEquals(3, values.size());
            assertEquals("root.sg.d1.temperature", values.get(0).getTagName());
            assertEquals(26.5, values.get(0).getSample().getDoubleValue(), 0.0001);
            assertEquals(Instant.ofEpochMilli(1700000100000L), values.get(0).getSample().getTimestamp());
            assertNull(values.get(1).getSample());
            assertEquals(3.5, values.get(2).getSample().getDoubleValue(), 0.0001);
        } finally {
            serviceClient.close();
        }
    }

    @Test
    void batchesCurrentValuesPerDatabaseAcrossDevices() {
        FixedExecutor executor = new FixedExecutor();
        // 库级档位是本轮的目标形态：一条语句跨过同库的多个设备，实测（M2）应答行序
        // 与请求顺序无关，且不存在的序列被整行丢弃，所以必须按名回填。
        executor.lastRows = rows(
                resultRow(1700000200000L, "root.sg.d2.pressure", 3.5, "FLOAT"),
                resultRow(1700000100000L, "root.sg.d1.temperature", 26.5, "FLOAT"));
        IotdbClient serviceClient = newClientWithQueryService(executor,
                Collections.<String, Object>singletonMap("query-batch-scope", "database"));
        try {
            List<TagSample> values = serviceClient.currentValues().read(Arrays.asList(
                    "root.sg.d1.temperature", "root.sg.d1.missing", "root.sg.d2.pressure"));

            assertEquals(1, executor.sqls.size());
            assertEquals("SELECT LAST `d1`.`temperature`, `d1`.`missing`, `d2`.`pressure` FROM root.`sg`",
                    executor.sqls.get(0));
            assertEquals(3, values.size());
            assertEquals(26.5, values.get(0).getSample().getDoubleValue(), 0.0001);
            assertEquals(Instant.ofEpochMilli(1700000100000L), values.get(0).getSample().getTimestamp());
            assertNull(values.get(1).getSample());
            assertEquals(3.5, values.get(2).getSample().getDoubleValue(), 0.0001);
        } finally {
            serviceClient.close();
        }
    }

    @Test
    void readsSampledHistoryWithArbitraryInterval() {
        FixedExecutor executor = new FixedExecutor();
        executor.groupedRows = rows(
                resultRow(1700000000000L, 25.5),
                resultRow(1700000300000L, 26.0));
        IotdbClient serviceClient = newClientWithQueryService(executor, null);
        try {
            List<TagSeries> series = serviceClient.history().read(HistoryQuery.builder()
                    .tags("root.sg.d1.temperature")
                    .range(Instant.ofEpochMilli(1700000000000L), Instant.ofEpochMilli(1700000600000L))
                    .mode(QueryMode.SAMPLED)
                    .interval(java.time.Duration.ofMinutes(5))
                    .build());

            // 5m 既不是 1s/1m/1h/1d，也不是月粒度；生产 2.0.11 实测接受该字面量，
            // 业务的 9×5min 曲线与 480 点固定网格都落在这条路径上，不能再用间隔白名单卡死。
            assertEquals(1, executor.sqls.size());
            assertTrue(executor.sqls.get(0).contains("GROUP BY ([1700000000000,1700000600000),5m)"));
            assertEquals(2, series.get(0).getSamples().size());
            assertEquals(26.0, series.get(0).getSamples().get(1).getDoubleValue(), 0.0001);
        } finally {
            serviceClient.close();
        }
    }

    @Test
    void marksEveryTagOfAFailedChunkWithTheSameScopedError() {
        FixedExecutor executor = new FixedExecutor();
        executor.windowRows = rows(resultRow(1700000000000L, 25.5, 100.0));
        executor.failOn = "`pressure`";
        IotdbClient serviceClient = newClientWithQueryService(executor, null);
        try {
            List<TagSeries> partial = serviceClient.history().read(HistoryQuery.builder()
                    .tags("root.sg.d1.temperature", "root.sg.d1.pressure")
                    .range(Instant.ofEpochMilli(1700000000000L), Instant.ofEpochMilli(1700000600000L))
                    .mode(QueryMode.RAW)
                    .partialResultAllowed(true)
                    .build());

            // 批量之后一条 SQL 代表一个块，服务端拒一条就影响块内全部位号：温度并不是
            // “有数据”，而是和压力同一条语句一起失败。把这里写成“只有压力失败”就是
            // 装作一致——现场会以为温度的空序列意味着那段时间没有采样。
            assertEquals(1, executor.sqls.size());
            assertEquals(2, partial.size());
            for (TagSeries series : partial) {
                assertTrue(series.getSamples().isEmpty());
                String error = String.valueOf(series.getAttributes().get("vendorError"));
                // 失败原因必须带上那条语句本身，调用方才数得清影响范围
                assertTrue(error.contains("iotdb rejected"));
                // 档位无关：只要那条语句本身在，调用方才数得清影响范围
                assertTrue(error.contains("SELECT"), error);
                assertTrue(error.contains("`temperature`") && error.contains("`pressure`"), error);
                assertEquals("chunk", series.getAttributes().get("vendorErrorScope"));
            }

            assertThrows(TsdbQueryException.class, () -> serviceClient.history().read(
                    HistoryQuery.builder()
                            .tags("root.sg.d1.temperature", "root.sg.d1.pressure")
                            .range(Instant.ofEpochMilli(1700000000000L),
                                    Instant.ofEpochMilli(1700000600000L))
                            .mode(QueryMode.RAW)
                            .partialResultAllowed(false)
                            .build()));
        } finally {
            serviceClient.close();
        }
    }

    @Test
    void keepsPerTagFailureAttributionWhenBatchSizeIsOne() {
        FixedExecutor executor = new FixedExecutor();
        executor.windowRows = rows(resultRow(1700000000000L, 25.5, 100.0));
        executor.failOn = "`pressure`";
        IotdbClient serviceClient = newClientWithQueryService(executor,
                Collections.<String, Object>singletonMap("query-batch-size", "1"));
        try {
            List<TagSeries> partial = serviceClient.history().read(HistoryQuery.builder()
                    .tags("root.sg.d1.temperature", "root.sg.d1.pressure")
                    .range(Instant.ofEpochMilli(1700000000000L), Instant.ofEpochMilli(1700000600000L))
                    .mode(QueryMode.RAW)
                    .partialResultAllowed(true)
                    .build());

            // query-batch-size: 1 是改造前行为的回退档：一位号一条语句，失败半径天然
            // 只有那一个位号，所以这条路径不能写 vendorErrorScope——标了就是把影响范围
            // 说大，现场会照这个属性去核对别的位号。注意同档位的 query-batch-scope: device
            // 并没有这个效果：同设备的两个位号仍然共用一条语句。
            assertEquals(2, executor.sqls.size());
            assertEquals(1, partial.get(0).getSamples().size());
            assertEquals(25.5, partial.get(0).getSamples().get(0).getDoubleValue(), 0.0001);
            assertFalse(partial.get(0).getAttributes().containsKey("vendorError"));
            assertTrue(partial.get(1).getSamples().isEmpty());
            assertTrue(String.valueOf(partial.get(1).getAttributes().get("vendorError"))
                    .contains("iotdb rejected"));
            assertFalse(partial.get(1).getAttributes().containsKey("vendorErrorScope"));
        } finally {
            serviceClient.close();
        }
    }

    @Test
    void marksTheTagsThatNeededAPerTagCompletionPass() {
        FixedExecutor executor = new FixedExecutor();
        executor.windowRows = rows(
                resultRow(1700000000000L, 25.5, 100.0),
                resultRow(1700000100000L, 26.0, 101.0));
        Map<String, Object> options = new LinkedHashMap<String, Object>();
        options.put("query-batch-row-budget", "2");
        IotdbClient serviceClient = newClientWithQueryService(executor, options);
        try {
            List<TagSeries> series = serviceClient.history().read(HistoryQuery.builder()
                    .tags("root.sg.d1.temperature", "root.sg.d1.pressure")
                    .range(Instant.ofEpochMilli(1700000000000L), Instant.ofEpochMilli(1700000600000L))
                    .mode(QueryMode.RAW)
                    .maxPoints(2)
                    .build());

            // 实测 M9：多列 RAW 的 LIMIT 是“合并时间戳行数”的预算，密集列会把稀疏列
            // 挤掉点。撞预算又没取满的位号必须逐位号补采，补采来的位号要留痕，
            // 否则现场看到 3 条语句会以为是分块配置错了，而不是补采发生了。
            assertEquals(3, executor.sqls.size());
            assertEquals("SELECT `d1`.`temperature`, `d1`.`pressure` FROM root.`sg` WHERE time >= "
                    + "1700000000000 AND time < 1700000600000 ORDER BY TIME ASC LIMIT 2", executor.sqls.get(0));
            assertEquals("SELECT `temperature` FROM root.`sg`.`d1` WHERE time >= 1700000000000"
                    + " AND time < 1700000600000 ORDER BY TIME ASC LIMIT 3", executor.sqls.get(1));
            for (TagSeries tagSeries : series) {
                assertEquals(2, tagSeries.getSamples().size());
                assertEquals("true", tagSeries.getAttributes().get("rawBatchCompleted"));
            }
        } finally {
            serviceClient.close();
        }
    }

    @Test
    void routesAggregatedHistoryThroughQueryService() {
        FixedExecutor executor = new FixedExecutor();
        executor.groupedRows = rows(
                resultRow(1700000000000L, 25.5),
                resultRow(1700000300000L, 26.0));
        IotdbClient serviceClient = newClientWithQueryService(executor, null);
        try {
            List<TagSeries> series = serviceClient.history().read(HistoryQuery.builder()
                    .tags("root.sg.d1.temperature")
                    .range(Instant.ofEpochMilli(1700000000000L), Instant.ofEpochMilli(1700000600000L))
                    .mode(QueryMode.AGGREGATED)
                    .aggregation(Aggregation.AVG)
                    .interval(java.time.Duration.ofMinutes(5))
                    .direction(Direction.BACKWARD)
                    .maxPoints(10)
                    .build());

            // 聚合模式一旦绕回 REST 直连，transport=native/jdbc 的实例就在这里
            // 悄悄换了一种通道执行，“三通道共用同一份 SQL”的不变式就有洞。
            assertEquals(1, executor.sqls.size());
            String sql = executor.sqls.get(0);
            assertTrue(sql.contains("AVG(`d1`.`temperature`)"), sql);
            assertTrue(sql.contains("GROUP BY ([1700000000000,1700000600000),5m)"));
            assertTrue(sql.contains("ORDER BY TIME DESC"));
            // maxPoints + 1 用于判断是否截断
            assertTrue(sql.contains("LIMIT 11"));
            assertEquals(2, series.get(0).getSamples().size());
        } finally {
            serviceClient.close();
        }
    }

    @Test
    void failsFastWhenQueryServiceIsMissing() {
        TsdbInstanceConfig instance = new TsdbInstanceConfig();
        instance.setType("iotdb");
        instance.setBaseUrl("http://127.0.0.1:" + port);
        instance.getAuth().setUsername("root");
        instance.getAuth().setPassword("root");
        SecurityConfig security = new SecurityConfig();
        security.setAllowInsecureTransport(true);

        IotdbConfig config = new IotdbConfig("iotdb_main", instance, security);
        // 缺依赖默认属于装配错误，必须构造期失败，而不是悄悄给出少三项能力的客户端
        assertTrue(config.isRequireQueryService());
        assertThrows(TsdbConfigurationException.class, () -> new IotdbClient(config,
                new IotdbRestApi(config, new IotdbHttpClient(config))));

        instance.getOptions().put("require-query-service", "false");
        IotdbConfig opted = new IotdbConfig("iotdb_main", instance, security);
        assertFalse(opted.isRequireQueryService());
        IotdbClient legacy = new IotdbClient(opted,
                new IotdbRestApi(opted, new IotdbHttpClient(opted)));
        try {
            assertFalse(legacy.capabilities().supports(Capability.INTERPOLATED_HISTORY));
        } finally {
            legacy.close();
        }

        // 本项决定的是要不要快速失败，把 yes/1 之类误写当成 false 等于让笔误关掉它，
        // 因此非布尔取值在配置构造期就拒绝。
        instance.getOptions().put("require-query-service", "yes");
        assertThrows(TsdbConfigurationException.class, () -> new IotdbConfig("iotdb_main", instance, security));
    }

    @Test
    void rejectsCountOnlyAggregatedQuery() {
        // IoTDB 聚合需要自然粒度间隔，仅设置服务端 count 而无 interval 时拒绝。
        assertThrows(TsdbUnsupportedException.class, () -> client.history().read(HistoryQuery.builder()
                .tags("root.sg.d1.temperature")
                .range(Instant.ofEpochMilli(1700000000000L), Instant.ofEpochMilli(1700000600000L))
                .mode(QueryMode.AGGREGATED)
                .aggregation(Aggregation.AVG)
                .count(10)
                .build()));
    }

    @Test
    void writeIsDisabledByDefault() {
        assertFalse(client.writeSession().isWritable());
        assertThrows(TsdbUnsupportedException.class, () -> client.writeSession().write(
                Arrays.asList(new WriteRequest("root.sg.d1.temperature",
                        Arrays.asList(Sample.ofDouble(Instant.ofEpochMilli(1700000000000L), 1.0, Quality.GOOD, null))))));
    }

    @Test
    void writesInsertTabletWhenAllowed() {
        IotdbClient writable = newClient(true);
        try {
            assertTrue(writable.capabilities().supports(Capability.WRITE));
            WriteResult result = writable.writeSession().write(Arrays.asList(
                    new WriteRequest("root.sg.d1.temperature", Arrays.asList(
                            Sample.ofDouble(Instant.ofEpochMilli(1700000000000L), 25.5, Quality.GOOD, null),
                            Sample.ofDouble(Instant.ofEpochMilli(1700000001000L), 25.8, Quality.GOOD, null)))));
            assertEquals(1, result.getAccepted());
            String body = lastWrite.get();
            assertNotNull(body);
            assertTrue(body.contains("\"device\":\"root.sg.d1\""));
            assertTrue(body.contains("\"measurements\":[\"temperature\"]"));
            assertTrue(body.contains("\"data_types\":[\"DOUBLE\"]"));
            assertTrue(body.contains("25.5"));
        } finally {
            writable.close();
        }
    }

    @Test
    void rejectsPlaintextTransportByDefault() {
        TsdbInstanceConfig instance = new TsdbInstanceConfig();
        instance.setBaseUrl("http://127.0.0.1:" + port);
        AuthConfig auth = new AuthConfig();
        auth.setUsername("root");
        auth.setPassword("root");
        instance.setAuth(auth);
        assertThrows(TsdbConfigurationException.class,
                () -> new IotdbConfig("x", instance, new SecurityConfig()));
    }

    @Test
    void exposesRawSqlAndInsertTablet() {
        IotdbRestApi api = client.unwrap(IotdbRestApi.class);

        JSONObject query = api.query("SHOW TIMESERIES root.sg.**");
        assertEquals(200, query.getIntValue("code"));
        assertTrue(lastSql.get().contains("SHOW TIMESERIES"));

        JSONObject nonQuery = api.nonQuery("CREATE TIMESERIES root.sg.d1.status WITH DATATYPE=BOOLEAN");
        assertEquals(200, nonQuery.getIntValue("code"));
        assertTrue(lastNonQuerySql.get().contains("CREATE TIMESERIES"));

        JSONObject request = JSON.parseObject(
                "{\"device\":\"root.sg.d1\",\"timestamps\":[1700000000000],"
                        + "\"measurements\":[\"temperature\"],\"data_types\":[\"DOUBLE\"],"
                        + "\"values\":[[25.5]]}");
        api.insertTablet(request);
        assertTrue(lastWrite.get().contains("\"device\":\"root.sg.d1\""));

        assertEquals("ok", api.ping());
    }

    @Test
    void unwrapsNativeApiAndConfig() {
        assertNotNull(client.unwrap(IotdbRestApi.class));
        assertNotNull(client.unwrap(IotdbConfig.class));
    }

    @Test
    void supportsConfiguredTimeZoneForParsingAndDisplay() {
        java.util.Map<String, Object> response = new java.util.LinkedHashMap<String, Object>();
        response.put("source-zone", "GMT+8");
        response.put("target-zone", "GMT+8");
        response.put("pattern", "yyyy-MM-dd HH:mm:ss");
        java.util.Map<String, Object> time = new java.util.LinkedHashMap<String, Object>();
        time.put("response", response);
        java.util.Map<String, Object> options = new java.util.LinkedHashMap<String, Object>();
        options.put("time", time);
        try (IotdbClient timeClient = newClientWithQueryService(new FixedExecutor(), options)) {
            assertEquals("GMT+08:00", timeClient.timeConfig().getResponseTargetZone().getId());
            assertEquals("2023-11-15 06:13:21",
                    timeClient.formatTimestamp(Instant.parse("2023-11-14T22:13:21Z")));
            assertEquals(Instant.parse("2023-11-14T22:13:21Z"),
                    timeClient.parseTimestamp("2023-11-15 06:13:21"));
        }
    }

    @Test
    void computesShiftWindowFromDefaultCalendarZoneAndShiftHour() {
        // 缺省即铜陵现场的口径：业务时区 Asia/Shanghai、班次 08:00 起。
        // 换算必须落在 SDK 内，业务侧才可能删掉自己打的 ±8h 补丁。
        try (IotdbClient serviceClient = newClientWithQueryService(new FixedExecutor(), null)) {
            // 本地 11-11 07:59:59 —— 还属于 11-10 08:00 起的那一班
            IotdbConfig.ShiftWindow previous =
                    serviceClient.shiftWindow(Instant.parse("2026-11-10T23:59:59Z"));
            assertEquals(Instant.parse("2026-11-10T00:00:00Z"), previous.getStart());
            assertEquals(Instant.parse("2026-11-11T00:00:00Z"), previous.getEnd());

            // 本地 08:00 整点即新班次的第一秒，不能仍算作上一班
            IotdbConfig.ShiftWindow current =
                    serviceClient.shiftWindow(Instant.parse("2026-11-11T00:00:00Z"));
            assertEquals(Instant.parse("2026-11-11T00:00:00Z"), current.getStart());
            assertEquals(Instant.parse("2026-11-12T00:00:00Z"), current.getEnd());
        }
    }

    @Test
    void honorsConfiguredZoneAndShiftHourForShiftWindow() {
        java.util.Map<String, Object> options = new java.util.LinkedHashMap<String, Object>();
        options.put("zone", "UTC");
        options.put("shift-hour", "8");
        try (IotdbClient serviceClient = newClientWithQueryService(new FixedExecutor(), options)) {
            IotdbConfig.ShiftWindow window =
                    serviceClient.shiftWindow(Instant.parse("2026-11-12T07:59:59Z"));
            assertEquals(Instant.parse("2026-11-11T08:00:00Z"), window.getStart());
            assertEquals(Instant.parse("2026-11-12T08:00:00Z"), window.getEnd());
        }

        assertThrows(TsdbConfigurationException.class,
                () -> newClientWithQueryService(new FixedExecutor(),
                        Collections.<String, Object>singletonMap("zone", "Mars/Olympus")));
        assertThrows(TsdbConfigurationException.class,
                () -> newClientWithQueryService(new FixedExecutor(),
                        Collections.<String, Object>singletonMap("shift-hour", "24")));
    }

    private static IotdbResultTable.Row resultRow(long timestamp, Object... values) {
        return new IotdbResultTable.Row(timestamp, values);
    }

    private static List<IotdbResultTable.Row> rows(IotdbResultTable.Row... rows) {
        return new ArrayList<IotdbResultTable.Row>(Arrays.asList(rows));
    }

    /**
     * 脚本化 SQL 执行器：按 SQL 应答固定结果，不触网。
     *
     * <p><b>两级派发</b>，优先级从高到低：</p>
     * <ol>
     *   <li>{@link #respond(String, IotdbResultTable)} 登记的“精确 SQL → 结果表”。
     *       批量改造后靠子串派发随时会假绿：{@code SELECT LAST `d`.`a`} 与
     *       {@code SELECT LAST_VALUE(…)} 只差两个字符，库级和设备级文本开头相同，
     *       同一族的批量语句和补采语句只差 {@code FROM} 深度。要看清具体语句的
     *       用例一律走这一级。</li>
     *   <li>按形态应答，并把<b>本次发射的表达式</b>回显成列名（实测 G1：三条通道
     *       的列名都是 {@code fn(root.db.device.measurement)}，裸列则是绝对路径）。
     *       列宽跟着请求走，夹具就不会给出“请求 4 列、答 2 列”这种真服务端不会
     *       出现的形态——那正是把列对齐破洞照成绿色的方式。</li>
     * </ol>
     *
     * <p>形态判据仍要注意：{@code "SELECT LAST "} 必须带尾空格，否则采样的
     * {@code SELECT LAST_VALUE(…)} 会被当成批量最新值；{@code "time <"} 同时出现在
     * 窗口 SQL 里，所以先判 {@code GROUP BY} 与 {@code time >=} 再判方向。</p>
     */
    private static final class FixedExecutor implements IotdbQueryExecutor {
        private final List<String> sqls = new ArrayList<String>();
        private final Map<String, IotdbResultTable> script = new LinkedHashMap<String, IotdbResultTable>();
        private List<IotdbResultTable.Row> firstLastRows = Collections.emptyList();
        private List<IotdbResultTable.Row> lastRows = Collections.emptyList();
        private List<IotdbResultTable.Row> groupedRows = Collections.emptyList();
        private List<IotdbResultTable.Row> windowRows = Collections.emptyList();
        private List<IotdbResultTable.Row> beforeRows = Collections.emptyList();
        private List<IotdbResultTable.Row> afterRows = Collections.emptyList();
        /** 命中该子串的 SQL 抛异常：按生成的 SQL 匹配，才能走真实的失败分支。 */
        private String failOn;

        /**
         * 登记一条精确 SQL 的应答表。
         *
         * @param sql   期望被原样匹配的语句
         * @param table 该语句的应答
         * @return {@code this}，便于链式装配
         */
        private FixedExecutor respond(String sql, IotdbResultTable table) {
            script.put(sql, table);
            return this;
        }

        @Override
        public IotdbResultTable query(String sql) {
            sqls.add(sql);
            if (failOn != null && sql.contains(failOn)) {
                throw new TsdbQueryException("iotdb rejected " + sql.substring(0, Math.min(24, sql.length())));
            }
            IotdbResultTable scripted = script.get(sql);
            if (scripted != null) {
                return scripted;
            }
            if (sql.startsWith("SELECT LAST ")) {
                return new IotdbResultTable(
                        Arrays.asList("Timeseries", "Value", "DataType"), lastRows);
            }
            if (sql.startsWith("SHOW")) {
                return new IotdbResultTable(Collections.singletonList("value"),
                        Collections.<IotdbResultTable.Row>emptyList());
            }
            return fitted(sql, shapeRows(sql));
        }

        /** 按形态取出该应答的行。 */
        private List<IotdbResultTable.Row> shapeRows(String sql) {
            if (sql.contains("first_value(")) {
                return firstLastRows;
            }
            if (sql.contains("GROUP BY")) {
                return groupedRows;
            }
            if (sql.contains("time >=")) {
                return paged(windowRows, sql);
            }
            return sql.contains("ORDER BY TIME DESC") ? beforeRows : afterRows;
        }

        /**
         * 把应答行对齐到本条语句发射的列宽：多的截掉、缺的补 {@code null}。
         * 真实服务端每行的单元格数恒等于投影列数，夹具也必须如此。
         */
        private static IotdbResultTable fitted(String sql, List<IotdbResultTable.Row> rows) {
            List<String> labels = echoedColumns(sql);
            List<IotdbResultTable.Row> fitted = new ArrayList<IotdbResultTable.Row>(rows.size());
            for (IotdbResultTable.Row row : rows) {
                Object[] values = new Object[labels.size()];
                Object[] source = row.getValues();
                System.arraycopy(source, 0, values, 0, Math.min(values.length, source.length));
                fitted.add(new IotdbResultTable.Row(row.getTimestamp(), values));
            }
            return new IotdbResultTable(labels, fitted);
        }

        /**
         * 从 {@code SELECT} 子句回显列名，形如实测的 {@code last_value(root.sg.d1.a)}；
         * 裸投影列则是去掉反引号的绝对路径。
         */
        private static List<String> echoedColumns(String sql) {
            int from = sql.indexOf(" FROM ");
            String base = sql.substring(from + " FROM ".length()).split(" ")[0].replace("`", "");
            String clause = sql.substring("SELECT ".length(), from);
            List<String> labels = new ArrayList<String>();
            for (String item : splitTopLevel(clause)) {
                String text = item.trim();
                String function = null;
                int open = text.indexOf('(');
                if (open > 0 && text.endsWith(")")) {
                    function = text.substring(0, open).trim();
                    text = text.substring(open + 1, text.length() - 1).trim();
                }
                String absolute = text.replace("`", "");
                if (!absolute.startsWith("root.")) {
                    absolute = base + "." + absolute;
                }
                labels.add(function == null ? absolute : function + "(" + absolute + ")");
            }
            return labels;
        }

        /** 按顶层逗号切分 SELECT 子句，括号与反引号内部的逗号不算分隔。 */
        private static List<String> splitTopLevel(String clause) {
            List<String> items = new ArrayList<String>();
            int depth = 0;
            boolean quoted = false;
            StringBuilder current = new StringBuilder();
            for (int i = 0; i < clause.length(); i++) {
                char c = clause.charAt(i);
                if (c == '`') {
                    quoted = !quoted;
                } else if (!quoted && c == '(') {
                    depth++;
                } else if (!quoted && c == ')') {
                    depth--;
                } else if (!quoted && depth == 0 && c == ',') {
                    items.add(current.toString());
                    current.setLength(0);
                    continue;
                }
                current.append(c);
            }
            items.add(current.toString());
            return items;
        }

        private static List<IotdbResultTable.Row> paged(List<IotdbResultTable.Row> all, String sql) {
            int offset = tailInt(sql, "OFFSET ", 0);
            int limit = tailInt(sql, "LIMIT ", all.size());
            int from = Math.min(Math.max(0, offset), all.size());
            int to = limit <= 0 ? all.size() : Math.min(from + limit, all.size());
            return all.subList(from, to);
        }

        private static int tailInt(String sql, String token, int fallback) {
            int index = sql.lastIndexOf(token);
            if (index < 0) {
                return fallback;
            }
            int start = index + token.length();
            int end = start;
            while (end < sql.length() && Character.isDigit(sql.charAt(end))) {
                end++;
            }
            return end == start ? fallback : Integer.parseInt(sql.substring(start, end));
        }

        @Override
        public String transport() {
            return "scripted";
        }

        @Override
        public void close() {
        }
    }

    private static String extractSql(String body) {
        int index = body.indexOf("\"sql\":\"");
        if (index < 0) {
            return "";
        }
        int start = index + 7;
        StringBuilder out = new StringBuilder();
        for (int i = start; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\') {
                i++;
                if (i < body.length()) {
                    out.append(body.charAt(i));
                }
            } else if (c == '"') {
                break;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
