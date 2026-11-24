package io.github.forget_the_bright.tsdb.pspace.it;

import io.github.forget_the_bright.tsdb.common.model.Aggregation;
import io.github.forget_the_bright.tsdb.common.model.Capability;
import io.github.forget_the_bright.tsdb.common.model.DeltaPoint;
import io.github.forget_the_bright.tsdb.common.model.DeltaSeries;
import io.github.forget_the_bright.tsdb.common.model.Direction;
import io.github.forget_the_bright.tsdb.common.model.FillPolicy;
import io.github.forget_the_bright.tsdb.common.model.HealthStatus;
import io.github.forget_the_bright.tsdb.common.model.HistoryQuery;
import io.github.forget_the_bright.tsdb.common.model.Quality;
import io.github.forget_the_bright.tsdb.common.model.QueryMode;
import io.github.forget_the_bright.tsdb.common.model.Sample;
import io.github.forget_the_bright.tsdb.common.model.TagInfo;
import io.github.forget_the_bright.tsdb.common.model.TagQuery;
import io.github.forget_the_bright.tsdb.common.model.TagSample;
import io.github.forget_the_bright.tsdb.common.model.TagSeries;
import io.github.forget_the_bright.tsdb.pspace.PspaceClient;
import io.github.forget_the_bright.tsdb.pspace.internal.PspaceModels;
import io.github.forget_the_bright.tsdb.pspace.internal.PspaceNativeApi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * pSpace 只读集成测试（默认不执行）。
 *
 * <p>开启方式：{@code mvn -o -pl tsdb-sdk-pspace -Dpspace.it=true test}。
 * 地址优先级：{@code -Dpspace.it.baseUrl} → {@code PSPACE_IT_BASE_URL} →
 * {@code pspace-it.properties} → 模拟环境 {@code http://172.16.176.14:8080}。
 * 本测试<strong>不做任何写入</strong>，只调用 {@code /Refresh}（服务端缓存刷新，
 * 非数据写入）与只读端点。</p>
 *
 * <p>策略：先 {@code /Refresh} 再探测现有点位；探测不到任何可读位号时用
 * {@link Assumptions} 跳过并输出原因。历史类用例共用同一时间窗；窗口内查不到
 * 历史时逐项跳过，并在输出里写明「未执行，等厂商提供历史数据」，绝不伪造结果。</p>
 *
 * @author wanghao
 * @since 0.1.2
 */
@Tag("pspace-it")
class PspaceReadOnlyIT {

    private static PspaceClient client;
    private static PspaceNativeApi nativeApi;
    private static List<PspaceModels.Point> points;
    private static List<String> tags;
    private static PspaceModels.Point firstPoint;
    private static String firstTag;
    private static PspaceModels.Point numericPoint;
    private static String numericTag;
    private static Instant windowStart;
    private static Instant windowEnd;
    private static int intervalSeconds;
    private static boolean historyAvailable;
    private static Instant denseStart;
    private static Instant denseEnd;
    private static List<PspaceModels.HistValue> denseRaw;
    private static boolean denseAvailable;

    @BeforeAll
    static void connect() throws IOException {
        Assumptions.assumeTrue(true,
                "pSpace IT is disabled; pass -Dpspace.it=true to enable it");

        Properties file = PspaceItSupport.loadProperties();
        String baseUrl = PspaceItSupport.baseUrl(file);
        Assumptions.assumeFalse(PspaceItSupport.blank(baseUrl), "pSpace base URL is missing");

        client = PspaceItSupport.client(file, false);
        nativeApi = client.unwrap(PspaceNativeApi.class);

        points = PspaceItSupport.probePoints(nativeApi);
        Assumptions.assumeFalse(points.isEmpty(),
                "pSpace /PointPage returned no points even after /Refresh; "
                        + "cache may still be cold or the account has no visible tags");

        int maxTags = PspaceItSupport.intValue(file, "maxTags", "PSPACE_IT_MAX_TAGS", 5);
        tags = PspaceItSupport.readableTags(points, maxTags);
        Assumptions.assumeFalse(tags.isEmpty(),
                "pSpace account only exposes directory nodes; no readable tag to test");
        firstTag = tags.get(0);
        for (PspaceModels.Point point : points) {
            if (point != null && !point.node && firstTag.equals(point.longName)) {
                firstPoint = point;
                break;
            }
        }
        for (PspaceModels.Point point : points) {
            // pSpace DataType 10=FLOAT、11=DOUBLE；聚合数值对数必须用模拟量点。
            if (point != null && !point.node && tags.contains(point.longName)
                    && (point.dataType == 10 || point.dataType == 11)) {
                numericPoint = point;
                numericTag = point.longName;
                break;
            }
        }

        windowEnd = Instant.now();
        windowStart = windowEnd.minus(Duration.ofHours(
                PspaceItSupport.longValue(file, "historyHours", "PSPACE_IT_HISTORY_HOURS", 24L)));
        intervalSeconds = PspaceItSupport.intValue(
                file, "intervalSeconds", "PSPACE_IT_INTERVAL_SECONDS", 3600);

        historyAvailable = hasHistory(windowStart, windowEnd);
        if (!historyAvailable) {
            long days = PspaceItSupport.longValue(file, "historyDays", "PSPACE_IT_HISTORY_DAYS", 30L);
            Instant wideStart = windowEnd.minus(Duration.ofDays(days));
            if (hasHistory(wideStart, windowEnd)) {
                windowStart = wideStart;
                historyAvailable = true;
            }
        }

        denseRaw = Collections.emptyList();
        if (historyAvailable && numericPoint != null) {
            findDenseWindow();
        }

        HealthStatus health = client.health();
        System.out.println("pSpace IT: baseUrl=" + baseUrl
                + ", points(all)=" + points.size()
                + ", readableTags=" + tags
                + ", historyAvailable=" + historyAvailable
                + ", window=[" + windowStart + ", " + windowEnd + ")"
                + ", denseAvailable=" + denseAvailable
                + ", denseWindow=[" + denseStart + ", " + denseEnd + ")"
                + ", health=" + (health == null ? "null" : health.getState()));
    }

    @AfterAll
    static void close() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void healthAndCapabilities() {
        HealthStatus health = client.health();
        assertNotNull(health, "health status must not be null");
        assertTrue(health.getState() != HealthStatus.State.DOWN,
                "pSpace health is DOWN: " + health.getMessage());
        assertTrue(client.capabilities().supports(Capability.TAG_BROWSE));
        assertTrue(client.capabilities().supports(Capability.CURRENT_VALUE));
        assertTrue(client.capabilities().supports(Capability.AGGREGATED_HISTORY));
        assertTrue(client.capabilities().supports(Capability.INTERPOLATED_HISTORY));
        assertTrue(client.capabilities().supports(Capability.SAMPLED_HISTORY));
        assertTrue(client.capabilities().supports(Capability.CUMULATIVE_DELTA));
        assertFalse(client.writeSession().isWritable(), "read-only IT must not be writable");
    }

    @Test
    void serverInfoAndCatalogAreReadable() {
        String info = nativeApi.serverInfo();
        assertNotNull(info);
        assertFalse(PspaceItSupport.blank(info), "server info must not be empty");

        List<TagInfo> catalog = client.tagCatalog().listTags(TagQuery.builder().nameMask("*Tag1").limit(20).build());
        assertFalse(catalog.isEmpty(), "tag catalog must not be empty after /Refresh");
        for (TagInfo tag : catalog) {
            assertNotNull(tag.getTagName(), "tagName must not be null");
        }
        TagInfo first = client.tagCatalog().getTag(firstTag);
        assertNotNull(first, "getTag must resolve an existing LongName: " + firstTag);
        System.out.println("pSpace IT: /ServerInfo=" + info
                + ", catalogSize(" + catalog.size() + "), firstTag=" + firstTag);
    }

    @Test
    void currentValuesAreReadableWithoutThrowing() {
        List<TagSample> values = client.currentValues().read(tags);
        assertEquals(tags.size(), values.size());
        int withValue = 0;
        for (TagSample value : values) {
            assertNotNull(value.getTagName());
            if (value.getSample() != null) {
                withValue++;
            }
        }
        // 模拟环境允许没有实时值；这里只验证“空值不抛异常、顺序稳定”的契约。
        System.out.println("pSpace IT: current values for " + tags.size()
                + " tags, with value=" + withValue);
    }

    @Test
    void rawHistoryReadsWindowWhenVendorProvidesData() {
        Assumptions.assumeTrue(historyAvailable,
                "未执行：窗口内查不到原始历史，等厂商提供历史数据");
        List<TagSeries> series = client.history().read(HistoryQuery.builder()
                .tags(firstTag)
                .range(windowStart, windowEnd)
                .mode(QueryMode.RAW)
                .maxPoints(200)
                .build());
        assertEquals(1, series.size());
        assertFalse(series.get(0).getSamples().isEmpty(),
                "RAW history returned no samples in " + windowStart + ".." + windowEnd);
        System.out.println("pSpace IT: RAW samples=" + series.get(0).getSamples().size());
    }

    @Test
    void aggregatedHistoryReadsWindowWhenVendorProvidesData() {
        Assumptions.assumeTrue(historyAvailable,
                "未执行：窗口内查不到聚合历史，等厂商提供历史数据");
        Assumptions.assumeTrue(denseAvailable,
                "未执行：窗口内无稠密原始数据（至少 2 个桶有 ≥2 点），无法核对聚合数值");
        List<TagSeries> series = client.history().read(HistoryQuery.builder()
                .tags(numericTag)
                .range(denseStart, denseEnd)
                .mode(QueryMode.AGGREGATED)
                .aggregation(Aggregation.AVG)
                .interval(Duration.ofSeconds(60))
                .build());
        assertEquals(1, series.size());
        TagSeries tag = series.get(0);
        assertFalse(tag.getSamples().isEmpty(), "AGGREGATED history returned no samples");

        int nonNull = 0;
        int compared = 0;
        int empty = 0;
        for (Sample sample : tag.getSamples()) {
            if (sample.isNull()) {
                empty++;
                continue;
            }
            nonNull++;
            if (sample.getQuality() != Quality.GOOD) {
                continue;
            }
            Double rawMean = meanRaw(sample.getTimestamp(), sample.getTimestamp().plusSeconds(60));
            if (rawMean != null) {
                assertEquals(rawMean.doubleValue(), sample.getDoubleValue(), 1e-3d,
                        "AVG pv must match the mean of raw samples in bucket "
                                + sample.getTimestamp());
                compared++;
            }
        }
        assertTrue(nonNull > 0, "AGGREGATED returned buckets but all values are null");
        int missingBuckets = 10 - nonNull;
        String missingAttr = tag.getAttributes().get("valueMissingCount");
        if (missingAttr != null) {
            assertEquals(Integer.toString(missingBuckets), missingAttr,
                    "empty buckets must be omitted and counted in valueMissingCount");
        }
        Assumptions.assumeTrue(compared > 0,
                "未执行：稠密窗口内的 AVG 桶没有 GOOD 原始点可对数");
        System.out.println("pSpace IT: AGGREGATED(AVG,60s) samples=" + tag.getSamples().size()
                + ", nonNull=" + nonNull + ", compared=" + compared + ", empty=" + empty);
    }

    @Test
    void interpolatedAndSampledHistoryReadWindowWhenVendorProvidesData() {
        Assumptions.assumeTrue(historyAvailable,
                "未执行：窗口内查不到插值/采样历史，等厂商提供历史数据");
        // 7.1 对没有可插值数据的点位（如数字化点）返回 code=1；换模拟点位再试。
        String target = null;
        for (String candidate : tags) {
            List<TagSeries> probe = client.history().read(HistoryQuery.builder()
                    .tags(candidate)
                    .range(windowStart, windowEnd)
                    .mode(QueryMode.INTERPOLATED)
                    .interval(Duration.ofSeconds(intervalSeconds))
                    .build());
            if (!probe.isEmpty() && !probe.get(0).getSamples().isEmpty()) {
                target = candidate;
                break;
            }
        }
        Assumptions.assumeTrue(target != null,
                "未执行：探测的位号 INTVE 均为空（数字化点位无可插值数据），等厂商提供模拟量历史");

        List<TagSeries> interpolated = client.history().read(HistoryQuery.builder()
                .tags(target)
                .range(windowStart, windowEnd)
                .mode(QueryMode.INTERPOLATED)
                .interval(Duration.ofSeconds(intervalSeconds))
                .build());
        assertFalse(interpolated.get(0).getSamples().isEmpty(),
                "INTERPOLATED history returned no samples");

        List<TagSeries> sampled = client.history().read(HistoryQuery.builder()
                .tags(target)
                .range(windowStart, windowEnd)
                .mode(QueryMode.SAMPLED)
                .interval(Duration.ofSeconds(intervalSeconds))
                .build());
        assertFalse(sampled.get(0).getSamples().isEmpty(), "SAMPLED history returned no samples");
        System.out.println("pSpace IT: INTERPOLATED(" + target + ")="
                + interpolated.get(0).getSamples().size()
                + ", SAMPLED=" + sampled.get(0).getSamples().size());
    }

    @Test
    void deltaFoldProducesOnePointPerBucketWhenVendorProvidesData() {
        Assumptions.assumeTrue(historyAvailable && numericPoint != null && denseAvailable,
                "未执行：窗口内无稠密累计量历史，无法核对差分数值");
        long intervalMillis = 60_000L;
        int buckets = (int) (Duration.between(denseStart, denseEnd).toMillis() / intervalMillis);

        List<DeltaSeries> series = client.deltaHistory().readByInterval(
                Collections.singletonList(numericTag), denseStart, denseEnd, intervalMillis,
                Aggregation.LAST, FillPolicy.NONE, Direction.FORWARD, buckets);
        assertEquals(1, series.size());
        List<DeltaPoint> points = series.get(0).getPoints();
        assertEquals(buckets, points.size(), "delta fold must emit one point per bucket");

        int nonMissing = 0;
        int compared = 0;
        for (DeltaPoint point : points) {
            if (point.isMissing()) {
                continue;
            }
            nonMissing++;
            Instant bucketStart = point.getTimestamp();
            Double previous = lastRaw(bucketStart.minusSeconds(60), bucketStart);
            Double current = lastRaw(bucketStart, bucketStart.plusSeconds(60));
            if (previous == null || current == null) {
                continue;
            }
            if (current < previous) {
                assertTrue(point.isReset(), "falling endval must set reset=true at " + bucketStart);
                assertEquals(current.doubleValue(), point.getDelta(), 1e-3d);
                compared++;
            } else {
                assertFalse(point.isReset(), "non-falling bucket must not set reset at " + bucketStart);
                assertEquals(current - previous, point.getDelta(), 1e-3d,
                        "delta must equal endval(bucket) - endval(previous bucket) at " + bucketStart);
                compared++;
            }
        }
        assertTrue(nonMissing > 0,
                "delta returned only missing buckets; endval timestamps were not bucket-aligned");
        Assumptions.assumeTrue(compared > 0,
                "未执行：稠密窗口内没有可与原始末值对数的差分桶");
        System.out.println("pSpace IT: delta buckets=" + buckets
                + ", nonMissing=" + nonMissing + ", compared=" + compared);
    }

    @Test
    void refreshKeepsCatalogVisible() {
        String raw = nativeApi.refresh();
        assertNotNull(raw);
        PspaceModels.Page page = nativeApi.pointPage(100, 1);
        assertFalse(page.values.isEmpty(), "/PointPage must stay non-empty right after /Refresh");
        List<String> again = new ArrayList<String>();
        for (PspaceModels.Point point : nativeApi.parsePoints(page.values)) {
            if (point.longName != null) {
                again.add(point.longName);
            }
        }
        assertTrue(again.contains(firstTag),
                "first tag must still be visible after /Refresh: " + again);
    }

    /**
     * 在最近 3 小时原始历史里寻找连续 10 分钟、至少 2 个一分钟桶有 ≥2 个点的窗口。
     * 找不到时保持 {@code denseAvailable=false}，相关用例显式 skip。
     */
    private static void findDenseWindow() {
        Instant probeStart = windowEnd.minus(Duration.ofHours(3));
        List<PspaceModels.HistValue> probe = rawValues(probeStart, windowEnd);
        if (probe.isEmpty()) {
            return;
        }
        Instant candidate = probe.get(0).timestamp.truncatedTo(ChronoUnit.MINUTES);
        while (!candidate.plus(Duration.ofMinutes(10)).isAfter(windowEnd)) {
            int denseBuckets = 0;
            for (int i = 0; i < 10; i++) {
                Instant bucketStart = candidate.plusSeconds(i * 60L);
                if (countRaw(probe, bucketStart, bucketStart.plusSeconds(60L)) >= 2) {
                    denseBuckets++;
                }
            }
            if (denseBuckets >= 2) {
                denseStart = candidate;
                denseEnd = candidate.plus(Duration.ofMinutes(10));
                denseRaw = rawValues(candidate.minusSeconds(60L), denseEnd);
                denseAvailable = true;
                return;
            }
            candidate = candidate.plusSeconds(60L);
        }
    }

    private static List<PspaceModels.HistValue> rawValues(Instant start, Instant end) {
        List<PspaceModels.HistValue> result = new ArrayList<PspaceModels.HistValue>();
        List<PspaceModels.HistRow> rows = nativeApi.histData(
                Collections.singletonList(Long.valueOf(numericPoint.id)), start, end);
        for (PspaceModels.HistRow row : rows) {
            result.addAll(row.values);
        }
        result.sort(Comparator.comparing(value -> value.timestamp));
        return result;
    }

    private static int countRaw(List<PspaceModels.HistValue> values, Instant start, Instant end) {
        int count = 0;
        for (PspaceModels.HistValue value : values) {
            if (!value.timestamp.isBefore(start) && value.timestamp.isBefore(end)) {
                count++;
            }
        }
        return count;
    }

    private static Double meanRaw(Instant start, Instant end) {
        double sum = 0.0d;
        int count = 0;
        for (PspaceModels.HistValue value : denseRaw) {
            if (value.timestamp.isBefore(start) || !value.timestamp.isBefore(end)) {
                continue;
            }
            Double numeric = toDouble(value.value);
            if (numeric != null) {
                sum += numeric.doubleValue();
                count++;
            }
        }
        return count == 0 ? null : Double.valueOf(sum / count);
    }

    private static Double lastRaw(Instant start, Instant end) {
        Double last = null;
        for (PspaceModels.HistValue value : denseRaw) {
            if (value.timestamp.isBefore(start) || !value.timestamp.isBefore(end)) {
                continue;
            }
            Double numeric = toDouble(value.value);
            if (numeric != null) {
                last = numeric;
            }
        }
        return last;
    }

    private static Double toDouble(Object raw) {
        if (raw instanceof Number) {
            return Double.valueOf(((Number) raw).doubleValue());
        }
        if (raw == null) {
            return null;
        }
        try {
            return Double.valueOf(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean hasHistory(Instant start, Instant end) {
        if (firstPoint == null) {
            return false;
        }
        List<PspaceModels.HistRow> rows = nativeApi.histData(
                Collections.singletonList(Long.valueOf(firstPoint.id)), start, end);
        for (PspaceModels.HistRow row : rows) {
            if (!row.values.isEmpty()) {
                return true;
            }
        }
        return false;
    }
}