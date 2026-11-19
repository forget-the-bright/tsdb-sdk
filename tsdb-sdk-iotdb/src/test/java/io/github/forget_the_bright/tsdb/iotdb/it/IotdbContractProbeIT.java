package io.github.forget_the_bright.tsdb.iotdb.it;

import io.github.forget_the_bright.tsdb.common.config.SecurityConfig;
import io.github.forget_the_bright.tsdb.common.config.TsdbConfig;
import io.github.forget_the_bright.tsdb.common.config.TsdbInstanceConfig;
import io.github.forget_the_bright.tsdb.common.exception.TsdbException;
import io.github.forget_the_bright.tsdb.common.spi.DriverContext;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.github.forget_the_bright.tsdb.iotdb.IotdbClient;
import io.github.forget_the_bright.tsdb.iotdb.IotdbConfig;
import io.github.forget_the_bright.tsdb.iotdb.IotdbDriver;
import io.github.forget_the_bright.tsdb.iotdb.internal.transport.IotdbHttpClient;
import io.github.forget_the_bright.tsdb.iotdb.internal.transport.IotdbJdbcQueryExecutor;
import io.github.forget_the_bright.tsdb.iotdb.internal.transport.IotdbNativeQueryExecutor;
import io.github.forget_the_bright.tsdb.iotdb.internal.transport.IotdbQueryExecutor;
import io.github.forget_the_bright.tsdb.iotdb.internal.query.IotdbQueryService;
import io.github.forget_the_bright.tsdb.iotdb.internal.transport.IotdbRestApi;
import io.github.forget_the_bright.tsdb.iotdb.internal.transport.IotdbRestQueryExecutor;
import io.github.forget_the_bright.tsdb.iotdb.internal.transport.IotdbResultTable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * L2 契约探针：把生产 2.0.11 现场库实测到的线缆形态钉成
 * 可复跑的断言。
 *
 * <p>docs/14 引用的每一条“实测结论”都以本类为准，而不是某次手工 curl 的记忆。
 * 默认不执行，只有显式传入 {@code -Diotdb.it=true} 时才连接现场库，且全部语句只读。</p>
 *
 * <p>断言窗口锚在测试库自身的首个样本时刻上：合成数据是固定区间，按“现在往前推”
 * 取窗会让断言随数据龄期失效。</p>
 *
 * <p><b>P1–P10 是“按数据库批量”改造的入口闸门</b>：这一组钉的是库级表达式
 * （{@code FROM root.<库>} + 库相对列）在三通道上的真实行为，以及批量会不会改变
 * 结果。任何一条前置序列缺失时本类跳过并写明原因，不伪造通过。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
@Tag("iotdb-it")
@EnabledIfSystemProperty(named = "iotdb.it", matches = "true")
class IotdbContractProbeIT {

    private static final String DEVICE = "root.sdk_it.device01";

    /** IT 专用库 {@code root.sdk_it} 的合成序列区间（小时级，168 点）。 */
    private static final String SDK_IT = "root.sdk_it";

    private static final String FLOW = "`device01`.`flow`";

    private static final String TEMPERATURE = "`device01`.`temperature`";

    /** 序列存在但一个点都没写，用来区分“空列”与“不存在”。 */
    private static final String EMPTY_SERIES = "`device02`.`flow`";

    /** 不存在的测点：服务端把整列丢掉，而不是回一列空值。 */
    private static final String MISSING_MEASUREMENT = "`device01`.`nope_zzz`";

    /** 模拟库 {@code root.simdemo}：秒级密集列与 10 倍密集列同库共存，用于复现共享 LIMIT。 */
    private static final String SIMDEMO = "root.simdemo";

    private static final String SIM_DENSE = "`counter_loop`.`cycle_no`";

    private static final String SIM_SPARSE = "`boiler_01`.`temperature`";

    /** 铜陵工厂库：两级设备 + 分钟级，业务位号路径形状的代表。 */
    private static final String TLPLANT = "root.tlplant";

    /** 测试库合成序列的首个样本时刻（2026-11-05T01:00:00Z）。 */
    private static final long FIRST_SAMPLE = 1793840400000L;

    /** {@code root.sdk_it.device01.flow} 的末样本时刻。 */
    private static final long SDK_IT_LAST = 1794441600000L;

    /** {@code root.simdemo} 全部序列的首末样本时刻（411 秒窗口）。 */
    private static final long SIM_FIRST = 1794447424427L;

    private static final long SIM_LAST = 1794447835427L;

    private static final long SECOND = 1000L;

    private static final long HOUR = 3_600_000L;

    private static final long DAY = 86400000L;

    /** 服务端对单次 {@code GROUP BY} 结果行数的硬上限，超出报 code=708。 */
    private static final String ROW_CAP_MESSAGE = "max row size (10000)";

    @Test
    void batchLastDropsMissingMeasurementsAndCarriesPerSeriesTimestamps() {
        IotdbClient client = client();
        try {
            IotdbRestApi api = client.unwrap(IotdbRestApi.class);
            JSONObject response = api.query("SELECT LAST `flow`, `nope_zzz` FROM " + DEVICE);
            assertTrue(response.getString("expressions").contains("Timeseries"),
                    "batch SELECT LAST replies as a Timeseries/Value/DataType dataset, got "
                            + response.getString("expressions"));
            assertEquals(1, response.getJSONArray("values").getJSONArray(0).size(),
                    "a measurement that does not exist is dropped without a placeholder row");
            assertEquals("TEXT", response.getJSONArray("data_types").getString(1),
                    "Value arrives as text, so numeric parsing may not assume a JSON number");
            assertTrue(response.getJSONArray("timestamps").getLongValue(0) > 0L,
                    "batch LAST carries a per-series sample time instead of one shared value");

            IotdbResultTable table = executor(client).query(
                    "SELECT LAST `flow`, `nope_zzz` FROM " + DEVICE);
            assertEquals(1, table.getRows().size());
            assertTrue(table.getRows().get(0).getTimestamp() > 0L,
                    "the parsed row must keep the sample time; hard-coding 0 puts every current value on 1970");
        } finally {
            client.close();
        }
    }

    @Test
    void columnarShowResponsesAreTransposedAndTimeColumnsParsed() {
        IotdbClient client = client();
        try {
            IotdbRestApi api = client.unwrap(IotdbRestApi.class);
            JSONObject show = api.query("SHOW TIMESERIES root.**");
            JSONArray expressions = show.getJSONArray("expressions");
            assertEquals(0, expressions == null ? 0 : expressions.size(),
                    "SHOW replies through column_names");
            assertTrue(show.getJSONArray("column_names").size() > 1);
            assertTrue(show.getJSONArray("values").getJSONArray(0).size() > 1,
                    "values is column-major, so its first array is the whole Timeseries column");

            IotdbResultTable catalog = executor(client).query("SHOW TIMESERIES root.**");
            assertFalse(catalog.getRows().isEmpty());
            assertTrue(catalog.getColumns().contains("Timeseries"));

            IotdbResultTable window = executor(client).query(
                    "SELECT `flow` FROM " + DEVICE + " ORDER BY TIME ASC LIMIT 3");
            assertEquals(3, window.getRows().size());
            long previous = -1;
            for (IotdbResultTable.Row row : window.getRows()) {
                assertTrue(row.getTimestamp() > 0L, "the Time column must be resolved, not left at 0");
                assertTrue(row.getTimestamp() >= previous, "rows keep the requested order");
                previous = row.getTimestamp();
            }
        } finally {
            client.close();
        }
    }

    @Test
    void intervalLiteralsAreAcceptedWithinTheServerRowCap() {
        IotdbClient client = client();
        try {
            IotdbRestApi api = client.unwrap(IotdbRestApi.class);
            String[][] literals = {
                    {"5m", String.valueOf(7 * DAY)},
                    {"30s", String.valueOf(2 * DAY)},
                    {"1h", String.valueOf(7 * DAY)},
                    {"1d", String.valueOf(7 * DAY)},
                    {"300000ms", String.valueOf(7 * DAY)},
                    {"250ms", String.valueOf(2 * SECOND)},
                    {"1mo", String.valueOf(60 * DAY)}
            };
            for (String[] probe : literals) {
                long start = FIRST_SAMPLE - Long.parseLong(probe[1]) / 2;
                JSONObject response = api.query("SELECT LAST_VALUE(`flow`) FROM " + DEVICE
                        + " GROUP BY ([" + start + ", " + (start + Long.parseLong(probe[1])) + "),"
                        + probe[0] + ")");
                assertFalse(response.getJSONArray("values").getJSONArray(0).isEmpty(),
                        "interval literal " + probe[0] + " must produce buckets");
            }
        } finally {
            client.close();
        }
    }

    @Test
    void bareMillisecondIntervalIsRejectedAndWideGridsHitTheRowCap() {
        IotdbClient client = client();
        try {
            IotdbRestApi api = client.unwrap(IotdbRestApi.class);
            TsdbException parseFailure = assertThrows(TsdbException.class, () -> api.query(
                    "SELECT LAST_VALUE(`flow`) FROM " + DEVICE + " GROUP BY ([" + FIRST_SAMPLE + ", "
                            + (FIRST_SAMPLE + DAY) + "), 86400000)"));
            assertTrue(String.valueOf(parseFailure.getMessage()).contains("no viable alternative"),
                    "bare millisecond counts are rejected by the parser, so the driver must emit units, got "
                            + parseFailure.getMessage());

            TsdbException capFailure = assertThrows(TsdbException.class, () -> api.query(
                    "SELECT LAST_VALUE(`flow`) FROM " + DEVICE + " GROUP BY ([" + FIRST_SAMPLE + ", "
                            + (FIRST_SAMPLE + 7 * DAY) + "),30s)"));
            assertTrue(String.valueOf(capFailure.getMessage()).contains(ROW_CAP_MESSAGE),
                    "a 30s grid over seven days exceeds the server row cap and must fail loudly, got "
                            + capFailure.getMessage());
        } finally {
            client.close();
        }
    }

    @Test
    void nonAsciiIdentifiersSurviveBecauseTheClientDeclaresUtf8Charset() {
        IotdbClient client = client();
        try {
            IotdbRestApi api = client.unwrap(IotdbRestApi.class);
            JSONObject response = api.query("SELECT `flow` FROM root.sdk_it.`设备01` LIMIT 1");
            JSONArray values = response.getJSONArray("values");
            assertEquals(0, values == null ? 0 : values.size(),
                    "a non-ASCII path that does not exist must come back empty rather than corrupt");
        } finally {
            client.close();
        }
    }

    @Test
    void pingIsServedAtServerRootWhileTheApiPrefixHasNone() {
        IotdbClient client = client();
        try {
            IotdbConfig config = client.unwrap(IotdbConfig.class);
            String body = client.unwrap(IotdbRestApi.class).ping();
            assertTrue(String.valueOf(body).contains("SUCCESS_STATUS"),
                    "the driver probes GET {base}/ping, got " + body);

            TsdbException failure = assertThrows(TsdbException.class,
                    () -> new IotdbHttpClient(config).post(config.apiUrl("/ping"), "{}"));
            assertTrue(String.valueOf(failure.getMessage()).contains("404"),
                    "REST v2 has no /ping of its own, got " + failure.getMessage());
            assertEquals("2.0.11", client.unwrap(IotdbQueryService.class).serverVersion());
        } finally {
            client.close();
        }
    }

    /**
     * P1：库级 {@code SELECT LAST} 一条语句跨设备，并丢弃无数据与不存在的序列。
     *
     * <p>这是“按数据库分组批量”的立足点：一次往返取回同库多个设备。三通道必须给出
     * 同一个结果集，否则 {@code query-batch-scope: database} 在现网就是通道相关的。</p>
     */
    @ParameterizedTest
    @ValueSource(strings = {"rest", "native", "jdbc"})
    void databaseLevelLastMixesDevicesAndDropsSeriesWithoutData(String transport) {
        IotdbQueryExecutor executor = executor(transport);
        try {
            assumeSeeded(executor, "SELECT LAST " + FLOW + " FROM " + SDK_IT, transport);
            IotdbResultTable table = executor.query("SELECT LAST " + FLOW + ", " + TEMPERATURE + ", "
                    + EMPTY_SERIES + ", " + MISSING_MEASUREMENT + " FROM root.`sdk_it`");
            dump(transport + " P1 database-level LAST", table);

            assertEquals(2, table.getRows().size(),
                    "an existing series with no samples and a measurement that does not exist must both be "
                            + "dropped from a database-level SELECT LAST, got " + describe(table));
            Set<String> names = textValues(table);
            assertTrue(names.contains(SDK_IT + ".device01.flow"), "flow missing from " + describe(table));
            assertTrue(names.contains(SDK_IT + ".device01.temperature"),
                    "a second device of the same database must be served by the same statement: " + describe(table));
            assertFalse(names.contains(SDK_IT + ".device02.flow"),
                    "the empty series must be dropped rather than answered with a null row: " + describe(table));
        } finally {
            executor.close();
        }
    }

    /**
     * P2：库相对表达式的四种引号形态必须等价。
     *
     * <p>业务侧写好的位号串可能全节点加引号、只给测点加引号（用户给的样例
     * {@code RL.FCS0107.`TI-1071815_PV`}），也可能完全没有引号。服务端对四种都接受，
     * 驱动的“解析后规范化重发”才不是猜的。</p>
     */
    @Test
    void everyQuotingShapeOfADatabaseRelativeLastExpressionIsAccepted() {
        IotdbQueryExecutor executor = executor("rest");
        try {
            String[][] shapes = {
                    {"`device01`.`flow`", SDK_IT + ".device01.flow"},
                    {"device01.`flow`", SDK_IT + ".device01.flow"},
                    {"device01.flow", SDK_IT + ".device01.flow"},
                    {"casting1.n01.`temp`", TLPLANT + ".casting1.n01.temp"}
            };
            for (String[] shape : shapes) {
                String database = shape[1].startsWith(TLPLANT) ? TLPLANT : SDK_IT;
                assumeSeeded(executor, "SELECT LAST " + shape[0] + " FROM " + database, "rest");
                IotdbResultTable table = executor.query("SELECT LAST " + shape[0] + " FROM " + database);
                assertEquals(1, table.getRows().size(),
                        "quoting shape " + shape[0] + " must answer one row, got " + describe(table));
                assertTrue(textValues(table).contains(shape[1]),
                        "quoting shape " + shape[0] + " resolved to the wrong series: " + describe(table));
            }
        } finally {
            executor.close();
        }
    }

    /**
     * P3：库级 {@code GROUP BY} 多列的列集合与 {@code FILL} 口径。
     *
     * <p>钉住两件事：① 存在的序列即使窗口内无数据也<b>保留整列空值</b>，所以按列下标
     * 读结果是安全的；② {@code FILL(PREVIOUS)} 按列独立填充，不会把相邻列的值串过来。
     * 这两条合起来决定 SAMPLED/AGGREGATED 族可以整库一条 SQL。</p>
     */
    @Test
    void groupedGridKeepsEveryColumnAndFillsEachColumnOnItsOwn() {
        IotdbQueryExecutor executor = executor("rest");
        try {
            assumeSeeded(executor, "SELECT LAST " + FLOW + " FROM " + SDK_IT, "rest");
            long end = FIRST_SAMPLE + HOUR;
            IotdbResultTable table = executor.query("SELECT last_value(" + FLOW + "), last_value("
                    + TEMPERATURE + "), last_value(" + EMPTY_SERIES + ") FROM root.`sdk_it`"
                    + " WHERE time >= " + FIRST_SAMPLE + " AND time < " + end
                    + " GROUP BY ([" + FIRST_SAMPLE + ", " + end + "),30s) FILL(PREVIOUS)");
            dump("P3 grouped grid", table);

            assertEquals(120, table.getRows().size(),
                    "rows follow the bucket count, not buckets x columns, so a 30s grid over one hour is 120 rows");
            assertEquals(3, table.getColumns().size(),
                    "an existing series without samples keeps its column instead of being dropped: "
                            + table.getColumns());
            assertEquals(120, nonNull(table, 0), "FILL(PREVIOUS) must carry the hourly value across the grid");
            assertEquals(120, nonNull(table, 1), "the neighbouring column must not be filled from column 0");
            assertEquals(0, nonNull(table, 2),
                    "a series with no data at all must stay empty instead of borrowing a value from another column");
        } finally {
            executor.close();
        }
    }

    /**
     * P3b：{@code GROUP BY} 的 10 000 行上限按<b>桶数</b>算，与列数无关。
     *
     * <p>批量列不会让行数预算变紧，因此加宽一次请求的列数是安全的；反过来，桶数一旦
     * 越界，多列与单列同样被 {@code code=708} 拒绝。</p>
     */
    @Test
    void groupedGridRowCapCountsBucketsNotColumns() {
        IotdbClient client = client();
        try {
            IotdbRestApi api = client.unwrap(IotdbRestApi.class);
            long end = FIRST_SAMPLE + 7 * DAY;
            TsdbException failure = assertThrows(TsdbException.class, () -> api.query(
                    "SELECT last_value(" + FLOW + "), last_value(" + TEMPERATURE + "), last_value("
                            + EMPTY_SERIES + ") FROM root.`sdk_it` GROUP BY ([" + FIRST_SAMPLE + ", " + end
                            + "),30s)"));
            assertTrue(String.valueOf(failure.getMessage()).contains(ROW_CAP_MESSAGE),
                    "three columns over the same over-wide grid must hit the same bucket cap, got "
                            + failure.getMessage());
        } finally {
            client.close();
        }
    }

    /**
     * P4：聚合列的列名在三通道上到底长什么样，以及列序是否等于请求序。
     *
     * <p>{@code IotdbSql.intervalSum} 的注释早就写明“不许按列名匹配”，因为各通道的
     * 函数列名大小写形式不同。本探针把三通道的实际列名打出来，并按列下标逐桶与单列
     * 查询比对——按位对齐只有在列序确实等于请求序时才成立。</p>
     */
    @ParameterizedTest
    @ValueSource(strings = {"rest", "native", "jdbc"})
    void aggregateColumnOrderFollowsTheRequestOrderOnEveryTransport(String transport) {
        IotdbQueryExecutor executor = executor(transport);
        try {
            assumeSeeded(executor, "SELECT LAST " + FLOW + " FROM " + SDK_IT, transport);
            long end = FIRST_SAMPLE + HOUR;
            String grid = " FROM root.`sdk_it` WHERE time >= " + FIRST_SAMPLE + " AND time < " + end
                    + " GROUP BY ([" + FIRST_SAMPLE + ", " + end + "),30m)";
            IotdbResultTable batch = executor.query("SELECT last_value(" + FLOW + "), last_value("
                    + TEMPERATURE + ")" + grid);
            IotdbResultTable singleFlow = executor.query("SELECT last_value(" + FLOW + ")" + grid);
            IotdbResultTable singleTemperature = executor.query("SELECT last_value(" + TEMPERATURE + ")" + grid);
            dump(transport + " P4 aggregate labels", batch);

            assertEquals(2, batch.getColumns().size(),
                    "two aggregated columns must come back as two columns, got " + batch.getColumns());
            assertEquals(singleFlow.getRows().size(), batch.getRows().size(),
                    "column count must not change the bucket count for transport " + transport);
            for (int i = 0; i < batch.getRows().size(); i++) {
                assertEquals(expected(singleFlow, i), expected(batch, i),
                        "column 0 must be the first requested series for transport " + transport
                                + " at bucket " + i + ", labels=" + batch.getColumns());
                assertEquals(expected(singleTemperature, i), expected(batch, i, 1),
                        "column 1 must be the second requested series for transport " + transport
                                + " at bucket " + i + ", labels=" + batch.getColumns());
                assertEquals(singleFlow.getRows().get(i).getTimestamp(), batch.getRows().get(i).getTimestamp(),
                        "the grid timestamps must be the same as the single-column grid for transport "
                                + transport);
            }
        } finally {
            executor.close();
        }
    }

    /**
     * P5：差分基线的批量形态——同一条 {@code LAST_VALUE ... WHERE time < start} 里，
     * 空序列保留列、不存在的路径整列消失。
     *
     * <p>这是本轮改造的分岔口。若“没有历史”也整列丢掉，按列下标回填就会串位；
     * 实测结论是：序列存在（即使无点）保留空列，只有<b>路径不存在</b>才丢列。
     * 因此批量基线的列数护栏必须以“请求路径全部存在”为前提，一旦列数少于发射数，
     * 只能按列名定位或整块退回逐位号，绝不能就近匹配。</p>
     */
    @Test
    void batchedBaselineKeepsTheEmptyColumnButDropsAnUnknownPath() {
        IotdbQueryExecutor executor = executor("rest");
        try {
            assumeSeeded(executor, "SELECT LAST " + FLOW + " FROM " + SDK_IT, "rest");
            long anchor = FIRST_SAMPLE + DAY;

            IotdbResultTable withEmpty = executor.query("SELECT LAST_VALUE(" + FLOW + "), LAST_VALUE("
                    + EMPTY_SERIES + ") FROM root.`sdk_it` WHERE time < " + anchor);
            dump("P5a baseline with an existing but empty series", withEmpty);
            assertEquals(2, withEmpty.getColumns().size(),
                    "an existing series without history keeps its column: " + withEmpty.getColumns());
            assertEquals(1, withEmpty.getRows().size(), "a baseline without GROUP BY is one logical row");
            assertNotNull(value(withEmpty, 0, 0), "the anchored column must carry its value: "
                    + describe(withEmpty));
            assertEquals(null, value(withEmpty, 0, 1),
                    "the column without history must read as null rather than as the neighbour's value");

            IotdbResultTable withMissing = executor.query("SELECT LAST_VALUE(" + FLOW + "), LAST_VALUE("
                    + MISSING_MEASUREMENT + ") FROM root.`sdk_it` WHERE time < " + anchor);
            dump("P5b baseline with an unknown measurement", withMissing);
            assertEquals(1, withMissing.getColumns().size(),
                    "an unknown path drops its whole column, so positional alignment is only sound when every "
                            + "requested path exists: " + withMissing.getColumns());
            assertTrue(String.valueOf(withMissing.getColumns().get(0)).contains(SDK_IT + ".device01.flow"),
                    "the surviving label must identify the series it belongs to, got " + withMissing.getColumns());
        } finally {
            executor.close();
        }
    }

    /**
     * P6：无 {@code GROUP BY} 的成对 {@code FIRST_VALUE}/{@code LAST_VALUE} 只回一个逻辑行。
     *
     * <p>{@code timestamps} 为空、每个值列长度为 1，因此 INTERVAL_SUM 整批一条 SQL
     * 可行；但同样受 P5 的“未知路径丢列”约束。</p>
     */
    @Test
    void pairAggregatesWithoutGroupByReturnASingleLogicalRow() {
        IotdbQueryExecutor executor = executor("rest");
        try {
            assumeSeeded(executor, "SELECT LAST " + FLOW + " FROM " + SDK_IT, "rest");
            long end = FIRST_SAMPLE + HOUR;
            IotdbResultTable table = executor.query("SELECT FIRST_VALUE(" + FLOW + "), LAST_VALUE(" + FLOW
                    + "), FIRST_VALUE(" + EMPTY_SERIES + "), LAST_VALUE(" + EMPTY_SERIES + "), FIRST_VALUE("
                    + TEMPERATURE + "), LAST_VALUE(" + TEMPERATURE + ") FROM root.`sdk_it` WHERE time >= "
                    + FIRST_SAMPLE + " AND time < " + end);
            dump("P6 paired aggregates", table);

            assertEquals(6, table.getColumns().size(), "six emitted columns must come back as six: "
                    + table.getColumns());
            assertEquals(1, table.getRows().size(), "no GROUP BY means one logical row however many columns");
            assertEquals(0L, table.getRows().get(0).getTimestamp(),
                    "an aggregate without a time column must not invent a timestamp");
            Double first = value(table, 0, 0);
            Double last = value(table, 0, 1);
            assertNotNull(first);
            assertNotNull(last);
            assertEquals(0.0, last - first, 1e-9,
                    "the one-hour window holds a single sample, so the whole-window increment is 0");
            assertEquals(null, value(table, 0, 2), "the empty series keeps its slot as null");
        } finally {
            executor.close();
        }
    }

    /**
     * P7：一条 {@code FROM root} 跨库 {@code SELECT LAST} 可以带 200 个表达式。
     *
     * <p>同时钉住 M2 的两个后果：响应行序不等于请求顺序、无数据序列被整行丢弃。
     * 只要这两条成立，批量最新值就必须按名回填，任何“按返回顺序对齐入参”的实现
     * 都会把一个位号的值挂到另一个位号名下。</p>
     */
    @Test
    void crossDatabaseLastAcceptsTwoHundredExpressionsInOneStatement() {
        IotdbClient client = client();
        try {
            IotdbRestApi api = client.unwrap(IotdbRestApi.class);
            List<String> existing = absolutePaths(api);
            assumeTrue(existing.size() >= 40, "this environment only exposes " + existing.size()
                    + " series; the 200-expression probe was not executed");

            List<String> expressions = new ArrayList<String>();
            for (String path : existing) {
                expressions.add(relative(path));
            }
            while (expressions.size() < 200) {
                expressions.add("`ghost" + expressions.size() + "`.`measurement`");
            }
            String sql = "SELECT LAST " + join(expressions) + " FROM root";
            IotdbResultTable table = executor(client).query(sql);
            System.out.println("P7 sqlBytes=" + sql.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    + " requested=" + expressions.size() + " rows=" + table.getRows().size());
            dump("P7 cross-database LAST", table);

            Set<String> known = new LinkedHashSet<String>(existing);
            Set<String> answered = answeredSeries(table, known);
            assertFalse(answered.isEmpty(),
                    "the Timeseries column must be identifiable among " + table.getColumns());
            assertTrue(table.getRows().size() < expressions.size(),
                    "series without data must be dropped instead of padding the response");
            assertEquals(table.getRows().size(), answered.size(),
                    "every answered row must name exactly one known series: " + describe(table));

            int probeCount = 0;
            for (String path : existing) {
                if (probeCount++ >= 24) {
                    break;
                }
                IotdbResultTable single = executor(client).query(
                        "SELECT LAST " + relative(path) + " FROM root");
                Set<String> singleNames = answeredSeries(single, known);
                boolean hasData = !singleNames.isEmpty();
                assertEquals(hasData, answered.contains(path),
                        path + " answered " + (hasData ? "on its own" : "nothing")
                                + " but the batch says otherwise; per-series answers and the batch must agree");
            }
            System.out.println("P7 answeredInRequestOrder="
                    + new ArrayList<String>(answered).equals(ordered(existing, answered)));
        } finally {
            client.close();
        }
    }

    /**
     * P8：多列 RAW 的 {@code LIMIT} 是<b>合并时间戳行数</b>预算，不是每位号点数。
     *
     * <p>同库共存一列 10 Hz、一列 1 Hz：批量 {@code LIMIT 10} 只给稀疏列 1 个点，
     * 而逐位号同样的 {@code LIMIT 10} 给满 10 个点。RAW 直接批量会<b>改变结果</b>，
     * 因此“全族按库批量”对 RAW 只能以“批量 + 逐位号补采”的形式成立；这条探针就是
     * 补采逻辑的书面依据。</p>
     */
    @Test
    void multiColumnRawSharesOneLimitAcrossColumnsAndStarvesSparseSeries() {
        IotdbQueryExecutor executor = executor("rest");
        try {
            assumeSeeded(executor, "SELECT LAST " + SIM_SPARSE + " FROM " + SIMDEMO, "rest");
            long end = SIM_LAST + SECOND;
            String where = " WHERE time >= " + SIM_FIRST + " AND time < " + end + " ORDER BY TIME ASC LIMIT 10";
            IotdbResultTable batch = executor.query("SELECT " + SIM_DENSE + ", " + SIM_SPARSE
                    + " FROM root.`simdemo`" + where);
            IotdbResultTable single = executor.query("SELECT " + SIM_SPARSE + " FROM root.`simdemo`" + where);
            dump("P8a multi-column raw", batch);

            assertEquals(10, batch.getRows().size(), "the shared LIMIT is a union-row budget");
            assertEquals(2, batch.getColumns().size(), "both existing columns must be projected");
            assertEquals(10, nonNull(batch, 0), "the dense column consumes the whole budget");
            assertEquals(10, nonNull(single, 0), "the sparse column answers all 10 points on its own");
            assertTrue(nonNull(batch, 1) < nonNull(single, 0),
                    "batching must starve the sparse column (that is why RAW needs a completion pass); batch="
                            + nonNull(batch, 1) + " single=" + nonNull(single, 0));
        } finally {
            executor.close();
        }
    }

    /**
     * P8b：RAW 的列集合在“路径不存在”和“存在但本窗无数据”两种情况下表现不同。
     *
     * <p>不存在 → 整列消失（下标会串）；存在但空 → 保留 null 列。与 P5 一致，
     * 这条决定了 RAW 批量也必须有列数护栏。</p>
     */
    @Test
    void rawProjectionDropsUnknownColumnsAndKeepsEmptyOnes() {
        IotdbQueryExecutor executor = executor("rest");
        try {
            assumeSeeded(executor, "SELECT LAST " + SIM_SPARSE + " FROM " + SIMDEMO, "rest");
            long end = SIM_LAST + SECOND;
            IotdbResultTable missing = executor.query("SELECT " + SIM_DENSE + ", `boiler_01`.`nope_zzz`"
                    + " FROM root.`simdemo` WHERE time >= " + SIM_FIRST + " AND time < " + end
                    + " ORDER BY TIME ASC LIMIT 10");
            assertEquals(1, missing.getColumns().size(),
                    "an unknown path drops its column even in a raw projection: " + missing.getColumns());

            long sdkEnd = FIRST_SAMPLE + HOUR;
            IotdbResultTable empty = executor.query("SELECT " + FLOW + ", " + EMPTY_SERIES
                    + " FROM root.`sdk_it` WHERE time >= " + FIRST_SAMPLE + " AND time < " + sdkEnd
                    + " ORDER BY TIME ASC LIMIT 10");
            dump("P8b raw with an existing but empty series", empty);
            assertEquals(2, empty.getColumns().size(),
                    "an existing empty series keeps its column: " + empty.getColumns());
            assertEquals(1, empty.getRows().size(),
                    "rows below the LIMIT mean the window is exhausted, which is the completion-pass trigger");
            assertEquals(null, value(empty, 0, 1), "the empty column must read as null");
        } finally {
            executor.close();
        }
    }

    /**
     * P9：多列 {@code GROUP BY} 配 {@code ORDER BY TIME DESC LIMIT} 仍然按桶截断。
     *
     * <p>AGGREGATED 模式带方向与 {@code maxPoints} 时，批量不改变裁剪口径；时间戳
     * 必须保持降序，否则报表按索引取点会整体倒置。</p>
     */
    @Test
    void groupedGridHonoursDescendingOrderAndLimitWithSeveralColumns() {
        IotdbQueryExecutor executor = executor("rest");
        try {
            assumeSeeded(executor, "SELECT LAST " + FLOW + " FROM " + SDK_IT, "rest");
            long end = SDK_IT_LAST + 1L;
            IotdbResultTable table = executor.query("SELECT last_value(" + FLOW + "), last_value("
                    + TEMPERATURE + ") FROM root.`sdk_it` WHERE time >= " + FIRST_SAMPLE + " AND time < "
                    + end + " GROUP BY ([" + FIRST_SAMPLE + ", " + end + "),1h) ORDER BY TIME DESC LIMIT 3");
            dump("P9 descending grid", table);

            assertEquals(3, table.getRows().size(), "LIMIT applies to buckets, not to buckets x columns");
            assertEquals(2, table.getColumns().size(), "both columns must survive the reordering");
            for (int i = 1; i < table.getRows().size(); i++) {
                assertTrue(table.getRows().get(i - 1).getTimestamp() > table.getRows().get(i).getTimestamp(),
                        "ORDER BY TIME DESC must be preserved, got " + timestamps(table));
                assertNotNull(value(table, i, 0), "column 0 must be filled at every bucket");
                assertNotNull(value(table, i, 1), "column 1 must be filled at every bucket");
            }
        } finally {
            executor.close();
        }
    }

    /**
     * P10：跨库单语句（{@code FROM root}）在原生与 JDBC 通道同样可用。
     *
     * <p>{@code query-batch-scope: root} 只有在三通道都成立时才是可交付的配置；
     * 若某一通道不支持，本探针会失败并给出真实原因，而不是让默认配置只在 REST 上成立。</p>
     */
    @ParameterizedTest
    @ValueSource(strings = {"rest", "native", "jdbc"})
    void crossDatabaseSingleStatementReachesEveryTransport(String transport) {
        IotdbConfig restConfig = config("rest");
        IotdbRestApi api = new IotdbRestApi(restConfig, new IotdbHttpClient(restConfig));
        IotdbQueryExecutor executor = executor(transport);
        IotdbQueryExecutor referenceExecutor = executor("rest");
        try {
            assumeSeeded(executor, "SELECT LAST " + FLOW + " FROM " + SDK_IT, transport);
            List<String> existing = absolutePaths(api);
            assumeTrue(!existing.isEmpty(), "SHOW TIMESERIES answered nothing, probe not executed");
            List<String> expressions = new ArrayList<String>();
            for (String path : existing) {
                expressions.add(relative(path));
            }
            String sql = "SELECT LAST " + join(expressions) + " FROM root";

            Set<String> known = new LinkedHashSet<String>(existing);
            Set<String> reference = answeredSeries(referenceExecutor.query(sql), known);
            assertFalse(reference.isEmpty(), "REST did not answer the cross-database statement at all");

            IotdbResultTable table = executor.query(sql);
            Set<String> answered = answeredSeries(table, known);
            System.out.println("P10 transport=" + transport + " expressions=" + expressions.size()
                    + " rows=" + table.getRows().size() + " restRows=" + reference.size()
                    + " columns=" + table.getColumns());
            assertEquals(reference, answered,
                    "transport " + transport + " answered a different set of series than REST for the same "
                            + "cross-database statement, so query-batch-scope: root is not transport "
                            + "independent. columns=" + table.getColumns() + " rows=" + table.getRows().size());
        } finally {
            executor.close();
            referenceExecutor.close();
        }
    }

    /**
     * 打印结果表形态，让“实测结论”留下原始证据。
     *
     * @param title 标题
     * @param table 结果表
     */
    private static void dump(String title, IotdbResultTable table) {
        System.out.println("== " + title);
        System.out.println("   columns=" + table.getColumns());
        System.out.println("   rows=" + table.getRows().size() + " timestamps=" + timestamps(table));
        for (int i = 0; i < Math.min(3, table.getRows().size()); i++) {
            IotdbResultTable.Row row = table.getRows().get(i);
            StringBuilder values = new StringBuilder();
            for (Object value : row.getValues()) {
                if (values.length() > 0) {
                    values.append(" | ");
                }
                values.append(value == null ? "null" : value.getClass().getSimpleName() + ":" + value);
            }
            System.out.println("   row" + i + " ts=" + row.getTimestamp() + " " + values);
        }
    }

    private static String timestamps(IotdbResultTable table) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < table.getRows().size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(table.getRows().get(i).getTimestamp());
            if (i >= 5) {
                builder.append(" ...");
                break;
            }
        }
        return builder.append(']').toString();
    }

    private static String describe(IotdbResultTable table) {
        return "columns=" + table.getColumns() + " rows=" + table.getRows().size()
                + " " + timestamps(table);
    }

    private static Set<String> textValues(IotdbResultTable table) {
        Set<String> values = new LinkedHashSet<String>();
        for (IotdbResultTable.Row row : table.getRows()) {
            for (Object value : row.getValues()) {
                if (value != null) {
                    values.add(String.valueOf(value).replace("`", "").trim());
                }
            }
        }
        return values;
    }

    private static int nonNull(IotdbResultTable table, int column) {
        int count = 0;
        for (IotdbResultTable.Row row : table.getRows()) {
            if (row.getValues().length > column && row.getValues()[column] != null) {
                count++;
            }
        }
        return count;
    }

    private static Double value(IotdbResultTable table, int row, int column) {
        if (row >= table.getRows().size()) {
            return null;
        }
        Object[] values = table.getRows().get(row).getValues();
        if (column >= values.length || values[column] == null) {
            return null;
        }
        Object value = values[column];
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String expected(IotdbResultTable table, int row) {
        return expected(table, row, 0);
    }

    private static String expected(IotdbResultTable table, int row, int column) {
        Double value = value(table, row, column);
        return value == null ? "null" : String.valueOf(Math.round(value * 1e6) / 1e6);
    }

    /**
     * 展开当前环境的绝对路径集合，供跨库探针使用。
     *
     * @param api REST 入口
     * @return 有序、去重的绝对路径列表
     */
    private static List<String> absolutePaths(IotdbRestApi api) {
        JSONObject show = api.query("SHOW TIMESERIES root.**");
        JSONArray columns = show.getJSONArray("column_names");
        int index = -1;
        for (int i = 0; i < columns.size(); i++) {
            if ("Timeseries".equals(columns.getString(i))) {
                index = i;
            }
        }
        assertTrue(index >= 0, "SHOW TIMESERIES has no Timeseries column: " + columns);
        List<String> paths = new ArrayList<String>();
        Set<String> seen = new LinkedHashSet<String>();
        for (Object node : show.getJSONArray("values").getJSONArray(index)) {
            String path = String.valueOf(node).replace("`", "");
            if (seen.add(path)) {
                paths.add(path);
            }
        }
        return paths;
    }

    /**
     * 把绝对路径改写为“去掉 {@code root} 锚点、逐节点加引号”的库相对表达式。
     *
     * <p>探针有意自己拼 SQL，不走 {@code IotdbSql}，这样钉住的是线缆形态而不是生成器
     * 的自洽。</p>
     *
     * @param absolutePath 形如 {@code root.db.device.measurement}
     * @return 形如 {@code `db`.`device`.`measurement`}
     */
    private static String relative(String absolutePath) {
        String[] nodes = absolutePath.split("\\.");
        StringBuilder builder = new StringBuilder();
        for (int i = 1; i < nodes.length; i++) {
            if (i > 1) {
                builder.append('.');
            }
            builder.append('`').append(nodes[i]).append('`');
        }
        return builder.toString();
    }

    private static String join(List<String> expressions) {
        StringBuilder builder = new StringBuilder();
        for (String expression : expressions) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(expression);
        }
        return builder.toString();
    }

    /**
     * 找出结果表里“整列都是已知序列绝对路径”的那一列，并把它们取出来。
     *
     * <p>三通道给这一列起的名字不一样（REST 是 {@code Timeseries}，原生与 JDBC 可能
     * 是别的形式），所以探针按<b>内容</b>识别而不是按列名，取不到就返回空集合让调用方
     * 的断言失败并打印真实列名，而不是猜一个下标。</p>
     *
     * @param table  结果表
     * @param known  当前环境已知的绝对路径集合
     * @return 该列的值集合；无法识别时为空集合
     */
    private static Set<String> answeredSeries(IotdbResultTable table, Set<String> known) {
        for (int column = 0; column < table.getColumns().size(); column++) {
            Set<String> candidates = new LinkedHashSet<String>();
            boolean identified = !table.getRows().isEmpty();
            for (IotdbResultTable.Row row : table.getRows()) {
                Object cell = column < row.getValues().length ? row.getValues()[column] : null;
                if (cell == null) {
                    identified = false;
                    break;
                }
                String text = String.valueOf(cell).replace("`", "").trim();
                if (!known.contains(text)) {
                    identified = false;
                    break;
                }
                candidates.add(text);
            }
            if (identified && candidates.size() == table.getRows().size()) {
                return candidates;
            }
        }
        return new LinkedHashSet<String>();
    }

    /**
     * 按请求顺序过滤出已应答的序列，用来报告服务端是否保持了请求顺序。
     *
     * @param existing  请求里的绝对路径顺序
     * @param answered  实际应答的集合
     * @return 请求顺序下的应答列表
     */
    private static List<String> ordered(List<String> existing, Set<String> answered) {
        List<String> ordered = new ArrayList<String>();
        for (String path : existing) {
            if (answered.contains(path)) {
                ordered.add(path);
            }
        }
        return ordered;
    }

    /**
     * 前置序列缺数据时跳过，避免把“环境没铺数据”报成契约不成立。
     *
     * @param executor  执行器
     * @param probe     一条最小语句
     * @param transport 通道名，写进跳过原因
     */
    private static void assumeSeeded(IotdbQueryExecutor executor, String probe, String transport) {
        IotdbResultTable table = executor.query(probe);
        assumeTrue(!table.getRows().isEmpty(), transport + " probe fixture is missing: " + probe
                + " answered no rows, so this contract probe was not executed");
    }

    private static IotdbRestQueryExecutor executor(IotdbClient client) {
        return new IotdbRestQueryExecutor(client.unwrap(IotdbConfig.class));
    }

    private static IotdbQueryExecutor executor(String transport) {
        IotdbConfig config = config(transport);
        if ("native".equals(transport)) {
            return new IotdbNativeQueryExecutor(config);
        }
        if ("jdbc".equals(transport)) {
            return new IotdbJdbcQueryExecutor(config);
        }
        return new IotdbRestQueryExecutor(config);
    }

    private static IotdbConfig config(String transport) {
        TsdbInstanceConfig instance = instance(transport);
        SecurityConfig security = new SecurityConfig();
        security.setAllowInsecureTransport(true);
        return new IotdbConfig("iotdb_contract_it", instance, security);
    }

    private static TsdbInstanceConfig instance(String transport) {
        TsdbInstanceConfig instance = new TsdbInstanceConfig();
        instance.setType("iotdb");
        instance.setBaseUrl(IotdbItConfig.required("baseUrl"));
        instance.getAuth().setUsername(IotdbItConfig.required("username"));
        instance.getAuth().setPassword(IotdbItConfig.required("password"));
        instance.getOptions().put("transport", transport);
        instance.getOptions().put("rpc-port", IotdbItConfig.text("rpcPort", "6667"));
        return instance;
    }

    private static IotdbClient client() {
        TsdbInstanceConfig instance = instance("rest");
        SecurityConfig security = new SecurityConfig();
        security.setAllowInsecureTransport(true);
        TsdbConfig root = new TsdbConfig();
        root.setSecurity(security);
        DriverContext context = new DriverContext("iotdb_contract_it", root, null);
        return (IotdbClient) new IotdbDriver().create(instance, context);
    }
}
