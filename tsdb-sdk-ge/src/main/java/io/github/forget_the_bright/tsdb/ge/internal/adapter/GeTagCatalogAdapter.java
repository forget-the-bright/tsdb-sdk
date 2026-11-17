package io.github.forget_the_bright.tsdb.ge.internal.adapter;

import io.github.forget_the_bright.tsdb.common.api.TagCatalog;
import io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException;
import io.github.forget_the_bright.tsdb.common.model.TagInfo;
import io.github.forget_the_bright.tsdb.common.model.TagQuery;

import java.util.ArrayList;
import java.util.List;
import io.github.forget_the_bright.tsdb.ge.GeMapper;

/**
 * GE 位号目录适配器：全量拉取后在内存中模拟 {@code limit/offset}。
 *
 * <p>GE 的标签浏览接口没有显式分页参数，只能靠一次取回的最大条数加内存截断来
 * 模拟分页；名称通配符可下推给厂商，值类型与工程单位过滤没有服务端能力，
 * 传入非空值会显式抛 {@link TsdbUnsupportedException}，不退化为本地过滤。</p>
 *
 * <p><b>非公共 API</b>：门户装配层实现，签名随内部重构变化，宿主应用请勿直接依赖；
 * 稳定契约请使用 {@link io.github.forget_the_bright.tsdb.ge.GeClient}。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class GeTagCatalogAdapter implements TagCatalog {

    /**
     * 单次标签列表查询的硬上限。
     *
     * <p>该常量同时约束“不限量”请求（{@code limit == 0}），避免把整台
     * Historian 的标签名一次性拉进内存。</p>
     */
    private static final int UNLIMITED_TAGS = 100_000;

    private final GeSupport support;

    /**
     * @param support 共享配置与原生客户端
     */
    public GeTagCatalogAdapter(GeSupport support) {
        this.support = support;
    }

    /**
     * 按条件列出位号。
     *
     * <p>只支持分页；名称通配符、值类型、工程单位三类过滤中，后两类没有服务端
     * 能力，传入非空值会抛 {@link TsdbUnsupportedException}。{@code limit == 0}
     * 表示不限制条数。</p>
     *
     * @param query 查询条件；为 {@code null} 时按默认条件处理
     * @return 非 {@code null} 的标签列表，按厂商返回顺序排列
     */
    @Override
    public List<TagInfo> listTags(TagQuery query) {
        TagQuery effective = query == null ? TagQuery.builder().build() : query;
        if (effective.getValueType() != null) {
            throw support.unsupported(new TsdbUnsupportedException(
                    "GE tag catalog cannot filter by value type"), "listTags");
        }
        if (effective.getUnit() != null && !effective.getUnit().trim().isEmpty()) {
            throw support.unsupported(new TsdbUnsupportedException(
                    "GE tag catalog cannot filter by unit"), "listTags");
        }
        int limit = effective.getLimit();
        int offset = effective.getOffset();
        int max = limit == 0 ? UNLIMITED_TAGS : Math.min(UNLIMITED_TAGS, limit + offset);
        List<String> names = support.nativeApi().listTags(max, effective.getNameMask());
        List<TagInfo> result = new ArrayList<TagInfo>();
        for (int i = offset; i < names.size(); i++) {
            if (limit > 0 && result.size() >= limit) {
                break;
            }
            result.add(GeMapper.toTagInfo(names.get(i)));
        }
        return result;
    }

    /**
     * 按名称精确查找单个标签。
     *
     * <p>GE 没有“按名取标签”的独立端点，只能把标签名当作掩码交给浏览接口，
     * 再在结果中做精确字符串匹配；查找上限固定为 1000 条。</p>
     *
     * @param tagName 标签名，不能为空白
     * @return 命中的标签信息；不存在时返回 {@code null}
     */
    @Override
    public TagInfo getTag(String tagName) {
        if (tagName == null || tagName.trim().isEmpty()) {
            throw new IllegalArgumentException("tagName must not be blank");
        }
        List<String> names = support.nativeApi().listTags(1000, tagName);
        for (String name : names) {
            if (tagName.equals(name)) {
                return GeMapper.toTagInfo(name);
            }
        }
        return null;
    }
}