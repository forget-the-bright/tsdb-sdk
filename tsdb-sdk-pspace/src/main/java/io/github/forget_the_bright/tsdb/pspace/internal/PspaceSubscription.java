package io.github.forget_the_bright.tsdb.pspace.internal;

import io.github.forget_the_bright.tsdb.common.exception.TsdbConnectionException;
import io.github.forget_the_bright.tsdb.pspace.PspaceConfig;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * pSpace 8081 WebSocket 实时订阅的原生门面。
 *
 * <p>协议事实：连接 pSpace Web 服务的 8081 端口，发送与 {@code /RealData}
 * 同构的订阅请求文本，服务端持续推送 {@code {count,values[]}} 消息。本类只做
 * “连接、发送请求、逐条回调、关闭”，不解析消息内容，也不进入公共
 * {@code TsdbClient} 能力集（公共 API 无订阅能力）。</p>
 *
 * <p>线程安全：消息回调在 JDK 的 WebSocket 线程上执行，调用方需自行保证
 * 回调内部线程安全。实例用完必须 {@link #close()}。</p>
 *
 * <p><b>非公共 API</b>：随内部重构变化，宿主应用请勿直接依赖。</p>
 *
 * @author wanghao
 * @since 0.1.2
 */
public final class PspaceSubscription implements AutoCloseable {

    /** pSpace 订阅端口。 */
    private static final int SUBSCRIPTION_PORT = 8081;

    private final WebSocket socket;

    private PspaceSubscription(WebSocket socket) {
        this.socket = socket;
    }

    /**
     * 建立订阅并发送请求。
     *
     * @param config      驱动配置，不能为 {@code null}
     * @param requestJson 与 {@code /RealData} 同构的订阅请求，不能为 {@code null}
     * @param onMessage   每完成一条文本消息回调一次，不能为 {@code null}
     * @return 非 {@code null} 的订阅句柄
     * @throws TsdbConnectionException 连接或发送失败时
     */
    public static PspaceSubscription open(PspaceConfig config, String requestJson,
                                          Consumer<String> onMessage) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(requestJson, "requestJson");
        Objects.requireNonNull(onMessage, "onMessage");
        URI uri = subscriptionUri(config.getBaseUrl());
        StringBuilder buffer = new StringBuilder();
        WebSocket.Listener listener = new WebSocket.Listener() {
            /** 连接建立后先申请一条消息的接收额度。 */
            @Override
            public void onOpen(WebSocket webSocket) {
                webSocket.request(1);
            }

            /**
             * 累积分片文本，{@code last=true} 时把整条消息交给回调；
             * 无论成功与否都继续申请接收下一条。
             */
            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                buffer.append(data);
                if (last) {
                    String message = buffer.toString();
                    buffer.setLength(0);
                    onMessage.accept(message);
                }
                webSocket.request(1);
                return null;
            }

            /** 连接异常时中止 socket，避免半开连接泄漏。 */
            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                webSocket.abort();
            }
        };
        try {
            WebSocket socket = HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .buildAsync(uri, listener)
                    .join();
            socket.sendText(requestJson, true).join();
            return new PspaceSubscription(socket);
        } catch (RuntimeException e) {
            throw new TsdbConnectionException(
                    "pSpace subscription failed: " + uri + ": " + e.getMessage(), e);
        }
    }

    /**
     * 把 {@code http(s)://host} 转成 {@code ws(s)://host:8081/}。
     *
     * @param baseUrl 根地址，不能为 {@code null}
     * @return 非 {@code null} 的 WebSocket URI
     * @throws TsdbConnectionException 根地址无法解析时
     */
    static URI subscriptionUri(String baseUrl) {
        try {
            URI base = new URI(baseUrl);
            String scheme = "https".equalsIgnoreCase(base.getScheme()) ? "wss" : "ws";
            return new URI(scheme, null, base.getHost(), SUBSCRIPTION_PORT, "/", null, null);
        } catch (URISyntaxException e) {
            throw new TsdbConnectionException("invalid pSpace base-url for subscription: " + baseUrl, e);
        }
    }

    /**
     * 关闭订阅。
     *
     * <p>幂等；关闭失败不影响调用方（底层 socket 会被 abort）。</p>
     */
    @Override
    public void close() {
        try {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
        } catch (RuntimeException e) {
            socket.abort();
        }
    }
}