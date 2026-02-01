package fun.ai.studio.controller.workspace.test;

import fun.ai.studio.common.Result;
import fun.ai.studio.entity.response.FunAiWorkspaceApiTestResponse;
import fun.ai.studio.service.FunAiWorkspaceService;
import fun.ai.studio.workspace.CurlCommandValidator;
import fun.ai.studio.workspace.WorkspaceActivityTracker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workspace API 测试：在容器内执行 curl 命令
 */
@RestController
@RequestMapping("/api/fun-ai/workspace/test")
@Tag(name = "Fun AI Workspace API 测试", description = "在容器内执行 curl 命令测试接口")
public class FunAiWorkspaceTestController {
    private static final Logger log = LoggerFactory.getLogger(FunAiWorkspaceTestController.class);

    private final FunAiWorkspaceService workspaceService;
    private final CurlCommandValidator curlCommandValidator;
    private final WorkspaceActivityTracker activityTracker;

    public FunAiWorkspaceTestController(FunAiWorkspaceService workspaceService,
                                        CurlCommandValidator curlCommandValidator,
                                        WorkspaceActivityTracker activityTracker) {
        this.workspaceService = workspaceService;
        this.curlCommandValidator = curlCommandValidator;
        this.activityTracker = activityTracker;
    }

    @PostMapping("/api")
    @Operation(summary = "执行 API 测试", description = "在 workspace 容器内执行 curl 命令测试接口")
    public Result<FunAiWorkspaceApiTestResponse> testApi(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @Parameter(description = "curl 命令", required = true) @RequestParam String curlCommand,
            @Parameter(description = "超时时间（秒）", required = false) @RequestParam(required = false, defaultValue = "30") Integer timeoutSeconds
    ) {
        try {
            activityTracker.touch(userId);
            
            // 参数验证
            if (userId == null) {
                return Result.error("userId 不能为空");
            }
            if (appId == null) {
                return Result.error("appId 不能为空");
            }
            if (curlCommand == null || curlCommand.isBlank()) {
                return Result.error("curlCommand 不能为空");
            }
            
            // 超时参数验证
            if (timeoutSeconds != null && (timeoutSeconds < 1 || timeoutSeconds > 300)) {
                return Result.error("timeoutSeconds 必须在 1 到 300 之间");
            }
            
            // 安全验证
            try {
                curlCommandValidator.validate(curlCommand);
            } catch (IllegalArgumentException e) {
                FunAiWorkspaceApiTestResponse errorResponse = new FunAiWorkspaceApiTestResponse();
                errorResponse.setUserId(userId);
                errorResponse.setAppId(appId);
                errorResponse.setSuccess(false);
                errorResponse.setErrorType("VALIDATION_ERROR");
                errorResponse.setMessage(e.getMessage());
                errorResponse.setExecutionTimeMs(0L);
                return Result.success(errorResponse);
            }
            
            return Result.success(workspaceService.executeCurlCommand(userId, appId, curlCommand, timeoutSeconds));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("execute curl command failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return Result.error("execute curl command failed: " + e.getMessage());
        }
    }
}
