package io.github.forget_the_bright.tsdb.common.model;

import io.github.forget_the_bright.tsdb.common.config.TimeConfig;

import java.time.Instant;
import java.util.Date;

/**
 * 轻量健康检查的结果。
 *
 * <p>由 {@code HealthCheck#check()} 返回，用于探活与监控。设计上要求产生它的
 * 检查是“无副作用、低成本、可频繁调用”的：只做一次极轻量的服务端往返
 * （例如 ping 或读取一个固定位号），不得触发数据写入、连接重建或大范围查询。</p>
 *
 * <p>字段语义：</p>
 * <ul>
 *   <li>{@link #getState()}：健康状态枚举，构造时传入 {@code null} 会被归一化为
 *       {@link State#UNKNOWN}，因此永不为 {@code null}；</li>
 *   <li>{@link #getLatencyMillis()}：本次探测的往返耗时，单位毫秒；</li>
 *   <li>{@link #getCheckedAt()}：本次探测的完成时刻，构造时传入 {@code null}
 *       会取 {@link Instant#now()}，因此永不为 {@code null}；</li>
 *   <li>{@link #getMessage()}：人类可读的补充说明，可能为 {@code null}
 *       （例如成功时驱动未提供描述）。</li>
 * </ul>
 *
 * <p>不可变性与线程安全：所有字段均为 {@code final}，构造后不可变，
 * 可安全地在多线程间共享。注意它只是某一时刻的快照，不具备时效性，
 * 需要最新状态时请重新调用 {@code check()}。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class HealthStatus {

    /**
     * 健康状态等级。
     *
     * <p>用于粗粒度表达实例可用性，便于监控系统直接做阈值判断：</p>
     *
     * @author wanghao
     * @since 0.1.0
     */
    public enum State {
        /**
         * 可用：服务端能正常响应，且未发现降级迹象。
         */
        UP,

        /**
         * 降级：服务端仍可响应，但存在异常，例如部分节点不可用、
         * 响应明显变慢、或处于只读/维护模式。功能可能不完整，调用方应做好兜底。
         */
        DEGRADED,

        /**
         * 不可用：无法建立连接、鉴权失败或服务端明确报告故障。
         * 此时通常应触发重连或告警。
         */
        DOWN,

        /**
         * 未知：驱动无法给出结论（例如尚未执行探测、返回内容无法解析）。
         * 构造 {@link HealthStatus} 时状态为 {@code null} 就会归一化为该值。
         */
        UNKNOWN
    }

    /**
     * 健康状态等级，永不为 {@code null}。
     */
    private final State state;

    /**
     * 补充说明信息，可能为 {@code null}。
     */
    private final String message;

    /**
     * 探测往返耗时，单位毫秒。
     */
    private final long latencyMillis;

    /**
     * 探测完成时刻，永不为 {@code null}。
     */
    private final Instant checkedAt;

    /**
     * 探测完成时刻的 {@link Date} 视图，便于前端/模板直接消费。
     */
    private final Date checkedAtDate;

    /**
     * 已格式化的探测完成时间文本，前端可直接展示。
     */
    private final String checkedAtText;

    /**
     * 构造一个健康检查结果。
     *
     * <p>本构造器对入参做了归一化：{@code state} 为 {@code null} 时取
     * {@link State#UNKNOWN}；{@code checkedAt} 为 {@code null} 时取
     * {@link Instant#now()}。{@code latencyMillis} 不做校验，调用方应自行保证
     * 其为非负值（负数在语义上表示“不适用/未测量”，不会抛异常）。</p>
     *
     * @param state         健康状态等级，允许为 {@code null}（归一化为 {@code UNKNOWN}）
     * @param message       人类可读的补充说明，允许为 {@code null}
     * @param latencyMillis 探测往返耗时，单位毫秒；建议非负
     * @param checkedAt     探测完成时刻，允许为 {@code null}（取当前时刻）
     */
    public HealthStatus(State state, String message, long latencyMillis, Instant checkedAt) {
        this(state, message, latencyMillis, checkedAt, null);
    }

    /**
     * 构造健康检查结果，并按实例时间配置生成展示时间。
     *
     * @param state         健康状态等级，允许为 {@code null}
     * @param message       人类可读的补充说明，允许为 {@code null}
     * @param latencyMillis 探测往返耗时，单位毫秒
     * @param checkedAt     探测完成时刻，允许为 {@code null}（取当前时刻）
     * @param timeConfig    实例时间配置，允许为 {@code null}（UTC ISO 文本）
     */
    public HealthStatus(State state, String message, long latencyMillis,
                        Instant checkedAt, TimeConfig timeConfig) {
        this.state = state == null ? State.UNKNOWN : state;
        this.message = message;
        this.latencyMillis = latencyMillis;
        Instant at = checkedAt == null ? Instant.now() : checkedAt;
        this.checkedAt = at;
        this.checkedAtDate = Date.from(at);
        this.checkedAtText = timeConfig == null ? at.toString() : timeConfig.formatResponse(at);
    }

    /**
     * 便捷工厂：构造一个“可用”的结果。
     *
     * <p>状态固定为 {@link State#UP}，说明信息固定为 {@code "ok"}，
     * 探测时刻取调用时的 {@link Instant#now()}。</p>
     *
     * @param latencyMillis 探测往返耗时，单位毫秒；建议非负
     * @return 非 {@code null} 的 {@link State#UP} 结果
     */
    public static HealthStatus up(long latencyMillis) {
        return new HealthStatus(State.UP, "ok", latencyMillis, Instant.now());
    }

    /**
     * 便捷工厂：构造一个“不可用”的结果。
     *
     * <p>状态固定为 {@link State#DOWN}，探测时刻取调用时的
     * {@link Instant#now()}。</p>
     *
     * @param message       失败原因说明，允许为 {@code null}；
     *                      建议填入可定位问题的信息（如异常摘要）
     * @param latencyMillis 探测往返耗时，单位毫秒；探测直接失败、未产生往返时
     *                      可传 0 或负数表示不适用
     * @return 非 {@code null} 的 {@link State#DOWN} 结果
     */
    public static HealthStatus down(String message, long latencyMillis) {
        return new HealthStatus(State.DOWN, message, latencyMillis, Instant.now());
    }

    /**
     * 返回健康状态等级。
     *
     * @return 非 {@code null} 的状态等级，构造时传入 {@code null} 会得到
     *         {@link State#UNKNOWN}
     */
    public State getState() {
        return state;
    }

    /**
     * 返回人类可读的补充说明。
     *
     * @return 说明文本，可能为 {@code null}（表示驱动未提供）
     */
    public String getMessage() {
        return message;
    }

    /**
     * 返回本次探测的往返耗时。
     *
     * <p>单位毫秒。该值不做校验，可能为 0 或负数，负值语义上表示“未测量”。
     * 它只是单次探测的样本值，不代表平均延迟或 SLA。</p>
     *
     * @return 往返耗时，单位毫秒
     */
    public long getLatencyMillis() {
        return latencyMillis;
    }

    /**
     * 返回本次探测的完成时刻。
     *
     * @return 非 {@code null} 的时刻；构造时未提供则为本对象创建时的当前时刻
     */
    public Instant getCheckedAt() {
        return checkedAt;
    }

    /**
     * 返回探测完成时刻的 {@link Date} 视图。
     *
     * @return 非 {@code null} 的 Date
     */
    public Date getCheckedAtDate() {
        return new Date(checkedAtDate.getTime());
    }

    /**
     * 返回格式化后的探测完成时间文本。
     *
     * @return 非 {@code null} 的展示文本
     */
    public String getCheckedAtText() {
        return checkedAtText;
    }

    /**
     * 返回使用指定时间配置重新格式化时间文本的副本。
     *
     * @param timeConfig 实例时间配置；为 {@code null} 时返回自身
     * @return 重新格式化后的健康状态
     */
    public HealthStatus withTimeFormat(TimeConfig timeConfig) {
        if (timeConfig == null) {
            return this;
        }
        return new HealthStatus(state, message, latencyMillis, checkedAt, timeConfig);
    }

    /**
     * 判断当前是否处于“可用”状态，等价于
     * {@code getState() == State.UP}。
     *
     * <p>注意 {@link State#DEGRADED} 不算可用，需要判定的调用方请直接比较
     * {@link #getState()}。</p>
     *
     * @return 当且仅当状态为 {@link State#UP} 时返回 {@code true}
     */
    public boolean isUp() {
        return state == State.UP;
    }
}
