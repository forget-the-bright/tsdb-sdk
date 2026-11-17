package io.github.forget_the_bright.tsdb.hls.internal;

import com.alibaba.fastjson.JSONObject;
import io.github.forget_the_bright.tsdb.common.exception.TsdbAuthenticationException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbDataException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbException;
import io.github.forget_the_bright.tsdb.common.internal.json.JsonText;
import io.github.forget_the_bright.tsdb.hls.HlsConfig;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HLS 访问 token 的缓存与刷新器。
 *
 * <p>职责：向 {@code /user/get_token} 申请 token、缓存到本地、在到期前自动
 * 刷新，并对外提供可直接放入请求头的 token。HLS 的鉴权方式是“裸 token 放在
 * 名为 {@code token} 的请求头里”（没有 {@code Bearer} 前缀，也不是
 * {@code Authorization} 头），这一点由 {@link #tokenHeader()} 统一封装，
 * 避免各调用点写错头名。</p>
 *
 * <p>token 申请兼容两类厂商/代理行为：</p>
 * <ul>
 *   <li><b>POST + JSON body</b>：{@code {"userid":"...","secretkey":"..."}}，
 *       适用于要求 JSON 的较新服务和反向代理；</li>
 *   <li><b>GET + 请求头</b>：{@code userid}/{@code secretkey} 放请求头，
 *       与原 HLS SDK 的行为一致，适用于老版本服务。</li>
 * </ul>
 * <p>刷新时先尝试 POST JSON，失败后自动回退到 GET 头模式；两条路径都失败时
 * 抛出最后一条异常，并把前一条作为 suppressed 异常保留，便于现场排查。
 * 这样既不会破坏原 SDK 能用的老环境，也能适配只接受 JSON 的新环境。</p>
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>两种凭证模式：配置了静态 token 时直接复用、永不刷新；否则用
 *       {@code userid}/{@code secretkey} 动态申请；</li>
 *   <li>缓存有效期按“厂商 token 有效期减去 5 秒安全边界”计算；当有效期过短
 *       （小于 1 秒）时退化为有效期的一半（不少于 500 毫秒），避免频繁刷新；</li>
 *   <li>刷新方法加 {@code synchronized} 并在锁内二次检查，保证并发过期时
 *       只发起一次申请；读取路径用 {@code volatile} 字段做无锁快路径；</li>
 *   <li>收到 401 后由调用方调用 {@link #invalidate()} 主动失效，下次获取时
 *       重新申请；静态 token 模式下失效操作无效（凭证由配置决定，无法刷新）。</li>
 * </ul>
 *
 * <p>线程安全：{@code token} 与 {@code expiresAtMillis} 均为 {@code volatile}，
 * 读路径无锁、写路径串行化，可被多线程并发使用。</p>
 *
 * <p>与原生层的关系：{@link HlsNativeApi} 在每次业务请求前调用
 * {@link #tokenHeader()} 取头；本类复用同一个 {@link HlsHttpClient} 完成
 * token 请求，因此超时与 TLS 行为与业务请求完全一致。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class HlsTokenManager {

    /**
     * 驱动配置，提供静态 token、动态凭证与 token 有效期。
     */
    private final HlsConfig config;

    /**
     * 复用的 HTTP 传输层，用于请求 {@code /user/get_token}。
     */
    private final HlsHttpClient http;

    /**
     * 当前缓存的 token；静态 token 模式下恒为配置值，动态模式下刷新时整体替换。
     */
    private volatile String token;

    /**
     * 缓存到期时间（epoch 毫秒）；静态 token 模式下为 {@link Long#MAX_VALUE}，
     * 表示永不过期。
     */
    private volatile long expiresAtMillis;

    /**
     * 创建 token 管理器。
     *
     * <p>构造时不会发起网络请求：静态 token 直接进入缓存并标记永不过期；
     * 动态模式留空，等第一次 {@link #getToken()} 时再申请。</p>
     *
     * @param config 驱动配置，不能为 {@code null}
     * @param http   HTTP 传输层，不能为 {@code null}
     */
    public HlsTokenManager(HlsConfig config, HlsHttpClient http) {
        this.config = config;
        this.http = http;
        this.token = config.getStaticToken();
        this.expiresAtMillis = config.getStaticToken() == null ? 0L : Long.MAX_VALUE;
    }

    /**
     * 获取可用的 token。
     *
     * <p>快路径：缓存非空且未到期时直接返回（无锁）。否则进入
     * {@link #refresh()} 申请或等待其它线程完成刷新。</p>
     *
     * @return 非 {@code null}、非空的 token 字符串
     * @throws TsdbAuthenticationException 当厂商拒绝申请（{@code code != 0}）或
     *                                     响应缺少 {@code Data.Token} 时
     * @throws TsdbDataException           当 token 响应不是合法 JSON 时
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbTimeoutException
     *                                     当 token 请求超时时
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbConnectionException
     *                                     当 token 请求网络失败时
     */
    public String getToken() {
        String current = token;
        if (current != null && System.currentTimeMillis() < expiresAtMillis) {
            return current;
        }
        return refresh();
    }

    /**
     * 强制刷新 token（或在缓存仍有效时直接复用）。
     *
     * <p>行为细节：</p>
     * <ul>
     *   <li>静态 token 模式：直接返回配置值，不发请求、不改变过期时间；</li>
     *   <li>动态模式：进入同步块后二次检查缓存，避免并发重复申请；</li>
     *   <li>请求头使用 {@code userid} 与 {@code secretkey}，响应 {@code code}
     *       必须为 0，token 取 {@code Data.Token}（同时兼容小写 {@code data}
     *       与字段名大小写不一致的返回）；</li>
     *   <li>缓存时长 = {@code token-expire-seconds * 1000 - 5000} 毫秒；若结果
     *       小于 1 秒，则改为 {@code max(500, token-expire-seconds * 1000 / 2)}
     *       毫秒，保证至少能缓存一小段时间。</li>
     * </ul>
     *
     * @return 非 {@code null}、非空的新 token
     * @throws TsdbAuthenticationException 当厂商拒绝申请或响应缺少 token 时
     * @throws TsdbDataException           当响应不是合法 JSON 时
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbTimeoutException
     *                                     当请求超时时
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbConnectionException
     *                                     当请求网络失败时
     */
    public synchronized String refresh() {
        if (config.getStaticToken() != null) {
            return config.getStaticToken();
        }
        if (token != null && System.currentTimeMillis() < expiresAtMillis) {
            return token;
        }
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("userid", config.getUserId());
        headers.put("secretkey", config.getSecretKey());

        /*
         * 新版 HLS / 反向代理要求把凭证放在 JSON body 里；老版原 SDK 则把
         * userid/secretkey 放请求头。这里先走 JSON，失败再回退到原 SDK 的
         * GET 头模式，避免为了适配一个现场而破坏另一个现场。
         */
        JSONObject request = new JSONObject();
        request.put("userid", config.getUserId());
        request.put("secretkey", config.getSecretKey());
        TsdbException postFailure = null;
        try {
            return parseAndCache(http.post("/user/get_token", headers, request.toString(),
                    HlsHttpClient.STRICT_JSON_CONTENT_TYPE));
        } catch (TsdbException e) {
            postFailure = e;
        }

        try {
            return parseAndCache(http.get("/user/get_token", headers));
        } catch (TsdbException e) {
            if (postFailure != null) {
                e.addSuppressed(postFailure);
            }
            throw e;
        }
    }

    /**
     * 解析 token 响应、校验业务码并写入缓存。
     *
     * <p>成功响应形如
     * {@code {"code":0,"data":{"token":"..."},"msg":"Success"}}；字段名大小写
     * 由 {@link #text(Object, String)} 兼容。业务码非 0 时按鉴权失败处理，
     * 不把厂商的 {@code msg} 丢掉，方便现场判断是凭证错误还是接口用法错误。</p>
     *
     * @param body token 接口返回的 JSON 文本
     * @return 可用的 token
     * @throws TsdbAuthenticationException 业务码非 0 或缺少 token
     * @throws TsdbDataException           响应不是合法 JSON
     */
    private String parseAndCache(String body) {
        Object root = JsonText.parse(body);
        Integer parsedCode = JsonText.integer(root, "code");
        int code = parsedCode == null ? -1 : parsedCode;
        if (code != 0) {
            String msg = JsonText.text(root, "msg");
            throw new TsdbAuthenticationException(
                    "HLS token request rejected: code=" + code + ", msg=" + (msg == null ? "" : msg));
        }
        String newToken = text(root, "Token");
        if (newToken == null || newToken.isEmpty()) {
            throw new TsdbAuthenticationException("HLS token response did not contain Data.Token");
        }
        long ttlMillis = config.getTokenExpireSeconds() * 1000L - 5000L;
        if (ttlMillis < 1000L) {
            ttlMillis = Math.max(500L, config.getTokenExpireSeconds() * 1000L / 2L);
        }
        this.token = newToken;
        this.expiresAtMillis = System.currentTimeMillis() + ttlMillis;
        return newToken;
    }

    /**
     * 主动使缓存 token 失效。
     *
     * <p>用于业务请求收到 401/403 后强制换新 token。仅对动态 token 生效；
     * 静态 token 由配置决定、无法刷新，因此调用本方法不会清空缓存，也不会
     * 抛异常。失效后下一次 {@link #getToken()} 会重新申请。</p>
     */
    public void invalidate() {
        if (config.getStaticToken() == null) {
            this.token = null;
            this.expiresAtMillis = 0L;
        }
    }

    /**
     * 从 token 响应中读取指定字段。
     *
     * <p>先定位 {@code Data} 节点（找不到时退回小写 {@code data}），再按字段名
     * 取值；节点缺失或为 JSON {@code null} 时返回 {@code null}，否则返回其文本
     * 形式。厂商在不同版本中字段大小写并不完全一致，因此取值统一走
     * {@link JsonText#valueIgnoreCase(Object, String)} 做大小写不敏感匹配。</p>
     *
     * @param root  响应根节点，不能为 {@code null}
     * @param field 字段名（例如 {@code "Token"}）
     * @return 字段文本；缺失或为 {@code null} 时返回 {@code null}
     */
    private static String text(Object root, String field) {
        Object data = JsonText.valueIgnoreCase(root, "Data");
        if (data == null) {
            return null;
        }
        return JsonText.textIgnoreCase(data, field);
    }

    /**
     * 构造携带 token 的请求头。
     *
     * <p>HLS 要求把裸 token（不加 {@code Bearer} 前缀）放进名为 {@code token}
     * 的请求头，本方法即返回该单条目映射。包内可见，仅供
     * {@link HlsNativeApi} 使用。</p>
     *
     * @return 不可变的单条目请求头 {@code {"token": <当前 token>}}
     * @throws TsdbAuthenticationException 当 token 申请失败时（由
     *                                     {@link #getToken()} 抛出）
     */
    Map<String, String> tokenHeader() {
        return Collections.singletonMap("token", getToken());
    }
}
