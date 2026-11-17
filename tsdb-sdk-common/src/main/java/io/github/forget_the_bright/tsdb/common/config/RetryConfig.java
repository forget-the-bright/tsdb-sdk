package io.github.forget_the_bright.tsdb.common.config;

/**
 * 实例级重试策略（可变 POJO）。
 *
 * <p>SDK 只对幂等操作重试：查询、取令牌、探活等读请求可以自动重放；写入
 * （{@code WriteSession}）默认一律不重试，避免重复写入造成时序库中出现双份数据。
 * 驱动实现同样遵循该约定，除非调用方显式开启写入重试。</p>
 *
 * <p>参数含义：{@link #getMaxAttempts()} 是“总尝试次数（含首次调用）”，因此
 * {@code 1} 表示不重试、{@code 2} 表示首次失败后再重试一次；{@link #getBackoffMillis()}
 * 是两次尝试之间的固定等待时间，单位为毫秒，{@code 0} 表示立即重试。</p>
 *
 * <p>线程安全：本类不是线程安全的。构造后由驱动在启动阶段读取并复制为不可变字段，
 * 运行期修改不会影响已经创建的客户端。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public class RetryConfig {

    /** 总尝试次数（含首次），默认 2；必须大于等于 1，驱动在启动校验时拒绝小于 1 的值。 */
    private int maxAttempts = 2;
    /** 重试间隔，单位毫秒，默认 200；负值会被驱动按 0 处理（等效立即重试）。 */
    private long backoffMillis = 200L;

    /**
     * 返回总尝试次数（含首次调用）。
     *
     * @return 尝试次数，默认 {@code 2}；{@code 1} 表示禁用重试
     */
    public int getMaxAttempts() {
        return maxAttempts;
    }

    /**
     * 设置总尝试次数（含首次调用）。
     *
     * <p>本方法不做范围校验，非法值（小于 1）会在驱动初始化时被拒绝；
     * 该值只影响读请求，写入仍默认不重试。</p>
     *
     * @param maxAttempts 总尝试次数，语义为“首次 + 重试次数”
     */
    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    /**
     * 返回重试间隔。
     *
     * @return 间隔毫秒数，默认 {@code 200}
     */
    public long getBackoffMillis() {
        return backoffMillis;
    }

    /**
     * 设置重试间隔。
     *
     * @param backoffMillis 间隔毫秒数；{@code 0} 表示立即重试，负值由驱动归一化为 {@code 0}
     */
    public void setBackoffMillis(long backoffMillis) {
        this.backoffMillis = backoffMillis;
    }
}
