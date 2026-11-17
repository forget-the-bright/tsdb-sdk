package io.github.forget_the_bright.tsdb.iotdb.internal.query;

import org.junit.jupiter.api.Test;

import io.github.forget_the_bright.tsdb.iotdb.internal.sql.IotdbTagPath;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link SeriesKeyIndex} 的离线单测：按名回填的三个要点——
 * 规范化能命中、服务端没给的行就是 {@code null}、大小写不合并。
 *
 * <p>批量 {@code SELECT LAST} 的行序不保证等于请求顺序，且不存在的序列被整行丢弃
 * （2026-11-13 生产 2.0.11 实测的 M2/G5）。索引错一次就是“把别人的最新值挂到
 * 这个位号名下”，比查不到更糟，所以这里连“未命中不许就近匹配”都要钉住。</p>
 */
class SeriesKeyIndexTest {

    private static IotdbTagPath path(String text) {
        return IotdbTagPath.ofAbsolute(text);
    }

    @Test
    void matchesTheServerLabelAfterDroppingQuotesAndWhitespace() {
        SeriesKeyIndex<String> index = new SeriesKeyIndex<String>();
        index.put(" root.factory.RL.FCS0107.TI-1071815_PV ", "1.5");

        assertEquals("1.5", index.get(path("root.factory.RL.FCS0107.TI-1071815_PV")));
    }

    @Test
    void matchesFullyQuotedAndFunctionWrappedLabels() {
        SeriesKeyIndex<String> index = new SeriesKeyIndex<String>();
        index.put("`root`.`factory`.`d1`.`a`", "quoted");
        index.put("last_value(root.factory.d2.b)", "wrapped");

        assertEquals("quoted", index.get(path("root.factory.d1.a")));
        assertEquals("wrapped", index.get(path("root.factory.d2.b")));
    }

    @Test
    void leavesADroppedRowUnmatchedInsteadOfShiftingTheNextOne() {
        SeriesKeyIndex<String> index = new SeriesKeyIndex<String>();
        index.put("root.factory.d1.a", "first");
        index.put("root.factory.d2.b", "second");

        assertEquals(2, index.size());
        assertEquals("second", index.get(path("root.factory.d2.b")));
        // 服务端把不存在的序列整行丢弃，这里必须是 null 占位，不能回落到别人的值。
        assertNull(index.get(path("root.factory.d3.c")));
    }

    @Test
    void doesNotMergeKeysThatDifferOnlyInCase() {
        SeriesKeyIndex<String> index = new SeriesKeyIndex<String>();
        index.put("root.factory.d1.PV", "upper");
        index.put("root.factory.d1.pv", "lower");

        // 节点名大小写敏感，只差大小写的两条序列可以真实共存，合并就是串位。
        assertEquals(2, index.size());
        assertEquals("upper", index.get(path("root.factory.d1.PV")));
        assertEquals("lower", index.get(path("root.factory.d1.pv")));
    }

    @Test
    void ignoresLabelsWithoutAUsableKeyAndKeepsTheLastWriteForADuplicate() {
        SeriesKeyIndex<String> index = new SeriesKeyIndex<String>();
        index.put(null, "dropped");
        index.put("   ", "dropped");
        index.put("root.factory.d1.a", "first");
        index.put("root.factory.d1.a", "second");

        assertEquals(1, index.size());
        assertEquals("second", index.get(path("root.factory.d1.a")));
    }

    @Test
    void keepsEveryRequestedRowWhenTheAnswerComesOutOfOrder() {
        SeriesKeyIndex<String> index = new SeriesKeyIndex<String>();
        // 实测响应顺序与请求顺序不同（G5），按名回填后仍要能逐位号取回。
        index.put("root.factory.d3.c", "c");
        index.put("root.factory.d1.a", "a");
        index.put("root.factory.d2.b", "b");

        StringBuilder byRequestOrder = new StringBuilder();
        for (IotdbTagPath path : new IotdbTagPath[]{path("root.factory.d1.a"),
                path("root.factory.d2.b"), path("root.factory.d3.c")}) {
            byRequestOrder.append(index.get(path));
        }
        assertEquals("abc", byRequestOrder.toString());
    }
}
