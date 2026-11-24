package io.github.forget_the_bright.tsdb.pspace.internal.adapter;

import io.github.forget_the_bright.tsdb.common.exception.TsdbQueryException;
import io.github.forget_the_bright.tsdb.common.model.TagInfo;
import io.github.forget_the_bright.tsdb.common.model.TagQuery;
import io.github.forget_the_bright.tsdb.common.model.ValueType;
import io.github.forget_the_bright.tsdb.pspace.PspaceClient;
import io.github.forget_the_bright.tsdb.pspace.PspaceTestServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PspaceTagCatalogAdapterTest {

    private PspaceTestServer server;
    private PspaceClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new PspaceTestServer();
        client = server.client();
    }

    @AfterEach
    void tearDown() {
        client.close();
        server.close();
    }

    @Test
    void listsAllPagesAndAppliesOffsetAndLimit() {
        server.on("/PointPage", request -> {
            int number = Integer.parseInt(request.bodyParam("pagenumber"));
            if (number == 1) {
                return page(3, 2, 1, 2,
                        point(101, "压力", "MPa", 11), point(102, "温度", "C", 11));
            }
            return page(3, 2, 2, 1, point(103, "液位", "m", 11));
        });
        List<TagInfo> all = client.tagCatalog().listTags(TagQuery.builder().limit(0).build());
        assertEquals(3, all.size());
        assertEquals("压力", all.get(0).getTagName());
        assertEquals("MPa", all.get(0).getUnit());
        assertEquals("ANA", all.get(0).getVendorType());

        server.clear();
        List<TagInfo> sliced = client.tagCatalog().listTags(
                TagQuery.builder().offset(1).limit(1).build());
        assertEquals(1, sliced.size());
        assertEquals("温度", sliced.get(0).getTagName());
        assertEquals(1, server.hits("/PointPage"));
    }

    @Test
    void readsOnlyTheRequestedServerPage() {
        server.on("/PointPage", request -> {
            int number = Integer.parseInt(request.bodyParam("pagenumber"));
            if (number == 1) {
                return page(4, 2, 1, 2,
                        point(101, "A", "", 11), point(102, "B", "", 11));
            }
            if (number == 2) {
                return page(4, 2, 2, 2,
                        point(103, "C", "", 11), point(104, "D", "", 11));
            }
            return page(4, 2, number, 0);
        });
        client.close();
        client = server.client(Collections.<String, Object>singletonMap("pspace.page-size", "2"), false);

        List<TagInfo> tags = client.tagCatalog().listTags(
                TagQuery.builder().offset(3).limit(1).build());
        assertEquals(1, tags.size());
        assertEquals("D", tags.get(0).getTagName());
        assertEquals(1, server.hits("/PointPage"));
        assertEquals("2", server.requests().get(0).bodyParam("pagenumber"));
    }

    @Test
    void streamsFilteredPagesUntilLimit() {
        server.on("/PointPage", request -> {
            int number = Integer.parseInt(request.bodyParam("pagenumber"));
            if (number == 1) {
                return page(3, 3, 1, 1, point(101, "A", "", 6));
            }
            if (number == 2) {
                return page(3, 3, 2, 1, point(102, "B", "", 11));
            }
            return page(3, 3, 3, 1, point(103, "C", "", 6));
        });
        client.close();
        client = server.client(Collections.<String, Object>singletonMap("pspace.page-size", "1"), false);

        List<TagInfo> tags = client.tagCatalog().listTags(TagQuery.builder()
                .valueType(ValueType.DOUBLE).offset(0).limit(1).build());
        assertEquals(1, tags.size());
        assertEquals("B", tags.get(0).getTagName());
        assertEquals(2, server.hits("/PointPage"));
    }

    @Test
    void ignoresRemovedMaxPagesOption() {
        server.on("/PointPage", request -> {
            int number = Integer.parseInt(request.bodyParam("pagenumber"));
            return page(3, 3, number, 1, point(100 + number, "T" + number, "", 11));
        });
        client.close();
        client = server.client(options("pspace.page-size", "1", "pspace.max-pages", "1"), false);

        List<TagInfo> tags = client.tagCatalog().listTags(TagQuery.builder().limit(0).build());
        assertEquals(3, tags.size());
        assertEquals("T1", tags.get(0).getTagName());
        assertEquals("T3", tags.get(2).getTagName());
        assertEquals(3, server.hits("/PointPage"));
    }

    @Test
    void throwsWhenPageCountIsMissingButPageIsFull() {
        server.on("/PointPage", request -> "{\"total\":1,\"page\":{\"number\":1,\"size\":1,"
                + "\"values\":[" + point(101, "压力", "MPa", 11) + "]}}");
        client.close();
        client = server.client(Collections.<String, Object>singletonMap("pspace.page-size", "1"), false);

        assertThrows(TsdbQueryException.class,
                () -> client.tagCatalog().listTags(TagQuery.builder().limit(0).build()));
    }

    @Test
    void warmsResolverCacheAfterListTags() {
        server.on("/PointPage", request -> page(1, 1, 1, 1, point(101, "压力", "MPa", 11)));
        server.on("/Point", request -> "{\"code\":0,\"data\":[" + point(101, "压力", "MPa", 11) + "]}");

        client.tagCatalog().listTags(TagQuery.builder().limit(0).build());
        TagInfo tag = client.tagCatalog().getTag("压力");
        assertNotNull(tag);
        assertEquals("压力", tag.getTagName());
        assertEquals(0, server.hits("/Point"));
    }

    @Test
    void filtersValueTypeAndUnitLocally() {
        server.on("/PointPage", request -> page(2, 1, 1, 2,
                point(101, "压力", "MPa", 11), point(102, "计数", "ea", 6)));
        List<TagInfo> doubleTags = client.tagCatalog().listTags(
                TagQuery.builder().valueType(ValueType.DOUBLE).limit(0).build());
        assertEquals(1, doubleTags.size());
        assertEquals("压力", doubleTags.get(0).getTagName());

        List<TagInfo> unitTags = client.tagCatalog().listTags(
                TagQuery.builder().unit("ea").limit(0).build());
        assertEquals(1, unitTags.size());
        assertEquals("计数", unitTags.get(0).getTagName());
    }

    @Test
    void appliesOffsetToFilteredMatchesAcrossPages() {
        server.on("/PointPage", request -> {
            int number = Integer.parseInt(request.bodyParam("pagenumber"));
            if (number == 1) {
                return page(5, 3, 1, 2, point(101, "A", "", 6), point(102, "B", "", 11));
            }
            if (number == 2) {
                return page(5, 3, 2, 2, point(103, "C", "", 11), point(104, "D", "", 6));
            }
            return page(5, 3, 3, 1, point(105, "E", "", 11));
        });
        client.close();
        client = server.client(Collections.<String, Object>singletonMap("pspace.page-size", "2"), false);

        List<TagInfo> tags = client.tagCatalog().listTags(TagQuery.builder()
                .valueType(ValueType.DOUBLE).offset(1).limit(1).build());
        assertEquals(1, tags.size());
        assertEquals("C", tags.get(0).getTagName());
        assertEquals(2, server.hits("/PointPage"));
    }

    @Test
    void translatesShellWildcardsAndAnchorsNameMask() {
        server.on("/PointFilter", request -> "{\"values\":[" + point(101, "压力A", "MPa", 11) + "]}");
        List<TagInfo> tags = client.tagCatalog().listTags(
                TagQuery.builder().nameMask("压力*").limit(0).build());
        assertEquals(1, tags.size());
        PspaceTestServer.Request request = server.requests().get(0);
        assertEquals("match", request.bodyParam("filtermode"));
        assertEquals("LongName", request.bodyParam("filterfield"));
        assertEquals("true", request.bodyParam("filterignorecase"));
        assertEquals("^压力.*$", request.bodyParam("filtervalue"));
    }

    @Test
    void treatsPlainNameMaskAsExactMatch() {
        String tag1 = point(101, "TAG1", "MPa", 11);
        String tag10 = point(102, "TAG10", "MPa", 11);
        server.on("/PointFilter", request -> {
            if ("match".equals(request.bodyParam("filtermode"))
                    && "^TAG1$".equals(request.bodyParam("filtervalue"))) {
                return "{\"values\":[" + tag1 + "]}";
            }
            return "{\"values\":[" + tag1 + "," + tag10 + "]}";
        });

        List<TagInfo> tags = client.tagCatalog().listTags(
                TagQuery.builder().nameMask("TAG1").limit(0).build());
        assertEquals(1, tags.size());
        assertEquals("TAG1", tags.get(0).getTagName());
        PspaceTestServer.Request request = server.requests().get(0);
        assertEquals("match", request.bodyParam("filtermode"));
        assertEquals("^TAG1$", request.bodyParam("filtervalue"));
    }

    @Test
    void getTagResolvesExactLongName() {
        server.on("/Point", request -> "{\"code\":0,\"data\":[" + point(101, "压力", "MPa", 11) + "]}");
        TagInfo tag = client.tagCatalog().getTag("压力");
        assertNotNull(tag);
        assertEquals("压力", tag.getTagName());
        assertEquals("101", tag.getAttributes().get("id"));
        assertEquals("11", tag.getAttributes().get("dataType"));
    }

    @Test
    void getTagReturnsNullWhenOnlyNearMatchExists() {
        server.on("/Point", request -> "{\"code\":0,\"data\":[" + point(101, "压力X", "MPa", 11) + "]}");
        server.on("/PointFilter", request -> "{\"values\":[]}");
        assertNull(client.tagCatalog().getTag("压力"));
    }

    @Test
    void escapesRegexMetacharactersForMatchFilter() {
        server.on("/Point", request -> "{\"code\":0,\"data\":[]}");
        server.on("/PointFilter", request -> "{\"values\":[" + point(101, "AI(1)", "MPa", 11) + "]}");
        client.close();
        client = server.client(Collections.<String, Object>singletonMap("pspace.auto-refresh", "false"), false);

        TagInfo tag = client.tagCatalog().getTag("AI(1)");
        assertNotNull(tag);
        assertEquals("AI(1)", tag.getTagName());
        PspaceTestServer.Request filter = server.requests().get(server.requests().size() - 1);
        assertEquals("match", filter.bodyParam("filtermode"));
        assertEquals("AI\\(1\\)", filter.bodyParam("filtervalue"));
    }

    @Test
    void rejectsBlankTagName() {
        try {
            client.tagCatalog().getTag("  ");
            assertTrue(false, "expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("tagName"));
        }
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

    private static String point(long id, String longName, String unit, int dataType) {
        return "{\"TagId\":" + id + ",\"TagType\":2,\"Name\":\"N" + id + "\","
                + "\"LongName\":\"" + longName + "\",\"EngUnit\":\"" + unit + "\","
                + "\"ParentId\":1,\"DataType\":" + dataType + ",\"IsNode\":0,\"His_IsSave\":1}";
    }
}