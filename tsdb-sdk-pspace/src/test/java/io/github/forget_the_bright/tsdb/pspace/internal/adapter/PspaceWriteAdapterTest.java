package io.github.forget_the_bright.tsdb.pspace.internal.adapter;

import io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException;
import io.github.forget_the_bright.tsdb.common.model.Quality;
import io.github.forget_the_bright.tsdb.common.model.Sample;
import io.github.forget_the_bright.tsdb.common.model.ValueType;
import io.github.forget_the_bright.tsdb.common.model.WriteRequest;
import io.github.forget_the_bright.tsdb.common.model.WriteResult;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PspaceWriteAdapterTest {

    private static final Instant TS = Instant.parse("2023-11-14T14:13:20Z");

    private PspaceTestServer server;
    private PspaceClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new PspaceTestServer();
        server.on("/Point", request -> "{\"code\":0,\"data\":["
                + "{\"TagId\":101,\"TagType\":2,\"Name\":\"N101\",\"LongName\":\"压力\","
                + "\"ParentId\":1,\"DataType\":11,\"IsNode\":0,\"His_IsSave\":1},"
                + "{\"TagId\":102,\"TagType\":2,\"Name\":\"N102\",\"LongName\":\"运行\","
                + "\"ParentId\":1,\"DataType\":1,\"IsNode\":0,\"His_IsSave\":1},"
                + "{\"TagId\":103,\"TagType\":3,\"Name\":\"N103\",\"LongName\":\"批次\","
                + "\"ParentId\":1,\"DataType\":13,\"IsNode\":0,\"His_IsSave\":1}]}");
        server.on("/PointFilter", request -> "{\"values\":[]}");
        client = server.client(Collections.<String, Object>emptyMap(), true);
    }

    @AfterEach
    void tearDown() {
        client.close();
        server.close();
    }

    @Test
    void writeIsDisabledWithoutSecuritySwitch() {
        client.close();
        client = server.client();
        assertFalse(client.writeSession().isWritable());
        assertThrows(TsdbUnsupportedException.class, () -> client.writeSession().write(
                Collections.singletonList(new WriteRequest("压力",
                        Collections.singletonList(Sample.ofDouble(TS, 1.0d, Quality.GOOD, null))))));
        assertEquals(0, server.hits("/UpdRealData"));
    }

    @Test
    void sendsNumericBooleanAndStringValues() {
        server.on("/UpdRealData", request -> "{\"code\":0,\"mesg\":\"Success\"}");
        WriteResult result = client.writeSession().write(Arrays.asList(
                new WriteRequest("压力",
                        Collections.singletonList(Sample.ofDouble(TS, 12.5d, Quality.GOOD, null))),
                new WriteRequest("运行",
                        Collections.singletonList(Sample.ofBoolean(TS, true, Quality.GOOD, null))),
                new WriteRequest("批次",
                        Collections.singletonList(Sample.ofString(TS, "A-1", Quality.GOOD, null)))));

        assertTrue(result.isFullyAccepted());
        assertEquals(3, result.getAccepted());
        assertEquals(0, result.getRejected());

        PspaceTestServer.Request sent = last("/UpdRealData");
        assertTrue(sent.body.contains("\"tagid\":101"), sent.body);
        assertTrue(sent.body.contains("\"pv\":12.5"), sent.body);
        assertTrue(sent.body.contains("\"qy\":192"), sent.body);
        assertTrue(sent.body.contains("2023-11-14 22:13:20"), sent.body);
        assertTrue(sent.body.contains("\"pv\":true"), sent.body);
        assertTrue(sent.body.contains("\"pv\":\"A-1\""), sent.body);
    }

    @Test
    void countsPerItemFailures() {
        server.on("/UpdRealData", request -> "{\"count\":2,\"values\":["
                + "{\"idx\":0,\"pid\":101,\"code\":0,\"mesg\":\"\"},"
                + "{\"idx\":1,\"pid\":101,\"code\":-1,\"mesg\":\"quality rejected\"}]}");
        WriteResult result = client.writeSession().write(Collections.singletonList(
                new WriteRequest("压力", Arrays.asList(
                        Sample.ofDouble(TS, 1.0d, Quality.GOOD, null),
                        Sample.ofDouble(TS.plusSeconds(1), 2.0d, Quality.BAD, null)))));

        assertEquals(1, result.getAccepted());
        assertEquals(1, result.getRejected());
        assertEquals(1, result.getErrors().size());
        assertTrue(result.getErrors().get(0).contains("quality rejected"));
    }

    @Test
    void rejectsUncertainQualityNullValueAndUnknownTagLocally() {
        server.on("/UpdRealData", request -> "{\"code\":0,\"mesg\":\"Success\"}");
        Sample uncertain = Sample.ofDouble(TS, 1.0d, Quality.UNCERTAIN, null);
        Sample nullValue = Sample.ofNull(TS, ValueType.DOUBLE, Quality.GOOD, null);
        WriteResult result = client.writeSession().write(Arrays.asList(
                new WriteRequest("压力", Collections.singletonList(uncertain)),
                new WriteRequest("压力", Collections.singletonList(nullValue)),
                new WriteRequest("不存在", Collections.singletonList(
                        Sample.ofDouble(TS, 3.0d, Quality.GOOD, null)))));

        assertEquals(0, result.getAccepted());
        assertEquals(3, result.getRejected());
        assertEquals(3, result.getErrors().size());
        assertEquals(0, server.hits("/UpdRealData"));
        assertTrue(result.getErrors().get(0).contains("UNCERTAIN"));
        assertTrue(result.getErrors().get(2).contains("not found"));
    }

    @Test
    void rejectsEmptyRequestList() {
        assertThrows(IllegalArgumentException.class,
                () -> client.writeSession().write(Collections.<WriteRequest>emptyList()));
    }

    @Test
    void rejectsBytesValue() {
        WriteResult result = client.writeSession().write(Collections.singletonList(
                new WriteRequest("压力", Collections.singletonList(
                        Sample.ofBytes(TS, new byte[]{1, 2}, Quality.GOOD, null)))));
        assertEquals(1, result.getRejected());
        assertTrue(result.getErrors().get(0).contains("BYTES"));
    }

    private PspaceTestServer.Request last(String path) {
        List<PspaceTestServer.Request> requests = server.requests();
        for (int i = requests.size() - 1; i >= 0; i--) {
            if (path.equals(requests.get(i).path)) {
                return requests.get(i);
            }
        }
        throw new AssertionError("no request for " + path);
    }
}