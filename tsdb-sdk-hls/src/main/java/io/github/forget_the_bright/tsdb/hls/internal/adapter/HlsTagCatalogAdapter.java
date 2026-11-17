package io.github.forget_the_bright.tsdb.hls.internal.adapter;

import io.github.forget_the_bright.tsdb.common.api.TagCatalog;
import io.github.forget_the_bright.tsdb.common.exception.TsdbException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException;
import io.github.forget_the_bright.tsdb.common.model.TagInfo;
import io.github.forget_the_bright.tsdb.common.model.TagQuery;
import io.github.forget_the_bright.tsdb.hls.internal.HlsModels;

import java.util.ArrayList;
import java.util.List;

/**
 * HLS 位号目录适配器：全量拉取后在内存中模拟分页。
 *
 * <p>HLS 的位号接口不支持服务端分页、按名称模糊匹配、值类型或单位过滤；
 * 后三类传入非空值会显式抛 {@link TsdbUnsupportedException}，不退化为本地过滤。</p>
 *
 * <p><b>非公共 API</b>：门户装配层实现，签名随内部重构变化，宿主应用请勿直接依赖；
 * 稳定契约请使用 {@link io.github.forget_the_bright.tsdb.hls.HlsClient}。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class HlsTagCatalogAdapter implements TagCatalog {

    private final HlsSupport support;

    /**
     * @param support 共享依赖
     */
    public HlsTagCatalogAdapter(HlsSupport support) {
        this.support = support;
    }

    /**
     * 按条件列出位号。
     *
     * @param query 查询条件；为 {@code null} 时等价于默认查询
     * @return 非 {@code null} 的位号列表，元素顺序与厂商返回顺序一致
     */
    @Override
    public List<TagInfo> listTags(TagQuery query) {
        support.ensureOpen();
        TagQuery effective = query == null ? TagQuery.builder().build() : query;
        if (effective.getNameMask() != null && !effective.getNameMask().trim().isEmpty()) {
            throw support.unsupported("HLS does not support server-side tag search", "listTags", null);
        }
        if (effective.getValueType() != null || effective.getUnit() != null) {
            throw support.unsupported("HLS does not support value-type or unit filters", "listTags", null);
        }
        List<TagInfo> all;
        try {
            all = new ArrayList<TagInfo>();
            for (HlsModels.Tag tag : support.nativeApi().listTags()) {
                all.add(support.mapper().toTagInfo(tag));
            }
        } catch (TsdbException e) {
            throw support.context(e, "listTags");
        }
        int from = Math.min(effective.getOffset(), all.size());
        int to = effective.getLimit() == 0
                ? all.size()
                : Math.min(from + effective.getLimit(), all.size());
        return new ArrayList<TagInfo>(all.subList(from, to));
    }

    /**
     * 按名称精确查找单个位号。
     *
     * @param tagName 位号名，不能为 {@code null} 或空白
     * @return 找到时返回对应的 {@link TagInfo}；不存在时返回 {@code null}
     */
    @Override
    public TagInfo getTag(String tagName) {
        support.ensureOpen();
        if (tagName == null || tagName.trim().isEmpty()) {
            throw new IllegalArgumentException("tagName must not be blank");
        }
        try {
            for (HlsModels.Tag tag : support.nativeApi().listTags()) {
                if (tagName.equals(tag.name)) {
                    return support.mapper().toTagInfo(tag);
                }
            }
            return null;
        } catch (TsdbException e) {
            throw support.context(e, "getTag");
        }
    }
}