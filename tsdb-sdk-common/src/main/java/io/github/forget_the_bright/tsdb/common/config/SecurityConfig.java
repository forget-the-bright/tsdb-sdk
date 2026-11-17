package io.github.forget_the_bright.tsdb.common.config;

/**
 * 全局安全开关（可变 POJO），对所有已配置实例统一生效。
 *
 * <p>默认值遵循“安全优先”：只读、校验证书主机名、拒绝明文传输、开启审计。
 * 这些开关由驱动在创建客户端时读取并固化，作用范围如下：</p>
 * <ul>
 *   <li>{@link #isAllowWrite()}：默认 {@code false}，此时驱动拒绝任何写会话，
 *       写入调用会抛出“写操作已禁用”类异常；</li>
 *   <li>{@link #isAllowInsecureTransport()}：默认 {@code false}，使用 {@code http://}
 *       等明文地址时启动即失败，需要显式置为 {@code true} 才放行；</li>
 *   <li>{@link #isVerifyHostname()}：默认 {@code true}，关闭后 TLS 握手不再校验
 *       服务端证书中的主机名（仍会校验证书链），仅用于自签名/内网证书场景；</li>
 *   <li>{@link #isAuditEnabled()}：默认 {@code true}，驱动可据此记录写操作与敏感访问的审计日志。</li>
 * </ul>
 *
 * <p>线程安全：本类不是线程安全的，应在启动阶段完成配置，运行期不要修改；
 * 运行期变更不会传播到已经创建的客户端。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public class SecurityConfig {

    /** 是否允许写入，默认 {@code false}（只读模式）。 */
    private boolean allowWrite;
    /** 是否允许明文/不安全的传输通道，默认 {@code false}（强制 TLS）。 */
    private boolean allowInsecureTransport;
    /** 是否记录审计日志，默认 {@code true}。 */
    private boolean auditEnabled = true;
    /** TLS 握手时是否校验服务端证书主机名，默认 {@code true}。 */
    private boolean verifyHostname = true;

    /**
     * 返回是否允许写入。
     *
     * @return {@code true} 表示允许写会话；默认 {@code false}，即 SDK 处于只读模式
     */
    public boolean isAllowWrite() {
        return allowWrite;
    }

    /**
     * 设置是否允许写入。
     *
     * <p>开启后仍需目标时序库账号本身具备写权限；该开关只是 SDK 侧的二次保护，
     * 不能替代服务端的权限控制。</p>
     *
     * @param allowWrite {@code true} 允许写入，默认 {@code false}
     */
    public void setAllowWrite(boolean allowWrite) {
        this.allowWrite = allowWrite;
    }

    /**
     * 返回是否允许明文/不安全传输。
     *
     * @return {@code true} 表示允许 {@code http://} 等明文通道；默认 {@code false}
     */
    public boolean isAllowInsecureTransport() {
        return allowInsecureTransport;
    }

    /**
     * 设置是否允许明文/不安全传输。
     *
     * <p>仅在连接内网测试环境或无法启用 TLS 的老旧现场时开启；开启后凭据将以明文
     * 在网络上传输。</p>
     *
     * @param allowInsecureTransport {@code true} 放行明文通道，默认 {@code false}
     */
    public void setAllowInsecureTransport(boolean allowInsecureTransport) {
        this.allowInsecureTransport = allowInsecureTransport;
    }

    /**
     * 返回是否开启审计日志。
     *
     * @return {@code true} 表示驱动应记录审计日志；默认 {@code true}
     */
    public boolean isAuditEnabled() {
        return auditEnabled;
    }

    /**
     * 设置是否开启审计日志。
     *
     * @param auditEnabled {@code true} 开启，默认 {@code true}
     */
    public void setAuditEnabled(boolean auditEnabled) {
        this.auditEnabled = auditEnabled;
    }

    /**
     * 返回 TLS 是否校验主机名。
     *
     * @return {@code true} 表示校验服务端证书主机名；默认 {@code true}
     */
    public boolean isVerifyHostname() {
        return verifyHostname;
    }

    /**
     * 设置 TLS 是否校验主机名。
     *
     * <p>关闭后证书链仍会校验，但证书中的 CN/SAN 不再与目标地址比对，
     * 存在中间人攻击风险，只应在证书与地址不匹配的内网环境中临时使用。</p>
     *
     * @param verifyHostname {@code true} 校验主机名，默认 {@code true}
     */
    public void setVerifyHostname(boolean verifyHostname) {
        this.verifyHostname = verifyHostname;
    }
}
