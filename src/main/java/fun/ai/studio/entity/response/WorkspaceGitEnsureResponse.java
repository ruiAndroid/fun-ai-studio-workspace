package fun.ai.studio.entity.response;

/**
 * /api/fun-ai/workspace/git/ensure 响应
 */
public class WorkspaceGitEnsureResponse {
    /**
     * 操作结果类型：CLONED / PULLED / ALREADY_UP_TO_DATE / NEED_COMMIT / NEED_CONFIRM / FAILED
     */
    private String result;
    /**
     * 当前分支名
     */
    private String branch;
    /**
     * 当前 commit SHA（short）
     */
    private String commitShort;
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

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}

