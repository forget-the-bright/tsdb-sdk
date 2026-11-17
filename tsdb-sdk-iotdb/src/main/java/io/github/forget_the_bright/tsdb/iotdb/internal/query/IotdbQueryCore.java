package io.github.forget_the_bright.tsdb.iotdb.internal.query;

import io.github.forget_the_bright.tsdb.common.exception.TsdbQueryException;
import io.github.forget_the_bright.tsdb.common.model.Direction;
import io.github.forget_the_bright.tsdb.iotdb.IotdbConfig;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import io.github.forget_the_bright.tsdb.iotdb.internal.IotdbModels;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.Grouping;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.IotdbSql;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.IotdbTagPath;
import io.github.forget_the_bright.tsdb.iotdb.internal.transport.IotdbQueryExecutor;
import io.github.forget_the_bright.tsdb.iotdb.internal.transport.IotdbResultTable;

/**
 * IoTDB 各查询族共用的执行内核：分块、并行、执行与结果列对齐。
 *
 * <p>拆出本类是为了让“怎么把一批位号变成若干条 SQL、再把结果按位号回填”
 * 只有一个实现。六个查询族（当前值、原始、采样、聚合、插值、差分）的差别只在
 * SQL 文本与一个位号发射几列，分块规则、并行策略、异常包装和列名对齐必须完全
 * 一致，否则同一个位号集合换一种查询就会得到不同数量的往返与不同的错误消息。</p>
 *
 * <p><b>块边界的两个约束</b>：{@code query-batch-size} 限制一条语句的表达式个数，
 * {@link #MAX_SQL_BYTES} 限制整条语句的 UTF-8 字节数，先到者生效；单个位号
 * 本身超过字节预算时仍单独成块，不能被丢掉。</p>
 *
 * <p><b>并行只发生在语句之间</b>：一条 SQL 已经覆盖一个块的全部位号，再往下切
 * 没有收益。线程池按调用创建、按调用销毁，避免客户端里养一个生命周期不明的常驻池。</p>
 *
 * <p>本类不对外暴露，{@link IotdbQueryService} 仍是唯一的公开门户；查询族类
 * 只在本包内可见。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
final class IotdbQueryCore {

    /**
     * 单条批量 SQL 的 UTF-8 字节预算。
     *
     * <p><b>这是客户端的保守值，不是实测的服务端限制</b>：M8 实测 300 个表达式、
     * 约 13 KB 的语句被生产 2.0.11 正常接受，业务形态（345 位号同库）估算只有
     * 12 KB 左右，正常配置下本预算不会触发。它兜的是“路径异常长 +
     * {@code query-batch-size} 调大”这种实测没钉过的组合。</p>
     */
    static final int MAX_SQL_BYTES = 65_536;

    /** 一条语句除列表达式之外的固定开销估算：关键字、{@code WHERE}、函数包裹与空白。 */
    static final int STATEMENT_OVERHEAD_BYTES = 512;

    private final IotdbConfig config;
    private final IotdbQueryExecutor executor;
    private final ResultColumns resultColumns;
    private final PointMath pointMath;

    /**
     * 创建共享内核。
     *
     * @param config   驱动配置，不能为 {@code null}
     * @param executor SQL 执行通道，不能为 {@code null}
     */
    IotdbQueryCore(IotdbConfig config, IotdbQueryExecutor executor) {
        this.config = config;
        this.executor = executor;
        this.resultColumns = new ResultColumns(config.getTimeConfig());
        this.pointMath = new PointMath(config.getTimeConfig(), resultColumns);
    }

    /**
     * @return 驱动配置
     */
    IotdbConfig config() {
        return config;
    }

    /**
     * @return SQL 执行通道
     */
    IotdbQueryExecutor executor() {
        return executor;
    }

    /**
     * @return 结果列定位器
     */
    ResultColumns columns() {
        return resultColumns;
    }

    /**
     * @return 点值计算器（差分、插值、首末相减）
     */
    PointMath math() {
        return pointMath;
    }

    /**
     * 把一批位号按配置的档位与上限切成若干块，每块对应一条 SQL。
     *
     * <p>本方法对外可见是为了失败归因：调用方按块逐次查询时，一条 SQL 的服务端
     * 错误只影响这一块里的位号。分块是幂等的——对某一块再次调用本方法得到的就是
     * 它自己，因为一块之内所有位号同组且块长不超过配置上限。</p>
     *
     * @param grouping 分组档位
     * @param tags     位号列表，可为空
     * @return 块列表，保持分组首次出现顺序与块内入参顺序
     */
    List<List<IotdbTagPath>> chunks(Grouping grouping, List<IotdbTagPath> tags) {
        return chunks(grouping, tags, config.getQueryBatchSize());
    }

    /**
     * 按指定批量上限切块，供“上限由调用点传入”的入口复用同一套分块规则。
     *
     * @param grouping  分组档位
     * @param tags      位号列表，可为空
     * @param batchSize 单条 SQL 的表达式个数上限，非正数时按 1 处理
     * @return 块列表，顺序与 {@link #chunks(Grouping, List)} 同口径
     */
    List<List<IotdbTagPath>> chunks(Grouping grouping, List<IotdbTagPath> tags, int batchSize) {
        List<List<IotdbTagPath>> result = new ArrayList<List<IotdbTagPath>>();
        if (tags == null || tags.isEmpty()) {
            return result;
        }
        int limit = Math.max(1, batchSize);
        for (List<IotdbTagPath> group : groupBy(tags, grouping).values()) {
            List<IotdbTagPath> chunk = new ArrayList<IotdbTagPath>(limit);
            int bytes = STATEMENT_OVERHEAD_BYTES;
            for (IotdbTagPath path : group) {
                int cost = utf8Bytes(grouping.expression(path)) + 2;
                if (!chunk.isEmpty() && (chunk.size() >= limit || bytes + cost > MAX_SQL_BYTES)) {
                    result.add(chunk);
                    chunk = new ArrayList<IotdbTagPath>(limit);
                    bytes = STATEMENT_OVERHEAD_BYTES;
                }
                chunk.add(path);
                bytes += cost;
            }
            if (!chunk.isEmpty()) {
                result.add(chunk);
            }
        }
        return result;
    }

    /**
     * 执行一批 SQL，返回与入参同序的结果表；需要时才并行。
     *
     * @param sqls        SQL 列表
     * @param parallelism 并行度上限，小于等于 1 或只有一条 SQL 时顺序执行
     * @return 与 {@code sqls} 同序的结果表列表
     */
    List<IotdbResultTable> queryAll(List<String> sqls, int parallelism) {
        if (parallelism > 1 && sqls.size() > 1) {
            return queryInParallel(sqls, parallelism);
        }
        List<IotdbResultTable> tables = new ArrayList<IotdbResultTable>(sqls.size());
        for (String sql : sqls) {
            tables.add(query(sql));
        }
        return tables;
    }

    /**
     * 执行单条 SQL，失败时把是哪条语句并进异常信息。
     *
     * <p>批量之后一条语句覆盖一个块，底层只回 {@code code=701 …} 时现场既不知道是
     * 哪条语句，也不知道影响哪些位号。这里把语句随异常一起抛出，客户端的块级失败
     * 归因直接把它写进 {@code vendorError}。</p>
     *
     * @param sql 待执行的语句
     * @return 非 {@code null} 的结果表
     * @throws TsdbQueryException 底层执行失败时，消息含被执行的语句
     */
    IotdbResultTable query(String sql) {
        try {
            return executor.query(sql);
        } catch (RuntimeException e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new TsdbQueryException("IoTDB statement failed, sql=" + sql + ": " + detail, e);
        }
    }

    /**
     * 多列族查询的共用骨架：分块 → 一条 SQL 一块 → 按列名定位 → 组装成“位号 → 每列点序列”。
     *
     * @param grouping  分组档位
     * @param tags      位号列表，空时返回空索引
     * @param perTag    每个位号发射的列数
     * @param functions 每列期望的函数名，长度须为 {@code perTag}；整体传 {@code null} 表示不校验
     * @param builder   族专属的 SQL 构造器
     * @return 位号绝对路径 → 长度 {@code perTag} 的列列表；键覆盖全部入参位号
     */
    Map<String, List<List<IotdbModels.Point>>> batchColumns(Grouping grouping, List<IotdbTagPath> tags,
                                                            int perTag, List<String> functions,
                                                            SqlBuilder builder) {
        Map<String, List<List<IotdbModels.Point>>> result =
                new LinkedHashMap<String, List<List<IotdbModels.Point>>>();
        if (tags == null || tags.isEmpty()) {
            return result;
        }
        List<List<IotdbTagPath>> chunks = chunks(grouping, tags);
        List<String> sqls = new ArrayList<String>(chunks.size());
        for (List<IotdbTagPath> chunk : chunks) {
            sqls.add(builder.build(grouping, chunk));
        }
        List<IotdbResultTable> tables = queryAll(sqls, config.getQueryParallelism());
        for (int i = 0; i < chunks.size(); i++) {
            List<IotdbTagPath> chunk = chunks.get(i);
            IotdbResultTable table = tables.get(i);
            List<String> keys = new ArrayList<String>(chunk.size() * perTag);
            List<String> emittedFunctions = functions == null ? null : new ArrayList<String>(chunk.size() * perTag);
            for (IotdbTagPath path : chunk) {
                for (int f = 0; f < perTag; f++) {
                    keys.add(path.getKey());
                    if (emittedFunctions != null) {
                        emittedFunctions.add(functions.get(f));
                    }
                }
            }
            int[] index = resultColumns.alignColumns(table, keys, emittedFunctions, sqls.get(i));
            for (int j = 0; j < chunk.size(); j++) {
                List<List<IotdbModels.Point>> columns = new ArrayList<List<IotdbModels.Point>>(perTag);
                for (int f = 0; f < perTag; f++) {
                    columns.add(resultColumns.columnSeries(table, index[j * perTag + f]));
                }
                result.put(chunk.get(j).getAbsolutePath(), columns);
            }
        }
        return result;
    }

    /**
     * 多列单列表征：一个位号只发射一列的族用这个便捷入口。
     *
     * @param grouping  分组档位
     * @param tags      位号列表
     * @param functions 该列期望的函数名，可为 {@code null}
     * @param builder   SQL 构造器
     * @return 位号绝对路径 → 点序列
     */
    Map<String, List<IotdbModels.Point>> batchSeries(Grouping grouping, List<IotdbTagPath> tags,
                                                     List<String> functions, SqlBuilder builder) {
        Map<String, List<IotdbModels.Point>> result = new LinkedHashMap<String, List<IotdbModels.Point>>();
        for (Map.Entry<String, List<List<IotdbModels.Point>>> entry
                : batchColumns(grouping, tags, 1, functions, builder).entrySet()) {
            result.put(entry.getKey(), entry.getValue().get(0));
        }
        return result;
    }

    /**
     * RAW 形态族的「批量 + 逐位号补采」内核。
     *
     * <p><b>为什么必须补采</b>：多列 RAW 的 {@code LIMIT} 是合并时间戳后的行数预算，
     * 同窗内密集位号会把稀疏位号挤出结果集（实测 M9/G6）。直接返回批量结果就是
     * “把稀疏位号的点静默丢掉”，与“批量不改变答案”这条底线冲突，所以：</p>
     * <ol>
     *   <li>一条多列 SQL 覆盖一块，{@code LIMIT = min(query-batch-row-budget,
     *       need × 块内位号数)}——后者才是这批列全部拿满 {@code need} 个点时可能的
     *       最大合并行数。</li>
     *   <li>返回行数 {@code < LIMIT}：窗口内的行已全部拿到，不需要补采。</li>
     *   <li>返回行数 {@code == LIMIT}（撞预算）：该列非空数 {@code >= need} 视为完整；
     *       小于 {@code need} 则尾部未知，对该位号发逐位号 SQL 补齐。</li>
     *   <li>需要补采的列数超过 {@code query-batch-completion-max} 时整块直接退回
     *       逐位号，避免“位号数 × 补采数”的乘积往返。</li>
     * </ol>
     *
     * @param grouping   分组档位
     * @param tags       位号列表，可为空
     * @param need       每位号需要几个点，至少 1
     * @param batchSql   一块一条的批量 SQL 构造器
     * @param perTagSql  逐位号 SQL 的构造器
     * @param completed  输出参数：走过补采（含整块退回）的位号绝对路径
     * @return 位号绝对路径 → 点序列，键覆盖全部入参位号
     */
    Map<String, List<IotdbModels.Point>> rawShapedBatch(Grouping grouping, List<IotdbTagPath> tags,
                                                        int need, RawSqlBuilder batchSql,
                                                        PerTagSqlBuilder perTagSql,
                                                        Set<String> completed) {
        Map<String, List<IotdbModels.Point>> result = new LinkedHashMap<String, List<IotdbModels.Point>>();
        if (tags == null || tags.isEmpty()) {
            return result;
        }
        int required = Math.max(1, need);
        int rowBudget = Math.max(1, config.getQueryBatchRowBudget());
        int completionMax = Math.max(0, config.getQueryBatchCompletionMax());
        List<List<IotdbTagPath>> chunks = chunks(grouping, tags);
        List<String> sqls = new ArrayList<String>(chunks.size());
        List<Integer> limits = new ArrayList<Integer>(chunks.size());
        for (List<IotdbTagPath> chunk : chunks) {
            int limit = unionRowLimit(required, chunk.size(), rowBudget);
            limits.add(limit);
            sqls.add(batchSql.build(grouping, chunk, limit));
        }
        List<IotdbResultTable> tables = queryAll(sqls, config.getQueryParallelism());
        for (int i = 0; i < chunks.size(); i++) {
            List<IotdbTagPath> chunk = chunks.get(i);
            IotdbResultTable table = tables.get(i);
            List<String> keys = new ArrayList<String>(chunk.size());
            for (IotdbTagPath path : chunk) {
                keys.add(path.getKey());
            }
            int[] index = resultColumns.alignColumns(table, keys, null, sqls.get(i));
            // 行数没撞满预算就说明窗口内的行已经全部返回，此时“这一列没几个点”
            // 是真实数据，不是被密集列挤掉的。
            boolean drained = table.getRows().size() < limits.get(i);
            List<Boolean> starved = new ArrayList<Boolean>(chunk.size());
            int starvedCount = 0;
            for (int j = 0; j < chunk.size(); j++) {
                boolean starvedColumn = !drained && filledCells(table, index[j]) < required;
                starved.add(Boolean.valueOf(starvedColumn));
                if (starvedColumn) {
                    starvedCount++;
                }
            }
            boolean wholeChunk = starvedCount > completionMax;
            for (int j = 0; j < chunk.size(); j++) {
                IotdbTagPath path = chunk.get(j);
                if (!wholeChunk && !starved.get(j).booleanValue()) {
                    result.put(path.getAbsolutePath(), nonEmptyColumnSeries(table, index[j]));
                    continue;
                }
                result.put(path.getAbsolutePath(),
                        resultColumns.singleColumn(query(perTagSql.build(path))));
                completed.add(path.getAbsolutePath());
            }
        }
        return result;
    }

    /**
     * 把单个路径文本包成“按设备分组”的批量入参。
     *
     * <p>逐位号公开方法是 {@code unwrap(IotdbQueryService.class)} 的既有入口，
     * SQL 生成统一走 {@code Grouping.DEVICE + 单元素列表}，与改造前按设备分组的
     * 文本逐字节一致。</p>
     *
     * @param path 完整序列路径
     * @return 单元素路径列表
     */
    static List<IotdbTagPath> singlePath(String path) {
        return java.util.Collections.singletonList(IotdbTagPath.ofAbsolute(path));
    }

    /**
     * 生成逐位号原始点分页 SQL：批量形态 + 尾部 {@code OFFSET}。
     *
     * @param path      完整序列路径
     * @param start     起点（含）
     * @param end       终点（不含）
     * @param direction 返回方向
     * @param limit     本页最大点数
     * @param offset    跳过的点数，非正数时不追加 {@code OFFSET}
     * @return 原始点 SELECT 语句
     */
    static String rawPage(String path, long start, long end, Direction direction,
                          int limit, int offset) {
        String sql = IotdbSql.raw(Grouping.DEVICE, singlePath(path), start, end, direction, limit);
        return offset <= 0 ? sql : sql + " OFFSET " + offset;
    }

    /**
     * 按 UTF-8 统计文本字节数，与 REST 通道发出请求时的编码口径一致。
     *
     * @param text 待统计文本，不能为 {@code null}
     * @return UTF-8 字节数
     */
    private static int utf8Bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * 按档位把位号分组，保持首次出现顺序与组内入参顺序。
     *
     * <p>组内按路径对象去重：重复位号合进一条 SQL 只会变长，不会多要回数据，
     * 结果仍按入参逐个回填。</p>
     *
     * @param paths    已解析的位号路径列表
     * @param grouping 分组档位
     * @return 分组键 → 去重后的位号列表
     */
    private static Map<String, List<IotdbTagPath>> groupBy(List<IotdbTagPath> paths, Grouping grouping) {
        Map<String, LinkedHashSet<IotdbTagPath>> grouped =
                new LinkedHashMap<String, LinkedHashSet<IotdbTagPath>>();
        for (IotdbTagPath path : paths) {
            String key = grouping.groupKey(path);
            LinkedHashSet<IotdbTagPath> members = grouped.get(key);
            if (members == null) {
                members = new LinkedHashSet<IotdbTagPath>();
                grouped.put(key, members);
            }
            members.add(path);
        }
        Map<String, List<IotdbTagPath>> result = new LinkedHashMap<String, List<IotdbTagPath>>();
        for (Map.Entry<String, LinkedHashSet<IotdbTagPath>> entry : grouped.entrySet()) {
            result.put(entry.getKey(), new ArrayList<IotdbTagPath>(entry.getValue()));
        }
        return result;
    }

    /**
     * 并行执行若干条 SQL，结果顺序与入参 SQL 一致。
     *
     * @param sqls        待执行的 SQL 列表
     * @param parallelism 并行度上限
     * @return 与 {@code sqls} 同序的结果表列表
     */
    private List<IotdbResultTable> queryInParallel(List<String> sqls, int parallelism) {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(parallelism, sqls.size()));
        try {
            List<Future<IotdbResultTable>> futures = new ArrayList<Future<IotdbResultTable>>(sqls.size());
            for (final String sql : sqls) {
                futures.add(pool.submit(new Callable<IotdbResultTable>() {
                    @Override
                    public IotdbResultTable call() {
                        return query(sql);
                    }
                }));
            }
            List<IotdbResultTable> tables = new ArrayList<IotdbResultTable>(futures.size());
            for (Future<IotdbResultTable> future : futures) {
                try {
                    tables.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new TsdbQueryException("interrupted while reading IoTDB", e);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException) {
                        throw (RuntimeException) cause;
                    }
                    throw new TsdbQueryException("failed to read IoTDB", cause);
                }
            }
            return tables;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 合并行数预算：够让块内每一列都取满 {@code need} 个点，同时不超过服务端行上限。
     *
     * @param need       每位号需要的点数
     * @param columns    块内列数
     * @param rowBudget  {@code query-batch-row-budget}
     * @return 单条批量 SQL 的 {@code LIMIT}
     */
    private static int unionRowLimit(int need, int columns, int rowBudget) {
        long upper = (long) Math.max(1, need) * (long) Math.max(1, columns);
        return (int) Math.max(1L, Math.min((long) rowBudget, upper));
    }

    /**
     * 统计某一列里非空单元格的个数，用于判断该位号是否被行数预算挤掉了点。
     *
     * @param table 结果表
     * @param index 列下标，{@code -1} 表示整列不存在
     * @return 非空单元格数
     */
    private static int filledCells(IotdbResultTable table, int index) {
        if (index < 0) {
            return 0;
        }
        int count = 0;
        for (IotdbResultTable.Row row : table.getRows()) {
            Object[] values = row.getValues();
            if (index < values.length && values[index] != null) {
                count++;
            }
        }
        return count;
    }

    /**
     * 读取某一列的非空点序列（RAW 形态专用）。
     *
     * <p>多列 RAW 的合并时间戳行上，本列没有点的行值是 {@code null}，这些行必须
     * 丢掉——逐位号查询根本不会返回它们。{@code GROUP BY} 族相反：空桶的
     * {@code null} 是答案的一部分，那里用 {@link ResultColumns#columnSeries}。</p>
     *
     * @param table 结果表
     * @param index 列下标，{@code -1} 表示整列不存在
     * @return 点列表；列缺失时为空列表
     */
    private List<IotdbModels.Point> nonEmptyColumnSeries(IotdbResultTable table, int index) {
        List<IotdbModels.Point> points = new ArrayList<IotdbModels.Point>();
        if (index < 0) {
            return points;
        }
        for (IotdbResultTable.Row row : table.getRows()) {
            Object[] values = row.getValues();
            if (index >= values.length || values[index] == null) {
                continue;
            }
            points.add(resultColumns.point(row.getTimestamp(), values[index]));
        }
        return points;
    }

    /**
     * 一条 SQL 里一个位号发射几列的构造回调。
     */
    interface SqlBuilder {

        /**
         * 构造覆盖这一块位号的 SQL。
         *
         * @param grouping 分组档位
         * @param chunk    本块位号，同属一个分组
         * @return 单条 SQL
         */
        String build(Grouping grouping, List<IotdbTagPath> chunk);
    }

    /**
     * RAW 形态批量 SQL 的构造回调：除本块位号外还要拿到本块的合并行数预算。
     */
    interface RawSqlBuilder {

        /**
         * 构造覆盖这一块位号的批量 SQL。
         *
         * @param grouping 分组档位
         * @param chunk    本块位号，同属一个分组
         * @param limit    本条语句的合并行数上限
         * @return 单条 SQL
         */
        String build(Grouping grouping, List<IotdbTagPath> chunk, int limit);
    }

    /**
     * 逐位号补采 SQL 的构造回调，产出的文本必须与改造前的逐位号查询一致。
     */
    interface PerTagSqlBuilder {

        /**
         * 构造单个位号的逐位号 SQL。
         *
         * @param path 位号路径
         * @return 单条 SQL
         */
        String build(IotdbTagPath path);
    }
}