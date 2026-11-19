package io.github.forget_the_bright.tsdb.ge;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.forget_the_bright.tsdb.common.config.SecurityConfig;
import io.github.forget_the_bright.tsdb.common.config.TsdbInstanceConfig;
import io.github.forget_the_bright.tsdb.common.exception.TsdbConfigurationException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbDataException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException;
import io.github.forget_the_bright.tsdb.common.internal.json.JsonText;
import io.github.forget_the_bright.tsdb.common.model.Aggregation;
import io.github.forget_the_bright.tsdb.common.model.Capability;
import io.github.forget_the_bright.tsdb.common.model.Direction;
import io.github.forget_the_bright.tsdb.common.model.FillPolicy;
import io.github.forget_the_bright.tsdb.common.model.HistoryQuery;
import io.github.forget_the_bright.tsdb.common.model.Quality;
import io.github.forget_the_bright.tsdb.common.model.QueryMode;
import io.github.forget_the_bright.tsdb.common.model.TagInfo;
import io.github.forget_the_bright.tsdb.common.model.TagQuery;
import io.github.forget_the_bright.tsdb.common.model.TagSample;
import io.github.forget_the_bright.tsdb.common.model.TagSeries;
import io.github.forget_the_bright.tsdb.common.model.ValueType;
import io.github.forget_the_bright.tsdb.ge.internal.GeHttpClient;
import io.github.forget_the_bright.tsdb.ge.internal.GeNativeApi;
import io.github.forget_the_bright.tsdb.ge.internal.GeTokenManager;
import io.github.forget_the_bright.tsdb.ge.nativeapi.GeEndpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GE 驱动的本地 Mock 单元测试。
 *
 * <p>用 JDK {@link HttpServer} 模拟 Historian REST 端点，重点断言
 * <strong>线缆格式</strong>与旧 {@code GE_REST_SDK} 一致：数据查询走 POST、
 * 标量参数在查询串、位号列表在请求体且为明文分号拼接、枚举参数为数值字符串。
 * 生产环境的真实数据一致性由 {@code GeComparisonIT}（{@code -Dge.it=true}）裁决。</p>
 */
class GeClientTest {

    private HttpServer server;
    private GeClient client;

    private final AtomicReference<String> lastAuthorization = new AtomicReference<String>();
    private final AtomicReference<String> lastRawQuery = new AtomicReference<String>();
    private final AtomicReference<String> lastRawBody = new AtomicReference<String>();
    private final AtomicReference<String> lastRawMethod = new AtomicReference<String>();
    private final AtomicReference<String> lastAggregatedQuery = new AtomicReference<String>();
    private final AtomicReference<String> lastAggregatedBody = new AtomicReference<String>();
    private final AtomicReference<String> lastSampledBody = new AtomicReference<String>();
    private final AtomicReference<String> lastSampledMethod = new AtomicReference<String>();
    private final AtomicReference<String> lastInterpolatedQuery = new AtomicReference<String>();
    private final AtomicReference<String> lastInterpolatedBody = new AtomicReference<String>();
    private final AtomicReference<String> lastInterpolatedMethod = new AtomicReference<String>();
    private final AtomicReference<String> lastCreateBody = new AtomicReference<String>();
    private final List<String> aggregatedQueries = new ArrayList<String>();
    /**
     * calculated 端点的应答生成器；用例可替换它来固定「稠密 / 缺基线桶」两类响应。
     */
    private volatile java.util.function.Function<String, String> calculatedResponse;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/uaa/oauth/token", exchange -> {
            respond(exchange, 200, "{\"access_token\":\"ge-token\",\"expires_in\":43199}");
        });
        server.createContext("/historian-rest-api/v1/tags", exchange -> {
            lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String query = exchange.getRequestURI().getRawQuery();
            if (query != null && query.contains("nameMask=PARTIAL")) {
                respond(exchange, 200, "{\"ErrorCode\":11,"
                        + "\"ErrorMessage\":\" Refine Search Results.\","
                        + "\"Tags\":[\"AI-101\"]}");
            } else {
                respond(exchange, 200, "{\"ErrorCode\":0,\"ErrorMessage\":\"\",\"Tags\":[\"AI-101\",\"AI-102\"]}");
            }
        });
        server.createContext("/historian-rest-api/v1/datapoints/currentvalue", exchange -> {
            lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"ErrorCode\":0,\"Data\":["
                    + "{\"TagName\":\"AI-101\",\"ErrorCode\":0,\"DataType\":\"Float\",\"Samples\":["
                    + "{\"TimeStamp\":\"2023-11-14 22:13:21\",\"Value\":\"12.5\",\"Quality\":3}]},"
                    + "{\"TagName\":\"AI-102\",\"ErrorCode\":0,\"DataType\":\"String\",\"Samples\":["
                    + "{\"TimeStamp\":\"2023-11-14 22:13:22\",\"Value\":\"bad\",\"Quality\":0}]}]}");
        });
        // POST sampled：当前值（samplingMode=1）与 SAMPLED 历史（samplingMode=2）共用。
        // AI-ERR 模拟 per-tag 错误码；AI-101 带工程单位与量程。
        server.createContext("/historian-rest-api/v1/datapoints/sampled", exchange -> {
            lastSampledMethod.set(exchange.getRequestMethod());
            lastSampledBody.set(readBody(exchange));
            respond(exchange, 200, "{\"ErrorCode\":0,\"Data\":["
                    + "{\"TagName\":\"AI-101\",\"ErrorCode\":0,\"DataType\":\"Float\","
                    + "\"EngineeringUnits\":\"MPa\",\"HiEngineeringUnits\":100,\"LoEngineeringUnits\":0,"
                    + "\"Samples\":[{\"TimeStamp\":\"2023-11-14 22:13:21\",\"Value\":\"12.5\",\"Quality\":3}]},"
                    + "{\"TagName\":\"AI-102\",\"ErrorCode\":0,\"DataType\":\"String\",\"Samples\":["
                    + "{\"TimeStamp\":\"2023-11-14 22:13:22\",\"Value\":\"bad\",\"Quality\":0}]},"
                    + "{\"TagName\":\"AI-ERR\",\"ErrorCode\":7,\"ErrorMessage\":\"Tag not found\","
                    + "\"DataType\":\"Float\",\"Samples\":[]}]}");
        });
        server.createContext("/historian-rest-api/v1/datapoints/raw", exchange -> {
            lastRawMethod.set(exchange.getRequestMethod());
            lastRawQuery.set(exchange.getRequestURI().getRawQuery());
            String body = readBody(exchange);
            lastRawBody.set(body);
            if (body.contains("AI-BADTIME")) {
                // 时间文本不可解析：驱动必须显式失败，不得静默丢点。
                respond(exchange, 200, "{\"ErrorCode\":0,\"Data\":["
                        + "{\"TagName\":\"AI-BADTIME\",\"ErrorCode\":0,\"DataType\":\"Float\",\"Samples\":["
                        + "{\"TimeStamp\":\"not-a-time\",\"Value\":\"1.0\",\"Quality\":3}]}]}");
                return;
            }
            respond(exchange, 200, "{\"ErrorCode\":0,\"Data\":["
                    + "{\"TagName\":\"AI-101\",\"ErrorCode\":0,\"DataType\":\"Float\",\"Samples\":["
                    + "{\"TimeStamp\":\"2023-11-14 22:13:21\",\"Value\":\"1.5\",\"Quality\":3},"
                    + "{\"TimeStamp\":\"2023-11-14 22:13:22\",\"Value\":\"2.5\",\"Quality\":3}]}]}");
        });
        // POST calculated：按 calculationMode 分支返回首值（17）/末值（19），
        // 供 INTERVAL_SUM 双请求算法断言；AI-BAD 首样本质量为 BAD（厂商码 0）。
        server.createContext("/historian-rest-api/v1/datapoints/calculated", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            lastAggregatedQuery.set(query);
            lastAggregatedBody.set(readBody(exchange));
            aggregatedQueries.add(query);
            respond(exchange, 200, calculatedResponse.apply(query));
        });
        server.createContext("/historian-rest-api/v1/datapoints/interpolated", exchange -> {
            lastInterpolatedMethod.set(exchange.getRequestMethod());
            lastInterpolatedQuery.set(exchange.getRequestURI().getRawQuery());
            lastInterpolatedBody.set(readBody(exchange));
            respond(exchange, 200, "{\"ErrorCode\":0,\"Data\":["
                    + "{\"TagName\":\"AI-101\",\"ErrorCode\":0,\"DataType\":\"Float\",\"Samples\":["
                    + "{\"TimeStamp\":\"2023-11-14 22:15:00\",\"Value\":\"7.25\",\"Quality\":3}]}]}");
        });
        server.createContext("/historian-rest-api/v1/datapoints/create", exchange -> {
            lastCreateBody.set(readBody(exchange));
            respond(exchange, 200, "{\"ErrorCode\":0,\"ErrorMessage\":\"\"}");
        });
        server.start();
        calculatedResponse = GeClientTest::defaultCalculatedBody;

        TsdbInstanceConfig config = new TsdbInstanceConfig();
        config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        config.getAuth().setUsername("admin");
        config.getAuth().setPassword("secret");
        config.getAuth().setClientId("historian_public_rest_api");
        config.getAuth().setClientSecret("test-secret");
        config.getOptions().put("time.response.source-zone", "UTC");
        config.getOptions().put("time.response.target-zone", "GMT+8");
        config.getOptions().put("time.response.pattern", "yyyy-MM-dd HH:mm:ss");
        SecurityConfig security = new SecurityConfig();
        security.setAllowInsecureTransport(true);

        GeConfig geConfig = new GeConfig("ge-test", config, security);
        GeHttpClient http = new GeHttpClient(geConfig);
        GeTokenManager tokens = new GeTokenManager(geConfig, http);
        client = new GeClient(geConfig, new GeNativeApi(geConfig, http, tokens));
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void exposesDeclaredCapabilitiesAndIsReadOnly() {
        assertTrue(client.capabilities().supports(Capability.TAG_BROWSE));
        assertTrue(client.capabilities().supports(Capability.CURRENT_VALUE));
        assertTrue(client.capabilities().supports(Capability.RAW_HISTORY));
        assertTrue(client.capabilities().supports(Capability.AGGREGATED_HISTORY));
        assertTrue(client.capabilities().supports(Capability.INTERPOLATED_HISTORY));
        assertFalse(client.capabilities().supports(Capability.WRITE));
        assertFalse(client.writeSession().isWritable());
        assertThrows(TsdbUnsupportedException.class,
                () -> client.writeSession().write(java.util.Collections.emptyList()));
    }

    @Test
    void driverAndClientDeclareSameCapabilities() {
        // GeDriver.CAPABILITIES 与 GeClient.CAPABILITIES 必须同步维护，
        // 否则 SPI 能力询问与实际客户端行为会不一致。
        assertEquals(new GeDriver().capabilities(), client.capabilities().asSet());
        assertEquals(7, client.capabilities().asSet().size());
    }

    @Test
    void listsTagsWithBearerToken() {
        assertEquals(2, client.tagCatalog().listTags(TagQuery.builder().limit(0).build()).size());
        assertEquals("ge-token", lastAuthorization.get().substring("Bearer ".length()));
        assertEquals(ValueType.UNKNOWN, client.tagCatalog().getTag("AI-101").getValueType());
    }

    @Test
    void treatsTruncatedTagSearchAsSuccess() {
        // 现场 GET /v1/tags 命中结果时仍返回 ErrorCode=11（Refine Search Results.），
        // 但 Tags 中有数据；不能把这种截断提示当成失败。
        List<TagInfo> tags = client.tagCatalog()
                .listTags(TagQuery.builder().nameMask("PARTIAL*").limit(1).build());
        assertEquals(1, tags.size());
        assertEquals("AI-101", tags.get(0).getTagName());
    }

    @Test
    void formatsTimestampsInConfiguredTargetZone() {
        // 响应按 UTC 解析（source-zone=UTC），展示按 GMT+8 格式化。
        assertEquals("2023-11-15 06:13:21",
                client.formatTimestamp(Instant.parse("2023-11-14T22:13:21Z")));
        assertEquals("GMT+08:00", client.timeConfig().getResponseTargetZone().getId());
        assertEquals(Instant.parse("2023-11-14T22:13:21Z"),
                client.parseTimestamp("2023-11-14 22:13:21"));
    }

    @Test
    void readsCurrentValuesInRequestedOrder() {
        List<TagSample> values = client.currentValues().read(Arrays.asList("AI-101", "AI-102", "AI-999"));
        assertEquals(3, values.size());

        assertEquals(ValueType.DOUBLE, values.get(0).getSample().getValueType());
        assertEquals(12.5d, values.get(0).getSample().getDoubleValue(), 0.0001d);
        assertEquals(Quality.GOOD, values.get(0).getSample().getQuality());
        assertEquals(Instant.parse("2023-11-14T22:13:21Z"), values.get(0).getSample().getTimestamp());
        assertEquals(Date.from(Instant.parse("2023-11-14T22:13:21Z")),
                values.get(0).getSample().getTimestampDate());
        assertEquals("2023-11-15 06:13:21", values.get(0).getSample().getTimestampText());

        assertEquals(ValueType.STRING, values.get(1).getSample().getValueType());
        assertEquals("bad", values.get(1).getSample().getTextValue());
        assertEquals(Quality.BAD, values.get(1).getSample().getQuality());

        assertEquals(null, values.get(2).getSample());

        // 当前值走 POST sampled，线缆格式与旧 SDK 一致：
        // samplingMode 为字符串 "1"、queryModifier 为数字 1、tagNames 明文分号拼接。
        assertEquals("POST", lastSampledMethod.get());
        JSONObject body = json(lastSampledBody.get());
        assertTrue(body.get("samplingMode") instanceof String);
        assertEquals("1", body.getString("samplingMode"));
        assertTrue(body.get("queryModifier") instanceof Number);
        assertEquals(1, body.getIntValue("queryModifier"));
        assertEquals("AI-101;AI-102;AI-999", body.getString("tagNames"));

        // 正常位号携带错误码 "0"、数据类型与工程单位/量程属性。
        assertEquals("0", values.get(0).getAttributes().get(TagSample.ATTR_ERROR_CODE));
        assertEquals("Float", values.get(0).getAttributes().get(TagSample.ATTR_DATA_TYPE));
        assertEquals("MPa", values.get(0).getAttributes().get(TagSample.ATTR_ENGINEERING_UNITS));
        assertEquals("100", values.get(0).getAttributes().get(TagSample.ATTR_HI_ENGINEERING_UNITS));
        assertEquals("0", values.get(0).getAttributes().get(TagSample.ATTR_LO_ENGINEERING_UNITS));
        // 厂商未返回的位号：无值占位，属性为空（区别于“出错”）。
        assertTrue(values.get(2).getAttributes().isEmpty());
    }

    @Test
    void exposesPerTagErrorsInCurrentValues() {
        // per-tag ErrorCode != 0：sample 为 null，但属性带错误码与描述，
        // 调用方据此区分“确实无值”与“该位号查询出错”。
        List<TagSample> values = client.currentValues().read(Arrays.asList("AI-ERR", "AI-101"));
        assertEquals(2, values.size());
        assertNull(values.get(0).getSample());
        assertEquals("7", values.get(0).getAttributes().get(TagSample.ATTR_ERROR_CODE));
        assertEquals("Tag not found", values.get(0).getAttributes().get(TagSample.ATTR_ERROR_MESSAGE));
        assertNotNull(values.get(1).getSample());
        assertEquals("0", values.get(1).getAttributes().get(TagSample.ATTR_ERROR_CODE));
    }

    @Test
    void readsRawHistoryAndForwardsDirection() {
        HistoryQuery query = HistoryQuery.builder()
                .tags("AI-101")
                .range(Instant.parse("2023-11-14T00:00:00Z"), Instant.parse("2023-11-15T00:00:00Z"))
                .mode(QueryMode.RAW)
                .direction(Direction.BACKWARD)
                .maxPoints(10)
                .build();
        List<TagSeries> result = client.history().read(query);
        assertEquals(1, result.size());
        assertEquals(2, result.get(0).getSamples().size());
        // RAW 历史走 POST：标量在查询串，位号在请求体（明文）。
        assertEquals("POST", lastRawMethod.get());
        assertTrue(lastRawQuery.get().contains("direction=0"));
        assertTrue(lastRawQuery.get().contains("count=10"));
        assertTrue(lastRawQuery.get().contains("start=2023-11-14T00%3A00%3A00Z"));
        assertFalse(lastRawQuery.get().contains("tagNames"));
        assertEquals("{\"tagNames\":\"AI-101\"}", lastRawBody.get());
    }

    @Test
    void postsTagNamesAsPlainTextWithoutUrlEncoding() {
        // 请求体里的位号不做 URL 编码（旧 SDK TagNamesEntity 为明文），
        // 空格、加号等特殊字符原样保留；查询串中不出现 tagNames。
        GeNativeApi api = client.unwrap(GeNativeApi.class);
        api.readRaw(Arrays.asList("AI-101", "AI+102", "AI 103"),
                Instant.parse("2023-11-14T00:00:00Z"), Instant.parse("2023-11-15T00:00:00Z"),
                Direction.FORWARD, 5);
        assertEquals("{\"tagNames\":\"AI-101;AI+102;AI 103\"}", lastRawBody.get());
        assertFalse(lastRawQuery.get().contains("tagNames"));
    }

    @Test
    void mapsAggregationToGeCalculationMode() {
        HistoryQuery query = HistoryQuery.builder()
                .tags("AI-101")
                .range(Instant.parse("2023-11-14T00:00:00Z"), Instant.parse("2023-11-15T00:00:00Z"))
                .mode(QueryMode.AGGREGATED)
                .aggregation(Aggregation.AVG)
                .interval(Duration.ofMinutes(1))
                .maxPoints(10)
                .build();
        List<TagSeries> result = client.history().read(query);
        assertEquals(1, result.get(0).getSamples().size());
        assertEquals(3.5d, result.get(0).getSamples().get(0).getDoubleValue(), 0.0001d);
        assertEquals(Quality.UNCERTAIN, result.get(0).getSamples().get(0).getQuality());
        assertTrue(lastAggregatedQuery.get().contains("calculationMode=1"));
        assertTrue(lastAggregatedQuery.get().contains("intervalMs=60000"));
        assertEquals("{\"tagNames\":\"AI-101\"}", lastAggregatedBody.get());
    }

    @Test
    void readsMillisecondIntervalDeltaWithoutRounding() {
        // 扩展起点 22:08:20 起的第 0 个样本就是基线桶，其余 6 个是窗口桶
        calculatedResponse = query -> calculatedBody("AI-101",
                new String[]{"2023-11-14 22:08:20", "2023-11-14 22:13:20", "2023-11-14 22:18:20",
                        "2023-11-14 22:23:20", "2023-11-14 22:28:20", "2023-11-14 22:33:20",
                        "2023-11-14 22:38:20"},
                new String[]{"100.0", "110.0", "120.0", "130.0", "140.0", "150.0", "160.0"});
        aggregatedQueries.clear();

        java.util.List<io.github.forget_the_bright.tsdb.common.model.DeltaSeries> deltas =
                client.deltaHistory().readByInterval(
                        java.util.Arrays.asList("AI-101"),
                        Instant.ofEpochSecond(1700000000L), Instant.ofEpochSecond(1700001800L),
                        300_000L, Aggregation.LAST, FillPolicy.NONE, Direction.FORWARD, 64);

        // 基线折进扩展窗：整批位号只发 1 次 calculated，
        // 起点 = 窗口起点 - 1 个间隔，count = 6 个窗口桶 + 1 个基线桶
        assertEquals(1, aggregatedQueries.size());
        String query = aggregatedQueries.get(0);
        assertTrue(query.contains("calculationMode=19"));
        assertTrue(query.contains("intervalMs=300000"));
        assertTrue(query.contains("start=2023-11-14T22%3A08%3A20Z"));
        assertTrue(query.contains("count=7"));
        assertEquals(1, deltas.size());
        assertEquals(6, deltas.get(0).getPoints().size());
        assertEquals(10.0, deltas.get(0).getPoints().get(0).getDelta(), 1e-9);
    }

    @Test
    void fallsBackToABatchedRawBaselineWhenTheExtendedBucketIsAbsent() {
        // 首样本是扩展桶的占位（Value 为空），不是有效基线；窗口桶从 22:13:20 起共 6 个
        calculatedResponse = query -> calculatedBody("AI-101",
                new String[]{"2023-11-14 22:08:20", "2023-11-14 22:13:20", "2023-11-14 22:18:20",
                        "2023-11-14 22:23:20", "2023-11-14 22:28:20", "2023-11-14 22:33:20",
                        "2023-11-14 22:38:20"},
                new String[]{null, "110.0", "120.0", "130.0", "140.0", "150.0", "160.0"});
        aggregatedQueries.clear();

        java.util.List<io.github.forget_the_bright.tsdb.common.model.DeltaSeries> deltas =
                client.deltaHistory().readByInterval(
                        java.util.Arrays.asList("AI-101"),
                        Instant.ofEpochSecond(1700000000L), Instant.ofEpochSecond(1700001800L),
                        300_000L, Aggregation.LAST, FillPolicy.NONE, Direction.FORWARD, 64);

        // 主查询 1 次 + 兜底 1 次批量 raw（count=1，反向），不逐位号
        assertEquals(1, aggregatedQueries.size());
        assertTrue(lastRawQuery.get().contains("count=1"));
        assertTrue(lastRawQuery.get().contains("direction=0"));
        assertEquals(6, deltas.get(0).getPoints().size());
        assertEquals(108.5, deltas.get(0).getPoints().get(0).getDelta(), 1e-9);
    }

    @Test
    void derivesServerCountFromRangeAndIntervalWithoutDeclaredCount() {
        HistoryQuery aggregated = HistoryQuery.builder()
                .tags("AI-101")
                .range(Instant.parse("2023-11-14T00:00:00Z"), Instant.parse("2023-11-15T00:00:00Z"))
                .mode(QueryMode.AGGREGATED)
                .aggregation(Aggregation.LAST)
                .interval(Duration.ofMinutes(1))
                .build();
        client.history().read(aggregated);
        assertTrue(lastAggregatedQuery.get().contains("count=1440"));

        HistoryQuery interpolated = HistoryQuery.builder()
                .tags("AI-101")
                .range(Instant.parse("2023-11-14T00:00:00Z"), Instant.parse("2023-11-15T00:00:00Z"))
                .mode(QueryMode.INTERPOLATED)
                .interval(Duration.ofMinutes(5))
                .build();
        client.history().read(interpolated);
        assertTrue(lastInterpolatedQuery.get().contains("count=288"));
    }

    @Test
    void rejectsDeclaredCountThatConflictsWithRangeAndInterval() {
        HistoryQuery aggregated = HistoryQuery.builder()
                .tags("AI-101")
                .range(Instant.parse("2023-11-14T00:00:00Z"), Instant.parse("2023-11-15T00:00:00Z"))
                .mode(QueryMode.AGGREGATED)
                .aggregation(Aggregation.LAST)
                .interval(Duration.ofMinutes(1))
                .count(24)
                .build();
        assertThrows(IllegalArgumentException.class, () -> client.history().read(aggregated));

        HistoryQuery sampled = HistoryQuery.builder()
                .tags("AI-101")
                .range(Instant.parse("2023-11-14T00:00:00Z"), Instant.parse("2023-11-15T00:00:00Z"))
                .mode(QueryMode.SAMPLED)
                .interval(Duration.ofMinutes(1))
                .count(24)
                .build();
        assertThrows(IllegalArgumentException.class, () -> client.history().read(sampled));
    }

    @Test
    void rejectsCalendarMonthDelta() {
        assertThrows(io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException.class,
                () -> client.deltaHistory().read(
                        java.util.Arrays.asList("AI-101"),
                        Instant.ofEpochSecond(1700000000L), Instant.ofEpochSecond(1700001800L),
                        io.github.forget_the_bright.tsdb.common.model.TimeGranularity.MONTH,
                        Aggregation.LAST, FillPolicy.NONE, Direction.FORWARD, 64));
    }

    @Test
    void mapsSumToRawTotalNine() {
        // 厂商实证：9=RawTotal 是算术累加和（公共 SUM）；
        // 24=SumValue 是末值减首值的区间累计（INTERVAL_SUM），不得混用。
        HistoryQuery query = HistoryQuery.builder()
                .tags("AI-101")
                .range(Instant.parse("2023-11-14T00:00:00Z"), Instant.parse("2023-11-15T00:00:00Z"))
                .mode(QueryMode.AGGREGATED)
                .aggregation(Aggregation.SUM)
                .interval(Duration.ofMinutes(1))
                .build();
        client.history().read(query);
        assertTrue(lastAggregatedQuery.get().contains("calculationMode=9"));
        assertFalse(lastAggregatedQuery.get().contains("calculationMode=24"));
    }

    @Test
    void computesIntervalSumAsLastMinusFirst() {
        // INTERVAL_SUM 复刻旧 getIntervalValueBySumTag：两次 POST calculated
        // （17=FirstRawValue、19=LastRawValue，count=1、intervalMs=1），末减首。
        Instant start = Instant.parse("2023-11-14T00:00:00Z");
        HistoryQuery query = HistoryQuery.builder()
                .tags("AI-101", "AI-BAD")
                .range(start, Instant.parse("2023-11-15T00:00:00Z"))
                .mode(QueryMode.AGGREGATED)
                .aggregation(Aggregation.INTERVAL_SUM)
                .count(1)
                .build();
        List<TagSeries> result = client.history().read(query);
        assertEquals(2, result.size());

        assertEquals(1, result.get(0).getSamples().size());
        assertEquals(10.25d, result.get(0).getSamples().get(0).getDoubleValue(), 1e-9d);
        assertEquals(start, result.get(0).getSamples().get(0).getTimestamp());
        assertEquals(Quality.GOOD, result.get(0).getSamples().get(0).getQuality());

        // 旧 SDK 边界语义：首样本 BAD（厂商质量码 0）按 0 参与计算，999 - 0 = 999。
        assertEquals(999d, result.get(1).getSamples().get(0).getDoubleValue(), 1e-9d);

        assertEquals(2, aggregatedQueries.size());
        assertTrue(aggregatedQueries.get(0).contains("calculationMode=17"));
        assertTrue(aggregatedQueries.get(1).contains("calculationMode=19"));
        for (String q : aggregatedQueries) {
            assertTrue(q.contains("count=1"));
            assertTrue(q.contains("intervalMs=1"));
            assertFalse(q.contains("tagNames"));
        }
        assertEquals("{\"tagNames\":\"AI-101;AI-BAD\"}", lastAggregatedBody.get());
    }

    @Test
    void readsSampledHistoryWithInterpolatedSamplingMode() {
        // SAMPLED 模式对齐业务 GeUtil 主流曲线路径：
        // POST sampled，samplingMode=2（Interpolated）+ queryModifier=1。
        HistoryQuery query = HistoryQuery.builder()
                .tags("AI-101")
                .range(Instant.parse("2023-11-14T00:00:00Z"), Instant.parse("2023-11-15T00:00:00Z"))
                .mode(QueryMode.SAMPLED)
                .aggregation(Aggregation.LAST)
                .interval(Duration.ofMinutes(1))
                .build();
        List<TagSeries> result = client.history().read(query);
        assertEquals(1, result.size());
        assertEquals("POST", lastSampledMethod.get());
        JSONObject body = json(lastSampledBody.get());
        assertTrue(body.get("samplingMode") instanceof String);
        assertEquals("2", body.getString("samplingMode"));
        assertTrue(body.get("queryModifier") instanceof Number);
        assertEquals(1, body.getIntValue("queryModifier"));
        assertTrue(body.get("intervalMs") instanceof Number);
        assertEquals(60000L, body.getLongValue("intervalMs"));
        assertEquals("2023-11-14T00:00:00Z", body.getString("start"));
        assertEquals("2023-11-15T00:00:00Z", body.getString("end"));
        assertTrue(body.get("direction") instanceof String);
        assertEquals("1", body.getString("direction"));
        assertEquals("AI-101", body.getString("tagNames"));
        // SAMPLED 忽略 aggregation，不下发 calculationMode；区间已知时 count
        // 由时间窗与间隔精确推导（24 小时 / 1 分钟 = 1440）。
        assertFalse(body.containsKey("calculationMode"));
        assertEquals(1440, body.getIntValue("count"));
    }

    @Test
    void sampledHistoryWithoutIntervalForwardsDeclaredCount() {
        // 没有 interval 时无法推导，显式 count 原样下发（SAMPLED 要求二者至少一个）。
        HistoryQuery query = HistoryQuery.builder()
                .tags("AI-101")
                .range(Instant.parse("2023-11-14T00:00:00Z"), Instant.parse("2023-11-15T00:00:00Z"))
                .mode(QueryMode.SAMPLED)
                .count(10)
                .build();
        client.history().read(query);
        JSONObject body = json(lastSampledBody.get());
        assertEquals(10, body.getIntValue("count"));
    }

    @Test
    void readsInterpolatedHistoryViaPostWithCountOnly() {
        // INTERPOLATED 模式走 POST interpolated 端点；只设 count 不设 interval 时
        // 下发 intervalMs=1（复刻旧 SDK count+intervalMs=1 的惯用技巧）。
        HistoryQuery query = HistoryQuery.builder()
                .tags("AI-101")
                .range(Instant.parse("2023-11-14T00:00:00Z"), Instant.parse("2023-11-15T00:00:00Z"))
                .mode(QueryMode.INTERPOLATED)
                .count(24)
                .build();
        List<TagSeries> result = client.history().read(query);
        assertEquals(1, result.size());
        assertEquals(7.25d, result.get(0).getSamples().get(0).getDoubleValue(), 1e-9d);
        assertEquals("POST", lastInterpolatedMethod.get());
        assertTrue(lastInterpolatedQuery.get().contains("count=24"));
        assertTrue(lastInterpolatedQuery.get().contains("intervalMs=1"));
        assertFalse(lastInterpolatedQuery.get().contains("tagNames"));
        assertEquals("{\"tagNames\":\"AI-101\"}", lastInterpolatedBody.get());
    }

    @Test
    void failsLoudlyOnUnparseableTimestamp() {
        // 响应时间文本不可解析时必须显式失败并暴露原文，不得静默丢点。
        GeNativeApi api = client.unwrap(GeNativeApi.class);
        TsdbDataException error = assertThrows(TsdbDataException.class,
                () -> api.readRaw(Arrays.asList("AI-BADTIME"),
                        Instant.parse("2023-11-14T00:00:00Z"), Instant.parse("2023-11-15T00:00:00Z"),
                        Direction.FORWARD, 5));
        assertTrue(error.getMessage().contains("not-a-time"));
        assertTrue(error.getMessage().contains("AI-BADTIME"));
    }

    @Test
    void rejectsUnsupportedFiltersAndModes() {
        assertThrows(TsdbUnsupportedException.class,
                () -> client.tagCatalog().listTags(TagQuery.builder()
                        .valueType(ValueType.DOUBLE).build()));
        HistoryQuery sampled = HistoryQuery.builder()
                .tags("AI-101")
                .range(Instant.parse("2023-11-14T00:00:00Z"), Instant.parse("2023-11-15T00:00:00Z"))
                .mode(QueryMode.SAMPLED)
                .aggregation(Aggregation.LAST)
                .interval(Duration.ofMinutes(1))
                .build();
        assertTrue(client.capabilities().supports(Capability.SAMPLED_HISTORY));
        assertEquals(1, client.history().read(sampled).size());
        assertTrue(client.capabilities().supports(Capability.CUMULATIVE_DELTA));

        HistoryQuery trend = HistoryQuery.builder()
                .tags("AI-101")
                .range(Instant.parse("2023-11-14T00:00:00Z"), Instant.parse("2023-11-15T00:00:00Z"))
                .mode(QueryMode.TREND)
                .build();
        assertThrows(TsdbUnsupportedException.class, () -> client.history().read(trend));
    }

    @Test
    void rejectsPlaintextTransportByDefault() {
        TsdbInstanceConfig config = new TsdbInstanceConfig();
        config.setBaseUrl("http://127.0.0.1:1");
        config.getAuth().setToken("static");
        SecurityConfig secureByDefault = new SecurityConfig();
        assertThrows(TsdbConfigurationException.class,
                () -> new GeConfig("ge", config, secureByDefault));
    }

    @Test
    void endpointCatalogCoversAllGeModules() {
        assertEquals(92, GeEndpoint.values().length);
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (GeEndpoint endpoint : GeEndpoint.values()) {
            assertNotNull(endpoint.description());
            assertNotNull(endpoint.path());
            assertTrue(endpoint.path().startsWith("/v1/"));
            assertTrue(endpoint.method().matches("GET|POST|PUT|DELETE"));
            Integer count = counts.get(endpoint.module());
            counts.put(endpoint.module(), count == null ? 1 : count + 1);
        }
        assertEquals(Integer.valueOf(22), counts.get("data"));
        assertEquals(Integer.valueOf(13), counts.get("tags"));
        assertEquals(Integer.valueOf(25), counts.get("systems"));
        assertEquals(Integer.valueOf(27), counts.get("collectors"));
        assertEquals(Integer.valueOf(5), counts.get("alarms"));
    }

    @Test
    void invokesNativeQueryEndpointWithQueryParameters() {
        GeNativeApi api = client.unwrap(GeNativeApi.class);
        Map<String, Object> query = new LinkedHashMap<String, Object>();
        query.put("maxNumber", 100);
        query.put("nameMask", "AI*");
        JSONObject result = JsonText.object(api.invoke(GeEndpoint.QUERY_TAGS_BY_PARAMS, query));

        JSONArray tags = result.getJSONArray("Tags");
        assertEquals(2, tags.size());
        assertEquals("AI-101", tags.getString(0));
    }

    @Test
    void invokesNativePathEndpointWithPathVariables() {
        GeNativeApi api = client.unwrap(GeNativeApi.class);
        Map<String, Object> path = new LinkedHashMap<String, Object>();
        path.put("tagNames", "AI-101,AI-102");
        path.put("start", "2023-11-14T00:00:00Z");
        path.put("end", "2023-11-15T00:00:00Z");
        path.put("direction", 1);
        path.put("count", 10);
        JSONObject result = JsonText.object(api.invoke(GeEndpoint.GET_RAW_DATA_BY_PATH_VARIABLE, path));

        JSONArray data = result.getJSONArray("Data");
        assertEquals(1, data.size());
        assertEquals("AI-101", data.getJSONObject(0).getString("TagName"));
    }

    @Test
    void invokesNativeBodyEndpoint() {
        GeNativeApi api = client.unwrap(GeNativeApi.class);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("TagName", "AI-101");
        JSONObject result = JsonText.object(api.invoke(GeEndpoint.CREATE_TAG_DATA, body));

        assertEquals(0, result.getIntValue("ErrorCode"));
        assertTrue(lastCreateBody.get().contains("AI-101"));
    }

    @Test
    void unwrapsNativeApi() {
        assertNotNull(client.unwrap(GeNativeApi.class));
        assertEquals("ge", client.type());
    }

    private static JSONObject json(String text) {
        return JSON.parseObject(text);
    }

    /**
     * 默认 calculated 应答：按 calculationMode 返回首值（17）/末值（19），
     * 供 INTERVAL_SUM 双请求算法断言；AI-BAD 首样本质量为 BAD（厂商码 0）。
     *
     * @param query 查询串
     * @return 应答 JSON
     */
    private static String defaultCalculatedBody(String query) {
        if (query != null && query.contains("calculationMode=17")) {
            return "{\"ErrorCode\":0,\"Data\":["
                    + "{\"TagName\":\"AI-101\",\"ErrorCode\":0,\"DataType\":\"Float\",\"Samples\":["
                    + "{\"TimeStamp\":\"2023-11-14 22:14:00\",\"Value\":\"100.5\",\"Quality\":3}]},"
                    + "{\"TagName\":\"AI-BAD\",\"ErrorCode\":0,\"DataType\":\"Float\",\"Samples\":["
                    + "{\"TimeStamp\":\"2023-11-14 22:14:00\",\"Value\":\"50\",\"Quality\":0}]}]}";
        }
        if (query != null && query.contains("calculationMode=19")) {
            return "{\"ErrorCode\":0,\"Data\":["
                    + "{\"TagName\":\"AI-101\",\"ErrorCode\":0,\"DataType\":\"Float\",\"Samples\":["
                    + "{\"TimeStamp\":\"2023-11-14 22:14:00\",\"Value\":\"110.75\",\"Quality\":3}]},"
                    + "{\"TagName\":\"AI-BAD\",\"ErrorCode\":0,\"DataType\":\"Float\",\"Samples\":["
                    + "{\"TimeStamp\":\"2023-11-14 22:14:00\",\"Value\":\"999\",\"Quality\":3}]}]}";
        }
        return "{\"ErrorCode\":0,\"Data\":["
                + "{\"TagName\":\"AI-101\",\"ErrorCode\":0,\"DataType\":\"Float\",\"Samples\":["
                + "{\"TimeStamp\":\"2023-11-14 22:14:00\",\"Value\":\"3.5\",\"Quality\":1}]}]}";
    }

    /**
     * 生成单一位号、等间隔的 calculated 应答。
     *
     * @param tag        位号
     * @param timestamps 时间文本，与数值一一对应
     * @param values     数值文本；{@code null} 表示该桶无值（JSON {@code null}）
     * @return 应答 JSON
     */
    private static String calculatedBody(String tag, String[] timestamps, String[] values) {
        StringBuilder samples = new StringBuilder();
        for (int i = 0; i < timestamps.length; i++) {
            if (i > 0) {
                samples.append(',');
            }
            samples.append("{\"TimeStamp\":\"").append(timestamps[i]).append("\",\"Value\":")
                    .append(values[i] == null ? "null" : "\"" + values[i] + "\"")
                    .append(",\"Quality\":3}");
        }
        return "{\"ErrorCode\":0,\"Data\":[{\"TagName\":\"" + tag
                + "\",\"ErrorCode\":0,\"DataType\":\"Float\",\"Samples\":["
                + samples + "]}]}";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        InputStream in = exchange.getRequestBody();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}