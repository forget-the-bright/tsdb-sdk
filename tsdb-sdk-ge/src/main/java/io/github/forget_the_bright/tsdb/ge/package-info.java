/**
 * GE Proficy Historian 驱动。
 *
 * <h2>协议特征</h2>
 * <p>GE 的 REST API 面很大，共 92 个端点，分布在 Data、Tags、Systems、
 * Collectors、Alarms 五个模块；认证走 OAuth2 密码模式。公共层只抽取最常用的
 * 标签、实时值、原始/聚合/插值历史，其余标签管理、采集器运维、系统配置、告警
 * 能力全部保留在原生层。</p>
 *
 * <h2>认证</h2>
 * <p>{@code POST /uaa/oauth/token?grant_type=password&username=...&password=...}，
 * 请求头 {@code Authorization: Basic base64(clientId:clientSecret)}；返回
 * {@code access_token}、{@code expires_in}。令牌默认缓存 {@code expires_in-5}
 * 秒，401 时清空并重试一次。令牌端点在主机根路径，不在
 * {@code /historian-rest-api} 前缀下。</p>
 *
 * <h2>数据语义</h2>
 * <ul>
 *   <li>时间参数统一用 UTC ISO 格式 {@code yyyy-MM-dd'T'HH:mm:ss'Z'}；响应
 *       时间戳是服务器本地时间 {@code yyyy-MM-dd HH:mm:ss}，参照 SDK 的约定
 *       按 GMT+8 解析；带显式偏移或 {@code Z} 的值按显式时区解析。</li>
 *   <li>质量码：BAD=0、UNCERTAIN=1、NA=2、GOOD=3。</li>
 *   <li>返回值一律是字符串，驱动按 DataType 和内容推断 long/double/boolean/
 *       string；推断失败保留字符串，不丢数据。</li>
 *   <li>业务错误码在 {@code ErrorCode} 字段，HTTP 200 不代表成功；驱动会校验
 *       非 0 错误码并抛 {@code TsdbQueryException}。</li>
 * </ul>
 *
 * <h2>原生能力</h2>
 * <p>{@link io.github.forget_the_bright.tsdb.ge.nativeapi.GeEndpoint} 枚举列出
 * 全部 92 个端点及其路径、方法、参数位置；
 * {@link io.github.forget_the_bright.tsdb.ge.internal.GeNativeApi#invoke} 可以
 * 调用任意端点，返回完整 JSON，避免为每个端点复制一个薄方法。少量常用端点仍
 * 提供类型化便捷方法。</p>
 *
 * @since 0.1.0
 */
package io.github.forget_the_bright.tsdb.ge;
