package io.github.forget_the_bright.tsdb.hls;

import io.github.forget_the_bright.tsdb.common.config.SecurityConfig;
import io.github.forget_the_bright.tsdb.common.config.TimeConfig;
import io.github.forget_the_bright.tsdb.common.config.TsdbInstanceConfig;
import io.github.forget_the_bright.tsdb.common.exception.TsdbConfigurationException;
import io.github.forget_the_bright.tsdb.common.internal.Texts;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * HLS 驱动的已校验、不可变配置。
 *
 * <p>职责：把公共层的 {@link TsdbInstanceConfig}（配合根配置里的
 * {@link SecurityConfig}）翻译成 HLS 驱动内部直接可用的参数，并在构造阶段
 * 一次性完成全部校验，使运行期不再重复做配置判断。构造成功后所有字段均为
 * {@code final}，实例不可变，可安全地在多线程间共享。</p>
 *
 * <p>配置来源与默认值：</p>
 * <ul>
 *   <li>{@code base-url}：HLS 服务根地址，必须使用 {@code http} 或
 *       {@code https}，末尾多余的 {@code /} 会被去掉；</li>
 *   <li>{@code options.path-prefix}：反向代理部署时的额外路径前缀，缺省为空串，
 *       会自动补齐开头的 {@code /} 并去掉末尾的 {@code /}；</li>
 *   <li>{@code auth.user-id} 与 {@code auth.secret-key}：用于调用
 *       {@code GET /user/get_token} 换取动态 token；</li>
 *   <li>{@code auth.token}：静态 token，一旦配置就不再走换取流程；</li>
 *   <li>{@code options.token-expire-seconds}：动态 token 的本地缓存时长，
 *       缺省 {@code 1800} 秒；</li>
 *   <li>{@code timeout.connect-millis} 与 {@code timeout.read-millis}：
 *       连接与读取超时，必须为正数；</li>
 *   <li>{@code retry.max-attempts} 与 {@code retry.backoff-millis}：
 *       读请求的最大尝试次数（含首次）与重试间隔，次数必须 {@code >= 1}，
 *       间隔小于 0 时按 0 处理；</li>
 *   <li>{@code options.time.*}：时间解析与展示配置。HLS 协议时间是 epoch 数字，
 *       该配置不改变请求/响应数值，只供 {@code HlsClient} 的
 *       {@code parseTimestamp}/{@code formatTimestamp} 统一解析与展示。</li>
 * </ul>
 *
 * <p>安全约定：当 {@code base-url} 使用明文 {@code http} 时，必须显式打开
 * {@link SecurityConfig#isAllowInsecureTransport()}，否则构造失败；
 * {@link SecurityConfig#isVerifyHostname()} 为 {@code false} 时，
 * HTTPS 连接会跳过主机名校验，仅建议用于自签名证书的内网环境。</p>
 *
 * <p>敏感信息：本类持有 {@code secret-key} 与 token 明文且未重写
 * {@code toString()}，请勿把本对象直接输出到日志或监控系统。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class HlsConfig {

    /**
     * 实例逻辑名，仅用于日志、异常上下文与监控标识，不参与协议交互。
     */
    private final String instanceName;

    /**
     * 规范化后的 HLS 根地址：已去掉末尾斜杠，协议为 http 或 https。
     */
    private final String baseUrl;

    /**
     * 路径前缀：空串或以 {@code /} 开头且不以 {@code /} 结尾，用于反向代理场景。
     */
    private final String pathPrefix;

    /**
     * 动态 token 的用户名；使用静态 token 时为 {@code null}。
     */
    private final String userId;

    /**
     * 动态 token 的密钥；使用静态 token 时为 {@code null}。
     */
    private final String secretKey;

    /**
     * 静态 token；为 {@code null} 表示改用
     * {@code user-id}/{@code secret-key} 动态换取。
     */
    private final String staticToken;

    /**
     * 动态 token 的本地缓存时长，单位秒，恒为正。
     */
    private final int tokenExpireSeconds;

    /**
     * 建立 TCP 连接的超时时间，单位毫秒，恒为正。
     */
    private final long connectTimeoutMillis;

    /**
     * 等待响应的读取超时时间，单位毫秒，恒为正。
     */
    private final long readTimeoutMillis;

    /**
     * 是否校验 HTTPS 主机名；{@code false} 表示接受任意证书主机名。
     */
    private final boolean verifyHostname;

    /**
     * 单个读请求的最大尝试次数（含首次），恒 {@code >= 1}。
     */
    private final int maxReadAttempts;

    /**
     * 两次尝试之间的等待时间，单位毫秒，恒 {@code >= 0}（0 表示立即重试）。
     */
    private final long retryBackoffMillis;

    /**
     * 时间解析与展示配置，来源于 {@code options.time.*}。
     *
     * <p>HLS 的请求与响应时间字段本身都是 epoch 秒/毫秒数字，
     * 因此该配置不改变 HLS 协议；它用于让调用方以统一方式解析、
     * 展示时间，并与 GE 等文本时间驱动保持同一套配置语义。</p>
     */
    private final TimeConfig timeConfig;

    /**
     * 从公共层配置构造并校验 HLS 驱动配置。
     *
     * <p>构造过程只做解析与校验，不发起任何网络请求，因此可以在启动阶段
     * 反复调用；{@code HlsDriver#validate} 正是借助这一特性做配置预检。</p>
     *
     * <p>校验顺序：先归一化并校验 {@code base-url} 与传输安全性，再解析
     * {@code path-prefix} 与认证信息，最后校验超时、token 有效期与重试次数。
     * 任一步失败都会抛出 {@link TsdbConfigurationException}，并且不会产生
     * 半初始化的对象。</p>
     *
     * @param instanceName 实例逻辑名，用于日志与异常上下文；允许为
     *                     {@code null}，例如仅做配置校验时可传占位值
     * @param config       公共层的单实例配置，不能为 {@code null}
     * @param security     根配置中的安全选项；为 {@code null} 时按
     *                     {@link SecurityConfig} 的默认值处理（默认禁止明文传输、
     *                     校验主机名）
     * @throws TsdbConfigurationException 当 {@code config} 为 {@code null}、
     *         {@code base-url} 缺失或不是合法的 http/https 地址、明文传输未被允许、
     *         既未提供静态 token 又缺少 {@code auth.user-id} 或
     *         {@code auth.secret-key}、连接/读取超时不为正、
     *         {@code token-expire-seconds} 不为正、
     *         {@code retry.max-attempts} 小于 1 时
     */
    public HlsConfig(String instanceName, TsdbInstanceConfig config, SecurityConfig security) {
        if (config == null) {
            throw new TsdbConfigurationException("HLS instance config is required");
        }
        SecurityConfig sec = security == null ? new SecurityConfig() : security;

        String url = Texts.trimToNull(config.getBaseUrl());
        if (url == null) {
            throw new TsdbConfigurationException("HLS base-url is required");
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new TsdbConfigurationException("invalid HLS base-url: " + url, e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new TsdbConfigurationException("HLS base-url must use http or https: " + url);
        }
        if ("http".equalsIgnoreCase(scheme) && !sec.isAllowInsecureTransport()) {
            throw new TsdbConfigurationException(
                    "plaintext HLS transport is disabled; use https or set allow-insecure-transport=true");
        }

        String prefix = Texts.trimToNull(config.option("path-prefix"));
        if (prefix == null) {
            prefix = "";
        }
        if (!prefix.isEmpty() && !prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }

        String user = Texts.trimToNull(config.getAuth() == null ? null : config.getAuth().getUserId());
        String secret = Texts.trimToNull(config.getAuth() == null ? null : config.getAuth().getSecretKey());
        String token = Texts.trimToNull(config.getAuth() == null ? null : config.getAuth().getToken());
        if (token == null && (user == null || secret == null)) {
            throw new TsdbConfigurationException("HLS auth.user-id and auth.secret-key are required");
        }

        long connect = config.getTimeout().getConnectMillis();
        long read = config.getTimeout().getReadMillis();
        if (connect <= 0 || read <= 0) {
            throw new TsdbConfigurationException("HLS connect/read timeouts must be > 0");
        }

        int expire = config.intOption("token-expire-seconds", 1800);
        if (expire <= 0) {
            throw new TsdbConfigurationException("HLS token-expire-seconds must be > 0");
        }
        int attempts = config.getRetry().getMaxAttempts();
        if (attempts < 1) {
            throw new TsdbConfigurationException("HLS retry.max-attempts must be >= 1");
        }

        this.instanceName = instanceName;
        this.baseUrl = url;
        this.pathPrefix = prefix;
        this.userId = user;
        this.secretKey = secret;
        this.staticToken = token;
        this.tokenExpireSeconds = expire;
        this.connectTimeoutMillis = connect;
        this.readTimeoutMillis = read;
        this.verifyHostname = sec.isVerifyHostname();
        this.maxReadAttempts = attempts;
        this.retryBackoffMillis = Math.max(0L, config.getRetry().getBackoffMillis());
        this.timeConfig = TimeConfig.from(config);
    }

    /**
     * 返回实例逻辑名。
     *
     * @return 构造时传入的实例名，可能为 {@code null}
     */
    public String getInstanceName() {
        return instanceName;
    }

    /**
     * 返回规范化后的 HLS 根地址。
     *
     * @return 不含末尾斜杠的 http/https 地址，非 {@code null}
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * 返回反向代理路径前缀。
     *
     * @return 空串，或以 {@code /} 开头且不以 {@code /} 结尾的前缀，非 {@code null}
     */
    public String getPathPrefix() {
        return pathPrefix;
    }

    /**
     * 返回动态 token 的用户名。
     *
     * @return 用户名；使用静态 token 或未配置时为 {@code null}
     */
    public String getUserId() {
        return userId;
    }

    /**
     * 返回动态 token 的密钥。
     *
     * @return 密钥；使用静态 token 或未配置时为 {@code null}
     */
    public String getSecretKey() {
        return secretKey;
    }

    /**
     * 返回静态 token。
     *
     * @return 静态 token；未配置时为 {@code null}，表示由
     *         {@code HlsTokenManager} 动态换取
     */
    public String getStaticToken() {
        return staticToken;
    }

    /**
     * 返回动态 token 的本地缓存时长。
     *
     * <p>该值只决定驱动多久主动刷新一次 token，并不等于服务端签发的
     * 真实有效期；驱动会在到期前提前刷新（见 {@code HlsTokenManager}）。</p>
     *
     * @return 缓存时长，单位秒，恒为正
     */
    public int getTokenExpireSeconds() {
        return tokenExpireSeconds;
    }

    /**
     * 返回建立连接的超时时间。
     *
     * @return 超时时间，单位毫秒，恒为正
     */
    public long getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    /**
     * 返回等待响应的读取超时时间。
     *
     * @return 超时时间，单位毫秒，恒为正
     */
    public long getReadTimeoutMillis() {
        return readTimeoutMillis;
    }

    /**
     * 返回是否校验 HTTPS 主机名。
     *
     * @return 需要校验返回 {@code true}（默认）；关闭校验时返回 {@code false}
     */
    public boolean isVerifyHostname() {
        return verifyHostname;
    }

    /**
     * 返回读请求的最大尝试次数（含首次）。
     *
     * @return 尝试次数，恒 {@code >= 1}
     */
    public int getMaxReadAttempts() {
        return maxReadAttempts;
    }

    /**
     * 返回两次尝试之间的等待时间。
     *
     * @return 等待时间，单位毫秒，恒 {@code >= 0}；0 表示立即重试
     */
    public long getRetryBackoffMillis() {
        return retryBackoffMillis;
    }

    /**
     * 返回时间解析与展示配置。
     *
     * <p>HLS 协议时间仍是 epoch 数字；该配置用于调用方统一解析、
     * 格式化时间，不参与 HLS 请求参数或响应数值的转换。</p>
     *
     * @return 非 {@code null} 的不可变时间配置
     */
    public TimeConfig getTimeConfig() {
        return timeConfig;
    }

    /**
     * 拼接完整请求地址。
     *
     * <p>按 {@code baseUrl + pathPrefix + path} 拼接，并保证 {@code path}
     * 以 {@code /} 开头。因为 {@code baseUrl} 已去掉末尾斜杠、
     * {@code pathPrefix} 已归一化为“空串或以 / 开头且不以 / 结尾”，
     * 所以结果中不会出现双斜杠。</p>
     *
     * @param path 相对路径，例如 {@code "/ddb/read_alltagname"}；
     *             不以 {@code /} 开头时会自动补上，不能为 {@code null}
     * @return 非 {@code null} 的绝对 URL 字符串
     */
    public String url(String path) {
        String normalized = path.startsWith("/") ? path : "/" + path;
        return baseUrl + pathPrefix + normalized;
    }
}

