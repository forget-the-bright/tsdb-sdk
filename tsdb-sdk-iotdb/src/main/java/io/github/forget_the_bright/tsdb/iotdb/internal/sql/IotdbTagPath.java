package io.github.forget_the_bright.tsdb.iotdb.internal.sql;

import io.github.forget_the_bright.tsdb.common.exception.TsdbDataException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * IoTDB 位号路径值对象：模块内<b>唯一</b>的位号解析与校验入口。
 *
 * <p><b>为什么要有它</b>：IoTDB 的树模型是固定两级数据库
 * （{@code root.<数据库>.<设备…>.<测点>}），而“按数据库批量”要求同一条 SQL 里
 * {@code FROM} 的前缀层级与 SELECT 表达式的层级<b>严格同源</b>：2026-11-13 在
 * 生产 2.0.11 实测（P1/P2 与 docs/14 §1 的 M1）——层级错配不报错，只静默返回空
 * 结果；表达式里带 {@code root.} 则被 {@code code=701} 拒绝。把两侧都从同一个
 * 对象派生，这两类错配就变成结构性不可能，而不是靠调用方各自拼字符串时不出错。</p>
 *
 * <p><b>三态入参</b>（{@link #parse(String, String)}）：</p>
 * <ol>
 *   <li>全名称：首节点是 {@code root}，数据库由路径自己给出，
 *       {@code root.factoryx.d.m} 属于 {@code root.factoryx}，不会被默认库改写；</li>
 *   <li>相对名称：首节点不是 {@code root}，用 {@code options.default-database}
 *       补齐两级库名；没配默认库就报错，绝不猜；</li>
 *   <li>已带反引号：先按 {@link #lex(String)} 拆成节点，再按同一套白名单校验，
 *       最后<b>规范化重发</b>（{@code root} 裸写、其余节点单反引号）。反引号是语法
 *       而不是内容，因此最终上线的文本只可能是本类的输出，注入面与不加引号时相同。</li>
 * </ol>
 *
 * <p><b>派生输出</b>：{@link #quotedDatabase()}（{@code FROM} 用的库级前缀）、
 * {@link #quotedDevicePath()}、{@link #fromDatabase()}（库相对表达式）、
 * {@link #fromRoot()}（跨库单语句用的“库起”表达式）、{@link #fromDevice()}
 * （设备相对表达式，即单个测点）。{@code FROM} 侧与列侧必须由同一档位配对：
 * {@code FROM root.`db`} 配 {@link #fromDatabase()}，{@code FROM root} 配
 * {@link #fromRoot()}，{@code FROM root.`db`.`dev`} 配 {@link #fromDevice()}。</p>
 *
 * <p><b>不支持</b>：节点文本真含反引号的位号（实测没钉过 doubled-backtick
 * {@code ``} 的转义语义，不做读写不对称的猜测）；通配符；少于四段的路径；
 * 未替换的模板占位（{@code ${...}}、{@code #{...}}）；不含任何 ASCII 字母数字的
 * 纯中文占位说明。全部抛 {@link TsdbDataException}，不静默降级。</p>
 *
 * <p><b>不可变且线程安全</b>：所有字段在构造时一次算全，节点数组对外返回副本。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class IotdbTagPath {

    /**
     * 单个路径节点的白名单，与 {@link IotdbSql} 同一套字符集。
     *
     * <p>放行工业位号常见的 {@code - : + / # % @ ( ) [ ] ~} 与中文，风险字符
     * （引号、反引号、分号、反斜杠、空白、控制字符、美元符、花括号、通配符、逗号）
     * 都不在其中，因此校验过的节点被反引号包裹后无法闭合逃逸。</p>
     */
    private static final Pattern NODE = Pattern.compile("[\\p{L}\\p{N}_:\\-+/#%@()\\[\\]~]+");

    /** 不含反引号时的整串白名单（快路径），在节点字符之上允许 {@code .}。 */
    private static final Pattern PATH = Pattern.compile("[\\p{L}\\p{N}_.:\\-+/#%@()\\[\\]~]+");

    /** 树模型锚点：作为首节点时必须裸写，服务端不接受 {@code `root`}。 */
    public static final String ROOT_ANCHOR = "root";

    /** 位号原文长度上限：正常位号远小于此，超限说明传进来的是拼接垃圾。 */
    private static final int MAX_TAG_TEXT_LENGTH = 512;

    /** 数据库层级固定为前两级：{@code root} 与库名。 */
    private static final int DATABASE_LEVELS = 2;

    private final String raw;
    private final String[] nodes;
    private final String absolutePath;
    private final String database;
    private final String devicePath;
    private final String measurement;

    private IotdbTagPath(String raw, String[] nodes) {
        this.raw = raw;
        this.nodes = nodes;
        this.absolutePath = join(nodes);
        this.database = join(Arrays.copyOfRange(nodes, 0, DATABASE_LEVELS));
        this.devicePath = join(Arrays.copyOfRange(nodes, 0, nodes.length - 1));
        this.measurement = nodes[nodes.length - 1];
    }

    /**
     * 解析业务位号。
     *
     * @param tagText         位号原文：全名称、相对名称或已加反引号的名称，不能为空白
     * @param defaultDatabase {@code options.default-database}，形如 {@code root.factory}；
     *                        相对名称缺它时直接报错
     * @return 非 {@code null} 的解析结果
     * @throws TsdbDataException 空文本、超长、引号不闭合、非法字符、通配符、层级不足或缺默认库
     */
    public static IotdbTagPath parse(String tagText, String defaultDatabase) {
        String trimmed = requireText(tagText);
        List<String> nodes = lex(trimmed);
        if (!ROOT_ANCHOR.equals(nodes.get(0))) {
            rejectPlaceholder(trimmed);
            if (defaultDatabase == null) {
                throw new TsdbDataException("IoTDB tag must start with 'root.' or configure "
                        + "options.default-database: " + trimmed);
            }
            List<String> prefix = lex(defaultDatabase);
            if (prefix.size() != DATABASE_LEVELS || !ROOT_ANCHOR.equals(prefix.get(0))) {
                throw new TsdbDataException("options.default-database must be 'root.<database>': "
                        + defaultDatabase);
            }
            nodes.addAll(0, prefix);
        }
        requireDepth(nodes, trimmed);
        return new IotdbTagPath(trimmed, nodes.toArray(new String[nodes.size()]));
    }

    /**
     * 解析已经是全名称的路径，供映射表的值使用。
     *
     * <p>与 {@link #parse(String, String)} 的区别只在缺锚点时的报错文案：映射表里
     * 写相对名称是配置缺陷，必须当场失败，而不是等到运行期发现某个位号查空。</p>
     *
     * @param pathText 形如 {@code root.factory.RL.FCS0107.TI-1071815_PV} 的全名称
     * @return 非 {@code null} 的解析结果
     * @throws TsdbDataException 不是全名称或层级、字符非法
     */
    public static IotdbTagPath ofAbsolute(String pathText) {
        String trimmed = requireText(pathText);
        List<String> nodes = lex(trimmed);
        if (!ROOT_ANCHOR.equals(nodes.get(0))) {
            throw new TsdbDataException("IoTDB tag mapping value must be a full 'root.' path: " + trimmed);
        }
        requireDepth(nodes, trimmed);
        return new IotdbTagPath(trimmed, nodes.toArray(new String[nodes.size()]));
    }

    /**
     * 把位号原文拆成规范节点列表。
     *
     * <p><b>快路径</b>：不含反引号时整串过 {@link #PATH} 白名单再按点切分，与本轮
     * 改造前的行为逐字节一致。<b>含反引号</b>时逐节点扫描，规则是：</p>
     * <ul>
     *   <li>遇 {@code `} 必须找到闭引号，否则报“反引号未闭合”；</li>
     *   <li>闭引号后只能是 {@code .} 或串尾——这一条让 {@code `a``b`} 成为硬错误，
     *       而不是被静默合并成 {@code ab}；</li>
     *   <li>裸节点遇到 {@code .} 或反引号结束，裸节点后紧跟反引号（缺分隔点）报错；</li>
     *   <li>每个节点非空且过 {@link #NODE} 白名单，空段（首尾点、连续点）报错。</li>
     * </ul>
     *
     * @param text 已 trim 的位号原文
     * @return 有序节点列表，至少一个节点
     * @throws TsdbDataException 违反上述任一条
     */
    static List<String> lex(String text) {
        if (text.length() > MAX_TAG_TEXT_LENGTH) {
            throw new TsdbDataException("IoTDB tag is too long (" + text.length() + " characters): "
                    + text.substring(0, Math.min(text.length(), 64)) + "...");
        }
        if (text.indexOf('`') < 0) {
            rejectWildcard(text);
            if (!PATH.matcher(text).matches()) {
                throw new TsdbDataException("invalid IoTDB tag path: " + text);
            }
            List<String> plain = new ArrayList<String>();
            for (String node : text.split("\\.", -1)) {
                if (node.isEmpty()) {
                    throw new TsdbDataException("IoTDB tag path contains an empty node: " + text);
                }
                plain.add(node);
            }
            return plain;
        }
        List<String> nodes = new ArrayList<String>();
        int index = 0;
        while (index < text.length()) {
            if (text.charAt(index) == '`') {
                int close = text.indexOf('`', index + 1);
                if (close < 0) {
                    throw new TsdbDataException("IoTDB tag has an unclosed backtick: " + text);
                }
                addNode(nodes, text.substring(index + 1, close), text);
                index = close + 1;
                if (index == text.length()) {
                    break;
                }
                if (text.charAt(index) != '.') {
                    throw new TsdbDataException("IoTDB tag must separate quoted nodes with '.': " + text);
                }
                index++;
                requireMore(text, index);
            } else {
                int end = index;
                while (end < text.length() && text.charAt(end) != '.' && text.charAt(end) != '`') {
                    end++;
                }
                addNode(nodes, text.substring(index, end), text);
                if (end == text.length()) {
                    break;
                }
                if (text.charAt(end) == '`') {
                    throw new TsdbDataException("IoTDB tag must separate nodes with '.': " + text);
                }
                index = end + 1;
                requireMore(text, index);
            }
        }
        return nodes;
    }

    /**
     * 把服务端回显的列名或 {@code Timeseries} 值规范成可与 {@link #getKey()} 比对的形态。
     *
     * <p>三通道实测（docs/14 §1.1 的 G1）聚合列名形如
     * {@code last_value(root.sdk_it.device01.flow)}：函数包裹按 SQL 里的大小写原样回显，
     * 括号内是规范绝对路径；裸列则直接是绝对路径。故这里去掉函数包裹、去反引号与首尾空白，
     * <b>保留大小写</b>（节点名本身大小写敏感），比对所需的忽略大小写退路留给索引类。</p>
     *
     * @param label 列名或序列名，可为 {@code null}
     * @return 规范后的键；入参为 {@code null} 时返回 {@code null}
     */
    public static String normalizeKey(String label) {
        if (label == null) {
            return null;
        }
        String text = label.trim();
        int open = text.indexOf('(');
        int close = text.lastIndexOf(')');
        if (open >= 0 && close > open) {
            text = text.substring(open + 1, close).trim();
        }
        return text.replace("`", "");
    }

    /**
     * 返回调用方原文（已 trim，不改写字符）。
     *
     * @return 原始位号文本
     */
    public String getRaw() {
        return raw;
    }

    /**
     * 返回规范节点数组副本。
     *
     * @return 非 {@code null}，首节点恒为 {@code root}
     */
    public String[] getNodes() {
        return nodes.clone();
    }

    /**
     * 返回规范绝对路径（无引号）。
     *
     * @return 形如 {@code root.factory.RL.FCS0107.TI-1071815_PV}
     */
    public String getAbsolutePath() {
        return absolutePath;
    }

    /**
     * 返回用作结果对齐键的规范绝对路径。
     *
     * <p>与 {@link #getAbsolutePath()} 同值，单独给出是为了让“按名回填”的调用点
     * 读起来是在说对齐，而不是在说路径文本。</p>
     *
     * @return 非 {@code null} 的键
     */
    public String getKey() {
        return absolutePath;
    }

    /**
     * 返回数据库前缀（无引号）。
     *
     * @return 形如 {@code root.factory}，恒为两级
     */
    public String getDatabase() {
        return database;
    }

    /**
     * 返回设备路径（无引号），即最后一个节点之前的全部层级。
     *
     * @return 形如 {@code root.factory.RL.FCS0107}
     */
    public String getDevicePath() {
        return devicePath;
    }

    /**
     * 返回测点名（无引号）。
     *
     * @return 形如 {@code TI-1071815_PV}
     */
    public String getMeasurement() {
        return measurement;
    }

    /**
     * 引用后的数据库前缀，供 {@code FROM root.`db`} 使用。
     *
     * @return 形如 {@code root.`factory`}
     */
    public String quotedDatabase() {
        return quote(0, DATABASE_LEVELS);
    }

    /**
     * 引用后的设备路径，供 {@code FROM} 设备级形态使用。
     *
     * @return 形如 {@code root.`factory`.`RL`.`FCS0107`}
     */
    public String quotedDevicePath() {
        return quote(0, nodes.length - 1);
    }

    /**
     * 引用后的完整路径。
     *
     * @return 形如 {@code root.`factory`.`RL`.`FCS0107`.`TI-1071815_PV`}
     */
    public String quotedAbsolutePath() {
        return quote(0, nodes.length);
    }

    /**
     * 库相对表达式：去掉 {@code root} 与库名两级，其余逐节点引用。
     *
     * <p>只可与 {@link #quotedDatabase()} 配对（M1）。</p>
     *
     * @return 形如 {@code `RL`.`FCS0107`.`TI-1071815_PV`}
     */
    public String fromDatabase() {
        return quote(DATABASE_LEVELS, nodes.length);
    }

    /**
     * 库起表达式：去掉 {@code root} 锚点、保留库名节点。
     *
     * <p>只可与 {@code FROM root} 配对，用于 {@code query-batch-scope: root}
     * 的跨库单语句（实测三通道均可用）。</p>
     *
     * @return 形如 {@code `factory`.`RL`.`FCS0107`.`TI-1071815_PV`}
     */
    public String fromRoot() {
        return quote(1, nodes.length);
    }

    /**
     * 设备相对表达式：只有测点一级。
     *
     * <p>只可与 {@link #quotedDevicePath()} 配对，即改造前的按设备分组形态。</p>
     *
     * @return 形如 {@code `TI-1071815_PV`}
     */
    public String fromDevice() {
        return quote(nodes.length - 1, nodes.length);
    }

    /**
     * 判断位号文本是否已经是全名称（首节点为 {@code root}）。
     *
     * <p>按<b>节点</b>判断而不是按 {@code "root."} 字符串前缀判断：
     * {@code root.factoryx.d.m} 是 {@code root.factoryx} 库的全名称，
     * 若用 {@code startsWith("root." + 默认库)} 就会把它误认成默认库下的位号。</p>
     *
     * @param tagText 位号原文，可为 {@code null}
     * @return {@code true} 表示首节点是 {@code root}
     */
    public static boolean isAbsoluteText(String tagText) {
        if (tagText == null) {
            return false;
        }
        String trimmed = tagText.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        int dot = trimmed.indexOf('.');
        String first = dot < 0 ? trimmed : trimmed.substring(0, dot);
        if (first.charAt(0) == '`') {
            int close = first.indexOf('`', 1);
            first = close > 0 ? first.substring(1, close) : first;
        }
        return ROOT_ANCHOR.equals(first);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof IotdbTagPath)) {
            return false;
        }
        return absolutePath.equals(((IotdbTagPath) other).absolutePath);
    }

    @Override
    public int hashCode() {
        return absolutePath.hashCode();
    }

    @Override
    public String toString() {
        return absolutePath;
    }

    /**
     * 逐节点引用并拼接，首节点为 {@code root} 时裸写。
     *
     * @param from 起始节点下标（含）
     * @param to   结束节点下标（不含）
     * @return 引用后的路径片段
     */
    private String quote(int from, int to) {
        StringBuilder builder = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (builder.length() > 0) {
                builder.append('.');
            }
            boolean anchor = i == 0 && ROOT_ANCHOR.equals(nodes[i]);
            builder.append(anchor ? ROOT_ANCHOR : "`" + nodes[i] + "`");
        }
        return builder.toString();
    }

    private static String join(String[] nodes) {
        StringBuilder builder = new StringBuilder();
        for (String node : nodes) {
            if (builder.length() > 0) {
                builder.append('.');
            }
            builder.append(node);
        }
        return builder.toString();
    }

    private static String requireText(String tagText) {
        if (tagText == null) {
            throw new TsdbDataException("IoTDB tag must not be null");
        }
        String trimmed = tagText.trim();
        if (trimmed.isEmpty()) {
            throw new TsdbDataException("IoTDB tag must not be blank");
        }
        return trimmed;
    }

    private static void requireMore(String text, int index) {
        if (index >= text.length()) {
            throw new TsdbDataException("IoTDB tag must not end with a separator: " + text);
        }
    }

    private static void addNode(List<String> nodes, String node, String text) {
        if (node.isEmpty()) {
            throw new TsdbDataException("IoTDB tag path contains an empty node: " + text);
        }
        rejectWildcard(node);
        if (!NODE.matcher(node).matches()) {
            throw new TsdbDataException("invalid IoTDB tag node '" + node + "' in: " + text);
        }
        nodes.add(node);
    }

    private static void rejectWildcard(String text) {
        if (text.indexOf('*') >= 0 || text.indexOf('?') >= 0) {
            throw new TsdbDataException("IoTDB tag path must not contain wildcards: " + text);
        }
    }

    /**
     * 校验层级：至少“库、设备、测点”四段（含 {@code root} 锚点）。
     *
     * @param nodes 解析出的节点列表
     * @param text  位号原文，用于错误信息
     * @throws TsdbDataException 层级不足
     */
    private static void requireDepth(List<String> nodes, String text) {
        if (nodes.size() < DATABASE_LEVELS + 2) {
            throw new TsdbDataException("IoTDB tag must be 'root.<database>.<device>.<measurement>': " + text);
        }
    }

    /**
     * 拒绝未替换的模板占位与纯中文占位说明。
     *
     * <p>现场配置里大量存在 {@code ${device}.PV}、{@code 待补充位号} 这类文本，
     * 它们不是位号；IoTDB 对不存在的序列返回空而不是报错，放行就等于把配置缺陷
     * 伪装成“这段时间没有数据”。映射表命中路径不经过本方法。</p>
     *
     * @param text 已 trim 的位号原文
     * @throws TsdbDataException 命中占位特征
     */
    private static void rejectPlaceholder(String text) {
        if (text.contains("${") || text.contains("#{")) {
            throw new TsdbDataException("IoTDB tag looks like an unresolved template placeholder: " + text);
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9') {
                return;
            }
        }
        throw new TsdbDataException("IoTDB tag contains no ASCII letter or digit, it is probably a "
                + "placeholder description rather than a real tag (register it in the tag mapping "
                + "table if it really exists): " + text);
    }
}
