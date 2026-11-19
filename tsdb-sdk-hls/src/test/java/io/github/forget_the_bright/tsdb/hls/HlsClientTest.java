package io.github.forget_the_bright.tsdb.hls;

import com.alibaba.fastjson.JSONObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.forget_the_bright.tsdb.common.config.SecurityConfig;
import io.github.forget_the_bright.tsdb.common.config.TsdbInstanceConfig;
import io.github.forget_the_bright.tsdb.common.exception.TsdbConfigurationException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException;
import io.github.forget_the_bright.tsdb.common.model.Aggregation;
import io.github.forget_the_bright.tsdb.common.model.Capability;
import io.github.forget_the_bright.tsdb.common.model.DeltaSeries;
import io.github.forget_the_bright.tsdb.common.model.Direction;
import io.github.forget_the_bright.tsdb.common.model.FillPolicy;
import io.github.forget_the_bright.tsdb.common.model.HistoryQuery;
import io.github.forget_the_bright.tsdb.common.model.Quality;
import io.github.forget_the_bright.tsdb.common.model.TagQuery;
import io.github.forget_the_bright.tsdb.common.model.TagSample;
import io.github.forget_the_bright.tsdb.common.model.TagSeries;
import io.github.forget_the_bright.tsdb.common.model.TimeGranularity;
import io.github.forget_the_bright.tsdb.common.model.ValueType;
import io.github.forget_the_bright.tsdb.hls.internal.HlsNativeApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HlsClientTest {

    private HttpServer server;
    private HlsClient client;
    private final AtomicReference<String> historyRequest = new AtomicReference<String>();
    private final java.util.List<String> historyRequests =
            java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
    /**
     * 宽表历史接口的应答生成器；用例可替换它来固定「稠密 / 缺桶」两类响应。
     */
    private volatile Function<String, String> hdbResponse;
    private final AtomicReference<String> lastToken = new AtomicReference<String>();
    private final AtomicReference<String> tokenRequestMethod = new AtomicReference<String>();
    private final AtomicReference<String> tokenRequestContentType = new AtomicReference<String>();
    private final AtomicReference<String> tokenRequestBody = new AtomicReference<String>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/user/get_token", exchange -> {
            tokenRequestMethod.set(exchange.getRequestMethod());
            tokenRequestContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            tokenRequestBody.set(readBody(exchange));
            lastToken.set(exchange.getRequestHeaders().getFirst("token"));
            respond(exchange, 200, "{\"code\":0,\"msg\":\"ok\",\"Data\":{\"Token\":\"tk-1\"}}");
        });
        server.createContext("/ddb/read_alltagname", exchange -> {
            lastToken.set(exchange.getRequestHeaders().getFirst("token"));
            respond(exchange, 200, "{\"code\":0,\"msg\":\"ok\",\"Data\":{\"TagNameList\":["
                    + "{\"TagName\":\"AI-101\",\"TagDes\":\"feed flow\"},"
                    + "{\"TagName\":\"AI-102\",\"TagDes\":\"product flow\"}]}}");
        });
        server.createContext("/ddb/read_ddbtagvalue", exchange -> {
            lastToken.set(exchange.getRequestHeaders().getFirst("token"));
            String request = readBody(exchange);
            String one = "{\"code\":0,\"msg\":\"ok\",\"Data\":{\"DDBTagValueList\":["
                    + "{\"Quality\":1,\"TagSize\":\"4\",\"TagType\":7,\"TagValue\":\"12.5\","
                    + "\"TagValueTime\":1700000000}]}}";
            String two = "{\"code\":0,\"msg\":\"ok\",\"Data\":{\"DDBTagValueList\":["
                    + "{\"Quality\":1,\"TagSize\":\"4\",\"TagType\":7,\"TagValue\":\"12.5\","
                    + "\"TagValueTime\":1700000000},"
                    + "{\"Quality\":0,\"TagSize\":\"4\",\"TagType\":8,\"TagValue\":\"bad\","
                    + "\"TagValueTime\":1700000001}]}}";
            respond(exchange, 200, request.contains("AI-999") ? one : two);
        });
        server.createContext("/hdb/read_hdbtagvalue", exchange -> {
            lastToken.set(exchange.getRequestHeaders().getFirst("token"));
            String request = readBody(exchange);
            historyRequest.set(request);
            historyRequests.add(request);
            respond(exchange, 200, hdbResponse.apply(request));
        });
        server.start();
        // 起点窗与终点窗返回不同的边界值，供 INTERVAL_SUM 的末−首断言；
        // 其余请求保持 1.0，避免影响既有差分/历史用例。
        hdbResponse = HlsClientTest::defaultHdbBody;

        TsdbInstanceConfig config = new TsdbInstanceConfig();
        config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        config.getAuth().setUserId("user");
        config.getAuth().setSecretKey("secret");
        SecurityConfig security = new SecurityConfig();
        security.setAllowInsecureTransport(true);
        client = new HlsClient("hls-test", new HlsConfig("hls-test", config, security));
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
    void exposesDeclaredCapabilities() {
        assertTrue(client.capabilities().supports(Capability.TAG_BROWSE));
        assertTrue(client.capabilities().supports(Capability.CURRENT_VALUE));
        assertTrue(client.capabilities().supports(Capability.RAW_HISTORY));
        assertTrue(client.capabilities().supports(Capability.AGGREGATED_HISTORY));
        assertTrue(client.capabilities().supports(Capability.SAMPLED_HISTORY));
        assertTrue(client.capabilities().supports(Capability.CUMULATIVE_DELTA));
        assertFalse(client.capabilities().supports(Capability.WRITE));
        assertFalse(client.writeSession().isWritable());
    }

    @Test
    void listsTagsAndSendsTokenHeader() {
        assertEquals(2, client.tagCatalog().listTags(TagQuery.builder().limit(0).build()).size());
        assertEquals("feed flow", client.tagCatalog().getTag("AI-101").getDescription());
        assertEquals("tk-1", lastToken.get());
        assertEquals("POST", tokenRequestMethod.get());
        assertEquals("application/json", tokenRequestContentType.get());
        assertTrue(tokenRequestBody.get().contains("\"userid\":\"user\""));
        assertTrue(tokenRequestBody.get().contains("\"secretkey\":\"secret\""));
    }

    @Test
    void rejectsTagSearch() {
        assertThrows(TsdbUnsupportedException.class,
                () -> client.tagCatalog().listTags(TagQuery.builder().nameMask("AI*").build()));
    }

    @Test
    void readsCurrentValuesInRequestedOrder() {
        List<TagSample> values = client.currentValues().read(Arrays.asList("AI-101", "AI-102"));
        assertEquals(2, values.size());
        assertEquals("AI-101", values.get(0).getTagName());
        assertEquals(ValueType.DOUBLE, values.get(0).getSample().getValueType());
        assertEquals(12.5d, values.get(0).getSample().getDoubleValue(), 0.0001d);
        assertEquals(Quality.GOOD, values.get(0).getSample().getQuality());
        assertEquals(Instant.ofEpochSecond(1700000000L), values.get(0).getSample().getTimestamp());
        assertEquals(Date.from(Instant.ofEpochSecond(1700000000L)),
                values.get(0).getSample().getTimestampDate());
        assertEquals("2023-11-14 22:13:20", values.get(0).getSample().getTimestampText());

        assertEquals(Quality.BAD, values.get(1).getSample().getQuality());
        assertEquals("bad", values.get(1).getSample().getTextValue());
    }

    @Test
    void mapsMissingCurrentValueToNullSample() {
        List<TagSample> values = client.currentValues().read(Arrays.asList("AI-101", "AI-999"));
        assertNotNull(values.get(0).getSample());
        assertNull(values.get(1).getSample());
    }

    @Test
    void readsAggregatedHistoryAndMapsAvgQualityToUnknown() {
        HistoryQuery query = HistoryQuery.builder()
                .tags("AI-101")
                .range(Instant.ofEpochSecond(1699999000L), Instant.ofEpochSecond(1700001000L))
                .mode(io.github.forget_the_bright.tsdb.common.model.QueryMode.AGGREGATED)
                .aggregation(Aggregation.AVG)
                .interval(Duration.ofSeconds(60))
                .build();
        List<TagSeries> series = client.history().read(query);
        assertEquals(1, series.size());
        assertEquals(1, series.get(0).getSamples().size());
        assertEquals(Quality.UNKNOWN, series.get(0).getSamples().get(0).getQuality());
        assertEquals("hls_not_provided", series.get(0).getAttributes().get("qualitySource"));
        assertEquals(1.5d, series.get(0).getSamples().get(0).getDoubleValue(), 0.0001d);

        String request = historyRequest.get();
        assertTrue(request.contains("\"NeedQueryAVG\":true"));
        assertTrue(request.contains("\"NeedQueryMIN\":false"));
        assertTrue(request.contains("\"interval\":60"));
    }

    @Test
    void usesColumnSpecificQualityForMinAndMax() {
        HistoryQuery min = HistoryQuery.builder()
                .tags("AI-101")
                .range(Instant.ofEpochSecond(1699999000L), Instant.ofEpochSecond(1700001000L))
                .mode(io.github.forget_the_bright.tsdb.common.model.QueryMode.AGGREGATED)
                .aggregation(Aggregation.MIN)
                .interval(Duration.ofSeconds(60))
                .build();
        TagSeries minSeries = client.history().read(min).get(0);
        assertEquals(Quality.GOOD, minSeries.getSamples().get(0).getQuality());
        assertEquals(1.0d, minSeries.getSamples().get(0).getDoubleValue(), 0.0001d);

        HistoryQuery max = HistoryQuery.builder()
                .tags("AI-101")
                .range(Instant.ofEpochSecond(1699999000L), Instant.ofEpochSecond(1700001000L))
                .mode(io.github.forget_the_bright.tsdb.common.model.QueryMode.AGGREGATED)
                .aggregation(Aggregation.MAX)
                .interval(Duration.ofSeconds(60))
                .build();
        TagSeries maxSeries = client.history().read(max).get(0);
        assertEquals(Quality.BAD, maxSeries.getSamples().get(0).getQuality());
        assertEquals(2.0d, maxSeries.getSamples().get(0).getDoubleValue(), 0.0001d);
    }

    @Test
    void readsRawHistoryViaOneSecondBoundBuckets() {
        historyRequests.clear();
        HistoryQuery query = HistoryQuery.builder()
                .tags("AI-101")
                .range(Instant.ofEpochSecond(1699999000L), Instant.ofEpochSecond(1700001000L))
                .mode(io.github.forget_the_bright.tsdb.common.model.QueryMode.RAW)
                .maxPoints(300)
                .build();
        List<TagSeries> series = client.history().read(query);
        assertEquals(1, series.size());
        assertEquals(1, series.get(0).getSamples().size());
        assertEquals(Instant.ofEpochSecond(1700000000L), series.get(0).getSamples().get(0).getTimestamp());
        assertEquals(1.0d, series.get(0).getSamples().get(0).getDoubleValue(), 0.0001d);
        assertEquals("hls_history_1s_bound", series.get(0).getAttributes().get("rawSource"));
        assertEquals("true", series.get(0).getAttributes().get("rawApproximate"));

        String request = historyRequests.get(0);
        assertTrue(request.contains("\"interval\":1"));
        assertTrue(request.contains("\"NeedQueryBound\":true"));
        assertFalse(request.contains("\"NeedQueryAVG\":true"));
    }

    @Test
    void rejectsBackwardRawHistory() {
        HistoryQuery query = HistoryQuery.builder()
                .tags("AI-101")
                .range(Instant.ofEpochSecond(1699999000L), Instant.ofEpochSecond(1700001000L))
                .mode(io.github.forget_the_bright.tsdb.common.model.QueryMode.RAW)
                .direction(Direction.BACKWARD)
                .build();
        assertThrows(TsdbUnsupportedException.class, () -> client.history().read(query));
    }

    @Test
    void computesIntervalSumFromTwoOneMinuteEdgeProbes() {
        Instant start = Instant.ofEpochSecond(1700000000L);
        Instant end = Instant.ofEpochSecond(1700000600L);
        historyRequests.clear();
        HistoryQuery query = HistoryQuery.builder()
                .tags("AI-101")
                .range(start, end)
                .mode(io.github.forget_the_bright.tsdb.common.model.QueryMode.AGGREGATED)
                .aggregation(Aggregation.INTERVAL_SUM)
                .interval(Duration.ofSeconds(60))
                .build();
        List<TagSeries> series = client.history().read(query);
        assertEquals(1, series.get(0).getSamples().size());
        assertEquals(32.0d, series.get(0).getSamples().get(0).getDoubleValue(), 0.0001d);
        assertEquals(start, series.get(0).getSamples().get(0).getTimestamp());
        assertEquals(2, historyRequests.size());
        assertTrue(historyRequests.get(0).contains("\"interval\":1"));
        assertTrue(historyRequests.get(1).contains("\"interval\":1"));
    }

    @Test
    void intervalSumEmitsNoPointWhenAnEdgeIsMissing() {
        HistoryQuery query = HistoryQuery.builder()
                .tags("AI-999")
                .range(Instant.ofEpochSecond(1700000000L), Instant.ofEpochSecond(1700000600L))
                .mode(io.github.forget_the_bright.tsdb.common.model.QueryMode.AGGREGATED)
                .aggregation(Aggregation.INTERVAL_SUM)
                .interval(Duration.ofSeconds(60))
                .build();
        List<TagSeries> series = client.history().read(query);
        assertTrue(series.get(0).getSamples().isEmpty());
    }

    @Test
    void readsFiveMinuteCumulativeDeltaByInterval() {
        historyRequests.clear();
        List<DeltaSeries> deltas = client.deltaHistory().readByInterval(
                Arrays.asList("AI-101"),
                Instant.ofEpochSecond(1699999200L), Instant.ofEpochSecond(1700001000L),
                300_000L, Aggregation.LAST, FillPolicy.NONE, Direction.FORWARD, 64);
        assertEquals(1, deltas.size());
        assertFalse(deltas.get(0).getPoints().isEmpty());
        assertTrue(historyRequests.get(0).contains("\"interval\":300"));
    }

    @Test
    void foldsTheBaselineBucketIntoOneHistoryRequestForDenseMultiTagDelta() {
        // 起点 1699999200、终点 1700001000、300 秒桶 → 6 个窗口桶 + 1 个基线桶
        Map<String, List<Object[]>> body = new LinkedHashMap<String, List<Object[]>>();
        body.put("AI-101", ramp(1699998900L, 300L, 7, 100.0, 10.0));
        body.put("AI-102", ramp(1699998900L, 300L, 7, 200.0, 5.0));
        hdbResponse = request -> hdbBody(body);
        historyRequests.clear();

        List<DeltaSeries> deltas = client.deltaHistory().readByInterval(
                Arrays.asList("AI-101", "AI-102"),
                Instant.ofEpochSecond(1699999200L), Instant.ofEpochSecond(1700001000L),
                300_000L, Aggregation.LAST, FillPolicy.NONE, Direction.FORWARD, 6);

        // 稠密响应：基线折进扩展桶，整批位号只发 1 次请求
        assertEquals(1, historyRequests.size());
        String request = historyRequests.get(0);
        assertTrue(request.contains("\"interval\":300"));
        assertTrue(request.contains("\"startTime\":1699998900"));
        assertEquals(6, deltas.get(0).getPoints().size());
        assertEquals(10.0, deltas.get(0).getPoints().get(0).getDelta(), 0.0001);
        assertEquals(5.0, deltas.get(1).getPoints().get(0).getDelta(), 0.0001);
        assertTrue(deltas.get(0).getPoints().size() <= 6);
    }

    @Test
    void fallsBackToABatchedBaselineRequestForTheTagMissingTheExtendedBucket() {
        // 首轮：AI-101 稠密 7 个，AI-102 只回窗口内 1 个（扩展桶缺失）；
        // 兜底轮：请求里不再含 AI-101，只回 AI-102 的基线。
        hdbResponse = request -> {
            if (request.contains("AI-101")) {
                Map<String, List<Object[]>> body = new LinkedHashMap<String, List<Object[]>>();
                body.put("AI-101", ramp(1699998900L, 300L, 7, 100.0, 10.0));
                body.put("AI-102", ramp(1700000000L, 300L, 1, 9.0, 0.0));
                return hdbBody(body);
            }
            Map<String, List<Object[]>> body = new LinkedHashMap<String, List<Object[]>>();
            body.put("AI-102", ramp(1699998900L, 300L, 1, 5.0, 0.0));
            return hdbBody(body);
        };
        historyRequests.clear();

        List<DeltaSeries> deltas = client.deltaHistory().readByInterval(
                Arrays.asList("AI-101", "AI-102"),
                Instant.ofEpochSecond(1699999200L), Instant.ofEpochSecond(1700001000L),
                300_000L, Aggregation.LAST, FillPolicy.NONE, Direction.FORWARD, 64);

        assertEquals(2, historyRequests.size());
        String fallback = historyRequests.get(1);
        assertTrue(fallback.contains("AI-102"));
        assertFalse(fallback.contains("AI-101"));
        assertTrue(fallback.contains("\"startTime\":1699998900"));
        assertEquals(10.0, deltas.get(0).getPoints().get(0).getDelta(), 0.0001);
        assertEquals(4.0, deltas.get(1).getPoints().get(0).getDelta(), 0.0001);
    }

    @Test
    void rejectsSubSecondIntervalDelta() {
        assertThrows(TsdbUnsupportedException.class,
                () -> client.deltaHistory().readByInterval(
                        Arrays.asList("AI-101"),
                        Instant.ofEpochSecond(1700000000L), Instant.ofEpochSecond(1700001000L),
                        1500L, Aggregation.LAST, FillPolicy.NONE, Direction.FORWARD, 64));
    }

    @Test
    void readsSampledHistoryAndComputesCumulativeDelta() {
        Instant end = Instant.ofEpochSecond(1700001000L);
        HistoryQuery sampled = HistoryQuery.builder()
                .tags("AI-101")
                .range(end.minus(Duration.ofHours(2)), end)
                .mode(io.github.forget_the_bright.tsdb.common.model.QueryMode.SAMPLED)
                .interval(Duration.ofHours(1))
                .build();
        assertEquals(1, client.history().read(sampled).get(0).getSamples().size());
        assertTrue(client.capabilities().supports(Capability.SAMPLED_HISTORY));
        assertTrue(client.capabilities().supports(Capability.CUMULATIVE_DELTA));

        List<DeltaSeries> deltas = client.deltaHistory().read(Arrays.asList("AI-101"),
                end.minus(Duration.ofHours(2)), end, TimeGranularity.HOUR,
                Aggregation.LAST, FillPolicy.NONE, Direction.FORWARD, 64);
        assertEquals(1, deltas.size());
        assertEquals("AI-101", deltas.get(0).getTagName());
        assertFalse(deltas.get(0).getPoints().isEmpty());
    }

    @Test
    void exposesNativeApiWithAllAggregationOverloads() {
        HlsNativeApi api = client.unwrap(HlsNativeApi.class);
        JSONObject tags = api.queryAllTags();
        assertEquals(2, tags.getJSONObject("Data").getJSONArray("TagNameList").size());

        api.getHDBTagValueBound(Instant.ofEpochSecond(1699999000L),
                Instant.ofEpochSecond(1700001000L), 60L, "AI-101");
        assertTrue(historyRequest.get().contains("\"NeedQueryBound\":true"));
        assertTrue(historyRequest.get().contains("\"NeedQueryMAX\":false"));
        assertTrue(historyRequest.get().contains("\"AI-101\""));
    }

    @Test
    void nativeCurrentValueCarriesDateAndFormattedText() {
        HlsNativeApi api = client.unwrap(HlsNativeApi.class);
        List<io.github.forget_the_bright.tsdb.hls.internal.HlsModels.CurrentValue> values =
                api.readCurrent(Arrays.asList("AI-101"));
        assertEquals(Date.from(Instant.ofEpochSecond(1700000000L)), values.get(0).timestampDate);
        assertEquals("2023-11-14 22:13:20", values.get(0).timestampText);
    }

    @Test
    void rejectsPlaintextTransportByDefault() {
        TsdbInstanceConfig config = new TsdbInstanceConfig();
        config.setBaseUrl("http://127.0.0.1:1");
        config.getAuth().setUserId("user");
        config.getAuth().setSecretKey("secret");
        assertThrows(TsdbConfigurationException.class,
                () -> new HlsConfig("x", config, new SecurityConfig()));
    }

    @Test
    void supportsConfiguredTimeZoneForParsingAndDisplay() {
        TsdbInstanceConfig config = new TsdbInstanceConfig();
        config.setBaseUrl("http://127.0.0.1:1");
        config.getAuth().setUserId("user");
        config.getAuth().setSecretKey("secret");
        java.util.Map<String, Object> response = new java.util.LinkedHashMap<String, Object>();
        response.put("source-zone", "GMT+8");
        response.put("target-zone", "GMT+8");
        response.put("pattern", "yyyy-MM-dd HH:mm:ss");
        java.util.Map<String, Object> time = new java.util.LinkedHashMap<String, Object>();
        time.put("response", response);
        config.getOptions().put("time", time);
        SecurityConfig security = new SecurityConfig();
        security.setAllowInsecureTransport(true);
        HlsClient timeClient = new HlsClient("hls-time", new HlsConfig("hls-time", config, security));
        try {
            assertEquals("GMT+08:00", timeClient.timeConfig().getResponseTargetZone().getId());
            assertEquals("2023-11-15 06:13:21",
                    timeClient.formatTimestamp(Instant.parse("2023-11-14T22:13:21Z")));
            assertEquals(Instant.parse("2023-11-14T22:13:21Z"),
                    timeClient.parseTimestamp("2023-11-15 06:13:21"));
        } finally {
            timeClient.close();
        }
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
        try (InputStream in = exchange.getRequestBody()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 默认宽表应答：AI-101 单点，{@code TagValue_Bound} 随起点窗变化。
     *
     * @param request 请求体
     * @return 应答 JSON
     */
    private static String defaultHdbBody(String request) {
        String bound = "1.0";
        if (request.contains("\"startTime\":1700000000")) {
            bound = "10.0";
        } else if (request.contains("\"startTime\":1700000540")) {
            bound = "42.0";
        }
        return "{\"code\":0,\"msg\":\"ok\",\"Data\":{\"HDBTagValueList\":["
                + "{\"Index\":0,\"TagType\":7,\"TagName\":\"AI-101\",\"OneTagHDBValueList\":["
                + "{\"TagValueTime\":1700000000,\"TagValue_AVG\":\"1.5\",\"TagValue_MIN\":\"1.0\","
                + "\"TagValue_MAX\":\"2.0\",\"TagValue_Bound\":\"" + bound + "\","
                + "\"Quality_MIN\":1,\"Quality_MAX\":0,\"Quality_Bound\":1}]}]}}";
    }

    /**
     * 按位号生成宽表 BOUND 应答；每个位号的 {@code TagValueTime} 与
     * {@code TagValue_Bound} 一一对应。
     *
     * @param series 位号 → 「时间, 值」二元组列表，按输出顺序
     * @return 应答 JSON
     */
    private static String hdbBody(Map<String, List<Object[]>> series) {
        StringBuilder list = new StringBuilder();
        for (Map.Entry<String, List<Object[]>> entry : series.entrySet()) {
            if (list.length() > 0) {
                list.append(',');
            }
            list.append("{\"Index\":0,\"TagType\":7,\"TagName\":\"").append(entry.getKey())
                    .append("\",\"OneTagHDBValueList\":[");
            List<Object[]> points = entry.getValue();
            for (int i = 0; i < points.size(); i++) {
                if (i > 0) {
                    list.append(',');
                }
                Object time = points.get(i)[0];
                Object value = points.get(i)[1];
                list.append("{\"TagValueTime\":").append(time)
                        .append(",\"TagValue_AVG\":\"").append(value)
                        .append("\",\"TagValue_MIN\":\"").append(value)
                        .append("\",\"TagValue_MAX\":\"").append(value)
                        .append("\",\"TagValue_Bound\":\"").append(value)
                        .append("\",\"Quality_MIN\":1,\"Quality_MAX\":1,\"Quality_Bound\":1}");
            }
            list.append("]}");
        }
        return "{\"code\":0,\"msg\":\"ok\",\"Data\":{\"HDBTagValueList\":[" + list + "]}}";
    }

    /**
     * 生成等间隔的「时间, 值」序列。
     *
     * @param first  首个时间（秒）
     * @param step   间隔（秒）
     * @param count  点数
     * @param base   首个数值
     * @param delta  相邻数值增量
     * @return 序列
     */
    private static List<Object[]> ramp(long first, long step, int count, double base, double delta) {
        List<Object[]> points = new ArrayList<Object[]>(count);
        for (int i = 0; i < count; i++) {
            points.add(new Object[]{first + i * step, base + i * delta});
        }
        return points;
    }
}
