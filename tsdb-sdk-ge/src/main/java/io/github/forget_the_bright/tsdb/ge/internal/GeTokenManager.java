package io.github.forget_the_bright.tsdb.ge.internal;

import io.github.forget_the_bright.tsdb.common.exception.TsdbAuthenticationException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbDataException;
import io.github.forget_the_bright.tsdb.common.internal.json.JsonText;
import io.github.forget_the_bright.tsdb.ge.GeConfig;

import java.net.URLEncoder;

/**
 * GE Historian 的 OAuth 密码模式（password grant）令牌缓存。
 *
 * <p>职责：向 Historian 所在主机的 UAA 端点换取访问令牌、在内存中缓存并在临近
 * 过期时自动刷新，供 {@link GeNativeApi} 为每个业务请求附加
 * {@code Authorization: Bearer ...}。</p>
 *
 * <p>厂商语义（与 GE 官方参考 SDK 保持一致的线上格式）：</p>
 * <ul>
 *   <li>令牌端点固定在<strong>主机根路径</strong>
 *       {@code /uaa/oauth/token}，不在 {@code /historian-rest-api} 前缀下，
 *       因此用 {@link GeConfig#rootUrl(String)} 而非 {@code apiUrl} 拼接；</li>
 *   <li>客户端凭据（{@code client-id}/{@code client-secret}）走
 *       HTTP Basic 头，用户凭据（{@code username}/{@code password}）与
 *       {@code grant_type=password} 放在查询串里；</li>
 *   <li>请求体为空字符串，但 {@code Content-Type} 声明为
 *       {@code application/x-www-form-urlencoded}；</li>
 *   <li>响应中的 {@code access_token} 为令牌，{@code expires_in} 为有效期秒数；
 *       {@code expires_in} 缺失或非正时回退到
 *       {@link GeConfig#getTokenExpireSeconds()}。</li>
 * </ul>
 *
 * <p>缓存策略：实际过期时间 = 当前时间 + {@code expires_in * 1000} -
 * {@value #EXPIRY_SAFETY_MILLIS} 毫秒，即提前 5 秒失效，避免请求在途中恰好过期。
 * 若配置了静态 token（{@link GeConfig#getStaticToken()}），则完全跳过本类的换取
 * 与刷新逻辑，直接返回该 token。</p>
 *
 * <p>线程安全性：{@code token}/{@code expiresAtMillis} 为 {@code volatile}，
 * {@link #getToken()} 的命中路径无锁；未命中时进入 {@link #refresh()} 的
 * {@code synchronized} 块并做二次检查，保证并发的首次调用只会真正换取一次令牌。
 * {@link #invalidate(String)} 同样是同步方法，并通过 {@code expected} 做
 * “仅失效我用的那个令牌”的比较，避免把其他线程刚刷新的新令牌误清。</p>
 *
 * <p>限制：只支持密码模式，不支持 {@code refresh_token}；凭据出现在查询串中，
 * 可能被代理或访问日志记录，生产环境应优先使用静态 token 并全程 HTTPS。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class GeTokenManager {

    /** 令牌过期前的安全余量（毫秒），提前 5 秒视为过期。 */
    private static final long EXPIRY_SAFETY_MILLIS = 5000L;

    /** 连接配置，提供令牌端点根路径与凭据。 */
    private final GeConfig config;

    /** 传输层，用于向令牌端点发 POST。 */
    private final GeHttpClient http;

    /** 当前缓存的访问令牌；{@code null} 表示尚未获取或已被失效。 */
    private volatile String token;

    /** 当前令牌的本地过期时刻（毫秒时间戳，含安全余量）；{@code 0} 表示无令牌。 */
    private volatile long expiresAtMillis;

    /**
     * 构造令牌管理器。
     *
     * <p>构造期不发起任何网络请求，首次调用 {@link #getToken()} 时才换取令牌。</p>
     *
     * @param config 连接配置，不能为 {@code null}
     * @param http   传输层，不能为 {@code null}
     */
    public GeTokenManager(GeConfig config, GeHttpClient http) {
        this.config = config;
        this.http = http;
    }

    /**
     * 获取可用的访问令牌。
     *
     * <p>优先级：静态 token（配置提供时直接返回，永不刷新）→ 未过期的缓存 →
     * 同步刷新。判断过期使用本地时钟，因此宿主机的时钟漂移会影响缓存命中。</p>
     *
     * @return 非空的访问令牌
     * @throws TsdbAuthenticationException 换取失败、响应缺少 {@code access_token}
     *                                     或响应无法解析
     */
    public String getToken() {
        if (config.getStaticToken() != null) {
            return config.getStaticToken();
        }
        String current = token;
        if (current != null && System.currentTimeMillis() < expiresAtMillis) {
            return current;
        }
        return refresh();
    }

    /**
     * 作废缓存中的令牌，通常在某次业务请求收到 401 后调用。
     *
     * <p>使用 {@code expected} 做比较失效：仅当它等于当前缓存令牌（或为
     * {@code null}，表示无条件清空）时才清除，防止并发场景下把其他线程刚换到的
     * 新令牌误删，造成不必要的重复换取。清除后下一次 {@link #getToken()} 会重新
     * 走 OAuth 流程。</p>
     *
     * @param expected 期望失效的令牌值；{@code null} 表示无条件清空
     */
    public synchronized void invalidate(String expected) {
        if (expected == null || expected.equals(token)) {
            token = null;
            expiresAtMillis = 0L;
        }
    }

    /**
     * 同步换取新令牌，并在进入临界区后二次检查缓存。
     *
     * <p>二次检查是必要的：多个线程可能同时发现缓存过期并排队等待锁，先进入的
     * 线程完成刷新后，后进入者应直接复用结果而不是重复请求。请求形如
     * {@code POST {rootUrl}/uaa/oauth/token?grant_type=password&username=..&password=..}，
     * 参数经 UTF-8 URL 编码；{@code Authorization} 头为客户端凭据的 Basic 编码。</p>
     *
     * @return 新获取的访问令牌，非 {@code null}
     * @throws TsdbAuthenticationException 端点返回非 2xx（由传输层翻译）、
     *                                     缺少 {@code access_token}，或响应体不是合法 JSON
     */
    private synchronized String refresh() {
        String current = token;
        if (current != null && System.currentTimeMillis() < expiresAtMillis) {
            return current;
        }
        String url = config.rootUrl("/uaa/oauth/token")
                + "?grant_type=password"
                + "&username=" + encode(config.getUsername())
                + "&password=" + encode(config.getPassword());
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<String, String>();
        headers.put("Authorization", GeHttpClient.basic(config.getClientId(), config.getClientSecret()));
        String body = http.post(url, headers, "", "application/x-www-form-urlencoded");
        try {
            Object root = JsonText.parse(body);
            String accessToken = JsonText.text(root, "access_token");
            if (accessToken == null || accessToken.isEmpty()) {
                throw new TsdbAuthenticationException("GE OAuth response did not contain access_token");
            }
            Long parsedExpiresIn = JsonText.longValue(root, "expires_in");
            long expiresIn = parsedExpiresIn == null ? config.getTokenExpireSeconds() : parsedExpiresIn;
            if (expiresIn <= 0L) {
                expiresIn = config.getTokenExpireSeconds();
            }
            token = accessToken;
            expiresAtMillis = System.currentTimeMillis() + expiresIn * 1000L - EXPIRY_SAFETY_MILLIS;
            return token;
        } catch (TsdbDataException e) {
            throw new TsdbAuthenticationException("cannot parse GE OAuth response: " + e.getMessage(), e);
        }
    }

    /**
     * 对查询串参数做 URL 编码。
     *
     * <p>固定使用 UTF-8；{@code null} 按空串处理。JDK 声明支持 UTF-8，
     * 理论上不会抛出编码异常，兜底返回空串以避免在鉴权路径上再抛受检异常。</p>
     *
     * @param value 原始值，可为 {@code null}
     * @return 编码后的字符串，非 {@code null}
     */
    private static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return "";
        }
    }
}
