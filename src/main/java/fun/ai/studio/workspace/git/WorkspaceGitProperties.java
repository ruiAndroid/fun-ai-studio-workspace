package fun.ai.studio.workspace.git;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Workspace Git 集成配置（workspace-bot 密钥路径、远端主机等）。
 *
 * <pre>
 * funai.workspace.git.enabled=true
 * funai.workspace.git.ssh-key-path=/opt/fun-ai-studio/keys/gitea/workspace_bot_ed25519
 * funai.workspace.git.known-hosts-path=/opt/fun-ai-studio/keys/gitea/known_hosts
 * funai.workspace.git.ssh-user=git
 * funai.workspace.git.ssh-host=172.21.138.103
 * funai.workspace.git.ssh-port=2222
 * funai.workspace.git.repo-owner=funai
 * funai.workspace.git.repo-name-template=u{userId}-app{appId}
 * funai.workspace.git.default-branch=main
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "funai.workspace.git")
public class WorkspaceGitProperties {
    private boolean enabled = false;
    private String sshKeyPath = "/opt/fun-ai-studio/keys/gitea/workspace_bot_ed25519";
    private String knownHostsPath = "/opt/fun-ai-studio/keys/gitea/known_hosts";
    private String sshUser = "git";
    private String sshHost = "172.21.138.103";
    private int sshPort = 2222;
    private String repoOwner = "funai";
    private String repoNameTemplate = "u{userId}-app{appId}";
    private String defaultBranch = "main";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSshKeyPath() {
        return sshKeyPath;
    }

    public void setSshKeyPath(String sshKeyPath) {
        this.sshKeyPath = sshKeyPath;
    }

    public String getKnownHostsPath() {
        return knownHostsPath;
    }

    public void setKnownHostsPath(String knownHostsPath) {
        this.knownHostsPath = knownHostsPath;
    }

    public String getSshUser() {
        return sshUser;
    }

    public void setSshUser(String sshUser) {
        this.sshUser = sshUser;
    }

    public String getSshHost() {
        return sshHost;
    }

    public void setSshHost(String sshHost) {
        this.sshHost = sshHost;
    }

    public int getSshPort() {
        return sshPort;
    }

    public void setSshPort(int sshPort) {
        this.sshPort = sshPort;
    }

    public String getRepoOwner() {
        return repoOwner;
    }

    public void setRepoOwner(String repoOwner) {
        this.repoOwner = repoOwner;
    }

    public String getRepoNameTemplate() {
        return repoNameTemplate;
    }

    public void setRepoNameTemplate(String repoNameTemplate) {
        this.repoNameTemplate = repoNameTemplate;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public void setDefaultBranch(String defaultBranch) {
        this.defaultBranch = defaultBranch;
    }

    /**
     * 构建 SSH 克隆 URL：ssh://git@host:port/owner/repo.git
     */
    public String buildRepoSshUrl(Long userId, Long appId) {
        String repo = repoNameTemplate
                .replace("{userId}", String.valueOf(userId))
                .replace("{appId}", String.valueOf(appId));
        return "ssh://" + sshUser + "@" + sshHost + ":" + sshPort + "/" + repoOwner + "/" + repo + ".git";
    }
}
