/**
 * 配置模型与 YAML 加载。
 *
 * <p>配置分三层：{@link io.github.forget_the_bright.tsdb.common.config.TsdbConfig}
 * 是根配置，包含默认实例名、安全策略和所有实例；
 * {@link io.github.forget_the_bright.tsdb.common.config.TsdbInstanceConfig} 描述
 * 单个实例的驱动类型、地址、认证、超时、重试和厂商选项；
 * {@link io.github.forget_the_bright.tsdb.common.config.SecurityConfig} 是全局
 * 安全底线，驱动只能在此基础上收紧，不能放宽。</p>
 *
 * <h2>安全默认值</h2>
 * <ul>
 *   <li>{@code allow-write=false}：默认只读，写入需显式开启。</li>
 *   <li>{@code allow-insecure-transport=false}：默认拒绝明文 HTTP；现场只有
 *       HTTP 时必须显式开启，并建议加 TLS 代理。</li>
 *   <li>{@code verify-hostname=true}：HTTPS 默认校验证书主机名。</li>
 *   <li>{@code audit-enabled=true}：写操作默认记录审计日志。</li>
 * </ul>
 *
 * <h2>加载方式</h2>
 * <p>{@link io.github.forget_the_bright.tsdb.common.config.TsdbConfigLoader}
 * 支持根节点直接写 {@code tsdb.*}，也支持包在 {@code tsdb:} 下；键名兼容
 * kebab-case 与 camelCase；值支持 {@code ${ENV}} 与 {@code ${ENV:default}}
 * 占位符，先查环境变量再查系统属性。敏感信息建议用环境变量注入，不写入
 * 版本库。</p>
 *
 * <h2>时间格式</h2>
 * <p>{@link io.github.forget_the_bright.tsdb.common.config.TimeConfig} 把
 * “请求格式化”“响应文本解析时区”“响应展示时区/格式”拆开配置，键为
 * {@code options.time.request.*} 与 {@code options.time.response.*}。
 * 公共模型的 {@code Sample.timestamp} 始终是绝对 {@link java.time.Instant}，
 * 时区只影响解析和展示，不改变时间值。默认全部 UTC，不假设东八区；铜陵 GE
 * 现场配 {@code response.target-zone: GMT+8} 即可“UTC 进出、北京时间展示”。</p>
 *
 * @since 0.1.0
 */
package io.github.forget_the_bright.tsdb.common.config;
