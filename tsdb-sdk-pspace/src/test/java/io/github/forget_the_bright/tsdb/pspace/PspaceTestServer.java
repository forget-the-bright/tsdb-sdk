package io.github.forget_the_bright.tsdb.pspace;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.forget_the_bright.tsdb.common.config.SecurityConfig;
import io.github.forget_the_bright.tsdb.common.config.TsdbInstanceConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单测用的 pSpace 模拟服务：按路径分发，按 GBK 编解码，记录全部请求。
 *
 * <p>只服务于 {@code tsdb-sdk-pspace} 的单元测试；每个用例自行注册所需端点，
 * {@code /Refresh} 默认返回成功信封。</p>
 */
public final class PspaceTestServer implements AutoCloseable {

    /** 一条已收到的请求。 */
    public static final class Request {

        /** HTTP 方法。 */
        public final String method;

        /** 请求路径。 */
        public final String path;

        /** 已按测试字符集解码的查询参数。 */
        public final Map<String, String> query;

        /** 已按测试字符集解码的请求体。 */
        public final String body;

        Request(String method, String path, Map<String, String> query, String body) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.body = body;
        }

        /**
         * @param name 参数名
         * @return 参数值；不存在时 {@code null}
         */
        public String param(String name) {
            return query.get(name);
        }

        /**
         * 读取 JSON 请求体的顶层字段（pSpace 7.1 读接口要求 POST + JSON）。
         *
         * @param name 字段名
         * @return 字段值的文本形式，对象/数组返回 JSON 文本；不存在或非 JSON 时 {@code null}
         */
        public String bodyParam(String name) {
            if (body == null || body.trim().isEmpty()) {
                return null;
            }
            try {
                JSONObject json = JSON.parseObject(body);
                Object value = json.get(name);
                if (value == null) {
                    return null;
                }
                return value instanceof String ? (String) value : JSON.toJSONString(value);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /** 路径处理器。 */
    public interface Handler {

        /**
         * @param request 已解码的请求
         * @return 响应正文；返回 {@code null} 视为空正文
         */
        String handle(Request request);
    }

    private final HttpServer server;
    private final Map<String, Handler> handlers = new ConcurrentHashMap<String, Handler>();
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<Request>());
    private volatile Charset charset = Charset.forName("GBK");

    /**
     * 启动一个随机端口的本地服务。
     *
     * @throws IOException 端口绑定失败时
     */
    public PspaceTestServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::dispatch);
        server.start();
        on("/Refresh", request -> "{\"code\":0,\"mesg\":\"Success\"}");
    }

    /**
     * 注册或替换一个路径处理器。
     *
     * @param path    路径，如 {@code "/PointPage"}
     * @param handler 处理器
     * @return 本对象，便于链式调用
     */
    public PspaceTestServer on(String path, Handler handler) {
        handlers.put(path, handler);
        return this;
    }

    /**
     * 切换请求体解码与响应编码使用的字符集；默认 GBK。
     *
     * @param charset 字符集
     */
    public void charset(Charset charset) {
        this.charset = charset;
    }

    /**
     * @return 服务根地址，如 {@code http://127.0.0.1:12345}
     */
    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * @return 当前已记录请求的快照
     */
    public List<Request> requests() {
        synchronized (requests) {
            return new ArrayList<Request>(requests);
        }
    }

    /**
     * 清空已记录的请求。
     */
    public void clear() {
        requests.clear();
    }

    /**
     * @param path 路径
     * @return 该路径收到的请求次数
     */
    public long hits(String path) {
        long count = 0;
        for (Request request : requests()) {
            if (path.equals(request.path)) {
                count++;
            }
        }
        return count;
    }

    /**
     * @return 默认配置（只读、自动刷新开启、GBK）
     */
    public PspaceConfig config() {
        return config(Collections.<String, Object>emptyMap(), false);
    }

    /**
     * 构造驱动配置。
     *
     * @param options    扁平 options 键值，如 {@code pspace.page-size}
     * @param allowWrite 是否打开写入门禁
     * @return 已校验的配置
     */
    public PspaceConfig config(Map<String, Object> options, boolean allowWrite) {
        TsdbInstanceConfig instance = new TsdbInstanceConfig();
        instance.setBaseUrl(url());
        instance.getOptions().putAll(options);
        SecurityConfig security = new SecurityConfig();
        security.setAllowInsecureTransport(true);
        security.setAllowWrite(allowWrite);
        return new PspaceConfig("pspace-test", instance, security);
    }

    /**
     * @param options 扁平 options
     * @return 使用该选项构造的驱动配置（只读）
     */
    public PspaceConfig config(Map<String, Object> options) {
        return config(options, false);
    }

    /**
     * @return 指向本服务的客户端（只读）
     */
    public PspaceClient client() {
        return new PspaceClient("pspace-test", config());
    }

    /**
     * @param options 扁平 options
     * @param allowWrite 写入门禁
     * @return 指向本服务的客户端
     */
    public PspaceClient client(Map<String, Object> options, boolean allowWrite) {
        return new PspaceClient("pspace-test", config(options, allowWrite));
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        Request request = new Request(exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                parseQuery(exchange.getRequestURI().getRawQuery()),
                readBody(exchange));
        requests.add(request);
        Handler handler = handlers.get(request.path);
        int status = 200;
        String body;
        if (handler == null) {
            status = 404;
            body = "{\"code\":-1,\"mesg\":\"no handler for " + request.path + "\"}";
        } else {
            try {
                body = handler.handle(request);
            } catch (RuntimeException e) {
                status = 500;
                body = "{\"code\":-1,\"mesg\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}";
            }
        }
        byte[] bytes = (body == null ? "" : body).getBytes(charset);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        if (bytes.length == 0) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    private Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return result;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            try {
                String key = URLDecoder.decode(pair.substring(0, eq), charset.name());
                String value = URLDecoder.decode(pair.substring(eq + 1), charset.name());
                result.put(key, value);
            } catch (UnsupportedEncodingException e) {
                throw new IllegalStateException(e);
            }
        }
        return result;
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), charset);
        }
    }
}