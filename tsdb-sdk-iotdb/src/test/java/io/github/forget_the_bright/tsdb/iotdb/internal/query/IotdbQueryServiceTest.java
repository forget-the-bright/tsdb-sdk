package io.github.forget_the_bright.tsdb.iotdb.internal.query;

import io.github.forget_the_bright.tsdb.common.config.SecurityConfig;
import io.github.forget_the_bright.tsdb.common.config.TsdbInstanceConfig;
import io.github.forget_the_bright.tsdb.common.exception.TsdbQueryException;
import io.github.forget_the_bright.tsdb.common.model.Aggregation;
import io.github.forget_the_bright.tsdb.common.model.DeltaPoint;
import io.github.forget_the_bright.tsdb.common.model.Direction;
import io.github.forget_the_bright.tsdb.common.model.FillPolicy;
import io.github.forget_the_bright.tsdb.common.model.TimeGranularity;
import io.github.forget_the_bright.tsdb.iotdb.IotdbConfig;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import io.github.forget_the_bright.tsdb.iotdb.internal.IotdbModels;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.Grouping;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.IotdbSql;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.IotdbTagPath;
import io.github.forget_the_bright.tsdb.iotdb.internal.transport.IotdbQueryExecutor;
import io.github.forget_the_bright.tsdb.iotdb.internal.transport.IotdbResultTable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IotdbQueryService} 的单元测试：用脚本化执行器验证 SQL 与差分算法，
 * 不连接真实数据库。
 *
 * @author wanghao
 * @since 0.1.0
 */
class IotdbQueryServiceTest {

    /**
     * 被测序列：{@code root.factory.device02.temperature}。
     */
    private static final String TEMPERATURE = "root.factory.device02.temperature";

    @Test
    void computesDeltaAgainstBaselineAndMarksCounterReset() {
        ScriptedExecutor executor = new ScriptedExecutor();
        executor.intervalRows = rows(
                row(-3600000L, 90.0),
                row(1000L, 100.0),
                row(2000L, 150.0),
                row(3000L, 40.0),
                row(4000L, 80.0));
        IotdbQueryService service = new IotdbQueryService(config(), executor);

        List<DeltaPoint> points = service.deltas("root.factory.device02.temperature",
                Instant.ofEpochSecond(0), Instant.ofEpochSecond(10),
                TimeGranularity.HOUR, FillPolicy.PREVIOUS, Direction.FORWARD, 10);

        assertEquals(4, points.size());
        assertEquals(10.0, points.get(0).getDelta(), 0.0001);
        assertEquals(50.0, points.get(1).getDelta(), 0.0001);
        assertTrue(points.get(2).isReset());
        assertEquals(40.0, points.get(2).getDelta(), 0.0001);
        assertEquals(40.0, points.get(3).getDelta(), 0.0001);
        // 基线折进扩展桶：一次往返即可，不再补 LAST_VALUE
        assertEquals(1, executor.sqls.size());
        assertTrue(executor.sqls.get(0).contains("GROUP BY"));
        assertTrue(executor.sqls.get(0).contains("FILL(PREVIOUS)"));
        assertTrue(executor.sqls.get(0).contains("[-3600000,10000)"));
    }

    @Test
    void marksEmptyBucketAsMissingAndKeepsPreviousValue() {
        ScriptedExecutor executor = new ScriptedExecutor();
        executor.intervalRows = rows(
                row(-3600000L, 5.0),
                row(1000L, 10.0),
                row(2000L, null),
                row(3000L, 30.0));
        IotdbQueryService service = new IotdbQueryService(config(), executor);

        List<DeltaPoint> points = service.deltas("root.factory.device02.temperature",
                Instant.ofEpochSecond(0), Instant.ofEpochSecond(10),
                TimeGranularity.HOUR, FillPolicy.NONE, Direction.FORWARD, 10);

        assertEquals(3, points.size());
        assertFalse(points.get(0).isMissing());
        assertTrue(points.get(1).isMissing());
        assertEquals(20.0, points.get(2).getDelta(), 0.0001);
        assertEquals(1, executor.sqls.size());
    }

    @Test
    void alignsMonthStartToFirstDayAndUsesMonthLiteral() {
        ScriptedExecutor executor = new ScriptedExecutor();
        executor.intervalRows = rows();
        IotdbQueryService service = new IotdbQueryService(config(), executor);

        service.interval("root.factory.device02.temperature",
                Instant.parse("2026-11-12T09:00:00Z"), Instant.parse("2026-12-01T00:00:00Z"),
                TimeGranularity.MONTH, Aggregation.LAST, FillPolicy.NONE);

        String sql = executor.sqls.get(0);
        assertTrue(sql.contains("1mo"));
        // 月首按业务时区算：2026-11-01T00:00+08:00 = 1793462400000。
        // 本用例未配置 options.time.*，展示时区就是默认的 UTC；若对齐误用展示时区，
        // 这里会得到 1793491200000（晚 8 小时），整月的桶会整体错一格。
        assertTrue(sql.contains("1793462400000"));
        assertFalse(sql.contains("1793491200000"));
    }

    @Test
    void alignsDayStartToBusinessZoneInsteadOfDisplayZone() {
        ScriptedExecutor executor = new ScriptedExecutor();
        executor.intervalRows = rows();
        IotdbQueryService service = new IotdbQueryService(config(), executor);

        service.interval("root.factory.device02.temperature",
                Instant.parse("2026-11-11T20:00:00Z"), Instant.parse("2026-11-12T20:00:00Z"),
                TimeGranularity.DAY, Aggregation.LAST, FillPolicy.NONE);

        // 起点折成本地时间是 11-12 04:00，业务时区（缺省 Asia/Shanghai）的日首
        // = 2026-11-12T00:00+08:00 = 1794412800000。日粒度报表按这一格切，
        // 若误用展示时区（本用例未配 options.time.*，即 UTC）会得到 1794355200000，
        // 每格差 8 小时——展示配置不该有任何资格改变分桶边界。
        String sql = executor.sqls.get(0);
        assertTrue(sql.contains("1794412800000"));
        assertFalse(sql.contains("1794355200000"));
    }

    @Test
    void alignsDayStartToConfiguredZone() {
        ScriptedExecutor executor = new ScriptedExecutor();
        executor.intervalRows = rows();
        IotdbQueryService service = new IotdbQueryService(
                configWith("zone", "UTC"), executor);

        service.interval("root.factory.device02.temperature",
                Instant.parse("2026-11-11T20:00:00Z"), Instant.parse("2026-11-12T20:00:00Z"),
                TimeGranularity.DAY, Aggregation.LAST, FillPolicy.NONE);

        // 同一个起点，换 zone 就换格：证明归整读的是 options.zone 而非硬编码
        String sql = executor.sqls.get(0);
        assertTrue(sql.contains("1794355200000"));
        assertFalse(sql.contains("1794412800000"));
    }

    @Test
    void readsServerVersionFromShowVersion() {
        ScriptedExecutor executor = new ScriptedExecutor();
        executor.versionRows = rows(row(0L, "2.0.11"));
        IotdbQueryService service = new IotdbQueryService(config(), executor);
        assertEquals("2.0.11", service.serverVersion());
        assertEquals("SHOW VERSION", executor.sqls.get(0));
    }

    @Test
    void intervalSumSubtractsWindowAnchorsInOneRoundTrip() {
        RecordingExecutor executor = new RecordingExecutor(
                sql -> table2(row(0L, 100.0, 157.5)));
        IotdbQueryService service = new IotdbQueryService(config(), executor);

        IotdbModels.Point point = service.intervalSum(TEMPERATURE,
                Instant.ofEpochMilli(1000L), Instant.ofEpochMilli(61000L));

        String sql = executor.sqls.get(0);
        assertEquals(1, executor.sqls.size());
        assertTrue(sql.contains("first_value(`temperature`)"));
        assertTrue(sql.contains("last_value(`temperature`)"));
        assertFalse(sql.contains("GROUP BY"));
        assertEquals(0, new BigDecimal("57.5").compareTo((BigDecimal) point.getValue()));
        // 整窗增量是单点，时间戳取窗口起点，与 GeClient 的片起始约定一致
        assertEquals(1000L, point.getTimestamp().toEpochMilli());
    }

    @Test
    void intervalSumNeverFabricatesZeroWhenAnAnchorIsMissing() {
        IotdbQueryService missingLast = new IotdbQueryService(config(),
                new RecordingExecutor(sql -> table2(row(0L, 100.0, null))));
        IotdbQueryService noRows = new IotdbQueryService(config(),
                new RecordingExecutor(sql -> table2()));

        // 锚点缺失只说明“没有数据”，补 0 会把它伪装成“这段时间没有消耗”
        assertNull(missingLast.intervalSum(TEMPERATURE,
                Instant.ofEpochMilli(1000L), Instant.ofEpochMilli(61000L)));
        assertNull(noRows.intervalSum(TEMPERATURE,
                Instant.ofEpochMilli(1000L), Instant.ofEpochMilli(61000L)));
    }

    @Test
    void interpolateReturnsRawSampleUntouchedWhenItSitsOnASlot() {
        RecordingExecutor executor = new RecordingExecutor(sql -> {
            if (isWindow(sql)) {
                return table(row(1000L, 10.0), row(2000L, 20.0));
            }
            if (sql.contains("ORDER BY TIME DESC")) {
                return table(row(500L, 5.0));
            }
            return table(row(4000L, 40.0));
        });
        IotdbQueryService service = new IotdbQueryService(config(), executor);

        List<IotdbModels.Point> grid = service.interpolate(TEMPERATURE,
                Instant.ofEpochMilli(1000L), 1000L, 3, 0L, 1000).getPoints();

        assertEquals(3, grid.size());
        assertEquals(10.0, ((Number) grid.get(0).getValue()).doubleValue(), 0.0001);
        assertEquals(20.0, ((Number) grid.get(1).getValue()).doubleValue(), 0.0001);
        // 第 3 格无采样，用 2000@20 与 4000@40 的真实时刻加权内插
        assertEquals(30.0, ((Number) grid.get(2).getValue()).doubleValue(), 0.0001);
        assertEquals(3000L, grid.get(2).getTimestamp().toEpochMilli());
    }

    @Test
    void interpolateWeightsByRealSampleTimestamps() {
        RecordingExecutor executor = new RecordingExecutor(sql -> {
            if (isWindow(sql)) {
                return table();
            }
            if (sql.contains("ORDER BY TIME DESC")) {
                return table(row(0L, 10.0));
            }
            return table(row(3000L, 40.0));
        });
        IotdbQueryService service = new IotdbQueryService(config(), executor);

        List<IotdbModels.Point> grid = service.interpolate(TEMPERATURE,
                Instant.ofEpochMilli(1000L), 1000L, 2, 0L, 1000).getPoints();

        // 包围点 0@10 与 3000@40：权重 1/3 与 2/3，若误用窗口边界时间会得到恒定错误值
        assertEquals(20.0, ((Number) grid.get(0).getValue()).doubleValue(), 0.0001);
        assertEquals(30.0, ((Number) grid.get(1).getValue()).doubleValue(), 0.0001);
    }

    @Test
    void interpolateRefusesToExtrapolateBeyondDataByDefault() {
        RecordingExecutor executor = new RecordingExecutor(sql ->
                sql.contains("ORDER BY TIME DESC") ? table(row(0L, 10.0)) : table());
        IotdbQueryService service = new IotdbQueryService(config(), executor);

        List<IotdbModels.Point> grid = service.interpolate(TEMPERATURE,
                Instant.ofEpochMilli(1000L), 1000L, 2, 0L, 1000).getPoints();

        // 容差 0 表示不外推：停产或改名后继续画出平直曲线比缺数更难发现
        assertNull(grid.get(0).getValue());
        assertNull(grid.get(1).getValue());
    }

    @Test
    void interpolateHoldsKnownValueOnlyInsideConfiguredTolerance() {
        RecordingExecutor executor = new RecordingExecutor(sql ->
                sql.contains("ORDER BY TIME DESC") ? table(row(0L, 10.0)) : table());
        IotdbQueryService service = new IotdbQueryService(config(), executor);

        List<IotdbModels.Point> grid = service.interpolate(TEMPERATURE,
                Instant.ofEpochMilli(1000L), 1000L, 2, 1500L, 1000).getPoints();

        assertEquals(10.0, ((Number) grid.get(0).getValue()).doubleValue(), 0.0001);
        assertNull(grid.get(1).getValue());
    }

    @Test
    void interpolatePagesRawAndFlagsTruncationWhenBudgetExhausted() {
        RecordingExecutor executor = new RecordingExecutor(sql -> isWindow(sql)
                ? table(row(1000L, 1.0), row(2000L, 2.0))
                : table());
        IotdbQueryService service = new IotdbQueryService(config(), executor);

        IotdbQueryService.Interpolation result = service.interpolate(TEMPERATURE,
                Instant.ofEpochMilli(1000L), 1000L, 2, 0L, 2);

        // 预算 2 用满且末页取满，说明窗口内还有原始点没取回
        assertTrue(result.isRawTruncated());
        assertEquals(3, executor.sqls.size());
        assertTrue(executor.sqls.get(1).contains("LIMIT 2"));
    }

    @Test
    void interpolateEmitsEveryGridSlotEvenWithNoSamples() {
        RecordingExecutor executor = new RecordingExecutor(sql -> table());
        IotdbQueryService service = new IotdbQueryService(config(), executor);

        List<IotdbModels.Point> grid = service.interpolate(TEMPERATURE,
                Instant.ofEpochMilli(1000L), 1000L, 5, 0L, 1000).getPoints();

        // 业务按索引取点，槽位数必须恒定，否则整条曲线错位
        assertEquals(5, grid.size());
        for (int i = 0; i < grid.size(); i++) {
            assertEquals(1000L + i * 1000L, grid.get(i).getTimestamp().toEpochMilli());
            assertNull(grid.get(i).getValue());
        }
        assertFalse(executor.sqls.get(1).contains("GROUP BY"));
    }

    @Test
    void sampledIntervalAcceptsArbitraryStep() {
        RecordingExecutor executor = new RecordingExecutor(sql -> table());
        IotdbQueryService service = new IotdbQueryService(config(), executor);

        service.interval(TEMPERATURE, Instant.ofEpochMilli(1000L), Instant.ofEpochMilli(61000L),
                300_000L, Aggregation.LAST, FillPolicy.NONE);
        service.interval(TEMPERATURE, Instant.ofEpochMilli(1000L), Instant.ofEpochMilli(61000L),
                250L, Aggregation.LAST, FillPolicy.NONE);

        // 整分的 5 分钟换成自然单位字面量，非整分的 250 毫秒保留 ms 形式；
        // 两者都在生产 2.0.11 上实测被接受，SDK 不得再自己卡成 1s/1m/1h/1d
        assertTrue(executor.sqls.get(0).contains("GROUP BY ([1000,61000),5m)"));
        assertTrue(executor.sqls.get(1).contains("GROUP BY ([1000,61000),250ms)"));
    }

    @Test
    void currentBatchMergesMeasurementsPerDeviceAndKeepsRequestOrder() {
        RecordingExecutor executor = new RecordingExecutor(sql ->
                sql.contains("FROM root.`factory`.`device01`")
                        ? lastTable(lastRow("root.factory.device01.temperature", 111L, "24.8"))
                        : lastTable(
                        lastRow("root.factory.device02.temperature", 222L, "25.6"),
                        lastRow("root.factory.device02.humidity", 333L, "48.2")));
        IotdbQueryService service = new IotdbQueryService(
                configWith("query-batch-scope", "device"), executor);

        List<IotdbModels.Point> points = service.currentBatch(paths(
                "root.factory.device02.temperature",
                "root.factory.device01.temperature",
                "root.factory.device02.missing",
                "root.factory.device02.humidity"));

        // device 回退档：两个设备 → 两条 SQL；返回列表长度与顺序都跟随入参。
        // 库级档位（一条 SQL 跨设备）由 currentBatchMergesWholeDatabaseIntoOneStatement 覆盖。
        assertEquals(2, executor.sqls.size());
        assertTrue(executor.sqls.get(0).contains("`temperature`, `missing`, `humidity`"));
        assertEquals(4, points.size());
        assertEquals(25.6, ((Number) numeric(points.get(0))).doubleValue(), 0.0001);
        assertEquals(222L, points.get(0).getTimestamp().toEpochMilli());
        assertEquals(24.8, ((Number) numeric(points.get(1))).doubleValue(), 0.0001);
        // 服务端会静默丢弃不存在的测点，缺数只能占位，不能顺延别人的值
        assertNull(points.get(2));
        assertEquals(48.2, ((Number) numeric(points.get(3))).doubleValue(), 0.0001);
    }

    @Test
    void currentBatchMergesWholeDatabaseIntoOneStatement() {
        RecordingExecutor executor = new RecordingExecutor(sql -> lastTable(
                lastRow("root.factory.device01.temperature", 111L, "24.8"),
                lastRow("root.factory.device02.humidity", 222L, "48.2")));
        IotdbQueryService service = new IotdbQueryService(
                configWith("query-batch-scope", "database"), executor);

        List<IotdbModels.Point> points = service.currentBatch(paths(
                "root.factory.device01.temperature",
                "root.factory.device02.humidity"));

        // 库级档位：跨设备的两个位号合进一条 SQL，FROM 是数据库，表达式是「设备.测点」
        assertEquals(1, executor.sqls.size());
        assertEquals("SELECT LAST `device01`.`temperature`, `device02`.`humidity` FROM root.`factory`",
                executor.sqls.get(0));
        assertEquals(24.8, ((Number) numeric(points.get(0))).doubleValue(), 0.0001);
        assertEquals(48.2, ((Number) numeric(points.get(1))).doubleValue(), 0.0001);
    }

    @Test
    void currentBatchDeduplicatesTagsAndChunksByMeasurementLimit() {
        RecordingExecutor executor = new RecordingExecutor(sql -> lastTable(
                lastRow("root.factory.device02.temperature", 1000L, "1.0"),
                lastRow("root.factory.device02.humidity", 1000L, "2.0"),
                lastRow("root.factory.device02.status", 1000L, "true")));
        IotdbQueryService service = new IotdbQueryService(configWith("query-batch-size", "2"), executor);

        List<IotdbModels.Point> points = service.currentBatch(paths(
                "root.factory.device02.temperature",
                "root.factory.device02.humidity",
                "root.factory.device02.status",
                "root.factory.device02.temperature"));

        // 重复位号不重复进 SQL，三个不同测点按 query-batch-size=2 拆成两条；
        // 默认档位是库级，所以同设备的表达式也带设备段
        assertEquals(2, executor.sqls.size());
        assertTrue(executor.sqls.get(0).contains("`device02`.`temperature`, `device02`.`humidity`"),
                executor.sqls.get(0));
        assertFalse(executor.sqls.get(0).contains("`status`"), executor.sqls.get(0));
        assertEquals(4, points.size());
        assertEquals(points.get(0).getValue(), points.get(3).getValue());
    }

    @Test
    void currentBatchReturnsRequestOrderWhenDevicesResolveOutOfOrder() {
        // 慢设备先提交、快设备后完成：并行只影响发出顺序，不影响回填顺序
        RecordingExecutor executor = new RecordingExecutor(sql -> {
            String device = sql.contains("device01") ? "device01" : "device02";
            if ("device01".equals(device)) {
                sleep(80L);
            }
            return lastTable(lastRow("root.factory." + device + ".temperature", 1000L,
                    "device01".equals(device) ? "1.0" : "2.0"));
        });
        IotdbQueryService service = new IotdbQueryService(
                configWith("query-batch-scope", "device", "query-parallelism", "4"), executor);

        List<IotdbModels.Point> points = service.currentBatch(paths(
                "root.factory.device01.temperature",
                "root.factory.device02.temperature"));

        assertEquals(2, points.size());
        assertEquals(1.0, ((Number) numeric(points.get(0))).doubleValue(), 0.0001);
        assertEquals(2.0, ((Number) numeric(points.get(1))).doubleValue(), 0.0001);
    }

    @Test
    void intervalBatchCarriesEveryTagOfOneDatabaseInOneStatement() {
        RecordingExecutor executor = new RecordingExecutor(sql -> new IotdbResultTable(
                Arrays.asList("AVG(root.factory.d1.a)", "AVG(root.factory.d2.b)"),
                rows(row(1000L, 1.0, 2.0), row(2000L, 3.0, null))));
        IotdbQueryService service = new IotdbQueryService(
                configWith("query-batch-scope", "database"), executor);

        Map<String, List<IotdbModels.Point>> series = service.intervalBatch(
                paths("root.factory.d1.a", "root.factory.d2.b"),
                Instant.ofEpochMilli(1000L), Instant.ofEpochMilli(3000L),
                3_600_000L, Aggregation.AVG, FillPolicy.NONE);

        assertEquals("SELECT AVG(`d1`.`a`), AVG(`d2`.`b`) FROM root.`factory`"
                        + " WHERE time >= 1000 AND time < 3000 GROUP BY ([1000,3000),1h)",
                executor.sqls.get(0));
        assertEquals(1, executor.sqls.size());
        assertEquals(2, series.get("root.factory.d1.a").size());
        assertEquals(2.0, ((Number) series.get("root.factory.d2.b").get(0).getValue()).doubleValue(), 0.0001);
        // 空桶就是 null：不能把上一列的值挪给这一列
        assertNull(series.get("root.factory.d2.b").get(1).getValue());
    }

    @Test
    void batchReadsColumnsByTheirPathLabelRatherThanByPosition() {
        // 实测列顺序等于发射顺序（M6），但位号可能存在性导致丢列（G2）：
        // 这里把第二列挪到前面，按位置读就会把 b 的数据挂到 a 名下
        RecordingExecutor executor = new RecordingExecutor(sql -> new IotdbResultTable(
                Arrays.asList("AVG(root.factory.d2.b)", "AVG(root.factory.d1.a)"),
                rows(row(1000L, 7.0, 3.0))));
        IotdbQueryService service = new IotdbQueryService(
                configWith("query-batch-scope", "database"), executor);

        Map<String, List<IotdbModels.Point>> series = service.intervalBatch(
                paths("root.factory.d1.a", "root.factory.d2.b"),
                Instant.ofEpochMilli(1000L), Instant.ofEpochMilli(2000L),
                1000L, Aggregation.AVG, FillPolicy.NONE);

        assertEquals(3.0, ((Number) series.get("root.factory.d1.a").get(0).getValue()).doubleValue(), 0.0001);
        assertEquals(7.0, ((Number) series.get("root.factory.d2.b").get(0).getValue()).doubleValue(), 0.0001);
    }

    @Test
    void batchLeavesADroppedColumnEmptyInsteadOfShiftingTheNeighbours() {
        // G2：不存在的序列整列消失。基线里 a 那一列不见了时，a 的第一个桶必须是
        // 「缺基线」，绝不能把 b 的基线当成 a 的——那是把别人的读数挂到 a 名下。
        RecordingExecutor executor = new RecordingExecutor(sql -> sql.contains("GROUP BY")
                ? new IotdbResultTable(Arrays.asList("LAST_VALUE(root.factory.d1.a)",
                        "LAST_VALUE(root.factory.d2.b)"), rows(row(1000L, 110.0, 205.0)))
                : new IotdbResultTable(Collections.singletonList("last_value(root.factory.d2.b)"),
                        rows(row(0L, 200.0))));
        IotdbQueryService service = new IotdbQueryService(
                configWith("query-batch-scope", "database"), executor);

        Map<String, List<DeltaPoint>> deltas = service.deltasBatch(
                paths("root.factory.d1.a", "root.factory.d2.b"),
                Instant.ofEpochSecond(0), Instant.ofEpochSecond(10),
                TimeGranularity.HOUR, FillPolicy.NONE, Direction.FORWARD, 10);

        assertEquals("SELECT LAST_VALUE(`d1`.`a`), LAST_VALUE(`d2`.`b`) FROM root.`factory`"
                        + " WHERE time < 0", executor.sqls.get(1));
        assertTrue(deltas.get("root.factory.d1.a").get(0).isMissing());
        assertEquals(5.0, deltas.get("root.factory.d2.b").get(0).getDelta(), 0.0001);
    }

    @Test
    void batchRejectsAColumnThatWasNeverRequested() {
        RecordingExecutor executor = new RecordingExecutor(sql -> new IotdbResultTable(
                Arrays.asList("AVG(root.factory.d1.a)", "AVG(root.factory.d1.stranger)"),
                rows(row(1000L, 1.0, 2.0))));
        IotdbQueryService service = new IotdbQueryService(
                configWith("query-batch-scope", "database"), executor);

        TsdbQueryException failure = assertThrows(TsdbQueryException.class,
                () -> service.intervalBatch(paths("root.factory.d1.a", "root.factory.d2.b"),
                        Instant.ofEpochMilli(1000L), Instant.ofEpochMilli(2000L),
                        1000L, Aggregation.AVG, FillPolicy.NONE));
        assertTrue(failure.getMessage().contains("unrequested series"), failure.getMessage());
        assertTrue(failure.getMessage().contains("stranger"), failure.getMessage());
    }

    @Test
    void batchFallsBackToPositionsOnlyWhenNoColumnCarriesAPath() {
        Function<String, IotdbResultTable> unnamed = sql -> new IotdbResultTable(
                Arrays.asList("Last_value", "Last_value"), rows(row(1000L, 1.0, 2.0)));

        Map<String, List<IotdbModels.Point>> aligned = new IotdbQueryService(
                configWith("query-batch-scope", "database"), new RecordingExecutor(unnamed))
                .intervalBatch(paths("root.factory.d1.a", "root.factory.d2.b"),
                        Instant.ofEpochMilli(1000L), Instant.ofEpochMilli(2000L),
                        1000L, Aggregation.LAST, FillPolicy.NONE);
        assertEquals(1.0, ((Number) aligned.get("root.factory.d1.a").get(0).getValue()).doubleValue(), 0.0001);
        assertEquals(2.0, ((Number) aligned.get("root.factory.d2.b").get(0).getValue()).doubleValue(), 0.0001);

        // 列数与发射数不符时，位置同样不可信 —— 宁可抛，不能猜
        RecordingExecutor narrower = new RecordingExecutor(sql -> new IotdbResultTable(
                Arrays.asList("Last_value", "Last_value", "Last_value"), rows(row(1000L, 1.0, 2.0, 3.0))));
        assertThrows(TsdbQueryException.class, () -> new IotdbQueryService(
                configWith("query-batch-scope", "database"), narrower)
                .intervalBatch(paths("root.factory.d1.a", "root.factory.d2.b"),
                        Instant.ofEpochMilli(1000L), Instant.ofEpochMilli(2000L),
                        1000L, Aggregation.LAST, FillPolicy.NONE));
    }

    @Test
    void intervalSumBatchPairsFirstAndLastByFunctionNotByPosition() {
        // 列名里函数名按 SQL 原样回显（G1）：这里 last 在前、first 在后，配对必须跟着名字走
        RecordingExecutor executor = new RecordingExecutor(sql -> new IotdbResultTable(
                Arrays.asList("last_value(root.factory.d1.a)", "first_value(root.factory.d1.a)"),
                rows(row(0L, 100.0, 42.5))));
        IotdbQueryService service = new IotdbQueryService(
                configWith("query-batch-scope", "database"), executor);

        Map<String, IotdbModels.Point> sums = service.intervalSumBatch(
                paths("root.factory.d1.a"), Instant.ofEpochMilli(1000L), Instant.ofEpochMilli(61000L));

        assertEquals("SELECT first_value(`d1`.`a`), last_value(`d1`.`a`) FROM root.`factory`"
                        + " WHERE time >= 1000 AND time < 61000", executor.sqls.get(0));
        assertEquals(0, new BigDecimal("57.5")
                .compareTo((BigDecimal) sums.get("root.factory.d1.a").getValue()));
        assertEquals(1000L, sums.get("root.factory.d1.a").getTimestamp().toEpochMilli());
    }

    @Test
    void intervalSumBatchCoversEveryTagOfTheDatabaseInOneRoundTrip() {
        RecordingExecutor executor = new RecordingExecutor(sql -> new IotdbResultTable(
                Arrays.asList("first_value(root.factory.d1.a)", "last_value(root.factory.d1.a)",
                        "first_value(root.factory.d2.b)", "last_value(root.factory.d2.b)"),
                rows(row(0L, 10.0, 17.5, 20.0, 20.0))));
        IotdbQueryService service = new IotdbQueryService(
                configWith("query-batch-scope", "database"), executor);

        Map<String, IotdbModels.Point> sums = service.intervalSumBatch(
                paths("root.factory.d1.a", "root.factory.d2.b"),
                Instant.ofEpochMilli(0L), Instant.ofEpochMilli(60_000L));

        assertEquals(1, executor.sqls.size());
        assertEquals(0, new BigDecimal("7.5").compareTo((BigDecimal) sums.get("root.factory.d1.a").getValue()));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) sums.get("root.factory.d2.b").getValue()));
    }

    @Test
    void deltasBatchSendsOneStatementPerChunkWhenExtendedBucketCarriesBaseline() {
        RecordingExecutor executor = new RecordingExecutor(sql -> sql.contains("GROUP BY")
                ? new IotdbResultTable(Arrays.asList("LAST_VALUE(root.factory.d1.a)",
                        "LAST_VALUE(root.factory.d2.b)"),
                rows(row(-3600000L, 100.0, 200.0),
                        row(1000L, 110.0, 205.0), row(2000L, 150.0, 205.0)))
                : new IotdbResultTable(Arrays.asList("last_value(root.factory.d1.a)",
                        "last_value(root.factory.d2.b)"), rows(row(0L, 100.0, 200.0))));
        IotdbQueryService service = new IotdbQueryService(
                configWith("query-batch-scope", "database"), executor);

        Map<String, List<DeltaPoint>> deltas = service.deltasBatch(
                paths("root.factory.d1.a", "root.factory.d2.b"),
                Instant.ofEpochSecond(0), Instant.ofEpochSecond(10),
                TimeGranularity.HOUR, FillPolicy.NONE, Direction.FORWARD, 10);

        // 基线折进扩展桶后每块只有一条 SQL，且起点 = 窗口起点 - 1 个间隔
        assertEquals(1, executor.sqls.size());
        assertTrue(executor.sqls.get(0).contains("GROUP BY"));
        assertTrue(executor.sqls.get(0).contains("[-3600000,10000)"));
        assertEquals(10.0, deltas.get("root.factory.d1.a").get(0).getDelta(), 0.0001);
        assertEquals(40.0, deltas.get("root.factory.d1.a").get(1).getDelta(), 0.0001);
        // b 第二个桶与第一个桶等值：增量为 0，不是缺数
        assertEquals(0.0, deltas.get("root.factory.d2.b").get(1).getDelta(), 0.0001);
    }

    @Test
    void deltasBatchFallsBackToABatchedBaselineForTheTagWhoseExtendedBucketIsEmpty() {
        RecordingExecutor executor = new RecordingExecutor(sql -> sql.contains("GROUP BY")
                ? new IotdbResultTable(Arrays.asList("LAST_VALUE(root.factory.d1.a)",
                        "LAST_VALUE(root.factory.d2.b)"),
                rows(row(-3600000L, null, 200.0),
                        row(1000L, 110.0, 205.0), row(2000L, 150.0, 205.0)))
                : new IotdbResultTable(Collections.singletonList("LAST_VALUE(root.factory.d1.a)"),
                rows(row(0L, 90.0))));
        IotdbQueryService service = new IotdbQueryService(
                configWith("query-batch-scope", "database"), executor);

        Map<String, List<DeltaPoint>> deltas = service.deltasBatch(
                paths("root.factory.d1.a", "root.factory.d2.b"),
                Instant.ofEpochSecond(0), Instant.ofEpochSecond(10),
                TimeGranularity.HOUR, FillPolicy.NONE, Direction.FORWARD, 10);

        // 扩展桶存在但值为空：丢弃该点后只给缺基线的那一块补一条批量 LAST_VALUE
        assertEquals(2, executor.sqls.size());
        assertTrue(executor.sqls.get(1).contains("WHERE time < 0"));
        assertTrue(executor.sqls.get(1).contains("LAST_VALUE(`d1`.`a`)"));
        assertFalse(executor.sqls.get(1).contains("`b`"));
        assertEquals(20.0, deltas.get("root.factory.d1.a").get(0).getDelta(), 0.0001);
        assertEquals(40.0, deltas.get("root.factory.d1.a").get(1).getDelta(), 0.0001);
        assertEquals(5.0, deltas.get("root.factory.d2.b").get(0).getDelta(), 0.0001);
    }

    @Test
    void monthDeltasFoldThePreviousCalendarMonthIntoTheBucketQuery() {
        RecordingExecutor executor = new RecordingExecutor(sql -> sql.contains("GROUP BY")
                ? new IotdbResultTable(Collections.singletonList(
                        "LAST_VALUE(root.factory.device02.temperature)"),
                rows(row(1790784000000L, 90.0), row(1793462400000L, 100.0)))
                : new IotdbResultTable(Collections.singletonList(
                        "last_value(root.factory.device02.temperature)"), rows(row(0L, 90.0))));
        IotdbQueryService service = new IotdbQueryService(config(), executor);

        List<DeltaPoint> points = service.deltas(TEMPERATURE,
                Instant.parse("2026-11-12T09:00:00Z"), Instant.parse("2026-12-01T00:00:00Z"),
                TimeGranularity.MONTH, FillPolicy.NONE, Direction.FORWARD, 10);

        // 归一后起点 = 2026-11-01T00:00+08:00（1793462400000），
        // 扩展起点 = 上一个自然月 1 号（1790784000000），仍用 1mo 分组。
        assertEquals(1, executor.sqls.size());
        String sql = executor.sqls.get(0);
        assertTrue(sql.contains("1mo"));
        // SQL 里只有区间两端：扩展起点与终点
        assertTrue(sql.contains("[1790784000000,"
                + Instant.parse("2026-12-01T00:00:00Z").toEpochMilli() + ")"));
        assertEquals(1, points.size());
        assertEquals(10.0, points.get(0).getDelta(), 0.0001);
    }

    @Test
    void deltasByIntervalSendsFiveMinuteLiteralAndFoldsBaseline() {
        RecordingExecutor executor = new RecordingExecutor(sql -> sql.contains("GROUP BY")
                ? new IotdbResultTable(Arrays.asList("LAST_VALUE(root.factory.d1.a)"),
                rows(row(-300000L, 100.0), row(0L, 110.0), row(300000L, 150.0)))
                : new IotdbResultTable(Arrays.asList("last_value(root.factory.d1.a)"),
                rows(row(-1L, 100.0))));
        IotdbQueryService service = new IotdbQueryService(
                configWith("query-batch-scope", "database"), executor);

        Map<String, List<DeltaPoint>> deltas = service.deltasByIntervalBatch(
                paths("root.factory.d1.a"),
                Instant.ofEpochSecond(0), Instant.ofEpochSecond(900),
                300000L, FillPolicy.NONE, Direction.FORWARD, 10);

        assertEquals(1, executor.sqls.size());
        assertTrue(executor.sqls.get(0).contains("5m"));
        assertTrue(executor.sqls.get(0).contains("[-300000,900000)"));
        assertEquals(10.0, deltas.get("root.factory.d1.a").get(0).getDelta(), 0.0001);
        assertEquals(40.0, deltas.get("root.factory.d1.a").get(1).getDelta(), 0.0001);
    }

    @Test
    void singleDeltasByIntervalUsesMillisLiteral() {
        RecordingExecutor executor = new RecordingExecutor(sql -> sql.contains("GROUP BY")
                ? new IotdbResultTable(Arrays.asList("LAST_VALUE(root.factory.device02.temperature)"),
                rows(row(-300000L, 100.0), row(0L, 105.0)))
                : new IotdbResultTable(Arrays.asList("last_value(root.factory.device02.temperature)"),
                rows(row(-1L, 100.0))));
        IotdbQueryService service = new IotdbQueryService(configWith("query-batch-scope", "device"), executor);

        List<DeltaPoint> points = service.deltasByInterval(TEMPERATURE,
                Instant.ofEpochSecond(0), Instant.ofEpochSecond(600),
                300000L, FillPolicy.NONE, Direction.FORWARD, 10);

        assertEquals(1, executor.sqls.size());
        assertTrue(executor.sqls.get(0).contains("5m"));
        assertEquals(1, points.size());
        assertEquals(5.0, points.get(0).getDelta(), 0.0001);
    }

    @Test
    void rawBatchCompletionRefillsTheColumnTheRowBudgetStarved() {
        // M9/G6：合并行数预算把稀疏列挤成只剩 1 个点，这一列必须退回逐位号补采。
        RecordingExecutor executor = new RecordingExecutor(sql -> sql.contains("`, `")
                ? new IotdbResultTable(Arrays.asList("root.factory.d1.dense", "root.factory.d1.sparse"),
                        rows(row(1000L, 1.0, 9.0), row(1100L, 2.0, null), row(1200L, 3.0, null)))
                : new IotdbResultTable(Collections.singletonList("root.factory.d1.sparse"),
                        rows(row(1150L, 9.5), row(1300L, 9.7))));
        IotdbQueryService service = new IotdbQueryService(
                configWith("query-batch-scope", "database", "query-batch-row-budget", "3"), executor);
        Set<String> completed = new LinkedHashSet<String>();

        Map<String, List<IotdbModels.Point>> series = service.rawBatchCompleted(
                paths("root.factory.d1.dense", "root.factory.d1.sparse"),
                Instant.ofEpochMilli(1000L), Instant.ofEpochMilli(2000L), Direction.FORWARD, 2, completed);

        assertEquals("SELECT `d1`.`dense`, `d1`.`sparse` FROM root.`factory`"
                        + " WHERE time >= 1000 AND time < 2000 ORDER BY TIME ASC LIMIT 3",
                executor.sqls.get(0));
        assertEquals("SELECT `sparse` FROM root.`factory`.`d1` WHERE time >= 1000 AND time < 2000"
                        + " ORDER BY TIME ASC LIMIT 3", executor.sqls.get(1));
        assertEquals(2, executor.sqls.size());
        assertEquals(3, series.get("root.factory.d1.dense").size());
        // 补采列用的是逐位号答案，不是批量里那一个点
        assertEquals(2, series.get("root.factory.d1.sparse").size());
        assertEquals(9.5, ((Number) series.get("root.factory.d1.sparse").get(0).getValue()).doubleValue(), 0.0001);
        assertEquals(Collections.singleton("root.factory.d1.sparse"), completed);
    }

    @Test
    void rawBatchCompletionFallsBackToTheWholeChunkPastTheCap() {
        // 补采列数超过 query-batch-completion-max 时整块退回逐位号：
        // 位号数 × 补采数的乘积往返比批量更慢，这条边界必须可见而不是静默截断。
        RecordingExecutor executor = new RecordingExecutor(sql -> sql.contains("`, `")
                ? new IotdbResultTable(Arrays.asList("root.factory.d1.a", "root.factory.d1.b",
                        "root.factory.d1.c"), rows(row(1000L, 1.0, null, null), row(1100L, 2.0, null, null)))
                : new IotdbResultTable(Collections.singletonList("one"), rows(row(1500L, 7.0))));
        IotdbQueryService service = new IotdbQueryService(configWith(
                "query-batch-scope", "database", "query-batch-row-budget", "2",
                "query-batch-completion-max", "1"), executor);
        Set<String> completed = new LinkedHashSet<String>();

        Map<String, List<IotdbModels.Point>> series = service.rawBatchCompleted(
                paths("root.factory.d1.a", "root.factory.d1.b", "root.factory.d1.c"),
                Instant.ofEpochMilli(1000L), Instant.ofEpochMilli(2000L), Direction.FORWARD, 1, completed);

        assertEquals(4, executor.sqls.size());
        assertEquals(3, completed.size());
        assertTrue(completed.contains("root.factory.d1.a"));
        assertEquals(7.0, ((Number) series.get("root.factory.d1.b").get(0).getValue()).doubleValue(), 0.0001);
    }

    @Test
    void rawBatchWithoutAFullRowBudgetTrustsTheBatchAndDropsNullCells() {
        // 行数没撞满预算说明窗口内的行已经全部返回：稀疏列的那个 null 是真实数据，
        // 不补采，但合并行里本列的空单元格必须丢掉，否则点数比逐位号多出一堆空点。
        RecordingExecutor executor = new RecordingExecutor(sql -> new IotdbResultTable(
                Arrays.asList("root.factory.d1.dense", "root.factory.d1.sparse"),
                rows(row(1000L, 1.0, 9.0), row(1100L, 2.0, null), row(1200L, 3.0, null))));
        IotdbQueryService service = new IotdbQueryService(
                configWith("query-batch-scope", "database", "query-batch-row-budget", "10"), executor);
        Set<String> completed = new LinkedHashSet<String>();

        Map<String, List<IotdbModels.Point>> series = service.rawBatchCompleted(
                paths("root.factory.d1.dense", "root.factory.d1.sparse"),
                Instant.ofEpochMilli(1000L), Instant.ofEpochMilli(2000L), Direction.FORWARD, 2, completed);

        assertEquals("SELECT `d1`.`dense`, `d1`.`sparse` FROM root.`factory`"
                        + " WHERE time >= 1000 AND time < 2000 ORDER BY TIME ASC LIMIT 6",
                executor.sqls.get(0));
        assertEquals(1, executor.sqls.size());
        assertTrue(completed.isEmpty());
        assertEquals(3, series.get("root.factory.d1.dense").size());
        assertEquals(1, series.get("root.factory.d1.sparse").size());
    }

    @Test
    void rawBatchCompletionReportsNoExtraSqlWhenTheCallerDoesNotWantTheLedger() {
        RecordingExecutor executor = new RecordingExecutor(sql -> new IotdbResultTable(
                Collections.singletonList("root.factory.d1.a"), rows(row(1000L, 1.0))));
        IotdbQueryService service = new IotdbQueryService(
                configWith("query-batch-scope", "database"), executor);

        Map<String, List<IotdbModels.Point>> series = service.rawBatchCompleted(
                paths("root.factory.d1.a"), Instant.ofEpochMilli(1000L), Instant.ofEpochMilli(2000L),
                Direction.FORWARD, 2, null);

        assertEquals(1, series.get("root.factory.d1.a").size());
    }

    @Test
    void interpolateBatchSendsThreeStatementsPerChunkAndMatchesThePerTagGrid() {
        RecordingExecutor executor = new RecordingExecutor(sql -> {
            if (isWindow(sql)) {
                return new IotdbResultTable(Collections.singletonList("root.factory.d1.a"),
                        rows(row(1500L, 5.0), row(2500L, 6.0)));
            }
            if (sql.contains("time < 1000")) {
                return new IotdbResultTable(Collections.singletonList("root.factory.d1.a"),
                        rows(row(800L, 4.0)));
            }
            return new IotdbResultTable(Collections.singletonList("root.factory.d1.a"),
                    rows(row(3500L, 7.0)));
        });
        IotdbQueryService service = new IotdbQueryService(
                configWith("query-batch-scope", "database"), executor);

        IotdbQueryService.Interpolation batched = service.interpolateBatch(
                paths("root.factory.d1.a"), Instant.ofEpochMilli(1000L), 1000L, 3, 0L, 10, null)
                .get("root.factory.d1.a");

        assertEquals("SELECT `d1`.`a` FROM root.`factory` WHERE time < 1000 ORDER BY TIME DESC LIMIT 1",
                executor.sqls.get(0));
        assertEquals("SELECT `d1`.`a` FROM root.`factory` WHERE time >= 1000 AND time < 3001"
                        + " ORDER BY TIME ASC LIMIT 11", executor.sqls.get(1));
        assertEquals("SELECT `d1`.`a` FROM root.`factory` WHERE time > 3000 ORDER BY TIME ASC LIMIT 1",
                executor.sqls.get(2));
        assertEquals(3, executor.sqls.size());
        assertFalse(batched.isRawTruncated());
        assertEquals(4.0 + 200.0 / 700.0, value(batched, 0), 0.0001);
        assertEquals(5.5, value(batched, 1), 0.0001);
        assertEquals(6.5, value(batched, 2), 0.0001);

        // 批量与逐位号共用同一份内插数学：同一份数据必须给出同一个网格。
        RecordingExecutor perTag = new RecordingExecutor(sql -> {
            if (isWindow(sql)) {
                return table(row(1500L, 5.0), row(2500L, 6.0));
            }
            return table(sql.contains("time < 1000") ? row(800L, 4.0) : row(3500L, 7.0));
        });
        IotdbQueryService.Interpolation direct = new IotdbQueryService(config(), perTag)
                .interpolate("root.factory.d1.a", Instant.ofEpochMilli(1000L), 1000L, 3, 0L, 10);
        assertEquals(value(batched, 1), value(direct, 1), 0.0001);
        assertEquals(value(batched, 2), value(direct, 2), 0.0001);
    }

    @Test
    void interpolateBatchCompletesTheTagTheWindowBudgetStarved() {
        RecordingExecutor executor = new RecordingExecutor(sql -> {
            if (!isWindow(sql)) {
                return sql.contains("time < 1000")
                        ? new IotdbResultTable(Arrays.asList("root.factory.d1.a", "root.factory.d1.c"),
                                rows(row(800L, 4.0, 2.0)))
                        : new IotdbResultTable(Arrays.asList("root.factory.d1.a", "root.factory.d1.c"),
                                rows(row(3500L, 7.0, 5.0)));
            }
            if (sql.contains("`, `")) {
                return new IotdbResultTable(Arrays.asList("root.factory.d1.a", "root.factory.d1.c"),
                        rows(row(1500L, 5.0, null), row(2500L, 6.0, null)));
            }
            return new IotdbResultTable(Collections.singletonList("root.factory.d1.c"), rows(row(1500L, 3.0)));
        });
        IotdbQueryService service = new IotdbQueryService(
                configWith("query-batch-scope", "database", "query-batch-row-budget", "2"), executor);
        Set<String> completed = new LinkedHashSet<String>();

        Map<String, IotdbQueryService.Interpolation> grids = service.interpolateBatch(
                paths("root.factory.d1.a", "root.factory.d1.c"), Instant.ofEpochMilli(1000L), 1000L, 3,
                0L, 1, completed);

        // 三条批量 + 一条补采；补采紧跟在它补的那一轮（窗内原始点）之后
        assertEquals(4, executor.sqls.size());
        assertEquals(Collections.singleton("root.factory.d1.c"), completed);
        assertEquals("SELECT `d1`.`a`, `d1`.`c` FROM root.`factory` WHERE time < 1000"
                + " ORDER BY TIME DESC LIMIT 2", executor.sqls.get(0));
        assertEquals("SELECT `d1`.`a`, `d1`.`c` FROM root.`factory` WHERE time >= 1000 AND time < 3001"
                + " ORDER BY TIME ASC LIMIT 2", executor.sqls.get(1));
        assertEquals("SELECT `c` FROM root.`factory`.`d1` WHERE time >= 1000 AND time < 3001"
                        + " ORDER BY TIME ASC LIMIT 2", executor.sqls.get(2));
        assertEquals("SELECT `d1`.`a`, `d1`.`c` FROM root.`factory` WHERE time > 3000"
                + " ORDER BY TIME ASC LIMIT 2", executor.sqls.get(3));
        assertEquals(5.5, value(grids.get("root.factory.d1.a"), 1), 0.0001);
        // 批量取满 rawLimit + 1 个点才敢说截断：a 拿到 2 个 > 1，如实上报
        assertTrue(grids.get("root.factory.d1.a").isRawTruncated());
        assertEquals(3.5, value(grids.get("root.factory.d1.c"), 1), 0.0001);
        assertFalse(grids.get("root.factory.d1.c").isRawTruncated());
    }

    @Test
    void batchSizeOneReproducesThePreChangeStatementPerTag() {
        // 回退档之一（计划 §七）：query-batch-size: 1 时任何族都退化成一条一个表达式，
        // 文本必须与改造前逐字节相同，否则现场没有可用的回退。
        RecordingExecutor executor = new RecordingExecutor(sql -> table(row(1000L, 1.0)));
        IotdbQueryService service = new IotdbQueryService(
                configWith("query-batch-scope", "device", "query-batch-size", "1"), executor);

        service.intervalBatch(paths("root.factory.d1.a", "root.factory.d1.b"),
                Instant.ofEpochMilli(1000L), Instant.ofEpochMilli(3000L),
                1000L, Aggregation.AVG, FillPolicy.NONE);

        assertEquals(2, executor.sqls.size());
        assertEquals(IotdbSql.interval(Grouping.DEVICE,
                Collections.singletonList(IotdbTagPath.ofAbsolute("root.factory.d1.a")),
                Aggregation.AVG, 1000L, 3000L, IotdbSql.intervalLiteral(1000L), FillPolicy.NONE),
                executor.sqls.get(0));
        assertEquals(IotdbSql.interval(Grouping.DEVICE,
                Collections.singletonList(IotdbTagPath.ofAbsolute("root.factory.d1.b")),
                Aggregation.AVG, 1000L, 3000L, IotdbSql.intervalLiteral(1000L), FillPolicy.NONE),
                executor.sqls.get(1));
    }

    @Test
    void deviceScopeMergesMeasurementsOfTheSameDeviceLikeBefore() {
        // 回退档之二：query-batch-scope: device 保留“同设备位号合并成一条 SQL”的旧行为。
        RecordingExecutor executor = new RecordingExecutor(sql -> table2(row(1000L, 1.0, 2.0)));
        IotdbQueryService service = new IotdbQueryService(
                configWith("query-batch-scope", "device"), executor);

        service.intervalBatch(paths("root.factory.d1.a", "root.factory.d1.b"),
                Instant.ofEpochMilli(1000L), Instant.ofEpochMilli(3000L),
                1000L, Aggregation.AVG, FillPolicy.NONE);

        assertEquals(1, executor.sqls.size());
        assertEquals(IotdbSql.interval(Grouping.DEVICE,
                Collections.singletonList(IotdbTagPath.ofAbsolute("root.factory.d1.a")),
                Aggregation.AVG, 1000L, 3000L, IotdbSql.intervalLiteral(1000L), FillPolicy.NONE)
                .replace("AVG(`a`)", "AVG(`a`), AVG(`b`)"), executor.sqls.get(0));
    }

    /**
     * 取网格上第 {@code slot} 个槽位的数值。
     *
     * @param interpolation 插值结果
     * @param slot          槽位序号
     * @return 数值
     */
    private static double value(IotdbQueryService.Interpolation interpolation, int slot) {
        return ((Number) interpolation.getPoints().get(slot).getValue()).doubleValue();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 取出点的数值：REST 通道把 {@code Value} 列以文本下发，
     * 单测里两种形态都要能读，故统一转成 {@code Double}。
     */
    private static Object numeric(IotdbModels.Point point) {
        Object value = point.getValue();
        if (value instanceof Number) {
            return value;
        }
        return Double.valueOf(String.valueOf(value));
    }

    private static IotdbResultTable.Row lastRow(String timeseries, long timestamp, Object value) {
        return new IotdbResultTable.Row(timestamp, new Object[]{timeseries, value, "FLOAT"});
    }

    private static IotdbResultTable lastTable(IotdbResultTable.Row... rows) {
        return new IotdbResultTable(
                Arrays.asList("Timeseries", "Value", "DataType"), rows(rows));
    }

    private static IotdbConfig config() {
        return configWith();
    }

    /**
     * 用给定的 {@code key, value, key, value…} 装配一个测试配置。
     *
     * <p>缺省把 {@code query-parallelism} 压到 1：{@link RecordingExecutor} 记录的是语句
     * <b>到达</b>顺序，而并行度大于 1 时同一批的几条语句由线程池并发提交，于是所有断言
     * {@code sqls.get(i)} 文本的用例都会偶发红。并行只改变发出顺序、不改变回填顺序这件事
     * 由 {@code currentBatchReturnsRequestOrderWhenDevicesResolveOutOfOrder} 专门覆盖，
     * 那里显式传 {@code query-parallelism: 4}，并且必须回退到 {@code device} 档——
     * 默认库级档位下同库位号只有一条 SQL，没有“多条语句乱序提交”可测。</p>
     *
     * @param keyValues 成对的选项键值，可为空；同名键覆盖这里的缺省
     * @return 非 {@code null} 的驱动配置
     */
    private static IotdbConfig configWith(String... keyValues) {
        TsdbInstanceConfig instance = new TsdbInstanceConfig();
        instance.setBaseUrl("http://127.0.0.1:18080");
        instance.getAuth().setUsername("root");
        instance.getAuth().setPassword("root");
        instance.getOptions().put("query-parallelism", "1");
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            instance.getOptions().put(keyValues[i], keyValues[i + 1]);
        }
        SecurityConfig security = new SecurityConfig();
        security.setAllowInsecureTransport(true);
        return new IotdbConfig("test", instance, security);
    }

    /**
     * 把位号文本解析成路径对象：新批量入口只接 {@link IotdbTagPath}，
     * 校验在解析这一遍做完，SQL 生成器不再重复处理裸字符串。
     *
     * @param tags 完整序列路径文本
     * @return 与入参同序的路径对象列表
     */
    private static List<IotdbTagPath> paths(String... tags) {
        List<IotdbTagPath> parsed = new ArrayList<IotdbTagPath>(tags.length);
        for (String tag : tags) {
            parsed.add(IotdbTagPath.ofAbsolute(tag));
        }
        return parsed;
    }

    private static IotdbResultTable.Row row(long timestamp, Object... values) {
        return new IotdbResultTable.Row(timestamp, values);
    }

    /**
     * 判断一条 SQL 是否为“窗口内原始点”查询。
     *
     * <p>插值一次发出三类语句：窗口内原始点查询带 {@code time >=} 下界，
     * 左包围点只有 {@code time <}，右包围点只有 {@code time >}。必须按这个
     * 特征分流，否则包围点会被当成窗口数据、窗口数据又被包围点分支吞掉，
     * 用例可能因为巧合的线性关系而“假绿”。</p>
     *
     * @param sql 待判断的 SQL
     * @return {@code true} 表示是窗口内原始点查询
     */
    private static boolean isWindow(String sql) {
        return sql.contains("time >=");
    }

    private static IotdbResultTable table(IotdbResultTable.Row... rows) {
        return new IotdbResultTable(Collections.singletonList("value"), rows(rows));
    }

    private static IotdbResultTable table2(IotdbResultTable.Row... rows) {
        return new IotdbResultTable(Arrays.asList("first_value", "last_value"), rows(rows));
    }

    private static List<IotdbResultTable.Row> rows(IotdbResultTable.Row... rows) {
        return new ArrayList<IotdbResultTable.Row>(Arrays.asList(rows));
    }

    /**
     * 按 SQL 文本按需应答的执行器，用于插值与整窗增量这类
     * “同一次调用发多条形态不同的 SQL”的用例。
     */
    private static final class RecordingExecutor implements IotdbQueryExecutor {
        /** 并行批量最新值会从多个线程写入，因此必须用同步列表。 */
        private final List<String> sqls = Collections.synchronizedList(new ArrayList<String>());
        private final Function<String, IotdbResultTable> responder;

        private RecordingExecutor(Function<String, IotdbResultTable> responder) {
            this.responder = responder;
        }

        @Override
        public IotdbResultTable query(String sql) {
            sqls.add(sql);
            return responder.apply(sql);
        }

        @Override
        public String transport() {
            return "recording";
        }

        @Override
        public void close() {
        }
    }

    private static final class ScriptedExecutor implements IotdbQueryExecutor {
        private final List<String> sqls = new ArrayList<String>();
        private List<IotdbResultTable.Row> intervalRows = Collections.emptyList();
        private List<IotdbResultTable.Row> baselineRows = Collections.emptyList();
        private List<IotdbResultTable.Row> versionRows = Collections.emptyList();

        @Override
        public IotdbResultTable query(String sql) {
            sqls.add(sql);
            if (sql.contains("SHOW VERSION")) {
                return new IotdbResultTable(Collections.singletonList("Version"), versionRows);
            }
            if (sql.contains("GROUP BY")) {
                return new IotdbResultTable(Collections.singletonList("Last_value"), intervalRows);
            }
            if (sql.contains("LAST_VALUE")) {
                return new IotdbResultTable(Collections.singletonList("Last_value"), baselineRows);
            }
            return new IotdbResultTable(Collections.singletonList("Last_value"), intervalRows);
        }

        @Override
        public String transport() {
            return "scripted";
        }

        @Override
        public void close() {
        }
    }
}
