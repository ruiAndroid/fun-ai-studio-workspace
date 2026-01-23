package fun.ai.studio.entity.response;

/**
 * /api/fun-ai/workspace/git/revert 响应
 */
public class WorkspaceGitRevertResponse {
    /**
     * 操作结果：SUCCESS / CONFLICT / PUSH_FAILED / FAILED
     */
    private String result;
    /**
     * revert 后的新 commit SHA（short）
     */
    private String commitShort;
    /**
     * 被 revert 的 commit SHA（short）
     */
    private String revertedCommit;
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

    public String getRevertedCommit() {
        return revertedCommit;
    }

    public void setRevertedCommit(String revertedCommit) {
        this.revertedCommit = revertedCommit;
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

