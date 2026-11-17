package io.github.forget_the_bright.tsdb.iotdb.internal.sql;

import io.github.forget_the_bright.tsdb.common.exception.TsdbDataException;
import io.github.forget_the_bright.tsdb.common.model.Aggregation;
import io.github.forget_the_bright.tsdb.common.model.Direction;
import io.github.forget_the_bright.tsdb.common.model.FillPolicy;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IotdbTagPath} 的离线单测：层级派生、三态入参与拒绝表。
 *
 * <p>这里的期望文本就是上线 SQL 的片段，因此引号位置、{@code root} 裸写、
 * 库相对层级数都必须逐字符钉住。</p>
 */
class IotdbTagPathTest {

    private static final String DEFAULT_DATABASE = "root.factory";

    /** 业务里最常见的形态：两级设备 + 含连字符的测点。 */
    private static final String BUSINESS_PATH = "root.factory.RL.FCS0107.TI-1071815_PV";

    @Test
    void derivesEveryLevelFromAFourPlusNodeAbsolutePath() {
        IotdbTagPath path = IotdbTagPath.parse(BUSINESS_PATH, DEFAULT_DATABASE);
        assertEquals("root.factory.RL.FCS0107.TI-1071815_PV", path.getAbsolutePath());
        assertEquals("root.factory", path.getDatabase());
        assertEquals("root.factory.RL.FCS0107", path.getDevicePath());
        assertEquals("TI-1071815_PV", path.getMeasurement());
        assertArrayEquals(new String[]{"root", "factory", "RL", "FCS0107", "TI-1071815_PV"}, path.getNodes());
        path.getNodes()[1] = "swapped";
        assertEquals("root.factory", path.getDatabase());
    }

    @Test
    void derivedFormsPairWithTheMatchingFromPrefix() {
        IotdbTagPath path = IotdbTagPath.parse(BUSINESS_PATH, DEFAULT_DATABASE);
        assertEquals("root.`factory`", path.quotedDatabase());
        assertEquals("root.`factory`.`RL`.`FCS0107`", path.quotedDevicePath());
        assertEquals("root.`factory`.`RL`.`FCS0107`.`TI-1071815_PV`", path.quotedAbsolutePath());
        assertEquals("`RL`.`FCS0107`.`TI-1071815_PV`", path.fromDatabase());
        assertEquals("`factory`.`RL`.`FCS0107`.`TI-1071815_PV`", path.fromRoot());
        assertEquals("`TI-1071815_PV`", path.fromDevice());
    }

    @Test
    void relativeTagIsPrefixedWithTheDefaultDatabaseNodeByNode() {
        IotdbTagPath path = IotdbTagPath.parse("RL.FCS0107.TI-1071815_PV", DEFAULT_DATABASE);
        assertEquals(BUSINESS_PATH, path.getAbsolutePath());
        assertEquals("`RL`.`FCS0107`.`TI-1071815_PV`", path.fromDatabase());
    }

    @Test
    void relativeTagWithoutADefaultDatabaseFailsInsteadOfGuessing() {
        TsdbDataException failure = assertThrows(TsdbDataException.class,
                () -> IotdbTagPath.parse("RL.FCS0107.TI-1071815_PV", null));
        assertTrue(failure.getMessage().contains("default-database"), failure.getMessage());
    }

    @Test
    void defaultDatabaseMustBeTheFixedTwoLevels() {
        TsdbDataException failure = assertThrows(TsdbDataException.class,
                () -> IotdbTagPath.parse("d1.t", "root.factory.line1"));
        assertTrue(failure.getMessage().contains("root.<database>"), failure.getMessage());
    }

    @Test
    void quotedInputIsParsedAndReEmittedCanonically() {
        IotdbTagPath path = IotdbTagPath.parse("root.sg.`d1`.temperature", DEFAULT_DATABASE);
        assertEquals("root.`sg`.`d1`.`temperature`", path.quotedAbsolutePath());
        assertEquals("`d1`.`temperature`", path.fromDatabase());
        assertEquals("root.sg.d1.temperature", path.getAbsolutePath());
    }

    @Test
    void quotedRootAnchorIsRewrittenBare() {
        IotdbTagPath path = IotdbTagPath.parse("`root`.`sg`.`d1`.`t`", DEFAULT_DATABASE);
        assertEquals("root.`sg`.`d1`.`t`", path.quotedAbsolutePath());
        assertEquals("root.sg.d1.t", path.getAbsolutePath());
    }

    @Test
    void measurementOnlyQuotingMatchesTheBusinessExample() {
        IotdbTagPath path = IotdbTagPath.parse("root.factory.RL.FCS0107.`TI-1071815_PV`", DEFAULT_DATABASE);
        assertEquals(BUSINESS_PATH, path.getAbsolutePath());
        assertEquals("`RL`.`FCS0107`.`TI-1071815_PV`", path.fromDatabase());
    }

    @Test
    void quotingShapesOfTheSamePathAreEquivalent() {
        IotdbTagPath plain = IotdbTagPath.parse(BUSINESS_PATH, DEFAULT_DATABASE);
        IotdbTagPath quoted = IotdbTagPath.parse("root.`factory`.RL.`FCS0107`.`TI-1071815_PV`", DEFAULT_DATABASE);
        assertEquals(plain, quoted);
        assertEquals(plain.getKey(), quoted.getKey());
        assertEquals(plain.quotedAbsolutePath(), quoted.quotedAbsolutePath());
    }

    @Test
    void rawKeepsTheCallersTextWhileDerivedFormsAreCanonical() {
        String input = "  root.factory.RL.`FCS0107`.TI-1071815_PV  ";
        IotdbTagPath path = IotdbTagPath.parse(input, DEFAULT_DATABASE);
        assertEquals("root.factory.RL.`FCS0107`.TI-1071815_PV", path.getRaw());
        assertEquals(BUSINESS_PATH, path.getAbsolutePath());
    }

    @Test
    void anotherDatabaseIsNotReadAsTheDefaultOne() {
        // 按节点判断全名，不能用 startsWith("root." + 默认库)：否则 root.factoryx 被当成 root.factory 的位号
        IotdbTagPath path = IotdbTagPath.parse("root.factoryx.d1.t", DEFAULT_DATABASE);
        assertEquals("root.factoryx", path.getDatabase());
        assertEquals("`d1`.`t`", path.fromDatabase());
    }

    @Test
    void chineseHyphenNumericAndBracketNodesSurviveTheWhitelist() {
        assertEquals("root.`工厂`.`装置1`.`流量`",
                IotdbTagPath.parse("root.工厂.装置1.流量", DEFAULT_DATABASE).quotedAbsolutePath());
        assertEquals("root.`factory`.`101`.`PT-1001A`",
                IotdbTagPath.parse("root.factory.101.PT-1001A", DEFAULT_DATABASE).quotedAbsolutePath());
        assertEquals("root.`factory`.`d1`.`(a+b)`",
                IotdbTagPath.parse("root.factory.d1.(a+b)", DEFAULT_DATABASE).quotedAbsolutePath());
    }

    @Test
    void rejectsSemicolonsAndQuotesInsideAnyNode() {
        assertRejected("root.sg.d1.temperature; DROP DATABASE root.**");
        assertRejected("root.sg.d1.temp' OR '1'='1");
        assertRejected("root.sg.`d1`;DROP");
        assertRejected("root.sg.d1.tem\"p");
    }

    @Test
    void rejectsUnclosedAndDoubledBackticks() {
        TsdbDataException unclosed = assertThrows(TsdbDataException.class,
                () -> IotdbTagPath.parse("root.sg.`d1.t", DEFAULT_DATABASE));
        assertTrue(unclosed.getMessage().contains("unclosed backtick"), unclosed.getMessage());

        TsdbDataException doubled = assertThrows(TsdbDataException.class,
                () -> IotdbTagPath.parse("root.sg.`a``b`.t", DEFAULT_DATABASE));
        assertTrue(doubled.getMessage().contains("separate"), doubled.getMessage());

        assertRejected("root.sg``.d1.t");
    }

    @Test
    void rejectsEmptyNodesAndStraySeparators() {
        assertRejected("root.sg..d1.t");
        assertRejected(".root.sg.d1.t");
        assertRejected("root.sg.d1.t.");
        assertRejected("root.sg.`d1`.");
        assertRejected("root..d1.t");
    }

    @Test
    void rejectsWildcardsBecauseATagIsNotAMask() {
        assertRejected("root.sg.**");
        assertRejected("root.sg.d1.*");
        assertRejected("root.sg.`d1`.`t*`");
    }

    @Test
    void rejectsPathsShallowerThanDatabaseDeviceMeasurement() {
        assertRejected("root.factory.device");
        assertRejected("root.factory");
        assertRejected("factory");
        assertRejected("root.PV");
    }

    @Test
    void rejectsUnresolvedPlaceholdersAndPurelyNonAsciiTags() {
        assertRejected("${device}.PV");
        assertRejected("#{device}.PV");
        assertRejected("待补充位号");
        assertRejected("待补充.设备.位号");
        // 全名称不经过占位检查（首节点 root 自带 ASCII 字母），靠层级与白名单拦住
        assertRejected("root.待补充.设备");
    }

    @Test
    void rejectsBlankNullOrOversizedTagText() {
        assertRejected(null);
        assertRejected("   ");
        StringBuilder longTag = new StringBuilder("root.factory.d1.");
        while (longTag.length() <= 512) {
            longTag.append('a');
        }
        assertRejected(longTag.toString());
    }

    @Test
    void ofAbsoluteRefusesARelativeMappingValue() {
        TsdbDataException failure = assertThrows(TsdbDataException.class,
                () -> IotdbTagPath.ofAbsolute("RL.FCS0107.TI-1071815_PV"));
        assertTrue(failure.getMessage().contains("full 'root.' path"), failure.getMessage());
        assertEquals(BUSINESS_PATH, IotdbTagPath.ofAbsolute(BUSINESS_PATH).getAbsolutePath());
    }

    @Test
    void normalizeKeyStripsTheFunctionWrapperAndQuotesButKeepsCase() {
        assertEquals("root.sdk_it.device01.flow",
                IotdbTagPath.normalizeKey("last_value(root.sdk_it.device01.flow)"));
        assertEquals("root.sdk_it.device01.flow",
                IotdbTagPath.normalizeKey("LAST_VALUE(root.sdk_it.device01.flow)"));
        assertEquals("root.sdk_it.device01.flow",
                IotdbTagPath.normalizeKey("  `root`.`sdk_it`.`device01`.`flow`  "));
        assertEquals("root.Factory.d1.T_PV", IotdbTagPath.normalizeKey("last_value(root.Factory.d1.T_PV)"));
        assertNull(IotdbTagPath.normalizeKey(null));
    }

    @Test
    void derivedFormsAreExactlyWhatTheSqlBuildersEmit() {
        // “SQL 文本不变”的证据：DEVICE 档位的单元素批量与逐位号公开方法同源，
        // 且 FROM 前缀与列表达式由同一个路径对象派生（层级错配在服务端只静默返空）
        String[] paths = {
                BUSINESS_PATH,
                "root.factory.BGZH1A.X-PV",
                "root.factory.101.PT-1001A",
                "root.sg.d1.temperature",
                "root.tlplant.casting1.n01.temp",
                "root.工厂.装置1.流量",
        };
        for (String path : paths) {
            IotdbTagPath parsed = IotdbTagPath.ofAbsolute(path);
            List<IotdbTagPath> single = Collections.singletonList(parsed);
            assertEquals("SELECT LAST " + parsed.fromDevice() + " FROM " + parsed.quotedDevicePath(),
                    IotdbSql.currentBatch(Grouping.DEVICE, single), path);
            assertEquals("SELECT " + parsed.fromDatabase() + " FROM " + parsed.quotedDatabase()
                            + " WHERE time >= 0 AND time < 1000 ORDER BY TIME ASC LIMIT 1",
                    IotdbSql.raw(Grouping.DATABASE, single, 0L, 1000L, Direction.FORWARD, 1), path);
            assertEquals("SELECT " + parsed.fromRoot() + " FROM root"
                            + " WHERE time >= 0 AND time < 1000 ORDER BY TIME ASC LIMIT 1",
                    IotdbSql.raw(Grouping.ROOT, single, 0L, 1000L, Direction.FORWARD, 1), path);
            // DEVICE 档位是改造前的逐位号形态：六族入口都必须由同一个路径对象派生
            assertEquals("SELECT " + parsed.fromDevice() + " FROM " + parsed.quotedDevicePath()
                            + " WHERE time < 1000 ORDER BY TIME DESC LIMIT 1",
                    IotdbSql.nearest(Grouping.DEVICE, single, 1000L, Direction.BACKWARD, 1), path);
            assertEquals("SELECT AVG(" + parsed.fromDevice() + ") FROM " + parsed.quotedDevicePath()
                            + " WHERE time >= 0 AND time < 1000 GROUP BY ([0,1000),1s)"
                            + " ORDER BY TIME ASC LIMIT 10",
                    IotdbSql.aggregated(Grouping.DEVICE, single, Aggregation.AVG, 0L, 1000L,
                            1_000L, Direction.FORWARD, 10), path);
            assertEquals("SELECT LAST_VALUE(" + parsed.fromDevice() + ") FROM "
                            + parsed.quotedDevicePath()
                            + " WHERE time >= 0 AND time < 1000 GROUP BY ([0,1000),1m) FILL(PREVIOUS)",
                    IotdbSql.interval(Grouping.DEVICE, single, Aggregation.LAST, 0L, 1000L, "1m",
                            FillPolicy.PREVIOUS), path);
            assertEquals("SELECT first_value(" + parsed.fromDevice() + "), last_value("
                            + parsed.fromDevice() + ") FROM " + parsed.quotedDevicePath()
                            + " WHERE time >= 0 AND time < 1000",
                    IotdbSql.intervalSum(Grouping.DEVICE, single, 0L, 1000L), path);
            assertEquals("SELECT LAST_VALUE(" + parsed.fromDevice() + ") FROM "
                            + parsed.quotedDevicePath() + " WHERE time < 1000",
                    IotdbSql.baseline(Grouping.DEVICE, single, 1000L), path);
        }
    }

    @Test
    void equalityIsTheCanonicalPathSoChunksDedupeCorrectly() {
        Map<IotdbTagPath, String> index = new HashMap<IotdbTagPath, String>();
        index.put(IotdbTagPath.parse(BUSINESS_PATH, DEFAULT_DATABASE), "first");
        index.put(IotdbTagPath.parse("root.`factory`.`RL`.`FCS0107`.`TI-1071815_PV`", DEFAULT_DATABASE),
                "second");
        assertEquals(1, index.size());
        assertEquals("second", index.get(IotdbTagPath.ofAbsolute(BUSINESS_PATH)));
        assertNotEquals(IotdbTagPath.ofAbsolute(BUSINESS_PATH),
                IotdbTagPath.ofAbsolute("root.factory.RL.FCS0106.TI-1071815_PV"));
    }

    @Test
    void isAbsoluteTextLooksAtTheFirstNodeOnly() {
        assertTrue(IotdbTagPath.isAbsoluteText("root.factory.d1.t"));
        assertTrue(IotdbTagPath.isAbsoluteText("  `root`.`factory`.`d1`.`t`  "));
        assertTrue(IotdbTagPath.isAbsoluteText("root.factoryx.d1.t"));
        assertEquals(false, IotdbTagPath.isAbsoluteText("factory.d1.t"));
        assertEquals(false, IotdbTagPath.isAbsoluteText("rootless"));
        assertEquals(false, IotdbTagPath.isAbsoluteText(null));
        assertEquals(false, IotdbTagPath.isAbsoluteText(" "));
    }

    private static void assertRejected(String tagText) {
        assertThrows(TsdbDataException.class, () -> IotdbTagPath.parse(tagText, DEFAULT_DATABASE));
    }
}
