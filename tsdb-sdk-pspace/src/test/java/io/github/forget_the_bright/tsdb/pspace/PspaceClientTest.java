package io.github.forget_the_bright.tsdb.pspace;

import io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException;
import io.github.forget_the_bright.tsdb.common.model.Capability;
import io.github.forget_the_bright.tsdb.common.model.HealthStatus;
import io.github.forget_the_bright.tsdb.pspace.internal.PspaceNativeApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PspaceClientTest {

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
    void exposesDeclaredCapabilities() {
        assertTrue(client.capabilities().supports(Capability.TAG_BROWSE));
        assertTrue(client.capabilities().supports(Capability.TAG_SEARCH));
        assertTrue(client.capabilities().supports(Capability.CURRENT_VALUE));
        assertTrue(client.capabilities().supports(Capability.AGGREGATED_HISTORY));
        assertTrue(client.capabilities().supports(Capability.INTERPOLATED_HISTORY));
        assertTrue(client.capabilities().supports(Capability.SAMPLED_HISTORY));
        assertTrue(client.capabilities().supports(Capability.CUMULATIVE_DELTA));
        assertTrue(client.capabilities().supports(Capability.WRITE));
        assertFalse(client.capabilities().supports(Capability.TREND));
        assertEquals("pspace", client.type());
        assertEquals("pspace-test", client.instanceName());
    }

    @Test
    void reportsUpAndDownWithoutThrowing() {
        server.on("/ServerInfo", request -> "{\"code\":0,\"mesg\":\"Success\",\"data\":{\"name\":\"pSpace\"}}");
        HealthStatus up = client.health();
        assertEquals(HealthStatus.State.UP, up.getState());

        server.on("/ServerInfo", request -> "{\"code\":-1,\"mesg\":\"boom\"}");
        HealthStatus down = client.health();
        assertEquals(HealthStatus.State.DOWN, down.getState());
        assertNotNull(down.getMessage());
    }

    @Test
    void unwrapsKnownNativeTypesAndRejectsOthers() {
        assertSame(client, client.unwrap(PspaceClient.class));
        assertNotNull(client.unwrap(PspaceNativeApi.class));
        assertNotNull(client.unwrap(PspaceConfig.class));
        assertThrows(TsdbUnsupportedException.class, () -> client.unwrap(String.class));
        assertThrows(IllegalArgumentException.class, () -> client.unwrap(null));
    }

    @Test
    void closeIsIdempotentAndBlocksSubInterfaces() {
        client.close();
        assertThrows(IllegalStateException.class, () -> client.tagCatalog());
        assertThrows(IllegalStateException.class, () -> client.currentValues());
        assertThrows(IllegalStateException.class, () -> client.history());
        assertThrows(IllegalStateException.class, () -> client.deltaHistory());
        assertThrows(IllegalStateException.class, () -> client.writeSession());
        assertThrows(IllegalStateException.class, () -> client.health());
        client.close();
    }

    @Test
    void formatsAndParsesTimestampsInConfiguredZone() {
        assertEquals("2023-11-14 22:13:21.000",
                client.formatTimestamp(Instant.parse("2023-11-14T14:13:21Z")));
        assertEquals(Instant.parse("2023-11-14T14:13:21Z"),
                client.parseTimestamp("2023-11-14 22:13:21.000"));
    }
}