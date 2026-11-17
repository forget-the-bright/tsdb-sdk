package io.github.forget_the_bright.tsdb.common;

import io.github.forget_the_bright.tsdb.common.api.TsdbClient;
import io.github.forget_the_bright.tsdb.common.config.TsdbConfig;
import io.github.forget_the_bright.tsdb.common.config.TsdbInstanceConfig;
import io.github.forget_the_bright.tsdb.common.exception.TsdbConfigurationException;
import io.github.forget_the_bright.tsdb.common.spi.DriverContext;
import io.github.forget_the_bright.tsdb.common.spi.Metrics;
import io.github.forget_the_bright.tsdb.common.spi.TsdbDriver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ServiceLoader;

/**
 * 面向多驱动的策略门面：按配置选择驱动、创建并持有“当前唯一的活动客户端”。
 *
 * <p>典型部署只对接一套时序库，因此本类不会为每个实例各建一个连接，而是只创建
 * “当前实例”的客户端；切换实例必须显式调用 {@link #switchTo(String)}。切换采用
 * “先成功、后切换”的顺序：新客户端建连成功后才替换活动客户端并关闭旧客户端，
 * 因此切换失败时旧客户端仍然可用，业务侧不会出现“两边都不可用”的窗口期。</p>
 *
 * <p>使用方式通常是业务代码持有本对象、每次通过 {@link #client()} 取当前客户端：</p>
 *
 * <pre>{@code
 * TsdbManager manager = TsdbClients.manager().config(config).build();
 * TsdbClient client = manager.client();      // 首次调用时才建连
 * ...
 * manager.close();                           // 用完必须关闭
 * }</pre>
 *
 * <p>线程安全：本类是线程安全的。{@code client()} 采用 volatile 字段 + 双检锁，
 * 命中已建连接时无需加锁；{@link #client(String)}、{@link #switchTo(String)} 与
 * {@link #close()} 均在同一把对象锁上串行执行，保证“任意时刻至多一个活动客户端”。
 * 关闭后再调用任何取客户端的方法都会抛出 {@link TsdbConfigurationException}。</p>
 *
 * <p>生命周期：{@link #close()} 幂等，重复调用无副作用；关闭后本对象不可复用，
 * 需要重新连接请新建一个 {@code TsdbManager}。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class TsdbManager implements AutoCloseable {

    /** 根配置，构造后不再改变，非 {@code null}。 */
    private final TsdbConfig config;
    /** 类型标识到驱动实例的映射，保持注册顺序，非 {@code null}。 */
    private final Map<String, TsdbDriver> drivers;
    /** 指标出口，构造时已归一化，永不为 {@code null}。 */
    private final Metrics metrics;

    /** 当前活动客户端；为 {@code null} 表示尚未建连或已关闭。 */
    private volatile TsdbClient active;
    /** 当前活动实例名；与 {@link #active} 同步更新，二者同时为 {@code null} 或同时非 {@code null}。 */
    private volatile String activeInstance;
    /** 是否已关闭，置为 {@code true} 后不可再打开客户端。 */
    private volatile boolean closed;

    /**
     * 私有构造器，请通过 {@link #builder()} 创建。
     *
     * <p>构造阶段只做“参数是否可用”的静态校验，不做任何网络连接：即使配置里的地址
     * 不可达，构造也会成功，错误会延迟到 {@link #client()} 首次建连时暴露。</p>
     *
     * @param config  根配置，不能为 {@code null}
     * @param drivers 驱动集合，可为 {@code null}；{@code null} 元素或 {@code type()} 为
     *                {@code null} 的驱动会被忽略，同类型驱动后者覆盖前者
     * @param metrics 指标出口，可为 {@code null}，会被替换为 {@link Metrics#NOOP}
     * @throws IllegalArgumentException     当 {@code config} 为 {@code null} 时抛出
     * @throws TsdbConfigurationException 当配置中没有任何实例时抛出
     */
    private TsdbManager(TsdbConfig config, Collection<TsdbDriver> drivers, Metrics metrics) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        if (config.getInstances().isEmpty()) {
            throw new TsdbConfigurationException("at least one tsdb instance is required");
        }
        this.config = config;
        this.metrics = metrics == null ? Metrics.NOOP : metrics;
        this.drivers = new LinkedHashMap<String, TsdbDriver>();
        if (drivers != null) {
            for (TsdbDriver driver : drivers) {
                if (driver != null && driver.type() != null) {
                    this.drivers.put(driver.type(), driver);
                }
            }
        }
    }

    /**
     * 创建构建器，用于装配配置、驱动与指标出口。
     *
     * @return 新的构建器实例，非 {@code null}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 返回当前活动实例名。
     *
     * <p>首次 {@link #client()} 之前、以及 {@link #close()} 之后均返回 {@code null}；
     * 由于并发切换时该值可能瞬间变化，不要把它当作“连接一定可用”的判断依据。</p>
     *
     * @return 活动实例名，可能为 {@code null}
     */
    public String activeInstance() {
        return activeInstance;
    }

    /**
     * 返回配置中声明的全部实例名。
     *
     * <p>返回的是配置 Map 的只读视图（不可修改，但会随底层配置变化），按声明顺序迭代，
     * 与“哪些实例当前可用/已连接”无关——禁用的实例同样会出现在这里。</p>
     *
     * @return 不可修改的实例名集合，非 {@code null}
     */
    public Set<String> availableInstances() {
        return Collections.unmodifiableSet(config.getInstances().keySet());
    }

    /**
     * 返回本管理器使用的根配置。
     *
     * <p>返回的是构造时传入的同一个对象（不是副本），修改其中的集合等可变字段会影响
     * 后续实例查找；运行期应视为只读。</p>
     *
     * @return 根配置，非 {@code null}
     */
    public TsdbConfig config() {
        return config;
    }

    /**
     * 返回当前活动客户端；首次调用时按默认实例懒加载建立连接。
     *
     * <p>选择默认实例的规则：优先使用 {@code default-instance} 指定的实例；
     * 未配置时取配置中第一个 {@code enabled} 的实例；若没有启用的实例则抛出异常。</p>
     *
     * @return 活动客户端，非 {@code null}
     * @throws TsdbConfigurationException 管理器已关闭、没有启用的实例、目标实例的 type
     *                                    缺失或找不到对应驱动、驱动校验失败时抛出
     * @throws RuntimeException           驱动建连失败时按其自身约定抛出（如
     *                                    {@code TsdbConnectionException}）
     */
    public TsdbClient client() {
        TsdbClient current = active;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            ensureOpen();
            if (active == null) {
                startDefault();
            }
            return active;
        }
    }

    /**
     * 返回指定实例的客户端，必要时切换活动实例。
     *
     * <p>语义细节：</p>
     * <ul>
     *   <li>实例名为 {@code null} 或空白时等价于 {@link #client()}；</li>
     *   <li>该实例已经是活动实例时直接返回现有客户端，不会重新建连；</li>
     *   <li>切换到其他实例时先建新连接，成功后才关闭旧客户端，因此切换失败时
     *       旧客户端保持可用且 {@link #activeInstance()} 不变；</li>
     *   <li>本管理器同一时刻只保留一个厂商连接。</li>
     * </ul>
     *
     * @param instanceName 实例名，可为 {@code null}/空白（表示使用默认实例）
     * @return 目标实例的客户端，非 {@code null}
     * @throws TsdbConfigurationException 管理器已关闭、实例未定义、实例被禁用、类型缺失
     *                                    或找不到驱动时抛出
     * @throws RuntimeException           驱动建连失败时按其自身约定抛出
     */
    public synchronized TsdbClient client(String instanceName) {
        ensureOpen();
        if (instanceName == null || instanceName.trim().isEmpty()) {
            return client();
        }
        if (active != null && instanceName.equals(activeInstance)) {
            return active;
        }
        return open(instanceName, true);
    }

    /**
     * 显式切换活动实例，行为与 {@link #client(String)} 一致，但不返回客户端。
     *
     * <p>新连接建立失败时抛出异常，且旧客户端仍然有效——调用方可以捕获异常后继续
     * 使用 {@link #client()}。切换成功后旧客户端会被立即关闭，业务代码若缓存了旧
     * 客户端引用，需要重新通过 {@link #client()} 获取。</p>
     *
     * @param instanceName 目标实例名，不能为 {@code null} 或空白（空白会因“未知实例”而失败）
     * @throws TsdbConfigurationException 管理器已关闭、实例未定义/被禁用、类型缺失或找不到驱动时抛出
     * @throws RuntimeException           驱动建连失败时按其自身约定抛出
     */
    public synchronized void switchTo(String instanceName) {
        ensureOpen();
        open(instanceName, true);
    }

    /**
     * 关闭管理器并释放活动客户端。
     *
     * <p>幂等：重复调用直接返回。关闭后活动客户端与活动实例名都被清空，后续
     * {@link #client()} / {@link #client(String)} / {@link #switchTo(String)} 一律抛出
     * {@link TsdbConfigurationException}。本方法不吞掉底层客户端关闭时的异常——
     * 若关闭失败，异常会向上传播，但本对象仍已标记为关闭。</p>
     *
     * @throws RuntimeException 底层客户端关闭失败时按其自身约定抛出
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        TsdbClient current = active;
        active = null;
        activeInstance = null;
        if (current != null) {
            current.close();
        }
    }

    /**
     * 断言管理器尚未关闭。
     *
     * @throws TsdbConfigurationException 已关闭时抛出，消息为 {@code "tsdb manager is closed"}
     */
    private void ensureOpen() {
        if (closed) {
            throw new TsdbConfigurationException("tsdb manager is closed");
        }
    }

    /**
     * 按默认实例规则打开第一个连接。
     *
     * <p>优先使用配置的 {@code default-instance}（此处不再校验其是否存在，交给
     * {@link #open(String, boolean)} 通过 {@code instance(name)} 报“未知实例”）；
     * 未配置时取声明顺序上第一个 {@code enabled} 的实例。两者都没有则抛出异常，
     * 表示配置里全是禁用实例。</p>
     *
     * @throws TsdbConfigurationException 没有启用的实例，或打开过程失败时抛出
     */
    private void startDefault() {
        String name = config.getDefaultInstance();
        if (name == null || name.trim().isEmpty()) {
            for (Map.Entry<String, TsdbInstanceConfig> entry : config.getInstances().entrySet()) {
                if (entry.getValue().isEnabled()) {
                    name = entry.getKey();
                    break;
                }
            }
        }
        if (name == null) {
            throw new TsdbConfigurationException("no enabled tsdb instance is configured");
        }
        open(name, false);
    }

    /**
     * 打开指定实例的客户端，并在成功后就地替换活动客户端。
     *
     * <p>顺序很重要：先校验实例（存在、已启用、type 非空、驱动存在）、再调用
     * {@link TsdbDriver#validate} 做静态校验、最后才 {@code create}。只有 {@code create}
     * 成功返回后才更新 {@code active}/{@code activeInstance}，因此建连失败不会破坏
     * 现有连接。旧客户端在替换完成后才关闭，关闭异常会向上传播但状态已切换。</p>
     *
     * @param instanceName 实例名，必须存在于配置中
     * @param closeCurrent 是否在切换成功后关闭旧客户端；首次启动默认实例时传 {@code false}
     * @return 新创建的客户端，非 {@code null}
     * @throws TsdbConfigurationException 实例不存在/被禁用、type 缺失、找不到驱动、
     *                                    或驱动校验失败时抛出
     */
    private TsdbClient open(String instanceName, boolean closeCurrent) {
        TsdbInstanceConfig instanceConfig = config.instance(instanceName);
        if (!instanceConfig.isEnabled()) {
            throw new TsdbConfigurationException("tsdb instance is disabled: " + instanceName);
        }
        String type = instanceConfig.getType();
        if (type == null || type.trim().isEmpty()) {
            throw new TsdbConfigurationException("tsdb instance '" + instanceName + "' has no type");
        }
        TsdbDriver driver = drivers.get(type);
        if (driver == null) {
            throw new TsdbConfigurationException("no driver for type '" + type
                    + "'; available drivers: " + drivers.keySet());
        }
        driver.validate(instanceConfig);
        TsdbClient created = driver.create(instanceConfig,
                new DriverContext(instanceName, config, metrics));
        TsdbClient previous = active;
        active = created;
        activeInstance = instanceName;
        if (closeCurrent && previous != null) {
            previous.close();
        }
        return created;
    }

    /**
     * {@link TsdbManager} 的构建器：先收集配置、驱动与指标出口，再一次性创建管理器。
     *
     * <p>构建器不是线程安全的，仅用于启动阶段的单线程装配；装配完成后不应复用。
     * 未显式添加任何驱动时，{@link #build()} 会自动执行一次
     * {@link #discoverDrivers()}（基于 {@link ServiceLoader}）。</p>
     *
     * @author wanghao
     * @since 0.1.0
     */
    public static final class Builder {
        /** 根配置，必须由 {@link #config(TsdbConfig)} 提供后才能构建。 */
        private TsdbConfig config;
        /** 已注册的驱动列表，保持加入顺序，非 {@code null}。 */
        private final List<TsdbDriver> drivers = new ArrayList<TsdbDriver>();
        /** 指标出口，默认空实现。 */
        private Metrics metrics = Metrics.NOOP;

        /**
         * 私有构造器，请通过 {@link TsdbManager#builder()} 获取。
         */
        private Builder() {
        }

        /**
         * 设置根配置（必需）。
         *
         * <p>直接持有传入对象，不做拷贝；重复调用以最后一次为准。
         * 配置为 {@code null} 时构建阶段会抛出 {@link IllegalArgumentException}。</p>
         *
         * @param config 根配置，可为 {@code null}（构建时才报错）
         * @return 当前构建器，便于链式调用
         */
        public Builder config(TsdbConfig config) {
            this.config = config;
            return this;
        }

        /**
         * 追加一个驱动。
         *
         * <p>{@code null} 会被静默忽略；同类型驱动按加入顺序后者覆盖前者，
         * 因此手工注册可以覆盖 {@link ServiceLoader} 发现的同名实现。</p>
         *
         * @param driver 驱动实例，可为 {@code null}
         * @return 当前构建器，便于链式调用
         */
        public Builder driver(TsdbDriver driver) {
            if (driver != null) {
                this.drivers.add(driver);
            }
            return this;
        }

        /**
         * 批量追加驱动。
         *
         * @param drivers 驱动集合，可为 {@code null}（忽略）；集合内的 {@code null} 元素
         *                会保留，最终在构造管理器时被过滤
         * @return 当前构建器，便于链式调用
         */
        public Builder drivers(Collection<TsdbDriver> drivers) {
            if (drivers != null) {
                this.drivers.addAll(drivers);
            }
            return this;
        }

        /**
         * 通过 {@link ServiceLoader} 发现 classpath 上的全部驱动并追加。
         *
         * <p>当没有显式添加任何驱动时，{@link #build()} 会自动调用本方法，因此一般无需
         * 手工调用。发现过程读取线程上下文类加载器可见的
         * {@code META-INF/services/io.github.forget_the_bright.tsdb.common.spi.TsdbDriver}；
         * 重复调用会重复追加（同类型在构造管理器时按后者覆盖）。</p>
         *
         * @return 当前构建器，便于链式调用
         */
        public Builder discoverDrivers() {
            for (TsdbDriver driver : ServiceLoader.load(TsdbDriver.class)) {
                this.drivers.add(driver);
            }
            return this;
        }

        /**
         * 设置指标出口。
         *
         * <p>{@code null} 等价于 {@link Metrics#NOOP}（在构造管理器时统一归一化）。</p>
         *
         * @param metrics 指标实现，可为 {@code null}
         * @return 当前构建器，便于链式调用
         */
        public Builder metrics(Metrics metrics) {
            this.metrics = metrics;
            return this;
        }

        /**
         * 创建管理器。
         *
         * <p>若从未添加任何驱动，会先自动执行一次 {@link #discoverDrivers()}。
         * 本阶段只做校验与对象组装，不建立任何数据库连接；真正的连接发生在
         * {@link TsdbManager#client()} 首次被调用时。</p>
         *
         * @return 新的管理器实例，非 {@code null}
         * @throws IllegalArgumentException  未设置配置（{@code config == null}）时抛出
         * @throws TsdbConfigurationException 配置中没有任何实例时抛出
         */
        public TsdbManager build() {
            if (drivers.isEmpty()) {
                discoverDrivers();
            }
            return new TsdbManager(config, drivers, metrics);
        }
    }
}
