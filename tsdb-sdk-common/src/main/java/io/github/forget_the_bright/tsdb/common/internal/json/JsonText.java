package io.github.forget_the_bright.tsdb.common.internal.json;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.JSONObject;
import io.github.forget_the_bright.tsdb.common.exception.TsdbDataException;

import java.util.Collection;
import java.util.Map;
import io.github.forget_the_bright.tsdb.common.internal.Texts;

/**
 * fastjson 树模型的共享读写工具。
 *
 * <p>驱动层只解析 JSON 树（{@link JSONObject}/{@link JSONArray}/标量），不做
 * 类型名反序列化；因此这里的入口不提供 {@code parseObject(text, Class)}，以免
 * 绕过 {@link Json} 设置的 safeMode。</p>
 *
 * <p>字段读取语义固定如下：字段缺失或为 JSON {@code null} 时返回
 * {@code null}；字段值是对象/数组时返回空串；其余标量用
 * {@code String.valueOf} 转文本。</p>
 *
 * <p>本类无共享可变状态，线程安全。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class JsonText {

    static {
        Json.init();
    }

    private JsonText() {
    }

    /**
     * 解析任意 JSON 文本。
     *
     * @param text JSON 文本，可为 {@code null}
     * @return {@link JSONObject}、{@link JSONArray} 或标量；输入为 {@code null} 时返回 {@code null}
     * @throws TsdbDataException 文本不是合法 JSON 时
     */
    public static Object parse(String text) {
        if (text == null) {
            return null;
        }
        try {
            return JSON.parse(text);
        } catch (JSONException e) {
            throw new TsdbDataException("cannot parse JSON: "
                    + Texts.abbreviate(text), e);
        }
    }

    /**
     * 解析 JSON 对象。
     *
     * @param text JSON 对象文本，可为 {@code null}
     * @return 解析结果；输入为 {@code null} 时返回 {@code null}
     * @throws TsdbDataException 文本非法或根节点不是对象时
     */
    public static JSONObject parseObject(String text) {
        Object value = parse(text);
        return value == null ? null : object(value);
    }

    /**
     * 解析 JSON 数组。
     *
     * @param text JSON 数组文本，可为 {@code null}
     * @return 解析结果；输入为 {@code null} 时返回 {@code null}
     * @throws TsdbDataException 文本非法或根节点不是数组时
     */
    public static JSONArray parseArray(String text) {
        Object value = parse(text);
        return value == null ? null : array(value);
    }

    /**
     * 把值收敛为 {@link JSONObject}。
     *
     * @param value {@link JSONObject}、{@link Map} 或 JSON 对象文本
     * @return 非 {@code null} 的对象
     * @throws TsdbDataException 值为 {@code null} 或不是对象时
     */
    @SuppressWarnings("unchecked")
    public static JSONObject object(Object value) {
        if (value instanceof JSONObject) {
            return (JSONObject) value;
        }
        if (value instanceof Map) {
            JSONObject object = new JSONObject();
            object.putAll((Map<String, Object>) value);
            return object;
        }
        if (value instanceof String) {
            return parseObject((String) value);
        }
        throw new TsdbDataException("JSON object expected but was "
                + (value == null ? "null" : value.getClass().getName()));
    }

    /**
     * 把值收敛为 {@link JSONArray}。
     *
     * @param value {@link JSONArray}、{@link Collection} 或 JSON 数组文本
     * @return 非 {@code null} 的数组
     * @throws TsdbDataException 值为 {@code null} 或不是数组时
     */
    public static JSONArray array(Object value) {
        if (value instanceof JSONArray) {
            return (JSONArray) value;
        }
        if (value instanceof Collection) {
            JSONArray array = new JSONArray();
            array.addAll((Collection<?>) value);
            return array;
        }
        if (value instanceof String) {
            return parseArray((String) value);
        }
        throw new TsdbDataException("JSON array expected but was "
                + (value == null ? "null" : value.getClass().getName()));
    }

    /**
     * 按字段名读取文本，区分大小写。
     *
     * @param parent 对象或映射，可为 {@code null}
     * @param field  字段名，不能为 {@code null}
     * @return 字段文本；对象为空、字段缺失或值为 JSON {@code null} 时返回 {@code null}
     * @throws TsdbDataException {@code parent} 不是对象/映射时
     */
    public static String text(Object parent, String field) {
        Object value = fieldValue(parent, field, false);
        return asText(value);
    }

    /**
     * 按字段名读取文本，忽略大小写。
     *
     * <p>优先精确匹配；找不到时遍历键做 {@link String#equalsIgnoreCase(String)}。</p>
     *
     * @param parent 对象或映射，可为 {@code null}
     * @param field  字段名，不能为 {@code null}
     * @return 字段文本；对象为空、字段缺失或值为 JSON {@code null} 时返回 {@code null}
     * @throws TsdbDataException {@code parent} 不是对象/映射时
     */
    public static String textIgnoreCase(Object parent, String field) {
        Object value = fieldValue(parent, field, true);
        return asText(value);
    }

    /**
     * 按字段名读取原始值，区分大小写。
     *
     * @param parent 对象或映射，可为 {@code null}
     * @param field  字段名，不能为 {@code null}
     * @return 原始值；对象为空或字段缺失时返回 {@code null}
     */
    public static Object value(Object parent, String field) {
        return fieldValue(parent, field, false);
    }

    /**
     * 按字段名读取原始值，忽略大小写。
     *
     * @param parent 对象或映射，可为 {@code null}
     * @param field  字段名，不能为 {@code null}
     * @return 原始值；对象为空或字段缺失时返回 {@code null}
     */
    public static Object valueIgnoreCase(Object parent, String field) {
        return fieldValue(parent, field, true);
    }

    /**
     * 按字段名读取长整型。
     *
     * @param parent 对象或映射，可为 {@code null}
     * @param field  字段名，不能为 {@code null}
     * @return 数值；字段缺失、为 JSON {@code null} 或无法解析时返回 {@code null}
     */
    public static Long longValue(Object parent, String field) {
        Object value = fieldValue(parent, field, false);
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 按字段名读取整型。
     *
     * @param parent 对象或映射，可为 {@code null}
     * @param field  字段名，不能为 {@code null}
     * @return 数值；字段缺失、为 JSON {@code null} 或无法解析时返回 {@code null}
     */
    public static Integer integer(Object parent, String field) {
        Long value = longValue(parent, field);
        return value == null ? null : Integer.valueOf(value.intValue());
    }

    /**
     * 把对象序列化为 JSON 文本。
     *
     * <p>{@link String} 会被当作普通字符串并加引号；已经序列化好的 JSON 文本
     * 请由调用方直接使用，不要经过本方法。</p>
     *
     * @param value 待序列化对象，可为 {@code null}
     * @return JSON 文本；{@code null} 返回 {@code "null"}
     * @throws TsdbDataException 序列化失败时
     */
    public static String toJson(Object value) {
        try {
            return JSON.toJSONString(value);
        } catch (JSONException e) {
            throw new TsdbDataException("cannot encode JSON", e);
        }
    }

    private static Object fieldValue(Object parent, String field, boolean ignoreCase) {
        if (parent == null) {
            return null;
        }
        if (parent instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) parent;
            Object direct = map.get(field);
            if (direct != null || map.containsKey(field) || !ignoreCase) {
                return direct;
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String
                        && ((String) entry.getKey()).equalsIgnoreCase(field)) {
                    return entry.getValue();
                }
            }
            return null;
        }
        throw new TsdbDataException("JSON object expected but was "
                + parent.getClass().getName());
    }

    private static String asText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map || value instanceof Collection) {
            return "";
        }
        return String.valueOf(value);
    }
}
