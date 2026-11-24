package io.github.forget_the_bright.tsdb.pspace.internal;

import com.alibaba.fastjson.JSONObject;
import io.github.forget_the_bright.tsdb.common.exception.TsdbException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException;
import io.github.forget_the_bright.tsdb.pspace.PspaceTestServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PspaceHttpTest {

    private PspaceTestServer server;
    private PspaceHttp http;

    @BeforeEach
    void setUp() throws IOException {
        server = new PspaceTestServer();
        http = new PspaceHttp(server.config());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void unwrapsEnvelopeDataObject() {
        server.on("/ServerInfo", request ->
                "{\"code\":0,\"mesg\":\"Success\",\"data\":{\"name\":\"pSpace\",\"version\":\"7.1\"}}");
        JSONObject info = http.getObject("/ServerInfo", null);
        assertEquals("pSpace", info.getString("name"));
        assertEquals("7.1", info.getString("version"));
    }

    @Test
    void acceptsBareDataWithoutEnvelope() {
        server.on("/PointPage", request -> "{\"total\":0,\"page\":{\"count\":1,\"values\":[]}}");
        JSONObject page = http.getObject("/PointPage", null);
        assertEquals(0, page.getIntValue("total"));
    }

    @Test
    void mapsNonZeroCodeToQueryException() {
        server.on("/Point", request -> "{\"code\":-1,\"mesg\":\"Not find children\"}");
        TsdbException e = assertThrows(TsdbException.class, () -> http.get("/Point", null));
        assertTrue(e.getMessage().contains("-1"), e.getMessage());
        assertTrue(e.getMessage().contains("Not find children"), e.getMessage());
    }

    @Test
    void decodesGbkResponseAndSendsCharsetParameter() {
        server.on("/Point", request ->
                "{\"code\":0,\"data\":{\"name\":\"压力\",\"unit\":\"兆帕\"}}");
        Map<String, String> query = new LinkedHashMap<String, String>();
        query.put("charset", "gbk");
        JSONObject point = http.getObject("/Point", query);
        assertEquals("压力", point.getString("name"));
        assertEquals("兆帕", point.getString("unit"));
        assertEquals("gbk", server.requests().get(0).param("charset"));
    }

    @Test
    void rejectsArchivedResponseBeforeAnyRequest() {
        Map<String, String> query = new LinkedHashMap<String, String>();
        query.put("archived", "1");
        assertThrows(TsdbUnsupportedException.class, () -> http.get("/Point", query));
        assertEquals(0, server.hits("/Point"));
    }

    @Test
    void mapsUnknownEndpointToTsdbException() {
        assertThrows(TsdbException.class,
                () -> http.execute("GET", "/Missing", null, null));
    }
}