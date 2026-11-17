package io.github.forget_the_bright.tsdb.common.model;

import com.alibaba.fastjson.JSON;
import io.github.forget_the_bright.tsdb.common.config.TimeConfig;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证公共样本同时暴露绝对时刻、{@link Date} 视图与已格式化文本，
 * 并确认 JSON 序列化后前端可以直接拿到这两个便利字段。
 */
class SampleTimeFieldsTest {

    @Test
    void exposesDateAndFormattedTextForFrontend() {
        Instant instant = Instant.parse("2023-11-14T22:13:20Z");
        Sample sample = Sample.ofDouble(instant, 12.5d, Quality.GOOD, 3)
                .withTimeFormat(TimeConfig.defaults());

        assertEquals(instant, sample.getTimestamp());
        assertEquals(Date.from(instant), sample.getTimestampDate());
        assertEquals("2023-11-14 22:13:20", sample.getTimestampText());
        assertEquals(12.5d, sample.getDoubleValue(), 0.0001d);
    }

    @Test
    void keepsDefaultTextWhenNoTimeConfig() {
        Instant instant = Instant.parse("2023-11-14T22:13:20Z");
        Sample sample = Sample.ofDouble(instant, 1.0d, Quality.GOOD, null);

        assertEquals(instant.toString(), sample.getTimestampText());
        assertEquals(Date.from(instant), sample.getTimestampDate());
    }

    @Test
    void serializesSampleConvenienceFieldsToJson() {
        Sample sample = Sample.ofDouble(Instant.parse("2023-11-14T22:13:20Z"), 12.5d,
                Quality.GOOD, 3).withTimeFormat(TimeConfig.defaults());

        String json = JSON.toJSONString(sample);

        assertTrue(json.contains("\"timestamp\":\"2023-11-14T22:13:20Z\""), json);
        assertTrue(json.contains("\"timestampDate\""), json);
        assertTrue(json.contains("\"timestampText\":\"2023-11-14 22:13:20\""), json);
    }

    @Test
    void serializesHealthStatusConvenienceFieldsToJson() {
        HealthStatus status = new HealthStatus(HealthStatus.State.UP, "ok", 12L,
                Instant.parse("2023-11-14T22:13:20Z"), TimeConfig.defaults());

        String json = JSON.toJSONString(status);

        assertTrue(json.contains("\"checkedAt\":\"2023-11-14T22:13:20Z\""), json);
        assertTrue(json.contains("\"checkedAtDate\""), json);
        assertTrue(json.contains("\"checkedAtText\":\"2023-11-14 22:13:20\""), json);
    }

    @Test
    void writesDateViewAsEpochMillis() {
        String json = JSON.toJSONString(Sample.ofLong(
                Instant.parse("2023-11-14T22:13:20Z"), 7L, Quality.GOOD, 3));

        assertTrue(json.contains("\"timestampDate\":1700000000000"), json);
    }

    @Test
    void omitsNullFieldsBecauseFastjsonDefaultIsUsed() {
        String json = JSON.toJSONString(
                Sample.ofNull(Instant.parse("2023-11-14T22:13:20Z"), ValueType.DOUBLE,
                        Quality.UNKNOWN, null));

        assertTrue(json.contains("\"timestamp\":\"2023-11-14T22:13:20Z\""), json);
        assertFalse(json.contains("\"value\":"), json);
        assertFalse(json.contains("\"textValue\":"), json);
    }
}
