package io.github.forget_the_bright.tsdb.common;

import io.github.forget_the_bright.tsdb.common.config.TsdbConfig;
import io.github.forget_the_bright.tsdb.common.config.TsdbConfigLoader;

import java.nio.file.Path;

/**
 * 非 Spring 应用的统一入口：以静态工厂方法装配 {@link TsdbManager}。
 *
 * <p>本类只是门面，本身不持有任何状态（私有构造器、不可实例化），真正的连接管理、
 * 驱动选择与生命周期都委托给 {@link TsdbManager}。它存在的意义是给不使用
 * Spring Boot Starter 的应用一个固定、易发现的调用点，并顺手支持“直接从 YAML 文件
 * 创建管理器”这种最常见的用法。</p>
 *
 * <pre>{@code
 * TsdbManager manager = TsdbClients.manager()
 *         .config(TsdbConfigLoader.load(Paths.get("tsdb.yml")))
 *         .build();
 * TsdbClient client = manager.client();
 * }</pre>
 *
 * <p>也可以一行完成：</p>
 *
 * <pre>{@code
 * TsdbManager manager = TsdbClients.fromYaml(Paths.get("tsdb.yml"));
 * }</pre>
 *
 * <p>注意事项：这里创建的管理器不会被任何容器托管，用完必须自行调用
 * {@link TsdbManager#close()} 释放连接；本类的方法均无状态，可安全地并发调用。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class TsdbClients {

    /**
     * 私有构造器：本类是纯静态工具类，不允许实例化。
     */
    private TsdbClients() {
    }

    /**
     * 返回一个空白构建器，便于链式补充配置、驱动与指标出口。
     *
     * <p>此时既没有配置也没有驱动，直接 {@code build()} 会失败；至少要调用
     * {@link TsdbManager.Builder#config(TsdbConfig)}。</p>
     *
     * @return 新的构建器，非 {@code null}
     */
    public static TsdbManager.Builder manager() {
        return TsdbManager.builder();
    }

    /**
     * 用现成的根配置直接创建管理器。
     *
     * <p>等价于 {@code TsdbManager.builder().config(config).build()}：驱动在
     * {@code build()} 阶段通过 {@link java.util.ServiceLoader} 自动发现。本方法不做
     * 任何连接操作，连接延迟到首次 {@link TsdbManager#client()} 调用。</p>
     *
     * @param config 根配置，不能为 {@code null}；为 {@code null} 时构建会抛
     *               {@link IllegalArgumentException}
     * @return 新管理器，非 {@code null}
     * @throws IllegalArgumentException   {@code config} 为 {@code null} 时抛出
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbConfigurationException
     *         配置中没有任何实例时抛出
     */
    public static TsdbManager fromConfig(TsdbConfig config) {
        return TsdbManager.builder().config(config).build();
    }

    /**
     * 从 YAML 配置文件创建管理器。
     *
     * <p>先通过 {@link TsdbConfigLoader#load(Path)} 解析文件，再委托
     * {@link #fromConfig(TsdbConfig)}。文件读取与解析错误（文件不存在、YAML 语法错误、
     * 结构不合法）会在本方法内同步抛出，不会延迟。</p>
     *
     * @param path YAML 文件路径，不能为 {@code null}
     * @return 新管理器，非 {@code null}
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbConfigurationException
     *         文件不可读、内容无法解析或缺少必要字段时抛出
     * @throws IllegalArgumentException {@code path} 为 {@code null} 时抛出
     */
    public static TsdbManager fromYaml(Path path) {
        return fromConfig(TsdbConfigLoader.load(path));
    }
}
