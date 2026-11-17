package io.github.forget_the_bright.tsdb.common.config;

import io.github.forget_the_bright.tsdb.common.exception.TsdbConfigurationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TimeConfig} 的单元测试。
 *
 * <p>重点验证三件事：请求时区/模式可配置；响应无时区文本按
 * {@code response.source-zone} 解释；展示按 {@code response.target-zone} 输出。
 * 这些规则直接决定铜陵 GE 现场“UTC 请求、UTC 响应、GMT+8 展示”的配置是否生效。</p>
 */
class TimeConfigTest {

    @Test
    void defaultsUseUtcAndIsoRequestPattern() {
        TimeConfig config = TimeConfig.from(new TsdbInstanceConfig());
        Instant instant = Instant.parse("2026-11-09T06:00:00Z");

        assertEquals("2026-11-09T06:00:00Z", config.formatRequest(instant));
        assertEquals(instant, config.parseResponse("2026-11-09 06:00:00"));
        assertEquals("2026-11-09 06:00:00", config.formatResponse(instant));
        assertEquals(ZoneOffset.UTC, config.getRequestZone());
        assertEquals(ZoneOffset.UTC, config.getResponseSourceZone());
    }

    @Test
    void convertsUtcResponseToConfiguredGmt8Display() {
        TimeConfig config = TimeConfig.from(instance(
                "time.response.source-zone", "UTC",
                "time.response.target-zone", "GMT+8",
                "time.response.pattern", "yyyy-MM-dd HH:mm:ss"));
        Instant instant = Instant.parse("2026-11-09T06:00:00Z");

        assertEquals(instant, config.parseResponse("2026-11-09 06:00:00"));
        assertEquals("2026-11-09 14:00:00", config.formatResponse(instant));
    }

    @Test
    void interpretsNaiveResponseInConfiguredSourceZone() {
        TimeConfig config = TimeConfig.from(instance(
                "time.response.source-zone", "GMT+8",
                "time.response.target-zone", "UTC"));

        assertEquals(Instant.parse("2026-11-09T06:00:00Z"),
                config.parseResponse("2026-11-09 14:00:00"));
    }

    @Test
    void explicitOffsetWinsOverSourceZone() {
        TimeConfig config = TimeConfig.from(instance(
                "time.response.source-zone", "GMT+8"));

        assertEquals(Instant.parse("2026-11-09T06:00:00Z"),
                config.parseResponse("2026-11-09T06:00:00Z"));
        assertEquals(Instant.parse("2026-11-09T06:00:00Z"),
                config.parseResponse("2026-11-09T14:00:00+08:00"));
    }

    @Test
    void formattingCanBeDisabled() {
        TimeConfig config = TimeConfig.from(instance(
                "time.request.enabled", "false",
                "time.request.zone", "GMT+8",
                "time.response.enabled", "false",
                "time.response.target-zone", "GMT+8"));

        assertEquals("2026-11-09T06:00:00Z",
                config.formatRequest(Instant.parse("2026-11-09T06:00:00Z")));
        assertEquals("2026-11-09T06:00:00Z",
                config.formatResponse(Instant.parse("2026-11-09T06:00:00Z")));
    }

    @Test
    void supportsNestedYamlStyleOptions() {
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("zone", "UTC");
        request.put("pattern", "yyyy-MM-dd'T'HH:mm:ss'Z'");
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("source-zone", "UTC");
        response.put("target-zone", "Asia/Shanghai");
        response.put("pattern", "yyyy-MM-dd HH:mm:ss");
        Map<String, Object> time = new LinkedHashMap<String, Object>();
        time.put("request", request);
        time.put("response", response);
        Map<String, Object> options = new LinkedHashMap<String, Object>();
        options.put("time", time);
        TsdbInstanceConfig instance = new TsdbInstanceConfig();
        instance.setOptions(options);

        TimeConfig config = TimeConfig.from(instance);
        assertEquals("2026-11-09T06:00:00Z",
                config.formatRequest(Instant.parse("2026-11-09T06:00:00Z")));
        assertEquals("2026-11-09 14:00:00",
                config.formatResponse(Instant.parse("2026-11-09T06:00:00Z")));
        assertTrue(config.isResponseEnabled());
    }

    @Test
    void loadsTimeOptionsFromYaml() {
        String yaml = "default-instance: ge_main\n"
                + "instances:\n"
                + "  ge_main:\n"
                + "    type: ge\n"
                + "    base-url: https://example\n"
                + "    options:\n"
                + "      time:\n"
                + "        request:\n"
                + "          zone: UTC\n"
                + "        response:\n"
                + "          source-zone: UTC\n"
                + "          target-zone: GMT+8\n"
                + "          pattern: yyyy-MM-dd HH:mm:ss\n";
        TsdbConfig config = TsdbConfigLoader.fromYaml(yaml);
        TimeConfig time = TimeConfig.from(config.instance("ge_main"));

        assertEquals("2026-11-09 14:00:00", time.formatResponse(
                Instant.parse("2026-11-09T06:00:00Z")));
        assertEquals("2026-11-09T06:00:00Z", time.formatRequest(
                Instant.parse("2026-11-09T06:00:00Z")));
    }

    @Test
    void rejectsInvalidZone() {
        assertThrows(TsdbConfigurationException.class, () -> TimeConfig.from(
                instance("time.request.zone", "Mars/Olympus")));
    }

    private static TsdbInstanceConfig instance(String... keyValues) {
        Map<String, Object> options = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            options.put(keyValues[i], keyValues[i + 1]);
        }
        TsdbInstanceConfig config = new TsdbInstanceConfig();
        config.setOptions(options);
        return config;
    }
}
