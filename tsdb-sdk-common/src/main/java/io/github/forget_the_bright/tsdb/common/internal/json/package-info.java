/**
 * 跨驱动共享的 JSON 工具（fastjson 实现）。
 *
 * <p><b>非公共 API</b>：本包是 {@code common.internal} 的 JSON 子包，签名可能随内部重构
 * 变化。驱动模块可以使用，宿主应用不应直接依赖。</p>
 *
 * <h2>本包类清单与「改什么找哪个类」</h2>
 * <ul>
 *   <li>{@code Json} —— fastjson 全局安全配置的唯一起点：AutoType 关闭、safeMode 开启。
 *       由 {@code JsonText} 在首次解析前调用 {@code Json.init()} 触发，驱动不需要显式初始化。
 *       改 JSON 安全边界只改这里。</li>
 *   <li>{@code JsonText} —— 树解析与宽容取值：{@code parse/parseObject/parseArray}、
 *       {@code object/array}（根类型不符抛 {@code TsdbDataException}）、
 *       {@code text}（严格字段名）与 {@code textIgnoreCase}（HLS 的大小写不敏感字段）、
 *       {@code toJson}（序列化）。原生 API 返回 {@code Object} 时，调用方用
 *       {@code JsonText.object/array} 显式转换，不要到处强转。</li>
 * </ul>
 *
 * <h2>边界</h2>
 * <p>只做树解析，禁止 {@code parseObject(text, Class)} 形态的反序列化；公共模型的序列化
 * 契约（{@code Instant} → ISO 字符串、{@code Date} → epoch 毫秒、null 字段不输出）由
 * {@code common.model} 的 {@code SampleTimeFieldsTest} 钉住，不在本包配置。</p>
 *
 * @since 0.1.0
 */
package io.github.forget_the_bright.tsdb.common.internal.json;