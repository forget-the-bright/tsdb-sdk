package io.github.forget_the_bright.tsdb.common.api;

import io.github.forget_the_bright.tsdb.common.model.HealthStatus;

/**
 * 轻量、无副作用的健康探测。
 *
 * <p>在 SDK 中用于探活与监控：以尽可能低的成本判断“这个数据库实例现在能不能用”，
 * 并给出粗略的可用性等级和往返延迟。它<b>不是</b>业务查询接口——不得触发数据写入、
 * 不得改变服务端状态、不得做大范围扫描，也不应因为一次探测而重建连接池。</p>
 *
 * <p>实现约束：</p>
 * <ul>
 *   <li>{@link #check()} 不得返回 {@code null}，始终返回一个
 *       {@link HealthStatus}；</li>
 *   <li>探测失败不应抛异常，而应返回
 *       {@link HealthStatus#down(String, long)} 之类的结果，
 *       这样监控系统可以把“不可用”当作一种正常返回值处理；</li>
 *   <li>实现必须线程安全，可被监控线程周期性地并发调用；</li>
 *   <li>返回对象不可变，但其代表的是调用时刻的快照，不具备时效性。</li>
 * </ul>
 *
 * @author wanghao
 * @since 0.1.0
 */
public interface HealthCheck {

    /**
     * 执行一次健康探测。
     *
     * <p>实现应当捕获探测过程中的所有运行时异常，并把它们转换成
     * {@link HealthStatus}（通常是 {@link HealthStatus.State#DOWN} 并带上
     * 异常摘要），而不是把异常抛给调用方。</p>
     *
     * @return 非 {@code null} 的健康状态快照，包含状态等级、可选说明、
     *         探测往返耗时（毫秒）与探测完成时刻
     */
    HealthStatus check();
}
