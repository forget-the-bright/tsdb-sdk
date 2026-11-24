package io.github.forget_the_bright.tsdb.pspace.internal.adapter;

import io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException;
import io.github.forget_the_bright.tsdb.common.model.Aggregation;
import io.github.forget_the_bright.tsdb.common.model.DeltaPoint;
import io.github.forget_the_bright.tsdb.common.model.DeltaSeries;
import io.github.forget_the_bright.tsdb.common.model.Direction;
import io.github.forget_the_bright.tsdb.common.model.FillPolicy;
import io.github.forget_the_bright.tsdb.common.model.TimeGranularity;
import io.github.forget_the_bright.tsdb.pspace.PspaceClient;
import io.github.forget_the_bright.tsdb.pspace.PspaceTestServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PspaceDeltaAdapterTest {

    private static final Instant START = Instant.parse("2023-11-14T14:00:00Z");
    private static final Instant END = Instant.parse("2023-11-14T14:05:00Z");

    private PspaceTestServer server;
    private PspaceClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new PspaceTestServer();
        server.on("/Point", request -> "{\"code\":0,\"data\":["
                + "{\"TagId\":101,\"TagType\":2,\"Name\":\"N101\",\"LongName\":\"压力\","
                + "\"ParentId\":1,\"DataType\":11,\"IsNode\":0,\"His_IsSave\":1}]}");
        client = server.client();
    }

    @AfterEach
    void tearDown() {
        client.close();
        server.close();
    }

    @Test
    void foldsBaselineBucketAndFlagsReset() {
        server.on("/HistAggreDataPage", request -> body(""
                + value("2023-11-14 21:59:00", 100) + ","
                + value("2023-11-14 22:00:00", 110) + ","
                + value("2023-11-14 22:01:00", 125) + ","
                + value("2023-11-14 22:02:00", 5) + ","
                + value("2023-11-14 22:03:00", 9) + ","
                + value("2023-11-14 22:04:00", 14)));
        List<DeltaSeries> series = client.deltaHistory().readByInterval(
                Arrays.asList("压力"), START, END, 60_000L,
                Aggregation.LAST, FillPolicy.NONE, Direction.FORWARD, 64);

        assertEquals(1, series.size());
        List<DeltaPoint> points = series.get(0).getPoints();
        assertEquals(5, points.size());
        assertEquals(10.0d, points.get(0).getDelta(), 0.0001d);
        assertEquals(15.0d, points.get(1).getDelta(), 0.0001d);
        assertEquals(5.0d, points.get(2).getDelta(), 0.0001d);
        assertTrue(points.get(2).isReset());
        assertEquals(4.0d, points.get(3).getDelta(), 0.0001d);
        assertEquals(5.0d, points.get(4).getDelta(), 0.0001d);
        assertEquals(START, points.get(0).getTimestamp());
        assertFalse(points.get(0).isMissing());

        PspaceTestServer.Request request = server.requests().get(1);
        assertTrue(request.bodyParam("aggredata").contains("\"aggfunc\":\"endval\""));
        assertEquals("60", request.bodyParam("interval"));
        assertTrue(request.bodyParam("btime").startsWith("2023-11-14 21:59:00"));
    }

    @Test
    void alignsEndValSampleTimesToBucketStarts() {
        server.on("/HistAggreDataPage", request -> page(4, 1, 1, 4,
                flat("2023-11-14 21:59:34.412", 100),
                flat("2023-11-14 22:00:34.491", 110),
                flat("2023-11-14 22:01:34.512", 125),
                flat("2023-11-14 22:02:34.622", 130)));

        List<DeltaPoint> points = client.deltaHistory().readByInterval(
                Arrays.asList("压力"), START, START.plusSeconds(180), 60_000L,
                Aggregation.LAST, FillPolicy.NONE, Direction.FORWARD, 64).get(0).getPoints();

        assertEquals(3, points.size());
        assertEquals(10.0d, points.get(0).getDelta(), 0.0001d);
        assertEquals(15.0d, points.get(1).getDelta(), 0.0001d);
        assertEquals(5.0d, points.get(2).getDelta(), 0.0001d);
        assertEquals(START, points.get(0).getTimestamp());
        assertFalse(points.get(0).isMissing());
    }

    @Test
    void marksFirstBucketMissingWithoutBaseline() {
        server.on("/HistAggreDataPage", request -> body(""
                + value("2023-11-14 22:00:00", 110) + ","
                + value("2023-11-14 22:01:00", 125)));
        List<DeltaPoint> points = client.deltaHistory().readByInterval(
                Arrays.asList("压力"), START, END, 60_000L,
                Aggregation.LAST, FillPolicy.NONE, Direction.FORWARD, 64).get(0).getPoints();

        assertEquals(5, points.size());
        assertTrue(points.get(0).isMissing());
        assertNull(points.get(0).getDelta());
        assertEquals(15.0d, points.get(1).getDelta(), 0.0001d);
        assertTrue(points.get(2).isMissing());
    }

    @Test
    void truncatesToMaxPointsAndSupportsBackwardDirection() {
        server.on("/HistAggreDataPage", request -> body(""
                + value("2023-11-14 21:59:00", 100) + ","
                + value("2023-11-14 22:00:00", 110) + ","
                + value("2023-11-14 22:01:00", 125) + ","
                + value("2023-11-14 22:02:00", 130)));
        List<DeltaPoint> forward = client.deltaHistory().readByInterval(
                Arrays.asList("压力"), START, END, 60_000L,
                Aggregation.LAST, FillPolicy.NONE, Direction.FORWARD, 2).get(0).getPoints();
        assertEquals(2, forward.size());
        assertEquals(10.0d, forward.get(0).getDelta(), 0.0001d);

        List<DeltaPoint> backward = client.deltaHistory().readByInterval(
                Arrays.asList("压力"), START, END, 60_000L,
                Aggregation.LAST, FillPolicy.NONE, Direction.BACKWARD, 64).get(0).getPoints();
        assertEquals(5, backward.size());
        assertEquals(Instant.parse("2023-11-14T14:04:00Z"), backward.get(0).getTimestamp());
    }

    @Test
    void usesGranularityMillisForRead() {
        server.on("/HistAggreDataPage", request -> body(""
                + value("2023-11-14 21:59:00", 100) + ","
                + value("2023-11-14 22:00:00", 110)));
        List<DeltaSeries> series = client.deltaHistory().read(
                Arrays.asList("压力"), START, START.plusSeconds(60), TimeGranularity.MINUTE,
                Aggregation.LAST, FillPolicy.NONE, Direction.FORWARD, 64);
        assertEquals(1, series.get(0).getPoints().size());
        assertEquals(10.0d, series.get(0).getPoints().get(0).getDelta(), 0.0001d);
    }

    @Test
    void ignoresRemovedMaxPagesOptionForDelta() {
        server.on("/HistAggreDataPage", request -> {
            int number = Integer.parseInt(request.bodyParam("pagenumber"));
            if (number == 1) {
                return page(3, 2, 1, 1, "{\"pid\":101,\"count\":1,\"items\":["
                        + value("2023-11-14 21:59:00", 100) + "]}");
            }
            return page(3, 2, 2, 2, "{\"pid\":101,\"count\":2,\"items\":["
                    + value("2023-11-14 22:00:00", 110) + ","
                    + value("2023-11-14 22:01:00", 125) + "]}");
        });
        client.close();
        client = server.client(options("pspace.page-size", "1", "pspace.max-pages", "1"), false);

        List<DeltaPoint> points = client.deltaHistory().readByInterval(
                Arrays.asList("压力"), START, START.plusSeconds(120), 60_000L,
                Aggregation.LAST, FillPolicy.NONE, Direction.FORWARD, 64).get(0).getPoints();
        assertEquals(2, points.size());
        assertEquals(10.0d, points.get(0).getDelta(), 0.0001d);
        assertEquals(15.0d, points.get(1).getDelta(), 0.0001d);
        assertEquals(2, server.hits("/HistAggreDataPage"));
    }

    @Test
    void rejectsCalendarMonthSubSecondIntervalAndOtherAggregations() {
        assertThrows(TsdbUnsupportedException.class, () -> client.deltaHistory().read(
                Arrays.asList("压力"), START, END, TimeGranularity.MONTH,
                Aggregation.LAST, FillPolicy.NONE, Direction.FORWARD, 64));
        assertThrows(TsdbUnsupportedException.class, () -> client.deltaHistory().readByInterval(
                Arrays.asList("压力"), START, END, 1500L,
                Aggregation.LAST, FillPolicy.NONE, Direction.FORWARD, 64));
        assertThrows(TsdbUnsupportedException.class, () -> client.deltaHistory().readByInterval(
                Arrays.asList("压力"), START, END, 60_000L,
                Aggregation.AVG, FillPolicy.NONE, Direction.FORWARD, 64));
    }

    private static java.util.Map<String, Object> options(String... pairs) {
        java.util.Map<String, Object> options = new java.util.LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            options.put(pairs[i], pairs[i + 1]);
        }
        return options;
    }

    private static String page(int total, int count, int number, int size, String... values) {
        StringBuilder sb = new StringBuilder("{\"total\":").append(total)
                .append(",\"page\":{\"count\":").append(count)
                .append(",\"number\":").append(number)
                .append(",\"size\":").append(size)
                .append(",\"values\":[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(values[i]);
        }
        return sb.append("]}}").toString();
    }

    private static String body(String items) {
        return page(1, 1, 1, 1, "{\"pid\":101,\"count\":6,\"items\":[" + items + "]}");
    }

    private static String flat(String timestamp, double value) {
        return "{\"pid\":101,\"pv\":" + value + ",\"tm\":\"" + timestamp + "\",\"qy\":192}";
    }

    private static String value(String timestamp, double value) {
        return "{\"pv\":" + value + ",\"tm\":\"" + timestamp + "\",\"qy\":192}";
    }
}