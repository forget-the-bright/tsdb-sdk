package io.github.forget_the_bright.tsdb.pspace.internal;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.github.forget_the_bright.tsdb.common.internal.json.JsonText;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * pSpace 原始响应的字段解析器：把厂商 JSON 转成 {@link PspaceModels} 值对象。
 *
 * <p>只做字段提取与结构兼容，不做业务过滤、排序或类型归一化。调用方传入的
 * {@code text} 应已经过 {@link PspaceHttp} 解包（即 envelope 的 {@code data} 或裸数据），
 * 本类不再处理 {@code code/mesg}。</p>
 *
 * <p>兼容规则（7.1 实测）：</p>
 * <ul>
 *   <li>字段名大小写不敏感，同时接受常见别名（如 {@code TagId/tagId/pid}）；</li>
 *   <li>列表响应既可能是裸数组，也可能是带 {@code values} 的对象，还可能把数组放在
 *       {@code page.values} 下；</li>
 *   <li>历史行的 {@code items} 既可能是单个对象，也可能是数组；数组元素既可能是对象，
 *       也可能是按 {@code [pv,tm,qy]} 排列的位置数组；</li>
 *   <li>缺字段时保留 {@code null}（数值 id 等核心字段归一化为 0），空值不会被丢弃；
 *       只有无法解析时间戳的历史值会被跳过。</li>
 * </ul>
 *
 * <p><b>非公共 API</b>：随内部重构变化，宿主应用请勿直接依赖。</p>
 *
 * @author wanghao
 * @since 0.1.2
 */
final class PspaceResponseParser {

    private final PspaceMapper mapper;

    /**
     * @param mapper 映射器，仅用于时间戳解析，不能为 {@code null}
     */
    PspaceResponseParser(PspaceMapper mapper) {
        if (mapper == null) {
            throw new IllegalArgumentException("mapper is required");
        }
        this.mapper = mapper;
    }

    /**
     * 解析已解包的点位列表文本。
     *
     * @param text 数据文本，可为 {@code null}
     * @param path 端点路径，仅用于异常消息
     * @return 非 {@code null} 的点位列表
     */
    List<PspaceModels.Point> parsePoints(String text, String path) {
        return parsePoints(listValues(text, path));
    }

    /**
     * 把分页/列表响应中的原始元素解析成点位。
     *
     * @param values 原始元素数组，可为 {@code null}
     * @return 非 {@code null} 的点位列表
     */
    List<PspaceModels.Point> parsePoints(JSONArray values) {
        List<PspaceModels.Point> result = new ArrayList<PspaceModels.Point>();
        if (values == null) {
            return result;
        }
        for (int i = 0; i < values.size(); i++) {
            JSONObject item = values.getJSONObject(i);
            if (item != null) {
                result.add(point(item));
            }
        }
        return result;
    }

    /**
     * 解析已解包的实时值响应。
     *
     * <p>空值行（{@code pv} 缺失）仍保留为 {@link PspaceModels.RealValue}，由映射层决定
     * 是否产出空样本；只有不是对象的数组元素会被跳过。</p>
     *
     * @param root 已解包的 JSON 对象，不能为 {@code null}
     * @return 非 {@code null} 的实时值列表
     */
    List<PspaceModels.RealValue> parseRealValues(JSONObject root) {
        JSONArray values = values(root);
        List<PspaceModels.RealValue> result = new ArrayList<PspaceModels.RealValue>(values.size());
        for (int i = 0; i < values.size(); i++) {
            JSONObject item = values.getJSONObject(i);
            if (item == null) {
                continue;
            }
            result.add(new PspaceModels.RealValue(
                    longOf(item, "pid", "tagid", "TagId"),
                    firstOf(item, "pv", "value"),
                    mapper.parseTimestamp(firstOf(item, "tm", "time")),
                    integerOf(item, "qy", "quality")));
        }
        return result;
    }

    /**
     * 解析分页响应。
     *
     * @param root 已解包的 JSON 对象，不能为 {@code null}
     * @return 非 {@code null} 的分页结果
     */
    PspaceModels.Page parsePage(JSONObject root) {
        JSONObject page = JsonText.object(root.get("page"));
        Long total = JsonText.longValue(root, "total");
        if (page == null) {
            page = root;
        }
        Integer pageCount = integerOf(page, "count");
        return new PspaceModels.Page(
                total == null ? 0L : total.longValue(),
                pageCount == null ? 0 : pageCount.intValue(),
                values(page));
    }

    /**
     * 解析已解包的历史行文本。
     *
     * @param text 数据文本，可为 {@code null}
     * @param path 端点路径，仅用于异常消息
     * @return 非 {@code null} 的历史行列表
     */
    List<PspaceModels.HistRow> parseHistRows(String text, String path) {
        return parseHistRows(listValues(text, path));
    }

    /**
     * 把分页/列表响应中的原始元素解析成历史行。
     *
     * <p>元素可以是 {@code {pid,count,items}} 行，也可以直接是
     * {@code {pid,pv,tm,qy}} 单值；{@code items} 既可能是对象，也可能是数组
     * （多行时），数组元素还可能是按 {@code [pv,tm,qy]} 排列的位置数组。</p>
     *
     * @param values 原始元素数组，可为 {@code null}
     * @return 非 {@code null} 的历史行列表
     */
    List<PspaceModels.HistRow> parseHistRows(JSONArray values) {
        List<PspaceModels.HistRow> result = new ArrayList<PspaceModels.HistRow>();
        if (values == null) {
            return result;
        }
        for (int i = 0; i < values.size(); i++) {
            JSONObject item = values.getJSONObject(i);
            if (item == null) {
                continue;
            }
            long id = longOf(item, "pid", "tagid", "TagId");
            List<PspaceModels.HistValue> points = new ArrayList<PspaceModels.HistValue>();
            Object rawItems = firstOf(item, "items", "values");
            JSONArray items = fieldArray(item, "items", "values");
            if (rawItems instanceof JSONObject) {
                addHistValue(points, (JSONObject) rawItems);
            } else if (items == null) {
                addHistValue(points, item);
            } else {
                for (int j = 0; j < items.size(); j++) {
                    Object element = items.get(j);
                    if (element instanceof JSONObject) {
                        addHistValue(points, (JSONObject) element);
                    } else if (element instanceof JSONArray) {
                        addHistArrayValue(points, (JSONArray) element);
                    }
                }
            }
            result.add(new PspaceModels.HistRow(id, points));
        }
        return result;
    }

    private PspaceModels.Point point(JSONObject item) {
        String node = field(item, "IsNode", "isNode");
        return new PspaceModels.Point(
                longOf(item, "TagId", "tagId", "pid"),
                field(item, "Name", "name"),
                field(item, "LongName", "longName"),
                field(item, "Desc", "desc"),
                field(item, "EngUnit", "engUnit", "unit"),
                longOf(item, "ParentId", "parentId"),
                (int) longOf(item, "DataType", "dataType"),
                (int) longOf(item, "TagType", "tagType"),
                "1".equals(node) || "true".equalsIgnoreCase(node),
                boolOf(item, "His_IsSave", "hisIsSave"));
    }

    private void addHistValue(List<PspaceModels.HistValue> points, JSONObject item) {
        Object tm = firstOf(item, "tm", "time");
        Instant timestamp = mapper.parseTimestamp(tm);
        if (timestamp == null) {
            return;
        }
        points.add(new PspaceModels.HistValue(timestamp,
                firstOf(item, "pv", "value"), integerOf(item, "qy", "quality")));
    }

    private void addHistArrayValue(List<PspaceModels.HistValue> points, JSONArray item) {
        Object tm = item.size() > 1 ? item.get(1) : null;
        Instant timestamp = mapper.parseTimestamp(tm);
        if (timestamp == null) {
            return;
        }
        Integer quality = null;
        Object rawQuality = item.size() > 2 ? item.get(2) : null;
        if (rawQuality instanceof Number) {
            quality = ((Number) rawQuality).intValue();
        }
        points.add(new PspaceModels.HistValue(timestamp,
                item.isEmpty() ? null : item.get(0), quality));
    }

    /**
     * 解析列表型响应：7.1 既可能返回裸数组，也可能返回带 {@code values} 的对象。
     *
     * @param text 已解开 envelope 的数据文本
     * @param path 路径（仅用于异常消息）
     * @return 非 {@code null} 的数组
     */
    private static JSONArray listValues(String text, String path) {
        if (text == null || text.trim().isEmpty()) {
            return new JSONArray();
        }
        if (text.trim().startsWith("[")) {
            return PspaceHttp.asArray(text);
        }
        return values(PspaceHttp.asObject(text, "POST", path));
    }

    private static JSONArray values(JSONObject root) {
        JSONArray direct = fieldArray(root, "values");
        if (direct != null) {
            return direct;
        }
        JSONObject page = JsonText.object(root.get("page"));
        if (page != null) {
            JSONArray nested = fieldArray(page, "values");
            if (nested != null) {
                return nested;
            }
        }
        return new JSONArray();
    }

    private static JSONArray fieldArray(JSONObject object, String... keys) {
        if (object == null) {
            return null;
        }
        for (String key : keys) {
            Object value = fieldObject(object, key);
            if (value instanceof JSONArray) {
                return (JSONArray) value;
            }
            if (value instanceof List) {
                JSONArray array = new JSONArray();
                array.addAll((List<?>) value);
                return array;
            }
        }
        return null;
    }

    private static Object firstOf(JSONObject object, String... keys) {
        for (String key : keys) {
            Object value = fieldObject(object, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Object fieldObject(JSONObject object, String key) {
        if (object == null) {
            return null;
        }
        if (object.containsKey(key)) {
            return object.get(key);
        }
        for (String candidate : object.keySet()) {
            if (candidate.equalsIgnoreCase(key)) {
                return object.get(candidate);
            }
        }
        return null;
    }

    private static String field(JSONObject object, String... keys) {
        Object value = firstOf(object, keys);
        return value == null ? null : String.valueOf(value);
    }

    private static long longOf(JSONObject object, String... keys) {
        Object value = firstOf(object, keys);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static Integer integerOf(JSONObject object, String... keys) {
        Object value = firstOf(object, keys);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Boolean boolOf(JSONObject object, String... keys) {
        Object value = firstOf(object, keys);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if ("1".equals(text) || "true".equalsIgnoreCase(text)) {
            return Boolean.TRUE;
        }
        if ("0".equals(text) || "false".equalsIgnoreCase(text)) {
            return Boolean.FALSE;
        }
        return null;
    }
}