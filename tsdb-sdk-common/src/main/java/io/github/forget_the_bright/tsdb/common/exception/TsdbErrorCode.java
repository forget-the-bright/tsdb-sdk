package io.github.forget_the_bright.tsdb.common.exception;

/**
 * 稳定的、与厂商无关的错误码。
 *
 * <p>在 SDK 中承担“错误分类字典”的角色：每个错误码由一个不可变的字符串码
 * （如 {@code "TSDB-TIMEOUT"}）和一个可重试标记组成。字符串码是
 * <b>公开契约的一部分，不允许修改</b>——监控规则、日志检索、跨版本判断都依赖它；
 * 枚举常量名可以重构，字符串码不行。</p>
 *
 * <p><b>可重试语义</b>：{@link #isRetryable()} 是该错误码的默认重试建议，
 * 表示“同一请求在稍后重试是否可能成功”。驱动可以针对具体场景用
 * {@code TsdbException} 的显式构造器覆盖它。</p>
 *
 * <p>本枚举不可变、线程安全。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public enum TsdbErrorCode {

    /**
     * 配置错误：配置项缺失、类型不合法、必填项为空等。
     * 属于调用方问题，重试无意义，必须修正配置。
     * 字符串码 {@code "TSDB-CONFIG"}，不可重试。
     */
    CONFIG("TSDB-CONFIG", false),

    /**
     * 连接错误：建连失败、连接中断、连接池耗尽等。
     * 通常由网络抖动或服务端重启引起，退避后重试有机会成功。
     * 字符串码 {@code "TSDB-CONN"}，可重试。
     */
    CONNECTION("TSDB-CONN", true),

    /**
     * 鉴权错误：凭证缺失、凭证错误、权限不足。
     * 重试不会改变结果，需要检查账号与授权。
     * 字符串码 {@code "TSDB-AUTH"}，不可重试。
     */
    AUTH("TSDB-AUTH", false),

    /**
     * 超时：请求已发出但在约定时间内未得到响应。
     * 可重试，但写入类操作超时可能已部分生效，重试需考虑幂等性。
     * 字符串码 {@code "TSDB-TIMEOUT"}，可重试。
     */
    TIMEOUT("TSDB-TIMEOUT", true),

    /**
     * 限流：服务端因配额或保护机制拒绝了本次请求（如 HTTP 429）。
     * 应按服务端建议的退避时间重试。
     * 字符串码 {@code "TSDB-RATE-LIMIT"}，可重试。
     */
    RATE_LIMIT("TSDB-RATE-LIMIT", true),

    /**
     * 不支持：驱动未实现该能力，或厂商不支持所请求的模式/过滤条件。
     * 重试无意义，调用方应先通过能力集判断。
     * 字符串码 {@code "TSDB-UNSUPPORTED"}，不可重试。
     */
    UNSUPPORTED("TSDB-UNSUPPORTED", false),

    /**
     * 查询错误：查询语法不合法、参数越界、服务端拒绝执行等。
     * 属于请求本身的问题，重试前需要修改查询条件。
     * 字符串码 {@code "TSDB-QUERY"}，不可重试。
     */
    QUERY("TSDB-QUERY", false),

    /**
     * 数据错误：数据内容非法（类型不匹配、时间戳越界、质量码无法解析）
     * 或响应数据无法解析。
     * 字符串码 {@code "TSDB-DATA"}，不可重试。
     */
    DATA("TSDB-DATA", false),

    /**
     * 内部错误：SDK 或驱动内部未预期的异常，也是错误码缺失时的兜底值。
     * 通常意味着缺陷，建议连同上下文信息上报。
     * 字符串码 {@code "TSDB-INTERNAL"}，不可重试。
     */
    INTERNAL("TSDB-INTERNAL", false);

    /**
     * 稳定的字符串错误码，属于公开契约。
     */
    private final String code;

    /**
     * 该错误码的默认可重试建议。
     */
    private final boolean retryable;

    /**
     * 枚举构造器。
     *
     * @param code      稳定的字符串错误码，不能为 {@code null}
     * @param retryable 默认可重试建议
     */
    TsdbErrorCode(String code, boolean retryable) {
        this.code = code;
        this.retryable = retryable;
    }

    /**
     * 返回稳定的字符串错误码，例如 {@code "TSDB-CONFIG"}。
     *
     * <p>该值跨版本保持稳定，可直接用于日志检索与告警规则。</p>
     *
     * @return 非 {@code null} 的字符串错误码
     */
    public String getCode() {
        return code;
    }

    /**
     * 返回该错误码的默认可重试建议。
     *
     * <p>实际异常上的可重试性可能被显式覆盖，判断时应优先使用
     * {@code TsdbException#isRetryable()}。</p>
     *
     * @return 建议可重试返回 {@code true}，否则 {@code false}
     */
    public boolean isRetryable() {
        return retryable;
    }
}
