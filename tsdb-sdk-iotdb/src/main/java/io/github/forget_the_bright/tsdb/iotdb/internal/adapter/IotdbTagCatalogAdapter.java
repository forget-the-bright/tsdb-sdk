package io.github.forget_the_bright.tsdb.iotdb.internal.adapter;

import io.github.forget_the_bright.tsdb.common.api.TagCatalog;
import io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException;
import io.github.forget_the_bright.tsdb.common.model.TagInfo;
import io.github.forget_the_bright.tsdb.common.model.TagQuery;
import io.github.forget_the_bright.tsdb.iotdb.internal.IotdbModels;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.IotdbTagPath;

import java.util.ArrayList;
import java.util.List;
import io.github.forget_the_bright.tsdb.iotdb.IotdbMapper;

/**
 * IoTDB 位号目录适配器：掩码下推、值类型过滤与内存分页。
 *
 * <p>REST v2 的 {@code SHOW TIMESERIES} 不支持 offset/limit，因此分页与按值类型
 * 过滤都在客户端完成；工程单位过滤没有对应的元数据，直接拒绝。</p>
 *
 * <p><b>非公共 API</b>：门户装配层实现，签名随内部重构变化，宿主应用请勿直接依赖；
 * 稳定契约请使用 {@link io.github.forget_the_bright.tsdb.iotdb.IotdbClient}。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class IotdbTagCatalogAdapter implements TagCatalog {

    private final IotdbSupport support;

    /**
     * @param support 共享依赖
     */
    public IotdbTagCatalogAdapter(IotdbSupport support) {
        this.support = support;
    }

    /**
     * 列出位号并完成值类型过滤与内存分页。
     *
     * @param query 查询条件，可为 {@code null}
     * @return 非 {@code null} 的位号列表；无匹配时为空列表
     * @throws TsdbUnsupportedException 当 {@code query.getUnit()} 非空时
     */
    @Override
    public List<TagInfo> listTags(TagQuery query) {
        TagQuery effective = query == null ? TagQuery.builder().build() : query;
        if (effective.getUnit() != null && !effective.getUnit().trim().isEmpty()) {
            throw support.unsupported(new TsdbUnsupportedException(
                    "IoTDB tag catalog cannot filter by unit"), "listTags");
        }
        String prefix = support.tagResolver().resolvePrefix(effective.getNameMask());
        List<IotdbModels.Tag> tags = support.queryService() == null
                ? support.api().listTags(prefix)
                : support.queryService().listTags(prefix);
        List<TagInfo> result = new ArrayList<TagInfo>();
        int offset = effective.getOffset();
        int limit = effective.getLimit();
        int seen = 0;
        for (IotdbModels.Tag tag : tags) {
            TagInfo info = IotdbMapper.toTagInfo(tag);
            if (effective.getValueType() != null && effective.getValueType() != info.getValueType()) {
                continue;
            }
            if (seen++ < offset) {
                continue;
            }
            if (limit > 0 && result.size() >= limit) {
                break;
            }
            result.add(info);
        }
        return result;
    }

    /**
     * 按精确名称查找单个位号。
     *
     * @param tagName 位号全路径，不能为空白
     * @return 匹配到的位号描述；不存在时为 {@code null}
     * @throws IllegalArgumentException 当 {@code tagName} 为 {@code null} 或空白时
     */
    @Override
    public TagInfo getTag(String tagName) {
        if (tagName == null || tagName.trim().isEmpty()) {
            throw new IllegalArgumentException("tagName must not be blank");
        }
        IotdbTagPath path = support.tagResolver().resolvePath(tagName);
        String device = path.getDevicePath();
        List<IotdbModels.Tag> tags = support.queryService() == null
                ? support.api().listTags(device)
                : support.queryService().listTags(device);
        for (IotdbModels.Tag tag : tags) {
            if (path.getAbsolutePath().equals(tag.getName())) {
                return IotdbMapper.toTagInfo(tag);
            }
        }
        return null;
    }
}