package io.github.forget_the_bright.tsdb.iotdb.internal.sql;

import io.github.forget_the_bright.tsdb.common.exception.TsdbDataException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException;
import io.github.forget_the_bright.tsdb.common.model.Aggregation;
import io.github.forget_the_bright.tsdb.common.model.Direction;
import io.github.forget_the_bright.tsdb.common.model.FillPolicy;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IotdbSql} 的离线单测：期望文本就是上线的 SQL，逐字符钉住。
 *
 * <p>本类的职责边界是<b>文本形态</b>——层级配对、{@code root} 裸写、列顺序、
 * 分块语义都必须在字符串层面可核对；执行与结果解析由
 * {@link io.github.forget_the_bright.tsdb.iotdb.internal.query.IotdbQueryServiceTest}
 * 与门控契约探针负责。</p>
 */
class IotdbSqlTest {

    /** 同库同设备的两个位号。 */
    private static final String TAG_A = "root.factory.BGZH1A.A-PV";
    private static final String TAG_B = "root.factory.BGZH1A.B-PV";
    /** 同库不同设备的位号：按库批量时它必须出现在同一条语句里。 */
    private static final String TAG_C = "root.factory.BGZH2A.C_PV";

    @Test
    void rejectsSqlMetacharactersInTagPath() {
        assertThrows(TsdbDataException.class,
                () -> single("root.sg.d1.temperature; DROP DATABASE root.**"));
        assertThrows(TsdbDataException.class,
                () -> single("root.sg.d1.temp' OR '1'='1"));
    }

    @Test
    void acceptsQuotedTagTextAndReEmitsItCanonically() {
        // 业务里已加引号的位号不是非法输入：解析后按同一套规则重发，形态与裸写等价
        assertEquals(IotdbSql.currentBatch(Grouping.DEVICE, single("root.sg.d1.temperature")),
                IotdbSql.currentBatch(Grouping.DEVICE, single("root.`sg`.`d1`.`temperature`")));
        assertEquals(IotdbSql.currentBatch(Grouping.DEVICE, single("root.sg.d1.temperature")),
                IotdbSql.currentBatch(Grouping.DEVICE, single("root.sg.d1.`temperature`")));
        assertThrows(TsdbDataException.class, () -> single("root.sg.${expr}.temperature"));
        assertThrows(TsdbDataException.class, () -> single("root.sg..temperature"));
    }

    @Test
    void validatesMasksUsedInShowTimeseries() {
        assertTrue(IotdbSql.isValidMask("root.sg.**"));
        assertTrue(IotdbSql.isValidMask("root.sg.d1.temperature"));
        assertFalse(IotdbSql.isValidMask("root.sg.**; DELETE TIMESERIES root.**"));
        assertFalse(IotdbSql.isValidMask("root.sg.** OR 1=1"));
    }

    @Test
    void currentUsesQuotedMeasurementAndDevice() {
        // 逐位号入口是 unwrap 兼容出口：文本必须与改造前逐字节一致
        assertEquals("SELECT LAST `X-PV` FROM root.`factory`.`BGZH1A`",
                IotdbSql.currentBatch(Grouping.DEVICE, single("root.factory.BGZH1A.X-PV")));
    }

    @Test
    void generatedSqlNeverQuotesRootAnchor() {
        List<IotdbTagPath> tags = Arrays.asList(path(TAG_A), path(TAG_B), path(TAG_C));
        for (String sql : Arrays.asList(
                IotdbSql.currentBatch(Grouping.DATABASE, tags),
                IotdbSql.currentBatch(Grouping.ROOT, tags),
                IotdbSql.raw(Grouping.DATABASE, tags, 0L, 1000L, Direction.FORWARD, 10),
                IotdbSql.nearest(Grouping.DATABASE, tags, 1000L, Direction.BACKWARD, 1),
                IotdbSql.aggregated(Grouping.DATABASE, tags, Aggregation.AVG, 0L, 1000L, 300_000L,
                        Direction.FORWARD, 5),
                IotdbSql.interval(Grouping.ROOT, tags, Aggregation.LAST, 0L, 1000L, "5m", FillPolicy.PREVIOUS),
                IotdbSql.intervalSum(Grouping.DATABASE, tags, 0L, 1000L),
                IotdbSql.baseline(Grouping.DATABASE, tags, 1000L))) {
            assertFalse(sql.contains("`root`"), sql);
        }
    }

    @Test
    void selectExpressionsNeverCarryTheRootAnchor() {
        // 实测 code=701：Path can not start with root in select clause
        List<IotdbTagPath> tags = Arrays.asList(path(TAG_A), path(TAG_C));
        for (String sql : Arrays.asList(
                IotdbSql.currentBatch(Grouping.ROOT, tags),
                IotdbSql.raw(Grouping.ROOT, tags, 0L, 1000L, Direction.FORWARD, 10),
                IotdbSql.aggregated(Grouping.ROOT, tags, Aggregation.AVG, 0L, 1000L, 60_000L,
                        Direction.FORWARD, 5),
                IotdbSql.intervalSum(Grouping.ROOT, tags, 0L, 1000L))) {
            assertFalse(sql.substring(0, sql.indexOf(" FROM ")).contains("root."), sql);
        }
    }

    @Test
    void currentBatchCarriesAllTagsOfOneDatabaseInOneStatement() {
        // 业务要写的形态：FROM 是数据库，表达式是“设备.测点”，跨设备也合成一条
        assertEquals("SELECT LAST `BGZH1A`.`A-PV`, `BGZH1A`.`B-PV`, `BGZH2A`.`C_PV` FROM root.`factory`",
                IotdbSql.currentBatch(Grouping.DATABASE, Arrays.asList(path(TAG_A), path(TAG_B), path(TAG_C))));
    }

    @Test
    void currentBatchAtDeviceLevelKeepsTheLegacyShape() {
        // 现场回退档：query-batch-scope: device 必须复现改造前按设备分组的文本
        assertEquals("SELECT LAST `A-PV`, `B-PV` FROM root.`factory`.`BGZH1A`",
                IotdbSql.currentBatch(Grouping.DEVICE, Arrays.asList(path(TAG_A), path(TAG_B))));
    }

    @Test
    void currentBatchAtRootLevelCarriesTheDatabaseNode() {
        // FROM root 的跨库单语句：表达式从库名起，实测三通道均可用
        assertEquals("SELECT LAST `factory`.`BGZH1A`.`A-PV`, `factory`.`BGZH2A`.`C_PV` FROM root",
                IotdbSql.currentBatch(Grouping.ROOT, Arrays.asList(path(TAG_A), path(TAG_C))));
    }

    @Test
    void batchRejectsTagsFromDifferentGroups() {
        // 层级错配在服务端静默返回空，因此必须在客户端就拒绝
        List<IotdbTagPath> mixed = Arrays.asList(path(TAG_A), IotdbTagPath.ofAbsolute("root.tlplant.d1.t"));
        TsdbDataException database = assertThrows(TsdbDataException.class,
                () -> IotdbSql.currentBatch(Grouping.DATABASE, mixed));
        assertTrue(database.getMessage().contains("mixes different groups"), database.getMessage());
        assertThrows(TsdbDataException.class, () -> IotdbSql.raw(Grouping.DEVICE, mixed, 0L, 1000L,
                Direction.FORWARD, 10));
        // ROOT 档位不区分组，跨库合一条是它的本意
        assertEquals(2, countExpressions(IotdbSql.currentBatch(Grouping.ROOT, mixed)));
    }

    @Test
    void batchRejectsAnEmptyTagList() {
        assertThrows(TsdbDataException.class,
                () -> IotdbSql.currentBatch(Grouping.DATABASE, Collections.<IotdbTagPath>emptyList()));
        assertThrows(TsdbDataException.class,
                () -> IotdbSql.intervalSum(Grouping.DATABASE, Collections.<IotdbTagPath>emptyList(), 0L, 1000L));
    }

    @Test
    void rawBatchSharesOneLimitBudgetAcrossColumns() {
        // 实测：LIMIT 是合并时间戳后的行数预算，不是每位号点数，调用方必须补采
        assertEquals("SELECT `BGZH1A`.`A-PV`, `BGZH1A`.`B-PV` FROM root.`factory`"
                        + " WHERE time >= 0 AND time < 1000 ORDER BY TIME ASC LIMIT 11",
                IotdbSql.raw(Grouping.DATABASE, Arrays.asList(path(TAG_A), path(TAG_B)),
                        0L, 1000L, Direction.FORWARD, 11));
        assertTrue(IotdbSql.raw(Grouping.DATABASE, Arrays.asList(path(TAG_A), path(TAG_B)),
                0L, 1000L, Direction.BACKWARD, 11).contains("ORDER BY TIME DESC"));
    }

    @Test
    void intervalSumIsOneRoundTripWithFirstThenLastColumn() {
        assertEquals("SELECT first_value(`X-PV`), last_value(`X-PV`) FROM root.`factory`.`BGZH1A`"
                        + " WHERE time >= 100 AND time < 200",
                IotdbSql.intervalSum(Grouping.DEVICE, single("root.factory.BGZH1A.X-PV"), 100L, 200L));
    }

    @Test
    void intervalSumBatchEmitsAPairPerTagInRequestOrder() {
        assertEquals("SELECT first_value(`BGZH1A`.`A-PV`), last_value(`BGZH1A`.`A-PV`),"
                        + " first_value(`BGZH2A`.`C_PV`), last_value(`BGZH2A`.`C_PV`)"
                        + " FROM root.`factory` WHERE time >= 100 AND time < 200",
                IotdbSql.intervalSum(Grouping.DATABASE, Arrays.asList(path(TAG_A), path(TAG_C)), 100L, 200L));
    }

    @Test
    void nearestKeepsRealSampleTimestampOnOneSide() {
        // 插值必须用真实采样时刻算权重，聚合函数的时间列是窗口边界，不能替代
        assertEquals("SELECT `X-PV` FROM root.`factory`.`BGZH1A` WHERE time < 500"
                        + " ORDER BY TIME DESC LIMIT 1",
                IotdbSql.nearest(Grouping.DEVICE, single("root.factory.BGZH1A.X-PV"),
                        500L, Direction.BACKWARD, 1));
        assertEquals("SELECT `X-PV` FROM root.`factory`.`BGZH1A` WHERE time > 500"
                        + " ORDER BY TIME ASC LIMIT 1",
                IotdbSql.nearest(Grouping.DEVICE, single("root.factory.BGZH1A.X-PV"),
                        500L, Direction.FORWARD, 1));
        assertEquals("SELECT `BGZH1A`.`A-PV`, `BGZH1A`.`B-PV` FROM root.`factory` WHERE time < 500"
                        + " ORDER BY TIME DESC LIMIT 1",
                IotdbSql.nearest(Grouping.DATABASE, Arrays.asList(path(TAG_A), path(TAG_B)),
                        500L, Direction.BACKWARD, 1));
    }

    @Test
    void aggregatedBatchCarriesOneFunctionPerColumnWithOrderAndLimit() {
        assertEquals("SELECT AVG(`BGZH1A`.`A-PV`), AVG(`BGZH2A`.`C_PV`) FROM root.`factory`"
                        + " WHERE time >= 0 AND time < 1000 GROUP BY ([0,1000),5m)"
                        + " ORDER BY TIME DESC LIMIT 5",
                IotdbSql.aggregated(Grouping.DATABASE, Arrays.asList(path(TAG_A), path(TAG_C)),
                        Aggregation.AVG, 0L, 1000L, 300_000L, Direction.BACKWARD, 5));
    }

    @Test
    void intervalBatchFillsOnceForTheWholeStatement() {
        assertEquals("SELECT LAST_VALUE(`BGZH1A`.`A-PV`), LAST_VALUE(`BGZH1A`.`B-PV`) FROM root.`factory`"
                        + " WHERE time >= 0 AND time < 1000 GROUP BY ([0,1000),5m) FILL(PREVIOUS)",
                IotdbSql.interval(Grouping.DATABASE, Arrays.asList(path(TAG_A), path(TAG_B)),
                        Aggregation.LAST, 0L, 1000L, "5m", FillPolicy.PREVIOUS));
    }

    @Test
    void baselineBatchReadsOneLastValueColumnPerTag() {
        assertEquals("SELECT LAST_VALUE(`BGZH1A`.`A-PV`), LAST_VALUE(`BGZH2A`.`C_PV`) FROM root.`factory`"
                        + " WHERE time < 1000",
                IotdbSql.baseline(Grouping.DATABASE, Arrays.asList(path(TAG_A), path(TAG_C)), 1000L));
    }

    @Test
    void intervalSumIsRejectedBySingleFunctionSql() {
        TsdbUnsupportedException e = assertThrows(TsdbUnsupportedException.class,
                () -> IotdbSql.aggregated(Grouping.DEVICE, single("root.factory.BGZH1A.X-PV"),
                        Aggregation.INTERVAL_SUM, 0L, 1000L, 60_000L, Direction.FORWARD, 10));
        assertTrue(e.getMessage().contains("intervalSum"), e.getMessage());
        assertThrows(TsdbUnsupportedException.class,
                () -> IotdbSql.interval(Grouping.DATABASE, Arrays.asList(path(TAG_A)),
                        Aggregation.INTERVAL_SUM, 0L, 1000L, "5m", FillPolicy.NONE));
    }

    @Test
    void supportsArbitraryIntervalLiterals() {
        // 业务有 9×5min 曲线与 480 点等分网格，SDK 不得只允许 1s/1m/1h/1d
        assertTrue(IotdbSql.interval(Grouping.DEVICE, single("root.sg.d1.s"),
                Aggregation.AVG, 0L, 1000L, "5m", FillPolicy.NONE)
                .contains("GROUP BY ([0,1000),5m)"));
        assertTrue(IotdbSql.interval(Grouping.DEVICE, single("root.sg.d1.s"),
                Aggregation.AVG, 0L, 1000L, "300s", FillPolicy.NONE)
                .contains("GROUP BY ([0,1000),300s)"));
    }

    @Test
    void aggregatedConvertsMillisToUnitLiteral() {
        // 裸毫秒数在 2.0.11 会被解析器拒绝，必须带单位
        assertTrue(IotdbSql.aggregated(Grouping.DEVICE, single("root.sg.d1.s"), Aggregation.AVG,
                0L, 1000L, 5_000L, Direction.FORWARD, 3).contains("),5s)"));
        assertTrue(IotdbSql.aggregated(Grouping.DEVICE, single("root.sg.d1.s"), Aggregation.AVG,
                0L, 1000L, 1_500L, Direction.FORWARD, 3).contains("),1500ms)"));
    }

    @Test
    void rejectsMalformedIntervalLiteral() {
        assertThrows(TsdbDataException.class,
                () -> IotdbSql.interval(Grouping.DEVICE, single("root.sg.d1.s"),
                        Aggregation.AVG, 0L, 1000L, "5 minutes", FillPolicy.NONE));
        assertThrows(TsdbDataException.class,
                () -> IotdbSql.interval(Grouping.DEVICE, single("root.sg.d1.s"),
                        Aggregation.AVG, 0L, 1000L, "1h) --", FillPolicy.NONE));
        assertThrows(TsdbDataException.class,
                () -> IotdbSql.interval(Grouping.DATABASE, Arrays.asList(path(TAG_A)), Aggregation.AVG,
                        0L, 1000L, "1h) --", FillPolicy.NONE));
    }

    @Test
    void showTimeseriesKeepsWildcardPrefixUnquoted() {
        // 反引号内的 * 会退化成字面量，SHOW TIMESERIES 必须裸写掩码
        assertEquals("SHOW TIMESERIES root.factory.**", IotdbSql.showTimeseries("root.factory"));
        // 掩码规则只有这一处：自带通配符的掩码原样使用，不能再补一层后缀，
        // 否则 root.sg.* 会变成 root.sg.*.**，列出的是另一批序列。
        assertEquals("SHOW TIMESERIES root.sg.*", IotdbSql.showTimeseries("root.sg.*"));
    }

    private static IotdbTagPath path(String absolutePath) {
        return IotdbTagPath.ofAbsolute(absolutePath);
    }

    /**
     * 逐位号形态的统一入参：按设备分组的单元素列表。
     *
     * @param absolutePath 完整序列路径
     * @return 单元素路径列表
     */
    private static List<IotdbTagPath> single(String absolutePath) {
        return Collections.singletonList(path(absolutePath));
    }

    /**
     * 按 {@code ", "} 统计 SELECT 段的列数，用于判断档位是否把多位号合进了同一条语句。
     */
    private static int countExpressions(String sql) {
        return sql.substring("SELECT LAST ".length(), sql.indexOf(" FROM ")).split(", ").length;
    }
}
