/**
 * 公共接入层的接口契约。
 *
 * <p>这一组接口只描述“做什么”，不描述“用哪家协议”。驱动实现负责把厂商语义
 * 映射到这些契约；业务代码面向接口编程，因此替换时序库时不需要改调用代码。</p>
 *
 * <h2>接口分工</h2>
 * <ul>
 *   <li>{@link io.github.forget_the_bright.tsdb.common.api.TsdbClient}：客户端
 *       门面，聚合标签目录、实时值、历史查询、写入、健康检查和原生逃生口。</li>
 *   <li>{@link io.github.forget_the_bright.tsdb.common.api.TagCatalog}：标签目录，
 *       支持按名称掩码、值类型、单位过滤；不支持的条件由驱动显式拒绝，而不是
 *       静默忽略。</li>
 *   <li>{@link io.github.forget_the_bright.tsdb.common.api.CurrentValueReader}：
 *       批量读取最新值，返回顺序与请求顺序一致，缺失标签返回 {@code null} 元素。</li>
 *   <li>{@link io.github.forget_the_bright.tsdb.common.api.HistoryReader}：按
 *       {@code HistoryQuery} 读取原始点、区间聚合、插值或采样数据。</li>
 *   <li>{@link io.github.forget_the_bright.tsdb.common.api.WriteSession}：写入
 *       会话；默认只读，未开启写权限时 {@code isWritable()} 为 {@code false}。</li>
 *   <li>{@link io.github.forget_the_bright.tsdb.common.api.HealthCheck}：轻量
 *       健康检查，用于启动自检和熔断恢复判断。</li>
 * </ul>
 *
 * <h2>实现约定</h2>
 * <p>实现类必须线程安全；查询参数为 {@code null} 时按“未指定”处理；时间统一用
 * {@link java.time.Instant}，区间为左闭右开；能力不支持时抛
 * {@link io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException}，
 * 不用空结果掩盖差异。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
package io.github.forget_the_bright.tsdb.common.api;
