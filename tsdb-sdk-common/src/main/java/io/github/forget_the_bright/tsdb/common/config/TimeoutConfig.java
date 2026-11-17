package io.github.forget_the_bright.tsdb.common.config;

/**
 * 实例级超时配置（可变 POJO），所有字段单位均为毫秒。
 *
 * <p>SDK 不允许无限等待，因此超时值必须严格大于 {@code 0}：零或负数会在驱动
 * 初始化（{@code TsdbDriver#validate} 或客户端构造）时被拒绝，并抛出
 * {@link io.github.forget_the_bright.tsdb.common.exception.TsdbConfigurationException}，
 * 而不是默默退化为“永不超时”。默认值按工业现场常见的局域网 HTTP 接口设定：
 * 建连 5 秒，读 30 秒，写 30 秒。</p>
 *
 * <p>三个超时的分工：{@link #getConnectMillis()} 仅覆盖 TCP/TLS 建连与连接池取连接；
 * {@link #getReadMillis()} 覆盖单次请求等待响应的时长（长查询应调大该值，
 * 不要用建连超时代替）；{@link #getWriteMillis()} 覆盖请求体发送与刷盘等待，
 * 仅对支持写会话的驱动有效，部分驱动（如只提供 HTTP 短连接的实现）会复用它或读超时。</p>
 *
 * <p>线程安全：本类不是线程安全的，应在启动阶段完成配置；驱动在创建客户端时
 * 读取并复制这些值，运行期修改不会影响已创建的客户端。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public class TimeoutConfig {

    /** 建连超时，单位毫秒，默认 {@code 5000}；必须大于 0。 */
    private long connectMillis = 5000L;
    /** 读超时（等待单次响应），单位毫秒，默认 {@code 30000}；必须大于 0。 */
    private long readMillis = 30000L;
    /** 写超时（发送请求体/等待写入确认），单位毫秒，默认 {@code 30000}；必须大于 0。 */
    private long writeMillis = 30000L;

    /**
     * 返回建连超时。
     *
     * @return 建连超时毫秒数，默认 {@code 5000}
     */
    public long getConnectMillis() {
        return connectMillis;
    }

    /**
     * 设置建连超时。
     *
     * <p>只控制建连阶段；查询本身的等待时间由 {@link #setReadMillis(long)} 决定。</p>
     *
     * @param connectMillis 建连超时毫秒数，必须大于 {@code 0}，否则驱动初始化失败
     */
    public void setConnectMillis(long connectMillis) {
        this.connectMillis = connectMillis;
    }

    /**
     * 返回读超时。
     *
     * @return 读超时毫秒数，默认 {@code 30000}
     */
    public long getReadMillis() {
        return readMillis;
    }

    /**
     * 设置读超时（等待单次响应）。
     *
     * <p>历史数据查询耗时与时间跨度成正比，跨度大的查询需要显式调大该值；
     * 超时后驱动抛出
     * {@link io.github.forget_the_bright.tsdb.common.exception.TsdbTimeoutException}。</p>
     *
     * @param readMillis 读超时毫秒数，必须大于 {@code 0}，否则驱动初始化失败
     */
    public void setReadMillis(long readMillis) {
        this.readMillis = readMillis;
    }

    /**
     * 返回写超时。
     *
     * @return 写超时毫秒数，默认 {@code 30000}
     */
    public long getWriteMillis() {
        return writeMillis;
    }

    /**
     * 设置写超时（发送请求体/等待写入确认）。
     *
     * <p>仅对支持写会话的驱动有意义；在只读（{@code allow-write=false}）部署中
     * 该值不会被使用。</p>
     *
     * @param writeMillis 写超时毫秒数，必须大于 {@code 0}
     */
    public void setWriteMillis(long writeMillis) {
        this.writeMillis = writeMillis;
    }
}
