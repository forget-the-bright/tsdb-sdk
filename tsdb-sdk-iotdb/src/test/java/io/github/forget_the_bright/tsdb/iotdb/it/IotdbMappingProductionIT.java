package io.github.forget_the_bright.tsdb.iotdb.it;

import io.github.forget_the_bright.tsdb.common.api.DeltaReader;
import io.github.forget_the_bright.tsdb.common.config.SecurityConfig;
import io.github.forget_the_bright.tsdb.common.config.TsdbConfig;
import io.github.forget_the_bright.tsdb.common.config.TsdbInstanceConfig;
import io.github.forget_the_bright.tsdb.common.model.Aggregation;
import io.github.forget_the_bright.tsdb.common.model.DeltaPoint;
import io.github.forget_the_bright.tsdb.common.model.DeltaSeries;
import io.github.forget_the_bright.tsdb.common.model.Direction;
import io.github.forget_the_bright.tsdb.common.model.FillPolicy;
import io.github.forget_the_bright.tsdb.common.model.HistoryQuery;
import io.github.forget_the_bright.tsdb.common.model.QueryMode;
import io.github.forget_the_bright.tsdb.common.model.Sample;
import io.github.forget_the_bright.tsdb.common.model.TagSample;
import io.github.forget_the_bright.tsdb.common.model.TagSeries;
import io.github.forget_the_bright.tsdb.common.model.TimeGranularity;
import io.github.forget_the_bright.tsdb.common.spi.DriverContext;
import io.github.forget_the_bright.tsdb.iotdb.IotdbClient;
import io.github.forget_the_bright.tsdb.iotdb.IotdbConfig;
import io.github.forget_the_bright.tsdb.iotdb.IotdbDriver;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用生产现场 IoTDB（连接信息见 {@link IotdbItConfig}）上的 root.sdk_it 测试库验证
 * “相对位号 + 默认数据库”、等间隔与日粒度差分。
 *
 * <p><b>窗口不取“现在往前推 N 天”</b>：测试库的合成数据是一次性写入的固定区间
 * （每小时 +12.5），一旦按当前时间取窗，几天后整个窗口就落在数据内部，
 * “首桶无基线”这类断言会随时间自然失效。这里改为先探测该序列自身的首末样本时刻，
 * 再把窗口锚到首样本所在的日界上，断言与数据的绝对龄期无关。</p>
 *
 * <p>日界按 {@code options.zone}（缺省 Asia/Shanghai）对齐，因此桶起点落在
 * UTC 16:00 而非 00:00 —— 本用例同时用 {@code zone=UTC} 再读一遍，
 * 证明对齐真的由该配置决定。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
@Tag("iotdb-it")
@EnabledIfSystemProperty(named = "iotdb.it", matches = "true")
class IotdbMappingProductionIT {

    private static final String TAG = "device01.flow";

    /** IT 专用库：相对位号的默认库（测试夹具，不是连接地址）。 */
    private static final String SDK_IT_DATABASE = "root.sdk_it";

    /** 每小时恒定 +12.5，所以任一完整日桶的增量都是 300。 */
    private static final double DAILY_DELTA = 300.0;

    @ParameterizedTest
    @ValueSource(strings = {"rest", "native", "jdbc"})
    void resolvesRelativeTagsAndReadsDailyDelta(String transport) {
        IotdbClient client = client(transport, null);
        IotdbClient utcClient = client(transport, "UTC");
        try {
            List<TagSample> current = client.currentValues().read(Collections.singletonList(TAG));
            assertEquals(1, current.size());
            assertNotNull(current.get(0).getSample(), "device01.flow should have a current value");

            Instant first = edgeTimestamp(client, Direction.FORWARD);
            Instant last = edgeTimestamp(client, Direction.BACKWARD);
            Instant businessDay = floorToZone(first, IotdbConfig.DEFAULT_ZONE);
            Instant utcDay = first.truncatedTo(ChronoUnit.DAYS);
            // 七格窗口：首格必然没有基线，其余六格都是完整的一天
            Instant windowEnd = businessDay.plus(7, ChronoUnit.DAYS);
            assertTrue(ChronoUnit.DAYS.between(businessDay, last) >= 7,
                    "test series must cover the whole seven-day window, got "
                            + ChronoUnit.DAYS.between(businessDay, last) + " days");

            List<TagSeries> sampled = client.history().read(HistoryQuery.builder()
                    .tags(TAG)
                    .range(businessDay, windowEnd)
                    .mode(QueryMode.SAMPLED)
                    .aggregation(Aggregation.LAST)
                    .interval(Duration.ofDays(1))
                    .fill(FillPolicy.PREVIOUS)
                    .maxPoints(10)
                    .build());
            assertEquals(1, sampled.size());
            List<Sample> buckets = sampled.get(0).getSamples();
            assertEquals(7, buckets.size(), "seven daily buckets");
            for (int i = 0; i < buckets.size(); i++) {
                assertEquals(businessDay.plus(i, ChronoUnit.DAYS), buckets.get(i).getTimestamp(),
                        "sampled window keeps the caller's start, bucket " + i);
            }

            List<DeltaPoint> points = deltas(client, first, windowEnd);
            assertEquals(7, points.size(), "seven daily delta buckets");
            // 首桶起点即业务时区的日界，早于测试数据的首个样本，因此必然没有基线
            assertEquals(businessDay, points.get(0).getTimestamp());
            assertTrue(points.get(0).isMissing(), "the first bucket has no baseline and must be marked missing");
            for (int i = 1; i < points.size(); i++) {
                assertEquals(businessDay.plus(i, ChronoUnit.DAYS), points.get(i).getTimestamp(),
                        "bucket " + i + " must start on the Asia/Shanghai day boundary");
                assertFalse(points.get(i).isMissing(), "bucket " + i + " should have a delta");
                assertEquals(DAILY_DELTA, points.get(i).getDelta(), 0.001,
                        "hourly flow is constant, so each full day's delta is 300");
            }

            // 同一个原始起点，只换 options.zone，日界就整体平移 8 小时：
            // 证明对齐读的是业务时区，而不是展示时区或写死的 UTC。
            List<DeltaPoint> utcPoints = deltas(utcClient, first, windowEnd);
            assertEquals(utcDay, utcPoints.get(0).getTimestamp());
            assertEquals(8L, Duration.between(businessDay, utcDay).toHours(),
                    "Asia/Shanghai day start must sit 8h before the UTC one");
        } finally {
            utcClient.close();
            client.close();
        }
    }

    private static List<DeltaPoint> deltas(IotdbClient client, Instant start, Instant end) {
        DeltaReader reader = client.deltaHistory();
        List<DeltaSeries> series = reader.read(Collections.singletonList(TAG), start, end,
                TimeGranularity.DAY, Aggregation.LAST,
                FillPolicy.PREVIOUS, Direction.FORWARD, 10);
        assertEquals(1, series.size());
        return series.get(0).getPoints();
    }

    /**
     * 取序列自身的首点或末点时刻。
     *
     * <p>用 RAW + 单点 + 指定方向，而不是对“现在”做任何假设；数据没播种时这里
     * 直接失败并给出原因，比后续一堆错位断言更好读。</p>
     *
     * @param client    已连通的 IoTDB 客户端
     * @param direction {@code FORWARD} 取首点，{@code BACKWARD} 取末点
     * @return 边缘样本的绝对时刻
     */
    private static Instant edgeTimestamp(IotdbClient client, Direction direction) {
        List<TagSeries> series = client.history().read(HistoryQuery.builder()
                .tags(TAG)
                .range(Instant.EPOCH, Instant.now().plus(1, ChronoUnit.DAYS))
                .mode(QueryMode.RAW)
                .direction(direction)
                .maxPoints(1)
                .build());
        assertFalse(series.get(0).getSamples().isEmpty(),
                "root.sdk_it.device01.flow has no data — the IT database was never seeded");
        return series.get(0).getSamples().get(0).getTimestamp();
    }

    private static Instant floorToZone(Instant instant, String zone) {
        return instant.atZone(ZoneId.of(zone)).truncatedTo(ChronoUnit.DAYS).toInstant();
    }

    private static IotdbClient client(String transport, String zone) {
        TsdbInstanceConfig instance = new TsdbInstanceConfig();
        instance.setType("iotdb");
        instance.setBaseUrl(IotdbItConfig.required("baseUrl"));
        instance.getAuth().setUsername(IotdbItConfig.required("username"));
        instance.getAuth().setPassword(IotdbItConfig.required("password"));
        instance.getOptions().put("transport", transport);
        instance.getOptions().put("rpc-port", IotdbItConfig.text("rpcPort", "6667"));
        instance.getOptions().put("default-database", SDK_IT_DATABASE);
        if (zone != null) {
            instance.getOptions().put("zone", zone);
        }

        SecurityConfig security = new SecurityConfig();
        security.setAllowInsecureTransport(true);
        TsdbConfig root = new TsdbConfig();
        root.setSecurity(security);
        DriverContext context = new DriverContext("iotdb_sdk_it", root, null);
        return (IotdbClient) new IotdbDriver().create(instance, context);
    }
}
