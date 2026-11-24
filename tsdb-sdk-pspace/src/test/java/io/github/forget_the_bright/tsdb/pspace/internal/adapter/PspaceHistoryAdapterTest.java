package io.github.forget_the_bright.tsdb.pspace.internal.adapter;

import io.github.forget_the_bright.tsdb.common.exception.TsdbQueryException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException;
import io.github.forget_the_bright.tsdb.common.model.Aggregation;
import io.github.forget_the_bright.tsdb.common.model.Direction;
import io.github.forget_the_bright.tsdb.common.model.FillPolicy;
import io.github.forget_the_bright.tsdb.common.model.HistoryQuery;
import io.github.forget_the_bright.tsdb.common.model.QueryMode;
import io.github.forget_the_bright.tsdb.common.model.Sample;
import io.github.forget_the_bright.tsdb.common.model.TagSeries;
import io.github.forget_the_bright.tsdb.pspace.PspaceClient;
import io.github.forget_the_bright.tsdb.pspace.PspaceTestServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PspaceHistoryAdapterTest {

    private static final Instant START = Instant.parse("2023-11-14T14:00:00Z");
    private static final Instant END = Instant.parse("2023-11-14T14:10:00Z");

    private PspaceTestServer server;
    private PspaceClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new PspaceTestServer();
        server.on("/Point", request -> "{\"code\":0,\"data\":["
                + point(101, "压力") + "," + point(102, "温度") + "]}");
        client = server.client();
    }

    @AfterEach
    void tearDown() {
        client.close();
        server.close();
    }

    @Test
    void readsRawHistoryAcrossPagesAndSortsByDirection() {
        server.on("/HistDataPage", request -> {
            int number = Integer.parseInt(request.bodyParam("pagenumber"));
            if (number == 1) {
                return page(2, 2, 1, 1, "{\"pid\":101,\"count\":2,\"items\":["
                        + "{\"pv\":3,\"tm\":\"2023-11-14 22:02:00\",\"qy\":192},"
                        + "{\"pv\":1,\"tm\":\"2023-11-14 22:00:00\",\"qy\":192}]}");
            }
            return page(2, 2, 2, 1, "{\"pid\":102,\"count\":1,\"items\":["
                    + "{\"pv\":7,\"tm\":\"2023-11-14 22:01:00\",\"qy\":0}]}");
        });
        HistoryQuery query = HistoryQuery.builder()
                .tags("压力", "温度")
                .range(START, END)
                .mode(QueryMode.RAW)
                .build();
        List<TagSeries> series = client.history().read(query);
        assertEquals(2, series.size());
        assertEquals(2, series.get(0).getSamples().size());
        assertEquals(Instant.parse("2023-11-14T14:00:00Z"), series.get(0).getSamples().get(0).getTimestamp());
        assertEquals(1.0d, series.get(0).getSamples().get(0).getDoubleValue(), 0.0001d);
        assertEquals(3.0d, series.get(0).getSamples().get(1).getDoubleValue(), 0.0001d);
        assertEquals(1, series.get(1).getSamples().size());
        assertEquals(2, server.hits("/HistDataPage"));

        server.clear();
        HistoryQuery backward = HistoryQuery.builder()
                .tags("压力")
                .range(START, END)
                .mode(QueryMode.RAW)
                .direction(Direction.BACKWARD)
                .build();
        List<Sample> samples = client.history().read(backward).get(0).getSamples();
        assertEquals(3.0d, samples.get(0).getDoubleValue(), 0.0001d);
        assertEquals(1.0d, samples.get(1).getDoubleValue(), 0.0001d);
    }

    @Test
    void marksTruncationAndRejectsItWhenPartialResultsAreNotAllowed() {
        server.on("/HistDataPage", request -> page(1, 1, 1, 1, "{\"pid\":101,\"count\":3,\"items\":["
                + "{\"pv\":1,\"tm\":\"2023-11-14 22:00:00\",\"qy\":192},"
                + "{\"pv\":2,\"tm\":\"2023-11-14 22:01:00\",\"qy\":192},"
                + "{\"pv\":3,\"tm\":\"2023-11-14 22:02:00\",\"qy\":192}]}"));
        HistoryQuery allowed = HistoryQuery.builder()
                .tags("压力").range(START, END).mode(QueryMode.RAW)
                .maxPoints(2).partialResultAllowed(true).build();
        TagSeries truncated = client.history().read(allowed).get(0);
        assertEquals(2, truncated.getSamples().size());
        assertTrue(truncated.isTruncated());

        HistoryQuery strict = HistoryQuery.builder()
                .tags("压力").range(START, END).mode(QueryMode.RAW)
                .maxPoints(2).partialResultAllowed(false).build();
        assertThrows(TsdbQueryException.class, () -> client.history().read(strict));
    }

    @Test
    void mapsAggregationAndIntervalInSeconds() {
        server.on("/HistAggreDataPage", request -> page(1, 1, 1, 1, "{\"pid\":101,\"count\":2,\"items\":["
                + "{\"pv\":10,\"tm\":\"2023-11-14 22:00:00\",\"qy\":192},"
                + "{\"pv\":20,\"tm\":\"2023-11-14 22:01:00\",\"qy\":192}]}"));
        HistoryQuery query = HistoryQuery.builder()
                .tags("压力").range(START, END)
                .mode(QueryMode.AGGREGATED).aggregation(Aggregation.AVG)
                .interval(Duration.ofSeconds(60)).build();
        List<TagSeries> series = client.history().read(query);
        assertEquals(2, series.get(0).getSamples().size());
        assertFalse(series.get(0).getAttributes().containsKey("valueMissingCount"));
        PspaceTestServer.Request request = server.requests().get(1);
        assertTrue(request.bodyParam("aggredata").contains("\"aggfunc\":\"avg\""));
        assertTrue(request.bodyParam("aggredata").contains("101"));
        assertEquals("60", request.bodyParam("interval"));
    }

    @Test
    void intervalSumForcesWholeWindowBucketEvenWithExplicitInterval() {
        server.on("/HistAggreDataPage", request -> page(1, 1, 1, 1, "{\"pid\":101,\"count\":1,\"items\":["
                + value("2023-11-14 22:10:00", 30) + "]}"));
        List<TagSeries> series = client.history().read(HistoryQuery.builder()
                .tags("压力").range(START, END)
                .mode(QueryMode.AGGREGATED).aggregation(Aggregation.INTERVAL_SUM)
                .interval(Duration.ofSeconds(60)).build());

        assertEquals(1, series.get(0).getSamples().size());
        PspaceTestServer.Request request = server.requests().get(1);
        assertTrue(request.bodyParam("aggredata").contains("\"aggfunc\":\"delta\""));
        assertEquals("600", request.bodyParam("interval"));
    }

    @Test
    void derivesIntervalFromCount() {
        server.on("/HistAggreDataPage", request -> page(0, 1, 1, 0));
        HistoryQuery query = HistoryQuery.builder()
                .tags("压力").range(START, END)
                .mode(QueryMode.AGGREGATED).aggregation(Aggregation.SUM)
                .count(10).build();
        client.history().read(query);
        PspaceTestServer.Request request = server.requests().get(1);
        assertEquals("60", request.bodyParam("interval"));
        assertTrue(request.bodyParam("aggredata").contains("\"aggfunc\":\"total\""));
    }

    @Test
    void mapsInterpolatedAndSampledAggregationFunctions() {
        server.on("/HistAggreDataPage", request -> page(0, 1, 1, 0));
        client.history().read(HistoryQuery.builder()
                .tags("压力").range(START, END)
                .mode(QueryMode.INTERPOLATED).interval(Duration.ofSeconds(60)).build());
        assertTrue(server.requests().get(1).bodyParam("aggredata").contains("\"aggfunc\":\"intve\""));

        server.clear();
        client.history().read(HistoryQuery.builder()
                .tags("压力").range(START, END)
                .mode(QueryMode.SAMPLED).interval(Duration.ofSeconds(60)).build());
        assertTrue(server.requests().get(0).bodyParam("aggredata").contains("\"aggfunc\":\"orgintve\""));
    }

    @Test
    void omitsAggregationBucketsWithoutPvAndReportsMissingMetrics() {
        server.on("/HistAggreDataPage", request -> page(1, 1, 1, 1, "{\"pid\":101,\"count\":3,\"items\":["
                + value("2023-11-14 22:00:00", 10) + ","
                + empty("2023-11-14 22:01:00") + ","
                + value("2023-11-14 22:02:00", 20) + "]}"));
        List<TagSeries> series = client.history().read(HistoryQuery.builder()
                .tags("压力").range(START, START.plusSeconds(180))
                .mode(QueryMode.AGGREGATED).aggregation(Aggregation.AVG)
                .interval(Duration.ofSeconds(60)).build());

        TagSeries tag = series.get(0);
        assertEquals(2, tag.getSamples().size());
        assertEquals(Instant.parse("2023-11-14T14:00:00Z"), tag.getSamples().get(0).getTimestamp());
        assertEquals(20.0d, tag.getSamples().get(1).getDoubleValue(), 0.0001d);
        assertEquals("1", tag.getAttributes().get("valueMissingCount"));
        assertEquals("pspace_aggregation_bucket_without_pv",
                tag.getAttributes().get("valueMissingReason"));
        assertTrue(tag.getAttributes().get("valueMissingFirstAt").startsWith("2023-11-14 22:01:00"));
        assertTrue(tag.getAttributes().get("valueMissingLastAt").startsWith("2023-11-14 22:01:00"));
        assertFalse(tag.getAttributes().containsKey("valueMissingAll"));
    }

    @Test
    void reportsAllMissingAggregationBuckets() {
        server.on("/HistAggreDataPage", request -> page(1, 1, 1, 1, "{\"pid\":101,\"count\":2,\"items\":["
                + empty("2023-11-14 22:00:00") + ","
                + empty("2023-11-14 22:01:00") + "]}"));
        TagSeries tag = client.history().read(HistoryQuery.builder()
                .tags("压力").range(START, START.plusSeconds(120))
                .mode(QueryMode.AGGREGATED).aggregation(Aggregation.AVG)
                .interval(Duration.ofSeconds(60)).build()).get(0);

        assertTrue(tag.getSamples().isEmpty());
        assertEquals("2", tag.getAttributes().get("valueMissingCount"));
        assertEquals("true", tag.getAttributes().get("valueMissingAll"));
    }

    @Test
    void maxPointsOnlyCountsNonNullAggregationSamples() {
        server.on("/HistAggreDataPage", request -> page(1, 1, 1, 1, "{\"pid\":101,\"count\":3,\"items\":["
                + value("2023-11-14 22:00:00", 1) + ","
                + empty("2023-11-14 22:01:00") + ","
                + value("2023-11-14 22:02:00", 2) + "]}"));
        TagSeries tag = client.history().read(HistoryQuery.builder()
                .tags("压力").range(START, START.plusSeconds(180))
                .mode(QueryMode.AGGREGATED).aggregation(Aggregation.AVG)
                .interval(Duration.ofSeconds(60))
                .maxPoints(1).partialResultAllowed(true).build()).get(0);

        assertEquals(1, tag.getSamples().size());
        assertTrue(tag.isTruncated());
        assertEquals("1", tag.getAttributes().get("valueMissingCount"));
    }

    @Test
    void keepsRawNullSamplesAndDoesNotWriteMissingMetrics() {
        server.on("/HistDataPage", request -> page(1, 1, 1, 1, "{\"pid\":101,\"count\":1,\"items\":["
                + "{\"tm\":\"2023-11-14 22:00:00\",\"qy\":0}]}"));
        TagSeries tag = client.history().read(HistoryQuery.builder()
                .tags("压力").range(START, END).mode(QueryMode.RAW).build()).get(0);

        assertEquals(1, tag.getSamples().size());
        assertTrue(tag.getSamples().get(0).isNull());
        assertFalse(tag.getAttributes().containsKey("valueMissingCount"));
    }

    @Test
    void ignoresRemovedMaxPagesOptionForAggregation() {
        server.on("/HistAggreDataPage", request -> {
            int number = Integer.parseInt(request.bodyParam("pagenumber"));
            if (number == 1) {
                return page(2, 2, 1, 1, "{\"pid\":101,\"count\":1,\"items\":["
                        + value("2023-11-14 22:00:00", 10) + "]}");
            }
            return page(2, 2, 2, 1, "{\"pid\":101,\"count\":1,\"items\":["
                    + value("2023-11-14 22:01:00", 20) + "]}");
        });
        client.close();
        client = server.client(options("pspace.page-size", "1", "pspace.max-pages", "1"), false);

        TagSeries tag = client.history().read(HistoryQuery.builder()
                .tags("压力").range(START, START.plusSeconds(120))
                .mode(QueryMode.AGGREGATED).aggregation(Aggregation.AVG)
                .interval(Duration.ofSeconds(60)).build()).get(0);
        assertEquals(2, tag.getSamples().size());
        assertEquals(2, server.hits("/HistAggreDataPage"));
    }

    @Test
    void throwsWhenHistoryPageCountIsMissingButPageIsFull() {
        server.on("/HistDataPage", request -> "{\"total\":1,\"page\":{\"number\":1,\"size\":1,"
                + "\"values\":[{\"pid\":101,\"count\":1,\"items\":["
                + "{\"pv\":1,\"tm\":\"2023-11-14 22:00:00\",\"qy\":192}]}]}}");
        client.close();
        client = server.client(options("pspace.page-size", "1"), false);

        assertThrows(TsdbQueryException.class, () -> client.history().read(HistoryQuery.builder()
                .tags("压力").range(START, END).mode(QueryMode.RAW).build()));
    }

    @Test
    void rejectsUnsupportedFillTrendAndSubSecondInterval() {
        assertThrows(TsdbUnsupportedException.class, () -> client.history().read(HistoryQuery.builder()
                .tags("压力").range(START, END)
                .mode(QueryMode.INTERPOLATED).interval(Duration.ofSeconds(60))
                .fill(FillPolicy.LINEAR).build()));

        assertThrows(TsdbUnsupportedException.class, () -> client.history().read(HistoryQuery.builder()
                .tags("压力").range(START, END).mode(QueryMode.TREND).build()));

        assertThrows(TsdbUnsupportedException.class, () -> client.history().read(HistoryQuery.builder()
                .tags("压力").range(START, END)
                .mode(QueryMode.AGGREGATED).aggregation(Aggregation.AVG)
                .interval(Duration.ofMillis(1500)).build()));
    }

    @Test
    void rejectsUnresolvedTagsWhenPartialResultsAreNotAllowed() {
        server.on("/HistDataPage", request -> page(0, 1, 1, 0));
        HistoryQuery query = HistoryQuery.builder()
                .tags("缺失").range(START, END).mode(QueryMode.RAW)
                .partialResultAllowed(false).build();
        assertThrows(TsdbQueryException.class, () -> client.history().read(query));
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

    private static String point(long id, String longName) {
        return "{\"TagId\":" + id + ",\"TagType\":2,\"Name\":\"N" + id + "\","
                + "\"LongName\":\"" + longName + "\",\"ParentId\":1,\"DataType\":11"
                + ",\"IsNode\":0,\"His_IsSave\":1}";
    }

    private static String value(String timestamp, double value) {
        return "{\"pv\":" + value + ",\"tm\":\"" + timestamp + "\",\"qy\":192}";
    }

    private static String empty(String timestamp) {
        return "{\"tm\":\"" + timestamp + "\",\"qy\":0}";
    }

    }