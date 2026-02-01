package fun.ai.studio.entity.response;

import lombok.Data;

/**
 * Workspace API 测试响应
 */
@Data
public class FunAiWorkspaceApiTestResponse {
    private Long userId;
    private Long appId;
    private Boolean success;
    private String stdout;
    private String stderr;
    private Integer exitCode;
    private Long executionTimeMs;
    /**
     * 错误类型：TIMEOUT, VALIDATION_ERROR, EXECUTION_ERROR
     */
    private String errorType;
    private String message;
    private Boolean stdoutTruncated;
    private Boolean stderrTruncated;
    private Long stdoutOriginalSize;
    private Long stderrOriginalSize;
}
