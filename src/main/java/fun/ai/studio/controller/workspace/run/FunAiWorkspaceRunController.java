package fun.ai.studio.controller.workspace.run;

import fun.ai.studio.common.Result;
import fun.ai.studio.entity.response.FunAiWorkspaceRunStatusResponse;
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
 * Workspace 运行态：DevServer/进程生命周期
 */
@RestController
@RequestMapping("/api/fun-ai/workspace/run")
@Tag(name = "Fun AI Workspace 应用运行态", description = "运行态：start/stop/status（同一用户同一时间仅允许运行一个 app）")
public class FunAiWorkspaceRunController {
    private static final Logger log = LoggerFactory.getLogger(FunAiWorkspaceRunController.class);

    private final FunAiWorkspaceService workspaceService;
    private final WorkspaceActivityTracker activityTracker;

    public FunAiWorkspaceRunController(FunAiWorkspaceService workspaceService, WorkspaceActivityTracker activityTracker) {
        this.workspaceService = workspaceService;
        this.activityTracker = activityTracker;
    }

    @PostMapping("/start")
    @Operation(summary = "启动应用（切换模式）", description = "在 workspace 容器内启动 npm run dev；若已有其它 app RUNNING/STARTING，会先 stop 再启动目标 app。")
    public Result<FunAiWorkspaceRunStatusResponse> start(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId
    ) {
        try {
            activityTracker.touch(userId);
            return Result.success(workspaceService.startDev(userId, appId));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("start dev failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return Result.error("start dev failed: " + e.getMessage());
        }
    }

    @PostMapping("/build")
    @Operation(summary = "受控构建（非阻塞）", description = "在 workspace 容器内执行 npm run build（写入 current.json/dev.log）。会先 stopRun，保证平台对 5173 拥有最终控制权。")
    public Result<FunAiWorkspaceRunStatusResponse> build(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId
    ) {
        try {
            activityTracker.touch(userId);
            return Result.success(workspaceService.startBuild(userId, appId));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("start build failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return Result.error("start build failed: " + e.getMessage());
        }
    }

    @PostMapping("/preview")
    @Operation(summary = "受控预览（非阻塞）", description = "在 workspace 容器内执行 npm run start（全栈项目预览）。会先 stopRun，保证平台对 5173 拥有最终控制权。")
    public Result<FunAiWorkspaceRunStatusResponse> preview(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId
    ) {
        try {
            activityTracker.touch(userId);
            return Result.success(workspaceService.startPreview(userId, appId));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("start preview failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return Result.error("start preview failed: " + e.getMessage());
        }
    }

    @PostMapping("/install")
    @Operation(summary = "受控安装依赖（非阻塞）", description = "在 workspace 容器内执行 npm install（写入 current.json/dev.log）。会先 stopRun，避免与受控预览/其它任务并发导致状态错乱。")
    public Result<FunAiWorkspaceRunStatusResponse> install(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId
    ) {
        try {
            activityTracker.touch(userId);
            return Result.success(workspaceService.startInstall(userId, appId));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("start install failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return Result.error("start install failed: " + e.getMessage());
        }
    }

    @PostMapping("/stop")
    @Operation(summary = "停止当前运行应用", description = "kill 进程组并清理 /workspace/run/dev.pid 与 current.json")
    public Result<FunAiWorkspaceRunStatusResponse> stop(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId
    ) {
        try {
            activityTracker.touch(userId);
            return Result.success(workspaceService.stopRun(userId));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("stop run failed: userId={}, error={}", userId, e.getMessage(), e);
            return Result.error("stop run failed: " + e.getMessage());
        }
    }

    @GetMapping("/status")
    @Operation(summary = "查询当前应用运行状态", description = "读取 /workspace/run/current.json 并验证 pid/端口是否就绪 应用运行态：IDLE / STARTING / RUNNING / DEAD（RUNNING 时返回 previewUrl）")
    public Result<FunAiWorkspaceRunStatusResponse> status(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId
    ) {
        try {
            activityTracker.touch(userId);
            return Result.success(workspaceService.getRunStatus(userId));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("get run status failed: userId={}, error={}", userId, e.getMessage(), e);
            return Result.error("get run status failed: " + e.getMessage());
        }
    }
}


