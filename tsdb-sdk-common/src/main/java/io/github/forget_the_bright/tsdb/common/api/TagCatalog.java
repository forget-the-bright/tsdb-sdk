package io.github.forget_the_bright.tsdb.common.api;

import io.github.forget_the_bright.tsdb.common.model.TagInfo;
import io.github.forget_the_bright.tsdb.common.model.TagQuery;

import java.util.List;

/**
 * 位号目录（元数据）操作。
 *
 * <p>在 SDK 中承担“点位发现与元数据查询”的角色：列出位号、按条件筛选位号、
 * 按名称精确查找单个位号。它只读元数据，不涉及采样点数据本身，
 * 因此通常用于系统初始化时构建点位清单、界面上的点位树、以及给曲线标注单位。</p>
 *
 * <p>能力要求：基础分页列举属于基线能力（所有驱动都必须支持）；
 * 而按名称通配符检索需要 {@code TAG_SEARCH} 能力，
 * 元数据增删改需要 {@code TAG_CRUD} 能力。驱动对不支持的过滤条件
 * 必须抛 {@code TSDB-UNSUPPORTED}，不能忽略过滤条件后返回全量数据
 * （那会让调用方误以为过滤生效）。</p>
 *
 * <p>实现约束：</p>
 * <ul>
 *   <li>不得返回 {@code null}，无匹配时返回空列表；</li>
 *   <li>实现必须线程安全，可并发调用；</li>
 *   <li>返回的 {@link TagInfo} 不可变，可安全跨线程传递。</li>
 * </ul>
 *
 * @author wanghao
 * @since 0.1.0
 */
public interface TagCatalog {

    /**
     * 按查询条件列出位号。
     *
     * <p>分页由 {@link TagQuery#getOffset()} 与 {@link TagQuery#getLimit()}
     * 控制；名称过滤由 {@link TagQuery#getNameMask()} 的通配符表达。
     * 返回结果不保证任何特定排序（不同驱动可能按名称、按内部 ID 或按存储顺序），
     * 需要稳定顺序的调用方应自行排序。</p>
     *
     * @param query 查询条件，不能为 {@code null}；
     *              传入 {@code null} 的实现行为由驱动决定
     *              （内置驱动通常按默认条件处理）
     * @return 非 {@code null} 的位号元数据列表，无匹配时为空列表；
     *         条数不超过 {@link TagQuery#getLimit()}（{@code limit = 0}
     *         的含义由驱动解释）
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException
     *         当驱动不支持查询中使用的过滤条件（如名称通配符、值类型、
     *         单位过滤）时
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbException
     *         连接、鉴权、超时或协议解析失败时抛出对应的子类
     */
    List<TagInfo> listTags(TagQuery query);

    /**
     * 按名称精确查找单个位号（便捷方法）。
     *
     * <p>默认实现用 {@code nameMask = tagName}、{@code limit = 0}
     * 构造查询并调用 {@link #listTags(TagQuery)}，然后逐个比较
     * {@link TagInfo#getTagName()} 与目标名称，返回第一个完全相等的元素。
     * 采用“先列后筛”的方式，是为了让没有原生单点查询接口的驱动也能复用默认逻辑；
     * 具备原生接口的驱动应当覆写本方法以避免多余的数据传输。</p>
     *
     * <p>注意 {@code tagName} 会被当作通配符表达式传入，因此若位号名本身含有
     * {@code *} 或 {@code ?}，默认实现可能列出额外候选，但最终仍以字符串
     * 完全相等作为判定，不会返回错误的位号（只会多做一次过滤）。</p>
     *
     * @param tagName 位号名，不能为 {@code null} 或全空白
     * @return 匹配的位号元数据；位号不存在时返回 {@code null}
     * @throws IllegalArgumentException 当 {@code tagName} 为 {@code null}
     *                                  或全空白时
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException
     *         当驱动不支持名称通配符检索时（默认实现依赖该能力）
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbException
     *         连接、鉴权、超时或协议解析失败时抛出对应的子类
     */
    default TagInfo getTag(String tagName) {
        if (tagName == null || tagName.trim().isEmpty()) {
            throw new IllegalArgumentException("tagName must not be blank");
        }
        TagQuery query = TagQuery.builder().nameMask(tagName).limit(0).build();
        for (TagInfo tag : listTags(query)) {
            if (tagName.equals(tag.getTagName())) {
                return tag;
            }
        }
        return null;
    }
}
