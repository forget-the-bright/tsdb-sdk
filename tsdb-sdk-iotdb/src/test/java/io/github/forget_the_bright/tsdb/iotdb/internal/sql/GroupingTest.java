package io.github.forget_the_bright.tsdb.iotdb.internal.sql;

import io.github.forget_the_bright.tsdb.common.exception.TsdbDataException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Grouping} 的离线单测：三档的分组键、{@code FROM} 前缀与列表达式配对，
 * 以及跨组拒绝。
 *
 * <p>{@code FROM} 层级与列表达式层级必须同源（2026-11-13 生产 2.0.11 实测的 M1：
 * 错配<b>静默返回空</b>而不是报错）。这里把三档的配对文本逐字符钉住，就是为了让
 * “各调用点自己拼一侧”这种做法在测试层面无处藏身。</p>
 */
class GroupingTest {

    /** 业务里最常见的形态：两级设备 + 含连字符的测点。 */
    private static final String BUSINESS_PATH = "root.factory.RL.FCS0107.TI-1071815_PV";

    private static final String OTHER_DEVICE_IN_SAME_DB = "root.factory.BGZH2A.C_PV";

    private static final String OTHER_DATABASE = "root.tlplant.casting1.n01.temp";

    private static IotdbTagPath path(String text) {
        return IotdbTagPath.ofAbsolute(text);
    }

    @Test
    void databaseScopeUsesDatabaseGroupKeyAndDeviceRelativeExpression() {
        IotdbTagPath parsed = path(BUSINESS_PATH);
        assertEquals("root.factory", Grouping.DATABASE.groupKey(parsed));
        assertEquals("root.`factory`", Grouping.DATABASE.fromPrefix(Collections.singletonList(parsed)));
        assertEquals("`RL`.`FCS0107`.`TI-1071815_PV`", Grouping.DATABASE.expression(parsed));
    }

    @Test
    void rootScopeGroupsEverythingUnderOneBatchAndKeepsTheDatabaseNode() {
        IotdbTagPath parsed = path(BUSINESS_PATH);
        assertEquals("root", Grouping.ROOT.groupKey(parsed));
        assertEquals("root", Grouping.ROOT.fromPrefix(Arrays.asList(parsed, path(OTHER_DATABASE))));
        assertEquals("`factory`.`RL`.`FCS0107`.`TI-1071815_PV`", Grouping.ROOT.expression(parsed));
    }

    @Test
    void deviceScopeKeepsThePreExistingOneDevicePerStatementShape() {
        IotdbTagPath parsed = path(BUSINESS_PATH);
        assertEquals("root.factory.RL.FCS0107", Grouping.DEVICE.groupKey(parsed));
        assertEquals("root.`factory`.`RL`.`FCS0107`",
                Grouping.DEVICE.fromPrefix(Collections.singletonList(parsed)));
        assertEquals("`TI-1071815_PV`", Grouping.DEVICE.expression(parsed));
    }

    @Test
    void noScopeEverEmitsTheRootAnchorInAColumnExpression() {
        IotdbTagPath parsed = path(BUSINESS_PATH);
        for (Grouping grouping : Grouping.values()) {
            String expression = grouping.expression(parsed);
            assertTrue(!expression.contains("root."), grouping + " leaked the root anchor: " + expression);
            assertTrue(!expression.contains("`root`"), grouping + " quoted the root anchor: " + expression);
        }
    }

    @Test
    void databaseScopeAcceptsSeveralDevicesOfOneDatabase() {
        List<IotdbTagPath> group = Arrays.asList(path(BUSINESS_PATH), path(OTHER_DEVICE_IN_SAME_DB));
        Grouping.DATABASE.requireSingleGroup(group);
        assertEquals("root.`factory`", Grouping.DATABASE.fromPrefix(group));
    }

    @Test
    void databaseScopeRejectsAChunkThatSpansTwoDatabases() {
        List<IotdbTagPath> group = Arrays.asList(path(BUSINESS_PATH), path(OTHER_DATABASE));

        TsdbDataException failure = assertThrows(TsdbDataException.class,
                () -> Grouping.DATABASE.fromPrefix(group));
        assertTrue(failure.getMessage().contains("root.factory vs root.tlplant"), failure.getMessage());
    }

    @Test
    void deviceScopeRejectsTwoDevicesOfTheSameDatabase() {
        List<IotdbTagPath> group = Arrays.asList(path(BUSINESS_PATH), path(OTHER_DEVICE_IN_SAME_DB));

        TsdbDataException crossGroup = assertThrows(TsdbDataException.class,
                () -> Grouping.DEVICE.fromPrefix(group));
        assertTrue(crossGroup.getMessage().contains("mixes different groups"), crossGroup.getMessage());
        assertTrue(crossGroup.getMessage().contains("root.factory.RL.FCS0107 vs root.factory.BGZH2A"),
                crossGroup.getMessage());
    }

    @Test
    void deviceScopeStillAcceptsSeveralMeasurementsOfTheSameDevice() {
        IotdbTagPath sibling = path("root.factory.RL.FCS0107.TI-1071816_PV");
        List<IotdbTagPath> group = Arrays.asList(path(BUSINESS_PATH), sibling);

        Grouping.DEVICE.requireSingleGroup(group);
        assertEquals("root.`factory`.`RL`.`FCS0107`", Grouping.DEVICE.fromPrefix(group));
    }

    @Test
    void emptyGroupIsRejectedRatherThanProducingAFROMWithoutColumns() {
        for (Grouping grouping : Grouping.values()) {
            TsdbDataException failure = assertThrows(TsdbDataException.class,
                    () -> grouping.fromPrefix(Collections.<IotdbTagPath>emptyList()));
            assertTrue(failure.getMessage().contains("at least one tag path"), failure.getMessage());
        }
        assertThrows(TsdbDataException.class,
                () -> Grouping.DATABASE.requireSingleGroup(null));
    }
}
