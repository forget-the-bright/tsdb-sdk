package io.github.forget_the_bright.tsdb.common.api;

import io.github.forget_the_bright.tsdb.common.model.TagSample;

import java.util.Collection;
import java.util.List;

/**
 * 当前值（最新值）读取操作。
 *
 * <p>在 SDK 中承担“实时快照读取”的角色：只取每个位号当前最新的一个采样点，
 * 不做历史区间查询。相比历史查询，它更轻量、延迟更低，适合做实时画面刷新、
 * 定时轮询和简单告警判断。</p>
 *
 * <p>能力要求：需要实现类所在的客户端声明
 * {@link io.github.forget_the_bright.tsdb.common.model.Capability#CURRENT_VALUE}
 * 能力；未声明时实现应抛 {@code TSDB-UNSUPPORTED}。</p>
 *
 * <p>实现约束：</p>
 * <ul>
 *   <li>方法不得返回 {@code null}，无结果时返回空列表；</li>
 *   <li>结果顺序应与请求的位号顺序一致，便于调用方按下标对应；</li>
 *   <li>实现必须线程安全（继承自 {@code TsdbClient} 的契约），
 *       可被多个线程并发调用；</li>
 *   <li>返回的 {@link TagSample} 与 {@link io.github.forget_the_bright.tsdb.common.model.Sample}
 *       均为不可变对象，可安全跨线程传递。</li>
 * </ul>
 *
 * @author wanghao
 * @since 0.1.0
 */
public interface CurrentValueReader {

    /**
     * 批量读取多个位号的当前值。
     *
     * <p>本次调用只取每个位号“最新”的一个点，时间戳即该值最后一次被采集的时刻。
     * 某个位号不存在或从未采集到数据时，实现可以选择跳过该位号（返回列表变短），
     * 或返回一个 {@link TagSample#getSample()} 为 {@code null} 的条目，
     * 具体由驱动决定；调用方不应假设返回列表长度一定等于请求长度，
     * 而应按 {@link TagSample#getTagName()} 对应。</p>
     *
     * <p>请求中的位号若含 {@code null} 或空白字符串，属于调用方错误，
     * 实现可以抛 {@link IllegalArgumentException}；空集合则返回空列表。</p>
     *
     * @param tagNames 待读取的位号名集合，不能为 {@code null}；
     *                 建议非空，元素非空白
     * @return 非 {@code null} 的当前值列表，顺序与请求顺序一致；
     *         无任何结果时为空列表
     * @throws IllegalArgumentException 当 {@code tagNames} 为 {@code null}
     *                                  或包含空白位号时（由实现决定是否校验）
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbException
     *         连接、鉴权、超时或协议解析失败时抛出对应的子类
     */
    List<TagSample> read(Collection<String> tagNames);

    /**
     * 读取单个位号的当前值（便捷方法）。
     *
     * <p>默认实现先做参数校验，然后委托给
     * {@link #read(Collection)}，取返回列表的第一个元素；若列表为空则返回
     * {@code null}。因此当底层实现选择“跳过不存在位号”的策略时，
     * 本方法以 {@code null} 表示“没有值”。</p>
     *
     * <p>本方法只保证返回列表非空时取第一个元素，不做位号名匹配；
     * 若实现返回的首个元素不属于请求的位号，属于实现违约。</p>
     *
     * @param tagName 待读取的位号名，不能为 {@code null} 或全空白
     * @return 该位号的当前值；位号无数据时返回 {@code null}
     * @throws IllegalArgumentException 当 {@code tagName} 为 {@code null}
     *                                  或全空白时
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbException
     *         连接、鉴权、超时或协议解析失败时抛出对应的子类
     */
    default TagSample read(String tagName) {
        if (tagName == null || tagName.trim().isEmpty()) {
            throw new IllegalArgumentException("tagName must not be blank");
        }
        List<TagSample> result = read(java.util.Collections.singletonList(tagName));
        return result.isEmpty() ? null : result.get(0);
    }
}
