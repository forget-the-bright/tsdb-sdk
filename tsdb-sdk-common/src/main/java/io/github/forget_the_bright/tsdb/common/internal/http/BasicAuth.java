package io.github.forget_the_bright.tsdb.common.internal.http;

import cn.hutool.core.codec.Base64;

import java.nio.charset.StandardCharsets;

/**
 * HTTP Basic 认证头生成器。
 *
 * <p>按 RFC 7617 将 {@code user:password} 以 UTF-8 编码后做 Base64。{@code null}
 * 参数按空串处理，保证结果永不为 {@code null}。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class BasicAuth {

    private BasicAuth() {
    }

    /**
     * 生成可用于 {@code Authorization} 请求头的值。
     *
     * @param user     用户名或客户端标识，可为 {@code null}
     * @param password 口令或客户端密钥，可为 {@code null}
     * @return 形如 {@code "Basic xxxxx"} 的字符串
     */
    public static String header(String user, String password) {
        String raw = (user == null ? "" : user) + ":" + (password == null ? "" : password);
        return "Basic " + Base64.encode(raw.getBytes(StandardCharsets.UTF_8));
    }
}
