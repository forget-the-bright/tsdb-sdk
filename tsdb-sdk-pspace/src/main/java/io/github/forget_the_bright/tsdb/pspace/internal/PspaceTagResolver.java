package io.github.forget_the_bright.tsdb.pspace.internal;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * pSpace 位号名（LongName）与点位 id（TagId）的双向解析缓存。
 *
 * <p>pSpace 的历史/实时接口主要按 {@code tagids} 或 {@code tagnames} 工作，
 * 而目录与结果回填需要 LongName。本类只维护
 * “LongName → 点位”一侧的进程内缓存：</p>
 * <ul>
 *   <li>按名解析：先查缓存，未命中用 {@code /Point?tagnames=...}，仍未命中再用
 *       {@code /PointFilter} 的 {@code match} 模式并做字符串全等校验
 *       （pSpace 把 filtervalue 当正则，因此位号名先转义）；</li>
 *   <li>所有查询都走原生层的“缓存未热自动刷新”安全网，且只缓存命中项，
 *       不缓存未命中，避免新建点位后长期看不到。</li>
 * </ul>
 *
 * <p>缓存线程安全（{@link ConcurrentHashMap}），实例可被多个适配器共享。</p>
 *
 * <p><b>非公共 API</b>：随内部重构变化，宿主应用请勿直接依赖。</p>
 *
 * @author wanghao
 * @since 0.1.2
 */
public final class PspaceTagResolver {

    private final PspaceNativeApi nativeApi;

    /** LongName → 点位。 */
    private final Map<String, PspaceModels.Point> byName =
            new ConcurrentHashMap<String, PspaceModels.Point>();

    /**
     * @param nativeApi 原生 API，不能为 {@code null}
     */
    public PspaceTagResolver(PspaceNativeApi nativeApi) {
        if (nativeApi == null) {
            throw new IllegalArgumentException("nativeApi must not be null");
        }
        this.nativeApi = nativeApi;
    }

    /**
     * 按 LongName 解析点位。
     *
     * @param tagName 位号长名，不能为空白
     * @return 点位；未找到返回 {@code null}
     */
    public PspaceModels.Point resolve(String tagName) {
        String name = trim(tagName);
        if (name == null) {
            throw new IllegalArgumentException("tagName must not be blank");
        }
        PspaceModels.Point cached = byName.get(name);
        if (cached != null) {
            return cached;
        }
        List<PspaceModels.Point> points = nativeApi.withAutoRefresh(
                () -> nativeApi.pointsByNames(java.util.Collections.singletonList(name)),
                List::isEmpty);
        for (PspaceModels.Point point : points) {
            cache(point);
            if (name.equals(point.longName)) {
                return point;
            }
        }
        List<PspaceModels.Point> matched = nativeApi.withAutoRefresh(
                () -> nativeApi.filterPoints("match", "LongName", escapeRegex(name), true),
                List::isEmpty);
        for (PspaceModels.Point point : matched) {
            cache(point);
            if (name.equals(point.longName)) {
                return point;
            }
        }
        return null;
    }

    /**
     * 批量按 LongName 解析点位（一次 {@code /Point?tagnames=} 请求）。
     *
     * <p>未返回的位号不出现在结果中，由调用方决定是“空序列”还是报错。</p>
     *
     * @param tagNames 位号长名集合，可为 {@code null}
     * @return LongName → 点位 的映射，非 {@code null}
     */
    public Map<String, PspaceModels.Point> resolveAll(Collection<String> tagNames) {
        Map<String, PspaceModels.Point> result = new LinkedHashMap<String, PspaceModels.Point>();
        if (tagNames == null || tagNames.isEmpty()) {
            return result;
        }
        LinkedHashSet<String> missing = new LinkedHashSet<String>();
        for (String tagName : tagNames) {
            String name = trim(tagName);
            if (name == null) {
                continue;
            }
            PspaceModels.Point cached = byName.get(name);
            if (cached != null) {
                result.put(name, cached);
            } else {
                missing.add(name);
            }
        }
        if (!missing.isEmpty()) {
            List<PspaceModels.Point> points = nativeApi.withAutoRefresh(
                    () -> nativeApi.pointsByNames(missing), List::isEmpty);
            Map<String, PspaceModels.Point> found = new LinkedHashMap<String, PspaceModels.Point>();
            for (PspaceModels.Point point : points) {
                cache(point);
                if (point.longName != null) {
                    found.put(point.longName, point);
                }
            }
            for (String name : missing) {
                PspaceModels.Point point = found.get(name);
                if (point != null) {
                    result.put(name, point);
                }
            }
        }
        return result;
    }

    /**
     * 按 LongName 解析点位 id。
     *
     * @param tagName 位号长名
     * @return 点位 id；未找到返回 {@code null}
     */
    public Long idOf(String tagName) {
        PspaceModels.Point point = resolve(tagName);
        return point == null ? null : Long.valueOf(point.id);
    }

    /**
     * 把点位放入缓存；没有 LongName 的点位无法按名命中，直接忽略。
     *
     * @param point 点位，可为 {@code null}
     */
    public void cache(PspaceModels.Point point) {
        if (point == null || point.longName == null) {
            return;
        }
        byName.put(point.longName, point);
    }

    /**
     * 把位号名转义成 pSpace 正则的字面量。
     *
     * @param value 原值，不能为 {@code null}
     * @return 转义后的字符串
     */
    public static String escapeRegex(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ("\\.*+?()[]{}^$|".indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }
}