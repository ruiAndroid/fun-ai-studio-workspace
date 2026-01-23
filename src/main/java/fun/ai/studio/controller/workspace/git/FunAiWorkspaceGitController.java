package fun.ai.studio.controller.workspace.git;

import fun.ai.studio.common.Result;
import fun.ai.studio.entity.request.WorkspaceGitCommitPushRequest;
import fun.ai.studio.entity.response.WorkspaceGitEnsureResponse;
import fun.ai.studio.entity.response.WorkspaceGitStatusResponse;
import fun.ai.studio.entity.response.WorkspaceGitLogResponse;
import fun.ai.studio.entity.response.WorkspaceGitCommitPushResponse;
import fun.ai.studio.entity.response.WorkspaceGitRevertResponse;
import fun.ai.studio.workspace.git.WorkspaceGitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

/**
 * Workspace Git 接口（落在 87 workspace-node）
 *
 * <p>前端仍调用 91 的 /api/fun-ai/workspace/git/*，API 会自动签名转发到 87。</p>
 */
@RestController
@RequestMapping("/api/fun-ai/workspace/git")
@Tag(name = "Workspace Git", description = "Workspace Git 状态与同步接口")
public class FunAiWorkspaceGitController {
    private static final Logger log = LoggerFactory.getLogger(FunAiWorkspaceGitController.class);

    private final WorkspaceGitService gitService;

    public FunAiWorkspaceGitController(WorkspaceGitService gitService) {
        this.gitService = gitService;
    }

    @GetMapping("/status")
    @Operation(summary = "获取 Git 状态", description = "返回指定 app 目录的 Git 状态（是否 git repo、是否 dirty、分支、commit）")
    public Result<WorkspaceGitStatusResponse> status(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId
    ) {
        if (!gitService.isEnabled()) {
            return Result.error("workspace git 功能未启用");
        }
        if (userId == null || appId == null) {
            return Result.error("userId/appId 不能为空");
        }
        try {
            WorkspaceGitStatusResponse resp = gitService.status(userId, appId);
            return Result.success(resp);
        } catch (Exception e) {
            log.error("git status failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return Result.error("git status failed: " + e.getMessage());
        }
    }

    @PostMapping("/ensure")
    @Operation(summary = "确保 Git 同步", description = "目录不存在则 clone；目录存在且 clean 则 pull；dirty 则返回 NEED_COMMIT")
    public Result<WorkspaceGitEnsureResponse> ensure(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId
    ) {
        if (!gitService.isEnabled()) {
            return Result.error("workspace git 功能未启用");
        }
        if (userId == null || appId == null) {
            return Result.error("userId/appId 不能为空");
        }
        try {
            WorkspaceGitEnsureResponse resp = gitService.ensure(userId, appId);
            return Result.success(resp);
        } catch (Exception e) {
            log.error("git ensure failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return Result.error("git ensure failed: " + e.getMessage());
        }
    }

    @GetMapping("/log")
    @Operation(summary = "查看提交历史", description = "返回最近 N 次提交记录（默认 10 条，最多 50 条）")
    public Result<WorkspaceGitLogResponse> log(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @Parameter(description = "返回条数（默认 10，最多 50）") @RequestParam(defaultValue = "10") Integer limit
    ) {
        if (!gitService.isEnabled()) {
            return Result.error("workspace git 功能未启用");
        }
        if (userId == null || appId == null) {
            return Result.error("userId/appId 不能为空");
        }
        try {
            WorkspaceGitLogResponse resp = gitService.log(userId, appId, limit == null ? 10 : limit);
            return Result.success(resp);
        } catch (Exception e) {
            log.error("git log failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return Result.error("git log failed: " + e.getMessage());
        }
    }

    @PostMapping("/commit-push")
    @Operation(summary = "一键提交并推送", description = "将所有改动 commit 并 push 到远端（使用 workspace-bot 身份）")
    public Result<WorkspaceGitCommitPushResponse> commitPush(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @RequestBody(required = false) WorkspaceGitCommitPushRequest request
    ) {
        if (!gitService.isEnabled()) {
            return Result.error("workspace git 功能未启用");
        }
        if (userId == null || appId == null) {
            return Result.error("userId/appId 不能为空");
        }
        try {
            String message = (request == null) ? null : request.getMessage();
            WorkspaceGitCommitPushResponse resp = gitService.commitPush(userId, appId, message);
            return Result.success(resp);
        } catch (Exception e) {
            log.error("git commit-push failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return Result.error("git commit-push failed: " + e.getMessage());
        }
    }

    @PostMapping("/revert")
    @Operation(summary = "回退到某次提交", description = "使用 git revert 生成一个新的 commit 来撤销指定提交的改动（不改写历史），并自动 push")
    public Result<WorkspaceGitRevertResponse> revert(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @Parameter(description = "要撤销的 commit SHA", required = true) @RequestParam String commitSha
    ) {
        if (!gitService.isEnabled()) {
            return Result.error("workspace git 功能未启用");
        }
        if (userId == null || appId == null) {
            return Result.error("userId/appId 不能为空");
        }
        if (commitSha == null || commitSha.isBlank()) {
            return Result.error("commitSha 不能为空");
        }
        try {
            WorkspaceGitRevertResponse resp = gitService.revert(userId, appId, commitSha);
            return Result.success(resp);
        } catch (Exception e) {
            log.error("git revert failed: userId={}, appId={}, commitSha={}, error={}", userId, appId, commitSha, e.getMessage(), e);
            return Result.error("git revert failed: " + e.getMessage());
        }
    }
}

