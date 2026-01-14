package fun.ai.studio.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * workspace-node 内部鉴权配置：
 * - 仅允许来自指定来源 IP（以及本机 loopback）的请求
 * - 可选开启签名校验（HMAC）
 */
@Component
@ConfigurationProperties(prefix = "workspace-node.internal")
public class WorkspaceNodeInternalAuthProperties {
    /**
     * 允许的来源 IP（单个值或逗号分隔列表）。
     * 说明：loopback(127.0.0.1/::1) 永远允许（供本机 Nginx auth_request 使用）。
     */
    private String allowedSourceIp;

    /**
     * HMAC shared secret（requireSignature=true 时启用）
     */
    private String sharedSecret;

    /**
     * 是否强制签名校验。起步建议 false（仅靠安全组+IP allowlist）。
     */
    private boolean requireSignature = false;

    /**
     * 签名允许的最大时间偏移（秒）
     */
    private long maxSkewSeconds = 60;

    /**
     * nonce 缓存的保留时间（秒）
     */
    private long nonceTtlSeconds = 120;

    public String getAllowedSourceIp() {
        return allowedSourceIp;
    }

    public void setAllowedSourceIp(String allowedSourceIp) {
        this.allowedSourceIp = allowedSourceIp;
    }

    public String getSharedSecret() {
        return sharedSecret;
    }

    public void setSharedSecret(String sharedSecret) {
        this.sharedSecret = sharedSecret;
    }

    public boolean isRequireSignature() {
        return requireSignature;
    }

    public void setRequireSignature(boolean requireSignature) {
        this.requireSignature = requireSignature;
    }

    public long getMaxSkewSeconds() {
        return maxSkewSeconds;
    }

    public void setMaxSkewSeconds(long maxSkewSeconds) {
        this.maxSkewSeconds = maxSkewSeconds;
    }

    public long getNonceTtlSeconds() {
        return nonceTtlSeconds;
    }

    public void setNonceTtlSeconds(long nonceTtlSeconds) {
        this.nonceTtlSeconds = nonceTtlSeconds;
    }

    /**
     * 解析允许 IP 列表（不包含 loopback；loopback 在 filter 内固定允许）。
     */
    public List<String> allowedIpList() {
        List<String> out = new ArrayList<>();
        if (allowedSourceIp == null) return out;
        String s = allowedSourceIp.trim();
        if (s.isEmpty()) return out;
        for (String part : s.split(",")) {
            String ip = part == null ? "" : part.trim();
            if (!ip.isEmpty()) out.add(ip);
        }
        return out;
    }
}


