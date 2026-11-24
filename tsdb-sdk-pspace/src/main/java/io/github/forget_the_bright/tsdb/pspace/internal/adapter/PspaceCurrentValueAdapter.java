package io.github.forget_the_bright.tsdb.pspace.internal.adapter;

import io.github.forget_the_bright.tsdb.common.api.CurrentValueReader;
import io.github.forget_the_bright.tsdb.common.exception.TsdbException;
import io.github.forget_the_bright.tsdb.common.model.Sample;
import io.github.forget_the_bright.tsdb.common.model.TagSample;
import io.github.forget_the_bright.tsdb.pspace.internal.PspaceModels;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * pSpace 当前值适配器：{@code /RealData} 一次取回全部位号的最新值。
 *
 * <p>结果严格按请求顺序返回、并按位号名去重；服务端未返回的位号保留一个采样为
 * {@code null} 的 {@link TagSample}，方便调用方按位置对应。服务端
 * {@code No Valid Data} 在原生层已归一化为空集合；若命中“缓存未热”，
 * 原生层会自动 {@code /Refresh} 后重试一次。</p>
 *
 * <p><b>非公共 API</b>：门户装配层实现，签名随内部重构变化，宿主应用请勿直接依赖；
 * 稳定契约请使用 {@link io.github.forget_the_bright.tsdb.pspace.PspaceClient}。</p>
 *
 * @author wanghao
 * @since 0.1.2
 */
public final class PspaceCurrentValueAdapter implements CurrentValueReader {

    private final PspaceSupport support;

    /**
     * @param support 共享依赖
     */
    public PspaceCurrentValueAdapter(PspaceSupport support) {
        this.support = support;
    }

    /**
     * 批量读取当前值。
     *
     * @param tagNames 位号长名集合；为 {@code null} 或空时返回空列表
     * @return 非 {@code null} 的列表，长度等于去重后的请求位号数
     * @throws IllegalArgumentException 位号为空或全空白时
     * @throws TsdbException            查询失败时
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
        try {
            Map<String, PspaceModels.Point> points = support.resolver().resolveAll(requested);
            List<PspaceModels.RealValue> values = support.nativeApi().withAutoRefresh(
                    () -> support.nativeApi().realData(requested), List::isEmpty);
            Map<Long, PspaceModels.RealValue> byId = new LinkedHashMap<Long, PspaceModels.RealValue>();
            for (PspaceModels.RealValue value : values) {
                byId.put(Long.valueOf(value.id), value);
            }
            List<TagSample> result = new ArrayList<TagSample>(requested.size());
            for (String tagName : requested) {
                PspaceModels.Point point = points.get(tagName);
                PspaceModels.RealValue value = point == null ? null : byId.get(Long.valueOf(point.id));
                result.add(sampleOf(tagName, point, value));
            }
            return result;
        } catch (TsdbException e) {
            throw support.context(e, "currentValues");
        }
    }

    /**
     * 组装单个位号的当前值。
     *
     * <p>属性口径：</p>
     * <ul>
     *   <li>{@code unresolved=true}：位号在 pSpace 目录中找不到；</li>
     *   <li>{@code unknownType=true}：点位存在但 DataType 未知，值按字符串保留；</li>
     *   <li>服务端未返回值、或返回的 {@code tm} 缺失时，sample 为 {@code null}，
     *       {@code pv} 为 {@code null} 但 {@code tm} 存在时同样返回 {@code null} sample。</li>
     * </ul>
     *
     * @param tagName 位号名
     * @param point   点位元数据，可为 {@code null}
     * @param value   实时值，可为 {@code null}
     * @return 带定位属性的样本
     */
    private TagSample sampleOf(String tagName, PspaceModels.Point point, PspaceModels.RealValue value) {
        Map<String, String> attributes = new LinkedHashMap<String, String>();
        int dataType = -1;
        if (point != null) {
            dataType = point.dataType;
            if (support.mapper().isUnknownDataType(dataType)) {
                attributes.put("unknownType", "true");
            }
        } else {
            attributes.put("unresolved", "true");
        }
        if (value == null || value.timestamp == null) {
            return TagSample.of(tagName, (Sample) null, attributes);
        }
        Sample sample = value.value == null
                ? null
                : support.mapper().toSample(value.timestamp, value.value, value.quality, dataType, true);
        return TagSample.of(tagName, sample, attributes);
    }
}