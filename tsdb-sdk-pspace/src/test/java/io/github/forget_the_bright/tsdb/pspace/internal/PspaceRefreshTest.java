package io.github.forget_the_bright.tsdb.pspace.internal;

import io.github.forget_the_bright.tsdb.common.model.TagInfo;
import io.github.forget_the_bright.tsdb.common.model.TagQuery;
import io.github.forget_the_bright.tsdb.common.model.TagSample;
import io.github.forget_the_bright.tsdb.pspace.PspaceClient;
import io.github.forget_the_bright.tsdb.pspace.PspaceTestServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PspaceRefreshTest {

    private static final String EMPTY_PAGE =
            "{\"total\":0,\"page\":{\"count\":1,\"number\":1,\"size\":0,\"values\":[]}}";
    private static final String ONE_PAGE =
            "{\"total\":1,\"page\":{\"count\":1,\"number\":1,\"size\":1,\"values\":["
                    + "{\"TagId\":101,\"TagType\":2,\"Name\":\"N101\",\"LongName\":\"压力\","
                    + "\"ParentId\":1,\"DataType\":11,\"IsNode\":0,\"His_IsSave\":1}]}}";

    private PspaceTestServer server;
    private PspaceClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        server.close();
    }

    @Test
    void refreshesOnceWhenDirectoryIsEmptyThenRetries() throws IOException {
        server = new PspaceTestServer();
        AtomicBoolean refreshed = new AtomicBoolean(false);
        server.on("/PointPage", request -> refreshed.get() ? ONE_PAGE : EMPTY_PAGE);
        server.on("/Refresh", request -> {
            refreshed.set(true);
            return "{\"code\":0,\"mesg\":\"Success\"}";
        });
        client = server.client();

        assertEquals(1, client.tagCatalog().listTags(TagQuery.builder().limit(0).build()).size());
        assertEquals(1, server.hits("/Refresh"));
        assertEquals(2, server.hits("/PointPage"));
    }

    @Test
    void refreshesOnColdErrorAndRetriesGetTag() throws IOException {
        server = new PspaceTestServer();
        AtomicBoolean refreshed = new AtomicBoolean(false);
        server.on("/Point", request -> refreshed.get()
                ? "{\"code\":0,\"data\":[{\"TagId\":101,\"TagType\":2,\"Name\":\"N101\","
                        + "\"LongName\":\"压力\",\"ParentId\":1,\"DataType\":11,"
                        + "\"IsNode\":0,\"His_IsSave\":1}]}"
                : "{\"code\":-1,\"mesg\":\"Parent (0) , Not find children\"}");
        server.on("/Refresh", request -> {
            refreshed.set(true);
            return "{\"code\":0,\"mesg\":\"Success\"}";
        });
        client = server.client();

        TagInfo tag = client.tagCatalog().getTag("压力");
        assertNotNull(tag);
        assertEquals("压力", tag.getTagName());
        assertEquals(1, server.hits("/Refresh"));
    }

    @Test
    void doesNotRefreshWhenAutoRefreshIsDisabled() throws IOException {
        server = new PspaceTestServer();
        server.on("/PointPage", request -> EMPTY_PAGE);
        Map<String, Object> options = new LinkedHashMap<String, Object>();
        options.put("pspace.auto-refresh", "false");
        client = server.client(options, false);

        assertTrue(client.tagCatalog().listTags(TagQuery.builder().limit(0).build()).isEmpty());
        assertEquals(0, server.hits("/Refresh"));
    }

    @Test
    void refreshesAtMostOnceWithinMinInterval() throws IOException {
        server = new PspaceTestServer();
        server.on("/PointPage", request -> EMPTY_PAGE);
        Map<String, Object> options = new LinkedHashMap<String, Object>();
        options.put("pspace.refresh-min-interval-ms", "60000");
        client = server.client(options, false);

        client.tagCatalog().listTags(TagQuery.builder().limit(0).build());
        client.tagCatalog().listTags(TagQuery.builder().limit(0).build());
        assertEquals(1, server.hits("/Refresh"));
        assertEquals(3, server.hits("/PointPage"));
    }

    @Test
    void currentValuesTriggerRefreshOnNoValidData() throws IOException {
        server = new PspaceTestServer();
        AtomicBoolean refreshed = new AtomicBoolean(false);
        server.on("/Point", request -> "{\"code\":0,\"data\":[{\"TagId\":101,\"TagType\":2,"
                + "\"Name\":\"N101\",\"LongName\":\"压力\",\"ParentId\":1,\"DataType\":11,"
                + "\"IsNode\":0,\"His_IsSave\":1}]}");
        server.on("/RealData", request -> refreshed.get()
                ? "{\"count\":1,\"values\":[{\"pid\":101,\"pv\":12.5,"
                        + "\"tm\":\"2023-11-14 22:13:20.000\",\"qy\":192}]}"
                : "{\"code\":-1,\"mesg\":\"No Valid Data\"}");
        server.on("/Refresh", request -> {
            refreshed.set(true);
            return "{\"code\":0,\"mesg\":\"Success\"}";
        });
        client = server.client();

        TagSample value = client.currentValues().read("压力");
        assertNotNull(value);
        assertNotNull(value.getSample());
        assertEquals(12.5d, value.getSample().getDoubleValue(), 0.0001d);
        assertEquals(1, server.hits("/Refresh"));
    }
}