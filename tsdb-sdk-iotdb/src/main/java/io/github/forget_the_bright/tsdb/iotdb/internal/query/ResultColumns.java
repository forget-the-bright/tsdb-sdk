package io.github.forget_the_bright.tsdb.iotdb.internal.query;

import io.github.forget_the_bright.tsdb.common.config.TimeConfig;
import io.github.forget_the_bright.tsdb.common.exception.TsdbQueryException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import io.github.forget_the_bright.tsdb.iotdb.internal.IotdbModels;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.IotdbTagPath;
import io.github.forget_the_bright.tsdb.iotdb.internal.transport.IotdbResultTable;

/**
 * IoTDB 结果表的列定位与读取（K2 对齐内核）。
 *
 * <p>从 {@link IotdbQueryService} 拆出，单独承载“把服务端返回的结果表按列名
 * 定位到发射表达式、再读成点”这一件事。服务端三条反直觉行为决定了这里的规则：
 * 多列族的列序等于请求顺序但列名带规范绝对路径，不存在的位号会让整列消失，
 * 因此一律<b>按名定位</b>而不是按位置就近对齐；列数与发射表达式数不符就抛
 * {@link TsdbQueryException}，绝不静默把别人的值挂到当前位号名下。</p>
 *
 * <p>本类是无状态工具（仅持有时间配置用于构造点），不发起任何 SQL，也不做
 * 数值推导——后者在 {@link PointMath}。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
final class ResultColumns {

    private final TimeConfig timeConfig;

    /**
     * 创建列读取器。
     *
     * @param timeConfig 实例时间配置，用于构造结果点，不能为 {@code null}
     */
    ResultColumns(TimeConfig timeConfig) {
        this.timeConfig = timeConfig;
    }

    /**
     * 按列名把结果表对齐到发射表达式，返回与 {@code keys} 同序的列下标。
     *
     * <p>判据是“列名规范化后是否带位号身份（以 {@code root} 锚点开头）”：
     * 全部带路径时按名 + 函数定位；一个路径都没有时退回位置判定，但宽度
     * 必须恰等于发射表达式数（{@code getColumns()} 不含时间列，判据是相等
     * 而不是加一），否则抛。抛出的异常带上 SQL 与期望/实际列名，便于现场核对。</p>
     *
     * @param table     结果表，非 {@code null}
     * @param keys      发射列的规范路径键，按发射顺序
     * @param functions 发射列期望的函数名，与 {@code keys} 同序，全为 {@code null} 时不校验函数
     * @param sql       本条 SQL，仅用于异常信息
     * @return 与 {@code keys} 同序的列下标，元素为 {@code -1} 表示该列未返回
     */
    int[] alignColumns(IotdbResultTable table, List<String> keys, List<String> functions, String sql) {
        List<String> labels = table.getColumns();
        int[] index = new int[keys.size()];
        Arrays.fill(index, -1);
        if (labels.isEmpty() && table.getRows().isEmpty()) {
            return index;
        }
        int width = labels.isEmpty() ? table.getRows().get(0).getValues().length : labels.size();
        List<String> pathKeys = new ArrayList<String>(labels.size());
        int named = 0;
        for (String label : labels) {
            String key = pathKey(label);
            pathKeys.add(key);
            if (key == null) {
                continue;
            }
            named++;
            if (!keys.contains(key)) {
                throw new TsdbQueryException("IoTDB result carries an unrequested series column '" + label
                        + "', expected one of " + keys + ", sql=" + sql);
            }
        }
        if (named == 0) {
            if (width != keys.size()) {
                throw new TsdbQueryException("IoTDB result carries no usable column names and its width ("
                        + width + ") does not match the " + keys.size() + " emitted expressions, sql=" + sql);
            }
            for (int i = 0; i < index.length; i++) {
                index[i] = i;
            }
            return index;
        }
        if (named != labels.size()) {
            throw new TsdbQueryException("IoTDB result column names are incomplete (" + named + " of "
                    + labels.size() + " carrying a series path, " + keys.size() + " emitted), sql=" + sql);
        }
        boolean[] consumed = new boolean[labels.size()];
        for (int i = 0; i < keys.size(); i++) {
            String function = functions == null ? null : functions.get(i);
            for (int c = 0; c < labels.size(); c++) {
                if (consumed[c] || !keys.get(i).equals(pathKeys.get(c))) {
                    continue;
                }
                if (function != null && !function.equalsIgnoreCase(columnFunction(labels.get(c)))) {
                    continue;
                }
                index[i] = c;
                consumed[c] = true;
                break;
            }
        }
        return index;
    }

    /**
     * 把列名规范化为可比较的绝对路径键，不含路径的列名返回 {@code null}。
     *
     * <p>实测三通道的数据列名形如 {@code last_value(root.db.device.measurement)} 或裸
     * {@code root.db.device.measurement}，因此“规范化后是否以 {@code root} 锚点开头”
     * 足以区分“这列带着位号身份”与“这列只有一个函数名/别名”。只有前者能用于按名定位，
     * 后者必须退回位置判定，不能因为字符串非空就当成请求过的序列。</p>
     *
     * @param label 列名，可为 {@code null}
     * @return 规范绝对路径键；不含路径时 {@code null}
     */
    private static String pathKey(String label) {
        String key = IotdbTagPath.normalizeKey(label);
        return key != null && key.startsWith(IotdbTagPath.ROOT_ANCHOR + ".") ? key : null;
    }

    /**
     * 把同一个期望函数名展开成与发射列数同长的列表。
     *
     * <p>{@link #alignColumns} 的 {@code functions} 是“每列一个”的判定表，
     * 而“整批都用同一个函数”的族（基线取 {@code LAST_VALUE}）只有一个函数名，
     * 因此需要按列展开，不能直接把单元素列表传进去。</p>
     *
     * @param function 函数名，如 {@code LAST_VALUE}
     * @param times    发射列数
     * @return 长度 {@code times} 的列表
     */
    List<String> repeat(String function, int times) {
        List<String> functions = new ArrayList<String>(times);
        for (int i = 0; i < times; i++) {
            functions.add(function);
        }
        return functions;
    }

    /**
     * 取列名里的函数名部分，无括号时返回 {@code null}。
     *
     * @param label 列名，可为 {@code null}
     * @return 函数名原文或 {@code null}
     */
    private static String columnFunction(String label) {
        if (label == null) {
            return null;
        }
        int open = label.indexOf('(');
        return open < 0 ? null : label.substring(0, open).trim();
    }

    /**
     * 读取结果表里某一列的全部点。
     *
     * @param table 结果表
     * @param index {@link #alignColumns} 给出的列下标，{@code -1} 表示该列不存在
     * @return 点列表；列缺失时为空列表
     */
    List<IotdbModels.Point> columnSeries(IotdbResultTable table, int index) {
        List<IotdbModels.Point> points = new ArrayList<IotdbModels.Point>();
        if (index < 0) {
            return points;
        }
        for (IotdbResultTable.Row row : table.getRows()) {
            Object[] values = row.getValues();
            points.add(point(row.getTimestamp(), index >= values.length ? null : values[index]));
        }
        return points;
    }

    /**
     * 读取结果表的第一列。
     *
     * <p><b>只适用于逐位号（单列）查询</b>：多列批量结果里第 0 列属于哪一个位号
     * 完全由服务端返回的列名决定，按位置读就是把不确定的序列挂到当前位号名下，
     * 因此批量一律走 {@link #columnSeries(IotdbResultTable, int)}。</p>
     *
     * @param table 单列结果表
     * @return 点列表，非 {@code null}
     */
    List<IotdbModels.Point> singleColumn(IotdbResultTable table) {
        List<IotdbModels.Point> points = new ArrayList<IotdbModels.Point>();
        for (IotdbResultTable.Row row : table.getRows()) {
            Object value = row.getValues().length == 0 ? null : row.getValues()[0];
            points.add(point(row.getTimestamp(), value));
        }
        return points;
    }

    /**
     * 用实例时间配置构造一个结果点。
     *
     * @param timestamp epoch 毫秒
     * @param value     原始值，可为 {@code null}
     * @return 结果点
     */
    IotdbModels.Point point(long timestamp, Object value) {
        return new IotdbModels.Point(Instant.ofEpochMilli(timestamp), value, timeConfig);
    }

    /**
     * 按列名取值列下标，找不到时退回给定兜底下标。
     *
     * @param table    结果表
     * @param name     列名（忽略大小写）
     * @param fallback 找不到时的兜底下标
     * @return 列下标
     */
    int valueColumn(IotdbResultTable table, String name, int fallback) {
        int index = column(table, name, -1);
        return index < 0 ? fallback : index;
    }

    /**
     * 按列名（忽略大小写）找列下标，找不到时返回兜底值。
     *
     * @param table    结果表
     * @param name     列名
     * @param fallback 找不到时的返回值
     * @return 列下标或 {@code fallback}
     */
    int column(IotdbResultTable table, String name, int fallback) {
        for (int i = 0; i < table.getColumns().size(); i++) {
            if (name.equalsIgnoreCase(table.getColumns().get(i))) {
                return i;
            }
        }
        return fallback;
    }

    /**
     * 取某行指定下标的文本值，越界返回 {@code null}。
     *
     * @param values 行值数组
     * @param index  下标
     * @return 文本值或 {@code null}
     */
    String value(Object[] values, int index) {
        return index < 0 || index >= values.length ? null : text(values[index]);
    }

    /**
     * 把原始值转文本，{@code null} 原样返回。
     *
     * @param value 原始值，可为 {@code null}
     * @return 文本或 {@code null}
     */
    String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
