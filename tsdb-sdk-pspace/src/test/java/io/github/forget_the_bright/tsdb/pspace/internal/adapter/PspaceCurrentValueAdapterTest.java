package io.github.forget_the_bright.tsdb.pspace.internal.adapter;

import io.github.forget_the_bright.tsdb.common.model.Quality;
import io.github.forget_the_bright.tsdb.common.model.TagSample;
import io.github.forget_the_bright.tsdb.common.model.ValueType;
import io.github.forget_the_bright.tsdb.pspace.PspaceClient;
import io.github.forget_the_bright.tsdb.pspace.PspaceTestServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PspaceCurrentValueAdapterTest {

    private PspaceTestServer server;
    private PspaceClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new PspaceTestServer();
        server.on("/Point", request -> "{\"code\":0,\"data\":["
                + point(101, "压力", 11) + "," + point(102, "温度", 13) + "]}");
        client = server.client();
    }

    @AfterEach
    void tearDown() {
        client.close();
        server.close();
    }

    @Test
    void readsInRequestedOrderWithDedupAndMissingEntries() {
        server.on("/RealData", request -> "{\"count\":2,\"values\":["
                + "{\"pid\":101,\"pv\":12.5,\"tm\":\"2023-11-14 22:13:20.000\",\"qy\":192},"
                + "{\"pid\":102,\"pv\":\"bad\",\"tm\":\"2023-11-14 22:13:21.000\",\"qy\":0}]}");
        List<TagSample> values = client.currentValues().read(
                Arrays.asList("压力", "温度", "缺失", "压力"));

        assertEquals(3, values.size());
        assertEquals("压力", values.get(0).getTagName());
        assertEquals(ValueType.DOUBLE, values.get(0).getSample().getValueType());
        assertEquals(12.5d, values.get(0).getSample().getDoubleValue(), 0.0001d);
        assertEquals(Quality.GOOD, values.get(0).getSample().getQuality());
        assertEquals(Instant.parse("2023-11-14T14:13:20Z"), values.get(0).getSample().getTimestamp());

        assertEquals("温度", values.get(1).getTagName());
        assertEquals("bad", values.get(1).getSample().getTextValue());
        assertEquals(Quality.BAD, values.get(1).getSample().getQuality());

        assertEquals("缺失", values.get(2).getTagName());
        assertNull(values.get(2).getSample());
        assertEquals("true", values.get(2).getAttributes().get("unresolved"));
        assertEquals("压力,温度,缺失", server.requests().get(1).bodyParam("tagnames"));
    }

    @Test
    void treatsNoValidDataAsAllNullSamples() {
        server.on("/RealData", request -> "{\"code\":-1,\"mesg\":\"No Valid Data\"}");
        List<TagSample> values = client.currentValues().read(Arrays.asList("压力"));
        assertEquals(1, values.size());
        assertNull(values.get(0).getSample());
        assertNotNull(values.get(0).getAttributes());
    }

    @Test
    void returnsEmptyForEmptyInput() {
        assertTrue(client.currentValues().read(Collections.<String>emptyList()).isEmpty());
        assertEquals(0, server.requests().size());
    }

    @Test
    void rejectsBlankNames() {
        assertThrows(IllegalArgumentException.class,
                () -> client.currentValues().read(Arrays.asList("压力", " ")));
    }

    @Test
    void singleReadReturnsNullWhenMissing() {
        server.on("/RealData", request -> "{\"count\":0,\"values\":[]}");
        TagSample value = client.currentValues().read("缺失");
        assertNotNull(value);
        assertNull(value.getSample());
        assertFalse(server.requests().isEmpty());
    }

    private static String point(long id, String longName, int dataType) {
        return "{\"TagId\":" + id + ",\"TagType\":2,\"Name\":\"N" + id + "\","
                + "\"LongName\":\"" + longName + "\",\"ParentId\":1,\"DataType\":" + dataType
                + ",\"IsNode\":0,\"His_IsSave\":1}";
    }
}