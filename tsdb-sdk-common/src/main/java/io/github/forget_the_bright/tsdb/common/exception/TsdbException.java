package io.github.forget_the_bright.tsdb.common.exception;

/**
 * 所有 SDK 失败的基异常（非受检异常）。
 *
 * <p>在 SDK 中承担“统一错误模型”的角色：不管底层是 HTTP 调用、私有协议、
 * 连接池还是序列化失败，都收敛成带稳定错误码的 {@link TsdbException}，
 * 让上层可以用一套逻辑处理重试、告警与提示，而不必 catch 各家厂商的异常类型。
 * 由于继承 {@link RuntimeException}，它不会强制调用方 catch，
 * 但配置错误、连接失败这类必须处理的问题在选择上仍通过文档提示。</p>
 *
 * <p><b>错误码</b>：{@link #getCode()} 返回结构化的 {@link TsdbErrorCode}，
 * {@link #getCodeString()} 返回其稳定的字符串形式（如 {@code "TSDB-TIMEOUT"}）。
 * 字符串码属于公开契约，不随版本变化，判断错误类型时请用它而不是异常文案。</p>
 *
 * <p><b>可重试性</b>：{@link #isRetryable()} 表示“同一请求在稍后重试是否可能成功”。
 * 默认由错误码推导（连接、超时、限流为可重试），也可由构造器显式指定以覆盖，
 * 例如厂商明确返回“临时不可用”时。注意：写入超时即便可重试，
 * 也可能已经部分成功，重试前需评估幂等性。</p>
 *
 * <p><b>上下文</b>：{@link #withContext(String, String, String, String)} 可附加
 * 实例名、驱动类型、操作名与厂商错误码，驱动应尽量在抛出前补齐，
 * 以便日志中直接定位“哪个实例的哪个操作失败了”。</p>
 *
 * <p>线程安全：上下文字段不是 {@code final}，由 {@code withContext} 写入，
 * 因此本类<b>不是</b>严格不可变的。约定的用法是在抛出前于单线程内一次性设置，
 * 之后只读；若需要跨线程传递异常对象，应视为只读。异常本身也不建议复用。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public class TsdbException extends RuntimeException {

    /**
     * 序列化版本号。
     */
    private static final long serialVersionUID = 1L;

    /**
     * 结构化错误码，非 {@code null}，默认 {@link TsdbErrorCode#INTERNAL}。
     */
    private final TsdbErrorCode code;

    /**
     * 该错误是否可重试。
     */
    private final boolean retryable;

    /**
     * 发生错误的实例名，可能为 {@code null}（未附加上下文时）。
     */
    private String instance;

    /**
     * 发生错误的驱动类型，可能为 {@code null}。
     */
    private String type;

    /**
     * 出错的操作名（如 {@code "listTags"}、{@code "read"}），可能为 {@code null}。
     */
    private String operation;

    /**
     * 厂商原始错误码，可能为 {@code null}；用于排查厂商特有问题。
     */
    private String vendorCode;

    /**
     * 构造异常，可重试性由错误码推导。
     *
     * @param code    结构化错误码，允许为 {@code null}
     *                （归一化为 {@link TsdbErrorCode#INTERNAL}）
     * @param message 错误描述，允许为 {@code null}
     */
    public TsdbException(TsdbErrorCode code, String message) {
        this(code, message, null);
    }

    /**
     * 构造异常并保留原始异常链，可重试性由错误码推导。
     *
     * @param code    结构化错误码，允许为 {@code null}
     *                （归一化为 {@link TsdbErrorCode#INTERNAL}）
     * @param message 错误描述，允许为 {@code null}
     * @param cause   导致本异常的底层异常，允许为 {@code null}；
     *                保留它可以避免丢失厂商原始栈信息
     */
    public TsdbException(TsdbErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code == null ? TsdbErrorCode.INTERNAL : code;
        this.retryable = this.code.isRetryable();
    }

    /**
     * 构造异常并显式指定可重试性，覆盖错误码的默认推导结果。
     *
     * <p>适用于“错误码相同但重试语义不同”的场景，例如厂商以
     * {@link TsdbErrorCode#INTERNAL} 返回了明确可重试的临时故障。</p>
     *
     * @param code      结构化错误码，允许为 {@code null}
     *                  （归一化为 {@link TsdbErrorCode#INTERNAL}）
     * @param message   错误描述，允许为 {@code null}
     * @param cause     导致本异常的底层异常，允许为 {@code null}
     * @param retryable 是否可重试；{@code true} 表示调用方可在退避后重试
     */
    public TsdbException(TsdbErrorCode code, String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.code = code == null ? TsdbErrorCode.INTERNAL : code;
        this.retryable = retryable;
    }

    /**
     * 返回结构化错误码。
     *
     * @return 非 {@code null} 的错误码；构造时传入 {@code null} 会得到
     *         {@link TsdbErrorCode#INTERNAL}
     */
    public TsdbErrorCode getCode() {
        return code;
    }

    /**
     * 返回错误码的稳定字符串形式，例如 {@code "TSDB-CONN"}。
     *
     * <p>该字符串属于公开契约，适合用于日志检索、监控告警规则与跨版本判断；
     * 不要使用枚举名（枚举名可能重命名，字符串码不会）。</p>
     *
     * @return 非 {@code null} 的短横线大写错误码字符串
     */
    public String getCodeString() {
        return code.getCode();
    }

    /**
     * 返回发生错误的实例名。
     *
     * @return 实例名，可能为 {@code null}（未通过
     *         {@link #withContext(String, String, String, String)} 附加时）
     */
    public String getInstance() {
        return instance;
    }

    /**
     * 返回驱动类型标识。
     *
     * @return 驱动类型（如 {@code "hls"}），可能为 {@code null}
     */
    public String getType() {
        return type;
    }

    /**
     * 返回出错的操作名。
     *
     * @return 操作名（如 {@code "listTags"}），可能为 {@code null}
     */
    public String getOperation() {
        return operation;
    }

    /**
     * 返回厂商原始错误码。
     *
     * <p>未做任何归一化，仅用于排查厂商特有问题；通用判断请使用
     * {@link #getCode()}。</p>
     *
     * @return 厂商错误码文本，可能为 {@code null}
     */
    public String getVendorCode() {
        return vendorCode;
    }

    /**
     * 判断该错误是否可以重试。
     *
     * <p>为 {@code true} 仅表示“重试有机会成功”，不表示“重试一定成功”，
     * 也不考虑重试次数与退避策略（这些由配置层的重试配置决定）。
     * 非幂等操作（尤其是写入）在重试前应自行评估重复写入的风险。</p>
     *
     * @return 可重试返回 {@code true}，否则 {@code false}
     */
    public boolean isRetryable() {
        return retryable;
    }

    /**
     * 附加请求上下文信息，并返回自身。
     *
     * <p>返回 {@code this} 是为了支持在 {@code throw} 语句中一行完成
     * “构造 + 附加上下文 + 抛出”，例如：</p>
     * <pre>{@code
     * throw new TsdbConnectionException("connect failed", e)
     *         .withContext(instanceName, "hls", "connect", vendorCode);
     * }</pre>
     *
     * <p>本方法会直接修改当前异常对象（不是创建副本），因此不要在异常已经
     * 被其他线程使用后再调用；正常用法是在抛出之前调用一次。</p>
     *
     * @param instance   实例名，允许为 {@code null}
     * @param type       驱动类型，允许为 {@code null}
     * @param operation  操作名，允许为 {@code null}
     * @param vendorCode 厂商原始错误码，允许为 {@code null}
     * @return {@code this}，便于链式调用与直接抛出
     */
    public TsdbException withContext(String instance, String type, String operation, String vendorCode) {
        this.instance = instance;
        this.type = type;
        this.operation = operation;
        this.vendorCode = vendorCode;
        return this;
    }

    /**
     * 返回包含错误码、上下文与消息的详细字符串表示。
     *
     * <p>格式为
     * {@code 异常类名{code=..., instance=..., type=..., operation=..., vendorCode=..., message=...}}，
     * 便于日志一眼看出是哪个实例的哪个操作失败。该格式仅用于日志排查，
     * 不构成稳定的解析契约；未附加的上下文会输出 {@code null}。</p>
     *
     * @return 非 {@code null} 的字符串表示
     */
    @Override
    public String toString() {
        return getClass().getSimpleName() + "{code=" + code.getCode()
                + ", instance=" + instance
                + ", type=" + type
                + ", operation=" + operation
                + ", vendorCode=" + vendorCode
                + ", message=" + getMessage() + '}';
    }
}
