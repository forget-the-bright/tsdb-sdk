package io.github.forget_the_bright.tsdb.common.internal.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.forget_the_bright.tsdb.common.exception.TsdbConnectionException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpTransportTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", exchange -> {
            String body = read(exchange.getRequestBody());
            String accept = exchange.getRequestHeaders().getFirst("Accept");
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            String response = "{\"body\":\"" + body + "\",\"accept\":\"" + accept
                    + "\",\"contentType\":\"" + contentType + "\"}";
            respond(exchange, 200, response);
        });
        server.createContext("/fail", exchange -> respond(exchange, 500, "boom"));
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/echo");
            respond(exchange, 302, "");
        });
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "late");
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsUtf8BodyWithExactContentType() {
        HttpTransport transport = new HttpTransport("TEST", 2000, 2000, true, null);
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("X-Token", "abc");
        headers.put("X-Skip", null);
        HttpResult result = transport.execute("POST", baseUrl + "/echo", headers,
                "{\"name\":\"中文\"}", "application/json");
        assertEquals(200, result.getStatus());
        assertTrue(result.getBody().contains("\"contentType\":\"application/json\""));
        assertTrue(result.getBody().contains("\"accept\":\"application/json\""));
        assertTrue(result.getBody().contains("中文"));
    }

    @Test
    void readsErrorBodyWithoutThrowing() {
        HttpTransport transport = new HttpTransport("TEST", 2000, 2000, true, null);
        HttpResult result = transport.execute("GET", baseUrl + "/fail", null, null, null);
        assertEquals(500, result.getStatus());
        assertEquals("boom", result.getBody());
    }

    @Test
    void doesNotFollowRedirects() {
        HttpTransport transport = new HttpTransport("TEST", 2000, 2000, true, null);
        HttpResult result = transport.execute("GET", baseUrl + "/redirect", null, null, null);
        assertEquals(302, result.getStatus());
    }

    @Test
    void mapsTimeoutAndConnectionFailure() {
        HttpTransport transport = new HttpTransport("TEST", 100, 100, true, null);
        assertThrows(TsdbTimeoutException.class,
                () -> transport.execute("GET", baseUrl + "/slow", null, null, null));
        assertThrows(TsdbConnectionException.class,
                () -> transport.execute("GET", "http://127.0.0.1:1/unreachable", null, null, null));
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
