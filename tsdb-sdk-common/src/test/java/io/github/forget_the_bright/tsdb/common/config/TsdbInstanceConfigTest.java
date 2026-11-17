package io.github.forget_the_bright.tsdb.common.config;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TsdbInstanceConfig} 参数读取辅助方法的行为测试。
 *
 * <p>重点锁定严格布尔：{@code yes}/{@code 1}/{@code on} 这类宽松写法必须失败，
 * 不能被静默当成 {@code false}，否则一次配置笔误就会关掉安全开关。</p>
 */
class TsdbInstanceConfigTest {

    private static TsdbInstanceConfig config(String key, Object value) {
        TsdbInstanceConfig config = new TsdbInstanceConfig();
        Map<String, Object> options = new LinkedHashMap<String, Object>();
        options.put(key, value);
        config.setOptions(options);
        return config;
    }

    @Test
    void intAndLongOptionsParseScalarsAndFallBackWhenMissing() {
        assertEquals(7, config("n", 7).intOption("n", 1));
        assertEquals(1, config("other", 7).intOption("n", 1));
        assertEquals(5_000_000_000L, config("n", "5000000000").longOption("n", 0L));
        assertEquals(3L, config("other", 7).longOption("n", 3L));
        assertThrows(NumberFormatException.class, () -> config("n", "abc").longOption("n", 0L));
    }

    @Test
    void booleanOptionAcceptsOnlyTrueAndFalseIgnoringCaseAndPadding() {
        assertTrue(config("b", "true").booleanOption("b", false));
        assertTrue(config("b", " TRUE ").booleanOption("b", false));
        assertFalse(config("b", "False").booleanOption("b", true));
        assertTrue(config("b", Boolean.TRUE).booleanOption("b", false));
        assertTrue(config("other", "true").booleanOption("b", true));
        assertFalse(config("other", "true").booleanOption("b", false));
        assertTrue(config("b", "  ").booleanOption("b", true));
    }

    @Test
    void booleanOptionRejectsLooseWritingsInsteadOfSilentlyReturningFalse() {
        assertThrows(IllegalArgumentException.class, () -> config("b", "yes").booleanOption("b", true));
        assertThrows(IllegalArgumentException.class, () -> config("b", "1").booleanOption("b", true));
        assertThrows(IllegalArgumentException.class, () -> config("b", "on").booleanOption("b", true));
    }
}