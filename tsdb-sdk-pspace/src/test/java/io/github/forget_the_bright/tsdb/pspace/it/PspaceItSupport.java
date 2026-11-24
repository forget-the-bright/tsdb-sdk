package io.github.forget_the_bright.tsdb.pspace.it;

import io.github.forget_the_bright.tsdb.common.config.SecurityConfig;
import io.github.forget_the_bright.tsdb.common.config.TsdbInstanceConfig;
import io.github.forget_the_bright.tsdb.common.exception.TsdbQueryException;
import io.github.forget_the_bright.tsdb.pspace.PspaceClient;
import io.github.forget_the_bright.tsdb.pspace.PspaceConfig;
import io.github.forget_the_bright.tsdb.pspace.internal.PspaceModels;
import io.github.forget_the_bright.tsdb.pspace.internal.PspaceNativeApi;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * pSpace 集成测试的共享装配与探测工具。
 *
 * <p>配置优先级：系统属性 {@code pspace.it.*} → 环境变量 {@code PSPACE_IT_*} →
 * 当前目录或 {@code tsdb-sdk-pspace/} 下的 {@code pspace-it.properties}。
 * 该文件被 {@code .gitignore} 排除，凭据与现场地址不得写进仓库。</p>
 *
 * <p>写入门禁：除 {@code -Dpspace.it.allow-write=true} 外，base URL 的主机名必须属于
 * {127.0.0.1, localhost, 172.16.176.14}，防止误对生产环境造数。</p>
 *
 * @author wanghao
 * @since 0.1.2
 */
final class PspaceItSupport {

    /** 模拟环境默认地址（用户提供的力控 7.1 联调机）。 */
    static final String DEFAULT_BASE_URL = "http://172.16.176.14:8080";

    /** 允许造数的主机白名单。 */
    private static final Set<String> WRITE_HOSTS = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("127.0.0.1", "localhost", "172.16.176.14")));

    private PspaceItSupport() {
    }

    /**
     * @return 是否显式打开只读集成测试（{@code -Dpspace.it=true} 或 {@code PSPACE_IT=true}）
     */
    static boolean enabled() {
        String property = System.getProperty("pspace.it");
        if (blank(property)) {
            property = System.getenv("PSPACE_IT");
        }
        return property != null && Boolean.parseBoolean(property.trim());
    }

    /**
     * @return 是否显式打开写/造数门禁（{@code -Dpspace.it.allow-write=true}）
     */
    static boolean writeAllowed() {
        String property = System.getProperty("pspace.it.allow-write");
        if (blank(property)) {
            property = System.getenv("PSPACE_IT_ALLOW_WRITE");
        }
        return property != null && Boolean.parseBoolean(property.trim());
    }

    /**
     * 读取 {@code pspace-it.properties}（若存在）。
     *
     * @return 非 {@code null} 的属性集合
     * @throws IOException 文件读取失败时
     */
    static Properties loadProperties() throws IOException {
        Properties properties = new Properties();
        Path configured = pathProperty("pspace.it.config", "PSPACE_IT_CONFIG");
        if (configured != null && Files.isRegularFile(configured)) {
            load(properties, configured);
            return properties;
        }
        Path[] candidates = new Path[]{
                Paths.get("pspace-it.properties"),
                Paths.get("tsdb-sdk-pspace", "pspace-it.properties"),
                Paths.get(System.getProperty("user.home", "."), "pspace-it.properties")
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                load(properties, candidate);
                break;
            }
        }
        return properties;
    }

    /**
     * 读取配置值：系统属性 → 环境变量 → 属性文件 → 默认值。
     *
     * @param file        属性文件
     * @param key         键名（不含 {@code pspace.it.} 前缀）
     * @param environment 环境变量名
     * @param fallback    默认值
     * @return 非空白字符串
     */
    static String value(Properties file, String key, String environment, String fallback) {
        String value = System.getProperty("pspace.it." + key);
        if (blank(value)) {
            value = System.getenv(environment);
        }
        if (blank(value)) {
            value = file.getProperty(key);
        }
        return blank(value) ? fallback : value.trim();
    }

    static int intValue(Properties file, String key, String environment, int fallback) {
        return Integer.parseInt(value(file, key, environment, Integer.toString(fallback)));
    }

    static long longValue(Properties file, String key, String environment, long fallback) {
        return Long.parseLong(value(file, key, environment, Long.toString(fallback)));
    }

    static boolean booleanValue(Properties file, String key, String environment, boolean fallback) {
        return Boolean.parseBoolean(value(file, key, environment, Boolean.toString(fallback)));
    }

    /**
     * @param file       属性文件
     * @return base URL；未配置时返回模拟环境地址
     */
    static String baseUrl(Properties file) {
        return value(file, "baseUrl", "PSPACE_IT_BASE_URL", DEFAULT_BASE_URL);
    }

    /**
     * 判断 base URL 的主机是否允许造数。
     *
     * @param baseUrl 服务地址
     * @return 主机在白名单内返回 {@code true}
     */
    static boolean writeHostAllowed(String baseUrl) {
        try {
            String host = URI.create(baseUrl).getHost();
            return host != null && WRITE_HOSTS.contains(host.toLowerCase(java.util.Locale.ROOT));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 按测试配置装配客户端。
     *
     * @param file       属性文件
     * @param allowWrite 是否打开 SDK 写入门禁
     * @return 非 {@code null} 的客户端
     */
    static PspaceClient client(Properties file, boolean allowWrite) {
        TsdbInstanceConfig instance = new TsdbInstanceConfig();
        instance.setType("pspace");
        instance.setBaseUrl(baseUrl(file));
        instance.getTimeout().setConnectMillis(
                longValue(file, "connectTimeoutMs", "PSPACE_IT_CONNECT_TIMEOUT_MS", 5000L));
        instance.getTimeout().setReadMillis(
                longValue(file, "readTimeoutMs", "PSPACE_IT_READ_TIMEOUT_MS", 30000L));
        instance.getOptions().put("pspace.charset",
                value(file, "charset", "PSPACE_IT_CHARSET", "gbk"));
        instance.getOptions().put("pspace.page-size",
                value(file, "pageSize", "PSPACE_IT_PAGE_SIZE", "1000"));
        instance.getOptions().put("pspace.auto-refresh",
                value(file, "autoRefresh", "PSPACE_IT_AUTO_REFRESH", "true"));
        instance.getOptions().put("pspace.refresh-min-interval-ms",
                value(file, "refreshMinIntervalMs", "PSPACE_IT_REFRESH_MIN_INTERVAL_MS", "1000"));

        SecurityConfig security = new SecurityConfig();
        security.setAllowWrite(allowWrite);
        // 联调机与现场均为明文 http，必须显式放行；长期方案应加 TLS 代理。
        security.setAllowInsecureTransport(true);
        security.setVerifyHostname(false);

        return new PspaceClient("pspace-it", new PspaceConfig("pspace-it", instance, security));
    }

    /**
     * 先 POST {@code /Refresh} 再翻页探测全部点位（含目录节点）。
     *
     * <p>末页完全由服务端 {@code page.count} 决定；若 {@code page.count} 缺失且
     * 当前页已满，直接抛异常，避免只探测到部分点位却继续当作全量。</p>
     *
     * @param api 原生 API
     * @return 非 {@code null} 的点位列表
     */
    static List<PspaceModels.Point> probePoints(PspaceNativeApi api) {
        api.refresh();
        List<PspaceModels.Point> all = new ArrayList<PspaceModels.Point>();
        int pageSize = 500;
        int pageNumber = 1;
        while (true) {
            PspaceModels.Page page = api.pointPage(pageSize, pageNumber);
            all.addAll(api.parsePoints(page.values));
            if (page.values.isEmpty()) {
                break;
            }
            if (page.pageCount > 0) {
                if (pageNumber >= page.pageCount) {
                    break;
                }
            } else if (page.values.size() < pageSize) {
                break;
            } else {
                throw new TsdbQueryException("/PointPage page " + pageNumber
                        + " is full but page.count is missing; refusing to probe a partial catalog");
            }
            pageNumber++;
        }
        return all;
    }

    /**
     * 过滤出可读的位号：非目录节点且 LongName 非空。
     *
     * @param points 点位列表
     * @param limit  最多返回数量
     * @return 非 {@code null} 的位号长名列表
     */
    static List<String> readableTags(List<PspaceModels.Point> points, int limit) {
        List<String> tags = new ArrayList<String>();
        for (PspaceModels.Point point : points) {
            if (point == null || point.node || blank(point.longName)) {
                continue;
            }
            tags.add(point.longName);
            if (tags.size() >= limit) {
                break;
            }
        }
        return tags;
    }

    static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static void load(Properties properties, Path path) throws IOException {
        try (Reader reader = new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
    }

    private static Path pathProperty(String property, String environment) {
        String value = System.getProperty(property);
        if (blank(value)) {
            value = System.getenv(environment);
        }
        return blank(value) ? null : Paths.get(value);
    }
}