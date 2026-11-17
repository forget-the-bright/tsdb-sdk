package io.github.forget_the_bright.tsdb.common.config;

import io.github.forget_the_bright.tsdb.common.exception.TsdbConfigurationException;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将 {@code tsdb.yml}（或等价的 YAML 文本/输入流）解析为纯配置 POJO 的工具类。
 *
 * <p>文档可以以 {@code tsdb:} 作为根键包裹，也可以让 {@code instances} 等键直接位于根节点，
 * 两种写法等价；解析结果统一为 {@link TsdbConfig}。字符串值支持
 * <code>${ENV}</code> 与 <code>${ENV:default}</code> 两种占位符，便于把账号口令
 * 放在环境变量里而不落库到源码仓库。</p>
 *
 * <p>键名同时接受 kebab-case（推荐，如 {@code base-url}）与 camelCase（如 {@code baseUrl}），
 * 同一层级两种写法都出现时以参数表中靠前的写法优先，不做合并。数字、布尔值也可以写成
 * 字符串，解析时会先 trim 再转换。</p>
 *
 * <p>解析过程会做以下校验，失败一律抛出
 * {@link TsdbConfigurationException}：根节点必须是映射；{@code tsdb} 键存在时其值必须是映射；
 * 每个实例的值必须是映射；最终至少要有一个实例。</p>
 *
 * <p>线程安全：本类无可变状态，所有公开方法都是静态的且只使用局部变量，
 * 可以并发调用；但每次调用都会新建 {@link Yaml} 实例（{@code Yaml} 对象本身不是线程安全的）。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class TsdbConfigLoader {

    /**
     * 私有构造器：本类是只提供静态方法的工具类，不允许实例化。
     */
    private TsdbConfigLoader() {
    }

    /**
     * 从文件路径读取并解析配置，文件按 UTF-8 解码。
     *
     * <p>整个文件一次性读入内存，因此适合几 KB 到几百 KB 的配置文件；
     * 读取失败会包装为配置异常，保留原始 {@link IOException} 作为 cause，
     * 便于定位“文件不存在/无权限”等环境问题。</p>
     *
     * @param path 配置文件路径，不能为 {@code null}
     * @return 解析后的根配置，至少包含一个实例
     * @throws IllegalArgumentException 当 {@code path} 为 {@code null} 时抛出
     * @throws TsdbConfigurationException 当文件读取失败或内容不合法（空文件、根节点不是映射、
     *                                    没有实例等）时抛出，消息中带有路径
     */
    public static TsdbConfig load(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            return fromYaml(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new TsdbConfigurationException("cannot read tsdb config " + path + ": " + e.getMessage(), e);
        }
    }

    /**
     * 从输入流读取并解析配置，按 UTF-8 解码（由 SnakeYAML 决定，实际为逐字符读取）。
     *
     * <p>本方法<b>不关闭</b>传入的流，生命周期由调用方负责；读取过程不缓冲整个文件，
     * 适合从 classpath 资源或容器配置中心流式加载。使用 {@link SafeConstructor}，
     * 因此 YAML 中的自定义标签（{@code !!java...}）不会被实例化，可安全解析不可信输入。</p>
     *
     * @param in YAML 输入流，不能为 {@code null}
     * @return 解析后的根配置，至少包含一个实例
     * @throws IllegalArgumentException 当 {@code in} 为 {@code null} 时抛出
     * @throws TsdbConfigurationException 当内容不是合法 YAML 或结构不符合约定时抛出
     *                                    （由内部解析逻辑抛出，可能包装 SnakeYAML 的异常）
     */
    public static TsdbConfig fromYaml(InputStream in) {
        if (in == null) {
            throw new IllegalArgumentException("input stream must not be null");
        }
        return parse(new Yaml(new SafeConstructor()).load(in));
    }

    /**
     * 从 YAML 文本解析配置。
     *
     * <p>空白文本（{@code null}、空串、只含空白字符）会被明确拒绝，而不是解析成
     * {@code null} 后再报“根节点不是映射”，这样错误信息更贴近真实原因。</p>
     *
     * @param yamlText YAML 文本，不能为 {@code null} 或空白
     * @return 解析后的根配置，至少包含一个实例
     * @throws TsdbConfigurationException 当文本为空、YAML 语法错误或结构不符合约定时抛出
     */
    public static TsdbConfig fromYaml(String yamlText) {
        if (yamlText == null || yamlText.trim().isEmpty()) {
            throw new TsdbConfigurationException("tsdb config is empty");
        }
        return parse(new Yaml(new SafeConstructor()).load(yamlText));
    }

    /**
     * 把 SnakeYAML 产出的原始对象树转换为 {@link TsdbConfig}。
     *
     * <p>处理顺序：先取根映射，若存在 {@code tsdb} 键则下钻一层；随后读取
     * {@code default-instance}、{@code fail-fast}、{@code security}、{@code instances}。
     * 实例名为空时不会报错，但 {@code TsdbManager} 之后会拒绝该实例。</p>
     *
     * @param loaded SnakeYAML 反序列化后的对象，通常为 {@link Map}
     * @return 解析完成的根配置
     * @throws TsdbConfigurationException 根节点/实例节点不是映射，或没有任何实例时抛出
     */
    private static TsdbConfig parse(Object loaded) {
        Map<String, Object> root = asMap(loaded);
        if (root == null) {
            throw new TsdbConfigurationException("tsdb config root must be a mapping");
        }
        Object nested = root.get("tsdb");
        Map<String, Object> tsdb = nested == null ? root : asMap(nested);
        if (tsdb == null) {
            throw new TsdbConfigurationException("tsdb config must be a mapping");
        }

        TsdbConfig config = new TsdbConfig();
        String defaultInstance = string(tsdb, "default-instance", "defaultInstance");
        if (defaultInstance != null && !defaultInstance.trim().isEmpty()) {
            config.setDefaultInstance(defaultInstance.trim());
        }
        Boolean failFast = bool(tsdb, "fail-fast", "failFast");
        if (failFast != null) {
            config.setFailFast(failFast.booleanValue());
        }
        config.setSecurity(security(tsdb.get("security")));

        Map<String, Object> instances = asMap(tsdb.get("instances"));
        if (instances != null) {
            // YAML 1.1 会把不加引号的 on/off/yes/no 键解析成布尔值，
            // 这里遍历原始 entrySet 并统一 String.valueOf，避免遗留配置因强转 Map<String, Object> 而抛 ClassCastException。
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) instances).entrySet()) {
                String name = String.valueOf(entry.getKey());
                Map<String, Object> value = asMap(entry.getValue());
                if (value == null) {
                    throw new TsdbConfigurationException("instance '" + name + "' must be a mapping");
                }
                config.getInstances().put(name, instance(value));
            }
        }
        if (config.getInstances().isEmpty()) {
            throw new TsdbConfigurationException("at least one tsdb instance is required");
        }
        return config;
    }

    /**
     * 把单个实例的 YAML 映射转换为 {@link TsdbInstanceConfig}。
     *
     * <p>{@code timeout} 与 {@code retry} 支持两种写法：嵌套在子映射下，或直接写在实例层级，
     * 见 {@link #timeout(Object, Map)} 与 {@link #retry(Object, Map)}。{@code options}
     * 保留原始类型且不做环境变量替换（只有字符串类字段会做替换）。</p>
     *
     * @param map 实例对应的 YAML 映射，不能为 {@code null}
     * @return 实例配置，各子配置字段均非 {@code null}
     */
    private static TsdbInstanceConfig instance(Map<String, Object> map) {
        TsdbInstanceConfig config = new TsdbInstanceConfig();
        config.setType(string(map, "type"));
        config.setProtocol(string(map, "protocol"));
        Boolean enabled = bool(map, "enabled");
        if (enabled != null) {
            config.setEnabled(enabled.booleanValue());
        }
        config.setBaseUrl(string(map, "base-url", "baseUrl"));
        config.setEndpoint(string(map, "endpoint"));
        config.setNodeUrls(strings(map.get("node-urls"), map.get("nodeUrls")));
        config.setAuth(auth(map.get("auth")));
        config.setTimeout(timeout(map.get("timeout"), map));
        config.setRetry(retry(map.get("retry"), map));
        config.setOptions(stringObjectMap(map.get("options")));
        return config;
    }

    /**
     * 解析 {@code auth} 子映射。
     *
     * <p>缺失或不是映射时返回空认证配置（所有字段为 {@code null}），把“必填项缺失”的
     * 判断留给驱动，公共层不预设某种认证模式的规则。{@code options} 中的值会做环境变量替换。</p>
     *
     * @param value {@code auth} 键对应的原始对象，可为 {@code null}
     * @return 认证配置，非 {@code null}
     */
    private static AuthConfig auth(Object value) {
        AuthConfig auth = new AuthConfig();
        Map<String, Object> map = asMap(value);
        if (map == null) {
            return auth;
        }
        auth.setMode(string(map, "mode"));
        auth.setUsername(string(map, "username"));
        auth.setPassword(string(map, "password"));
        auth.setClientId(string(map, "client-id", "clientId"));
        auth.setClientSecret(string(map, "client-secret", "clientSecret"));
        auth.setUserId(string(map, "user-id", "userId"));
        auth.setSecretKey(string(map, "secret-key", "secretKey"));
        auth.setToken(string(map, "token"));
        auth.setOptions(stringMap(map.get("options")));
        return auth;
    }

    /**
     * 解析超时配置，支持嵌套与实例层级两种写法。
     *
     * <p>查找顺序：先看 {@code timeout} 子映射，键为 {@code connect}/{@code read}/{@code write}
     * （单位毫秒）；子映射中没找到时，回退到实例层级的 {@code connect-timeout-ms}、
     * {@code read-timeout-ms}、{@code write-timeout-ms} 及其 camelCase 写法。
     * 两边都没有时保留 {@link TimeoutConfig} 的默认值，不做覆盖。</p>
     *
     * @param nested {@code timeout} 子映射，可为 {@code null}
     * @param parent 实例层级的映射，用于兼容扁平写法，不能为 {@code null}
     * @return 超时配置，非 {@code null}
     * @throws TsdbConfigurationException 数值无法解析时由 {@link #longValue(Map, String...)} 抛出
     */
    private static TimeoutConfig timeout(Object nested, Map<String, Object> parent) {
        TimeoutConfig timeout = new TimeoutConfig();
        Map<String, Object> map = asMap(nested);
        Long connect = firstLong(map, parent, "connect", "connect-timeout-ms", "connectMillis");
        Long read = firstLong(map, parent, "read", "read-timeout-ms", "readMillis");
        Long write = firstLong(map, parent, "write", "write-timeout-ms", "writeMillis");
        if (connect != null) {
            timeout.setConnectMillis(connect.longValue());
        }
        if (read != null) {
            timeout.setReadMillis(read.longValue());
        }
        if (write != null) {
            timeout.setWriteMillis(write.longValue());
        }
        return timeout;
    }

    /**
     * 解析重试配置，支持嵌套与实例层级两种写法。
     *
     * <p>子映射键为 {@code max-attempts}/{@code maxAttempts} 与
     * {@code backoff-millis}/{@code backoffMillis}；实例层级的同名键作为回退。
     * 未配置的项保留 {@link RetryConfig} 的默认值（2 次尝试、200 毫秒间隔）。
     * 注意这里只做数值转换，取值范围（如 {@code max-attempts >= 1}）由驱动校验。</p>
     *
     * @param nested {@code retry} 子映射，可为 {@code null}
     * @param parent 实例层级的映射，用于兼容扁平写法，不能为 {@code null}
     * @return 重试配置，非 {@code null}
     * @throws TsdbConfigurationException 数值无法解析时抛出
     */
    private static RetryConfig retry(Object nested, Map<String, Object> parent) {
        RetryConfig retry = new RetryConfig();
        Map<String, Object> map = asMap(nested);
        Integer attempts = firstInt(map, parent, "max-attempts", "maxAttempts");
        Long backoff = firstLong(map, parent, "backoff-millis", "backoffMillis");
        if (attempts != null) {
            retry.setMaxAttempts(attempts.intValue());
        }
        if (backoff != null) {
            retry.setBackoffMillis(backoff.longValue());
        }
        return retry;
    }

    /**
     * 解析 {@code security} 子映射。
     *
     * <p>为了兼容不同的书写习惯，每个开关都接受两种形态：</p>
     * <ul>
     *   <li>扁平的 {@code allow-write}/{@code allow-write} 等键；</li>
     *   <li>嵌套的 {@code audit.enabled} 与 {@code tls.verify-hostname}；
     *       仅当扁平键缺失时才读取嵌套值。</li>
     * </ul>
     * <p>未出现的开关保留 {@link SecurityConfig} 的安全默认值（只读、强制 TLS、校验主机名、开启审计）。</p>
     *
     * @param value {@code security} 键对应的原始对象，可为 {@code null}
     * @return 安全配置，非 {@code null}
     */
    private static SecurityConfig security(Object value) {
        SecurityConfig security = new SecurityConfig();
        Map<String, Object> map = asMap(value);
        if (map == null) {
            return security;
        }
        Boolean allowWrite = bool(map, "allow-write", "allowWrite");
        Boolean allowInsecure = bool(map, "allow-insecure-transport", "allowInsecureTransport");
        Boolean verifyHostname = bool(map, "verify-hostname", "verifyHostname");
        Boolean audit = bool(map, "audit-enabled", "auditEnabled");
        if (audit == null) {
            Map<String, Object> auditMap = asMap(map.get("audit"));
            if (auditMap != null) {
                audit = bool(auditMap, "enabled");
            }
        }
        Map<String, Object> tls = asMap(map.get("tls"));
        if (tls != null && verifyHostname == null) {
            verifyHostname = bool(tls, "verify-hostname", "verifyHostname");
        }
        if (allowWrite != null) {
            security.setAllowWrite(allowWrite.booleanValue());
        }
        if (allowInsecure != null) {
            security.setAllowInsecureTransport(allowInsecure.booleanValue());
        }
        if (verifyHostname != null) {
            security.setVerifyHostname(verifyHostname.booleanValue());
        }
        if (audit != null) {
            security.setAuditEnabled(audit.booleanValue());
        }
        return security;
    }

    /**
     * 先查子映射、再回退到父映射，返回第一个存在的数值。
     *
     * @param nested 子映射，可为 {@code null}
     * @param parent 父映射，不能为 {@code null}
     * @param keys   候选键名，按顺序匹配
     * @return 第一个命中的数值；两处都没有时返回 {@code null}
     * @throws TsdbConfigurationException 命中但无法解析为整数时抛出
     */
    private static Long firstLong(Map<String, Object> nested, Map<String, Object> parent, String... keys) {
        Long value = nested == null ? null : longValue(nested, keys);
        return value != null ? value : longValue(parent, keys);
    }

    /**
     * 先查子映射、再回退到父映射，返回第一个存在的整数。
     *
     * <p>内部按 {@code long} 解析后截断为 {@code int}：超出 int 范围的值不会报错，
     * 而是发生截断，因此不应把巨大数值用于超时/重试等配置。</p>
     *
     * @param nested 子映射，可为 {@code null}
     * @param parent 父映射，不能为 {@code null}
     * @param keys   候选键名，按顺序匹配
     * @return 第一个命中的整数；两处都没有时返回 {@code null}
     * @throws TsdbConfigurationException 命中但无法解析为整数时抛出
     */
    private static Integer firstInt(Map<String, Object> nested, Map<String, Object> parent, String... keys) {
        Integer value = nested == null ? null : intValue(nested, keys);
        return value != null ? value : intValue(parent, keys);
    }

    /**
     * 读取字符串型配置项，并做 trim 与环境变量替换。
     *
     * <p>空串/仅空白的值统一按“未配置”处理，返回 {@code null}，这样驱动可以用
     * “是否为 null”判断必填项是否缺失；{@code null} 与空串不会得到不同语义。</p>
     *
     * @param map  待读取的映射，可为 {@code null}
     * @param keys 候选键名，按顺序返回第一个非 {@code null} 的值
     * @return 替换占位符并 trim 后的字符串；未配置或为空白时返回 {@code null}
     */
    private static String string(Map<String, Object> map, String... keys) {
        Object value = first(map, keys);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        return resolveEnv(text);
    }

    /**
     * 读取布尔型配置项。
     *
     * <p>若 YAML 已解析为 {@link Boolean} 则直接返回；否则按
     * {@link Boolean#valueOf(String)} 解析——只有忽略大小写的 {@code "true"} 为真，
     * {@code "false"}、{@code "yes"}、{@code "1"} 等都会得到 {@code false}。
     * 由于 SnakeYAML 按 YAML 1.1 解析，未加引号的 {@code yes}/{@code on} 会先变成
     * {@link Boolean}，行为与加引号时不同，配置时建议只写 {@code true}/{@code false}。</p>
     *
     * @param map  待读取的映射，可为 {@code null}
     * @param keys 候选键名，按顺序返回第一个非 {@code null} 的值
     * @return 布尔值；未配置时返回 {@code null}（与 {@code false} 区分，用于保留默认值）
     */
    private static Boolean bool(Map<String, Object> map, String... keys) {
        Object value = first(map, keys);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.valueOf(String.valueOf(value).trim());
    }

    /**
     * 读取长整型配置项。
     *
     * <p>YAML 中的整数（{@link Number}）直接取 {@code longValue()}，因此 {@code 5000}
     * 与 {@code "5000"} 等价；其他字符串先 trim 再按十进制解析，不接受
     * {@code 5s}、{@code 1_000} 这类带单位或分隔符的写法。解析失败会转换为
     * 配置异常，消息中带上原始值便于定位。</p>
     *
     * @param map  待读取的映射，可为 {@code null}
     * @param keys 候选键名，按顺序返回第一个非 {@code null} 的值
     * @return 长整数值；未配置时返回 {@code null}
     * @throws TsdbConfigurationException 值存在但不是合法十进制整数时抛出
     *                                    （消息为 {@code "invalid numeric value: <value>"}）
     */
    private static Long longValue(Map<String, Object> map, String... keys) {
        Object value = first(map, keys);
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return Long.valueOf(((Number) value).longValue());
        }
        try {
            return Long.valueOf(Long.parseLong(String.valueOf(value).trim()));
        } catch (NumberFormatException e) {
            throw new TsdbConfigurationException("invalid numeric value: " + value);
        }
    }

    /**
     * 读取整型配置项。
     *
     * <p>先按 {@link #longValue(Map, String...)} 解析为 {@code long}，再强转为
     * {@code int}；超出 {@code int} 范围时静默截断，不会报错。</p>
     *
     * @param map  待读取的映射，可为 {@code null}
     * @param keys 候选键名，按顺序返回第一个非 {@code null} 的值
     * @return 整数值；未配置时返回 {@code null}
     * @throws TsdbConfigurationException 值存在但不是合法十进制整数时抛出
     */
    private static Integer intValue(Map<String, Object> map, String... keys) {
        Long value = longValue(map, keys);
        return value == null ? null : Integer.valueOf(value.intValue());
    }

    /**
     * 按顺序返回第一个“键存在且值非 {@code null}”的值。
     *
     * <p>注意这里把显式写成 {@code null} 的键视为未配置，因此无法用 YAML 的
     * {@code key: null} 清空默认值。</p>
     *
     * @param map  待查找的映射，可为 {@code null}
     * @param keys 候选键名，区分大小写
     * @return 命中的值；全部未命中或 {@code map} 为 {@code null} 时返回 {@code null}
     */
    private static Object first(Map<String, Object> map, String... keys) {
        if (map == null) {
            return null;
        }
        for (String key : keys) {
            if (map.containsKey(key) && map.get(key) != null) {
                return map.get(key);
            }
        }
        return null;
    }

    /**
     * 解析字符串列表，兼容标量与列表两种写法。
     *
     * <p>可同时传入 {@code node-urls} 与 {@code nodeUrls} 两个候选值：先加入的在前，
     * 后加入的追加，不做去重；{@code null} 元素被跳过，元素会 trim 并做环境变量替换。
     * 标量值（如 {@code node-urls: http://a:9000}）会被当作只有一个元素的列表。</p>
     *
     * @param values 候选原始值，可为 {@code null}、标量或 {@link List}
     * @return 可变的结果列表，元素非 {@code null}，保持加入顺序；无有效值时为空列表
     */
    private static List<String> strings(Object... values) {
        List<String> result = new ArrayList<String>();
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            if (value instanceof List) {
                for (Object item : (List<?>) value) {
                    if (item != null) {
                        result.add(resolveEnv(String.valueOf(item).trim()));
                    }
                }
            } else {
                result.add(resolveEnv(String.valueOf(value).trim()));
            }
        }
        return result;
    }

    /**
     * 解析“字符串到字符串”的映射（如认证参数）。
     *
     * <p>值会做环境变量替换，{@code null} 值保留为 {@code null}（不会变成字符串
     * {@code "null"}）；非映射输入返回空 Map。</p>
     *
     * @param value 原始对象，通常为 {@link Map}，可为 {@code null}
     * @return 保持插入顺序的可变 Map，非 {@code null}
     */
    private static Map<String, String> stringMap(Object value) {
        Map<String, Object> map = asMap(value);
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (map != null) {
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                result.put(entry.getKey(), entry.getValue() == null ? null : resolveEnv(String.valueOf(entry.getValue())));
            }
        }
        return result;
    }

    /**
     * 解析驱动私有参数表，保留原始值类型。
     *
     * <p>与 {@link #stringMap(Object)} 不同，这里不把值转成字符串、也不做环境变量替换，
     * 以便 {@code intOption} 等读取数字/布尔参数；非映射输入返回空 Map。</p>
     *
     * @param value 原始对象，通常为 {@link Map}，可为 {@code null}
     * @return 可变的参数表（若输入是 Map 则直接返回同一引用），非 {@code null}
     */
    private static Map<String, Object> stringObjectMap(Object value) {
        Map<String, Object> map = asMap(value);
        return map == null ? new LinkedHashMap<String, Object>() : map;
    }

    /**
     * 把对象安全地视为 {@code Map<String, Object>}。
     *
     * <p>只做 {@code instanceof} 判断后的非受检强转：若 YAML 中存在非字符串键，
     * 强转在编译期被抑制、运行期也不会立即报错，实际取键时可能得到非 String 的
     * {@code ClassCastException}——因此实例集合单独用原始 entrySet 遍历规避该问题。</p>
     *
     * @param value 任意对象，可为 {@code null}
     * @return 同一引用的 Map；不是 Map 时返回 {@code null}
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    /**
     * 替换字符串中的环境变量占位符。
     *
     * <p>语法：<code>${NAME}</code> 或 <code>${NAME:default}</code>，一行里可以出现多个；
     * 冒号之后的全部内容都是默认值（允许再包含冒号）。查找顺序为
     * {@code System.getenv(NAME)} -&gt; {@code System.getProperty(NAME)} -&gt; 默认值；
     * 三者都没有时<b>原样保留</b> <code>${NAME}</code> 文本，而不是替换成空串，
     * 便于在错误信息里看出漏配的变量。缺少右花括号时，从该 <code>${</code> 起的剩余文本
     * 原样保留；不支持嵌套占位符，也不会对变量名做 trim。</p>
     *
     * @param value 原始字符串，可为 {@code null}
     * @return 替换后的字符串；入参为 {@code null} 或不含 <code>${</code> 时原样返回
     */
    private static String resolveEnv(String value) {
        if (value == null || !value.contains("${")) {
            return value;
        }
        StringBuilder out = new StringBuilder();
        int index = 0;
        while (index < value.length()) {
            int start = value.indexOf("${", index);
            if (start < 0) {
                out.append(value.substring(index));
                break;
            }
            out.append(value.substring(index, start));
            int end = value.indexOf('}', start + 2);
            if (end < 0) {
                out.append(value.substring(start));
                break;
            }
            String expression = value.substring(start + 2, end);
            String name = expression;
            String fallback = null;
            int colon = expression.indexOf(':');
            if (colon >= 0) {
                name = expression.substring(0, colon);
                fallback = expression.substring(colon + 1);
            }
            String resolved = System.getenv(name);
            if (resolved == null) {
                resolved = System.getProperty(name);
            }
            if (resolved == null) {
                resolved = fallback;
            }
            out.append(resolved == null ? value.substring(start, end + 1) : resolved);
            index = end + 1;
        }
        return out.toString();
    }
}
