package io.github.forget_the_bright.tsdb.common.model;

/**
 * 驱动可以声明支持的能力项集合。
 *
 * <p>在 SDK 中，能力声明是“可选功能”的唯一判定依据：调用方在使用任何非基线功能
 * （例如写数据、浏览位号、订阅报警）之前，必须先通过
 * {@link CapabilitySet#supports(Capability)} 询问驱动是否支持；驱动对于自己
 * 未声明的能力，必须抛出 {@code TSDB-UNSUPPORTED}（即
 * {@code TsdbUnsupportedException}，错误码 {@code TSDB-UNSUPPORTED}），
 * 而不是静默降级、返回空集合或返回 {@code null}。</p>
 *
 * <p>基线能力（所有驱动都必须具备，不需要声明，也不会出现在能力集中）为：
 * 健康检查 {@code health()}、位号目录查询 {@code listTags}、
 * 当前值读取 {@code currentValues()}、原始历史读取（{@code QueryMode.RAW}）、
 * 以及 {@code close()} 资源释放。</p>
 *
 * <p>能力集在客户端创建时确定，运行期不会变化，因此调用方可以缓存询问结果；
 * 但连接重建后应重新获取 {@link CapabilitySet}。本枚举是不可变值类型，
 * 线程安全。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public enum Capability {

    /**
     * 支持浏览位号目录（分页列出位号元数据，对应 {@code TagCatalog#listTags}）。
     */
    TAG_BROWSE,

    /**
     * 支持按名称通配符或属性条件检索位号，
     * 对应 {@link TagQuery#getNameMask()} 等过滤条件的服务端下推。
     */
    TAG_SEARCH,

    /**
     * 支持位号元数据的新增、修改、删除（位号本身的 CRUD，不含采样点写入）。
     */
    TAG_CRUD,

    /**
     * 支持读取位号当前值（最新值），对应 {@code CurrentValueReader}。
     */
    CURRENT_VALUE,

    /**
     * 支持原始历史数据读取，即 {@link QueryMode#RAW} 模式。
     */
    RAW_HISTORY,

    /**
     * 支持区间聚合历史读取，即 {@link QueryMode#AGGREGATED} 模式，
     * 需同时指定 {@link Aggregation} 与聚合间隔。
     */
    AGGREGATED_HISTORY,

    /**
     * 支持插值历史读取，即 {@link QueryMode#INTERPOLATED} 模式，
     * 通常配合 {@link FillPolicy} 使用。
     */
    INTERPOLATED_HISTORY,

    /**
     * 支持等间隔采样历史读取，即 {@link QueryMode#SAMPLED} 模式。
     */
    SAMPLED_HISTORY,

    /**
     * 支持趋势（TREND）查询，返回压缩后的趋势数据而非全量原始点。
     */
    TREND,

    /**
     * 支持累计量差分查询：按等间隔取累计值末值，并在内存中与上一区间相减。
     * 对应 {@code TsdbClient#deltaHistory()}。
     */
    CUMULATIVE_DELTA,

    /**
     * 支持采样点写入，对应 {@code WriteSession#write}。
     * 未声明该能力的驱动其 {@code WriteSession#isWritable()} 必须返回 {@code false}。
     */
    WRITE,

    /**
     * 支持删除历史数据（按时间区间或位号删除已落盘的采样点）。
     */
    DELETE,

    /**
     * 支持报警（Alarm）相关操作，如报警查询与确认。
     */
    ALARM,

    /**
     * 支持实时订阅（Subscription），如变化推送、流式回调。
     */
    SUBSCRIPTION,

    /**
     * 支持系统管理操作（如创建/删除数据库、查看服务端状态等运维级接口）。
     * 属于高危能力，通常需要更高权限的账号。
     */
    SYSTEM_ADMIN
}
