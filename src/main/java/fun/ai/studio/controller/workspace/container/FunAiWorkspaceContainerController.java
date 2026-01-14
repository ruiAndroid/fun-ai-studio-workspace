package fun.ai.studio.controller.workspace.container;

import fun.ai.studio.common.Result;
import fun.ai.studio.entity.response.FunAiWorkspaceHeartbeatResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceInfoResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceStatusResponse;
import fun.ai.studio.service.FunAiWorkspaceService;
import fun.ai.studio.workspace.WorkspaceActivityTracker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workspace 容器级接口：确保容器/挂载/目录就绪
 */
@RestController
@RequestMapping("/api/fun-ai/workspace/container")
@Tag(name = "Fun AI Workspace 容器级", description = "容器级：ensure/status/heartbeat（不涉及具体 app 的运行态）")
public class FunAiWorkspaceContainerController {
    private static final Logger log = LoggerFactory.getLogger(FunAiWorkspaceContainerController.class);

    private final FunAiWorkspaceService workspaceService;
    private final WorkspaceActivityTracker activityTracker;

    public FunAiWorkspaceContainerController(
            FunAiWorkspaceService workspaceService,
            WorkspaceActivityTracker activityTracker
    ) {
        this.workspaceService = workspaceService;
        this.activityTracker = activityTracker;
    }

    @PostMapping("/ensure")
    @Operation(summary = "创建宿主机目录并确保 workspace 容器运行", description = "创建宿主机目录并确保 ws-u-{userId} 容器运行，挂载到 /workspace")
    public Result<FunAiWorkspaceInfoResponse> ensure(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId
    ) {
        try {
            activityTracker.touch(userId);
            return Result.success(workspaceService.ensureWorkspace(userId));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("ensure workspace failed: userId={}, error={}", userId, e.getMessage(), e);
            return Result.error("ensure workspace failed: " + e.getMessage());
        }
    }

    @GetMapping("/status")
    @Operation(summary = "查询 workspace 容器状态", description = "返回容器状态、端口、宿主机目录 容器状态值：NOT_CREATED / RUNNING / EXITED / UNKNOWN")
    public Result<FunAiWorkspaceStatusResponse> status(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId
    ) {
        try {
            activityTracker.touch(userId);
            return Result.success(workspaceService.getStatus(userId));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("get workspace status failed: userId={}, error={}", userId, e.getMessage(), e);
            return Result.error("get workspace status failed: " + e.getMessage());
        }
    }

    @PostMapping("/heartbeat")
    @Operation(summary = "workspace 心跳", description = "前端定时调用，更新 lastActiveAt，用于 idle 回收")
    public Result<FunAiWorkspaceHeartbeatResponse> heartbeat(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId
    ) {
        try {
            activityTracker.touch(userId);
            FunAiWorkspaceHeartbeatResponse resp = new FunAiWorkspaceHeartbeatResponse();
            resp.setUserId(userId);
            resp.setServerTimeMs(System.currentTimeMillis());
            resp.setMessage("ok");
            return Result.success(resp);
        } catch (Exception e) {
            return Result.error("heartbeat failed: " + e.getMessage());
        }
    }

    @PostMapping("/remove")
    @Operation(summary = "删除 workspace 容器", description = "删除 ws-u-{userId} 容器（docker rm -f）。默认只删容器，不删宿主机持久化目录。")
    public Result<FunAiWorkspaceStatusResponse> remove(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId
    ) {
        try {
            activityTracker.touch(userId);
            return Result.success(workspaceService.removeWorkspaceContainer(userId));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("remove workspace container failed: userId={}, error={}", userId, e.getMessage(), e);
            return Result.error("remove workspace container failed: " + e.getMessage());
        }
    }
}


