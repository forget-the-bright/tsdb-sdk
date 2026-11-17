package io.github.forget_the_bright.tsdb.common.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个时序库实例的配置（可变 POJO）。
 *
 * <p>本类刻意保持为纯 POJO、无任何框架依赖，因此既能被 Spring Boot 的
 * {@code @ConfigurationProperties} 松散绑定（{@code base-url} 与 {@code baseUrl}
 * 均可），也能由 {@link TsdbConfigLoader} 手工填充，或由业务代码直接 new 出来设置。</p>
 *
 * <p>关键语义：</p>
 * <ul>
 *   <li>{@code type} 是驱动选择键，必须与某个 {@code TsdbDriver#type()} 完全一致
 *       （内置值：{@code hls}、{@code ge}、{@code iotdb}），缺失或找不到驱动时
 *       获取客户端会抛出 {@code TsdbConfigurationException}；</li>
 *   <li>{@code enabled} 为 {@code false} 时该实例不可被选中，既不会作为默认实例，
 *       显式请求也会被拒绝；</li>
 *   <li>{@code base-url} 是当前三个内置驱动使用的服务地址（含 scheme 与端口，
 *       不带结尾斜杠）；{@code protocol}、{@code endpoint}、{@code node-urls}
 *       是为集群/多节点部署预留的字段，由具体驱动自行解释，公共层不做处理；</li>
 *   <li>{@link #getAuth()}、{@link #getTimeout()}、{@link #getRetry()}、
 *       {@link #getOptions()} 永不为 {@code null}，便于驱动无判空地读取。</li>
 * </ul>
 *
 * <p>线程安全：本类不是线程安全的，应在启动阶段完成绑定/赋值，运行期只读。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public class TsdbInstanceConfig {

    /** 驱动类型键，对应 {@code TsdbDriver#type()}，如 hls、ge、iotdb；未配置时为 {@code null}。 */
    private String type;
    /** 传输协议提示（如 http/https），保留字段，由驱动自行解释；未配置时为 {@code null}。 */
    private String protocol;
    /** 是否启用该实例，默认 {@code true}；关闭后不可被选中。 */
    private boolean enabled = true;
    /** 服务基地址，形如 {@code https://host:port}，不带结尾斜杠；内置驱动必填。 */
    private String baseUrl;
    /** 单节点/集群入口地址，保留字段，由驱动自行解释；未配置时为 {@code null}。 */
    private String endpoint;
    /** 集群节点地址列表，保留字段，默认空列表，永不为 {@code null}。 */
    private List<String> nodeUrls = new ArrayList<String>();
    /** 认证配置，永不为 {@code null}。 */
    private AuthConfig auth = new AuthConfig();
    /** 超时配置（毫秒），永不为 {@code null}。 */
    private TimeoutConfig timeout = new TimeoutConfig();
    /** 重试配置，永不为 {@code null}。 */
    private RetryConfig retry = new RetryConfig();
    /** 驱动私有参数，值可以是任意对象（数字、布尔、嵌套 Map 等），永不为 {@code null}。 */
    private Map<String, Object> options = new LinkedHashMap<String, Object>();

    /**
     * 返回驱动类型键。
     *
     * @return 类型标识（如 {@code "hls"}）；未配置时为 {@code null}
     */
    public String getType() {
        return type;
    }

    /**
     * 设置驱动类型键。
     *
     * <p>必须与目标驱动 {@code type()} 的返回值完全一致（区分大小写、不做 trim）；
     * 本方法不校验可用性，匹配失败在创建客户端时才会暴露。</p>
     *
     * @param type 类型标识，可为 {@code null}
     */
    public void setType(String type) {
        this.type = type;
    }

    /**
     * 返回协议提示。
     *
     * @return 协议字符串（如 {@code "http"}）；未配置时为 {@code null}
     */
    public String getProtocol() {
        return protocol;
    }

    /**
     * 设置协议提示。
     *
     * <p>属于预留字段，当前内置驱动从 {@code base-url} 的 scheme 推导协议，
     * 不读取本属性；自定义驱动可自行解释。</p>
     *
     * @param protocol 协议字符串，可为 {@code null}
     */
    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    /**
     * 返回实例是否启用。
     *
     * @return {@code true} 表示可被选中；默认 {@code true}
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置实例是否启用。
     *
     * <p>关闭的实例不会被选为默认实例，显式请求时
     * {@code TsdbManager} 会抛出 {@code "tsdb instance is disabled: <name>"}。
     * 本方法只是读取时判断，不会主动关闭已建立的连接。</p>
     *
     * @param enabled {@code true} 启用，默认 {@code true}
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 返回服务基地址。
     *
     * @return 基地址，如 {@code https://127.0.0.1:8243}；未配置时为 {@code null}
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * 设置服务基地址。
     *
     * <p>必须包含 scheme（{@code http} 或 {@code https}）与端口；是否允许明文由
     * {@link SecurityConfig#isAllowInsecureTransport()} 决定。结尾斜杠、路径前缀
     * 由驱动在初始化时归一化，这里无需处理。</p>
     *
     * @param baseUrl 基地址，可为 {@code null}（内置驱动会在校验时报错）
     */
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * 返回单节点/集群入口地址。
     *
     * @return 入口地址；未配置时为 {@code null}
     */
    public String getEndpoint() {
        return endpoint;
    }

    /**
     * 设置单节点/集群入口地址。
     *
     * <p>预留字段，当前内置驱动不读取，仅供自定义驱动描述非 HTTP 拓扑。</p>
     *
     * @param endpoint 入口地址，可为 {@code null}
     */
    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    /**
     * 返回集群节点地址列表。
     *
     * @return 可变的节点地址列表，默认空列表，非 {@code null}
     */
    public List<String> getNodeUrls() {
        return nodeUrls;
    }

    /**
     * 设置集群节点地址列表。
     *
     * <p>直接持有传入列表的引用，不做拷贝；传入 {@code null} 时替换为空列表，
     * 保证 {@link #getNodeUrls()} 不回 {@code null}。预留字段，内置驱动不读取。</p>
     *
     * @param nodeUrls 节点地址列表，可为 {@code null}
     */
    public void setNodeUrls(List<String> nodeUrls) {
        this.nodeUrls = nodeUrls == null ? new ArrayList<String>() : nodeUrls;
    }

    /**
     * 返回认证配置。
     *
     * @return 认证配置，永不为 {@code null}
     */
    public AuthConfig getAuth() {
        return auth;
    }

    /**
     * 设置认证配置。
     *
     * @param auth 认证配置；传入 {@code null} 时替换为空的认证配置（所有字段为 {@code null}）
     */
    public void setAuth(AuthConfig auth) {
        this.auth = auth == null ? new AuthConfig() : auth;
    }

    /**
     * 返回超时配置。
     *
     * @return 超时配置（毫秒），永不为 {@code null}
     */
    public TimeoutConfig getTimeout() {
        return timeout;
    }

    /**
     * 设置超时配置。
     *
     * @param timeout 超时配置；传入 {@code null} 时替换为默认超时（建连 5s、读 30s、写 30s）
     */
    public void setTimeout(TimeoutConfig timeout) {
        this.timeout = timeout == null ? new TimeoutConfig() : timeout;
    }

    /**
     * 返回重试配置。
     *
     * @return 重试配置，永不为 {@code null}
     */
    public RetryConfig getRetry() {
        return retry;
    }

    /**
     * 设置重试配置。
     *
     * @param retry 重试配置；传入 {@code null} 时替换为默认策略（2 次尝试、200ms 间隔）
     */
    public void setRetry(RetryConfig retry) {
        this.retry = retry == null ? new RetryConfig() : retry;
    }

    /**
     * 返回驱动私有参数表。
     *
     * <p>返回内部可变 Map 本身，按插入顺序迭代，永不为 {@code null}；
     * 值保留 YAML 解析出的原始类型（{@link Integer}/{@link Boolean}/{@link Map} 等）。</p>
     *
     * @return 可变的参数表，非 {@code null}
     */
    public Map<String, Object> getOptions() {
        return options;
    }

    /**
     * 设置驱动私有参数表。
     *
     * <p>直接持有传入的 Map 引用，不做拷贝；传入 {@code null} 时替换为空的
     * {@link LinkedHashMap}。</p>
     *
     * @param options 参数表，可为 {@code null}
     */
    public void setOptions(Map<String, Object> options) {
        this.options = options == null ? new LinkedHashMap<String, Object>() : options;
    }

    /**
     * 以字符串形式读取一个驱动私有参数。
     *
     * <p>内部通过 {@link String#valueOf(Object)} 转换：数字会变成十进制字面量，
     * 布尔变成 {@code "true"}/{@code "false"}，嵌套集合则得到其 {@code toString()}
     * 结果，因此只适合读取标量参数。键不存在或值为 {@code null} 时返回 {@code null}，
     * 便于调用方用默认值兜底。</p>
     *
     * @param key 参数名，区分大小写
     * @return 参数值的字符串形式；键不存在或值为 {@code null} 时返回 {@code null}
     */
    public String option(String key) {
        Object value = options.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 以整数形式读取一个驱动私有参数。
     *
     * <p>先按 {@link #option(String)} 取字符串再解析；键不存在或值为 {@code null}
     * 时返回调用方提供的默认值，不做异常包装。</p>
     *
     * @param key          参数名，区分大小写
     * @param defaultValue 参数缺失时返回的默认值
     * @return 解析后的整数；参数缺失时返回 {@code defaultValue}
     * @throws NumberFormatException 当参数存在但不是合法十进制整数时抛出
     *                               （注意：不是 {@code TsdbConfigurationException}）
     */
    public int intOption(String key, int defaultValue) {
        String value = option(key);
        return value == null ? defaultValue : Integer.parseInt(value);
    }

    /**
     * 以长整数形式读取一个驱动私有参数。
     *
     * <p>与 {@link #intOption(String, int)} 相同，只是使用 {@link Long#parseLong(String)}，
     * 用于毫秒数、批次大小等可能超过 32 位的参数。键不存在或值为 {@code null}
     * 时返回调用方提供的默认值。</p>
     *
     * @param key          参数名，区分大小写
     * @param defaultValue 参数缺失时返回的默认值
     * @return 解析后的长整数；参数缺失时返回 {@code defaultValue}
     * @throws NumberFormatException 当参数存在但不是合法十进制长整数时抛出
     */
    public long longOption(String key, long defaultValue) {
        String value = option(key);
        return value == null ? defaultValue : Long.parseLong(value);
    }

    /**
     * 以布尔形式读取一个驱动私有参数，只接受 {@code true}/{@code false}。
     *
     * <p>刻意不使用 {@link Boolean#parseBoolean(String)}：它会把 {@code yes}、
     * {@code 1}、{@code on} 之类全部静默当成 {@code false}，等于让一次配置笔误
     * 关掉安全开关或快速失败路径。因此取值非法时当场抛异常，由调用方转换为
     * {@code TsdbConfigurationException} 在启动期失败。</p>
     *
     * <p>比较忽略大小写与首尾空白；键不存在、值为 {@code null} 或空白时返回
     * 调用方提供的默认值。</p>
     *
     * @param key          参数名，区分大小写
     * @param defaultValue 参数缺失时返回的默认值
     * @return 解析后的布尔值；参数缺失时返回 {@code defaultValue}
     * @throws IllegalArgumentException 当参数存在但不是 {@code true}/{@code false} 时
     */
    public boolean booleanOption(String key, boolean defaultValue) {
        String value = option(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        String text = value.trim();
        if ("true".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text)) {
            return false;
        }
        throw new IllegalArgumentException("boolean option " + key + " must be true or false: " + value);
    }
}
