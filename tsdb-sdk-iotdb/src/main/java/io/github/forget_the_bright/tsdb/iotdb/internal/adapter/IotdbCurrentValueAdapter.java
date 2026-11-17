package io.github.forget_the_bright.tsdb.iotdb.internal.adapter;

import io.github.forget_the_bright.tsdb.common.api.CurrentValueReader;
import io.github.forget_the_bright.tsdb.common.model.Sample;
import io.github.forget_the_bright.tsdb.common.model.TagSample;
import io.github.forget_the_bright.tsdb.iotdb.internal.IotdbModels;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.IotdbTagPath;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import io.github.forget_the_bright.tsdb.iotdb.IotdbMapper;

/**
 * IoTDB 当前值适配器。
 *
 * <p>装配了统一 SQL 查询服务时按分组档位批量 {@code SELECT LAST}，并按
 * {@code Timeseries} 列回填；精简实例逐位号走 REST 直连。两条路径的返回长度
 * 都恒等于入参，缺数位号用 {@code null} 样本占位。</p>
 *
 * <p><b>非公共 API</b>：门户装配层实现，签名随内部重构变化，宿主应用请勿直接依赖；
 * 稳定契约请使用 {@link io.github.forget_the_bright.tsdb.iotdb.IotdbClient}。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class IotdbCurrentValueAdapter implements CurrentValueReader {

    private final IotdbSupport support;

    /**
     * @param support 共享依赖
     */
    public IotdbCurrentValueAdapter(IotdbSupport support) {
        this.support = support;
    }

    /**
     * 批量读取最新值，返回顺序与入参一致。
     *
     * @param tagNames 位号全路径集合，可为 {@code null} 或空
     * @return 非 {@code null} 的样本列表，长度与入参一致
     */
    @Override
    public List<TagSample> read(Collection<String> tagNames) {
        if (tagNames == null || tagNames.isEmpty()) {
            return Collections.emptyList();
        }
        if (support.queryService() != null) {
            List<IotdbTagPath> paths = new ArrayList<IotdbTagPath>(tagNames.size());
            for (String tag : tagNames) {
                paths.add(support.tagResolver().resolvePath(tag));
            }
            List<IotdbModels.Point> points = support.queryService().currentBatch(paths);
            List<TagSample> batched = new ArrayList<TagSample>(tagNames.size());
            int index = 0;
            for (String tag : tagNames) {
                IotdbModels.Point point = points.get(index++);
                batched.add(TagSample.of(tag,
                        point == null ? null : IotdbMapper.toSample(point, support.config().getTimeConfig())));
            }
            return batched;
        }
        List<TagSample> result = new ArrayList<TagSample>(tagNames.size());
        for (String tag : tagNames) {
            String path = support.tagResolver().resolvePath(tag).getAbsolutePath();
            IotdbModels.Point point = support.api().readCurrent(path);
            Sample sample = point == null ? null : IotdbMapper.toSample(point, support.config().getTimeConfig());
            result.add(TagSample.of(tag, sample));
        }
        return result;
    }
}