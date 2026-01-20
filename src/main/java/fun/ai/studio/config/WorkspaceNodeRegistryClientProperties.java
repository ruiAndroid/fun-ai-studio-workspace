package fun.ai.studio.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * workspace-node -> API 心跳上报配置（workspace 服务侧）。
 *
 * <pre>
 * funai.workspace-node-registry-client.enabled=true
 * funai.workspace-node-registry-client.api-base-url=http://<api-host>:8080
 * funai.workspace-node-registry-client.node-name=ws-node-01
 * funai.workspace-node-registry-client.nginx-base-url=http://<this-node-nginx>
 * funai.workspace-node-registry-client.node-api-base-url=http://<this-node>:7001
 * funai.workspace-node-registry-client.token=CHANGE_ME_STRONG_SECRET
 * funai.workspace-node-registry-client.interval-seconds=15
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "funai.workspace-node-registry-client")
public class WorkspaceNodeRegistryClientProperties {
    private boolean enabled = false;
    private String apiBaseUrl;
    private String nodeName;
    private String nginxBaseUrl;
    private String nodeApiBaseUrl;
    private String token;
    private long intervalSeconds = 15;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    public String getNodeName() {
        return nodeName;
    }

    public void setNodeName(String nodeName) {
        this.nodeName = nodeName;
    }

    public String getNginxBaseUrl() {
        return nginxBaseUrl;
    }

    public void setNginxBaseUrl(String nginxBaseUrl) {
        this.nginxBaseUrl = nginxBaseUrl;
    }

    public String getNodeApiBaseUrl() {
        return nodeApiBaseUrl;
    }

    public void setNodeApiBaseUrl(String nodeApiBaseUrl) {
        this.nodeApiBaseUrl = nodeApiBaseUrl;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public long getIntervalSeconds() {
        return intervalSeconds;
    }

    public void setIntervalSeconds(long intervalSeconds) {
        this.intervalSeconds = intervalSeconds;
    }
}


