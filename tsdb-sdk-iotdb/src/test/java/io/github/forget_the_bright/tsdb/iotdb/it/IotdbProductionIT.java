package io.github.forget_the_bright.tsdb.iotdb.it;

import io.github.forget_the_bright.tsdb.common.config.SecurityConfig;
import io.github.forget_the_bright.tsdb.common.config.TsdbConfig;
import io.github.forget_the_bright.tsdb.common.config.TsdbInstanceConfig;
import io.github.forget_the_bright.tsdb.common.model.Aggregation;
import io.github.forget_the_bright.tsdb.common.model.DeltaSeries;
import io.github.forget_the_bright.tsdb.common.model.Direction;
import io.github.forget_the_bright.tsdb.common.model.FillPolicy;
import io.github.forget_the_bright.tsdb.common.model.HistoryQuery;
import io.github.forget_the_bright.tsdb.common.model.QueryMode;
import io.github.forget_the_bright.tsdb.common.model.TagInfo;
import io.github.forget_the_bright.tsdb.common.model.TagQuery;
import io.github.forget_the_bright.tsdb.common.model.TagSample;
import io.github.forget_the_bright.tsdb.common.model.TagSeries;
import io.github.forget_the_bright.tsdb.common.model.TimeGranularity;
import io.github.forget_the_bright.tsdb.common.spi.DriverContext;
import io.github.forget_the_bright.tsdb.iotdb.IotdbClient;
import io.github.forget_the_bright.tsdb.iotdb.IotdbDriver;
import io.github.forget_the_bright.tsdb.iotdb.internal.query.IotdbQueryService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 对真实 IoTDB 2.0.11 的只读集成测试。
 *
 * <p>默认不执行，只有显式传入 {@code -Diotdb.it=true} 时才连接现场库。
 * 地址、账号、测试点位按“系统属性 → 环境变量 → iotdb-it.properties”的
 * 优先级读取，避免把现场凭据写进仓库。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
@Tag("iotdb-it")
@EnabledIfSystemProperty(named = "iotdb.it", matches = "true")
class IotdbProductionIT {

    private static Properties properties;

    @BeforeAll
    static void loadProperties() throws IOException {
        properties = new Properties();
        Path local = Paths.get("iotdb-it.properties");
        if (!Files.exists(local)) {
            local = Paths.get("tsdb-sdk-iotdb", "iotdb-it.properties");
        }
        if (Files.exists(local)) {
            try (InputStream in = Files.newInputStream(local)) {
                properties.load(in);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"rest", "native", "jdbc"})
    void readsLastValueAndTagCatalog(String transport) {
        IotdbClient client = client(transport);
        try {
            String tag = text("tag", "root.factory.device02.temperature");
            List<TagSample> samples = client.currentValues().read(Arrays.asList(tag));
            assertEquals(1, samples.size());
            assertNotNull(samples.get(0).getSample(), "current value must not be null");
            assertTrue(samples.get(0).getSample().getDoubleValue() > -1_000_000);

            List<TagInfo> tags = client.tagCatalog().listTags(
                    TagQuery.builder().nameMask("root.factory.**").limit(20).build());
            assertFalse(tags.isEmpty(), "tag catalog must not be empty");
            System.out.println("transport=" + transport + " tags=" + tags.size()
                    + " current=" + samples.get(0).getSample().getDoubleValue());
        } finally {
            client.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"native"})
    void readsBatchRawAndHourlyHistory(String transport) {
        IotdbClient client = client(transport);
        try {
           // List<String> tagNames = Arrays.asList("root.factory.RL.FCS0107.`TI-1071815_PV`", "root.factory.RL.FCS0107.`TI-1071816_PV`");
           // List<String> tagNames = Arrays.asList("RL.FCS0107.`TI-1071815_PV`", "RL.FCS0107.`TI-1071816_PV`");
            List<String> tagNames = Arrays.asList("RL.FCS0107.TI-1071815_PV", "RL.FCS0107.TI-1071816_PV");

            Instant end = Instant.now();
            Instant start = end.minus(Duration.ofDays(30));

            HistoryQuery raw = HistoryQuery.builder()
                    .tags(tagNames).range(start, end).mode(QueryMode.RAW).maxPoints(100).build();
            List<TagSeries> tagValues = client.history().read(raw);
            TagSeries rawSeries = tagValues.get(0);
            assertFalse(rawSeries.getSamples().isEmpty(), "raw history must not be empty");

            HistoryQuery sampled = HistoryQuery.builder()
                    .tags(tagNames).range(start, end).mode(QueryMode.SAMPLED)
                    .aggregation(Aggregation.LAST)
                    .interval(Duration.ofHours(1))
                    .fill(FillPolicy.PREVIOUS)
                    .maxPoints(200).build();
            TagSeries sampledSeries = client.history().read(sampled).get(0);
            assertFalse(sampledSeries.getSamples().isEmpty(), "hourly history must not be empty");
            System.out.println("transport=" + transport + " raw=" + rawSeries.getSamples().size()
                    + " hourly=" + sampledSeries.getSamples().size());
        } finally {
            client.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"rest", "native", "jdbc"})
    void readsRawAndHourlyHistory(String transport) {
        IotdbClient client = client(transport);
        try {
            String tag = text("tag", "root.factory.device01.temperature");
            Instant end = Instant.now();
            Instant start = end.minus(Duration.ofDays(30));

            HistoryQuery raw = HistoryQuery.builder()
                    .tags(tag).range(start, end).mode(QueryMode.RAW).maxPoints(100).build();
            TagSeries rawSeries = client.history().read(raw).get(0);
            assertFalse(rawSeries.getSamples().isEmpty(), "raw history must not be empty");

            HistoryQuery sampled = HistoryQuery.builder()
                    .tags(tag).range(start, end).mode(QueryMode.SAMPLED)
                    .aggregation(Aggregation.LAST)
                    .interval(Duration.ofHours(1))
                    .fill(FillPolicy.PREVIOUS)
                    .maxPoints(200).build();
            TagSeries sampledSeries = client.history().read(sampled).get(0);
            assertFalse(sampledSeries.getSamples().isEmpty(), "hourly history must not be empty");
            System.out.println("transport=" + transport + " raw=" + rawSeries.getSamples().size()
                    + " hourly=" + sampledSeries.getSamples().size());
        } finally {
            client.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"rest", "native", "jdbc"})
    void calculatesHourlyCumulativeDelta(String transport) {
        IotdbClient client = client(transport);
        try {
            String tag = text("tag", "root.factory.device02.temperature");
            Instant end = Instant.now();
            Instant start = end.minus(Duration.ofDays(7));
            List<DeltaSeries> series = client.deltaHistory().read(Arrays.asList(tag), start, end,
                    TimeGranularity.HOUR, Aggregation.LAST, FillPolicy.PREVIOUS,
                    Direction.FORWARD, 200);
            assertEquals(1, series.size());
            assertFalse(series.get(0).getPoints().isEmpty(), "delta points must not be empty");
            System.out.println("transport=" + transport + " deltas=" + series.get(0).getPoints().size());
        } finally {
            client.close();
        }
    }

    @Test
    void reportsServerVersionThroughNativeService() {
        IotdbClient client = client("rest");
        try {
            IotdbQueryService service = client.unwrap(IotdbQueryService.class);
            assertNotNull(service);
            String version = service.serverVersion();
            assertNotNull(version);
            System.out.println("serverVersion=" + version);
        } finally {
            client.close();
        }
    }

    private static IotdbClient client(String transport) {
        assumeTrue(Boolean.parseBoolean(System.getProperty("iotdb.it", "true")),
                "set -Diotdb.it=true to run against a real IoTDB");
        SecurityConfig security = new SecurityConfig();
        security.setAllowInsecureTransport(true);

        TsdbInstanceConfig instance = new TsdbInstanceConfig();
        instance.setType("iotdb");
        instance.setBaseUrl(text("baseUrl", "http://192.168.3.199:18080"));
        instance.getAuth().setUsername(text("username", "root"));
        instance.getAuth().setPassword(text("password", "root"));
        instance.getOptions().put("transport", transport);
        instance.getOptions().put("rpc-port", text("rpcPort", "6667"));
        instance.getOptions().put("default-database", "root.factory");

        TsdbConfig root = new TsdbConfig();
        root.setSecurity(security);
        DriverContext context = new DriverContext("iotdb_it", root, null);
        return (IotdbClient) new IotdbDriver().create(instance, context);
    }

    private static String text(String key, String fallback) {
        String system = System.getProperty("iotdb.it." + key);
        if (system != null && !system.trim().isEmpty()) {
            return system.trim();
        }
        String env = System.getenv("IOTDB_IT_" + key.replace('.', '_').toUpperCase());
        if (env != null && !env.trim().isEmpty()) {
            return env.trim();
        }
        String value = properties == null ? null : properties.getProperty(key);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
