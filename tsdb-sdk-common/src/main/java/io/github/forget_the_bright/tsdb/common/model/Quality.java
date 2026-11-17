package io.github.forget_the_bright.tsdb.common.model;

/**
 * 归一化后的数据质量等级。
 *
 * <p>不同厂商的原始质量码取值范围和含义各不相同（例如 0 表示好、192 表示坏、
 * 或使用位标志），SDK 在这里把它们收敛成 5 个等级，供上层做统一的过滤与展示。
 * 原始厂商质量码不会被丢弃，而是单独保存在
 * {@link Sample#getVendorQualityCode()} 中，需要精确定位厂商语义时应读取该字段，
 * 而不要试图从本枚举反推。</p>
 *
 * <p>等级之间不是等距的数值关系，只是一个有序的分类，不要做算术运算。</p>
 *
 * <p>本枚举不可变、线程安全。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public enum Quality {
    /**
     * 质量良好：数据可信，可直接参与计算与统计。
     */
    GOOD,

    /**
     * 质量不确定：数据可用但存在疑问，例如插值补点、传感器自检未完成、
     * 采集过程中发生过通信重试。下游做关键决策时应谨慎对待。
     */
    UNCERTAIN,

    /**
     * 质量坏：数据不可信，例如传感器故障、越限、采集器标记为无效。
     * 默认实现中此类点不参与聚合计算。
     */
    BAD,

    /**
     * 数据过期（陈旧）：采样点的值不再更新，通常是采集通道中断后
     * 服务端继续返回值但标记为陈旧。时间戳可能已经很久远。
     */
    STALE,

    /**
     * 未知：厂商返回了无法映射到上述任何等级的质量码，或驱动未提供质量信息。
     * 这是构造 {@link Sample} 时质量参数为 {@code null} 时的默认值。
     */
    UNKNOWN;

    /**
     * 判断当前质量等级是否为 {@link #GOOD}。
     *
     * @return 当且仅当 {@code this == GOOD} 时返回 {@code true}；
     *         其他等级（含 {@code null} 语义下的 {@link #UNKNOWN}）返回 {@code false}
     */
    public boolean isGood() {
        return this == GOOD;
    }
}
