package io.github.forget_the_bright.tsdb.iotdb.internal.sql;

import io.github.forget_the_bright.tsdb.common.exception.TsdbDataException;

import java.util.List;

/**
 * 批量 SQL 的分组档位：决定一条语句覆盖多大范围、{@code FROM} 写什么、
 * SELECT 表达式带几级前缀。
 *
 * <p><b>为什么必须三样东西一起定</b>：2026-11-13 在生产 2.0.11 上实测
 * （docs/14 §1 的 M1）——{@code FROM} 的前缀层级与 SELECT 表达式的层级一旦错配，
 * 服务端<b>不报错，只静默返回空结果</b>；表达式里带 {@code root.} 则被
 * {@code code=701} 拒绝。所以“分组键、{@code FROM} 前缀、列表达式”必须是同一个
 * 档位下的三种派生写法，不能由各调用点分别拼字符串。</p>
 *
 * <p>三档都已实测可用：</p>
 * <table>
 *   <caption>分组档位与 SQL 形态（{@code root.factory.RL.FCS0107.TI-1071815_PV}）</caption>
 *   <tr><th>档位</th><th>{@code FROM}</th><th>列表达式</th></tr>
 *   <tr><td>{@link #DATABASE}</td><td>{@code root.`factory`}</td>
 *       <td>{@code `RL`.`FCS0107`.`TI-1071815_PV`}</td></tr>
 *   <tr><td>{@link #ROOT}</td><td>{@code root}</td>
 *       <td>{@code `factory`.`RL`.`FCS0107`.`TI-1071815_PV`}</td></tr>
 *   <tr><td>{@link #DEVICE}</td><td>{@code root.`factory`.`RL`.`FCS0107`}</td>
 *       <td>{@code `TI-1071815_PV`}</td></tr>
 * </table>
 *
 * <p>{@link #DATABASE} 是本轮的目标档位，也是当前默认档位：业务单次请求的 248～345 个
 * 位号通常同属一个数据库，一次查询即可覆盖全批。{@link #ROOT} 的往返更少（实测 200 个跨库表达式一条
 * 语句可用），但受限账号可能只授予按库权限，因此不作为目标档。{@link #DEVICE} 是改造前
 * 的行为，同时用作现场回退档；{@code IotdbConfig#DEFAULT_QUERY_BATCH_SCOPE} 翻到
 * {@link #DATABASE} 的入场券是同库自对自的双跑对数（{@code IotdbTierParityIT}：
 * 逐表达式、{@code device}、{@code database} 三种配置逐序列逐点 0 容差全部相同）。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public enum Grouping {

    /** 按数据库分组，{@code FROM root.<db>}，列表达式为“设备.测点”。 */
    DATABASE,

    /** 不分组，{@code FROM root}，列表达式从库名起（跨库单语句）。 */
    ROOT,

    /** 按设备分组，{@code FROM} 为设备路径，列表达式只有测点。 */
    DEVICE;

    /**
     * 返回该位号在当前档位下的分组键。
     *
     * @param path 已解析的位号路径，不能为 {@code null}
     * @return 用于 {@code groupBy} 的键（设备路径、数据库前缀或常量 {@code root}）
     */
    public String groupKey(IotdbTagPath path) {
        switch (this) {
            case DEVICE:
                return path.getDevicePath();
            case ROOT:
                return IotdbTagPath.ROOT_ANCHOR;
            case DATABASE:
            default:
                return path.getDatabase();
        }
    }

    /**
     * 返回同一组共用的 {@code FROM} 前缀（已按节点引用）。
     *
     * @param group 同一分组键下的位号，不能为空
     * @return {@code FROM} 之后的路径文本
     * @throws TsdbDataException 组为空，或组内混进了不同分组键的位号
     */
    public String fromPrefix(List<IotdbTagPath> group) {
        requireSingleGroup(group);
        switch (this) {
            case DEVICE:
                return group.get(0).quotedDevicePath();
            case ROOT:
                return IotdbTagPath.ROOT_ANCHOR;
            case DATABASE:
            default:
                return group.get(0).quotedDatabase();
        }
    }

    /**
     * 返回该位号在当前档位下的 SELECT 表达式（已引用）。
     *
     * @param path 已解析的位号路径
     * @return 表达式文本，恒不含 {@code root.}
     */
    public String expression(IotdbTagPath path) {
        switch (this) {
            case DEVICE:
                return path.fromDevice();
            case ROOT:
                return path.fromRoot();
            case DATABASE:
            default:
                return path.fromDatabase();
        }
    }

    /**
     * 校验一条语句只覆盖一个分组。
     *
     * <p>分组键不同的位号合进一条 SQL 就是 M1 那类错配：{@code FROM} 只能取其中
     * 一个前缀，另一个位号的表达式层级立刻不对，服务端静默返空。这里当场拒绝，
     * 而不是让调用方把空结果当成“这段时间没数据”。</p>
     *
     * @param group 待校验的位号列表
     * @throws TsdbDataException 列表为空或分组键不唯一
     */
    public void requireSingleGroup(List<IotdbTagPath> group) {
        if (group == null || group.isEmpty()) {
            throw new TsdbDataException("IoTDB batch query requires at least one tag path");
        }
        String key = groupKey(group.get(0));
        for (IotdbTagPath path : group) {
            if (!key.equals(groupKey(path))) {
                throw new TsdbDataException("IoTDB " + name() + "-level query mixes different groups: "
                        + key + " vs " + groupKey(path));
            }
        }
    }
}
