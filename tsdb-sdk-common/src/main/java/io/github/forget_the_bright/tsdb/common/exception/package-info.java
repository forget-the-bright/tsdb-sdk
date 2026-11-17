/**
 * 统一异常与错误码。
 *
 * <p>公共层只抛 {@link io.github.forget_the_bright.tsdb.common.exception.TsdbException}
 * 及其子类，调用方可以按错误码或异常类型统一处理，不需要捕获各厂商的
 * {@code ApiException}、{@code IOException} 或 JDBC 异常。</p>
 *
 * <h2>错误码分段</h2>
 * <ul>
 *   <li>{@code TSDB-CONFIG}：配置缺失、实例不存在、驱动未注册。</li>
 *   <li>{@code TSDB-CONN}：网络不可达、连接被拒、HTTP 5xx。</li>
 *   <li>{@code TSDB-AUTH}：认证失败、令牌过期、权限不足。</li>
 *   <li>{@code TSDB-TIMEOUT}：连接/读取超时。</li>
 *   <li>{@code TSDB-RATE-LIMIT}：服务端限流。</li>
 *   <li>{@code TSDB-UNSUPPORTED}：当前驱动不支持该能力或参数组合。</li>
 *   <li>{@code TSDB-QUERY}：语法错误、业务错误码、查询被服务端拒绝。</li>
 *   <li>{@code TSDB-DATA}：响应无法解析、时间/数值格式非法。</li>
 *   <li>{@code TSDB-INTERNAL}：不应发生的内部错误。</li>
 * </ul>
 *
 * <p>异常携带 {@code instance}、{@code type}、{@code operation} 和
 * {@code vendorCode} 上下文；驱动应通过 {@code withContext} 补充信息，不要
 * 丢失厂商原始错误码。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
package io.github.forget_the_bright.tsdb.common.exception;
