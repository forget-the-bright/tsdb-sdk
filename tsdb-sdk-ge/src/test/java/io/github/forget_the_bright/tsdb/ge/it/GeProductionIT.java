package io.github.forget_the_bright.tsdb.ge.it;

import com.alibaba.fastjson.JSONObject;
import io.github.forget_the_bright.tsdb.common.api.TsdbClient;
import io.github.forget_the_bright.tsdb.common.config.AuthConfig;
import io.github.forget_the_bright.tsdb.common.config.SecurityConfig;
import io.github.forget_the_bright.tsdb.common.config.TimeoutConfig;
import io.github.forget_the_bright.tsdb.common.config.TsdbInstanceConfig;
import io.github.forget_the_bright.tsdb.common.exception.TsdbQueryException;
import io.github.forget_the_bright.tsdb.common.internal.json.JsonText;
import io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException;
import io.github.forget_the_bright.tsdb.common.model.Aggregation;
import io.github.forget_the_bright.tsdb.common.model.Capability;
import io.github.forget_the_bright.tsdb.common.model.DeltaSeries;
import io.github.forget_the_bright.tsdb.common.model.Direction;
import io.github.forget_the_bright.tsdb.common.model.FillPolicy;
import io.github.forget_the_bright.tsdb.common.model.HealthStatus;
import io.github.forget_the_bright.tsdb.common.model.HistoryQuery;
import io.github.forget_the_bright.tsdb.common.model.QueryMode;
import io.github.forget_the_bright.tsdb.common.model.TimeGranularity;
import io.github.forget_the_bright.tsdb.common.model.TagInfo;
import io.github.forget_the_bright.tsdb.common.model.TagQuery;
import io.github.forget_the_bright.tsdb.common.model.TagSample;
import io.github.forget_the_bright.tsdb.common.model.TagSeries;
import io.github.forget_the_bright.tsdb.ge.GeClient;
import io.github.forget_the_bright.tsdb.ge.GeConfig;
import io.github.forget_the_bright.tsdb.ge.internal.GeHttpClient;
import io.github.forget_the_bright.tsdb.ge.internal.GeNativeApi;
import io.github.forget_the_bright.tsdb.ge.internal.GeTokenManager;
import io.github.forget_the_bright.tsdb.ge.nativeapi.GeEndpoint;
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
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GE Proficy Historian 生产环境只读集成测试。
 *
 * <p>本测试直接访问真实 Historian，与 {@code GeClientTest} 的本地 Mock 测试互补。
 * 默认<strong>不执行</strong>，只有显式传入 {@code -Dge.it=true} 才运行；缺少凭据或
 * 标签数据时按 JUnit {@link Assumptions} 跳过，而不是让常规 {@code mvn test} 失败。</p>
 *
 * <p><strong>只读约束：</strong>本类只调用查询端点（标签、当前值、原始/聚合/插值、
 * 系统/采集器 GET、告警查询 POST），绝不调用 create/delete/rename/配置修改/采集器
 * 启停/告警备份恢复。生产环境账号也应只授予只读权限。</p>
 *
 * <p>凭据优先级：系统属性 {@code ge.it.*} → 环境变量 {@code GE_IT_*} →
 * 当前目录或 {@code tsdb-sdk-ge/} 下的 {@code ge-it.properties}。示例见
 * {@code tsdb-sdk-ge/ge-it.properties.example}。</p>
 *
 * <p>运行示例：</p>
 * <pre>{@code
 * mvn -q -pl tsdb-sdk-ge -am test \
 *   "-Dtest=GeProductionIT" "-Dsurefire.failIfNoSpecifiedTests=false" \
 *   "-Dge.it=true"
 * }</pre>
 *
 * @author wanghao
 * @since 0.1.0
 */
@Tag("ge-it")
class GeProductionIT {

    private static final DateTimeFormatter UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private static TsdbClient client;
    private static GeNativeApi nativeApi;
    private static String tag;
    private static Instant anchor;

    @BeforeAll
    static void connect() throws IOException {
        Assumptions.assumeTrue(enabled(),
                "GE production IT is disabled; pass -Dge.it=true to enable it");

        Properties file = loadProperties();
        String baseUrl = value(file, "baseUrl", "GE_IT_BASE_URL", "https://172.16.176.9");
        String username = value(file, "username", "GE_IT_USERNAME", null);
        String password = value(file, "password", "GE_IT_PASSWORD", null);
        String clientId = value(file, "clientId", "GE_IT_CLIENT_ID", null);
        String clientSecret = value(file, "clientSecret", "GE_IT_CLIENT_SECRET", null);
        String token = value(file, "token", "GE_IT_TOKEN", null);

        boolean hasToken = !blank(token);
        boolean hasPassword = !blank(username) && !blank(password)
                && !blank(clientId) && !blank(clientSecret);
        Assumptions.assumeTrue(hasToken || hasPassword,
                "GE production credentials are missing; set ge.it.username/password/"
                        + "clientId/clientSecret or ge.it.token");

        TsdbInstanceConfig instance = new TsdbInstanceConfig();
        instance.setType("ge");
        instance.setBaseUrl(baseUrl);
        AuthConfig auth = instance.getAuth();
        auth.setToken(emptyToNull(token));
        auth.setUsername(emptyToNull(username));
        auth.setPassword(emptyToNull(password));
        auth.setClientId(emptyToNull(clientId));
        auth.setClientSecret(emptyToNull(clientSecret));

        TimeoutConfig timeout = instance.getTimeout();
        timeout.setConnectMillis(longValue(file, "connectTimeoutMs", "GE_IT_CONNECT_TIMEOUT_MS", 5000L));
        timeout.setReadMillis(longValue(file, "readTimeoutMs", "GE_IT_READ_TIMEOUT_MS", 20000L));

        // 现场证书为自签名且 CN 常为机器名，必须显式关闭主机名校验；
        // trust-all 默认打开，仅用于本次联调，生产长期接入应导入现场根证书。
        SecurityConfig security = new SecurityConfig();
        security.setAllowWrite(false);
        security.setVerifyHostname(booleanValue(file, "verifyHostname", "GE_IT_VERIFY_HOSTNAME", false));
        instance.getOptions().put("trust-all-certificates",
                Boolean.toString(booleanValue(file, "trustAll", "GE_IT_TRUST_ALL", true)));

        // 时间格式按现场配置：铜陵 GE 请求/响应都是 UTC，界面展示 GMT+8。
        // 如果某个项目 GE 本身用 GMT+8，只需把 requestZone/responseSourceZone 改成 GMT+8。
        instance.getOptions().put("time.request.enabled",
                value(file, "requestEnabled", "GE_IT_REQUEST_ENABLED", "true"));
        instance.getOptions().put("time.request.zone",
                value(file, "requestZone", "GE_IT_REQUEST_ZONE", "UTC"));
        instance.getOptions().put("time.request.pattern",
                value(file, "requestPattern", "GE_IT_REQUEST_PATTERN",
                        "yyyy-MM-dd'T'HH:mm:ssXXX"));
        instance.getOptions().put("time.response.enabled",
                value(file, "responseEnabled", "GE_IT_RESPONSE_ENABLED", "true"));
        instance.getOptions().put("time.response.source-zone",
                value(file, "responseSourceZone", "GE_IT_RESPONSE_SOURCE_ZONE", "UTC"));
        instance.getOptions().put("time.response.target-zone",
                value(file, "responseTargetZone", "GE_IT_RESPONSE_TARGET_ZONE", "GMT+8"));
        instance.getOptions().put("time.response.pattern",
                value(file, "responsePattern", "GE_IT_RESPONSE_PATTERN",
                        "yyyy-MM-dd HH:mm:ss"));

        GeConfig geConfig = new GeConfig("ge-production-it", instance, security);
        GeHttpClient http = new GeHttpClient(geConfig);
        GeTokenManager tokens = new GeTokenManager(geConfig, http);
        nativeApi = new GeNativeApi(geConfig, http, tokens);
        client = new GeClient(geConfig, nativeApi);

        HealthStatus health = client.health();
        assertNotNull(health, "health status must not be null");
        assertTrue(health.getState() != HealthStatus.State.DOWN,
                "GE production health is DOWN: " + health.getMessage());

        List<TagInfo> tags = client.tagCatalog().listTags(
                TagQuery.builder().limit(intValue(file, "maxTags", "GE_IT_MAX_TAGS", 20)).build());
        String configured = value(file, "tag", "GE_IT_TAG", null);
        if (!blank(configured)) {
            tag = configured;
        } else if (!tags.isEmpty()) {
            tag = tags.get(0).getTagName();
        }

        anchor = Instant.now();
        if (tag != null) {
            List<TagSample> current = client.currentValues().read(Collections.singletonList(tag));
            if (!current.isEmpty() && current.get(0).getSample() != null
                    && current.get(0).getSample().getTimestamp() != null) {
                anchor = current.get(0).getSample().getTimestamp();
            }
        }
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
        assertTrue(client.capabilities().supports(Capability.RAW_HISTORY));
        assertTrue(client.capabilities().supports(Capability.AGGREGATED_HISTORY));
        assertTrue(client.capabilities().supports(Capability.INTERPOLATED_HISTORY));
        assertFalse(client.capabilities().supports(Capability.WRITE));
        assertFalse(client.writeSession().isWritable());
        assertThrows(TsdbUnsupportedException.class,
                () -> client.writeSession().write(Collections.emptyList()));
    }

    @Test
    void browsesRealTagCatalog() {
        Assumptions.assumeTrue(tag != null, "no tag available on the production Historian");
        List<TagInfo> tags = client.tagCatalog().listTags(TagQuery.builder().limit(5).build());
        assertFalse(tags.isEmpty(), "tag catalog returned no rows");
        TagInfo info = client.tagCatalog().getTag(tag);
        assertNotNull(info, "getTag returned null for a tag from listTags");
        assertEquals(tag, info.getTagName());
    }

    @Test
    void readsRealCurrentValue() {
        Assumptions.assumeTrue(tag != null, "no tag available on the production Historian");
        List<String> list = Arrays.asList(tag, "ZJ110.ELECTRIC01.ME_17000");
        List<TagSample> values = client.currentValues().read(list);
        assertEquals(list.size(), values.size());
        TagSample value = values.get(0);
        assertEquals(tag, value.getTagName());
        assertNotNull(value.getSample(), "tag " + tag + " has no current value");
        assertNotNull(value.getSample().getTimestamp());
        assertNotNull(value.getSample().getQuality());
    }

    @Test
    void readsRealRawHistory() {
        Assumptions.assumeTrue(tag != null, "no tag available on the production Historian");
        List<TagSeries> series = client.history().read(HistoryQuery.builder()
                .tags(tag)
                .range(anchor.minus(Duration.ofHours(6)), anchor.plus(Duration.ofMinutes(5)))
                .mode(QueryMode.RAW)
                .maxPoints(20)
                .build());
        assertEquals(1, series.size());
        assertEquals(tag, series.get(0).getTagName());
        assertNotNull(series.get(0).getSamples());
        assertFalse(series.get(0).getSamples().isEmpty(), "raw history returned no samples");
        assertTrue(series.get(0).getSamples().size() <= 20);
    }

    @Test
    void readsRealAggregatedHistory() {
        Assumptions.assumeTrue(tag != null, "no tag available on the production Historian");
        List<TagSeries> series = client.history().read(HistoryQuery.builder()
                .tags(tag)
                .range(anchor.minus(Duration.ofHours(48)), anchor.plus(Duration.ofMinutes(5)))
                .mode(QueryMode.AGGREGATED)
                .aggregation(Aggregation.AVG)
                .interval(Duration.ofHours(1))
                .maxPoints(48)
                .build());
        assertEquals(1, series.size());
        assertNotNull(series.get(0).getSamples());
        assertFalse(series.get(0).getSamples().isEmpty(), "aggregated history returned no samples");
        assertTrue(series.get(0).getSamples().size() <= 48);
    }

    @Test
    void readsRealInterpolatedHistory() {
        Assumptions.assumeTrue(tag != null, "no tag available on the production Historian");
        List<TagSeries> series = client.history().read(HistoryQuery.builder()
                .tags(tag)
                .range(anchor.minus(Duration.ofHours(24)), anchor.plus(Duration.ofMinutes(5)))
                .mode(QueryMode.INTERPOLATED)
                .interval(Duration.ofHours(1))
                .maxPoints(24)
                .build());
        assertEquals(1, series.size());
        assertNotNull(series.get(0).getSamples());
        assertFalse(series.get(0).getSamples().isEmpty(), "interpolated history returned no samples");
    }

    @Test
    void readsRealSampledHistoryAndCumulativeDelta() {
        Assumptions.assumeTrue(tag != null, "no tag available on the production Historian");
        List<TagSeries> sampled = client.history().read(HistoryQuery.builder()
                .tags(tag)
                .range(anchor.minus(Duration.ofHours(48)), anchor.plus(Duration.ofMinutes(5)))
                .mode(QueryMode.SAMPLED)
                .aggregation(Aggregation.LAST)
                .interval(Duration.ofHours(1))
                .maxPoints(48)
                .build());
        assertEquals(1, sampled.size());
        assertFalse(sampled.get(0).getSamples().isEmpty(), "sampled history returned no samples");

        List<DeltaSeries> deltas = client.deltaHistory().read(Collections.singletonList(tag),
                anchor.minus(Duration.ofHours(48)), anchor, TimeGranularity.HOUR,
                Aggregation.LAST, FillPolicy.NONE, Direction.FORWARD, 48);
        assertEquals(1, deltas.size());
        assertEquals(tag, deltas.get(0).getTagName());
        assertFalse(deltas.get(0).getPoints().isEmpty(), "cumulative delta returned no points");
    }

    @Test
    void invokesNativeTagAndDataEndpoints() {
        Assumptions.assumeTrue(tag != null, "no tag available on the production Historian");
        Map<String, Object> query = new LinkedHashMap<String, Object>();
        query.put("maxNumber", 5);
        JSONObject tags = JsonText.object(nativeApi.invoke(GeEndpoint.QUERY_TAGS_BY_PARAMS, query));
        assertNotNull(tags);
        assertNotNull(tags.getJSONArray("Tags"));

        JSONObject current = JsonText.object(nativeApi.invoke(GeEndpoint.GET_CURRENT_VALUE_BY_PATH_VARIABLE,
                Collections.<String, Object>singletonMap("tagNames", tag)));
        assertNotNull(current);
        assertTrue(current.getJSONArray("Data") != null || current.getIntValue("ErrorCode") == 0);

        Map<String, Object> path = new LinkedHashMap<String, Object>();
        path.put("tagNames", tag);
        path.put("start", UTC.format(anchor.minus(Duration.ofHours(2))));
        path.put("end", UTC.format(anchor.plus(Duration.ofMinutes(5))));
        path.put("direction", 1);
        path.put("count", 5);
        Object raw = nativeApi.invoke(GeEndpoint.GET_RAW_DATA_BY_PATH_VARIABLE, path);
        assertNotNull(raw);

        Object properties = nativeApi.invoke(GeEndpoint.GET_TAG_PROPERTIES_PATH,
                Collections.<String, Object>singletonMap("tagName", tag));
        assertNotNull(properties);
    }

    @Test
    void invokesNativeReadOnlySystemsCollectorsAndAlarms() {
        Object servers = nativeApi.invoke(GeEndpoint.GET_SERVER_PROPERTIES);
        assertNotNull(servers);
        Object stores = nativeApi.invoke(GeEndpoint.GET_DATA_STORES);
        assertNotNull(stores);
        Object details = nativeApi.invoke(GeEndpoint.COLLECTOR_DETAILS);
        assertNotNull(details);
        Object offline = nativeApi.invoke(GeEndpoint.OFFLINE_COLLECTORS);
        assertNotNull(offline);

        Map<String, Object> alarmQuery = new LinkedHashMap<String, Object>();
        alarmQuery.put("AlarmType", "4");
        alarmQuery.put("StartTime", UTC.format(anchor.minus(Duration.ofHours(1))));
        alarmQuery.put("EndTime", UTC.format(anchor.plus(Duration.ofMinutes(5))));
        alarmQuery.put("MaxRecords", 10);
        try {
            Object alarms = nativeApi.invoke(GeEndpoint.QUERY_ALARMS, alarmQuery);
            assertNotNull(alarms);
        } catch (TsdbQueryException e) {
            // 现场 Historian 未配置 A&E Server 时返回 ErrorCode=-26（A&E Server not connected）。
            // 这属于环境能力缺失，不应让其他只读端点的验证失败。
            Assumptions.assumeTrue(e.getMessage().contains("ErrorCode=-26"),
                    "GE alarm query failed: " + e.getMessage());
        }
    }

    @Test
    void unwrapsNativeApi() {
        GeNativeApi unwrapped = client.unwrap(GeNativeApi.class);
        assertNotNull(unwrapped);
        assertTrue(unwrapped == nativeApi);
    }

    private static boolean enabled() {
        String property = System.getProperty("ge.it");
        if (property == null || property.trim().isEmpty()) {
            property = System.getenv("GE_IT");
        }
        return property != null && Boolean.parseBoolean(property.trim());
    }

    private static Properties loadProperties() throws IOException {
        Properties properties = new Properties();
        Path configured = pathProperty("ge.it.config", "GE_IT_CONFIG");
        if (configured != null && Files.isRegularFile(configured)) {
            load(properties, configured);
            return properties;
        }
        Path[] candidates = new Path[]{
                Paths.get("ge-it.properties"),
                Paths.get("tsdb-sdk-ge", "ge-it.properties"),
                Paths.get(System.getProperty("user.home", "."), "ge-it.properties")
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
        String value = System.getProperty("ge.it." + key);
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

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String emptyToNull(String value) {
        return blank(value) ? null : value.trim();
    }
}
