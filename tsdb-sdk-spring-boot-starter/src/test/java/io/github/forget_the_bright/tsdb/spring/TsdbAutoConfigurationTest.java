package io.github.forget_the_bright.tsdb.spring;

import io.github.forget_the_bright.tsdb.common.TsdbManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindingPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TsdbAutoConfigurationTest {

    @Test
    void createsManagerFromPropertiesWithoutConnecting() {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("tsdb.default-instance", "iotdb_main");
        values.put("tsdb.instances.iotdb_main.type", "iotdb");
        values.put("tsdb.instances.iotdb_main.base-url", "http://127.0.0.1:18080");
        values.put("tsdb.instances.iotdb_main.auth.username", "root");
        values.put("tsdb.instances.iotdb_main.auth.password", "root");

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("test", values));
        ConfigurationPropertiesBindingPostProcessor.register(context);
        context.register(TsdbAutoConfiguration.class);
        context.refresh();
        try {
            TsdbManager manager = context.getBean(TsdbManager.class);
            assertNotNull(manager);
            assertEquals("iotdb_main", manager.config().getDefaultInstance());
            assertEquals(1, manager.availableInstances().size());
        } finally {
            context.close();
        }
    }
}
