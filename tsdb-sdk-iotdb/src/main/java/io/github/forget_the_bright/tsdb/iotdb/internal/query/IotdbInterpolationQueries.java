package io.github.forget_the_bright.tsdb.iotdb.internal.query;

import io.github.forget_the_bright.tsdb.common.model.Direction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import io.github.forget_the_bright.tsdb.iotdb.internal.IotdbModels;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.Grouping;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.IotdbSql;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.IotdbTagPath;

/**
 * IoTDB 插值族：取回真实采样点后按固定网格在内存中线性内插。
 *
 * <p><b>为什么不用 {@code FILL(LINEAR)}</b>：IoTDB 的填充只作用在已经生成的
 * {@code GROUP BY} 桶之间，桶内没有采样点就不产生该桶，产出的点数与网格槽位数
 * 不匹配，也无法保证“首点等于 {@code gridStart}”这类网格不变式。业务曲线是按
 * 索引取点的，错一格就整体错位。</p>
 *
 * <p><b>为什么必须用原始点</b>：线性权重需要真实采样时刻，而
 * {@code first_value}/{@code last_value} 这类聚合返回的时间列是窗口边界，代入
 * 权重公式会得到恒定错误的插值。因此两侧包围点走单侧原始查询，窗口内走原始点
 * 分页查询。</p>
 *
 * <p><b>数据量护栏</b>：{@code rawLimit} 限制本次最多拉取多少原始点，达到上限
 * 仍有剩余时置 {@link IotdbQueryService.Interpolation#isRawTruncated()}，让调用方
 * 知道后续槽位的插值依据不完整。批量形态三路都受合并行数预算约束，因此统一走
 * {@link IotdbQueryCore#rawShapedBatch} 的补采内核。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
final class IotdbInterpolationQueries {

    /**
     * 插值取原始点时的单页行数上限。
     *
     * <p>分页只是为了控制单次 HTTP 响应体大小与内存峰值，总拉取量由调用方传入的
     * {@code rawLimit} 决定；页大小与总量无关，因此不必随网格密度调整。</p>
     */
    private static final int INTERPOLATION_RAW_PAGE_SIZE = 10_000;

    private final IotdbQueryCore core;

    /**
     * @param core 共享执行内核，不能为 {@code null}
     */
    IotdbInterpolationQueries(IotdbQueryCore core) {
        this.core = core;
    }

    /**
     * 按固定网格对单个位号做线性插值。
     *
     * <p>槽位时刻与某个采样时刻完全重合时原样返回该采样值（保留布尔、文本等原始
     * 类型）；否则取左右包围点线性内插，两侧都有值时无论间隔多大都内插
     * （{@code toleranceMillis} 不限定内插跨度）；只剩单侧时只有距离不超过
     * {@code toleranceMillis} 才保持该侧值，为 0 表示一律不外推。</p>
     *
     * @param path            完整序列路径
     * @param gridStart       网格起点，即第 0 个槽位的时刻
     * @param stepMillis      槽位间隔（毫秒）；{@code slots} 为 1 时可为 0
     * @param slots           槽位数量，小于等于 0 时返回空结果
     * @param toleranceMillis 单侧外推容差（毫秒），{@code 0} 表示禁止外推
     * @param rawLimit        窗口内原始点拉取上限，非正数时按单页大小处理
     * @return 非 {@code null} 的插值结果，点按槽位顺序排列且时间戳恰为网格时刻
     */
    IotdbQueryService.Interpolation interpolate(String path, Instant gridStart, long stepMillis, int slots,
                                                long toleranceMillis, int rawLimit) {
        if (slots <= 0) {
            return new IotdbQueryService.Interpolation(Collections.<IotdbModels.Point>emptyList(), false);
        }
        long first = gridStart.toEpochMilli();
        long last = first + stepMillis * (slots - 1L);
        List<IotdbModels.Point> samples = new ArrayList<IotdbModels.Point>();
        samples.addAll(core.columns().singleColumn(core.executor().query(
                IotdbSql.nearest(Grouping.DEVICE, IotdbQueryCore.singlePath(path),
                        first, Direction.BACKWARD, 1))));
        int budget = Math.max(1, rawLimit);
        int fetched = 0;
        boolean truncated = false;
        while (true) {
            int pageLimit = (int) Math.min((long) INTERPOLATION_RAW_PAGE_SIZE, (long) budget - fetched);
            if (pageLimit <= 0) {
                truncated = true;
                break;
            }
            List<IotdbModels.Point> page = core.columns().singleColumn(core.executor().query(
                    IotdbQueryCore.rawPage(path, first, last + 1L, Direction.FORWARD, pageLimit, fetched)));
            samples.addAll(page);
            fetched += page.size();
            if (page.size() < pageLimit) {
                break;
            }
            if (fetched >= budget) {
                truncated = true;
                break;
            }
        }
        samples.addAll(core.columns().singleColumn(core.executor().query(
                IotdbSql.nearest(Grouping.DEVICE, IotdbQueryCore.singlePath(path),
                        last, Direction.FORWARD, 1))));
        return new IotdbQueryService.Interpolation(
                core.math().gridOf(samples, first, stepMillis, slots, toleranceMillis), truncated);
    }

    /**
     * 按配置的档位批量插值：每块三条 SQL（窗内原始点 + 两侧包围点），
     * 取数不足的位号逐位号补采。
     *
     * <p>与逐位号唯一的口径差别：逐位号分页取满 {@code rawLimit} 个点就把
     * {@code rawTruncated} 置真，而批量多取一个点作见证，只有真的存在第
     * {@code rawLimit + 1} 个点时才置真。数值完全一致，只是这个标记不再把
     * “窗口里恰好只有 {@code rawLimit} 个点”误报成截断。</p>
     *
     * @param tags            位号列表
     * @param gridStart       网格起点，即第 0 个槽位的时刻
     * @param stepMillis      槽位间隔（毫秒）
     * @param slots           槽位数量，小于等于 0 时每个位号都是空结果
     * @param toleranceMillis 单侧外推容差（毫秒），{@code 0} 表示禁止外推
     * @param rawLimit        窗内原始点拉取上限
     * @param completed       输出参数：走过补采的位号绝对路径，可为 {@code null}
     * @return 位号绝对路径 → 插值结果，键覆盖全部入参位号
     */
    Map<String, IotdbQueryService.Interpolation> interpolateBatch(List<IotdbTagPath> tags, Instant gridStart,
                                                                  long stepMillis, int slots, long toleranceMillis,
                                                                  int rawLimit, Set<String> completed) {
        Map<String, IotdbQueryService.Interpolation> result =
                new LinkedHashMap<String, IotdbQueryService.Interpolation>();
        if (tags == null || tags.isEmpty()) {
            return result;
        }
        if (slots <= 0) {
            for (IotdbTagPath path : tags) {
                result.put(path.getAbsolutePath(), new IotdbQueryService.Interpolation(
                        Collections.<IotdbModels.Point>emptyList(), false));
            }
            return result;
        }
        Grouping grouping = core.config().getQueryBatchScope();
        long first = gridStart.toEpochMilli();
        long last = first + stepMillis * (slots - 1L);
        int budget = Math.max(1, rawLimit);
        Set<String> sink = completed == null ? new LinkedHashSet<String>() : completed;
        Map<String, List<IotdbModels.Point>> before = core.rawShapedBatch(grouping, tags, 1,
                (candidate, chunk, limit) -> IotdbSql.nearest(candidate, chunk, first,
                        Direction.BACKWARD, limit),
                path -> IotdbSql.nearest(Grouping.DEVICE, Collections.singletonList(path), first,
                        Direction.BACKWARD, 1), sink);
        Map<String, List<IotdbModels.Point>> window = core.rawShapedBatch(grouping, tags, budget + 1,
                (candidate, chunk, limit) -> IotdbSql.raw(candidate, chunk, first, last + 1L,
                        Direction.FORWARD, limit),
                path -> IotdbSql.raw(Grouping.DEVICE, Collections.singletonList(path), first,
                        last + 1L, Direction.FORWARD, budget + 1), sink);
        Map<String, List<IotdbModels.Point>> after = core.rawShapedBatch(grouping, tags, 1,
                (candidate, chunk, limit) -> IotdbSql.nearest(candidate, chunk, last,
                        Direction.FORWARD, limit),
                path -> IotdbSql.nearest(Grouping.DEVICE, Collections.singletonList(path), last,
                        Direction.FORWARD, 1), sink);
        for (IotdbTagPath path : tags) {
            String key = path.getAbsolutePath();
            List<IotdbModels.Point> samples = new ArrayList<IotdbModels.Point>();
            List<IotdbModels.Point> raw = window.get(key);
            samples.addAll(core.math().zeroIfNull(before.get(key)));
            samples.addAll(raw == null ? Collections.<IotdbModels.Point>emptyList() : raw);
            samples.addAll(core.math().zeroIfNull(after.get(key)));
            boolean truncated = raw != null && raw.size() > budget;
            List<IotdbModels.Point> grid = core.math().gridOf(samples, first, stepMillis, slots, toleranceMillis);
            result.put(key, new IotdbQueryService.Interpolation(grid, truncated));
        }
        return result;
    }
}