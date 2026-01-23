package fun.ai.studio.entity.request;

/**
 * /api/fun-ai/workspace/git/commit-push 请求
 */
public class WorkspaceGitCommitPushRequest {
    /**
     * 提交信息（必填）
     */
    private String message;

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}

