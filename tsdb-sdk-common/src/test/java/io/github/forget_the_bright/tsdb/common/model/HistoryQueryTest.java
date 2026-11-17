package io.github.forget_the_bright.tsdb.common.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link HistoryQuery} 构造校验测试，重点覆盖服务端点数 {@code count}
 * 与 {@code interval} 的“二选一”规则。
 */
class HistoryQueryTest {

    private static final Instant START = Instant.parse("2023-11-14T00:00:00Z");
    private static final Instant END = Instant.parse("2023-11-15T00:00:00Z");

    private static HistoryQuery.Builder base(QueryMode mode) {
        return HistoryQuery.builder().tags("AI-101").range(START, END).mode(mode);
    }

    @Test
    void countOnlyIsValidForIntervalModes() {
        for (QueryMode mode : new QueryMode[]{QueryMode.AGGREGATED, QueryMode.INTERPOLATED, QueryMode.SAMPLED}) {
            HistoryQuery.Builder builder = base(mode).count(24);
            if (mode == QueryMode.AGGREGATED) {
                builder.aggregation(Aggregation.AVG);
            }
            HistoryQuery query = builder.build();
            assertEquals(Integer.valueOf(24), query.getCount());
            assertNull(query.getInterval());
        }
    }

    @Test
    void intervalOnlyRemainsValid() {
        HistoryQuery query = base(QueryMode.AGGREGATED)
                .aggregation(Aggregation.AVG)
                .interval(Duration.ofMinutes(1))
                .build();
        assertNull(query.getCount());
        assertEquals(Duration.ofMinutes(1), query.getInterval());
    }

    @Test
    void countMustBePositive() {
        assertThrows(IllegalArgumentException.class,
                () -> base(QueryMode.INTERPOLATED).count(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> base(QueryMode.INTERPOLATED).count(-1).build());
    }

    @Test
    void intervalModesRequireIntervalOrCount() {
        assertThrows(IllegalArgumentException.class,
                () -> base(QueryMode.AGGREGATED).aggregation(Aggregation.AVG).build());
        assertThrows(IllegalArgumentException.class,
                () -> base(QueryMode.INTERPOLATED).build());
        assertThrows(IllegalArgumentException.class,
                () -> base(QueryMode.SAMPLED).build());
    }

    @Test
    void zeroOrNegativeIntervalDoesNotSatisfyRequirement() {
        assertThrows(IllegalArgumentException.class,
                () -> base(QueryMode.INTERPOLATED).interval(Duration.ZERO).build());
        assertThrows(IllegalArgumentException.class,
                () -> base(QueryMode.INTERPOLATED).interval(Duration.ofSeconds(-1)).build());
    }

    @Test
    void rawModeNeedsNeitherIntervalNorCount() {
        HistoryQuery query = base(QueryMode.RAW).build();
        assertNull(query.getCount());
        assertNull(query.getInterval());
    }

    @Test
    void intervalSumNeedsNeitherIntervalNorCount() {
        HistoryQuery query = base(QueryMode.AGGREGATED)
                .aggregation(Aggregation.INTERVAL_SUM)
                .build();
        assertNull(query.getCount());
        assertNull(query.getInterval());
        assertEquals(Aggregation.INTERVAL_SUM, query.getAggregation());
        // 豁免只针对 INTERVAL_SUM：其它聚合仍要求 interval 或 count。
        assertThrows(IllegalArgumentException.class,
                () -> base(QueryMode.AGGREGATED).aggregation(Aggregation.SUM).build());
    }

    @Test
    void countDefaultsToNull() {
        assertNull(base(QueryMode.RAW).build().getCount());
    }
}