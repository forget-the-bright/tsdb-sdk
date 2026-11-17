/**
 * GE 原生端点目录。
 *
 * <p>本包只放“端点元数据”，不放 HTTP 实现。这样做的原因是 GE 有 92 个端点，
 * 如果每个端点都写一个方法，会出现大量只有一行转发的重复代码，而且新增端点
 * 时容易漏掉。改为“枚举描述端点 + 一个通用执行器”后：</p>
 *
 * <ul>
 *   <li>端点清单集中在一处，评审时可以逐项核对原 SDK；</li>
 *   <li>路径、HTTP 方法、参数位置与原枚举一一对应，能力不被裁剪；</li>
 *   <li>{@code GeNativeApi.invoke} 负责认证、重试、编码和错误码校验；</li>
 *   <li>调用方仍然可以直接拿到厂商原始 JSON，不受公共模型限制。</li>
 * </ul>
 *
 * <p>{@link io.github.forget_the_bright.tsdb.ge.nativeapi.GeEndpoint} 的每个
 * 常量都带中文说明、路径和 HTTP 方法；模块归属用 {@code module()} 查询，便于
 * 按 Data/Tags/Systems/Collectors/Alarms 分组统计和生成文档。</p>
 *
 * @since 0.1.0
 */
package io.github.forget_the_bright.tsdb.ge.nativeapi;
