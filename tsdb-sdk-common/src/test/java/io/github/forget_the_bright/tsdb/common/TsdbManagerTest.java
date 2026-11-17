package io.github.forget_the_bright.tsdb.common;

import io.github.forget_the_bright.tsdb.common.api.CurrentValueReader;
import io.github.forget_the_bright.tsdb.common.api.HistoryReader;
import io.github.forget_the_bright.tsdb.common.api.TagCatalog;
import io.github.forget_the_bright.tsdb.common.api.TsdbClient;
import io.github.forget_the_bright.tsdb.common.api.WriteSession;
import io.github.forget_the_bright.tsdb.common.config.TsdbConfig;
import io.github.forget_the_bright.tsdb.common.config.TsdbConfigLoader;
import io.github.forget_the_bright.tsdb.common.exception.TsdbConfigurationException;
import io.github.forget_the_bright.tsdb.common.model.Capability;
import io.github.forget_the_bright.tsdb.common.model.CapabilitySet;
import io.github.forget_the_bright.tsdb.common.model.HealthStatus;
import io.github.forget_the_bright.tsdb.common.spi.DriverContext;
import io.github.forget_the_bright.tsdb.common.spi.TsdbDriver;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TsdbManagerTest {

    private static final String CONFIG = ""
            + "tsdb:\n"
            + "  default-instance: hls_main\n"
            + "  security:\n"
            + "    allow-write: false\n"
            + "    audit:\n"
            + "      enabled: true\n"
            + "  instances:\n"
            + "    hls_main:\n"
            + "      type: fake\n"
            + "      base-url: http://localhost:9000\n"
            + "      auth:\n"
            + "        username: ${TSDB_TEST_USER:fallback}\n"
            + "    ge_main:\n"
            + "      type: fake\n"
            + "      base-url: https://localhost:9443\n"
            + "    bad_site:\n"
            + "      type: fake\n"
            + "      base-url: https://localhost:9444\n";

    @Test
    void loadsYamlAndResolvesEnvWithDefault() {
        TsdbConfig config = TsdbConfigLoader.fromYaml(CONFIG);
        assertEquals("hls_main", config.getDefaultInstance());
        assertTrue(config.getSecurity().isAuditEnabled());
        assertEquals("fallback", config.instance("hls_main").getAuth().getUsername());
        assertEquals(3, config.getInstances().size());
    }

    @Test
    void acceptsBooleanLikeInstanceKeyWithoutClassCastException() {
        TsdbConfig config = TsdbConfigLoader.fromYaml(""
                + "tsdb:\n"
                + "  instances:\n"
                + "    on:\n"
                + "      type: fake\n");
        assertTrue(config.getInstances().containsKey("true"),
                "YAML 1.1 boolean key must be normalized instead of failing with ClassCastException");
    }

    @Test
    void rejectsEmptyConfig() {
        assertThrows(TsdbConfigurationException.class, () -> TsdbConfigLoader.fromYaml("  "));
        assertThrows(TsdbConfigurationException.class, () -> TsdbConfigLoader.fromYaml("tsdb:\n  instances: {}\n"));
    }

    @Test
    void createsDefaultAndSwitchesSingleActiveClient() {
        FakeDriver driver = new FakeDriver();
        TsdbManager manager = TsdbManager.builder()
                .config(TsdbConfigLoader.fromYaml(CONFIG))
                .driver(driver)
                .build();

        TsdbClient first = manager.client();
        assertEquals("hls_main", first.instanceName());
        assertEquals("hls_main", manager.activeInstance());

        TsdbClient second = manager.client("ge_main");
        assertEquals("ge_main", second.instanceName());
        assertEquals("ge_main", manager.activeInstance());
        assertTrue(((FakeClient) first).closed, "old client must be closed");
        assertSame(second, manager.client());

        manager.close();
        assertTrue(((FakeClient) second).closed);
    }

    @Test
    void failedSwitchKeepsPreviousClientActive() {
        FakeDriver driver = new FakeDriver();
        TsdbManager manager = TsdbManager.builder()
                .config(TsdbConfigLoader.fromYaml(CONFIG))
                .driver(driver)
                .build();

        TsdbClient first = manager.client();
        assertThrows(IllegalStateException.class, () -> manager.client("bad_site"));
        assertEquals("hls_main", manager.activeInstance());
        assertSame(first, manager.client());
        assertFalse(((FakeClient) first).closed, "old client must stay open after a failed switch");
    }

    @Test
    void rejectsUseAfterClose() {
        TsdbManager manager = TsdbManager.builder()
                .config(TsdbConfigLoader.fromYaml(CONFIG))
                .driver(new FakeDriver())
                .build();
        manager.client();
        manager.close();
        assertThrows(TsdbConfigurationException.class, manager::client);
        assertThrows(TsdbConfigurationException.class, () -> manager.client("ge_main"));
        assertThrows(TsdbConfigurationException.class, () -> manager.switchTo("ge_main"));
    }

    @Test
    void failsWhenTypeHasNoDriver() {
        TsdbConfig config = TsdbConfigLoader.fromYaml(""
                + "tsdb:\n"
                + "  instances:\n"
                + "    only:\n"
                + "      type: missing\n");
        TsdbManager manager = TsdbManager.builder().config(config).driver(new FakeDriver()).build();
        TsdbConfigurationException error =
                assertThrows(TsdbConfigurationException.class, manager::client);
        assertTrue(error.getMessage().contains("no driver for type 'missing'"));
    }

    @Test
    void rejectsDisabledInstance() {
        TsdbConfig config = TsdbConfigLoader.fromYaml(""
                + "tsdb:\n"
                + "  instances:\n"
                + "    disabled_site:\n"
                + "      type: fake\n"
                + "      enabled: false\n");
        TsdbManager manager = TsdbManager.builder().config(config).driver(new FakeDriver()).build();
        assertThrows(TsdbConfigurationException.class, () -> manager.client("disabled_site"));
    }

    @Test
    void builderDiscoversNothingAndFailsClearlyWhenNoDriverRegistered() {
        TsdbConfig config = TsdbConfigLoader.fromYaml(CONFIG);
        TsdbManager manager = TsdbManager.builder().config(config).build();
        TsdbConfigurationException error =
                assertThrows(TsdbConfigurationException.class, manager::client);
        assertNotNull(error.getMessage());
        assertTrue(error.getMessage().contains("no driver for type"));
    }

    private static final class FakeDriver implements TsdbDriver {
        @Override
        public String type() {
            return "fake";
        }

        @Override
        public Set<Capability> capabilities() {
            return Collections.singleton(Capability.TAG_BROWSE);
        }

        @Override
        public TsdbClient create(io.github.forget_the_bright.tsdb.common.config.TsdbInstanceConfig config, DriverContext context) {
            if ("bad_site".equals(context.getInstanceName())) {
                throw new IllegalStateException("simulated connection failure");
            }
            return new FakeClient(context.getInstanceName());
        }
    }

    private static final class FakeClient implements TsdbClient {
        private final String name;
        private boolean closed;

        private FakeClient(String name) {
            this.name = name;
        }

        @Override
        public String instanceName() {
            return name;
        }

        @Override
        public String type() {
            return "fake";
        }

        @Override
        public CapabilitySet capabilities() {
            return CapabilitySet.of(Capability.TAG_BROWSE);
        }

        @Override
        public TagCatalog tagCatalog() {
            return null;
        }

        @Override
        public CurrentValueReader currentValues() {
            return null;
        }

        @Override
        public HistoryReader history() {
            return null;
        }

        @Override
        public WriteSession writeSession() {
            return null;
        }

        @Override
        public HealthStatus health() {
            return HealthStatus.up(0L);
        }

        @Override
        public <T> T unwrap(Class<T> nativeType) {
            return nativeType.isInstance(this) ? nativeType.cast(this) : null;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
