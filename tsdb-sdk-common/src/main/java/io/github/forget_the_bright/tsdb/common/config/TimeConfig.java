package io.github.forget_the_bright.tsdb.common.config;

import io.github.forget_the_bright.tsdb.common.exception.TsdbConfigurationException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbDataException;
import io.github.forget_the_bright.tsdb.common.internal.Texts;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 可配置的时间格式转换规则。
 *
 * <p>工业时序库对时间的表达并不统一：有的接口要求 UTC 字符串，有的接口使用
 * 服务器本地时间；有的响应带 {@code Z}/偏移量，有的只返回
 * {@code yyyy-MM-dd HH:mm:ss} 这样的“无时区”文本。如果 SDK 把时区写死，
 * 换一个现场就可能整体偏移 8 小时。本类把“请求怎么写、响应怎么读、展示怎么格式化”
 * 拆成三组独立配置，由使用方按项目现场选择。</p>
 *
 * <p><strong>三个概念：</strong></p>
 * <ul>
 *   <li><strong>请求格式</strong>（{@code time.request.*}）：把 SDK 内部的
 *       {@link Instant} 转成厂商接口要求的字符串。{@code enabled=false} 时使用
 *       ISO-8601 UTC（{@link Instant#toString()}）。</li>
 *   <li><strong>响应解析</strong>（{@code time.response.source-zone}）：厂商返回的
 *       无时区文本按哪个时区解释。带 {@code Z} 或显式偏移的文本始终以文本为准，
 *       不会被该配置覆盖。</li>
 *   <li><strong>响应展示</strong>（{@code time.response.target-zone/pattern}）：
 *       调用 {@link #formatResponse(Instant)} 时输出成哪个时区、哪种格式；
 *       {@code enabled=false} 时输出 ISO-8601 UTC。</li>
 * </ul>
 *
 * <p><strong>YAML 写法</strong>（推荐嵌套，也兼容扁平键）：</p>
 * <pre>{@code
 * options:
 *   time:
 *     request:
 *       enabled: true
 *       zone: UTC
 *       pattern: "yyyy-MM-dd'T'HH:mm:ssXXX"
 *     response:
 *       enabled: true
 *       source-zone: UTC
 *       target-zone: "GMT+8"
 *       pattern: "yyyy-MM-dd HH:mm:ss"
 * }</pre>
 *
 * <p>扁平写法示例：{@code time.request.zone: UTC}、
 * {@code time.response.source-zone: UTC}。嵌套键优先于扁平键。</p>
 *
 * <p><strong>默认值：</strong>请求 UTC、{@code yyyy-MM-dd'T'HH:mm:ssXXX}；
 * 响应按 UTC 解析、按 UTC 展示、{@code yyyy-MM-dd HH:mm:ss}。默认值刻意保持
 * “不假设服务器在东八区”，需要本地时间时显式配置 {@code target-zone: GMT+8}。</p>
 *
 * <p>本类不可变、线程安全；格式化器在构造时一次性编译，运行期不再解析模式。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class TimeConfig {

    /**
     * 默认请求格式：UTC 下输出 {@code 2026-11-09T06:00:00Z}，非 UTC 时输出
     * {@code 2026-11-09T14:00:00+08:00}。使用 {@code XXX} 而不是字面量
     * {@code 'Z'}，这样同一套配置可以安全地切换时区。
     */
    public static final String DEFAULT_REQUEST_PATTERN = "yyyy-MM-dd'T'HH:mm:ssXXX";

    /**
     * 默认响应展示格式，不带时区；时区由 {@code target-zone} 决定。
     */
    public static final String DEFAULT_RESPONSE_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /**
     * 默认时区：UTC。
     */
    public static final ZoneId DEFAULT_ZONE = ZoneOffset.UTC;

    private static final DateTimeFormatter LOCAL_DATE_TIME = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .optionalEnd()
            .toFormatter();

    private static final DateTimeFormatter LOCAL_ISO = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .optionalEnd()
            .toFormatter();

    private final boolean requestEnabled;
    private final ZoneId requestZone;
    private final String requestPattern;
    private final DateTimeFormatter requestFormatter;
    private final boolean responseEnabled;
    private final ZoneId responseSourceZone;
    private final ZoneId responseTargetZone;
    private final String responsePattern;
    private final DateTimeFormatter responseFormatter;

    private TimeConfig(boolean requestEnabled, ZoneId requestZone, String requestPattern,
                       boolean responseEnabled, ZoneId responseSourceZone, ZoneId responseTargetZone,
                       String responsePattern) {
        this.requestEnabled = requestEnabled;
        this.requestZone = requestZone;
        this.requestPattern = requestPattern;
        this.requestFormatter = DateTimeFormatter.ofPattern(requestPattern).withZone(requestZone);
        this.responseEnabled = responseEnabled;
        this.responseSourceZone = responseSourceZone;
        this.responseTargetZone = responseTargetZone;
        this.responsePattern = responsePattern;
        this.responseFormatter = DateTimeFormatter.ofPattern(responsePattern).withZone(responseTargetZone);
    }

    /**
     * 返回全默认配置：请求 UTC、响应按 UTC 解析并按 UTC 展示。
     *
     * @return 非 {@code null} 的默认配置
     */
    public static TimeConfig defaults() {
        return new TimeConfig(true, DEFAULT_ZONE, DEFAULT_REQUEST_PATTERN,
                true, DEFAULT_ZONE, DEFAULT_ZONE, DEFAULT_RESPONSE_PATTERN);
    }

    /**
     * 从实例配置中读取 {@code options.time.*}，未配置的项使用默认值。
     *
     * <p>同时支持嵌套写法（{@code options.time.request.zone}）与扁平写法
     * （{@code options["time.request.zone"]}）；同名键同时存在时嵌套优先。</p>
     *
     * @param config 实例配置，不能为 {@code null}
     * @return 解析并校验后的时间配置，非 {@code null}
     * @throws TsdbConfigurationException 时区名、布尔值或时间模式非法时抛出
     */
    public static TimeConfig from(TsdbInstanceConfig config) {
        if (config == null) {
            throw new TsdbConfigurationException("time config requires an instance config");
        }
        Map<String, Object> time = asMap(config.getOptions().get("time"));
        Map<String, Object> request = asMap(value(time, "request"));
        Map<String, Object> response = asMap(value(time, "response"));

        boolean requestEnabled = bool(request, "enabled",
                config.option("time.request.enabled"), true);
        ZoneId requestZone = zone(string(request, "zone",
                config.option("time.request.zone"), DEFAULT_ZONE.getId()), "time.request.zone");
        String requestPattern = string(request, "pattern",
                config.option("time.request.pattern"), DEFAULT_REQUEST_PATTERN);
        validatePattern(requestPattern, "time.request.pattern");

        boolean responseEnabled = bool(response, "enabled",
                config.option("time.response.enabled"), true);
        ZoneId sourceZone = zone(string(response, "source-zone",
                config.option("time.response.source-zone"), DEFAULT_ZONE.getId()),
                "time.response.source-zone");
        ZoneId targetZone = zone(string(response, "target-zone",
                config.option("time.response.target-zone"), DEFAULT_ZONE.getId()),
                "time.response.target-zone");
        String responsePattern = string(response, "pattern",
                config.option("time.response.pattern"), DEFAULT_RESPONSE_PATTERN);
        validatePattern(responsePattern, "time.response.pattern");

        return new TimeConfig(requestEnabled, requestZone, requestPattern,
                responseEnabled, sourceZone, targetZone, responsePattern);
    }

    /**
     * 按请求配置格式化一个时刻。
     *
     * @param instant 时刻，允许为 {@code null}
     * @return 厂商接口使用的字符串；入参为 {@code null} 时返回 {@code null}
     */
    public String formatRequest(Instant instant) {
        if (instant == null) {
            return null;
        }
        return requestEnabled ? requestFormatter.format(instant) : instant.toString();
    }

    /**
     * 解析厂商返回的时间文本。
     *
     * <p>优先级：显式 {@code Z}/偏移量 &gt; 配置的 {@code response.pattern}
     * &gt; 常见 {@code yyyy-MM-dd HH:mm:ss[.fff]} / ISO 本地格式。无时区文本按
     * {@code response.source-zone} 解释；无法解析时抛异常，不做“当前时间”兜底。</p>
     *
     * @param text 厂商返回的时间文本，允许为 {@code null} 或空白
     * @return 绝对时刻；入参为空时返回 {@code null}
     * @throws TsdbDataException 文本无法解析时抛出
     */
    public Instant parseResponse(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        String value = text.trim();
        if (value.endsWith("Z") || value.endsWith("z")) {
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException ignored) {
                // 继续尝试偏移量与本地格式
            }
        }
        if (hasOffset(value)) {
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (DateTimeParseException ignored) {
                // 继续尝试本地格式
            }
        }
        try {
            return LocalDateTime.parse(value, DateTimeFormatter.ofPattern(responsePattern))
                    .atZone(responseSourceZone).toInstant();
        } catch (DateTimeParseException ignored) {
            // 继续尝试常见格式
        }
        try {
            return LocalDateTime.parse(value, LOCAL_DATE_TIME)
                    .atZone(responseSourceZone).toInstant();
        } catch (DateTimeParseException ignored) {
            // 继续尝试 ISO 本地格式
        }
        try {
            return LocalDateTime.parse(value, LOCAL_ISO)
                    .atZone(responseSourceZone).toInstant();
        } catch (DateTimeParseException e) {
            throw new TsdbDataException("invalid timestamp '" + text
                    + "' for zone " + responseSourceZone, e);
        }
    }

    /**
     * 按响应展示配置格式化一个时刻。
     *
     * @param instant 时刻，允许为 {@code null}
     * @return 展示字符串；{@code response.enabled=false} 时为 ISO-8601 UTC；
     *         入参为 {@code null} 时返回 {@code null}
     */
    public String formatResponse(Instant instant) {
        if (instant == null) {
            return null;
        }
        return responseEnabled ? responseFormatter.format(instant) : instant.toString();
    }

    /**
     * 返回请求格式化器，便于调用方复用（不可变、线程安全）。
     *
     * @return 非 {@code null} 的格式化器
     */
    public DateTimeFormatter requestFormatter() {
        return requestFormatter;
    }

    /**
     * 返回响应展示格式化器，便于调用方复用（不可变、线程安全）。
     *
     * @return 非 {@code null} 的格式化器
     */
    public DateTimeFormatter responseFormatter() {
        return responseFormatter;
    }

    /**
     * 请求格式化是否启用。
     *
     * @return 启用返回 {@code true}
     */
    public boolean isRequestEnabled() {
        return requestEnabled;
    }

    /**
     * 请求使用的时区。
     *
     * @return 非 {@code null} 的时区
     */
    public ZoneId getRequestZone() {
        return requestZone;
    }

    /**
     * 请求使用的模式。
     *
     * @return 非 {@code null} 的模式字符串
     */
    public String getRequestPattern() {
        return requestPattern;
    }

    /**
     * 响应展示格式化是否启用。
     *
     * @return 启用返回 {@code true}
     */
    public boolean isResponseEnabled() {
        return responseEnabled;
    }

    /**
     * 响应无时区文本的解析时区。
     *
     * @return 非 {@code null} 的时区
     */
    public ZoneId getResponseSourceZone() {
        return responseSourceZone;
    }

    /**
     * 响应展示时区。
     *
     * @return 非 {@code null} 的时区
     */
    public ZoneId getResponseTargetZone() {
        return responseTargetZone;
    }

    /**
     * 响应展示模式。
     *
     * @return 非 {@code null} 的模式字符串
     */
    public String getResponsePattern() {
        return responsePattern;
    }

    private static boolean hasOffset(String value) {
        return value.length() > 19
                && (value.charAt(19) == '+' || value.charAt(19) == '-');
    }

    private static void validatePattern(String pattern, String key) {
        try {
            DateTimeFormatter.ofPattern(pattern);
        } catch (IllegalArgumentException e) {
            throw new TsdbConfigurationException("invalid " + key + ": " + pattern, e);
        }
    }

    private static ZoneId zone(String value, String key) {
        String text = Texts.trimToNull(value);
        if (text == null) {
            throw new TsdbConfigurationException(key + " must not be blank");
        }
        String normalized = text;
        if (normalized.startsWith("UTC+") || normalized.startsWith("UTC-")) {
            normalized = "GMT" + normalized.substring(3);
        }
        try {
            return ZoneId.of(normalized);
        } catch (RuntimeException e) {
            throw new TsdbConfigurationException("invalid " + key + ": " + value, e);
        }
    }

    private static boolean bool(Map<String, Object> map, String key, String flat, boolean defaultValue) {
        Object nested = value(map, key);
        Object raw = nested != null ? nested : flat;
        if (raw == null) {
            return defaultValue;
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
            return Boolean.parseBoolean(text);
        }
        throw new TsdbConfigurationException("invalid boolean for time." + key + ": " + text);
    }

    private static String string(Map<String, Object> map, String key, String flat, String defaultValue) {
        Object nested = value(map, key);
        Object raw = nested != null ? nested : flat;
        String text = raw == null ? null : Texts.trimToNull(String.valueOf(raw));
        return text == null ? defaultValue : text;
    }

    private static Object value(Map<String, Object> map, String key) {
        return map == null ? null : map.get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map)) {
            return new LinkedHashMap<String, Object>();
        }
        Map<?, ?> source = (Map<?, ?>) value;
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }
}
