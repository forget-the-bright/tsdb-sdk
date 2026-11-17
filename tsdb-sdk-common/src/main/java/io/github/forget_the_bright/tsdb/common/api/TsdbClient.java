package io.github.forget_the_bright.tsdb.common.api;

import io.github.forget_the_bright.tsdb.common.model.CapabilitySet;
import io.github.forget_the_bright.tsdb.common.model.HealthStatus;

/**
 * 到某个时序数据库实例的一条逻辑连接。
 *
 * <p>在 SDK 中，本接口是面向使用者的统一门面：一个实例对应配置里的一项
 * （同一个物理库可以配置多个实例名），构造后通过它拿到
 * 位号目录、当前值、历史查询、写入四个子接口，以及健康状态。
 * 具体驱动（HLS、GE、IoTDB 等）实现本接口，使用者不需要感知厂商差异。</p>
 *
 * <p>生命周期：连接在创建时即建立（或懒建立），使用完毕后必须调用
 * {@link #close()} 释放资源；推荐用 try-with-resources 包裹，因为本接口继承
 * {@link AutoCloseable} 且 {@link #close()} 不声明受检异常。</p>
 *
 * <p>线程安全：<b>实现必须是线程安全的</b>。同一个客户端实例应能被多个线程
 * 并发调用；其返回的子接口（{@link TagCatalog}、{@link CurrentValueReader}、
 * {@link HistoryReader}、{@link WriteSession}）同样要求线程安全。</p>
 *
 * <p>错误约定：所有方法在失败时抛出 {@code TsdbException} 的对应子类
 * （配置、连接、鉴权、超时、限流、不支持、查询、数据、内部错误），
 * 并通过 {@code withContext(...)} 附带实例名、驱动类型、操作名与厂商错误码，
 * 便于定位问题；不要依赖具体异常文案做判断，应使用
 * {@code TsdbException#getCode()}。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public interface TsdbClient extends AutoCloseable {

    /**
     * 返回该实例的配置名称。
     *
     * <p>即配置中为这个连接起的逻辑名，用于日志、监控与异常上下文标识，
     * 不是数据库服务端的实例名，也不参与协议交互。</p>
     *
     * @return 实例名；同一配置下唯一，通常非 {@code null}
     */
    String instanceName();

    /**
     * 返回驱动类型标识。
     *
     * <p>标识该连接由哪个驱动实现，用于日志与诊断。取值是小写的驱动短名，
     * 例如 {@code "hls"}、{@code "ge"}、{@code "iotdb"}，
     * 与驱动注册时声明的类型一致，不随实例配置变化。</p>
     *
     * @return 非 {@code null} 的驱动类型短名
     */
    String type();

    /**
     * 返回该驱动声明支持的能力快照。
     *
     * <p>调用方在使用任何可选功能前应先询问，例如
     * {@code capabilities().supports(Capability.WRITE)}。能力集在客户端
     * 创建后不再变化，可安全缓存；但重建客户端后应重新获取。
     * 本方法不应因连接状态而抛异常。</p>
     *
     * @return 非 {@code null} 的不可变能力集合；驱动无任何可选能力时为空集合
     */
    CapabilitySet capabilities();

    /**
     * 返回位号目录操作接口。
     *
     * <p>用于列出、检索、查看位号元数据。返回的对象是客户端内部持有的
     * 单例式实现，多次调用返回同一实例，可长期缓存复用。</p>
     *
     * @return 非 {@code null} 的位号目录接口，线程安全
     * @throws IllegalStateException 当客户端已关闭时（内置驱动的行为）
     */
    TagCatalog tagCatalog();

    /**
     * 返回当前值读取接口。
     *
     * <p>用于读取位号的最新采样点。返回的对象是客户端内部持有的实现，
     * 多次调用返回同一实例。</p>
     *
     * @return 非 {@code null} 的当前值读取接口，线程安全
     * @throws IllegalStateException 当客户端已关闭时（内置驱动的行为）
     */
    CurrentValueReader currentValues();

    /**
     * 返回历史数据读取接口。
     *
     * <p>用于按区间查询原始/聚合/插值历史数据。返回的对象是客户端内部持有的
     * 实现，多次调用返回同一实例。</p>
     *
     * @return 非 {@code null} 的历史读取接口，线程安全
     * @throws IllegalStateException 当客户端已关闭时（内置驱动的行为）
     */
    HistoryReader history();

    /**
     * 返回累计量差分读取接口。
     *
     * <p>累计量点位保存的是“从某个时刻起的累计值”，业务上常需要“每个区间用了多少”，
     * 即区间末值减去上一区间末值。默认实现抛
     * {@link io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException}，
     * 驱动只有在声明 {@link io.github.forget_the_bright.tsdb.common.model.Capability#CUMULATIVE_DELTA}
     * 后才应覆盖本方法。</p>
     *
     * @return 差分读取接口，非 {@code null}
     */
    default DeltaReader deltaHistory() {
        throw new io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException(
                "driver " + type() + " does not support cumulative delta");
    }

    /**
     * 返回写入会话。
     *
     * <p>即使驱动不支持写入，也<b>不会</b>返回 {@code null}：而是返回一个
     * {@code isWritable()} 为 {@code false} 的会话，从而让调用方统一处理。
     * 返回的对象是客户端内部持有的实现，多次调用返回同一实例。</p>
     *
     * @return 非 {@code null} 的写入会话；是否可写由
     *         {@link WriteSession#isWritable()} 判断
     * @throws IllegalStateException 当客户端已关闭时（内置驱动的行为）
     */
    WriteSession writeSession();

    /**
     * 执行一次轻量健康探测。
     *
     * <p>语义为“无副作用、低成本、可频繁调用”，只用于探活，
     * 不应触发数据写入或大范围查询。探测失败时通常不抛异常，
     * 而是返回状态为 {@link HealthStatus.State#DOWN} 的结果。</p>
     *
     * @return 非 {@code null} 的健康状态快照；即使不可用也会返回结果对象
     * @throws IllegalStateException 当客户端已关闭时（内置驱动的行为）
     */
    HealthStatus health();

    /**
     * 取出厂商原生的门面对象，用于访问 SDK 未抽象的能力。
     *
     * <p>例如 GE 驱动可 {@code unwrap(GeNativeApi.class)} 拿到原生 API，
     * IoTDB 驱动还可 {@code unwrap(IotdbConfig.class)} 拿到驱动配置对象。
     * 这是“逃生舱口”，使用了它就不再具备跨厂商可移植性。</p>
     *
     * @param nativeType 期望得到的原生类型 {@link Class}，不能为 {@code null}；
     *                   内置驱动通常支持三类目标：客户端自身类型、
     *                   厂商原生 API 类型、驱动配置类型
     * @param <T>        原生类型
     * @return 非 {@code null} 的原生对象实例；具体是哪个对象由驱动决定
     * @throws IllegalArgumentException 当 {@code nativeType} 为 {@code null} 时
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException
     *         当本驱动不提供所请求的原生类型时
     */
    <T> T unwrap(Class<T> nativeType);

    /**
     * 关闭客户端并释放底层资源（连接、线程池、缓冲区等）。
     *
     * <p>约定：实现必须<b>幂等</b>，重复调用不产生副作用、不抛异常；
     * 关闭后再调用数据接口的行为由实现定义（内置驱动会抛
     * {@link IllegalStateException}）。本方法不声明受检异常，
     * 因此可在 try-with-resources 中直接使用。</p>
     *
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbException
     *         当释放资源过程中发生不可恢复的错误时（实现可选择吞掉以避免
     *         掩盖业务异常，但需在驱动文档中说明）
     */
    @Override
    void close();
}
