package io.github.forget_the_bright.tsdb.pspace.internal.adapter;

import io.github.forget_the_bright.tsdb.common.api.TagCatalog;
import io.github.forget_the_bright.tsdb.common.exception.TsdbException;
import io.github.forget_the_bright.tsdb.common.model.TagInfo;
import io.github.forget_the_bright.tsdb.common.model.TagQuery;
import io.github.forget_the_bright.tsdb.pspace.internal.PspaceModels;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * pSpace 位号目录适配器。
 *
 * <p>分页列举直接使用 {@code /PointPage} 的服务端分页：按 {@code offset/limit}
 * 计算起始页与页内偏移，只读取需要的页；没有 {@code nameMask} 与本地过滤时
 * 不再把整个目录读进内存。</p>
 *
 * <p>名称检索走 {@code /PointFilter} 的 {@code match} 模式。{@code nameMask}
 * 按公共契约使用 shell 通配符（{@code *} 与 {@code ?}），适配器逐字符转义正则
 * 元字符后整体锚定，例如 {@code TAG1} 变成 {@code ^TAG1$}，不会误匹配
 * {@code TAG10}。{@code /PointFilter} 实测忽略分页参数，因此该路径仍是一次
 * 请求取全部匹配，再本地截断。</p>
 *
 * <p>{@code valueType} 与 {@code unit} 服务端不支持，只能逐页过滤；过滤路径按
 * “匹配条数”累计 {@code offset}，达到 {@code limit} 立即停止，不预读整目录。</p>
 *
 * <p>两条路径解析出的点位都会写入 {@link io.github.forget_the_bright.tsdb.pspace.internal.PspaceTagResolver}
 * 缓存，后续 {@code getTag}/实时值/历史/写入解析可直接命中，少一次 {@code /Point}。</p>
 *
 * <p>目录节点（TagType=NODE）也会返回，并带 {@code isNode=true} 属性，
 * 由调用方决定是否展示。</p>
 *
 * <p><b>非公共 API</b>：门户装配层实现，签名随内部重构变化，宿主应用请勿直接依赖；
 * 稳定契约请使用 {@link io.github.forget_the_bright.tsdb.pspace.PspaceClient}。</p>
 *
 * @author wanghao
 * @since 0.1.2
 */
public final class PspaceTagCatalogAdapter implements TagCatalog {

    private final PspaceSupport support;

    /**
     * @param support 共享依赖
     */
    public PspaceTagCatalogAdapter(PspaceSupport support) {
        this.support = support;
    }

    /**
     * 按条件列出位号。
     *
     * @param query 查询条件；为 {@code null} 时按默认条件（limit=100）处理
     * @return 非 {@code null} 的位号列表
     * @throws TsdbException 查询失败，或服务端页数元数据缺失且当前页已满时
     */
    @Override
    public List<TagInfo> listTags(TagQuery query) {
        support.ensureOpen();
        TagQuery effective = query == null ? TagQuery.builder().build() : query;
        try {
            if (hasNameMask(effective)) {
                return loadByMask(effective);
            }
            return scanPages(effective, hasLocalFilter(effective));
        } catch (TsdbException e) {
            throw support.context(e, "listTags");
        }
    }

    /**
     * 按名称精确查找单个位号。
     *
     * <p>走 {@code /PointFilter} 的 {@code match} 模式（位号名先做正则转义），
     * 再以字符串全等作为最终判定，避免正则元字符造成误匹配。</p>
     *
     * @param tagName 位号长名，不能为空白
     * @return 位号元数据；不存在时返回 {@code null}
     */
    @Override
    public TagInfo getTag(String tagName) {
        support.ensureOpen();
        if (tagName == null || tagName.trim().isEmpty()) {
            throw new IllegalArgumentException("tagName must not be blank");
        }
        try {
            PspaceModels.Point point = support.resolver().resolve(tagName);
            return point == null ? null : support.mapper().toTagInfo(point);
        } catch (TsdbException e) {
            throw support.context(e, "getTag");
        }
    }

    private List<TagInfo> loadByMask(TagQuery query) {
        String regex = globToRegex(query.getNameMask().trim());
        List<PspaceModels.Point> points = support.nativeApi().withAutoRefresh(
                () -> support.nativeApi().filterPoints("match", "LongName", regex, true),
                List::isEmpty);
        cache(points);
        List<TagInfo> tags = new ArrayList<TagInfo>(points.size());
        for (PspaceModels.Point point : points) {
            TagInfo tag = support.mapper().toTagInfo(point);
            if (matchesFilter(tag, query)) {
                tags.add(tag);
            }
        }
        return slice(tags, query.getOffset(), query.getLimit());
    }

    /**
     * 逐页扫描 {@code /PointPage}，按需过滤后再做 offset/limit 截断。
     *
     * <p>{@code offset} 始终按“已接受的位号数”累计：{@code filtered=false} 时没有
     * 本地过滤，可以直接从 {@code offset} 所在的页开始读（只请求需要的页）；
     * {@code filtered=true} 时只统计通过 {@code valueType/unit} 的行，必须从第 1 页
     * 扫起，被过滤掉的行不占 offset。</p>
     *
     * <p>只有第一页且 {@code offset == 0} 的首屏启用 {@code withAutoRefresh}：
     * pSpace 缓存未热时首页可能为空，刷新后重试一次；后续页为空属于正常结束，
     * 不重复刷新。翻页结束由 {@link PspaceSupport#hasMorePages} 判定。</p>
     *
     * @param query    查询条件，非 {@code null}
     * @param filtered 是否按 {@code valueType/unit} 本地过滤
     * @return 非 {@code null} 的位号列表
     */
    private List<TagInfo> scanPages(TagQuery query, boolean filtered) {
        int pageSize = support.config().getPageSize();
        int offset = Math.max(0, query.getOffset());
        int limit = query.getLimit();
        int accepted = filtered ? 0 : offset - offset % pageSize;
        int pageNumber = filtered ? 1 : offset / pageSize + 1;
        List<TagInfo> result = new ArrayList<TagInfo>();
        while (true) {
            final int requestedPage = pageNumber;
            boolean firstScreen = pageNumber == 1 && offset == 0;
            PspaceModels.Page page = firstScreen
                    ? support.nativeApi().withAutoRefresh(
                            () -> support.nativeApi().pointPage(pageSize, requestedPage),
                            p -> p.values.isEmpty())
                    : support.nativeApi().pointPage(pageSize, pageNumber);
            List<PspaceModels.Point> points = support.nativeApi().parsePoints(page.values);
            cache(points);
            for (PspaceModels.Point point : points) {
                TagInfo tag = support.mapper().toTagInfo(point);
                if (filtered && !matchesFilter(tag, query)) {
                    continue;
                }
                if (accepted < offset) {
                    accepted++;
                    continue;
                }
                result.add(tag);
                if (limit > 0 && result.size() >= limit) {
                    return result;
                }
            }
            if (!support.hasMorePages("/PointPage", page, pageSize, pageNumber)) {
                return result;
            }
            pageNumber++;
        }
    }

    private void cache(List<PspaceModels.Point> points) {
        for (PspaceModels.Point point : points) {
            support.resolver().cache(point);
        }
    }

    private static boolean hasNameMask(TagQuery query) {
        String mask = query.getNameMask();
        return mask != null && !mask.trim().isEmpty();
    }

    private static boolean hasLocalFilter(TagQuery query) {
        return query.getValueType() != null || query.getUnit() != null;
    }

    private static boolean matchesFilter(TagInfo tag, TagQuery query) {
        if (query.getValueType() != null && tag.getValueType() != query.getValueType()) {
            return false;
        }
        String unit = query.getUnit();
        return unit == null || Objects.equals(unit, tag.getUnit());
    }

    /**
     * 把公共契约的 shell 通配符（{@code *}、{@code ?}）转成锚定的 pSpace 正则。
     *
     * @param mask 名称掩码，非空白
     * @return 形如 {@code ^TAG1$} 或 {@code ^压力.*$} 的正则
     */
    static String globToRegex(String mask) {
        StringBuilder sb = new StringBuilder(mask.length() + 8).append('^');
        for (int i = 0; i < mask.length(); i++) {
            char c = mask.charAt(i);
            if (c == '*') {
                sb.append(".*");
            } else if (c == '?') {
                sb.append('.');
            } else {
                if ("\\.^$|()[]{}+".indexOf(c) >= 0) {
                    sb.append('\\');
                }
                sb.append(c);
            }
        }
        return sb.append('$').toString();
    }

    private static List<TagInfo> slice(List<TagInfo> tags, int offset, int limit) {
        int from = Math.min(Math.max(0, offset), tags.size());
        int to = limit > 0 ? Math.min(tags.size(), from + limit) : tags.size();
        return new ArrayList<TagInfo>(tags.subList(from, to));
    }
}