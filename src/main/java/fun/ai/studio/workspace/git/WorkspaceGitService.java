package fun.ai.studio.workspace.git;

import fun.ai.studio.entity.response.WorkspaceGitEnsureResponse;
import fun.ai.studio.entity.response.WorkspaceGitStatusResponse;
import fun.ai.studio.entity.response.WorkspaceGitLogResponse;
import fun.ai.studio.entity.response.WorkspaceGitCommitPushResponse;
import fun.ai.studio.entity.response.WorkspaceGitRevertResponse;
import fun.ai.studio.workspace.CommandResult;
import fun.ai.studio.workspace.CommandRunner;
import fun.ai.studio.workspace.WorkspaceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Workspace Git 操作服务（调用系统 git 命令）。
 *
 * <p>所有 Git 操作使用 workspace-bot 的 SSH key（由配置指定），通过 GIT_SSH_COMMAND 注入。</p>
 */
@Service
public class WorkspaceGitService {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceGitService.class);
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(120);

    private final WorkspaceGitProperties gitProps;
    private final WorkspaceProperties workspaceProps;
    private final CommandRunner commandRunner;

    public WorkspaceGitService(WorkspaceGitProperties gitProps, WorkspaceProperties workspaceProps, CommandRunner commandRunner) {
        this.gitProps = gitProps;
        this.workspaceProps = workspaceProps;
        this.commandRunner = commandRunner;
    }

    public boolean isEnabled() {
        return gitProps != null && gitProps.isEnabled();
    }

    /**
     * 获取指定 app 目录的 Git 状态
     */
    public WorkspaceGitStatusResponse status(Long userId, Long appId) {
        WorkspaceGitStatusResponse resp = new WorkspaceGitStatusResponse();
        Path appDir = resolveAppDir(userId, appId);
        if (appDir == null || !Files.isDirectory(appDir)) {
            resp.setGitRepo(false);
            resp.setMessage("目录不存在");
            return resp;
        }
        Path gitDir = appDir.resolve(".git");
        if (!Files.isDirectory(gitDir)) {
            resp.setGitRepo(false);
            resp.setMessage("非 Git 仓库");
            return resp;
        }
        resp.setGitRepo(true);

        // branch
        CommandResult branchRes = runGit(appDir, "rev-parse", "--abbrev-ref", "HEAD");
        if (branchRes.getExitCode() == 0) {
            resp.setBranch(branchRes.getOutput().trim());
        }

        // commit short
        CommandResult commitRes = runGit(appDir, "rev-parse", "--short", "HEAD");
        if (commitRes.getExitCode() == 0) {
            resp.setCommitShort(commitRes.getOutput().trim());
        }

        // remote url
        CommandResult remoteRes = runGit(appDir, "config", "--get", "remote.origin.url");
        if (remoteRes.getExitCode() == 0) {
            resp.setRemoteUrl(remoteRes.getOutput().trim());
        }

        // dirty
        CommandResult statusRes = runGit(appDir, "status", "--porcelain");
        if (statusRes.getExitCode() == 0) {
            resp.setDirty(!statusRes.getOutput().trim().isEmpty());
        }

        resp.setMessage("OK");
        return resp;
    }

    /**
     * 确保目录与远端同步：
     * - 目录不存在/空：clone
     * - 目录存在且是 git repo + clean：pull
     * - 目录存在且是 git repo + dirty：返回 NEED_COMMIT
     * - 目录存在但非 git repo/非空：返回 NEED_CONFIRM
     */
    public WorkspaceGitEnsureResponse ensure(Long userId, Long appId) {
        WorkspaceGitEnsureResponse resp = new WorkspaceGitEnsureResponse();
        Path appDir = resolveAppDir(userId, appId);
        if (appDir == null) {
            resp.setResult("FAILED");
            resp.setMessage("无法解析 app 目录");
            return resp;
        }

        String repoUrl = gitProps.buildRepoSshUrl(userId, appId);
        boolean dirExists = Files.isDirectory(appDir);
        boolean isEmpty = dirExists && isDirEmpty(appDir);
        boolean isGitRepo = dirExists && Files.isDirectory(appDir.resolve(".git"));

        // 情况1：目录不存在或为空 → clone
        if (!dirExists || isEmpty) {
            try {
                if (!dirExists) {
                    Files.createDirectories(appDir);
                }
            } catch (IOException e) {
                resp.setResult("FAILED");
                resp.setMessage("创建目录失败: " + e.getMessage());
                return resp;
            }
            CommandResult cloneRes = runGit(appDir.getParent(), "clone", repoUrl, appDir.getFileName().toString());
            if (cloneRes.getExitCode() != 0) {
                resp.setResult("FAILED");
                resp.setMessage("clone 失败: " + cloneRes.getOutput());
                return resp;
            }
            resp.setResult("CLONED");
            resp.setMessage("clone 成功");
            fillBranchAndCommit(resp, appDir);
            return resp;
        }

        // 情况2：存在但非 git repo → 需要用户确认
        if (!isGitRepo) {
            resp.setResult("NEED_CONFIRM");
            resp.setMessage("目录已有内容但不是 Git 仓库，请先手动处理（删除 / 初始化）");
            return resp;
        }

        // 情况3：是 git repo，检查是否 dirty
        CommandResult statusRes = runGit(appDir, "status", "--porcelain");
        boolean dirty = statusRes.getExitCode() == 0 && !statusRes.getOutput().trim().isEmpty();
        if (dirty) {
            resp.setResult("NEED_COMMIT");
            resp.setMessage("工作区有未提交改动，请先 commit 或 stash");
            fillBranchAndCommit(resp, appDir);
            return resp;
        }

        // 情况4：clean → fetch + pull
        CommandResult fetchRes = runGit(appDir, "fetch", "origin");
        if (fetchRes.getExitCode() != 0) {
            log.warn("git fetch failed: {}", fetchRes.getOutput());
        }
        CommandResult pullRes = runGit(appDir, "pull", "--ff-only");
        if (pullRes.getExitCode() != 0) {
            resp.setResult("FAILED");
            resp.setMessage("pull 失败（可能有冲突）: " + pullRes.getOutput());
            fillBranchAndCommit(resp, appDir);
            return resp;
        }
        String pullOut = pullRes.getOutput().trim();
        if (pullOut.contains("Already up to date") || pullOut.contains("Already up-to-date")) {
            resp.setResult("ALREADY_UP_TO_DATE");
            resp.setMessage("已是最新");
        } else {
            resp.setResult("PULLED");
            resp.setMessage("pull 成功");
        }
        fillBranchAndCommit(resp, appDir);
        return resp;
    }

    /**
     * 查看最近 N 次提交
     */
    public WorkspaceGitLogResponse log(Long userId, Long appId, int limit) {
        WorkspaceGitLogResponse resp = new WorkspaceGitLogResponse();
        Path appDir = resolveAppDir(userId, appId);
        if (appDir == null || !Files.isDirectory(appDir) || !Files.isDirectory(appDir.resolve(".git"))) {
            resp.setMessage("非 Git 仓库");
            return resp;
        }

        // branch
        CommandResult branchRes = runGit(appDir, "rev-parse", "--abbrev-ref", "HEAD");
        if (branchRes.getExitCode() == 0) {
            resp.setBranch(branchRes.getOutput().trim());
        }

        // log: format = sha|shaShort|author|date|message
        int n = Math.max(1, Math.min(limit, 50));
        CommandResult logRes = runGit(appDir, "log", "-n", String.valueOf(n), "--pretty=format:%H|%h|%an|%aI|%s");
        if (logRes.getExitCode() != 0) {
            resp.setMessage("git log 失败: " + logRes.getOutput());
            return resp;
        }

        List<WorkspaceGitLogResponse.GitCommitItem> commits = new ArrayList<>();
        String[] lines = logRes.getOutput().split("\n");
        for (String line : lines) {
            if (line == null || line.isBlank()) continue;
            String[] parts = line.split("\\|", 5);
            if (parts.length < 5) continue;
            WorkspaceGitLogResponse.GitCommitItem item = new WorkspaceGitLogResponse.GitCommitItem();
            item.setSha(parts[0]);
            item.setShaShort(parts[1]);
            item.setAuthor(parts[2]);
            item.setDate(parts[3]);
            item.setMessage(parts[4]);
            commits.add(item);
        }
        resp.setCommits(commits);
        resp.setMessage("OK");
        return resp;
    }

    /**
     * 一键 commit + push（使用 workspace-bot 身份）
     */
    public WorkspaceGitCommitPushResponse commitPush(Long userId, Long appId, String commitMessage) {
        WorkspaceGitCommitPushResponse resp = new WorkspaceGitCommitPushResponse();
        Path appDir = resolveAppDir(userId, appId);
        if (appDir == null || !Files.isDirectory(appDir) || !Files.isDirectory(appDir.resolve(".git"))) {
            resp.setResult("FAILED");
            resp.setMessage("非 Git 仓库");
            return resp;
        }

        // 检查是否有改动
        CommandResult statusRes = runGit(appDir, "status", "--porcelain");
        if (statusRes.getExitCode() != 0) {
            resp.setResult("FAILED");
            resp.setMessage("git status 失败: " + statusRes.getOutput());
            return resp;
        }
        if (statusRes.getOutput().trim().isEmpty()) {
            resp.setResult("NO_CHANGES");
            resp.setMessage("没有需要提交的改动");
            fillBranchAndCommitForCommitPush(resp, appDir);
            return resp;
        }

        // 配置 user.name / user.email（workspace-bot 身份，带 userId/appId 便于审计）
        runGit(appDir, "config", "user.name", "workspace-bot");
        runGit(appDir, "config", "user.email", "workspace-bot@funai.local");

        // git add -A
        CommandResult addRes = runGit(appDir, "add", "-A");
        if (addRes.getExitCode() != 0) {
            resp.setResult("FAILED");
            resp.setMessage("git add 失败: " + addRes.getOutput());
            return resp;
        }

        // git commit
        String msg = (commitMessage == null || commitMessage.isBlank())
                ? "Update from workspace (userId=" + userId + ", appId=" + appId + ")"
                : commitMessage + " (userId=" + userId + ", appId=" + appId + ")";
        CommandResult commitRes = runGit(appDir, "commit", "-m", msg);
        if (commitRes.getExitCode() != 0) {
            resp.setResult("FAILED");
            resp.setMessage("git commit 失败: " + commitRes.getOutput());
            return resp;
        }

        // git push
        CommandResult pushRes = runGit(appDir, "push", "origin", "HEAD");
        if (pushRes.getExitCode() != 0) {
            resp.setResult("PUSH_FAILED");
            resp.setMessage("commit 成功但 push 失败（可能有冲突，请先 pull）: " + pushRes.getOutput());
            fillBranchAndCommitForCommitPush(resp, appDir);
            return resp;
        }

        resp.setResult("SUCCESS");
        resp.setMessage("commit + push 成功");
        fillBranchAndCommitForCommitPush(resp, appDir);
        return resp;
    }

    /**
     * 回退到某一次提交（使用 git revert 生成新 commit，不改写历史）
     */
    public WorkspaceGitRevertResponse revert(Long userId, Long appId, String commitSha) {
        WorkspaceGitRevertResponse resp = new WorkspaceGitRevertResponse();
        Path appDir = resolveAppDir(userId, appId);
        if (appDir == null || !Files.isDirectory(appDir) || !Files.isDirectory(appDir.resolve(".git"))) {
            resp.setResult("FAILED");
            resp.setMessage("非 Git 仓库");
            return resp;
        }
        if (commitSha == null || commitSha.isBlank()) {
            resp.setResult("FAILED");
            resp.setMessage("commitSha 不能为空");
            return resp;
        }

        // 检查工作区是否干净
        CommandResult statusRes = runGit(appDir, "status", "--porcelain");
        if (statusRes.getExitCode() == 0 && !statusRes.getOutput().trim().isEmpty()) {
            resp.setResult("FAILED");
            resp.setMessage("工作区有未提交改动，请先 commit 或 stash");
            fillBranchAndCommitForRevert(resp, appDir);
            return resp;
        }

        // 配置 user.name / user.email
        runGit(appDir, "config", "user.name", "workspace-bot");
        runGit(appDir, "config", "user.email", "workspace-bot@funai.local");

        // git revert --no-edit <sha>
        CommandResult revertRes = runGit(appDir, "revert", "--no-edit", commitSha.trim());
        if (revertRes.getExitCode() != 0) {
            // 可能有冲突，尝试 abort
            runGit(appDir, "revert", "--abort");
            resp.setResult("CONFLICT");
            resp.setMessage("revert 失败（可能有冲突）: " + revertRes.getOutput());
            fillBranchAndCommitForRevert(resp, appDir);
            return resp;
        }

        resp.setRevertedCommit(commitSha.length() > 7 ? commitSha.substring(0, 7) : commitSha);

        // git push
        CommandResult pushRes = runGit(appDir, "push", "origin", "HEAD");
        if (pushRes.getExitCode() != 0) {
            resp.setResult("PUSH_FAILED");
            resp.setMessage("revert 成功但 push 失败: " + pushRes.getOutput());
            fillBranchAndCommitForRevert(resp, appDir);
            return resp;
        }

        resp.setResult("SUCCESS");
        resp.setMessage("revert + push 成功");
        fillBranchAndCommitForRevert(resp, appDir);
        return resp;
    }

    private void fillBranchAndCommitForCommitPush(WorkspaceGitCommitPushResponse resp, Path appDir) {
        CommandResult branchRes = runGit(appDir, "rev-parse", "--abbrev-ref", "HEAD");
        if (branchRes.getExitCode() == 0) {
            resp.setBranch(branchRes.getOutput().trim());
        }
        CommandResult commitRes = runGit(appDir, "rev-parse", "--short", "HEAD");
        if (commitRes.getExitCode() == 0) {
            resp.setCommitShort(commitRes.getOutput().trim());
        }
    }

    private void fillBranchAndCommitForRevert(WorkspaceGitRevertResponse resp, Path appDir) {
        CommandResult branchRes = runGit(appDir, "rev-parse", "--abbrev-ref", "HEAD");
        if (branchRes.getExitCode() == 0) {
            resp.setBranch(branchRes.getOutput().trim());
        }
        CommandResult commitRes = runGit(appDir, "rev-parse", "--short", "HEAD");
        if (commitRes.getExitCode() == 0) {
            resp.setCommitShort(commitRes.getOutput().trim());
        }
    }

    private void fillBranchAndCommit(WorkspaceGitEnsureResponse resp, Path appDir) {
        CommandResult branchRes = runGit(appDir, "rev-parse", "--abbrev-ref", "HEAD");
        if (branchRes.getExitCode() == 0) {
            resp.setBranch(branchRes.getOutput().trim());
        }
        CommandResult commitRes = runGit(appDir, "rev-parse", "--short", "HEAD");
        if (commitRes.getExitCode() == 0) {
            resp.setCommitShort(commitRes.getOutput().trim());
        }
    }

    private Path resolveAppDir(Long userId, Long appId) {
        if (workspaceProps == null || workspaceProps.getHostRoot() == null) return null;
        return Paths.get(workspaceProps.getHostRoot(), String.valueOf(userId), "apps", String.valueOf(appId));
    }

    private boolean isDirEmpty(Path dir) {
        try (var s = Files.list(dir)) {
            return s.findFirst().isEmpty();
        } catch (IOException e) {
            return true;
        }
    }

    /**
     * 执行 git 命令，自动注入 GIT_SSH_COMMAND 使用 workspace-bot key。
     */
    private CommandResult runGit(Path workDir, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        for (String a : args) cmd.add(a);

        String sshCmd = buildSshCommand();
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());
        pb.environment().put("GIT_SSH_COMMAND", sshCmd);
        pb.redirectErrorStream(true);

        try {
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (out.length() < 32_000) out.append(line).append('\n');
                }
            }
            boolean finished = p.waitFor(GIT_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                return new CommandResult(124, out + "\n[timeout]");
            }
            return new CommandResult(p.exitValue(), out.toString());
        } catch (Exception e) {
            log.error("runGit failed: cmd={}, error={}", cmd, e.getMessage(), e);
            return new CommandResult(1, "runGit failed: " + e.getMessage());
        }
    }

    private String buildSshCommand() {
        // ssh -i <key> -o UserKnownHostsFile=<known_hosts> -o StrictHostKeyChecking=yes
        String key = gitProps.getSshKeyPath();
        String kh = gitProps.getKnownHostsPath();
        return "ssh -i " + key + " -o UserKnownHostsFile=" + kh + " -o StrictHostKeyChecking=yes";
    }
}

