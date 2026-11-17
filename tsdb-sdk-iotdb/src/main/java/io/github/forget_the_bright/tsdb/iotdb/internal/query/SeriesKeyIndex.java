package io.github.forget_the_bright.tsdb.iotdb.internal.query;

import java.util.LinkedHashMap;
import java.util.Map;
import io.github.forget_the_bright.tsdb.iotdb.internal.IotdbModels;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.IotdbTagPath;

/**
 * 按序列路径建立的结果索引，用于把服务端返回的<b>行</b>回填到入参位号上。
 *
 * <p><b>为什么必须按名而不是按序</b>：批量 {@code SELECT LAST} 的行序不保证等于
 * 请求顺序（2026-11-13 生产 2.0.11 实测，200 表达式乱序返回），且不存在的序列会被
 * 服务端<b>整行丢弃</b>而不是补一行空值。按位置对齐就会把一个位号的最新值挂到
 * 另一个位号名下——配置错误被显示成“别人的真实读数”，比查不到更糟。</p>
 *
 * <p><b>只做精确匹配</b>：键由 {@link IotdbTagPath#normalizeKey(String)} 生成
 * （去掉 {@code FN(...)} 包裹、反引号与首尾空白，保留大小写）。<b>不做忽略大小写的
 * 就近匹配</b>：IoTDB 的节点名大小写敏感，两条只差大小写的序列可以真实共存，
 * 忽略大小写的退路会把它们混成一条。未命中就是未命中，由调用方填 {@code null}。</p>
 *
 * <p>未知键（响应里出现了本次没有请求的序列）同样按精确匹配处理：它进索引但不会被
 * 任何入参取到。本类不报错，因为“行”维度的多余序列不影响其他位号的正确归属；
 * 列维度的对齐由 {@code IotdbQueryService} 的列定位护栏负责。</p>
 *
 * <p>非线程安全：每次批量查询新建一个实例。</p>
 *
 * @param <V> 索引的值类型，通常是 {@link IotdbModels.Point}
 * @author wanghao
 * @since 0.1.0
 */
public final class SeriesKeyIndex<V> {

    private final Map<String, V> values = new LinkedHashMap<String, V>();

    /**
     * 以服务端回显的列名或 {@code Timeseries} 值为键写入一项结果。
     *
     * @param serverLabel 服务端文本，非 {@code null} 时按其规范键写入
     * @param value       结果值
     */
    public void put(String serverLabel, V value) {
        String key = IotdbTagPath.normalizeKey(serverLabel);
        if (key != null && !key.isEmpty()) {
            values.put(key, value);
        }
    }

    /**
     * 按位号路径取回结果。
     *
     * @param path 已解析的位号路径，不能为 {@code null}
     * @return 该位号对应的结果；服务端没有返回这一行时为 {@code null}
     */
    public V get(IotdbTagPath path) {
        return values.get(path.getKey());
    }

    /**
     * 返回已索引的结果条数，仅用于诊断与断言。
     *
     * @return 索引条目数
     */
    public int size() {
        return values.size();
    }
}
