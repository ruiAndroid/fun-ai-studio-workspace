package fun.ai.studio.entity.response;

/**
 * /api/fun-ai/workspace/git/reset 响应
 */
public class WorkspaceGitRestoreResponse {
    /**
     * 操作结果：SUCCESS / PUSH_FAILED / FAILED
     */
    private String result;
    /**
     * 恢复后的新 commit SHA（short）
     */
    private String commitShort;
    /**
     * 恢复到的目标 commit SHA（short）
     */
    private String targetCommit;
    /**
     * 当前分支
     */
    private String branch;
    /**
     * 提示信息
     */
    private String message;

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getCommitShort() {
        return commitShort;
    }

    public void setCommitShort(String commitShort) {
        this.commitShort = commitShort;
    }

    public String getTargetCommit() {
        return targetCommit;
    }

    public void setTargetCommit(String targetCommit) {
        this.targetCommit = targetCommit;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}

