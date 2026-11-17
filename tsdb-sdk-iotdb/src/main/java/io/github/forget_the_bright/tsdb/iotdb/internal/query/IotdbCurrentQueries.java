package io.github.forget_the_bright.tsdb.iotdb.internal.query;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import io.github.forget_the_bright.tsdb.iotdb.internal.IotdbModels;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.Grouping;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.IotdbSql;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.IotdbTagPath;
import io.github.forget_the_bright.tsdb.iotdb.internal.transport.IotdbResultTable;

/**
 * IoTDB 最新值查询族：按配置档位一次取回一批位号的 {@code LAST} 值。
 *
 * <p>批量 LAST 走 {@code expressions=["Timeseries","Value","DataType"]} 的列式布局，
 * {@code timestamps} 是每个序列各自的采样时刻，且行序不保证等于入参顺序；请求里
 * 不存在的测点会被服务端整行丢弃（不报错、不占位）。因此必须按 {@code Timeseries}
 * 建索引再按入参顺序回填，不能按列序对齐——否则一个改名位号会让后面所有位号
 * 集体串位，把配置错误显示成“别人的最新值”。</p>
 *
 * <p>缺数语义：返回列表长度恒等于入参长度，无数据的位号对应 {@code null}。
 * 公共层 {@code CurrentValueReader} 的调用方普遍依赖“下标即位号”这一不变式。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
final class IotdbCurrentQueries {

    private final IotdbQueryCore core;

    /**
     * @param core 共享执行内核，不能为 {@code null}
     */
    IotdbCurrentQueries(IotdbQueryCore core) {
        this.core = core;
    }

    /**
     * 按配置的分组档位批量读取最新值，返回与入参同序的点列表。
     *
     * <p>档位、单条语句的表达式上限与并行度都取自 {@code IotdbConfig}。切换档位
     * 只改变一次请求发几条 SQL，不改变返回列表的长度、顺序与取值口径。</p>
     *
     * @param paths 已解析的位号路径，按返回顺序排列，可为空
     * @return 与 {@code paths} 同序的点列表，元素可能为 {@code null}
     */
    List<IotdbModels.Point> currentBatch(List<IotdbTagPath> paths) {
        return currentBatch(paths, core.config().getQueryBatchScope(),
                core.config().getQueryBatchSize(), core.config().getQueryParallelism());
    }

    /**
     * 指定档位、块大小与并行度的最新值批量查询。
     *
     * @param ordered     位号列表，保持返回顺序
     * @param grouping    分组档位
     * @param batchSize   单条语句的表达式上限
     * @param parallelism 语句级并行度
     * @return 与 {@code ordered} 同序的点列表
     */
    List<IotdbModels.Point> currentBatch(List<IotdbTagPath> ordered, Grouping grouping, int batchSize,
                                         int parallelism) {
        if (ordered.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> sqls = new ArrayList<String>();
        for (List<IotdbTagPath> part : core.chunks(grouping, ordered, batchSize)) {
            sqls.add(IotdbSql.currentBatch(grouping, part));
        }
        SeriesKeyIndex<IotdbModels.Point> found = new SeriesKeyIndex<IotdbModels.Point>();
        if (parallelism > 1 && sqls.size() > 1) {
            for (IotdbResultTable table : core.queryAll(sqls, parallelism)) {
                merge(table, found);
            }
        } else {
            for (String sql : sqls) {
                merge(core.query(sql), found);
            }
        }
        List<IotdbModels.Point> result = new ArrayList<IotdbModels.Point>(ordered.size());
        for (IotdbTagPath path : ordered) {
            result.add(found.get(path));
        }
        return result;
    }

    /**
     * 把一条批量 LAST 的结果按 {@code Timeseries} 列合并进索引表。
     *
     * <p>列名按实测为 {@code Timeseries}/{@code Value}；不同通道大小写可能不同，
     * 因此按名匹配并保留“第 0 列是路径、第 1 列是值”的下标兜底。服务端会丢弃
     * 不存在的测点，这里不补任何假值：缺失由返回列表中的 {@code null} 表达。</p>
     *
     * @param table 单条批量 LAST 的结果
     * @param found 累积的“规范路径 → 最新点”索引
     */
    private void merge(IotdbResultTable table, SeriesKeyIndex<IotdbModels.Point> found) {
        ResultColumns columns = core.columns();
        int pathIndex = columns.column(table, "Timeseries", 0);
        int valueIndex = columns.valueColumn(table, "Value", 1);
        for (IotdbResultTable.Row row : table.getRows()) {
            Object[] values = row.getValues();
            if (pathIndex < 0 || pathIndex >= values.length || values[pathIndex] == null) {
                continue;
            }
            Object value = valueIndex >= 0 && valueIndex < values.length ? values[valueIndex] : null;
            found.put(String.valueOf(values[pathIndex]), columns.point(row.getTimestamp(), value));
        }
    }
}