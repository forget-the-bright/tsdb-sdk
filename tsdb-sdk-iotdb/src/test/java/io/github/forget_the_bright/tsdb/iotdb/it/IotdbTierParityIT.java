package io.github.forget_the_bright.tsdb.iotdb.it;

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
import io.github.forget_the_bright.tsdb.iotdb.IotdbDriver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * L3 双跑对数（同库自对自）：同一 IoTDB 实例、同一批位号、同一窗口，
 * 三种批量配置各取一遍数，逐序列逐点比对，<b>0 容差</b>。
 *
 * <p>三个配置只差“一条 SQL 里放几个表达式”，读的是同一批序列，所以答案必须
 * 逐点相同：</p>
 * <ul>
 *   <li>{@code reference} —— {@code query-batch-size: 1}，一条 SQL 一个表达式，
 *       等价于本轮改造前的逐位号取数，是基准；</li>
 *   <li>{@code device} —— 按设备分组（改造前行为，也是当前默认档位）；</li>
 *   <li>{@code database} —— 按数据库分组（本轮目标形态，即业务要的 SQL 形状）。</li>
 * </ul>
 *
 * <p>这一档专门盯批量改造唯一可能引入的错误：K1/K2 列对齐串位——把 A 位号的值
 * 挂到 B 位号名下。GE↔IoTDB 对数未必抓得住这种错（两边都可能“看起来有数”），
 * 而自对自的 0 容差比对口子最紧。按 {@code docs/14} §8.3，
 * <b>{@code database} 档与基准逐点相同，是把默认档位从 {@code device} 翻成
 * {@code database} 的唯一入场券</b>。</p>
 *
 * <p>比对集合覆盖三类容易串位的形态：同一设备下多个测点、同库跨设备、跨库混合；
 * 并固定包含一个“序列存在但无点”与一个“路径不存在”的位号（实测 G2：前者保留空列、
 * 后者整列消失，两者都是对齐护栏必须正确处理的形状）。</p>
 *
 * <p>差异一律<b>失败</b>，不做“多次取平均”之类的容差。仅在失败前复读一次，
 * 目的是把两轮结果都写进断言消息，便于区分“护栏真串位”与“夹具在动”，
 * 复读不会把失败变成通过。</p>
 *
 * <p>只读：全程只有 SELECT，不写入、不建表、不改配置。默认不执行，
 * 只有显式传入 {@code -Diotdb.it=true} 才连接现场库；地址与凭据按
 * “系统属性 → 环境变量 → iotdb-it.properties”的优先级读取。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
@Tag("iotdb-it")
@EnabledIfSystemProperty(named = "iotdb.it", matches = "true")
class IotdbTierParityIT {

    /** 参与对数的位号：同库跨设备、跨库、含空序列与不存在路径。 */
    private static final List<String> TAGS = Arrays.asList(
            "root.sdk_it.device01.flow",
            "root.sdk_it.device01.temperature",
            "root.sdk_it.device02.flow",
            "root.simdemo.counter_loop.cycle_no",
            "root.simdemo.boiler_01.temperature",
            "root.tlplant.casting1.n01.temp",
            "root.sdk_it.device01.nope_zzz_parity");

    /** 空序列（存在但无点）与不存在路径，用来比对“缺值”形态在三种配置下是否一致。 */
    private static final String EMPTY_SERIES = "root.sdk_it.device02.flow";
    private static final String UNKNOWN_PATH = "root.sdk_it.device01.nope_zzz_parity";

    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;

    private static Properties properties;
    private static Instant anchorLast;
    private static List<String> populated;

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

    @Test
    void currentValuesMatchAcrossTheThreeBatchShapes() {
        List<String> tags = anchor();
        run("current", "sample", new Comparison() {
            public Outcome compare(IotdbClient reference, IotdbClient device, IotdbClient database) {
                List<TagSample> base = reference.currentValues().read(tags);
                List<TagSample> dev = device.currentValues().read(tags);
                List<TagSample> db = database.currentValues().read(tags);
                List<String> diffs = new ArrayList<String>();
                compareCurrent("device", tags, base, dev, diffs);
                compareCurrent("database", tags, base, db, diffs);
                return new Outcome(diffs, tags.size(), samplesOfCurrent(base), 0);
            }
        });
    }

    @Test
    void rawHistoryMatchesAcrossTheThreeBatchShapes() {
        List<String> tags = anchor();
        run("raw", "point", new Comparison() {
            public Outcome compare(IotdbClient reference, IotdbClient device, IotdbClient database) {
                HistoryQuery query = HistoryQuery.builder()
                        .tags(tags)
                        .range(anchorLast.minus(Duration.ofHours(3)), anchorLast.plusMillis(1))
                        .mode(QueryMode.RAW)
                        .direction(Direction.BACKWARD)
                        .maxPoints(500)
                        .build();
                // 3 小时窗口里既有 10 Hz 的密集序列，也有小时级的稀疏序列：
                // 这正是实测 M9 的形态——多列 raw 共享一个 LIMIT 会把稀疏列饿掉，
                // 只有“批量 + 逐位号补采”才可能与逐位号取数逐点相同。
                return seriesOutcome(reference, device, database, query, tags);
            }
        });
    }

    @Test
    void sampledGridMatchesAcrossTheThreeBatchShapes() {
        List<String> tags = anchor();
        run("sampled", "slot", grid(QueryMode.SAMPLED, Aggregation.LAST, FillPolicy.PREVIOUS,
                10 * MINUTE, 6 * HOUR, tags));
    }

    @Test
    void aggregatedHistoryMatchesAcrossTheThreeBatchShapes() {
        List<String> tags = anchor();
        run("aggregated", "slot", grid(QueryMode.AGGREGATED, Aggregation.AVG, FillPolicy.NONE,
                HOUR, 6 * HOUR, tags));
    }

    @Test
    void intervalSumMatchesAcrossTheThreeBatchShapes() {
        List<String> tags = anchor();
        run("intervalSum", "slot", grid(QueryMode.AGGREGATED, Aggregation.INTERVAL_SUM,
                FillPolicy.NONE, HOUR, 6 * HOUR, tags));
    }

    @Test
    void interpolatedGridMatchesAcrossTheThreeBatchShapes() {
        List<String> tags = anchor();
        run("interpolated", "slot", grid(QueryMode.INTERPOLATED, Aggregation.AVG, FillPolicy.NONE,
                15 * MINUTE, 3 * HOUR, tags));
    }

    @Test
    void deltaHistoryMatchesAcrossTheThreeBatchShapes() {
        List<String> tags = anchor();
        run("delta", "bucket", new Comparison() {
            public Outcome compare(IotdbClient reference, IotdbClient device, IotdbClient database) {
                Instant start = anchorLast.minus(Duration.ofHours(6));
                Instant end = anchorLast.plusMillis(1);
                List<DeltaSeries> base = delta(reference, tags, start, end);
                List<DeltaSeries> dev = delta(device, tags, start, end);
                List<DeltaSeries> db = delta(database, tags, start, end);
                List<String> diffs = new ArrayList<String>();
                for (int i = 0; i < tags.size(); i++) {
                    compareDelta("device", tags.get(i), base.get(i), dev.get(i), diffs);
                    compareDelta("database", tags.get(i), base.get(i), db.get(i), diffs);
                }
                int buckets = 0;
                for (DeltaSeries series : base) {
                    buckets += series.getPoints().size();
                }
                return new Outcome(diffs, tags.size(), buckets, 0);
            }
        });
    }

    private interface Comparison {
        Outcome compare(IotdbClient reference, IotdbClient device, IotdbClient database);
    }

    /** 三种批量配置的读入与逐点比对结果。 */
    private static final class Outcome {
        private final List<String> diffs;
        private final int series;
        private final int units;
        private final int completedColumns;

        private Outcome(List<String> diffs, int series, int units, int completedColumns) {
            this.diffs = diffs;
            this.series = series;
            this.units = units;
            this.completedColumns = completedColumns;
        }
    }

    /** 三个客户端各读一遍并比对，同时统计逐位号补采的列数。 */
    private static Outcome seriesOutcome(IotdbClient reference, IotdbClient device,
                                         IotdbClient database, HistoryQuery query,
                                         List<String> tags) {
        List<TagSeries> base = reference.history().read(query);
        List<TagSeries> dev = device.history().read(query);
        List<TagSeries> db = database.history().read(query);
        List<String> diffs = new ArrayList<String>();
        compareSeries("device", tags, base, dev, diffs);
        compareSeries("database", tags, base, db, diffs);
        return new Outcome(diffs, tags.size(), samplesOf(base), completionColumns(db));
    }

    private static Comparison grid(QueryMode mode, Aggregation aggregation, FillPolicy fill,
                                   long intervalMillis, long spanMillis, List<String> tags) {
        return new Comparison() {
            public Outcome compare(IotdbClient reference, IotdbClient device, IotdbClient database) {
                HistoryQuery query = HistoryQuery.builder()
                        .tags(tags)
                        .range(anchorLast.minus(Duration.ofMillis(spanMillis)),
                                anchorLast.plusMillis(1))
                        .mode(mode)
                        .aggregation(aggregation)
                        .interval(Duration.ofMillis(intervalMillis))
                        .fill(fill)
                        .build();
                return seriesOutcome(reference, device, database, query, tags);
            }
        };
    }

    private static List<DeltaSeries> delta(IotdbClient client, List<String> tags,
                                           Instant start, Instant end) {
        return client.deltaHistory().read(tags, start, end, TimeGranularity.HOUR,
                Aggregation.LAST, FillPolicy.PREVIOUS, Direction.FORWARD, 200);
    }

    /**
     * 建三个配置的客户端、跑一次比对；有差异时再复读一次，把两轮结果都写进断言消息。
     * 复读只用于取证，不用于放行。
     */
    private static void run(String family, String unit, Comparison comparison) {
        IotdbClient reference = client(1, "device");
        IotdbClient device = client(200, "device");
        IotdbClient database = client(200, "database");
        try {
            Outcome first = comparison.compare(reference, device, database);
            log(family, unit, first, "round1");
            if (first.diffs.isEmpty()) {
                return;
            }
            Outcome second = comparison.compare(reference, device, database);
            log(family, unit, second, "round2");
            assertTrue(second.diffs.isEmpty(), "L3 双跑对数失败（0 容差）family=" + family
                    + "，round1=" + first.diffs.size() + "、round2=" + second.diffs.size()
                    + "；复读后仍存在差异，说明不是夹具在动。\n"
                    + "round2 前 12 条：\n" + preview(second.diffs));
        } finally {
            reference.close();
            device.close();
            database.close();
        }
    }

    private static void log(String family, String unit, Outcome outcome, String round) {
        System.out.println("L3-PARITY family=" + family + " " + round + " series=" + outcome.series
                + " " + unit + "s=" + outcome.units
                + " rawBatchCompletedColumns=" + outcome.completedColumns
                + " diffs=" + outcome.diffs.size()
                + (outcome.diffs.isEmpty() ? "" : " preview=" + preview(outcome.diffs)));
    }

    private static String preview(List<String> diffs) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < Math.min(12, diffs.size()); i++) {
            text.append(i > 0 ? " | " : "").append(diffs.get(i));
        }
        return text.toString();
    }

    private static int completionColumns(List<TagSeries> series) {
        int count = 0;
        for (TagSeries seriesItem : series) {
            if ("true".equals(seriesItem.getAttributes().get("rawBatchCompleted"))) {
                count++;
            }
        }
        return count;
    }

    private static int samplesOf(List<TagSeries> series) {
        int count = 0;
        for (TagSeries seriesItem : series) {
            count += seriesItem.getSamples().size();
        }
        return count;
    }

    private static int samplesOfCurrent(List<TagSample> samples) {
        int count = 0;
        for (TagSample sample : samples) {
            if (sample.getSample() != null) {
                count++;
            }
        }
        return count;
    }

    private static void compareSeries(String tier, List<String> tags, List<TagSeries> base,
                                      List<TagSeries> other, List<String> diffs) {
        if (base.size() != tags.size() || other.size() != tags.size()) {
            diffs.add("tier=" + tier + " returned " + base.size() + "/" + other.size()
                    + " series for " + tags.size() + " tags（返回长度必须等于入参长度）");
            return;
        }
        for (int i = 0; i < tags.size(); i++) {
            String tag = tags.get(i);
            TagSeries left = base.get(i);
            TagSeries right = other.get(i);
            if (!tag.equals(left.getTagName()) || !tag.equals(right.getTagName())) {
                diffs.add("index=" + i + " tier=" + tier + " tagName " + left.getTagName()
                        + "/" + right.getTagName() + " != " + tag);
            }
            if (left.isTruncated() != right.isTruncated()) {
                diffs.add(tag + " tier=" + tier + " truncated " + left.isTruncated() + "!="
                        + right.isTruncated());
            }
            List<Sample> a = left.getSamples();
            List<Sample> b = right.getSamples();
            if (a.size() != b.size()) {
                diffs.add(tag + " tier=" + tier + " count " + a.size() + "!=" + b.size());
                continue;
            }
            for (int j = 0; j < a.size(); j++) {
                compareSamples(tier, tag, j, a.get(j), b.get(j), diffs);
            }
        }
    }

    private static void compareSamples(String tier, String tag, int index, Sample a, Sample b,
                                       List<String> diffs) {
        if (!Objects.equals(a.getTimestamp(), b.getTimestamp())) {
            diffs.add(tag + " tier=" + tier + " index=" + index + " timestamp " + a.getTimestamp()
                    + "!=" + b.getTimestamp());
            return;
        }
        if (a.isNull() != b.isNull()) {
            diffs.add(tag + " tier=" + tier + " index=" + index + " isNull " + a.isNull() + "!="
                    + b.isNull() + " values=" + a.getValue() + "/" + b.getValue());
            return;
        }
        if (!sameValue(a.getValue(), b.getValue())) {
            diffs.add(tag + " tier=" + tier + " index=" + index + " value " + a.getValue() + " ("
                    + a.getValueType() + ") != " + b.getValue() + " (" + b.getValueType() + ")");
            return;
        }
        if (!Objects.equals(a.getQuality(), b.getQuality())) {
            diffs.add(tag + " tier=" + tier + " index=" + index + " quality " + a.getQuality()
                    + "!=" + b.getQuality());
        }
    }

    private static void compareCurrent(String tier, List<String> tags, List<TagSample> base,
                                       List<TagSample> other, List<String> diffs) {
        if (base.size() != tags.size() || other.size() != tags.size()) {
            diffs.add("current tier=" + tier + " returned " + base.size() + "/" + other.size()
                    + " entries for " + tags.size() + " tags");
            return;
        }
        for (int i = 0; i < tags.size(); i++) {
            String tag = tags.get(i);
            Sample left = base.get(i).getSample();
            Sample right = other.get(i).getSample();
            if ((left == null) != (right == null)) {
                diffs.add(tag + " tier=" + tier + " current nullness " + (left == null) + "!="
                        + (right == null));
            } else if (left != null) {
                compareSamples(tier, tag, 0, left, right, diffs);
            }
        }
    }

    private static void compareDelta(String tier, String tag, DeltaSeries base,
                                     DeltaSeries other, List<String> diffs) {
        List<DeltaPoint> a = base.getPoints();
        List<DeltaPoint> b = other.getPoints();
        if (a.size() != b.size()) {
            diffs.add(tag + " tier=" + tier + " bucket count " + a.size() + "!=" + b.size());
            return;
        }
        for (int i = 0; i < a.size(); i++) {
            DeltaPoint left = a.get(i);
            DeltaPoint right = b.get(i);
            if (!Objects.equals(left.getTimestamp(), right.getTimestamp())) {
                diffs.add(tag + " tier=" + tier + " bucket=" + i + " timestamp "
                        + left.getTimestamp() + "!=" + right.getTimestamp());
            } else if (left.isMissing() != right.isMissing() || left.isReset() != right.isReset()) {
                diffs.add(tag + " tier=" + tier + " bucket=" + i + " flags missing "
                        + left.isMissing() + "/" + right.isMissing() + " reset "
                        + left.isReset() + "/" + right.isReset());
            } else {
                Double leftDelta = left.getDelta();
                Double rightDelta = right.getDelta();
                boolean same = leftDelta == null || rightDelta == null
                        ? leftDelta == null && rightDelta == null
                        : leftDelta - rightDelta == 0.0;
                if (!same) {
                    diffs.add(tag + " tier=" + tier + " bucket=" + i + " delta " + leftDelta
                            + "!=" + rightDelta);
                }
            }
        }
    }

    /** 数值按数值本身比较（实测 G8：三通道类型不同），其余按文本比较，一律 0 容差。 */
    private static boolean sameValue(Object a, Object b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        if (a instanceof Number && b instanceof Number) {
            double left = ((Number) a).doubleValue();
            double right = ((Number) b).doubleValue();
            return Double.compare(left, right) == 0 || (Double.isNaN(left) && Double.isNaN(right));
        }
        return String.valueOf(a).equals(String.valueOf(b));
    }

    /**
     * 窗口锚定：先探测每个位号自身的首末样本，再把窗口钉在末样本上。
     * 不按“现在往前推 N 小时”取窗口——合成数据是固定区间，相对窗口会在数据龄期
     * 变化后整窗落空（{@code IotdbMappingProductionIT} 就是这么失效过的）。
     */
    private static List<String> anchor() {
        if (anchorLast == null) {
            IotdbClient probe = client(1, "device");
            try {
                Set<String> withData = new LinkedHashSet<String>();
                Instant latest = null;
                for (String tag : TAGS) {
                    Instant last = lastSampleOf(probe, tag);
                    if (last != null) {
                        withData.add(tag);
                        if (latest == null || last.isAfter(latest)) {
                            latest = last;
                        }
                    }
                }
                anchorLast = latest;
                populated = new ArrayList<String>(withData);
                System.out.println("L3-PARITY anchor last=" + latest + " populated=" + withData.size()
                        + " of " + TAGS.size() + " " + populated);
            } finally {
                probe.close();
            }
        }
        assumeTrue(anchorLast != null, "no fixture series carries data on this server");
        assumeTrue(populated.size() >= 2, "需要至少两个有序列的位号才能比出串位");
        // 有数的位号 + 空序列 + 不存在路径；后两者用来比对“缺值”形态是否一致
        Set<String> tags = new LinkedHashSet<String>(populated);
        tags.add(EMPTY_SERIES);
        tags.add(UNKNOWN_PATH);
        return new ArrayList<String>(tags);
    }

    /** 该位号在库里的最后一个样本时间戳；没有数据时返回 null。 */
    private static Instant lastSampleOf(IotdbClient client, String tag) {
        List<TagSeries> series = client.history().read(HistoryQuery.builder()
                .tags(tag)
                .range(Instant.ofEpochMilli(1), Instant.now().plusSeconds(3_600))
                .mode(QueryMode.RAW)
                .direction(Direction.BACKWARD)
                .maxPoints(1)
                .build());
        List<Sample> samples = series.get(0).getSamples();
        return samples.isEmpty() ? null : samples.get(0).getTimestamp();
    }

    private static IotdbClient client(int batchSize, String scope) {
        assumeTrue(Boolean.parseBoolean(System.getProperty("iotdb.it", "false")),
                "set -Diotdb.it=true to run against a real IoTDB");
        SecurityConfig security = new SecurityConfig();
        security.setAllowInsecureTransport(true);

        TsdbInstanceConfig instance = new TsdbInstanceConfig();
        instance.setType("iotdb");
        instance.setBaseUrl(text("baseUrl", "http://192.168.3.199:18080"));
        instance.getAuth().setUsername(text("username", "root"));
        instance.getAuth().setPassword(text("password", "root"));
        instance.getOptions().put("transport", text("transport", "rest"));
        instance.getOptions().put("rpc-port", text("rpcPort", "6667"));
        instance.getOptions().put("query-batch-size", String.valueOf(batchSize));
        instance.getOptions().put("query-batch-scope", scope);

        TsdbConfig root = new TsdbConfig();
        root.setSecurity(security);
        DriverContext context = new DriverContext(
                "iotdb_parity_" + scope + "_" + batchSize, root, null);
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
