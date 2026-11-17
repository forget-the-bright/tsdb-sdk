package io.github.forget_the_bright.tsdb.hls.internal.adapter;

import io.github.forget_the_bright.tsdb.common.api.CurrentValueReader;
import io.github.forget_the_bright.tsdb.common.exception.TsdbException;
import io.github.forget_the_bright.tsdb.common.model.Sample;
import io.github.forget_the_bright.tsdb.common.model.TagSample;
import io.github.forget_the_bright.tsdb.hls.internal.HlsModels;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * HLS 当前值适配器：一次请求取回全部位号的最新值，按入参顺序重建结果。
 *
 * <p>结果严格按请求顺序返回、并按位号名去重；厂商未返回的位号保留一个采样为
 * {@code null} 的 {@link TagSample}，方便调用方用下标与请求一一对应。</p>
 *
 * <p><b>非公共 API</b>：门户装配层实现，签名随内部重构变化，宿主应用请勿直接依赖；
 * 稳定契约请使用 {@link io.github.forget_the_bright.tsdb.hls.HlsClient}。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class HlsCurrentValueAdapter implements CurrentValueReader {

    private final HlsSupport support;

    /**
     * @param support 共享依赖
     */
    public HlsCurrentValueAdapter(HlsSupport support) {
        this.support = support;
    }

    /**
     * 批量读取位号的当前值。
     *
     * @param tagNames 位号名集合；为 {@code null} 或空时返回空列表
     * @return 非 {@code null} 的列表，长度等于去重后的请求位号数
     */
    @Override
    public List<TagSample> read(Collection<String> tagNames) {
        support.ensureOpen();
        if (tagNames == null || tagNames.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> requested = new LinkedHashSet<String>();
        for (String tagName : tagNames) {
            if (tagName == null || tagName.trim().isEmpty()) {
                throw new IllegalArgumentException("tag name must not be blank");
            }
            requested.add(tagName);
        }
        List<HlsModels.CurrentValue> values;
        try {
            values = support.nativeApi().readCurrent(requested);
        } catch (TsdbException e) {
            throw support.context(e, "currentValues");
        }
        Map<String, Sample> byName = new LinkedHashMap<String, Sample>();
        for (HlsModels.CurrentValue value : values) {
            if (value.tagName != null) {
                byName.put(value.tagName, support.mapper().toSample(value));
            }
        }
        List<TagSample> result = new ArrayList<TagSample>(requested.size());
        for (String tagName : requested) {
            result.add(TagSample.of(tagName, byName.get(tagName)));
        }
        return result;
    }
}