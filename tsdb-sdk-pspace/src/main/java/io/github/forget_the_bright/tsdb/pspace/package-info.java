/**
 * 力控（ForceControl）pSpace Web API 驱动的公共入口。
 *
 * <p>本包只暴露对外稳定的三类对象：</p>
 * <ul>
 *   <li>{@link io.github.forget_the_bright.tsdb.pspace.PspaceDriver}：SPI 入口，
 *       类型标识 {@code pspace}，由 {@code TsdbManager} 通过
 *       {@link java.util.ServiceLoader} 发现；</li>
 *   <li>{@link io.github.forget_the_bright.tsdb.pspace.PspaceClient}：公共
 *       {@code TsdbClient} 门面实现，装配目录、当前值、历史、差分与写入五个适配器；</li>
 *   <li>{@link io.github.forget_the_bright.tsdb.pspace.PspaceConfig}：已校验的
 *       不可变配置，含字符集、分页、自动刷新与时间格式策略。</li>
 * </ul>
 *
 * <p>两个子包各自承担一层，均为<b>非公共 API</b>：</p>
 * <ul>
 *   <li>{@code internal}：HTTP 协议、响应解析、模型映射、位号缓存与原生端点；</li>
 *   <li>{@code internal.adapter}：公共接口（目录/当前值/历史/差分/写入）的适配器实现。</li>
 * </ul>
 *
 * <p>推荐阅读顺序：{@code PspaceDriver} → {@code PspaceClient} → {@code PspaceConfig}
 * → {@code internal.adapter} → {@code internal.PspaceNativeApi}
 * → {@code internal.PspaceHttp}/{@code PspaceResponseParser} → {@code internal.PspaceModels}。</p>
 *
 * <p>协议事实以 {@code docs/16-pSpace接入与验证.md} 为准：响应外层有两种形态
 * （{@code {code,mesg,data}} 信封与裸数据）、字符集默认 GBK、聚合间隔单位为秒、
 * 点位缓存未热时需要先 POST {@code /Refresh}。驱动已内建对这些事实的处理，
 * 调用方无需手工刷新。</p>
 *
 * @author wanghao
 * @since 0.1.2
 */
package io.github.forget_the_bright.tsdb.pspace;