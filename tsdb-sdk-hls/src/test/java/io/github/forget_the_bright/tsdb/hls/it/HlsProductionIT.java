package io.github.forget_the_bright.tsdb.hls.it;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.github.forget_the_bright.tsdb.common.api.TsdbClient;
import io.github.forget_the_bright.tsdb.common.config.AuthConfig;
import io.github.forget_the_bright.tsdb.common.config.SecurityConfig;
import io.github.forget_the_bright.tsdb.common.config.TimeoutConfig;
import io.github.forget_the_bright.tsdb.common.config.TsdbInstanceConfig;
import io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException;
import io.github.forget_the_bright.tsdb.common.model.Aggregation;
import io.github.forget_the_bright.tsdb.common.model.Capability;
import io.github.forget_the_bright.tsdb.common.model.DeltaSeries;
import io.github.forget_the_bright.tsdb.common.model.Direction;
import io.github.forget_the_bright.tsdb.common.model.FillPolicy;
import io.github.forget_the_bright.tsdb.common.model.HealthStatus;
import io.github.forget_the_bright.tsdb.common.model.HistoryQuery;
import io.github.forget_the_bright.tsdb.common.model.QueryMode;
import io.github.forget_the_bright.tsdb.common.model.Sample;
import io.github.forget_the_bright.tsdb.common.model.TimeGranularity;
import io.github.forget_the_bright.tsdb.common.model.TagInfo;
import io.github.forget_the_bright.tsdb.common.model.TagQuery;
import io.github.forget_the_bright.tsdb.common.model.TagSample;
import io.github.forget_the_bright.tsdb.common.model.TagSeries;
import io.github.forget_the_bright.tsdb.hls.HlsClient;
import io.github.forget_the_bright.tsdb.hls.HlsConfig;
import io.github.forget_the_bright.tsdb.hls.internal.HlsNativeApi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 和利时 HLS 生产环境只读集成测试。
 *
 * <p>本测试直接访问真实 HLS 服务，和 {@code HlsClientTest} 的本地 Mock 测试互补。
 * 默认<strong>不执行</strong>；只有显式传入 {@code -Dhls.it=true} 才运行。缺少地址、
 * 凭据或现场没有任何有效点位时，用 JUnit {@link Assumptions} 跳过，而不是让常规
 * {@code mvn test} 失败。</p>
 *
 * <p><strong>只读约束：</strong>HLS 驱动本身只提供标签目录、当前值和区间聚合历史，
 * 本测试不会也无力调用写入接口。生产账号仍应只授予读权限。</p>
 *
 * <p><strong>HLS 与 GE 的差异：</strong>HLS 没有原生原始点、插值、趋势、写入、告警、
 * 采集器、系统管理等接口。{@code RAW} 不是厂商接口，而是按现场生产同款方案用
 * 历史值接口的 1 秒 {@code BOUND} 桶近似，本测试据此校验结果属性与样本完整性；
 * 插值/趋势类用例仍然不做。整体覆盖 DDB 当前值、HDB 区间聚合（AVG/MIN/MAX/BOUND）、
 * RAW 近似、整窗增量、累计量差分以及厂商原生 JSON 接口。</p>
 *
 * <p>凭据优先级：系统属性 {@code hls.it.*} → 环境变量 {@code HLS_IT_*} →
 * 当前目录或 {@code tsdb-sdk-hls/} 下的 {@code hls-it.properties}。示例见
 * {@code tsdb-sdk-hls/hls-it.properties.example}。</p>
 *
 * <p>运行示例（PowerShell）：</p>
 * <pre>{@code
 * mvn -q -pl tsdb-sdk-hls -am test `
 *   "-Dtest=HlsProductionIT" "-Dsurefire.failIfNoSpecifiedTests=false" `
 *   "-Dhls.it=true"
 * }</pre>
 *
 * @author wanghao
 * @since 0.1.0
 */
@Tag("hls-it")
class HlsProductionIT {

    /**
     * 用户提供的矿山现场点位，默认全部请求；不存在的点位会以 null Sample 返回。
     */
    private static final String[] DEFAULT_POINTS = new String[]{
            "E101FIT1001_DACA_PV",
            "E101FIT2001_DACA_PV",
            "E101FIT3001_DACA_PV",
            "E131FIT1003_DACA_PV",
            "E131FIT2003_DACA_PV",
            "E131FIT3003_DACA_PV",
            "E102FIT1013_DACA_PV",
            "E102FIT2013_DACA_PV",
            "E102FIT1016_lastvalue_pv",
            "E102FIT1017_lastvalue_pv",
            "E102FIT2016_lastvalue_pv",
            "E102FIT2017_lastvalue_pv",
            "E102FIT3016_lastvalue_pv",
            "E102FIT3017_lastvalue_pv",
            "E405FIT0007_lastvalue_pv",
            "E405FIT0008_lastvalue_pv"
    };

    private static TsdbClient client;
    private static HlsNativeApi nativeApi;
    private static List<String> points;
    private static String tag;
    private static Instant anchor;
    private static long historyHours;
    private static long intervalSeconds;

    @BeforeAll
    static void connect() throws IOException {
        Assumptions.assumeTrue(enabled(),
                "HLS production IT is disabled; pass -Dhls.it=true to enable it");

        Properties file = loadProperties();
        String baseUrl = value(file, "baseUrl", "HLS_IT_BASE_URL", null);
        String userId = value(file, "userId", "HLS_IT_USER_ID", null);
        String secretKey = value(file, "secretKey", "HLS_IT_SECRET_KEY", null);
        String token = value(file, "token", "HLS_IT_TOKEN", null);
        Assumptions.assumeTrue(!blank(baseUrl),
                "HLS production base URL is missing; set hls.it.baseUrl or HLS_IT_BASE_URL");
        Assumptions.assumeTrue(!blank(token) || (!blank(userId) && !blank(secretKey)),
                "HLS production credentials are missing; set hls.it.userId/secretKey or hls.it.token");

        TsdbInstanceConfig instance = new TsdbInstanceConfig();
        instance.setType("hls");
        instance.setBaseUrl(baseUrl);

        AuthConfig auth = instance.getAuth();
        auth.setToken(emptyToNull(token));
        auth.setUserId(emptyToNull(userId));
        auth.setSecretKey(emptyToNull(secretKey));

        TimeoutConfig timeout = instance.getTimeout();
        // 现场配置里的 -1 表示“不限制”，但 SDK 为保证可诊断性要求正数超时；
        // 集成测试默认 5s 连接 / 30s 读取，现场可再调大。
        timeout.setConnectMillis(longValue(file, "connectTimeoutMs", "HLS_IT_CONNECT_TIMEOUT_MS", 5000L));
        // 全量标签目录在生产环境可能返回数万行，30s 会超时；集成测试单独放宽到 180s。
        timeout.setReadMillis(longValue(file, "readTimeoutMs", "HLS_IT_READ_TIMEOUT_MS", 180000L));
        instance.getOptions().put("token-expire-seconds",
                value(file, "tokenExpireSeconds", "HLS_IT_TOKEN_EXPIRE_SECONDS", "43199"));
        instance.getRetry().setMaxAttempts(intValue(file, "maxAttempts", "HLS_IT_MAX_ATTEMPTS", 1));
        instance.getRetry().setBackoffMillis(longValue(file, "retryBackoffMs", "HLS_IT_RETRY_BACKOFF_MS", 200L));

        SecurityConfig security = new SecurityConfig();
        security.setAllowWrite(false);
        // 现场是 http://172.17.1.16:9000，必须显式允许明文；生产长期方案应加 TLS 代理。
        security.setAllowInsecureTransport(
                booleanValue(file, "allowInsecureTransport", "HLS_IT_ALLOW_INSECURE", true));
        security.setVerifyHostname(booleanValue(file, "verifyHostname", "HLS_IT_VERIFY_HOSTNAME", true));

        // HLS 返回 epoch 数字，source-zone 不参与数值解析；target/pattern 只影响
        // Sample.timestampText 的展示。现场按国内习惯配置 GMT+8。
        instance.getOptions().put("time.response.enabled",
                value(file, "responseEnabled", "HLS_IT_RESPONSE_ENABLED", "true"));
        instance.getOptions().put("time.response.source-zone",
                value(file, "responseSourceZone", "HLS_IT_RESPONSE_SOURCE_ZONE", "GMT+8"));
        instance.getOptions().put("time.response.target-zone",
                value(file, "responseTargetZone", "HLS_IT_RESPONSE_TARGET_ZONE", "GMT+8"));
        instance.getOptions().put("time.response.pattern",
                value(file, "responsePattern", "HLS_IT_RESPONSE_PATTERN", "yyyy-MM-dd HH:mm:ss"));

        historyHours = longValue(file, "historyHours", "HLS_IT_HISTORY_HOURS", 24L);
        intervalSeconds = longValue(file, "intervalSeconds", "HLS_IT_INTERVAL_SECONDS", 3600L);
        points = splitPoints(value(file, "points", "HLS_IT_POINTS", String.join(",", DEFAULT_POINTS)));

        HlsConfig config = new HlsConfig("hls-production-it", instance, security);
        client = new HlsClient("hls-production-it", config);
        nativeApi = client.unwrap(HlsNativeApi.class);

        HealthStatus health = client.health();
        assertNotNull(health, "health status must not be null");
        assertTrue(health.getState() != HealthStatus.State.DOWN,
                "HLS production health is DOWN: " + health.getMessage());
        assertNotNull(health.getCheckedAtDate(), "health checkedAtDate must not be null");
        assertNotNull(health.getCheckedAtText(), "health checkedAtText must not be null");

        anchor = Instant.now();
        List<TagSample> current = client.currentValues().read(points);
        for (TagSample value : current) {
            if (value.getSample() != null && value.getSample().getTimestamp() != null) {
                tag = value.getTagName();
                anchor = value.getSample().getTimestamp();
                break;
            }
        }
        if (tag == null) {
            tag = points.get(0);
        }

        System.out.println("HLS production IT: baseUrl=" + baseUrl
                + ", points=" + points.size()
                + ", first tag with value=" + tag
                + ", anchor=" + anchor);
    }

    @AfterAll
    static void close() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void healthAndCapabilities() {
        assertNotNull(client);
        assertTrue(client.capabilities().supports(Capability.TAG_BROWSE));
        assertTrue(client.capabilities().supports(Capability.CURRENT_VALUE));
        assertTrue(client.capabilities().supports(Capability.AGGREGATED_HISTORY));
        assertTrue(client.capabilities().supports(Capability.RAW_HISTORY));
        assertTrue(client.capabilities().supports(Capability.SAMPLED_HISTORY));
        assertTrue(client.capabilities().supports(Capability.CUMULATIVE_DELTA));
        assertFalse(client.capabilities().supports(Capability.INTERPOLATED_HISTORY));
        assertFalse(client.writeSession().isWritable());
        assertThrows(TsdbUnsupportedException.class,
                () -> client.writeSession().write(Collections.emptyList()));
    }

    @Test
    void browsesRealTagCatalog() {
        List<TagInfo> tags = client.tagCatalog().listTags(TagQuery.builder().limit(5).build());
        assertFalse(tags.isEmpty(), "HLS tag catalog returned no rows");
        TagInfo info = client.tagCatalog().getTag(tags.get(0).getTagName());
        assertNotNull(info, "getTag returned null for a tag from listTags");
        assertEquals(tags.get(0).getTagName(), info.getTagName());
        System.out.println("HLS tag catalog sample=" + tags.get(0).getTagName()
                + ", description=" + tags.get(0).getDescription());
    }

    @Test
    void readsConfiguredPointsCurrentValues() {
        List<TagSample> values = client.currentValues().read(points);
        assertEquals(points.size(), values.size(), "result must follow the requested point order");

        int withValue = 0;
        for (TagSample value : values) {
            if (value.getSample() == null) {
                System.out.println("HLS point " + value.getTagName() + ": no value");
                continue;
            }
            withValue++;
            assertNotNull(value.getSample().getTimestamp(), "timestamp missing for " + value.getTagName());
            assertNotNull(value.getSample().getTimestampDate(), "timestampDate missing for " + value.getTagName());
            assertNotNull(value.getSample().getTimestampText(), "timestampText missing for " + value.getTagName());
            System.out.println("HLS point " + value.getTagName()
                    + ": value=" + value.getSample().getDoubleValue()
                    + ", quality=" + value.getSample().getQuality()
                    + ", vendorQuality=" + value.getSample().getVendorQualityCode()
                    + ", time=" + value.getSample().getTimestampText());
        }
        assertTrue(withValue > 0,
                "none of the configured HLS points returned a current value: " + points);
    }

    @Test
    void readsAggregatedHistoryForFirstPointWithValue() {
        Assumptions.assumeTrue(tag != null, "no HLS point available");
        Instant historyEnd = anchor.minusSeconds(1);
        List<TagSeries> series = client.history().read(HistoryQuery.builder()
                .tags(tag)
                .range(historyEnd.minus(Duration.ofHours(historyHours)), historyEnd)
                .mode(QueryMode.AGGREGATED)
                .aggregation(Aggregation.AVG)
                .interval(Duration.ofSeconds(intervalSeconds))
                .maxPoints(256)
                .build());
        assertEquals(1, series.size());
        assertNotNull(series.get(0).getSamples());
        assertFalse(series.get(0).getSamples().isEmpty(),
                "no AVG history for " + tag + " in the last " + historyHours + "h");
        System.out.println("HLS AVG history " + tag + ": samples=" + series.get(0).getSamples().size()
                + ", first=" + series.get(0).getSamples().get(0).getTimestampText());
    }

    @Test
    void readsAllFourAggregationColumns() {
        Assumptions.assumeTrue(tag != null, "no HLS point available");
        Instant historyEnd = anchor.minusSeconds(1);
        for (Aggregation aggregation : Arrays.asList(
                Aggregation.AVG, Aggregation.MIN, Aggregation.MAX, Aggregation.BOUND)) {
            List<TagSeries> series = client.history().read(HistoryQuery.builder()
                    .tags(tag)
                    .range(historyEnd.minus(Duration.ofHours(historyHours)), historyEnd)
                    .mode(QueryMode.AGGREGATED)
                    .aggregation(aggregation)
                    .interval(Duration.ofSeconds(intervalSeconds))
                    .maxPoints(256)
                    .build());
            assertEquals(1, series.size());
            assertNotNull(series.get(0).getSamples());
            System.out.println("HLS " + aggregation + " history " + tag
                    + ": samples=" + series.get(0).getSamples().size());
            if (aggregation == Aggregation.AVG) {
                assertEquals("hls_not_provided",
                        series.get(0).getAttributes().get("qualitySource"),
                        "HLS has no Quality_AVG; AVG quality must be marked as not provided");
            }
        }
    }

    @Test
    void readsSampledHistoryAndCumulativeDelta() {
        Assumptions.assumeTrue(tag != null, "no HLS point available");
        Assumptions.assumeTrue(Boolean.parseBoolean(System.getProperty("hls.it.delta", "false")),
                "HLS delta issues several history requests; pass -Dhls.it.delta=true to include it");
        Instant historyEnd = anchor.minusSeconds(1);
        List<TagSeries> sampled = client.history().read(HistoryQuery.builder()
                .tags(tag)
                .range(historyEnd.minus(Duration.ofHours(historyHours)), historyEnd)
                .mode(QueryMode.SAMPLED)
                .interval(Duration.ofSeconds(intervalSeconds))
                .maxPoints(256)
                .build());
        assertEquals(1, sampled.size());
        assertFalse(sampled.get(0).getSamples().isEmpty(), "sampled history returned no samples");

        List<DeltaSeries> deltas = client.deltaHistory().read(Collections.singletonList(tag),
                historyEnd.minus(Duration.ofHours(historyHours)), historyEnd,
                TimeGranularity.HOUR, Aggregation.LAST, FillPolicy.NONE, Direction.FORWARD, 256);
        assertEquals(1, deltas.size());
        assertEquals(tag, deltas.get(0).getTagName());
        assertFalse(deltas.get(0).getPoints().isEmpty(), "cumulative delta returned no points");

        List<DeltaSeries> fiveMinute = client.deltaHistory().readByInterval(
                Collections.singletonList(tag),
                historyEnd.minus(Duration.ofHours(1)), historyEnd,
                300_000L, Aggregation.LAST, FillPolicy.NONE, Direction.FORWARD, 64);
        assertEquals(1, fiveMinute.size());
        assertFalse(fiveMinute.get(0).getPoints().isEmpty(),
                "arbitrary-interval cumulative delta returned no points");
        System.out.println("HLS 5m delta " + tag + ": points=" + fiveMinute.get(0).getPoints().size());
    }

    @Test
    void readsRawHistoryThroughOneSecondBoundBuckets() {
        Assumptions.assumeTrue(tag != null, "no HLS point available");
        Instant historyEnd = anchor.minusSeconds(1);
        List<TagSeries> series = client.history().read(HistoryQuery.builder()
                .tags(tag)
                .range(historyEnd.minus(Duration.ofMinutes(10)), historyEnd)
                .mode(QueryMode.RAW)
                .maxPoints(300)
                .build());
        assertEquals(1, series.size());
        TagSeries raw = series.get(0);
        assertFalse(raw.getSamples().isEmpty(), "RAW history returned no samples for " + tag);
        assertEquals("hls_history_1s_bound", raw.getAttributes().get("rawSource"),
                "RAW history must declare its BOUND-bucket source");
        assertEquals("true", raw.getAttributes().get("rawApproximate"),
                "RAW history must be marked as approximate");
        for (Sample sample : raw.getSamples()) {
            assertNotNull(sample.getTimestamp(), "RAW sample timestamp missing for " + tag);
            assertNotNull(sample.getTimestampText(), "RAW sample timestampText missing for " + tag);
            assertTrue(sample.getDoubleValue() != null || sample.getTextValue() != null,
                    "RAW sample has neither numeric nor text value for " + tag);
        }
        System.out.println("HLS RAW history " + tag + ": samples=" + raw.getSamples().size()
                + ", first=" + raw.getSamples().get(0).getTimestampText()
                + ", last=" + raw.getSamples().get(raw.getSamples().size() - 1).getTimestampText());
    }

    @Test
    void readsIntervalSumFromBoundedEdges() {
        Assumptions.assumeTrue(tag != null, "no HLS point available");
        Instant historyEnd = anchor.minusSeconds(1);
        Instant historyStart = historyEnd.minus(Duration.ofHours(historyHours));
        List<TagSeries> series = client.history().read(HistoryQuery.builder()
                .tags(tag)
                .range(historyStart, historyEnd)
                .mode(QueryMode.AGGREGATED)
                .aggregation(Aggregation.INTERVAL_SUM)
                .build());
        assertEquals(1, series.size());
        if (series.get(0).getSamples().isEmpty()) {
            Assumptions.assumeTrue(false,
                    "no bounded edge value in the first/last minute of the window for " + tag);
        }
        assertEquals(1, series.get(0).getSamples().size());
        assertEquals(historyStart.truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                series.get(0).getSamples().get(0).getTimestamp());
        Double value = series.get(0).getSamples().get(0).getDoubleValue();
        assertNotNull(value, "interval sum value must be numeric for " + tag);
        assertTrue(Double.isFinite(value), "interval sum value must be finite for " + tag);
        System.out.println("HLS INTERVAL_SUM " + tag + ": last " + historyHours + "h sum=" + value);
    }

    @Test
    void invokesNativeHlsEndpoints() {
        JSONObject tags = nativeApi.queryAllTags();
        assertNotNull(tags);
        assertTrue(field(field(tags, "Data"), "TagNameList") instanceof JSONArray,
                "native queryAllTags did not return Data.TagNameList");

        JSONObject ddb = nativeApi.getDDBTagValue(points);
        assertNotNull(ddb);
        assertTrue(field(field(ddb, "Data"), "DDBTagValueList") instanceof JSONArray,
                "native getDDBTagValue did not return Data.DDBTagValueList");

        JSONObject hdb = nativeApi.getHDBTagValueAll(
                Instant.now().minus(Duration.ofHours(historyHours)).minusSeconds(5),
                Instant.now().minusSeconds(5),
                intervalSeconds, tag);
        assertNotNull(hdb);
        assertTrue(field(field(hdb, "Data"), "HDBTagValueList") instanceof JSONArray,
                "native getHDBTagValueAll did not return Data.HDBTagValueList");
        Object hdbListValue = field(field(hdb, "Data"), "HDBTagValueList");
        if (hdbListValue instanceof JSONArray && !((JSONArray) hdbListValue).isEmpty()) {
            Object valuesValue = field(((JSONArray) hdbListValue).get(0), "OneTagHDBValueList");
            if (valuesValue instanceof JSONArray && !((JSONArray) valuesValue).isEmpty()) {
                Object first = ((JSONArray) valuesValue).get(0);
                System.out.println("HLS BOUND probe: bound=" + field(first, "TagValue_Bound")
                        + ", avg=" + field(first, "TagValue_AVG")
                        + ", min=" + field(first, "TagValue_MIN")
                        + ", max=" + field(first, "TagValue_MAX")
                        + ", time=" + field(first, "TagValueTime"));
            }
        }

        Object raw = nativeApi.rawRequest("GET", "/ddb/read_alltagname", null);
        assertNotNull(raw);
        assertTrue(field(field(raw, "Data"), "TagNameList") instanceof JSONArray,
                "native rawRequest did not return Data.TagNameList");
    }

    private static Object field(Object parent, String name) {
        if (!(parent instanceof java.util.Map)) {
            return null;
        }
        return io.github.forget_the_bright.tsdb.common.internal.json.JsonText.valueIgnoreCase(parent, name);
    }

    @Test
    void unwrapsNativeApi() {
        HlsNativeApi unwrapped = client.unwrap(HlsNativeApi.class);
        assertNotNull(unwrapped);
        assertTrue(unwrapped == nativeApi);
    }

    private static boolean enabled() {
        String property = System.getProperty("hls.it");
        if (property == null || property.trim().isEmpty()) {
            property = System.getenv("HLS_IT");
        }
        return property != null && Boolean.parseBoolean(property.trim());
    }

    private static Properties loadProperties() throws IOException {
        Properties properties = new Properties();
        Path configured = pathProperty("hls.it.config", "HLS_IT_CONFIG");
        if (configured != null && Files.isRegularFile(configured)) {
            load(properties, configured);
            return properties;
        }
        Path[] candidates = new Path[]{
                Paths.get("hls-it.properties"),
                Paths.get("tsdb-sdk-hls", "hls-it.properties"),
                Paths.get(System.getProperty("user.home", "."), "hls-it.properties")
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                load(properties, candidate);
                break;
            }
        }
        return properties;
    }

    private static void load(Properties properties, Path path) throws IOException {
        try (Reader reader = new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
    }

    private static Path pathProperty(String property, String environment) {
        String value = System.getProperty(property);
        if (blank(value)) {
            value = System.getenv(environment);
        }
        return blank(value) ? null : Paths.get(value);
    }

    private static String value(Properties file, String key, String environment, String fallback) {
        String value = System.getProperty("hls.it." + key);
        if (blank(value)) {
            value = System.getenv(environment);
        }
        if (blank(value)) {
            value = file.getProperty(key);
        }
        return blank(value) ? fallback : value.trim();
    }

    private static int intValue(Properties file, String key, String environment, int fallback) {
        return Integer.parseInt(value(file, key, environment, Integer.toString(fallback)));
    }

    private static long longValue(Properties file, String key, String environment, long fallback) {
        return Long.parseLong(value(file, key, environment, Long.toString(fallback)));
    }

    private static boolean booleanValue(Properties file, String key, String environment, boolean fallback) {
        return Boolean.parseBoolean(value(file, key, environment, Boolean.toString(fallback)));
    }

    private static List<String> splitPoints(String raw) {
        List<String> result = new ArrayList<String>();
        if (raw == null) {
            return result;
        }
        for (String part : raw.split("[,;\\s]+")) {
            String point = part.trim();
            if (!point.isEmpty()) {
                result.add(point);
            }
        }
        return result;
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String emptyToNull(String value) {
        return blank(value) ? null : value.trim();
    }
}
