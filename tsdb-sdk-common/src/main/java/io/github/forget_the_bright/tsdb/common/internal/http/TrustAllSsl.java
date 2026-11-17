package io.github.forget_the_bright.tsdb.common.internal.http;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

/**
 * 显式开启时才使用的 trust-all TLS 支持。
 *
 * <p>仅用于无法导入现场根证书的自签名内网联调。默认配置不得调用本类；
 * 开启后 HTTPS 的证书链校验被完全关闭，存在中间人风险。</p>
 *
 * <p>SSL 工厂在类加载时创建一次并复用，线程安全。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class TrustAllSsl {

    private static final TrustManager[] TRUST_ALL = new TrustManager[]{
            new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    // 本客户端从不作为服务端，无需校验客户端证书。
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    // 由配置显式要求，仅用于自签名内网部署。
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }
    };

    private static final SSLSocketFactory FACTORY = buildFactory();

    private TrustAllSsl() {
    }

    /**
     * 返回信任全部证书的 SSL 工厂。
     *
     * @return 可复用的非 {@code null} 工厂
     */
    public static SSLSocketFactory factory() {
        return FACTORY;
    }

    /**
     * 返回恒通过的主机名校验器。
     *
     * @return 非 {@code null} 的校验器
     */
    public static HostnameVerifier trustAllHostnames() {
        return (hostname, session) -> true;
    }

    private static SSLSocketFactory buildFactory() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, TRUST_ALL, new SecureRandom());
            return context.getSocketFactory();
        } catch (Exception e) {
            throw new IllegalStateException("cannot initialize trust-all SSL context", e);
        }
    }
}
