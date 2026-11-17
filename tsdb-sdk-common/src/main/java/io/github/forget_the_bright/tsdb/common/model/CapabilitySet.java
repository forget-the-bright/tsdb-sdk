package io.github.forget_the_bright.tsdb.common.model;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 不可变的驱动能力快照。
 *
 * <p>由驱动在创建客户端时构造一次，通过
 * {@code TsdbClient#capabilities()} 暴露给调用方，用于在使用可选功能前做
 * 能力询问。典型用法：</p>
 * <pre>{@code
 * if (client.capabilities().supports(Capability.WRITE)) {
 *     client.writeSession().write(requests);
 * }
 * }</pre>
 *
 * <p>不可变性说明：实例内部持有一个由构造时传入集合复制而成的
 * {@link EnumSet}，并用 {@link Collections#unmodifiableSet} 包装，
 * 因此外部无法通过 {@link #asSet()} 修改内部状态，构造之后内容永不改变。
 * 唯一需要注意的细节是：包装的是内部 {@code EnumSet} 本身而非副本，
 * 由于该 {@code EnumSet} 在构造时已由 {@link #of(Capability...)} /
 * {@link #of(Collection)} 新建、外部不持有引用，实际仍是安全的。</p>
 *
 * <p>线程安全：对象在构造完成后完全不可变，可安全地在多线程间共享与缓存。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class CapabilitySet {

    /**
     * 内部能力集合，构造时已被 {@link Collections#unmodifiableSet} 包装，
     * 且为 {@link EnumSet} 以保证遍历顺序按枚举声明顺序、查询为 O(1)。
     */
    private final Set<Capability> capabilities;

    /**
     * 私有构造器，仅由本类的静态工厂方法调用。
     *
     * @param capabilities 已经由工厂方法新建、外部不持有引用的能力集合；
     *                     本构造器会对其做只读包装
     */
    private CapabilitySet(Set<Capability> capabilities) {
        this.capabilities = Collections.unmodifiableSet(capabilities);
    }

    /**
     * 以可变参数形式创建能力集合。
     *
     * <p>传入 {@code null} 数组等价于传入空集合，会得到“不支持任何可选能力”的空集；
     * 数组中的 {@code null} 元素会被 {@link Collections#addAll} 拒绝并抛出
     * {@link NullPointerException}（{@link EnumSet} 不允许 null 元素）。</p>
     *
     * @param capabilities 能力项，允许为 {@code null}（视为不传）；允许重复，
     *                     重复项会被自动去重
     * @return 非 {@code null} 的不可变能力集合；参数为空时返回 {@link #isEmpty()}
     *         为 {@code true} 的实例
     * @throws NullPointerException 当数组中包含 {@code null} 元素时
     */
    public static CapabilitySet of(Capability... capabilities) {
        EnumSet<Capability> set = EnumSet.noneOf(Capability.class);
        if (capabilities != null) {
            Collections.addAll(set, capabilities);
        }
        return new CapabilitySet(set);
    }

    /**
     * 以集合形式创建能力集合。
     *
     * <p>传入 {@code null} 等价于空集合；传入的集合会在方法内部被复制，
     * 因此调用方之后修改原集合不会影响本实例。</p>
     *
     * @param capabilities 能力项集合，允许为 {@code null}（视为不传）；
     *                     集合中不得包含 {@code null} 元素
     * @return 非 {@code null} 的不可变能力集合；参数为 {@code null} 或空集合时
     *         返回 {@link #isEmpty()} 为 {@code true} 的实例
     * @throws NullPointerException 当集合中包含 {@code null} 元素时
     */
    public static CapabilitySet of(Collection<Capability> capabilities) {
        EnumSet<Capability> set = EnumSet.noneOf(Capability.class);
        if (capabilities != null) {
            set.addAll(capabilities);
        }
        return new CapabilitySet(set);
    }

    /**
     * 判断驱动是否声明支持指定能力。
     *
     * <p>对 {@code null} 入参返回 {@code false} 而不是抛异常，
     * 便于调用方在能力项可能为空时安全询问。</p>
     *
     * @param capability 待询问的能力项，允许为 {@code null}
     * @return 当且仅当集合中包含该能力项时返回 {@code true}；
     *         {@code null} 或未声明时返回 {@code false}
     */
    public boolean supports(Capability capability) {
        return capabilities.contains(capability);
    }

    /**
     * 返回全部已声明能力的只读视图。
     *
     * <p>返回的集合按 {@link Capability} 的声明顺序遍历，不可修改；
     * 对其调用 {@code add}/{@code remove} 会抛
     * {@link UnsupportedOperationException}。返回的是内部集合本身（已只读包装），
     * 多次调用返回同一实例。</p>
     *
     * @return 非 {@code null} 的不可变集合；驱动未声明任何可选能力时为空集合
     */
    public Set<Capability> asSet() {
        return capabilities;
    }

    /**
     * 判断是否没有声明任何可选能力（即只具备基线能力）。
     *
     * @return 当且仅当能力集合为空时返回 {@code true}
     */
    public boolean isEmpty() {
        return capabilities.isEmpty();
    }

    /**
     * 返回能力集合的字符串表示，直接采用底层 {@link EnumSet} 的格式，
     * 例如 {@code [TAG_BROWSE, WRITE]}；空集返回 {@code []}。
     * 该格式仅用于日志排查，不构成稳定的序列化契约。
     *
     * @return 非 {@code null} 的字符串表示
     */
    @Override
    public String toString() {
        return capabilities.toString();
    }
}
