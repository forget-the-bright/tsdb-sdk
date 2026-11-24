package io.github.forget_the_bright.tsdb.pspace;

import io.github.forget_the_bright.tsdb.common.config.AuthConfig;
import io.github.forget_the_bright.tsdb.common.config.SecurityConfig;
import io.github.forget_the_bright.tsdb.common.config.TimeConfig;
import io.github.forget_the_bright.tsdb.common.config.TsdbInstanceConfig;
import io.github.forget_the_bright.tsdb.common.exception.TsdbConfigurationException;
import io.github.forget_the_bright.tsdb.common.internal.Texts;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * pSpace 驱动的已校验、不可变配置。
 *
 * <p>职责：把公共层的 {@link TsdbInstanceConfig}（配合根配置中的
 * {@link SecurityConfig}）翻译成驱动内部直接可用的参数，并在构造阶段一次性完成
 * 全部校验，使运行期不再重复做配置判断。构造成功后所有字段均为 {@code final}，
 * 实例不可变，可安全地在多线程间共享。</p>
 *
 * <p>配置项与默认值：</p>
 * <table border="1">
 *   <caption>配置项</caption>
 *   <tr><th>键</th><th>默认</th><th>说明</th></tr>
 *   <tr><td>{@code base-url}</td><td>必填</td><td>形如 {@code http://host:8080}，
 *       末尾斜杠会被去掉；明文 http 需要根配置开启
 *       {@code allow-insecure-transport}</td></tr>
 *   <tr><td>{@code options.pspace.charset}</td><td>{@code gbk}</td>
 *       <td>只接受 {@code gbk} 与 {@code utf-8}；同时用于请求体编码、响应解码与
 *       请求 JSON 里的 {@code charset} 字段</td></tr>
 *   <tr><td>{@code options.pspace.page-size}</td><td>{@code 1000}</td>
 *       <td>分页接口每页条数，必须为正；翻页按服务端 {@code page.count} 结束，
 *       不设人为页数上限</td></tr>
 *   <tr><td>{@code options.pspace.auto-refresh}</td><td>{@code true}</td>
 *       <td>读路径首次取空或命中“缓存未热”错误时，自动 POST {@code /Refresh}
 *       后重试一次</td></tr>
 *   <tr><td>{@code options.pspace.refresh-min-interval-ms}</td><td>{@code 1000}</td>
 *       <td>两次自动刷新的最小间隔，避免并发失败触发刷新风暴，必须非负</td></tr>
 *   <tr><td>{@code options.pspace.archived}</td><td>{@code 0}</td>
 *       <td>只接受 0。1 表示 zlib 压缩响应，本期未实现，配置即失败</td></tr>
 *   <tr><td>{@code options.time.*}</td><td>见下</td>
 *       <td>pSpace 时间是 {@code yyyy-MM-dd HH:mm:ss.SSS} 文本，未配置时由本类
 *       注入默认值（{@code GMT+8}）</td></tr>
 * </table>
 *
 * <p>时间默认值的注入方式：复制调用方的 {@code options}（不修改原对象），在副本中
 * 仅为缺失项补齐 {@code time.request.pattern}/{@code zone} 与
 * {@code time.response.source-zone}/{@code target-zone}/{@code pattern}，
 * 再用副本构造 {@link TimeConfig}。因此调用方显式配置的时间项永远优先。</p>
 *
 * <p>鉴权约定：pSpace Web API 没有 REST 客户端鉴权，账号配置在服务端 ini 中。
 * 一旦配置了任何 {@code auth.*} 字段（用户名、密码、token 等），本类直接失败，
 * 避免调用方误以为凭据生效。</p>
 *
 * <p>敏感信息：本类不保存任何凭据（配置了也会被拒绝），{@code toString()} 未重写
 * 也不会泄漏密码。</p>
 *
 * @author wanghao
 * @since 0.1.2
 */
public final class PspaceConfig {

    /** 默认字符集：力控 7.1 现场实测固定按 GBK 响应。 */
    private static final String DEFAULT_CHARSET = "gbk";

    /** 默认分页大小。 */
    private static final int DEFAULT_PAGE_SIZE = 1000;

    /** 默认自动刷新开关。 */
    private static final boolean DEFAULT_AUTO_REFRESH = true;

    /** 默认两次自动刷新的最小间隔（毫秒）。 */
    private static final long DEFAULT_REFRESH_MIN_INTERVAL_MILLIS = 1000L;

    /** pSpace 默认请求时间格式：与手册示例一致。 */
    private static final String DEFAULT_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss.SSS";

    /** pSpace 默认时区：现场服务器按东八区。 */
    private static final String DEFAULT_TIME_ZONE = "GMT+8";

    /** 实例逻辑名，仅用于日志与异常上下文。 */
    private final String instanceName;

    /** 规范化后的根地址：不含末尾斜杠，协议为 http 或 https。 */
    private final String baseUrl;

    /** 请求与响应统一使用的字符集。 */
    private final Charset charset;

    /** 分页接口每页条数，恒为正。 */
    private final int pageSize;

    /** 读路径失败时是否自动 POST {@code /Refresh} 后重试一次。 */
    private final boolean autoRefresh;

    /** 两次自动刷新的最小间隔，单位毫秒，恒为非负。 */
    private final long refreshMinIntervalMillis;

    /** 建立连接的默认超时，单位毫秒，恒为正。 */
    private final long connectTimeoutMillis;

    /** 读取响应正文的默认超时，单位毫秒，恒为正。 */
    private final long readTimeoutMillis;

    /** 读请求的最大尝试次数（含首次），恒 {@code >= 1}。 */
    private final int maxReadAttempts;

    /** 两次尝试之间的等待时间，单位毫秒，恒 {@code >= 0}。 */
    private final long retryBackoffMillis;

    /** 是否校验 HTTPS 主机名。 */
    private final boolean verifyHostname;

    /** 是否允许写入（来自根配置的安全开关），默认 {@code false}。 */
    private final boolean allowWrite;

    /** 时间解析与展示配置（已注入 pSpace 默认值）。 */
    private final TimeConfig timeConfig;

    /**
     * 从公共层配置构造并校验 pSpace 驱动配置。
     *
     * <p>构造过程只做解析与校验，不发起任何网络请求，因此可以在启动阶段反复调用；
     * {@link PspaceDriver#validate(TsdbInstanceConfig)} 正是借助这一特性做配置预检。</p>
     *
     * @param instanceName 实例逻辑名，用于日志与异常上下文；允许为 {@code null}
     * @param config       公共层的单实例配置，不能为 {@code null}
     * @param security     根配置中的安全选项；为 {@code null} 时按
     *                     {@link SecurityConfig} 默认值处理（禁止明文传输、校验主机名、只读）
     * @throws TsdbConfigurationException {@code config} 为 {@code null}、
     *         {@code base-url} 缺失或非法、明文传输未放行、配置了不支持的字符集、
     *         {@code archived=1}、分页参数非正、超时非正、重试次数小于 1，
     *         或错误配置了客户端鉴权时抛出
     */
    public PspaceConfig(String instanceName, TsdbInstanceConfig config, SecurityConfig security) {
        if (config == null) {
            throw new TsdbConfigurationException("pSpace instance config is required");
        }
        SecurityConfig sec = security == null ? new SecurityConfig() : security;

        String url = Texts.trimToNull(config.getBaseUrl());
        if (url == null) {
            throw new TsdbConfigurationException("pSpace base-url is required");
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new TsdbConfigurationException("invalid pSpace base-url: " + url, e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new TsdbConfigurationException("pSpace base-url must use http or https: " + url);
        }
        if ("http".equalsIgnoreCase(scheme) && !sec.isAllowInsecureTransport()) {
            throw new TsdbConfigurationException(
                    "plaintext pSpace transport is disabled; use https or set allow-insecure-transport=true");
        }
        if (hasClientAuth(config.getAuth())) {
            throw new TsdbConfigurationException(
                    "pSpace REST has no client authentication; configure the account in the server ini instead");
        }

        int archived = intOption(config, "pspace.archived", 0);
        if (archived != 0) {
            throw new TsdbConfigurationException(
                    "pSpace archived=1 (zlib) is not supported in this version; use archived=0");
        }

        long connect = config.getTimeout().getConnectMillis();
        long read = config.getTimeout().getReadMillis();
        if (connect <= 0 || read <= 0) {
            throw new TsdbConfigurationException("pSpace connect/read timeouts must be > 0");
        }
        int attempts = config.getRetry().getMaxAttempts();
        if (attempts < 1) {
            throw new TsdbConfigurationException("pSpace retry.max-attempts must be >= 1");
        }

        this.instanceName = instanceName;
        this.baseUrl = url;
        this.charset = charset(config.option("pspace.charset"));
        this.pageSize = positive(intOption(config, "pspace.page-size", DEFAULT_PAGE_SIZE), "pspace.page-size");
        this.autoRefresh = booleanOption(config, "pspace.auto-refresh", DEFAULT_AUTO_REFRESH);
        this.refreshMinIntervalMillis = Math.max(0L,
                longOption(config, "pspace.refresh-min-interval-ms", DEFAULT_REFRESH_MIN_INTERVAL_MILLIS));
        this.connectTimeoutMillis = connect;
        this.readTimeoutMillis = read;
        this.maxReadAttempts = attempts;
        this.retryBackoffMillis = Math.max(0L, config.getRetry().getBackoffMillis());
        this.verifyHostname = sec.isVerifyHostname();
        this.allowWrite = sec.isAllowWrite();
        this.timeConfig = timeConfig(config);
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
     * 返回规范化后的根地址。
     *
     * @return 不含末尾斜杠的 http/https 地址，非 {@code null}
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * 返回请求与响应统一使用的字符集。
     *
     * @return 非 {@code null} 的字符集，默认 GBK
     */
    public Charset getCharset() {
        return charset;
    }

    /**
     * 返回分页接口每页条数。
     *
     * @return 恒为正的页大小
     */
    public int getPageSize() {
        return pageSize;
    }

    /**
     * 返回是否启用“缓存未热自动刷新”。
     *
     * @return 启用返回 {@code true}
     */
    public boolean isAutoRefresh() {
        return autoRefresh;
    }

    /**
     * 返回两次自动刷新的最小间隔。
     *
     * @return 间隔毫秒数，非负
     */
    public long getRefreshMinIntervalMillis() {
        return refreshMinIntervalMillis;
    }

    /**
     * 返回建立连接的默认超时。
     *
     * @return 超时毫秒数，恒为正
     */
    public long getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    /**
     * 返回读取响应的默认超时。
     *
     * @return 超时毫秒数，恒为正
     */
    public long getReadTimeoutMillis() {
        return readTimeoutMillis;
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
     * @return 等待毫秒数，恒 {@code >= 0}；0 表示立即重试
     */
    public long getRetryBackoffMillis() {
        return retryBackoffMillis;
    }

    /**
     * 返回是否校验 HTTPS 主机名。
     *
     * @return 需要校验返回 {@code true}（默认）
     */
    public boolean isVerifyHostname() {
        return verifyHostname;
    }

    /**
     * 返回是否允许写入。
     *
     * <p>来源是根配置的 {@code security.allow-write}，默认 {@code false}。
     * 为 {@code false} 时写入会话 {@code isWritable()} 返回 {@code false}，
     * {@code write} 抛 {@code TsdbUnsupportedException}。</p>
     *
     * @return 允许写入返回 {@code true}
     */
    public boolean isAllowWrite() {
        return allowWrite;
    }

    /**
     * 返回时间解析与展示配置。
     *
     * <p>已注入 pSpace 默认值：请求与响应模式均为
     * {@value #DEFAULT_TIME_PATTERN}，时区均为 {@code GMT+8}。</p>
     *
     * @return 非 {@code null} 的不可变时间配置
     */
    public TimeConfig getTimeConfig() {
        return timeConfig;
    }

    /**
     * 拼接完整请求地址。
     *
     * @param path 相对路径，例如 {@code "/ServerInfo"}；不以 {@code /} 开头时自动补上，
     *             不能为 {@code null}
     * @return 非 {@code null} 的绝对 URL 字符串
     */
    public String url(String path) {
        String normalized = path.startsWith("/") ? path : "/" + path;
        return baseUrl + normalized;
    }

    /**
     * 解析字符集：只接受 gbk/cp936 与 utf-8/utf8，其余配置直接失败。
     *
     * @param value 原始配置值，可为 {@code null}（取默认 GBK）
     * @return 非 {@code null} 的字符集
     * @throws TsdbConfigurationException 配置了不支持的字符集
     */
    private static Charset charset(String value) {
        String text = Texts.trimToNull(value);
        if (text == null) {
            text = DEFAULT_CHARSET;
        }
        String normalized = text.toLowerCase(java.util.Locale.ROOT).replace("_", "-");
        if ("gbk".equals(normalized) || "cp936".equals(normalized)) {
            return Charset.forName("GBK");
        }
        if ("utf-8".equals(normalized) || "utf8".equals(normalized)) {
            return StandardCharsets.UTF_8;
        }
        throw new TsdbConfigurationException(
                "pSpace charset must be gbk or utf-8, but was: " + value);
    }

    /**
     * 校验正整数配置项。
     *
     * @param value 解析后的值
     * @param key   配置键名，用于异常消息
     * @return 原值
     * @throws TsdbConfigurationException 值不大于 0
     */
    private static int positive(int value, String key) {
        if (value <= 0) {
            throw new TsdbConfigurationException("pSpace " + key + " must be > 0");
        }
        return value;
    }

    /**
     * 读取整数配置项，把公共层的 {@link NumberFormatException} 转成配置异常。
     *
     * @param config       公共层配置
     * @param key          配置键名
     * @param defaultValue 缺失时的默认值
     * @return 配置值或默认值
     * @throws TsdbConfigurationException 配置值不是整数
     */
    private static int intOption(TsdbInstanceConfig config, String key, int defaultValue) {
        try {
            return config.intOption(key, defaultValue);
        } catch (NumberFormatException e) {
            throw new TsdbConfigurationException("pSpace " + key + " must be an integer", e);
        }
    }

    /**
     * 读取长整数配置项，把公共层的 {@link NumberFormatException} 转成配置异常。
     *
     * @param config       公共层配置
     * @param key          配置键名
     * @param defaultValue 缺失时的默认值
     * @return 配置值或默认值
     * @throws TsdbConfigurationException 配置值不是整数
     */
    private static long longOption(TsdbInstanceConfig config, String key, long defaultValue) {
        try {
            return config.longOption(key, defaultValue);
        } catch (NumberFormatException e) {
            throw new TsdbConfigurationException("pSpace " + key + " must be an integer", e);
        }
    }

    /**
     * 读取布尔配置项，只接受 true/false（大小写不敏感由公共层决定）。
     *
     * @param config       公共层配置
     * @param key          配置键名
     * @param defaultValue 缺失时的默认值
     * @return 配置值或默认值
     * @throws TsdbConfigurationException 配置值不是布尔
     */
    private static boolean booleanOption(TsdbInstanceConfig config, String key, boolean defaultValue) {
        try {
            return config.booleanOption(key, defaultValue);
        } catch (IllegalArgumentException e) {
            throw new TsdbConfigurationException("pSpace " + key + " must be true or false", e);
        }
    }

    /**
     * 判断是否配置了任何客户端鉴权字段。
     *
     * <p>pSpace Web API 不支持 REST 客户端鉴权，命中任意非空字段即视为误配置，
     * 由构造函数拒绝，避免调用方以为凭据生效。</p>
     *
     * @param auth 鉴权配置，可为 {@code null}
     * @return 配置了任一鉴权字段返回 {@code true}
     */
    private static boolean hasClientAuth(AuthConfig auth) {
        if (auth == null) {
            return false;
        }
        return Texts.trimToNull(auth.getMode()) != null
                || Texts.trimToNull(auth.getUsername()) != null
                || Texts.trimToNull(auth.getPassword()) != null
                || Texts.trimToNull(auth.getClientId()) != null
                || Texts.trimToNull(auth.getClientSecret()) != null
                || Texts.trimToNull(auth.getUserId()) != null
                || Texts.trimToNull(auth.getSecretKey()) != null
                || Texts.trimToNull(auth.getToken()) != null
                || !auth.getOptions().isEmpty();
    }

    /**
     * 构造一份注入 pSpace 时间默认值的配置副本，再交给 {@link TimeConfig#from}。
     *
     * <p>只补缺失项，不覆盖调用方显式配置；返回的是副本，不影响传入对象。</p>
     */
    private static TimeConfig timeConfig(TsdbInstanceConfig source) {
        Map<String, Object> options = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : source.getOptions().entrySet()) {
            options.put(entry.getKey(), entry.getValue());
        }
        Map<String, Object> time = copyMap(options.get("time"));
        Map<String, Object> request = copyMap(time.get("request"));
        putIfAbsent(request, "pattern", DEFAULT_TIME_PATTERN);
        putIfAbsent(request, "zone", DEFAULT_TIME_ZONE);
        time.put("request", request);
        Map<String, Object> response = copyMap(time.get("response"));
        putIfAbsent(response, "pattern", DEFAULT_TIME_PATTERN);
        putIfAbsent(response, "source-zone", DEFAULT_TIME_ZONE);
        putIfAbsent(response, "target-zone", DEFAULT_TIME_ZONE);
        time.put("response", response);
        options.put("time", time);

        TsdbInstanceConfig copy = new TsdbInstanceConfig();
        copy.setBaseUrl(source.getBaseUrl());
        copy.setOptions(options);
        return TimeConfig.from(copy);
    }

    /**
     * 浅拷贝一个 Map；非 Map 输入返回空 Map。
     *
     * @param value 原始配置节点，可为 {@code null}
     * @return 非 {@code null} 的新 Map
     */
    private static Map<String, Object> copyMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    /**
     * 仅在键缺失（或值为 {@code null}）时写入默认值，显式配置优先。
     *
     * @param map   目标 Map
     * @param key   键
     * @param value 默认值
     */
    private static void putIfAbsent(Map<String, Object> map, String key, String value) {
        if (map.get(key) == null) {
            map.put(key, value);
        }
    }
}