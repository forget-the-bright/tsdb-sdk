package io.github.forget_the_bright.tsdb.iotdb.internal.sql;

import io.github.forget_the_bright.tsdb.common.exception.TsdbDataException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException;
import io.github.forget_the_bright.tsdb.common.model.Aggregation;
import io.github.forget_the_bright.tsdb.common.model.Direction;
import io.github.forget_the_bright.tsdb.common.model.FillPolicy;

import java.util.List;
import java.util.regex.Pattern;

/**
 * IoTDB 树模型 SQL 构造器。
 *
 * <p>标签路径遵循 {@code root.<database>.<device...>.<measurement>} 约定：数据库
 * 固定两级，最后一个节点是测点，其余是设备层级。层级切分、字符白名单与标识符引用
 * 全部在 {@link IotdbTagPath} 完成，本类只接已解析的路径对象，不再对裸字符串做第二
 * 遍校验（改造前三处各校验一次，同一字符串被重复处理）。需要逐位号形态时由调用方
 * 传 {@code Grouping.DEVICE + 单元素列表}，SQL 生成只有这一套入口。</p>
 *
 * <p><b>为什么必须逐节点加反引号</b>：现场位号里 {@code -} 极其常见（铜陵业务
 * 891 个字面位号中 779 个含连字符，如 {@code BGZH1A.X-PV}），IoTDB 会把裸
 * {@code X-PV} 解析成“减法表达式”，直接报语法错误；纯数字节点（如 {@code 101}）
 * 也会被解析器拒绝。逐节点反引号引用是这两类位号可用<b>前提</b>，不是可选优化。</p>
 *
 * <p><b>为什么首节点 {@code root} 不加引号</b>：IoTDB 2.0 树模型的解析器把
 * {@code root} 当作路径锚点保留字，实测 {@code FROM `root`.`factory`} 报
 * {@code no viable alternative at input}，而 {@code root.`factory`} 正常。</p>
 *
 * <p><b>批量形态与层级配对</b>：一条语句覆盖多个位号时，{@code FROM} 前缀与所有列
 * 表达式必须由同一个 {@link Grouping} 档位派生（{@code DATABASE} / {@code ROOT} /
 * {@code DEVICE}）。2026-11-13 生产 2.0.11 实测：层级错配<b>不报错、只静默返回空</b>，
 * 表达式里带 {@code root.} 会被 {@code code=701} 拒绝。因此本类的每个批量入口都只接
 * 路径对象列表，并先调用 {@link Grouping#requireSingleGroup(List)} 确认这一组同属一个
 * 分组——错配在客户端就变成异常，不可能表现为“查出来是空的”。</p>
 *
 * <p><b>安全边界</b>：路径节点白名单在 {@link IotdbTagPath}；掩码白名单在本类
 * （{@link #isValidMask(String)}，通配符只能出现在掩码里）。反引号本身、引号、分号、
 * 反斜杠、空白与控制字符一律拒绝，因此转义不会被闭合，也就无法逃逸出标识符位置。</p>
 *
 * <p>本类只生成 SQL，不发起网络请求，便于单元测试和审计。</p>
 */
public final class IotdbSql {

    /**
     * 标签掩码白名单：在路径字符基础上允许 {@code *} 与 {@code ?} 通配符。
     *
     * <p>路径字符集与 {@link IotdbTagPath} 保持一致（字母含中文、数字、
     * {@code _ : - + / # % @ ( ) [ ] ~} 与点）。引号、分号、空白和其他 SQL 元字符
     * 一律拒绝：掩码会被直接拼进 {@code SHOW TIMESERIES}，这里是安全边界。</p>
     */
    private static final Pattern MASK = Pattern.compile("[\\p{L}\\p{N}_.:*?\\-+/#%@()\\[\\]~]+");

    /** IoTDB 时间间隔字面量：数值 + 单位（{@code ms/s/m/h/d/mo}）。 */
    private static final Pattern INTERVAL_LITERAL =
            Pattern.compile("\\d+(?:ms|mo|s|m|h|d)", Pattern.CASE_INSENSITIVE);

    private IotdbSql() {
    }

    /**
     * 判断标签掩码是否可以安全地拼入 {@code SHOW TIMESERIES}。
     *
     * @param mask 标签掩码，例如 {@code root.factory.**}
     * @return {@code true} 表示只包含白名单字符
     */
    public static boolean isValidMask(String mask) {
        return mask != null && MASK.matcher(mask).matches();
    }

    /**
     * 生成“批量查询最新值”SQL：{@code SELECT LAST <表达式…> FROM <分组前缀>}。
     *
     * <p>库级形态（默认档）就是业务侧要的写法：
     * {@code SELECT LAST `RL`.`FCS0107`.`TI-1071815_PV`, `RL`.`FCS0107`.`TI-1071816_PV`
     * FROM root.`factory`}。业务最新值接口单请求位号数达 248～345 个且通常同属一个
     * 数据库，逐个 {@code SELECT LAST} 会产生同等数量的往返，是本方案要消除的主要
     * 性能缺口。</p>
     *
     * <p><b>结果对齐方式由调用方负责</b>：该语句返回 {@code Timeseries}/{@code Value}
     * /{@code DataType} 三列，<b>行序不保证等于入参顺序</b>（实测 200 表达式的跨库
     * 语句乱序返回），且不存在的序列会被整行丢弃而不是补空。必须按 {@code Timeseries}
     * 建索引再按入参顺序回填，缺失的位号保留 {@code null} 占位。</p>
     *
     * @param grouping 分组档位，同一批位号必须同属一个分组
     * @param paths    该分组下的位号路径，不能为空
     * @return 单条 SELECT LAST 语句
     * @throws TsdbDataException 列表为空、组内跨分组，或档位与分组前缀不匹配
     */
    public static String currentBatch(Grouping grouping, List<IotdbTagPath> paths) {
        grouping.requireSingleGroup(paths);
        return "SELECT LAST " + expressions(grouping, paths) + " FROM " + grouping.fromPrefix(paths);
    }

    /**
     * 生成原始点查询 SQL（多列批量形态）。
     *
     * <p>时间区间为左闭右开 {@code [startMillis, endMillis)}，与公共层
     * {@code HistoryQuery} 的语义一致；方向为 {@link Direction#BACKWARD} 时
     * 使用 {@code ORDER BY TIME DESC}，否则升序。{@code limit} 至少为 1。</p>
     *
     * <p><b>{@code LIMIT} 是“合并时间戳行数”的预算，不是每位号的点数</b>：实测同窗
     * 4 个序列 {@code LIMIT 10} 时，小时级序列只活了 1 个点，而密集序列占满 10 行。
     * 因此直接批量会<b>改变结果</b>，调用方必须按 {@code IotdbClient} 的补采流程
     * 对不足列逐位号补齐，否则就是把稀疏位号的点静默丢掉。</p>
     *
     * @param grouping    分组档位
     * @param paths       同一分组下的位号，不能为空
     * @param startMillis 起始时间（epoch 毫秒，含）
     * @param endMillis   结束时间（epoch 毫秒，不含）
     * @param direction   返回顺序
     * @param limit       本条语句最多返回的行数（合并时间戳后的行数）
     * @return 原始点 SELECT 语句
     */
    public static String raw(Grouping grouping, List<IotdbTagPath> paths, long startMillis, long endMillis,
                             Direction direction, int limit) {
        grouping.requireSingleGroup(paths);
        return "SELECT " + expressions(grouping, paths)
                + " FROM " + grouping.fromPrefix(paths)
                + " WHERE time >= " + startMillis + " AND time < " + endMillis
                + " ORDER BY TIME " + (direction == Direction.BACKWARD ? "DESC" : "ASC")
                + " LIMIT " + Math.max(1, limit);
    }

    /**
     * 生成“取某时刻之前/之后最近若干个原始点”的 SQL（多列批量形态）。
     *
     * <p>插值的网格槽位需要真实采样时刻来计算线性权重：{@code LAST_VALUE(m)} 这类
     * 聚合返回的时间列是窗口边界而不是采样时刻，不能用于插值。因此取包围点必须走
     * 原始点查询，只省略一侧的时间边界。{@code LIMIT} 与 {@link #raw(Grouping, List,
     * long, long, Direction, int)} 同样是合并按行数，需要补采。</p>
     *
     * @param grouping   分组档位
     * @param paths      同一分组下的位号，不能为空
     * @param timeMillis 锚点时刻（epoch 毫秒，不含该时刻本身）
     * @param direction  {@link Direction#BACKWARD} 取之前最近的点，
     *                   {@link Direction#FORWARD} 取之后最近的点
     * @param limit      每条最多返回几个点（合并行数），至少 1
     * @return 单侧包围点 SELECT 语句
     */
    public static String nearest(Grouping grouping, List<IotdbTagPath> paths, long timeMillis,
                                 Direction direction, int limit) {
        grouping.requireSingleGroup(paths);
        boolean backward = direction == Direction.BACKWARD;
        return "SELECT " + expressions(grouping, paths)
                + " FROM " + grouping.fromPrefix(paths)
                + " WHERE time " + (backward ? "< " : "> ") + timeMillis
                + " ORDER BY TIME " + (backward ? "DESC" : "ASC")
                + " LIMIT " + Math.max(1, limit);
    }

    /**
     * 生成区间聚合查询 SQL（多列批量形态）。
     *
     * <p>在 {@code [startMillis, endMillis)} 上按固定窗口分组，聚合函数由
     * {@link Aggregation} 映射为 IoTDB 内置函数；整批位号共用一个聚合函数，
     * 每个位号一列。</p>
     *
     * <p><b>行数只等于桶数，与列数无关</b>（实测 64 列 × 24 桶 = 24 行；
     * 3 列 × 7 天 × 30 s 与单列一样撞 {@code code=708} 的 10 000 行上限），
     * 所以批量不会消耗额外的行预算，截断口径与逐位号一致。</p>
     *
     * <p>{@link Aggregation#INTERVAL_SUM} 不走本方法：它按公共层契约是“整窗末值
     * 减首值”的单点增量，必须用 {@link #intervalSum(Grouping, List, long, long)}
     * 的双列专用 SQL 再由调用方相减；按桶的累计增量是 {@code DeltaReader} 的
     * “相邻桶末值差 + 基线”，两者语义不同，不得互相替代。</p>
     *
     * @param grouping       分组档位
     * @param paths          同一分组下的位号，不能为空
     * @param aggregation    聚合方式
     * @param startMillis    起始时间（epoch 毫秒，含）
     * @param endMillis      结束时间（epoch 毫秒，不含）
     * @param intervalMillis 分组窗口长度（毫秒）
     * @param direction      返回顺序
     * @param limit          最大返回行数
     * @return 聚合 SELECT 语句
     * @throws TsdbDataException        聚合方式为空或不支持
     * @throws TsdbUnsupportedException 聚合方式为 {@code INTERVAL_SUM}
     */
    public static String aggregated(Grouping grouping, List<IotdbTagPath> paths, Aggregation aggregation,
                                    long startMillis, long endMillis, long intervalMillis,
                                    Direction direction, int limit) {
        grouping.requireSingleGroup(paths);
        String function = function(aggregation);
        return "SELECT " + expressions(grouping, paths, function)
                + " FROM " + grouping.fromPrefix(paths)
                + " WHERE time >= " + startMillis + " AND time < " + endMillis
                + " GROUP BY ([" + startMillis + "," + endMillis + ")," + intervalLiteral(intervalMillis) + ")"
                + " ORDER BY TIME " + (direction == Direction.BACKWARD ? "DESC" : "ASC")
                + " LIMIT " + Math.max(1, limit);
    }

    /**
     * 生成按自然粒度分桶的聚合 SQL（多列批量形态）。
     *
     * <p>IoTDB 2.0.11 树模型不支持 {@code date_bin}/{@code sample}，只能用
     * {@code GROUP BY ([start,end), interval)}；月粒度使用 {@code 1mo}，
     * 起点必须已归整到自然月。粒度字面量不限于 {@code 1s/1m/1h/1d}：
     * 现场需要 {@code 5m}、{@code 30m} 与 480 点等分网格，因此接受任意
     * {@code <数值><单位>} 形式。</p>
     *
     * <p>{@code FILL} 按列独立填充，不会把一个位号的末值填进另一个位号的空桶
     *（实测 {@code FILL(PREVIOUS)} 三列中两个无数据列为空）。</p>
     *
     * @param grouping    分组档位
     * @param paths       同一分组下的位号，不能为空
     * @param aggregation 聚合方式
     * @param startMillis 起始时间（含）
     * @param endMillis   结束时间（不含）
     * @param literal     时间粒度字面量，如 {@code 1h}、{@code 5m}、{@code 1mo}
     * @param fill        空桶填充策略
     * @return 分桶聚合 SQL
     * @throws TsdbDataException        粒度字面量非法、填充策略不支持
     * @throws TsdbUnsupportedException 聚合方式为 {@code INTERVAL_SUM}
     */
    public static String interval(Grouping grouping, List<IotdbTagPath> paths, Aggregation aggregation,
                                  long startMillis, long endMillis, String literal, FillPolicy fill) {
        grouping.requireSingleGroup(paths);
        String function = function(aggregation);
        StringBuilder sql = new StringBuilder()
                .append("SELECT ").append(expressions(grouping, paths, function))
                .append(" FROM ").append(grouping.fromPrefix(paths))
                .append(" WHERE time >= ").append(startMillis)
                .append(" AND time < ").append(endMillis)
                .append(" GROUP BY ([").append(startMillis).append(',').append(endMillis)
                .append("),").append(checkedIntervalLiteral(literal)).append(')');
        if (fill != null && fill != FillPolicy.NONE) {
            switch (fill) {
                case PREVIOUS:
                    sql.append(" FILL(PREVIOUS)");
                    break;
                case LINEAR:
                    sql.append(" FILL(LINEAR)");
                    break;
                case NULL:
                    sql.append(" FILL(NULL)");
                    break;
                default:
                    throw new TsdbDataException("IoTDB does not support fill policy " + fill);
            }
        }
        return sql.toString();
    }

    /**
     * 生成“整窗累计增量”SQL（多列批量形态）：每个位号一对 {@code first_value}/{@code last_value}。
     *
     * <p>对应 GE 侧 {@code getIntervalValueBySumTag} 的真实算法——它对整窗各取一次
     * {@code FirstRawValue}/{@code LastRawValue}（{@code count=1}）再相减，<b>不是</b>
     * 分桶差分。列顺序为位号顺序 × ｛first_value, last_value｝，即第 {@code 2j} 列是
     * 第 {@code j} 个位号的首值、第 {@code 2j+1} 列是它的末值，由调用方在内存里用
     * {@code BigDecimal} 相减。</p>
     *
     * <p><b>不带 {@code GROUP BY} 时只返回 1 个逻辑行</b>（实测 6 列 1 行、
     * {@code timestamps} 为空数组），所以整批一次往返即可，不受行数上限影响。</p>
     *
     * <p>列定位按列名括号内的规范绝对路径（实测三通道列名一致，形如
     * {@code first_value(root.db.device.measurement)}），函数名大小写原样回显，
     * 比对时忽略；位置匹配只在列数等于发射表达式数时作为兜底。</p>
     *
     * <p><b>已知差异</b>：GE 的 first/last 只统计质量为 GOOD 的采样点，IoTDB 的
     * {@code first_value}/{@code last_value} 没有质量码概念，坏值会以合法数值参与
     * 计算并锚定首末读数。该差异属底层数据层，无法通过调容差消除。</p>
     *
     * @param grouping    分组档位
     * @param paths       同一分组下的位号，不能为空
     * @param startMillis 起始时间（含）
     * @param endMillis   结束时间（不含）
     * @return 单条取首末值的 SELECT 语句
     */
    public static String intervalSum(Grouping grouping, List<IotdbTagPath> paths,
                                     long startMillis, long endMillis) {
        grouping.requireSingleGroup(paths);
        StringBuilder columns = new StringBuilder();
        for (IotdbTagPath path : paths) {
            String expression = grouping.expression(path);
            if (columns.length() > 0) {
                columns.append(", ");
            }
            columns.append("first_value(").append(expression).append("), last_value(")
                    .append(expression).append(')');
        }
        return "SELECT " + columns
                + " FROM " + grouping.fromPrefix(paths)
                + " WHERE time >= " + startMillis + " AND time < " + endMillis;
    }

    /**
     * 生成查询起点之前最近若干个末值的 SQL（差分基线，多列批量形态）。
     *
     * <p>实测不存在的位号会被<b>整列丢弃</b>（请求 2 列返回 1 列），而“序列存在但
     * 起点之前没有点”会保留空列。两种情况在结果表里形态不同，因此调用方必须按列名
     * 定位而不是按位置读，否则一个不存在的位号会把后面所有位号的基线串过来。</p>
     *
     * @param grouping    分组档位
     * @param paths       同一分组下的位号，不能为空
     * @param startMillis 查询起点（不含）
     * @return 基线 SELECT 语句
     */
    public static String baseline(Grouping grouping, List<IotdbTagPath> paths, long startMillis) {
        grouping.requireSingleGroup(paths);
        return "SELECT " + expressions(grouping, paths, "LAST_VALUE")
                + " FROM " + grouping.fromPrefix(paths)
                + " WHERE time < " + startMillis;
    }

    /**
     * 生成按前缀列出时间序列的 SQL（掩码规则的唯一出处）。
     *
     * <p>前缀带通配符，必须保持裸写：反引号内的 {@code *} 会退化成字面量，
     * 导致 {@code SHOW TIMESERIES} 返回空。</p>
     *
     * <p>调用方只传“前缀或掩码原文”，补通配符由本方法负责：已带通配符的掩码
     * 原样使用，不带通配符的前缀补 {@code .**}（列该前缀下的全部序列）。
     * {@code IotdbClient} 的 REST 直连分支与统一查询服务分支因此走同一份规则，
     * 不必在各自调用点手拼 {@code ".**"}——手拼的两处只要有一处口径不同，
     * 同一个 {@code TagQuery} 在两种装配下就会列出不同的位号。</p>
     *
     * @param prefix 设备或数据库前缀（如 {@code root.factory}），或已带通配符的掩码
     * @return SHOW TIMESERIES 语句
     */
    public static String showTimeseries(String prefix) {
        String normalized = prefix.indexOf('*') >= 0 ? prefix : prefix + ".**";
        if (!isValidMask(normalized)) {
            throw new TsdbDataException("invalid IoTDB tag prefix: " + prefix);
        }
        return "SHOW TIMESERIES " + normalized;
    }

    /**
     * 把毫秒窗口长度换算成 IoTDB 间隔字面量。
     *
     * <p>整除到分钟/秒的窗口优先用自然单位（{@code 5m}、{@code 30s}），其余
     * 用毫秒形式。裸毫秒数字（如 {@code 86400000}）在 2.0.11 会被解析器拒绝，
     * 必须带单位，因此这里统一补 {@code ms}。</p>
     *
     * <p><b>实测依据</b>（2026-11-13，生产 2.0.11 REST，只读）：
     * {@code 5m}、{@code 30s}、{@code 300000ms}、{@code 250ms} 与 {@code 1mo}
     * 都被服务端接受并正常分桶，裸 {@code 300000} 报
     * {@code no viable alternative}。因此等间隔查询的粒度<b>不必</b>限制在
     * {@code 1s/1m/1h/1d}，现场的 5 分钟曲线与 480 点等分网格可以直接下推。</p>
     *
     * @param intervalMillis 窗口长度（毫秒），至少 1
     * @return 形如 {@code 5m}、{@code 1500ms} 的字面量
     */
    public static String intervalLiteral(long intervalMillis) {
        long millis = Math.max(1L, intervalMillis);
        if (millis % 86_400_000L == 0) {
            return millis / 86_400_000L + "d";
        }
        if (millis % 3_600_000L == 0) {
            return millis / 3_600_000L + "h";
        }
        if (millis % 60_000L == 0) {
            return millis / 60_000L + "m";
        }
        if (millis % 1_000L == 0) {
            return millis / 1_000L + "s";
        }
        return millis + "ms";
    }

    /**
     * 把同一档位的列表达式按 {@code ", "} 拼接。
     *
     * @param grouping 分组档位
     * @param paths    同一分组下的位号
     * @return 逗号分隔的表达式列表
     */
    private static String expressions(Grouping grouping, List<IotdbTagPath> paths) {
        StringBuilder columns = new StringBuilder();
        for (IotdbTagPath path : paths) {
            if (columns.length() > 0) {
                columns.append(", ");
            }
            columns.append(grouping.expression(path));
        }
        return columns.toString();
    }

    /**
     * 把每个位号的表达式包进同一个聚合函数后按 {@code ", "} 拼接。
     *
     * @param grouping  分组档位
     * @param paths     同一分组下的位号
     * @param function  IoTDB 聚合函数名
     * @return 逗号分隔的函数调用列表
     */
    private static String expressions(Grouping grouping, List<IotdbTagPath> paths, String function) {
        StringBuilder columns = new StringBuilder();
        for (IotdbTagPath path : paths) {
            if (columns.length() > 0) {
                columns.append(", ");
            }
            columns.append(function).append('(').append(grouping.expression(path)).append(')');
        }
        return columns.toString();
    }

    /**
     * 校验调用方传入的粒度字面量。
     *
     * <p>只接受 {@code <数值><单位>}，单位限 {@code ms/s/m/h/d/mo}；其他形式
     * （含空白、引号、逗号）在进入 SQL 前拒绝。</p>
     *
     * @param literal 粒度字面量
     * @return 原字面量
     * @throws TsdbDataException 字面量为空或形式非法
     */
    private static String checkedIntervalLiteral(String literal) {
        if (literal == null || !INTERVAL_LITERAL.matcher(literal).matches()) {
            throw new TsdbDataException("invalid IoTDB interval literal: " + literal);
        }
        return literal;
    }

    /**
     * 把公共层聚合枚举映射为 IoTDB 内置函数名。
     *
     * @param aggregation 公共层聚合方式
     * @return IoTDB SQL 函数名
     * @throws TsdbDataException        聚合为空，或该聚合在 IoTDB 中没有对应单函数
     * @throws TsdbUnsupportedException 聚合为 {@link Aggregation#INTERVAL_SUM}
     *                                  ——它需要 {@link #intervalSum(Grouping, List, long, long)}
     *                                  的双列专用 SQL 再由调用方相减，不能用单条聚合函数表达，
     *                                  显式拒绝，绝不静默按 {@code SUM} 处理
     */
    private static String function(Aggregation aggregation) {
        if (aggregation == null) {
            throw new TsdbDataException("aggregation is required");
        }
        switch (aggregation) {
            case AVG:
                return "AVG";
            case MIN:
                return "MIN_VALUE";
            case MAX:
                return "MAX_VALUE";
            case SUM:
                return "SUM";
            case COUNT:
                return "COUNT";
            case FIRST:
                return "FIRST_VALUE";
            case LAST:
                return "LAST_VALUE";
            case INTERVAL_SUM:
                throw new TsdbUnsupportedException("IoTDB aggregation " + aggregation
                        + " requires IotdbSql.intervalSum() two-column SQL");
            default:
                throw new TsdbDataException("IoTDB does not support aggregation " + aggregation);
        }
    }
}
