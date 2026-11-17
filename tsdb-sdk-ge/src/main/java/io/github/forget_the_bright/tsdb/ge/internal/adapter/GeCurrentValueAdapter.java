package io.github.forget_the_bright.tsdb.ge.internal.adapter;

import io.github.forget_the_bright.tsdb.common.api.CurrentValueReader;
import io.github.forget_the_bright.tsdb.common.model.Sample;
import io.github.forget_the_bright.tsdb.common.model.TagSample;
import io.github.forget_the_bright.tsdb.ge.internal.GeModels;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.github.forget_the_bright.tsdb.ge.GeMapper;

/**
 * GE 当前值适配器：一次请求取回全部标签的最新采样，再按入参顺序重建结果。
 *
 * <p>厂商未返回的标签用 {@link TagSample#of(String, Sample)} 占位，保证结果长度
 * 与请求集合一一对应。每个结果都携带厂商元信息（错误码、数据类型、工程单位），
 * 调用方据此区分“确实无值”与“该位号查询出错”。</p>
 *
 * <p><b>非公共 API</b>：门户装配层实现，签名随内部重构变化，宿主应用请勿直接依赖；
 * 稳定契约请使用 {@link io.github.forget_the_bright.tsdb.ge.GeClient}。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class GeCurrentValueAdapter implements CurrentValueReader {

    private final GeSupport support;

    /**
     * @param support 共享配置与原生客户端
     */
    public GeCurrentValueAdapter(GeSupport support) {
        this.support = support;
    }

    /**
     * 批量读取当前值。
     *
     * @param tagNames 标签名集合；为 {@code null} 或空时直接返回空列表，不发请求
     * @return 与入参顺序一致的采样列表，缺失项采样为 {@code null}
     */
    @Override
    public List<TagSample> read(Collection<String> tagNames) {
        if (tagNames == null || tagNames.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> requested = new ArrayList<String>(tagNames);
        List<GeModels.Series> series = support.nativeApi().readCurrentRawValue(requested);
        Map<String, GeModels.Series> byName = new LinkedHashMap<String, GeModels.Series>();
        for (GeModels.Series item : series) {
            if (item.getTagName() != null) {
                byName.put(item.getTagName(), item);
            }
        }
        List<TagSample> result = new ArrayList<TagSample>(requested.size());
        for (String tag : requested) {
            GeModels.Series item = byName.get(tag);
            if (item == null) {
                result.add(TagSample.of(tag, null));
                continue;
            }
            Map<String, String> attributes = attributes(item);
            Sample sample = null;
            if (item.getErrorCode() == null && !item.getPoints().isEmpty()) {
                sample = GeMapper.toSample(item.getPoints().get(0), support.config().getTimeConfig());
            }
            result.add(TagSample.of(tag, sample, attributes));
        }
        return result;
    }

    /**
     * 把厂商序列的元信息整理为附加属性表。
     *
     * <p>{@code errorCode} 恒写入（正常为 {@code "0"}）；其余键仅在厂商提供时
     * 写入，避免用空串冒充缺失。</p>
     *
     * @param item 厂商序列
     * @return 属性表，非 {@code null}
     */
    private static Map<String, String> attributes(GeModels.Series item) {
        Map<String, String> attributes = new LinkedHashMap<String, String>();
        attributes.put(TagSample.ATTR_ERROR_CODE,
                String.valueOf(item.getErrorCode() == null ? 0 : item.getErrorCode()));
        if (item.getErrorMessage() != null) {
            attributes.put(TagSample.ATTR_ERROR_MESSAGE, item.getErrorMessage());
        }
        if (item.getDataType() != null) {
            attributes.put(TagSample.ATTR_DATA_TYPE, item.getDataType());
        }
        if (item.getEngineeringUnits() != null) {
            attributes.put(TagSample.ATTR_ENGINEERING_UNITS, item.getEngineeringUnits());
        }
        if (item.getHiEngineeringUnits() != null) {
            attributes.put(TagSample.ATTR_HI_ENGINEERING_UNITS, item.getHiEngineeringUnits());
        }
        if (item.getLoEngineeringUnits() != null) {
            attributes.put(TagSample.ATTR_LO_ENGINEERING_UNITS, item.getLoEngineeringUnits());
        }
        return attributes;
    }
}