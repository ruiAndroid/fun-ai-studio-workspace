package fun.ai.studio.entity.response;

/**
 * /api/fun-ai/workspace/git/status 响应
 */
public class WorkspaceGitStatusResponse {
    /**
     * 是否是 git 仓库（存在 .git 目录）
     */
    private boolean gitRepo;
    /**
     * 是否有未提交改动（dirty）
     */
    private boolean dirty;
    /**
     * 当前分支名
     */
    private String branch;
    /**
     * 当前 commit SHA（short）
     */
    private String commitShort;
    /**
     * 远端 URL（如果有）
     */
    private String remoteUrl;
    /**
     * 提示信息
     */
    private String message;

    public boolean isGitRepo() {
        return gitRepo;
    }

    public void setGitRepo(boolean gitRepo) {
        this.gitRepo = gitRepo;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public String getCommitShort() {
        return commitShort;
    }

    public void setCommitShort(String commitShort) {
        this.commitShort = commitShort;
    }

    public String getRemoteUrl() {
        return remoteUrl;
    }

    public void setRemoteUrl(String remoteUrl) {
        this.remoteUrl = remoteUrl;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}

