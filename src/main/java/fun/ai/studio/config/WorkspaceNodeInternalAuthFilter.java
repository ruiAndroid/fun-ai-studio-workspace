package fun.ai.studio.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * workspace-node 内部鉴权：
 * - 允许：本机 loopback + 配置允许 IP（通常是 API 服务器（小机）公网 IP）
 * - 可选：签名校验（HMAC-SHA256）与防重放（nonce）
 */
public class WorkspaceNodeInternalAuthFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceNodeInternalAuthFilter.class);

    private static final String HDR_SIG = "X-WS-Signature";
    private static final String HDR_TS = "X-WS-Timestamp";
    private static final String HDR_NONCE = "X-WS-Nonce";

    private final WorkspaceNodeInternalAuthProperties props;
    private final Map<String, Long> nonceSeenAtSec = new ConcurrentHashMap<>();

    public WorkspaceNodeInternalAuthFilter(WorkspaceNodeInternalAuthProperties props) {
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (request == null) return true;
        String uri = request.getRequestURI();
        return uri == null || !uri.startsWith("/api/fun-ai/workspace/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // 1) 先做来源 IP 限制（强烈建议配合安全组一起做）
        String remote = request.getRemoteAddr();
        if (!isAllowedIp(remote, props == null ? List.of() : props.allowedIpList())) {
            deny(response, 403, "forbidden");
            return;
        }

        // 2) 签名校验（可选）
        if (props != null && props.isRequireSignature()) {
            // 重要：multipart 上传如果在 filter 里读取 InputStream，会导致后续 MultipartResolver 读不到文件 part，报
            // "Required part 'file' is not present."
            // 对这类请求，建议依赖安全组 + IP allowlist（仍然很强），跳过 body 签名校验。
            if (!shouldSkipSignatureFor(request)) {
                byte[] body = StreamUtils.copyToByteArray(request.getInputStream());
                if (!verifySignature(request, body, props)) {
                    deny(response, 401, "unauthorized");
                    return;
                }
                HttpServletRequest wrapped = new CachedBodyRequestWrapper(request, body);
                filterChain.doFilter(wrapped, response);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean shouldSkipSignatureFor(HttpServletRequest req) {
        if (req == null) return true;
        String ct = req.getContentType();
        if (ct != null && ct.toLowerCase(Locale.ROOT).startsWith("multipart/")) return true;
        String uri = req.getRequestURI();
        // 诊断接口：仅用于运维排障（仍受 IP allowlist 约束），跳过签名便于直接 curl。
        if (uri != null && uri.startsWith("/api/fun-ai/workspace/internal/activity")) {
            return true;
        }
        // Nginx auth_request 子请求：通常只会从本机 127.0.0.1 访问 workspace-node，且 Nginx 不方便附加 HMAC 头。
        // 这些接口本身会再校验 X-WS-Token（nginxAuthToken），并且外层还有安全组 + 127.0.0.1/IP allowlist，安全性足够。
        if (uri != null && uri.startsWith("/api/fun-ai/workspace/internal/nginx/")) {
            return true;
        }
        // 文件上传：multipart
        if (uri != null && (uri.contains("/api/fun-ai/workspace/files/upload-zip") || uri.contains("/api/fun-ai/workspace/files/upload-file"))) {
            return true;
        }
        return false;
    }

    private boolean isAllowedIp(String remoteAddr, List<String> allow) {
        if (remoteAddr == null || remoteAddr.isBlank()) return false;
        // loopback 永远允许（本机 Nginx auth_request 会走 127.0.0.1）
        if ("127.0.0.1".equals(remoteAddr) || "::1".equals(remoteAddr) || "0:0:0:0:0:0:0:1".equals(remoteAddr)) {
            return true;
        }
        if (allow == null || allow.isEmpty()) return false;
        return allow.contains(remoteAddr);
    }

    private boolean verifySignature(HttpServletRequest req, byte[] body, WorkspaceNodeInternalAuthProperties p) {
        try {
            String secret = p.getSharedSecret();
            if (secret == null || secret.isBlank() || "CHANGE_ME_STRONG_SECRET".equals(secret)) {
                log.warn("workspace-node requireSignature=true but sharedSecret is empty/default");
                return false;
            }

            String tsStr = req.getHeader(HDR_TS);
            String nonce = req.getHeader(HDR_NONCE);
            String sig = req.getHeader(HDR_SIG);
            if (tsStr == null || nonce == null || sig == null) return false;

            long ts = Long.parseLong(tsStr.trim());
            long now = Instant.now().getEpochSecond();
            long skew = Math.abs(now - ts);
            if (skew > Math.max(5, p.getMaxSkewSeconds())) return false;

            // 防重放：nonce 在 TTL 内只能用一次
            cleanupNonce(now, p.getNonceTtlSeconds());
            Long prev = nonceSeenAtSec.putIfAbsent(nonce, now);
            if (prev != null) return false;

            String bodySha256 = sha256Hex(body == null ? new byte[0] : body);

            String method = req.getMethod() == null ? "" : req.getMethod();
            String path = req.getRequestURI() == null ? "" : req.getRequestURI();
            String query = req.getQueryString() == null ? "" : req.getQueryString();

            String canonical = method + "\n" + path + "\n" + query + "\n" + bodySha256 + "\n" + ts + "\n" + nonce;
            String expect = hmacSha256Base64(secret, canonical);

            return constantTimeEquals(expect, sig.trim());
        } catch (Exception e) {
            log.warn("verify signature failed: {}", e.getMessage());
            return false;
        }
    }

    private void cleanupNonce(long nowSec, long ttlSec) {
        long ttl = Math.max(10, ttlSec);
        // 简单清理：当 map 太大时做一次全量扫描（起步够用）
        if (nonceSeenAtSec.size() < 5000) return;
        for (var it = nonceSeenAtSec.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (e == null || e.getValue() == null) {
                it.remove();
                continue;
            }
            if (nowSec - e.getValue() > ttl) {
                it.remove();
            }
        }
    }

    private String hmacSha256Base64(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] out = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(out);
    }

    private String sha256Hex(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] dig = md.digest(bytes);
        StringBuilder sb = new StringBuilder(dig.length * 2);
        for (byte b : dig) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        if (x.length != y.length) return false;
        int r = 0;
        for (int i = 0; i < x.length; i++) r |= (x[i] ^ y[i]);
        return r == 0;
    }

    private void deny(HttpServletResponse resp, int code, String msg) throws IOException {
        resp.setStatus(code);
        resp.setContentType(MediaType.TEXT_PLAIN_VALUE);
        resp.getWriter().write(msg);
    }

    /**
     * 让 request body 可重复读取（给 Spring MVC / MultipartResolver / @RequestBody 使用）。
     *
     * <p>注意：这里只用于非 multipart 的签名校验场景（multipart 直接跳过签名以避免大文件读入内存）。</p>
     */
    private static final class CachedBodyRequestWrapper extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequestWrapper(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = (body == null) ? new byte[0] : body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public boolean isFinished() {
                    return in.available() <= 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    // not async
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}


