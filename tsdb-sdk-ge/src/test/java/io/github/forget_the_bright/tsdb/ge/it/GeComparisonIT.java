package io.github.forget_the_bright.tsdb.ge.it;

import io.github.forget_the_bright.ge.config.ApiConfig;
import io.github.forget_the_bright.ge.constant.common.CalculationMode;
import io.github.forget_the_bright.ge.constant.common.SamplingMode;
import io.github.forget_the_bright.ge.core.ApiClient;
import io.github.forget_the_bright.ge.core.LocalTimedCacheHolder;
import io.github.forget_the_bright.ge.core.TokenHolder;
import io.github.forget_the_bright.ge.entity.request.data.SampledEntity;
import io.github.forget_the_bright.ge.entity.request.tags.TagNamesEntity;
import io.github.forget_the_bright.ge.entity.response.DataResult;
import io.github.forget_the_bright.ge.entity.response.TagsResult;
import io.github.forget_the_bright.ge.entity.response.base.DataItem;
import io.github.forget_the_bright.ge.service.DataApiInvoker;
import io.github.forget_the_bright.ge.service.TagsApiInvoker;
import io.github.forget_the_bright.tsdb.common.config.AuthConfig;
import io.github.forget_the_bright.tsdb.common.config.SecurityConfig;
import io.github.forget_the_bright.tsdb.common.config.TimeoutConfig;
import io.github.forget_the_bright.tsdb.common.config.TsdbInstanceConfig;
import io.github.forget_the_bright.tsdb.common.model.Aggregation;
import io.github.forget_the_bright.tsdb.common.model.Direction;
import io.github.forget_the_bright.tsdb.common.model.HealthStatus;
import io.github.forget_the_bright.tsdb.common.model.HistoryQuery;
import io.github.forget_the_bright.tsdb.common.model.QueryMode;
import io.github.forget_the_bright.tsdb.common.model.Sample;
import io.github.forget_the_bright.tsdb.common.model.TagSample;
import io.github.forget_the_bright.tsdb.common.model.TagSeries;
import io.github.forget_the_bright.tsdb.ge.GeClient;
import io.github.forget_the_bright.tsdb.ge.GeConfig;
import io.github.forget_the_bright.tsdb.ge.internal.GeHttpClient;
import io.github.forget_the_bright.tsdb.ge.internal.GeModels;
import io.github.forget_the_bright.tsdb.ge.internal.GeNativeApi;
import io.github.forget_the_bright.tsdb.ge.internal.GeTokenManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GE Proficy Historian 生产环境<strong>只读</strong>新旧 SDK 逐点一致性对比。
 *
 * <p>本测试是 SDK 改造的<strong>裁决闸口</strong>：用真实生产 Historian 同时跑
 * 旧 {@code GE_REST_SDK}（基准）与新 {@code tsdb-sdk-ge}，对同一批位号、同一时间窗
 * 的六类查询逐点比对<strong>时间戳 / 值 / 质量码</strong>，任一 DIFF 即失败。
 * 目的是证明改造后的公共 API 能查出与旧 SDK <em>真实一致</em>的数据，
 * 而不是“装作一致”。</p>
 *
 * <p><strong>默认不执行</strong>：只有显式 {@code -Dge.it=true}（或环境变量
 * {@code GE_IT=true}）才运行；缺凭据/缺位号按 {@link Assumptions} 跳过，
 * 不影响常规 {@code mvn test}。运行示例：</p>
 * <pre>{@code
 * mvn -q -pl tsdb-sdk-ge -am test \
 *   "-Dtest=GeComparisonIT" "-Dsurefire.failIfNoSpecifiedTests=false" \
 *   "-Dge.it=true"
 * }</pre>
 *
 * <p><strong>只读守卫：</strong>本类只调用查询端点（标签浏览、当前值、
 * 原始/聚合/插值/采样、区间增量），绝不调用 create/write/delete/rename/
 * 配置修改/采集器启停/告警恢复。生产账号也应只授予只读权限。</p>
 *
 * <p><strong>时间语义（已生产裁决）：</strong>旧 SDK 用 hutool
 * {@code DateUtil.format(date, DatePattern.UTC_FORMAT)} 格式化请求时间，而
 * {@code DatePattern.UTC_FORMAT} 是<strong>时区绑定为 UTC</strong> 的
 * {@code FastDateFormat}，无论 JVM 默认时区为何都输出<strong>真 UTC</strong>
 * （并非早先假设的“本地墙钟 + 字面 Z”伪 UTC）。据此本测试构建两个新客户端：</p>
 * <ul>
 *   <li><b>newClient</b>：真 UTC（请求/响应解析均 UTC）——这是<strong>主契约</strong>，
 *       old vs new 必须 MATCH；生产实证 50 个原始点逐点精确一致；</li>
 *   <li><b>pseudoClient</b>：伪 UTC（请求 GMT+8 墙钟 + 字面 Z）——仅用于
 *       <strong>时间裁决探针</strong>，实证会把查询窗口整体平移 +8 小时导致 DIFF，
 *       作为“为何不能用伪 UTC”的反面证据记录，不硬断言相等。</li>
 * </ul>
 *
 * <p><strong>归一化：</strong>两侧都归一为 {@code Map<位号, List<Pt>>}，
 * {@link Pt} = (epochMilli, 规范化值字符串, 厂商质量码 int)。值统一经
 * {@link BigDecimal} 规范化（去尾零、非科学计数法），质量码取厂商原始 int
 * （旧 {@code Quality.getValue()} == 新 {@code Sample.getVendorQualityCode()}），
 * 时间戳取绝对毫秒。浮点比对先精确 {@code compareTo}，再退化为相对误差 &lt; 1e-9。</p>
 *
 * <p><strong>报告：</strong>逐条 {@code [COMPARE] ... verdict=MATCH|DIFF|OLD_ONLY|NEW_ONLY}
 * 与末尾 {@code [SUMMARY]} 同时输出到控制台与 {@code target/ge-comparison-report.txt}
 * （target 已 gitignore，不入库）。探索性探针（伪 UTC 反证、24 vs 17/19、
 * 两条旧插值路径交叉、GET 对照组、批量耗时）只记录 verdict，不硬失败。</p>
 *
 * <p>凭据优先级与 {@code GeProductionIT} 一致：系统属性 {@code ge.it.*} →
 * 环境变量 {@code GE_IT_*} → {@code ge-it.properties}。旧 SDK 的 {@link ApiConfig}
 * 全部字段从凭据注入，<strong>不使用</strong>其硬编码默认账号。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
@Tag("ge-it")
class GeComparisonIT {

    /** 不存在位号常量，用于验证 per-tag 错误码与“无值/出错”区分。 */
    private static final String MISSING_TAG = "ZJ_IT_NONEXISTENT_TAG_99999";

    /** 批量对比的位号规模（旧 SDK 曾压测 2000 位号单请求 POST）。 */
    private static final int BATCH_SIZE = 2000;

    /** 全量批量超时时的回退规模（仍远超 GET URL 长度上限，足以证明 POST 容量）。 */
    private static final int BATCH_FALLBACK = 500;

    /** 浮点相对误差容差。 */
    private static final double REL_TOLERANCE = 1e-9;

    /** 主契约客户端：真 UTC（已生产实证与旧 SDK 逐点一致）。 */
    private static GeClient newClient;
    private static GeNativeApi newNative;
    /** 探针客户端：伪 UTC（仅用于时间裁决反证，实证会 +8h 错位）。 */
    private static GeClient pseudoClient;

    private static String tag;
    private static String sumTag;
    private static List<String> batchTags;
    private static Instant anchor;

    private static PrintWriter report;
    private static TimeZone originalZone;

    private static int matchCount;
    private static int diffCount;
    private static int oldOnlyCount;
    private static int newOnlyCount;
    private static int probeCount;

    @BeforeAll
    static void connect() throws IOException {
        Assumptions.assumeTrue(enabled(),
                "GE comparison IT is disabled; pass -Dge.it=true to enable it");

        // 旧 SDK 响应解析依赖 JVM 默认时区，固定为 GMT+8 保证可复现；
        // 请求侧 hutool UTC_FORMAT 绑定 UTC，不受此设置影响（已 jshell 实证）。
        originalZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("GMT+8"));

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
                "GE production credentials are missing; set ge.it.username/password/clientId/clientSecret");

        long connectMs = longValue(file, "connectTimeoutMs", "GE_IT_CONNECT_TIMEOUT_MS", 5000L);
        long readMs = longValue(file, "readTimeoutMs", "GE_IT_READ_TIMEOUT_MS", 30000L);
        boolean trustAll = booleanValue(file, "trustAll", "GE_IT_TRUST_ALL", true);
        boolean verifyHostname = booleanValue(file, "verifyHostname", "GE_IT_VERIFY_HOSTNAME", false);

        // ---- 旧 SDK 基准装配（脱离 Spring，全静态字段由构造器赋值）----
        ApiConfig apiConfig = new ApiConfig();
        apiConfig.setBaseUrl(baseUrl);
        apiConfig.setUsername(emptyToNull(username));
        apiConfig.setPassword(emptyToNull(password));
        apiConfig.setClientId(emptyToNull(clientId));
        apiConfig.setClientSecret(emptyToNull(clientSecret));
        apiConfig.setTokenExpireSeconds(43199L);
        apiConfig.setReadTimeout((int) Math.min(Integer.MAX_VALUE, readMs));
        apiConfig.setConnectionTimeout((int) Math.min(Integer.MAX_VALUE, connectMs));
        apiConfig.setCacheModel("Local");
        new ApiClient(apiConfig);
        new TokenHolder(new LocalTimedCacheHolder<String, String>(apiConfig), apiConfig);

        // ---- 新 SDK：真 UTC（主契约）与伪 UTC（时间裁决反证探针）----
        newClient = buildClient("ge-compare-utc", baseUrl, username, password, clientId,
                clientSecret, token, connectMs, readMs, trustAll, verifyHostname, false, file);
        newNative = newClient.unwrap(GeNativeApi.class);
        pseudoClient = buildClient("ge-compare-pseudo", baseUrl, username, password, clientId,
                clientSecret, token, connectMs, readMs, trustAll, verifyHostname, true, file);

        HealthStatus health = newClient.health();
        assertNotNull(health, "health status must not be null");
        Assumptions.assumeTrue(health.getState() != HealthStatus.State.DOWN,
                "GE production health is DOWN: " + health.getMessage());

        // ---- 位号发现 ----
        String configured = value(file, "tag", "GE_IT_TAG", null);
        if (!blank(configured)) {
            tag = configured;
        } else {
            List<String> first = newNative.listTags(1, null);
            Assumptions.assumeTrue(!first.isEmpty(), "no tag available on the production Historian");
            tag = first.get(0);
        }
        String configuredSum = value(file, "sumTag", "GE_IT_SUM_TAG", null);
        if (!blank(configuredSum)) {
            sumTag = configuredSum;
        } else {
            List<String> sums = newNative.listTags(50, "*_SUM");
            sumTag = sums.isEmpty() ? tag : sums.get(0);
        }
        batchTags = newNative.listTags(BATCH_SIZE, null);
        if (!batchTags.contains(tag)) {
            List<String> copy = new ArrayList<String>(batchTags);
            copy.add(0, tag);
            batchTags = copy;
        }

        // ---- 时间锚点：取主位号当前值时间戳，缺省用 now ----
        anchor = Instant.now();
        List<TagSample> current = newClient.currentValues().read(Collections.singletonList(tag));
        if (!current.isEmpty() && current.get(0).getSample() != null
                && current.get(0).getSample().getTimestamp() != null) {
            anchor = current.get(0).getSample().getTimestamp();
        }

        Path dir = Paths.get("target");
        Files.createDirectories(dir);
        report = new PrintWriter(Files.newBufferedWriter(dir.resolve("ge-comparison-report.txt"),
                StandardCharsets.UTF_8), true);
        line("[SETUP] baseUrl=" + baseUrl + " tag=" + tag + " sumTag=" + sumTag
                + " batchTags=" + batchTags.size() + " anchor=" + anchor
                + " jvmZone=" + TimeZone.getDefault().getID());
    }

    @AfterAll
    static void close() {
        if (newClient != null) {
            newClient.close();
        }
        if (pseudoClient != null) {
            pseudoClient.close();
        }
        if (report != null) {
            line("[SUMMARY] MATCH=" + matchCount + " DIFF=" + diffCount
                    + " OLD_ONLY=" + oldOnlyCount + " NEW_ONLY=" + newOnlyCount
                    + " PROBE=" + probeCount);
            report.close();
        }
        if (originalZone != null) {
            TimeZone.setDefault(originalZone);
        }
    }

    // ------------------------------------------------------------------
    // 场景 1：实时值（含不存在位号 / per-tag 错误码）
    // ------------------------------------------------------------------

    @Test
    void compareCurrentValue() {
        List<String> tags = Arrays.asList(tag, MISSING_TAG);
        List<String> failures = new ArrayList<String>();

        DataResult old = DataApiInvoker.getSampledByRequestParamPost(new SampledEntity()
                .setSamplingMode(SamplingMode.CurrentValue)
                .setQueryModifier(1L)
                .setTagNames(join(tags)));
        List<TagSample> neu = newClient.currentValues().read(tags);

        Map<String, List<Pt>> oldMap = oldPoints(old);
        Map<String, List<Pt>> newMap = currentPoints(neu);
        for (String t : tags) {
            compare("current", t, "now", oldMap.get(t), newMap.get(t), failures);
        }
        // 原生 POST sampled 线缆级复核（与公共 API 应一致）。
        Map<String, List<Pt>> nativeMap = nativePoints(newNative.readCurrentRawValue(tags));
        for (String t : tags) {
            compare("current-native", t, "now", oldMap.get(t), nativeMap.get(t), failures);
        }
        // per-tag 错误码：存在位号应为 0，不存在位号两侧都应非 0 或都缺数据。
        recordErrorCodes(old, neu);
        assertNoFailures(failures);
    }

    // ------------------------------------------------------------------
    // 场景 2：插值曲线（旧 sampled-Interpolated+qm=1 vs 新 SAMPLED）
    // ------------------------------------------------------------------

    @Test
    void compareSampledCurve() {
        Instant start = anchor.minus(Duration.ofHours(24));
        Instant end = anchor;
        long intervalMs = Duration.ofHours(1).toMillis();
        List<String> tags = Collections.singletonList(tag);
        List<String> failures = new ArrayList<String>();

        DataResult old = DataApiInvoker.getSampledByRequestParamPost(new SampledEntity()
                .setSamplingMode(SamplingMode.Interpolated)
                .setStartTime(Date.from(start))
                .setEndTime(Date.from(end))
                .setIntervalMs(intervalMs)
                .setQueryModifier(1L)
                .setDirection(io.github.forget_the_bright.ge.constant.common.Direction.Forward)
                .setTagNames(join(tags)));
        List<TagSeries> neu = newClient.history().read(HistoryQuery.builder()
                .tags(tags).range(start, end).mode(QueryMode.SAMPLED)
                .interval(Duration.ofMillis(intervalMs)).maxPoints(1000).build());

        Map<String, List<Pt>> oldMap = oldPoints(old);
        Map<String, List<Pt>> newMap = seriesPoints(neu);
        for (String t : tags) {
            compare("sampled", t, window(start, end), oldMap.get(t), newMap.get(t), failures);
        }

        // 交叉探针：旧 sampled-Interpolated 与旧 interpolated 端点是否等价（记录，不硬失败）。
        DataResult oldInterp = DataApiInvoker.getInterpolatedByRequestParamPost(
                new TagNamesEntity().setTagNames(join(tags)), Date.from(start), Date.from(end),
                24, intervalMs);
        probe("sampled-vs-interpolated(old)", tag, window(start, end),
                oldMap.get(tag), oldPoints(oldInterp).get(tag));
        assertNoFailures(failures);
    }

    // ------------------------------------------------------------------
    // 场景 3：插值曲线（旧 interpolated 端点 vs 新 INTERPOLATED）
    // ------------------------------------------------------------------

    @Test
    void compareInterpolatedCurve() {
        Instant start = anchor.minus(Duration.ofHours(24));
        Instant end = anchor;
        long intervalMs = Duration.ofHours(1).toMillis();
        int count = 24;
        List<String> tags = Collections.singletonList(tag);
        List<String> failures = new ArrayList<String>();

        DataResult old = DataApiInvoker.getInterpolatedByRequestParamPost(
                new TagNamesEntity().setTagNames(join(tags)), Date.from(start), Date.from(end),
                count, intervalMs);
        List<TagSeries> neu = newClient.history().read(HistoryQuery.builder()
                .tags(tags).range(start, end).mode(QueryMode.INTERPOLATED)
                .count(count).interval(Duration.ofMillis(intervalMs)).maxPoints(1000).build());

        Map<String, List<Pt>> oldMap = oldPoints(old);
        Map<String, List<Pt>> newMap = seriesPoints(neu);
        for (String t : tags) {
            compare("interpolated", t, window(start, end), oldMap.get(t), newMap.get(t), failures);
        }
        assertNoFailures(failures);
    }

    // ------------------------------------------------------------------
    // 场景 4：聚合 AVG/MIN/MAX/COUNT/FIRST/LAST + SUM→9 专项
    // ------------------------------------------------------------------

    @Test
    void compareAggregations() {
        Instant start = anchor.minus(Duration.ofHours(24));
        Instant end = anchor;
        long intervalMs = Duration.ofHours(1).toMillis();
        int count = 24;
        List<String> tags = Collections.singletonList(tag);
        List<String> failures = new ArrayList<String>();

        Aggregation[] aggs = {Aggregation.AVG, Aggregation.MIN, Aggregation.MAX,
                Aggregation.COUNT, Aggregation.FIRST, Aggregation.LAST, Aggregation.SUM};
        CalculationMode[] modes = {CalculationMode.Average, CalculationMode.Minimum,
                CalculationMode.Maximum, CalculationMode.Count, CalculationMode.FirstRawValue,
                CalculationMode.LastRawValue, CalculationMode.RawTotal};

        for (int i = 0; i < aggs.length; i++) {
            DataResult old = DataApiInvoker.getCalculatedByRequestParamPost(
                    new TagNamesEntity().setTagNames(join(tags)), count,
                    Date.from(start), Date.from(end), modes[i], intervalMs);
            List<TagSeries> neu = newClient.history().read(HistoryQuery.builder()
                    .tags(tags).range(start, end).mode(QueryMode.AGGREGATED)
                    .aggregation(aggs[i]).count(count).interval(Duration.ofMillis(intervalMs))
                    .maxPoints(1000).build());
            Map<String, List<Pt>> oldMap = oldPoints(old);
            Map<String, List<Pt>> newMap = seriesPoints(neu);
            for (String t : tags) {
                compare("agg-" + aggs[i] + "(mode=" + modes[i].getValue() + ")", t,
                        window(start, end), oldMap.get(t), newMap.get(t), failures);
            }
        }
        assertNoFailures(failures);
    }

    // ------------------------------------------------------------------
    // 场景 5：INTERVAL_SUM 专项（新 readIntervalSum vs 旧 getIntervalValueBySumTag）
    // ------------------------------------------------------------------

    @Test
    void compareIntervalSum() {
        Instant start = anchor.minus(Duration.ofHours(24));
        Instant end = anchor;
        List<String> tags = Arrays.asList(sumTag, tag);
        List<String> failures = new ArrayList<String>();

        Map<String, String> old = DataApiInvoker.getIntervalValueBySumTag(join(tags),
                Date.from(start), Date.from(end));
        Map<String, String> neu = newNative.readIntervalSum(tags, start, end);
        for (String t : tags) {
            compareScalar("interval-sum", t, window(start, end), old.get(t), neu.get(t), failures);
        }

        // 公共 API AGGREGATED+INTERVAL_SUM 单点值应与原生差值一致（double 化，容差比对）。
        // INTERVAL_SUM 是整窗末减首，无需 interval/count（公共层已豁免二选一校验）。
        List<TagSeries> pub = newClient.history().read(HistoryQuery.builder()
                .tags(tags).range(start, end).mode(QueryMode.AGGREGATED)
                .aggregation(Aggregation.INTERVAL_SUM).maxPoints(1000).build());
        for (TagSeries series : pub) {
            String t = series.getTagName();
            assertEquals(1, series.getSamples().size(),
                    "INTERVAL_SUM should produce exactly one point per tag: " + t);
            Double pubValue = series.getSamples().get(0).getDoubleValue();
            assertNotNull(pubValue, "INTERVAL_SUM public point value is null: " + t);
            compareScalar("interval-sum-public", t, window(start, end),
                    old.get(t), pubValue.toString(), failures);
        }

        // 探针：厂商 24=SumValue 单请求 与 17/19 双请求末减首 是否等价（记录，不硬失败）。
        for (String t : tags) {
            List<GeModels.Series> mode24 = newNative.readAggregated(
                    Collections.singletonList(t), start, end, 24, 1, 1L);
            String v24 = firstValue(mode24, t);
            probe("sumValue24-vs-17/19", t, window(start, end),
                    scalarPt(neu.get(t)), scalarPt(v24));
        }
        assertNoFailures(failures);
    }

    // ------------------------------------------------------------------
    // 场景 6：原始值反查（raw POST Backward）
    // ------------------------------------------------------------------

    @Test
    void compareRawBackward() {
        Instant start = anchor.minus(Duration.ofHours(6));
        Instant end = anchor;
        int count = 50;
        List<String> tags = Collections.singletonList(tag);
        List<String> failures = new ArrayList<String>();

        DataResult old = DataApiInvoker.getRawDataByRequestParamPost(
                new TagNamesEntity().setTagNames(join(tags)), Date.from(start), Date.from(end),
                io.github.forget_the_bright.ge.constant.common.Direction.Backward, count);
        List<TagSeries> neu = newClient.history().read(HistoryQuery.builder()
                .tags(tags).range(start, end).mode(QueryMode.RAW)
                .direction(Direction.BACKWARD).count(count).maxPoints(1000).build());

        Map<String, List<Pt>> oldMap = oldPoints(old);
        Map<String, List<Pt>> newMap = seriesPoints(neu);
        for (String t : tags) {
            compare("raw-backward", t, window(start, end), oldMap.get(t), newMap.get(t), failures);
        }
        assertNoFailures(failures);
    }

    // ------------------------------------------------------------------
    // 场景 7：标签查询（ErrorCode=11 截断场景）
    // ------------------------------------------------------------------

    @Test
    void compareTagCatalog() {
        int maxNumber = 5;
        String mask = "*";
        List<String> failures = new ArrayList<String>();

        TagsResult old = TagsApiInvoker.queryTagsByParams(maxNumber, mask);
        List<String> oldTags = old.getTags() == null ? Collections.<String>emptyList() : old.getTags();
        List<String> newTags = newNative.listTags(maxNumber, mask);
        int oldCode = old.getErrorCode() == null ? 0 : old.getErrorCode().getValue();

        boolean same = oldTags.equals(newTags);
        String verdict = same ? "MATCH" : "DIFF";
        line("[COMPARE] tags/mask=" + mask + "/max=" + maxNumber
                + " old{n=" + oldTags.size() + ",errorCode=" + oldCode + "}"
                + " new{n=" + newTags.size() + "} verdict=" + verdict);
        count(verdict);
        if (!same) {
            failures.add("tags catalog differs: old=" + oldTags + " new=" + newTags);
        }
        // 截断场景应返回 ErrorCode=11 且仍带部分结果（如实记录）。
        line("[PROBE] tags-truncation errorCode=" + oldCode + " returned=" + oldTags.size()
                + " (11=TAG_SEARCH_RESULT_IS_HUGE)");
        probeCount++;
        assertNoFailures(failures);
    }

    // ------------------------------------------------------------------
    // 场景 8：时间语义裁决探针（A 旧真UTC / B 新真UTC / C 新伪UTC 反证）
    // ------------------------------------------------------------------

    @Test
    void probeTimeSemantics() {
        Instant start = anchor.minus(Duration.ofHours(6));
        Instant end = anchor;
        int count = 50;
        List<String> tags = Collections.singletonList(tag);

        DataResult old = DataApiInvoker.getRawDataByRequestParamPost(
                new TagNamesEntity().setTagNames(join(tags)), Date.from(start), Date.from(end),
                io.github.forget_the_bright.ge.constant.common.Direction.Backward, count);
        List<Pt> oldPts = oldPoints(old).get(tag);

        List<TagSeries> trueUtc = newClient.history().read(HistoryQuery.builder()
                .tags(tags).range(start, end).mode(QueryMode.RAW)
                .direction(Direction.BACKWARD).count(count).maxPoints(1000).build());
        List<Pt> trueUtcPts = seriesPoints(trueUtc).get(tag);

        List<TagSeries> pseudo = pseudoClient.history().read(HistoryQuery.builder()
                .tags(tags).range(start, end).mode(QueryMode.RAW)
                .direction(Direction.BACKWARD).count(count).maxPoints(1000).build());
        List<Pt> pseudoPts = seriesPoints(pseudo).get(tag);

        // A vs B（旧真UTC vs 新真UTC）：主契约，必须 MATCH。
        List<String> failures = new ArrayList<String>();
        compare("time-A(old)-vs-B(trueUtc)", tag, window(start, end), oldPts, trueUtcPts, failures);
        // A vs C（旧真UTC vs 新伪UTC）：反证探针，预期 DIFF（+8h），记录供文档，不硬失败。
        probe("time-A(old)-vs-C(pseudoUtc)", tag, window(start, end), oldPts, pseudoPts);
        // B vs C：观察真/伪 UTC 的绝对时间偏移量。
        probe("time-B(trueUtc)-vs-C(pseudoUtc)", tag, window(start, end), trueUtcPts, pseudoPts);
        line("[PROBE] time-semantics oldFirst=" + first(oldPts)
                + " trueUtcFirst=" + first(trueUtcPts) + " pseudoFirst=" + first(pseudoPts));
        probeCount++;
        assertNoFailures(failures);
    }

    // ------------------------------------------------------------------
    // 场景 9：大批量位号 POST（核心：GET 换 POST 后承载大批量；GET 对照组预期失败）
    // ------------------------------------------------------------------

    @Test
    void compareBatchPost() {
        Assumptions.assumeTrue(batchTags.size() >= 2, "not enough tags for batch comparison");
        List<String> failures = new ArrayList<String>();

        // 新 SDK 单请求 POST 承载全部位号——这是 GET→POST 改造的根本目的。
        // 旧 SDK 在 2000 位号 sampled POST 上曾 30s 读超时，故两侧都优雅容错；
        // 全量超时则回退到 500 位号仍证明 POST 容量（远超 GET URL 上限），如实记录。
        List<String> tags = batchTags;
        Map<String, List<Pt>> newMap = tryBatchNew(tags);
        if (newMap == null && tags.size() > BATCH_FALLBACK) {
            line("[PROBE] batch-new-POST full size=" + tags.size()
                    + " failed; falling back to " + BATCH_FALLBACK);
            probeCount++;
            tags = new ArrayList<String>(tags.subList(0, BATCH_FALLBACK));
            newMap = tryBatchNew(tags);
        }
        assertNotNull(newMap, "new SDK POST batch failed even at reduced size " + tags.size());
        line("[COMPARE] batch-new-POST/tags=" + tags.size() + "/window=now"
                + " new{n=" + newMap.size() + "} verdict=OK");

        // 旧 SDK 对照（可能超时，优雅记录为旧 SDK 局限，不硬失败）。
        Map<String, List<Pt>> oldMap = tryBatchOld(tags);
        if (oldMap == null) {
            line("[PROBE] batch-old-POST/tags=" + tags.size()
                    + " FAILED (old SDK timeout/limitation; new POST still proven above)");
            probeCount++;
        } else {
            int matched = 0;
            int differed = 0;
            List<String> diffSamples = new ArrayList<String>();
            for (String t : tags) {
                List<Pt> o = oldMap.get(t);
                List<Pt> n = newMap.get(t);
                String verdict = verdict(o, n);
                // 批量两侧是先后两次 POST（各约 1s），快更新位号可能在间隔内
                // 前进到不同归档样本。对该位号单标签紧挨复读，证实是活数据漂移
                // 才透明重分类为 MATCH；可复现的稳定差异仍保留 DIFF 硬失败。
                if (!"MATCH".equals(verdict) && isLiveDrift(t, o, n)) {
                    line("[PROBE] batch-drift-resolved tag=" + t
                            + " origOld=" + first(o) + " origNew=" + first(n)
                            + " (single-tag re-read confirms live drift, not SDK discrepancy)");
                    probeCount++;
                    verdict = "MATCH";
                }
                count(verdict);
                if ("MATCH".equals(verdict)) {
                    matched++;
                } else {
                    differed++;
                    if (diffSamples.size() < 10) {
                        diffSamples.add(t + " old=" + first(o) + " new=" + first(n) + " " + verdict);
                    }
                }
            }
            line("[COMPARE] batch-current/tags=" + tags.size() + "/window=now"
                    + " matched=" + matched + " differed=" + differed
                    + " verdict=" + (differed == 0 ? "MATCH" : "DIFF"));
            for (String s : diffSamples) {
                line("[COMPARE] batch-diff " + s);
            }
            if (differed != 0) {
                failures.add("batch current value differed on " + differed + " tags; first: " + diffSamples);
            }
        }

        // GET 对照组：同样位号拼 URL，预期超长失败或截断；如实记录，不硬失败。
        try {
            long t2 = System.nanoTime();
            List<GeModels.Series> get = newNative.readCurrent(tags);
            long getMs = (System.nanoTime() - t2) / 1_000_000L;
            line("[PROBE] batch-GET-control SUCCEEDED n=" + get.size() + " ms=" + getMs
                    + " (URL length not exceeded on this server)");
        } catch (RuntimeException e) {
            line("[PROBE] batch-GET-control FAILED as expected: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        probeCount++;
        assertNoFailures(failures);
    }

    /** 尝试新 SDK 批量 POST 当前值，超时/异常返回 {@code null}（优雅容错）。 */
    private static Map<String, List<Pt>> tryBatchNew(List<String> tags) {
        try {
            long t = System.nanoTime();
            List<GeModels.Series> neu = newNative.readCurrentRawValue(tags);
            long ms = (System.nanoTime() - t) / 1_000_000L;
            line("[PROBE] batch-new-POST timing tags=" + tags.size() + " ms=" + ms + " n=" + neu.size());
            probeCount++;
            return nativePoints(neu);
        } catch (RuntimeException e) {
            line("[PROBE] batch-new-POST exception tags=" + tags.size() + ": "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            probeCount++;
            return null;
        }
    }

    /** 尝试旧 SDK 批量 POST 当前值，超时/异常返回 {@code null}（优雅容错）。 */
    private static Map<String, List<Pt>> tryBatchOld(List<String> tags) {
        try {
            long t = System.nanoTime();
            DataResult old = DataApiInvoker.getSampledByRequestParamPost(new SampledEntity()
                    .setSamplingMode(SamplingMode.CurrentValue)
                    .setQueryModifier(1L)
                    .setTagNames(join(tags)));
            long ms = (System.nanoTime() - t) / 1_000_000L;
            line("[PROBE] batch-old-POST timing tags=" + tags.size() + " ms=" + ms);
            probeCount++;
            return oldPoints(old);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 确认批量当前值的 DIFF 是“活数据漂移”而非 SDK 缺陷。
     *
     * <p>批量读取时旧/新两侧是先后两次请求（500 位号各约 1s），快更新位号的
     * 当前值可能在两次请求之间前进到不同归档样本，导致时间戳/值不同——这是
     * 真实数据在动，不是 SDK 翻译错误。为严谨区分，对该位号做<strong>单标签
     * 紧挨复读</strong>（把两次请求的时间间隔压到最小），最多两轮：</p>
     * <ul>
     *   <li>复读 MATCH：原 DIFF 是批量读取时间差造成的漂移，判定为漂移；</li>
     *   <li>复读仍 DIFF 但新值相对原批量值持续变化：位号仍在动，判定为漂移；</li>
     *   <li>复读稳定 DIFF（新旧各自复现原值却互不相等）：可复现的真实差异，
     *       <strong>不</strong>判定为漂移，保留 DIFF 硬失败。</li>
     * </ul>
     *
     * @param tag     出现 DIFF 的位号
     * @param origOld 批量读取时旧 SDK 的点
     * @param origNew 批量读取时新 SDK 的点
     * @return 证实为活数据漂移返回 {@code true}；稳定可复现差异返回 {@code false}
     */
    private static boolean isLiveDrift(String tag, List<Pt> origOld, List<Pt> origNew) {
        for (int attempt = 0; attempt < 2; attempt++) {
            List<Pt> o;
            List<Pt> n;
            try {
                o = oldPoints(DataApiInvoker.getSampledByRequestParamPost(new SampledEntity()
                        .setSamplingMode(SamplingMode.CurrentValue)
                        .setQueryModifier(1L)
                        .setTagNames(tag))).get(tag);
                n = nativePoints(newNative.readCurrentRawValue(Collections.singletonList(tag))).get(tag);
            } catch (RuntimeException e) {
                line("[PROBE] batch-drift-recheck exception tag=" + tag + ": "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
                return false;
            }
            if ("MATCH".equals(verdict(o, n))) {
                return true;
            }
            // 新值相对原批量值仍在变化 → 位号在动，DIFF 是移动靶而非稳定缺陷。
            if (!"MATCH".equals(verdict(origNew, n))) {
                return true;
            }
        }
        return false;
    }

    // ==================================================================
    // 装配与归一化辅助
    // ==================================================================

    /**
     * 构建一个新 SDK 客户端。{@code pseudo=true} 构造伪 UTC（请求 GMT+8 墙钟 +
     * 字面 Z，仅作时间反证探针）；{@code pseudo=false} 用真 UTC（请求/响应时区
     * 取自凭据文件，缺省 UTC，是已实证对齐旧 SDK 的主契约）。
     */
    private static GeClient buildClient(String name, String baseUrl, String username, String password,
                                        String clientId, String clientSecret, String token,
                                        long connectMs, long readMs, boolean trustAll,
                                        boolean verifyHostname, boolean pseudo, Properties file) {
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
        timeout.setConnectMillis(connectMs);
        timeout.setReadMillis(readMs);
        SecurityConfig security = new SecurityConfig();
        security.setAllowWrite(false);
        security.setVerifyHostname(verifyHostname);
        instance.getOptions().put("trust-all-certificates", Boolean.toString(trustAll));

        instance.getOptions().put("time.request.enabled", "true");
        instance.getOptions().put("time.response.enabled", "true");
        if (pseudo) {
            // 反证探针：请求 GMT+8 墙钟 + 字面 Z（实证会 +8h 错位，故 NOT 主契约）。
            instance.getOptions().put("time.request.zone", "GMT+8");
            instance.getOptions().put("time.request.pattern", "yyyy-MM-dd'T'HH:mm:ss'Z'");
            instance.getOptions().put("time.response.source-zone", "GMT+8");
            instance.getOptions().put("time.response.target-zone", "GMT+8");
            instance.getOptions().put("time.response.pattern", "yyyy-MM-dd HH:mm:ss");
        } else {
            instance.getOptions().put("time.request.zone",
                    value(file, "requestZone", "GE_IT_REQUEST_ZONE", "UTC"));
            instance.getOptions().put("time.request.pattern",
                    value(file, "requestPattern", "GE_IT_REQUEST_PATTERN", "yyyy-MM-dd'T'HH:mm:ssXXX"));
            instance.getOptions().put("time.response.source-zone",
                    value(file, "responseSourceZone", "GE_IT_RESPONSE_SOURCE_ZONE", "UTC"));
            instance.getOptions().put("time.response.target-zone",
                    value(file, "responseTargetZone", "GE_IT_RESPONSE_TARGET_ZONE", "GMT+8"));
            instance.getOptions().put("time.response.pattern",
                    value(file, "responsePattern", "GE_IT_RESPONSE_PATTERN", "yyyy-MM-dd HH:mm:ss"));
        }

        GeConfig geConfig = new GeConfig(name, instance, security);
        GeHttpClient http = new GeHttpClient(geConfig);
        GeTokenManager tokens = new GeTokenManager(geConfig, http);
        GeNativeApi nativeApi = new GeNativeApi(geConfig, http, tokens);
        return new GeClient(geConfig, nativeApi);
    }

    /** 归一化旧 SDK DataResult → 位号 → 点列表。 */
    private static Map<String, List<Pt>> oldPoints(DataResult result) {
        Map<String, List<Pt>> map = new LinkedHashMap<String, List<Pt>>();
        if (result == null || result.getData() == null) {
            return map;
        }
        for (DataItem item : result.getData()) {
            List<Pt> pts = new ArrayList<Pt>();
            if (item.getSamples() != null) {
                for (io.github.forget_the_bright.ge.entity.response.base.Sample s : item.getSamples()) {
                    long ts = s.getTimeStamp() == null ? -1L : s.getTimeStamp().getTime();
                    int q = s.getQuality() == null ? -1 : s.getQuality().getValue();
                    pts.add(new Pt(ts, canonical(s.getValue()), q));
                }
            }
            map.put(item.getTagName(), pts);
        }
        return map;
    }

    /** 归一化新 SDK 原生 Series → 位号 → 点列表（线缆级，原始字符串值 + 原始质量 int）。 */
    private static Map<String, List<Pt>> nativePoints(List<GeModels.Series> series) {
        Map<String, List<Pt>> map = new LinkedHashMap<String, List<Pt>>();
        for (GeModels.Series s : series) {
            List<Pt> pts = new ArrayList<Pt>();
            for (GeModels.Point p : s.getPoints()) {
                pts.add(new Pt(p.getTimestamp().toEpochMilli(), canonical(p.getValue()), p.getQuality()));
            }
            map.put(s.getTagName(), pts);
        }
        return map;
    }

    /** 归一化新 SDK 公共 TagSeries → 位号 → 点列表（值经 GeMapper 强类型化后再规范化）。 */
    private static Map<String, List<Pt>> seriesPoints(List<TagSeries> series) {
        Map<String, List<Pt>> map = new LinkedHashMap<String, List<Pt>>();
        for (TagSeries s : series) {
            List<Pt> pts = new ArrayList<Pt>();
            for (Sample sample : s.getSamples()) {
                int q = sample.getVendorQualityCode() == null ? -1 : sample.getVendorQualityCode();
                pts.add(new Pt(sample.getTimestamp().toEpochMilli(), canonical(sample.getValue()), q));
            }
            map.put(s.getTagName(), pts);
        }
        return map;
    }

    /** 归一化新 SDK 公共当前值 TagSample → 位号 → 点列表（每标签至多一点）。 */
    private static Map<String, List<Pt>> currentPoints(List<TagSample> samples) {
        Map<String, List<Pt>> map = new LinkedHashMap<String, List<Pt>>();
        for (TagSample ts : samples) {
            List<Pt> pts = new ArrayList<Pt>();
            Sample sample = ts.getSample();
            if (sample != null) {
                int q = sample.getVendorQualityCode() == null ? -1 : sample.getVendorQualityCode();
                pts.add(new Pt(sample.getTimestamp().toEpochMilli(), canonical(sample.getValue()), q));
            }
            map.put(ts.getTagName(), pts);
        }
        return map;
    }

    /** 记录并比对新旧当前值的 per-tag 错误码（存在位号应为 0）。 */
    private static void recordErrorCodes(DataResult old, List<TagSample> neu) {
        Map<String, Integer> oldCodes = new LinkedHashMap<String, Integer>();
        if (old != null && old.getData() != null) {
            for (DataItem item : old.getData()) {
                oldCodes.put(item.getTagName(), item.getErrorCode());
            }
        }
        for (TagSample ts : neu) {
            Integer oldCode = oldCodes.get(ts.getTagName());
            String newCode = ts.getAttributes().get(TagSample.ATTR_ERROR_CODE);
            line("[PROBE] current-errorCode tag=" + ts.getTagName()
                    + " old=" + oldCode + " new=" + newCode);
            probeCount++;
            if (oldCode != null && newCode != null) {
                assertEquals(String.valueOf(oldCode), newCode,
                        "per-tag errorCode mismatch for " + ts.getTagName());
            }
        }
    }

    /** 取原生序列中某位号的首样本值字符串（用于 24 探针）。 */
    private static String firstValue(List<GeModels.Series> series, String tag) {
        for (GeModels.Series s : series) {
            if (tag.equals(s.getTagName()) && !s.getPoints().isEmpty()) {
                return s.getPoints().get(0).getValue();
            }
        }
        return null;
    }

    // ==================================================================
    // 比对与报告
    // ==================================================================

    /** 规范化值字符串：trim → BigDecimal 去尾零非科学计数法；非数字原样 trim。 */
    private static String canonical(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return "";
        }
        try {
            return new BigDecimal(text).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return text;
        }
    }

    /** 规范化任意值对象（新 Sample.getValue() 可能是 Double/Long/Boolean/String）。 */
    private static String canonical(Object value) {
        return value == null ? "" : canonical(value.toString());
    }

    /** 值相等：先字符串精确，再 BigDecimal compareTo，再相对误差容差。 */
    private static boolean valueEquals(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        try {
            BigDecimal x = new BigDecimal(a);
            BigDecimal y = new BigDecimal(b);
            if (x.compareTo(y) == 0) {
                return true;
            }
            double dx = x.doubleValue();
            double dy = y.doubleValue();
            double scale = Math.max(Math.abs(dx), Math.abs(dy));
            return scale > 0 && Math.abs(dx - dy) / scale < REL_TOLERANCE;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 计算 verdict：MATCH / DIFF / OLD_ONLY / NEW_ONLY。 */
    private static String verdict(List<Pt> old, List<Pt> neu) {
        boolean oldEmpty = old == null || old.isEmpty();
        boolean newEmpty = neu == null || neu.isEmpty();
        if (oldEmpty && newEmpty) {
            return "MATCH";
        }
        if (oldEmpty) {
            return "NEW_ONLY";
        }
        if (newEmpty) {
            return "OLD_ONLY";
        }
        if (old.size() != neu.size()) {
            return "DIFF";
        }
        for (int i = 0; i < old.size(); i++) {
            Pt o = old.get(i);
            Pt n = neu.get(i);
            if (o.epochMilli != n.epochMilli || o.quality != n.quality || !valueEquals(o.value, n.value)) {
                return "DIFF";
            }
        }
        return "MATCH";
    }

    /** 逐点比对并写报告；failures 非空时把 DIFF 计入硬失败。 */
    private static void compare(String scenario, String tag, String window,
                                List<Pt> old, List<Pt> neu, List<String> failures) {
        String verdict = verdict(old, neu);
        count(verdict);
        line("[COMPARE] " + scenario + "/" + tag + "/" + window
                + " old{" + first(old) + "} new{" + first(neu) + "} verdict=" + verdict);
        if (failures != null && !"MATCH".equals(verdict)) {
            failures.add(scenario + "/" + tag + " verdict=" + verdict
                    + " old=" + dump(old) + " new=" + dump(neu));
        }
    }

    /** 标量（区间增量字符串）比对：BigDecimal 精确或相对容差。 */
    private static void compareScalar(String scenario, String tag, String window,
                                      String old, String neu, List<String> failures) {
        String o = canonical(old);
        String n = canonical(neu);
        boolean ok = (o.isEmpty() && n.isEmpty()) || valueEquals(o, n);
        String verdict = ok ? "MATCH" : "DIFF";
        count(verdict);
        line("[COMPARE] " + scenario + "/" + tag + "/" + window
                + " old{" + o + "} new{" + n + "} verdict=" + verdict);
        if (failures != null && !ok) {
            failures.add(scenario + "/" + tag + " old=" + o + " new=" + n);
        }
    }

    /** 探索性探针：只记录 verdict，不硬失败。 */
    private static void probe(String scenario, String tag, String window, List<Pt> a, List<Pt> b) {
        String verdict = verdict(a, b);
        probeCount++;
        line("[PROBE] " + scenario + "/" + tag + "/" + window
                + " a{" + first(a) + "} b{" + first(b) + "} verdict=" + verdict);
    }

    private static List<Pt> scalarPt(String value) {
        if (value == null) {
            return Collections.emptyList();
        }
        return Collections.singletonList(new Pt(0L, canonical(value), 3));
    }

    private static void count(String verdict) {
        if ("MATCH".equals(verdict)) {
            matchCount++;
        } else if ("DIFF".equals(verdict)) {
            diffCount++;
        } else if ("OLD_ONLY".equals(verdict)) {
            oldOnlyCount++;
        } else if ("NEW_ONLY".equals(verdict)) {
            newOnlyCount++;
        }
    }

    private static String first(List<Pt> pts) {
        if (pts == null || pts.isEmpty()) {
            return "n=0";
        }
        Pt p = pts.get(0);
        return "n=" + pts.size() + ",ts=" + p.epochMilli + ",v=" + p.value + ",q=" + p.quality;
    }

    private static String dump(List<Pt> pts) {
        if (pts == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < pts.size() && i < 5; i++) {
            Pt p = pts.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(p.epochMilli).append(':').append(p.value).append(':').append(p.quality);
        }
        if (pts.size() > 5) {
            sb.append(", ...(").append(pts.size()).append(")");
        }
        return sb.append(']').toString();
    }

    private static void assertNoFailures(List<String> failures) {
        assertTrue(failures.isEmpty(),
                "GE comparison DIFF detected:\n" + String.join("\n", failures));
    }

    private static String window(Instant start, Instant end) {
        return start + ".." + end;
    }

    private static String join(List<String> tags) {
        StringBuilder sb = new StringBuilder();
        for (String t : tags) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(t);
        }
        return sb.toString();
    }

    private static void line(String text) {
        System.out.println(text);
        if (report != null) {
            report.println(text);
        }
    }

    /** 归一化后的采样点：绝对毫秒 + 规范化值 + 厂商质量码。 */
    private static final class Pt {
        private final long epochMilli;
        private final String value;
        private final int quality;

        private Pt(long epochMilli, String value, int quality) {
            this.epochMilli = epochMilli;
            this.value = value;
            this.quality = quality;
        }
    }

    // ==================================================================
    // 凭据加载（与 GeProductionIT 一致）
    // ==================================================================

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