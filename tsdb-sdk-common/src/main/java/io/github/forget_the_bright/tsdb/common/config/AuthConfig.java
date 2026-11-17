package io.github.forget_the_bright.tsdb.common.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 实例认证配置（可变 POJO），保存访问某一时序库实例所需的全部凭据字段。
 *
 * <p>本类只负责承载数据，不决定“哪些字段必填”：必填项由实例的 {@code type}
 * （即具体驱动）配合 {@link #getMode()} 共同决定。常见组合为：</p>
 * <ul>
 *   <li>用户名/密码：{@code username} + {@code password}；</li>
 *   <li>OAuth2 客户端模式：{@code clientId} + {@code clientSecret}；</li>
 *   <li>签名模式：{@code userId} + {@code secretKey}（如 HLS 的 token 接口）；</li>
 *   <li>静态令牌：直接使用 {@code token}，跳过取令牌流程。</li>
 * </ul>
 * <p>驱动会在 {@code TsdbDriver#validate} / 客户端构造阶段检查所需的组合，缺失时抛出
 * {@link io.github.forget_the_bright.tsdb.common.exception.TsdbConfigurationException}。</p>
 *
 * <p>所有字符串字段默认 {@code null}，表示“未配置”；空字符串与 {@code null} 的语义差异由驱动
 * 自行做 trim 归一化。{@link #getOptions()} 永不为 {@code null}
 * （{@link #setOptions(Map)} 传入 {@code null} 时会替换为空的 {@link LinkedHashMap}，
 * 保持插入顺序，便于排查配置）。</p>
 *
 * <p>线程安全：本类不是线程安全的。约定在启动阶段由 {@link TsdbConfigLoader} 或 Spring Boot
 * 的 {@code @ConfigurationProperties} 绑定完成后只读使用；运行期不应再调用 setter，
 * 以免不同线程看到部分更新的凭据。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public class AuthConfig {

    /** 认证模式标识，取值由驱动定义（如 basic、oauth2、token），未配置时为 {@code null}。 */
    private String mode;
    /** 用户名，用户名/密码模式必填；未配置时为 {@code null}。 */
    private String username;
    /** 密码，用户名/密码模式必填；明文保存，不会自动脱敏，日志中请勿打印。 */
    private String password;
    /** OAuth2 客户端 ID，客户端凭证模式必填。 */
    private String clientId;
    /** OAuth2 客户端密钥，客户端凭证模式必填；同样以明文保存。 */
    private String clientSecret;
    /** 签名认证使用的用户标识（如 HLS 的 user-id）。 */
    private String userId;
    /** 签名认证使用的密钥（如 HLS 的 secret-key），明文保存。 */
    private String secretKey;
    /** 预置的静态访问令牌；配置后驱动通常不再走取令牌接口，令牌过期需由使用方自行更新。 */
    private String token;
    /** 驱动私有认证参数，键值均为字符串，保持插入顺序；默认空 Map，永不为 {@code null}。 */
    private Map<String, String> options = new LinkedHashMap<String, String>();

    /**
     * 返回认证模式标识。
     *
     * <p>该字符串仅由驱动解释，公共层不做枚举校验；未配置时返回 {@code null}，
     * 此时驱动一般按“字段是否齐全”推断模式。</p>
     *
     * @return 认证模式标识；可能为 {@code null}
     */
    public String getMode() {
        return mode;
    }

    /**
     * 设置认证模式标识。
     *
     * @param mode 认证模式名称，可为 {@code null} 表示由驱动推断；不会做 trim 或大小写转换
     */
    public void setMode(String mode) {
        this.mode = mode;
    }

    /**
     * 返回用户名。
     *
     * @return 用户名；未配置时为 {@code null}
     */
    public String getUsername() {
        return username;
    }

    /**
     * 设置用户名。
     *
     * @param username 用户名，可为 {@code null}
     */
    public void setUsername(String username) {
        this.username = username;
    }

    /**
     * 返回密码明文。
     *
     * @return 密码；未配置时为 {@code null}
     */
    public String getPassword() {
        return password;
    }

    /**
     * 设置密码明文。
     *
     * @param password 密码，可为 {@code null}
     */
    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * 返回 OAuth2 客户端 ID。
     *
     * @return 客户端 ID；未配置时为 {@code null}
     */
    public String getClientId() {
        return clientId;
    }

    /**
     * 设置 OAuth2 客户端 ID。
     *
     * @param clientId 客户端 ID，可为 {@code null}
     */
    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    /**
     * 返回 OAuth2 客户端密钥明文。
     *
     * @return 客户端密钥；未配置时为 {@code null}
     */
    public String getClientSecret() {
        return clientSecret;
    }

    /**
     * 设置 OAuth2 客户端密钥明文。
     *
     * @param clientSecret 客户端密钥，可为 {@code null}
     */
    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    /**
     * 返回签名认证的用户标识。
     *
     * @return 用户标识；未配置时为 {@code null}
     */
    public String getUserId() {
        return userId;
    }

    /**
     * 设置签名认证的用户标识。
     *
     * @param userId 用户标识，可为 {@code null}
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * 返回签名认证的密钥明文。
     *
     * @return 密钥；未配置时为 {@code null}
     */
    public String getSecretKey() {
        return secretKey;
    }

    /**
     * 设置签名认证的密钥明文。
     *
     * @param secretKey 密钥，可为 {@code null}
     */
    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    /**
     * 返回预置的静态访问令牌。
     *
     * @return 令牌；未配置时为 {@code null}
     */
    public String getToken() {
        return token;
    }

    /**
     * 设置预置的静态访问令牌。
     *
     * @param token 令牌，可为 {@code null} 表示交由驱动动态获取
     */
    public void setToken(String token) {
        this.token = token;
    }

    /**
     * 返回驱动的私有认证参数表。
     *
     * <p>返回的是内部可变 Map 本身（不做防御性拷贝），调用方可以修改其内容；
     * 该 Map 永不为 {@code null}。</p>
     *
     * @return 可变的认证参数表，按插入顺序迭代，非 {@code null}
     */
    public Map<String, String> getOptions() {
        return options;
    }

    /**
     * 设置驱动的私有认证参数表。
     *
     * <p>直接持有传入的 Map 引用，不做拷贝；传入 {@code null} 时替换为空的
     * {@link LinkedHashMap}，保证 {@link #getOptions()} 永不返回 {@code null}。</p>
     *
     * @param options 认证参数表，可为 {@code null}
     */
    public void setOptions(Map<String, String> options) {
        this.options = options == null ? new LinkedHashMap<String, String>() : options;
    }
}
