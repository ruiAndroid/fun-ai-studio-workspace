package fun.ai.studio.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.ai.studio.config.WorkspaceNodeRegistryClientProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * workspace-node 定时向 API 上报心跳（用于节点注册表与健康判断）。
 */
@Component
public class WorkspaceNodeHeartbeatReporter {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceNodeHeartbeatReporter.class);

    private final WorkspaceNodeRegistryClientProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public WorkspaceNodeHeartbeatReporter(WorkspaceNodeRegistryClientProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @Scheduled(fixedDelayString = "#{T(java.lang.Math).max(5000, @workspaceNodeRegistryClientProperties.intervalSeconds * 1000)}")
    public void report() {
        if (props == null || !props.isEnabled()) return;
        if (!StringUtils.hasText(props.getApiBaseUrl())
                || !StringUtils.hasText(props.getNodeName())
                || !StringUtils.hasText(props.getNginxBaseUrl())
                || !StringUtils.hasText(props.getNodeApiBaseUrl())
                || !StringUtils.hasText(props.getToken())) {
            log.debug("heartbeat reporter skipped: missing config");
            return;
        }

        String url = joinUrl(props.getApiBaseUrl(), "/api/fun-ai/admin/workspace-nodes/heartbeat");

        Map<String, Object> body = new HashMap<>();
        body.put("nodeName", props.getNodeName());
        body.put("nginxBaseUrl", props.getNginxBaseUrl());
        body.put("apiBaseUrl", props.getNodeApiBaseUrl());
        body.put("version", "workspace-node");

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(body);
        } catch (Exception e) {
            log.warn("heartbeat encode failed: {}", e.getMessage());
            return;
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .header("X-WS-Node-Token", props.getToken())
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();

        try {
            HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            int code = resp.statusCode();
            if (code / 100 != 2) {
                log.warn("heartbeat failed: http={}, url={}", code, url);
            } else {
                log.debug("heartbeat ok: url={}", url);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("heartbeat request failed: {}", e.getMessage());
        }
    }

    private String joinUrl(String baseUrl, String path) {
        String b = baseUrl.trim();
        if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        String p = (path == null ? "" : path.trim());
        if (!p.startsWith("/")) p = "/" + p;
        return b + p;
    }
}


