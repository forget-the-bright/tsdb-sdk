package io.github.forget_the_bright.tsdb.iotdb.internal.query;

import io.github.forget_the_bright.tsdb.common.model.Direction;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import io.github.forget_the_bright.tsdb.iotdb.internal.IotdbModels;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.Grouping;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.IotdbSql;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.IotdbTagPath;
import io.github.forget_the_bright.tsdb.iotdb.internal.transport.IotdbResultTable;

/**
 * IoTDB 原始点查询族：批量 RAW 与逐位号 RAW。
 *
 * <p>多列 RAW 的 {@code LIMIT} 是合并时间戳后的行数预算，不是每位号的点数。
 * 同窗内密集位号会把稀疏位号挤出结果集，因此批量结果必须经过
 * {@link IotdbQueryCore#rawShapedBatch} 的补采判定；逐位号入口与补采 SQL 同源，
 * 都是“设备前缀 + 单个测点 + 需求点数”。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
final class IotdbRawQueries {

    private final IotdbQueryCore core;

    /**
     * @param core 共享执行内核，不能为 {@code null}
     */
    IotdbRawQueries(IotdbQueryCore core) {
        this.core = core;
    }

    /**
     * 批量读取原始点并保证与逐位号查询逐点相同（RAW 族对外的唯一批量形态）。
     *
     * <p>先按块发多列批量 SQL，取数不足的位号再逐位号补采；补采用的就是逐位号
     * 公开方法那条 SQL，因此不新造语义。</p>
     *
     * @param tags      位号列表
     * @param start     起点（含）
     * @param end       终点（不含）
     * @param direction 返回方向
     * @param maxPoints 每位号需要的点数（不含用于判截断的那一个额外点）
     * @param completed 输出参数：走过补采的位号绝对路径，可为 {@code null}
     * @return 位号绝对路径 → 原始点序列，每位号至多 {@code maxPoints + 1} 个点
     */
    Map<String, List<IotdbModels.Point>> rawBatchCompleted(List<IotdbTagPath> tags, Instant start,
                                                          Instant end, Direction direction, int maxPoints,
                                                          Set<String> completed) {
        Grouping grouping = core.config().getQueryBatchScope();
        long begin = start.toEpochMilli();
        long finish = end.toEpochMilli();
        int need = Math.max(1, maxPoints) + 1;
        Set<String> sink = completed == null ? new LinkedHashSet<String>() : completed;
        return core.rawShapedBatch(grouping, tags, need,
                (candidate, chunk, limit) -> IotdbSql.raw(candidate, chunk, begin, finish, direction, limit),
                path -> IotdbSql.raw(Grouping.DEVICE, Collections.singletonList(path), begin, finish,
                        direction, need),
                sink);
    }

    /**
     * 读取单个位号的原始点。
     *
     * @param path      完整序列路径
     * @param start     起点（含）
     * @param end       终点（不含）
     * @param direction 返回方向
     * @param limit     最大点数
     * @return 点列表，非 {@code null}
     */
    List<IotdbModels.Point> raw(String path, Instant start, Instant end,
                                Direction direction, int limit) {
        IotdbResultTable table = core.executor().query(IotdbSql.raw(Grouping.DEVICE,
                IotdbQueryCore.singlePath(path), start.toEpochMilli(), end.toEpochMilli(), direction, limit));
        return core.columns().singleColumn(table);
    }
}