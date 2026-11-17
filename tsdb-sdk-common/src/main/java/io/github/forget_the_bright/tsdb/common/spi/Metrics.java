package io.github.forget_the_bright.tsdb.common.spi;

/**
 * 极简指标出口，用于把 SDK 内部计数与耗时上报给调用方的监控体系。
 *
 * <p>公共层刻意不依赖 Micrometer 等具体监控库，只暴露两个方法；Spring Boot starter
 * 或业务代码可以实现本接口，把数据桥接到 Micrometer、Dropwizard、Prometheus 等实现上。
 * SDK 默认使用 {@link #NOOP}，即不做任何采集，因此不引入本接口时也没有额外开销。</p>
 *
 * <p>实现约定：实现类必须线程安全（驱动会在多次并发请求中调用），并且
 * <b>不得抛出异常、不得阻塞</b>——指标采集失败不应影响数据读写链路。实现方若需要
 * 远程上报，应自行异步化。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public interface Metrics {

    /**
     * 累加一个计数器。
     *
     * @param name  指标名，建议使用点号命名（如 {@code tsdb.query.count}），不能为 {@code null}
     * @param delta 增量，可为负数表示减少；单位由指标名约定
     * @param tags  可变标签，按“键、值、键、值”成对传入（如 {@code "instance", "hls_main"}），
     *              可以为空数组但不能为 {@code null} 元素
     */
    void counter(String name, long delta, String... tags);

    /**
     * 记录一次耗时。
     *
     * @param name   指标名，建议使用点号命名（如 {@code tsdb.query.latency}），不能为 {@code null}
     * @param millis 耗时毫秒数，应为非负值；负值由实现方决定忽略或按原值记录
     * @param tags   可变标签，成对传入，可为空
     */
    void timer(String name, long millis, String... tags);

    /**
     * 空实现：所有上报都被丢弃，用于未接入监控的场景。
     *
     * <p>该常量本身不可变、无状态，可安全地在多线程间共享；SDK 在
     * {@link DriverContext} 构造时用它替代 {@code null}，从而让驱动免于判空。</p>
     */
    Metrics NOOP = new Metrics() {
        @Override
        public void counter(String name, long delta, String... tags) {
        }

        @Override
        public void timer(String name, long millis, String... tags) {
        }
    };
}
