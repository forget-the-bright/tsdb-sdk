package io.github.forget_the_bright.tsdb.pspace.internal;

import com.alibaba.fastjson.JSONArray;
import io.github.forget_the_bright.tsdb.pspace.PspaceTestServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PspaceNativeApiTest {

    private PspaceTestServer server;
    private PspaceNativeApi api;

    @BeforeEach
    void setUp() throws IOException {
        server = new PspaceTestServer();
        api = new PspaceNativeApi(server.config(),
                new PspaceMapper(server.config().getTimeConfig()));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void readsPointPageAndJoinsTagIds() {
        server.on("/PointPage", request -> "{\"total\":3,\"page\":{\"count\":1,\"number\":1,"
                + "\"size\":3,\"values\":["
                + "{\"TagId\":101,\"TagType\":2,\"Name\":\"AI101\",\"LongName\":\"压力\","
                + "\"EngUnit\":\"MPa\",\"ParentId\":1,\"DataType\":11,\"IsNode\":0,\"His_IsSave\":1},"
                + "{\"TagId\":102,\"TagType\":2,\"Name\":\"AI102\",\"LongName\":\"温度\","
                + "\"EngUnit\":\"C\",\"ParentId\":1,\"DataType\":11,\"IsNode\":0,\"His_IsSave\":1}]}}");
        PspaceModels.Page page = api.pointPage(100, 1);
        assertEquals(3L, page.total);
        assertEquals(1, page.pageCount);
        List<PspaceModels.Point> points = api.parsePoints(page.values);
        assertEquals(2, points.size());
        assertEquals("压力", points.get(0).longName);
        assertEquals("MPa", points.get(0).unit);
        assertEquals(11, points.get(0).dataType);

        server.on("/Point", request -> "{\"code\":0,\"data\":[]}");
        api.pointsByIds(Arrays.asList(Long.valueOf(101), Long.valueOf(102), Long.valueOf(103)));
        assertEquals("101,102,103", server.requests().get(1).bodyParam("tagids"));
    }

    @Test
    void parsesHistRowsWithObjectArrayAndPositionalItems() {
        JSONArray values = new JSONArray();
        values.add(com.alibaba.fastjson.JSON.parseObject("{\"pid\":101,\"count\":2,\"items\":["
                + "{\"pv\":1.5,\"tm\":\"2023-11-14 22:00:00\",\"qy\":192},"
                + "{\"pv\":2.5,\"tm\":\"2023-11-14 22:01:00\",\"qy\":0}]}"));
        values.add(com.alibaba.fastjson.JSON.parseObject("{\"pid\":102,\"count\":1,"
                + "\"items\":{\"pv\":3,\"tm\":\"2023-11-15 24:00:00\",\"qy\":192}}"));
        values.add(com.alibaba.fastjson.JSON.parseObject("{\"pid\":103,\"count\":1,"
                + "\"items\":[[\"4.5\",\"2023-11-14 22:02:00\"]]}"));

        List<PspaceModels.HistRow> rows = api.parseHistRows(values);
        assertEquals(3, rows.size());
        assertEquals(2, rows.get(0).values.size());
        assertEquals(Instant.parse("2023-11-14T14:01:00Z"), rows.get(0).values.get(1).timestamp);
        assertEquals(Integer.valueOf(0), rows.get(0).values.get(1).quality);

        assertEquals(1, rows.get(1).values.size());
        assertEquals(Instant.parse("2023-11-15T16:00:00Z"), rows.get(1).values.get(0).timestamp);

        assertEquals(1, rows.get(2).values.size());
        assertEquals("4.5", rows.get(2).values.get(0).value);
    }

    @Test
    void treatsNoValidDataAsEmptyRealValues() {
        server.on("/Point", request -> "{\"code\":0,\"data\":[{\"TagId\":101,\"TagType\":2,"
                + "\"LongName\":\"压力\",\"DataType\":11,\"IsNode\":0}]}");
        server.on("/RealData", request -> "{\"code\":-1,\"mesg\":\"No Valid Data\"}");
        List<PspaceModels.RealValue> values = api.realData(Arrays.asList("压力"));
        assertTrue(values.isEmpty());
        assertEquals("压力", server.requests().get(0).bodyParam("tagnames"));
    }

    @Test
    void refreshPostsEmptyJsonBody() {
        api.refresh();
        PspaceTestServer.Request request = server.requests().get(0);
        assertEquals("POST", request.method);
        assertEquals("/Refresh", request.path);
        assertEquals("{}", request.body);
    }

    @Test
    void parsesRealValuesWithQualityAndTimestamp() {
        server.on("/RealData", request -> "{\"count\":1,\"values\":["
                + "{\"pid\":101,\"pv\":12.5,\"tm\":\"2023-11-14 22:13:20.123\",\"qy\":192}]}");
        List<PspaceModels.RealValue> values = api.realData(Arrays.asList("压力"));
        assertEquals(1, values.size());
        assertEquals(101L, values.get(0).id);
        assertEquals("12.5", String.valueOf(values.get(0).value));
        assertEquals(Integer.valueOf(192), values.get(0).quality);
        assertNotNull(values.get(0).timestamp);
    }

    @Test
    void expandsTagIdCollectionInOrder() {
        server.on("/Property", request -> "{\"code\":0,\"data\":{\"values\":[]}}");
        api.property(new ArrayList<Long>(Arrays.asList(Long.valueOf(5), Long.valueOf(9))), "PV,PV_Tm");
        PspaceTestServer.Request request = server.requests().get(0);
        assertEquals("5,9", request.bodyParam("tagids"));
        assertEquals("PV,PV_Tm", request.bodyParam("fieldnames"));
    }
}