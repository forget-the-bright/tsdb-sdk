package io.github.forget_the_bright.tsdb.common.exception;

/**
 * 查询错误异常：查询条件不合法，或服务端拒绝执行该查询。
 *
 * <p>固定使用错误码 {@link TsdbErrorCode#QUERY}（字符串码 {@code "TSDB-QUERY"}），
 * 属于<b>不可重试</b>错误：同样的查询再发一次仍会失败，必须修改查询条件
 * 或权限配置后重发。</p>
 *
 * <p>使用场景：时间区间非法（起点不早于终点）、聚合方式与该厂商的历史查询
 * 模式不兼容（如请求聚合历史但驱动不支持）、{@code maxPoints} 超出服务端上限、
 * 位号不存在、查询语句被服务端判定为语法错误等。</p>
 *
 * <p>注意：查询参数在 SDK 侧已做基础校验（例如区间顺序、必须项非空），
 * 因此能在客户端发现的问题通常抛 {@link IllegalArgumentException}；
 * 本异常主要用于服务端拒绝或驱动能力不足导致的查询失败。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public class TsdbQueryException extends TsdbException {

    /**
     * 序列化版本号。
     */
    private static final long serialVersionUID = 1L;

    /**
     * 构造查询错误异常。
     *
     * @param message 错误描述，建议包含查询条件摘要与服务端返回的原因，
     *                允许为 {@code null}
     */
    public TsdbQueryException(String message) {
        super(TsdbErrorCode.QUERY, message);
    }

    /**
     * 构造查询错误异常并保留底层原因。
     *
     * @param message 错误描述，允许为 {@code null}
     * @param cause   导致查询失败的底层异常（如服务端错误响应解析异常），
     *                允许为 {@code null}
     */
    public TsdbQueryException(String message, Throwable cause) {
        super(TsdbErrorCode.QUERY, message, cause);
    }
}
