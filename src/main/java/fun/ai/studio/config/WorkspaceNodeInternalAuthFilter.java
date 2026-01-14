package fun.ai.studio.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * workspace-node 内部鉴权：
 * - 允许：本机 loopback + 配置允许 IP（通常是小机公网 IP）
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
        ContentCachingRequestWrapper req = (request instanceof ContentCachingRequestWrapper)
                ? (ContentCachingRequestWrapper) request
                : new ContentCachingRequestWrapper(request);

        // 1) 先做来源 IP 限制（强烈建议配合安全组一起做）
        String remote = req.getRemoteAddr();
        if (!isAllowedIp(remote, props == null ? List.of() : props.allowedIpList())) {
            deny(response, 403, "forbidden");
            return;
        }

        // 2) 签名校验（可选）
        if (props != null && props.isRequireSignature()) {
            if (!verifySignature(req, props)) {
                deny(response, 401, "unauthorized");
                return;
            }
        }

        filterChain.doFilter(req, response);
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

    private boolean verifySignature(ContentCachingRequestWrapper req, WorkspaceNodeInternalAuthProperties p) {
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

            // 读取 body（ContentCachingRequestWrapper 会缓存）
            byte[] body = req.getContentAsByteArray();
            if (body == null || body.length == 0) {
                // 触发一次读取，保证缓存可用（对 GET 等无 body 的请求无影响）
                StreamUtils.copyToByteArray(req.getInputStream());
                body = req.getContentAsByteArray();
            }
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
}


