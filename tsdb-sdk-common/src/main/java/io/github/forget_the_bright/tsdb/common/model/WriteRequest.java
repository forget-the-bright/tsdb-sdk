package io.github.forget_the_bright.tsdb.common.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 单个位号的写入请求：一组待写入的采样点。
 *
 * <p>这是 {@code WriteSession#write(List)} 入参列表的元素类型，
 * 一次写入可以携带多个位号、每个位号多个点。语义上这组点属于同一个位号，
 * 因此位号名只在请求级别出现一次，避免每个点重复携带。</p>
 *
 * <p>约束：{@link #getTagName()} 必填且不能为 {@code null}；
 * {@link #getSamples()} 不能为 {@code null} 也不能为空列表
 * （空写入没有意义，视为调用方错误），否则构造时抛
 * {@link IllegalArgumentException}。列表中的 {@code null} 元素不会被本类过滤，
 * 由驱动在写入时决定如何处理。</p>
 *
 * <p>不可变性与线程安全：采样点列表在构造时被复制并包装为不可修改视图，
 * 字段均为 {@code final}，实例完全不可变，可安全地在多线程间共享。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class WriteRequest {

    /**
     * 目标位号名，非 {@code null}。
     */
    private final String tagName;

    /**
     * 待写入的采样点列表，非空、不可修改。
     */
    private final List<Sample> samples;

    /**
     * 构造一个位号的写入请求。
     *
     * @param tagName 目标位号名，不能为 {@code null}
     * @param samples 待写入的采样点列表，不能为 {@code null} 或空列表；
     *                元素允许为 {@code null}（交由驱动处理）
     * @throws NullPointerException     当 {@code tagName} 为 {@code null} 时
     * @throws IllegalArgumentException 当 {@code samples} 为 {@code null}
     *                                  或空列表时
     */
    public WriteRequest(String tagName, List<Sample> samples) {
        this.tagName = Objects.requireNonNull(tagName, "tagName");
        if (samples == null || samples.isEmpty()) {
            throw new IllegalArgumentException("samples must not be empty");
        }
        this.samples = Collections.unmodifiableList(new ArrayList<Sample>(samples));
    }

    /**
     * 返回目标位号名。
     *
     * @return 非 {@code null} 的位号名
     */
    public String getTagName() {
        return tagName;
    }

    /**
     * 返回待写入的采样点列表。
     *
     * <p>注意写入顺序通常就是列表顺序，是否按时间戳重排、是否允许乱序，
     * 由驱动遵守厂商语义并需在驱动文档中说明。</p>
     *
     * @return 非 {@code null} 且非空的只读列表，修改会抛
     *         {@link UnsupportedOperationException}
     */
    public List<Sample> getSamples() {
        return samples;
    }
}
