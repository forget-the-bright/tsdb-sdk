package io.github.forget_the_bright.tsdb.ge;

import io.github.forget_the_bright.tsdb.common.config.SecurityConfig;
import io.github.forget_the_bright.tsdb.common.config.TimeConfig;
import io.github.forget_the_bright.tsdb.common.config.TsdbInstanceConfig;
import io.github.forget_the_bright.tsdb.common.exception.TsdbConfigurationException;
import io.github.forget_the_bright.tsdb.common.internal.Texts;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * 已校验、不可变的 GE Historian 连接配置。
 *
 * <p>职责：把公共层的 {@link TsdbInstanceConfig} 与 {@link SecurityConfig}
 * 归一化成驱动内部使用的强类型参数，并在构造阶段一次性完成全部静态校验，
 * 使运行期不再需要判断“配置是否合法”。</p>
 *
 * <p><strong>两个根路径：</strong>GE 的 OAuth 令牌端点挂在主机根路径下
 * （{@code /uaa/oauth/token}），Historian 业务接口挂在 {@code /historian-rest-api}
 * 前缀下，二者不能共用同一个拼接方法。因此业务请求用
 * {@link #apiUrl(String)}，取令牌用 {@link #rootUrl(String)}。前缀可通过
 * {@code options.path-prefix} 覆盖，用于反向代理把业务 API 挂到自定义路径的
 * 部署；覆盖值会被规范化为“以 {@code /} 开头、不以 {@code /} 结尾”。</p>
 *
 * <p>构造期校验（违反任意一条即抛 {@link TsdbConfigurationException}）：</p>
 * <ul>
 *   <li>{@code base-url} 必填，必须能被 {@link URI} 解析，scheme 只能是
 *       {@code http} 或 {@code https}；</li>
 *   <li>使用 {@code http} 时必须显式打开 {@code allow-insecure-transport}，
 *       否则拒绝明文传输（默认只允许 https）；</li>
 *   <li>鉴权二选一：静态 token，或同时提供 username/password/client-id/
 *       client-secret 四项，缺任意一项都视为配置错误；</li>
 *   <li>连接超时与读取超时都必须为正数；</li>
 *   <li>{@code token-expire-seconds} 必须为正数，未配置时默认 {@code 1800} 秒，
 *       仅作为响应缺少 {@code expires_in} 时的兜底；</li>
 *   <li>{@code retry.max-attempts} 必须 {@code >= 1}，该值决定 401 之后
 *       刷新令牌并重试的总次数上限。</li>
 * </ul>
 *
 * <p>不可变性与线程安全：所有字段均为 {@code final}，构造后不再修改；URL 拼接
 * 方法无状态，因此本类可安全地在多线程间共享。注意 {@link #getPassword()} 与
 * {@link #getClientSecret()} 返回明文凭据，不要把本对象整体输出到日志。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class GeConfig {

    /**
     * Historian 业务 API 的默认前缀：{@code /historian-rest-api}。
     *
     * <p>仅在配置未提供 {@code options.path-prefix} 时使用。OAuth 令牌端点
     * （{@code /uaa/oauth/token}）不在该前缀下，见 {@link #rootUrl(String)}。</p>
     */
    public static final String DEFAULT_API_PREFIX = "/historian-rest-api";

    private final String instanceName;
    private final String baseUrl;
    private final String apiPrefix;
    private final String username;
    private final String password;
    private final String clientId;
    private final String clientSecret;
    private final String staticToken;
    private final int tokenExpireSeconds;
    private final long connectTimeoutMillis;
    private final long readTimeoutMillis;
    private final boolean verifyHostname;
    private final boolean trustAllCertificates;
    private final int maxReadAttempts;
    private final long retryBackoffMillis;
    private final TimeConfig timeConfig;

    /**
     * 从公共层配置构造并校验 GE 连接参数。
     *
     * <p>构造过程只做字符串归一化与静态校验，<strong>不发起任何网络请求</strong>，
     * 因此地址不可达、凭据错误都不会在这里暴露，而是延迟到首次调用数据接口时。
     * 传入的 {@code security} 为 {@code null} 时按“严格默认值”处理：
     * 禁止明文传输、校验主机名。</p>
     *
     * <p>字符串处理细节：{@code base-url}、{@code path-prefix} 以及四项 OAuth
     * 凭据都会先 trim，再把空串视为 {@code null}（例如把 {@code " "} 当作未配置），
     * 避免配置文件里的空白被当成有效值。</p>
     *
     * @param instanceName 实例逻辑名，用于日志与异常上下文，允许为 {@code null}
     * @param config       公共层实例配置，不能为 {@code null}；提供 base-url、
     *                     auth、timeout、retry 与 options 等原始值
     * @param security     根配置中的安全选项，可为 {@code null}；为 {@code null}
     *                     时使用默认策略（禁止明文、校验主机名）
     * @throws TsdbConfigurationException 当 base-url 缺失/非法、scheme 不是
     *         http(s)、明文传输未放行、鉴权信息不完整、超时非正、
     *         {@code token-expire-seconds} 非正、{@code retry.max-attempts < 1}、
     *         {@code trust-all-certificates} 不是 {@code true}/{@code false}
     *         时抛出
     */
    public GeConfig(String instanceName, TsdbInstanceConfig config, SecurityConfig security) {
        if (config == null) {
            throw new TsdbConfigurationException("GE instance config is required");
        }
        SecurityConfig sec = security == null ? new SecurityConfig() : security;

        String url = Texts.trimToNull(config.getBaseUrl());
        if (url == null) {
            throw new TsdbConfigurationException("GE base-url is required");
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new TsdbConfigurationException("invalid GE base-url: " + url, e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new TsdbConfigurationException("GE base-url must use http or https: " + url);
        }
        if ("http".equalsIgnoreCase(scheme) && !sec.isAllowInsecureTransport()) {
            throw new TsdbConfigurationException(
                    "plaintext GE transport is disabled; use https or set allow-insecure-transport=true");
        }

        String prefix = Texts.trimToNull(config.option("path-prefix"));
        if (prefix == null) {
            prefix = DEFAULT_API_PREFIX;
        }
        if (!prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }

        String token = Texts.trimToNull(config.getAuth() == null ? null : config.getAuth().getToken());
        String user = Texts.trimToNull(config.getAuth() == null ? null : config.getAuth().getUsername());
        String pass = Texts.trimToNull(config.getAuth() == null ? null : config.getAuth().getPassword());
        String client = Texts.trimToNull(config.getAuth() == null ? null : config.getAuth().getClientId());
        String secret = Texts.trimToNull(config.getAuth() == null ? null : config.getAuth().getClientSecret());
        if (token == null && (user == null || pass == null || client == null || secret == null)) {
            throw new TsdbConfigurationException(
                    "GE auth requires username/password/client-id/client-secret or a static token");
        }

        long connect = config.getTimeout().getConnectMillis();
        long read = config.getTimeout().getReadMillis();
        if (connect <= 0 || read <= 0) {
            throw new TsdbConfigurationException("GE connect/read timeouts must be > 0");
        }

        int expire = config.intOption("token-expire-seconds", 1800);
        if (expire <= 0) {
            throw new TsdbConfigurationException("GE token-expire-seconds must be > 0");
        }
        int attempts = config.getRetry().getMaxAttempts();
        if (attempts < 1) {
            throw new TsdbConfigurationException("GE retry.max-attempts must be >= 1");
        }

        this.instanceName = instanceName;
        this.baseUrl = url;
        this.apiPrefix = prefix;
        this.username = user;
        this.password = pass;
        this.clientId = client;
        this.clientSecret = secret;
        this.staticToken = token;
        this.tokenExpireSeconds = expire;
        this.connectTimeoutMillis = connect;
        this.readTimeoutMillis = read;
        this.verifyHostname = sec.isVerifyHostname();
        try {
            this.trustAllCertificates = config.booleanOption("trust-all-certificates", false);
        } catch (IllegalArgumentException e) {
            throw new TsdbConfigurationException(e.getMessage(), e);
        }
        this.maxReadAttempts = attempts;
        this.retryBackoffMillis = Math.max(0L, config.getRetry().getBackoffMillis());
        this.timeConfig = TimeConfig.from(config);
    }

    /**
     * 返回实例的逻辑名。
     *
     * <p>该名字只用于日志、指标与异常上下文标识，不参与任何 GE 协议交互，
     * 也与 Historian 服务端登记的机器名无关。</p>
     *
     * @return 构造时传入的实例名，可能为 {@code null}
     */
    public String getInstanceName() {
        return instanceName;
    }

    /**
     * 返回归一化后的基地址。
     *
     * <p>已去掉首尾空白与结尾的全部斜杠，因此可安全地做
     * {@code getBaseUrl() + "/path"} 之类的拼接；返回值不含
     * {@link #getApiPrefix()}。</p>
     *
     * @return 形如 {@code https://host:9443} 的基地址，非 {@code null}
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * 返回 Historian 业务 API 的前缀。
     *
     * <p>始终以 {@code /} 开头且不以 {@code /} 结尾；未配置时为
     * {@link #DEFAULT_API_PREFIX}。</p>
     *
     * @return 规范化后的前缀，非 {@code null}
     */
    public String getApiPrefix() {
        return apiPrefix;
    }

    /**
     * 返回 OAuth 密码模式使用的用户名。
     *
     * <p>仅在未配置静态 token 时使用；配置了静态 token 时本字段可能为
     * {@code null}。</p>
     *
     * @return 用户名，可能为 {@code null}
     */
    public String getUsername() {
        return username;
    }

    /**
     * 返回 OAuth 密码模式使用的口令（明文）。
     *
     * <p>仅供 {@code GeTokenManager} 拼装令牌请求；不要写入日志或异常消息。</p>
     *
     * @return 明文口令，可能为 {@code null}
     */
    public String getPassword() {
        return password;
    }

    /**
     * 返回 OAuth 客户端标识。
     *
     * <p>作为 HTTP Basic 的用户名部分参与令牌请求，与业务登录用户名不是同一个
     * 概念。</p>
     *
     * @return 客户端标识，可能为 {@code null}
     */
    public String getClientId() {
        return clientId;
    }

    /**
     * 返回 OAuth 客户端密钥（明文）。
     *
     * <p>作为 HTTP Basic 的口令部分参与令牌请求；不要写入日志。</p>
     *
     * @return 客户端密钥，可能为 {@code null}
     */
    public String getClientSecret() {
        return clientSecret;
    }

    /**
     * 返回预置的静态访问令牌。
     *
     * <p>非 {@code null} 时驱动直接使用该令牌，完全跳过 OAuth 换取流程，
     * 因此也不会尝试刷新；令牌过期后只能由外部重新配置并重建客户端。</p>
     *
     * @return 静态令牌，未配置时为 {@code null}
     */
    public String getStaticToken() {
        return staticToken;
    }

    /**
     * 返回令牌有效期的兜底秒数。
     *
     * <p>仅当 OAuth 响应缺少 {@code expires_in} 或该值非正时使用，默认
     * {@code 1800} 秒。实际缓存会在该值基础上提前 {@code 5} 秒过期。</p>
     *
     * @return 恒为正的秒数，默认 1800
     */
    public int getTokenExpireSeconds() {
        return tokenExpireSeconds;
    }

    /**
     * 返回建立 TCP 连接的超时时间。
     *
     * @return 恒为正的毫秒数
     */
    public long getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    /**
     * 返回等待响应的读取超时时间。
     *
     * <p>GE 的聚合/插值查询在大区间上可能耗时较长，该值需要按现场数据量评估；
     * 超时会被翻译为公共层的 {@code TsdbTimeoutException}。</p>
     *
     * @return 恒为正的毫秒数
     */
    public long getReadTimeoutMillis() {
        return readTimeoutMillis;
    }

    /**
     * 是否校验 HTTPS 服务器主机名。
     *
     * <p>来自根配置的安全选项，默认 {@code true}；返回 {@code false} 表示调用方
     * 显式接受了中间人风险（常见于自签名证书的内网部署）。</p>
     *
     * @return 需要校验主机名返回 {@code true}，否则返回 {@code false}
     */
    public boolean isVerifyHostname() {
        return verifyHostname;
    }

    /**
     * 是否跳过 HTTPS 证书链校验（信任所有服务端证书）。
     *
     * <p>对应驱动私有配置 {@code options.trust-all-certificates}，默认
     * {@code false}。仅用于内网自签名证书且无法导入 CA 的现场；开启后
     * {@link #isVerifyHostname()} 通常也应设为 {@code false}，因为现场证书
     * 的 CN 往往是机器名而不是访问用的 IP。</p>
     *
     * <p><strong>安全警告：</strong>开启后无法防御中间人攻击，凭据可能被截获。
     * 生产环境优先把现场根证书导入 JVM 信任库，或改成受信的 HTTPS 反向代理；
     * 只在一次性联调/验收时临时打开，并在完成后立即关闭。</p>
     *
     * @return 跳过证书链校验返回 {@code true}，默认 {@code false}
     */
    public boolean isTrustAllCertificates() {
        return trustAllCertificates;
    }

    /**
     * 返回单次请求允许的最大尝试次数。
     *
     * <p>该值同时用于两处：读接口遇到 401 时刷新令牌后的重试，以及
     * {@code GeNativeApi} 的通用请求重试；配置了静态 token 时不会重试。</p>
     *
     * @return 恒 {@code >= 1} 的尝试次数
     */
    public int getMaxReadAttempts() {
        return maxReadAttempts;
    }

    /**
     * 返回重试退避的毫秒数。
     *
     * <p>构造成员保留该配置值（负值归零），但当前实现的重试循环不插入 sleep，
     * 因此它目前只作为配置快照存在，不改变调用节奏。</p>
     *
     * @return 非负的毫秒数，默认取配置值
     */
    public long getRetryBackoffMillis() {
        return retryBackoffMillis;
    }

    /**
     * 返回本实例的时间格式转换配置。
     *
     * <p>GE 的 REST 接口本身只接受 UTC 字符串，但不同现场返回的
     * {@code TimeStamp} 可能是 UTC，也可能是服务器本地时间。本配置决定
     * “请求按哪个时区/模式输出、响应的无时区文本按哪个时区解释、展示时按哪个
     * 时区/模式格式化”。详见 {@link TimeConfig}。</p>
     *
     * @return 非 {@code null} 的时间配置
     */
    public TimeConfig getTimeConfig() {
        return timeConfig;
    }

    /**
     * 预设时间配置：真 UTC（驱动默认语义，已生产实证对齐旧 {@code GE_REST_SDK}）。
     *
     * <p>请求把 {@link java.time.Instant} 换算到 UTC 后输出
     * {@code yyyy-MM-dd'T'HH:mm:ssXXX}（UTC 下结尾为真实的 {@code Z}）；
     * 响应的无时区文本按 UTC 解析。等价于 {@link TimeConfig#defaults()}，
     * 也等价于不配置任何 {@code options.time.*}。</p>
     *
     * <p><strong>生产裁决结论（只读逐点对比实证）：</strong>旧 {@code GE_REST_SDK}
     * 的请求时间并非“本地墙钟 + 字面 Z”的伪 UTC——它用 hutool
     * {@code DateUtil.format(date, DatePattern.UTC_FORMAT)} 格式化，而
     * {@code DatePattern.UTC_FORMAT} 是一个<strong>时区绑定为 UTC</strong> 的
     * {@code FastDateFormat} 实例（{@code FastDateFormat[yyyy-MM-dd'T'HH:mm:ss'Z',zh_CN,UTC]}），
     * 无论 JVM 默认时区为何都输出真 UTC。生产对比中“旧 SDK vs 新真 UTC 客户端”
     * 对同一位号同一窗口 50 个原始点的时间戳/值/质量码<strong>逐点精确一致</strong>；
     * 而“伪 UTC”（请求 GMT+8 墙钟 + 字面 Z）会把查询窗口整体平移 +8 小时，
     * 导致聚合/插值/原始/区间增量全部错位。因此 GE 驱动<strong>默认且推荐真 UTC</strong>，
     * 不提供伪 UTC 预设。</p>
     *
     * <p>若现场需要“UTC 请求、UTC 响应、GMT+8 展示”，不要改请求/解析时区，
     * 只配置 {@code options.time.response.target-zone=GMT+8}（仅影响展示字符串，
     * 不影响 {@link java.time.Instant} 绝对时刻）。</p>
     *
     * @return 不可变、线程安全的真 UTC 时间配置，非 {@code null}
     */
    public static TimeConfig timeConfigTrueUtc() {
        return TimeConfig.defaults();
    }

    /**
     * 在 Historian API 前缀下拼接完整 URL。
     *
     * <p>例如 {@code apiUrl("/v1/tags")} 返回
     * {@code https://host/historian-rest-api/v1/tags}。业务端点都应使用本方法，
     * 只有 OAuth 令牌端点在根路径下，使用 {@link #rootUrl(String)}。</p>
     *
     * @param path 相对于 API 前缀的路径，允许不以斜杠开头
     * @return 可直接交给 HTTP 客户端的绝对 URL
     */
    public String apiUrl(String path) {
        return baseUrl + apiPrefix + normalize(path);
    }

    /**
     * 在主机根路径下拼接完整 URL，供 OAuth 令牌端点使用。
     *
     * <p>GE 的令牌端点是 {@code /uaa/oauth/token}，不在
     * {@code /historian-rest-api} 前缀下，因此不能使用
     * {@link #apiUrl(String)}。</p>
     *
     * @param path 以根路径为基准的相对路径
     * @return 绝对 URL
     */
    public String rootUrl(String path) {
        return baseUrl + normalize(path);
    }

    /**
     * 把相对路径规范化为以 {@code /} 开头。
     *
     * <p>只补前导斜杠，不做 URL 编码、不处理 {@code ..}、也不去掉结尾斜杠，
     * 这些语义由调用方自行保证。</p>
     *
     * @param path 相对路径，不能为 {@code null}
     * @return 以 {@code /} 开头的路径
     */
    private static String normalize(String path) {
        return path.startsWith("/") ? path : "/" + path;
    }
}
