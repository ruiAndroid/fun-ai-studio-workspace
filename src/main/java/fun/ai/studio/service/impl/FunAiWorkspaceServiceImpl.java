package fun.ai.studio.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.ai.studio.entity.response.FunAiWorkspaceInfoResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceFileNode;
import fun.ai.studio.entity.response.FunAiWorkspaceFileReadResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceFileTreeResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceProjectDirResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceRunMeta;
import fun.ai.studio.entity.response.FunAiWorkspaceRunStatusResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceStatusResponse;
import fun.ai.studio.service.FunAiWorkspaceService;
import fun.ai.studio.workspace.CommandResult;
import fun.ai.studio.workspace.CommandRunner;
import fun.ai.studio.workspace.WorkspaceMeta;
import fun.ai.studio.workspace.WorkspaceProperties;
import fun.ai.studio.workspace.ZipUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class FunAiWorkspaceServiceImpl implements FunAiWorkspaceService {
    private static final Logger log = LoggerFactory.getLogger(FunAiWorkspaceServiceImpl.class);

    private final WorkspaceProperties props;
    private final CommandRunner commandRunner;
    private final ObjectMapper objectMapper;

    /**
     * Workspace 会调用 docker/podman 执行 stop/rm 等操作。
     * 在 podman-docker 场景下，`docker rm -f` 默认会等待 10s（SIGTERM -> SIGKILL），
     * 如果这里也用 10s 超时，很容易“刚好超时”导致命令被强杀、容器残留，进而触发 name already in use / conmon 异常链路。
     */
    private static final Duration CMD_TIMEOUT = Duration.ofSeconds(30);
    private static final long MAX_TEXT_FILE_BYTES = 1024L * 1024L * 2L; // 2MB

    public FunAiWorkspaceServiceImpl(WorkspaceProperties props,
                                     CommandRunner commandRunner,
                                     ObjectMapper objectMapper) {
        this.props = props;
        this.commandRunner = commandRunner;
        this.objectMapper = objectMapper;
    }

    @Override
    public FunAiWorkspaceInfoResponse ensureWorkspace(Long userId) {
        assertEnabled();
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (!StringUtils.hasText(props.getHostRoot())) {
            throw new IllegalArgumentException("funai.workspace.hostRoot 未配置");
        }
        if (!StringUtils.hasText(props.getImage())) {
            throw new IllegalArgumentException("funai.workspace.image 未配置（建议使用你自己的 ACR 镜像）");
        }

        Path hostUserDir = resolveHostWorkspaceDir(userId);
        Path appsDir = hostUserDir.resolve("apps");
        Path runDir = hostUserDir.resolve("run");
        ensureDir(hostUserDir);
        ensureDir(appsDir);
        ensureDir(runDir);

        // 兜底清理历史 run 日志（按 userId+type 全局保留 N 份；忽略 appId）
        // 说明：用户频繁切换 appId 时，如果仅按 appId 清理，run 目录仍会持续增长。
        int keep = Math.max(0, props.getRunLogKeepPerType());
        if (keep > 0) {
            try { pruneRunLogs(userId, "BUILD", keep); } catch (Exception ignore) {}
            try { pruneRunLogs(userId, "INSTALL", keep); } catch (Exception ignore) {}
            try { pruneRunLogs(userId, "START", keep); } catch (Exception ignore) {}
            try { pruneRunLogs(userId, "DEV", keep); } catch (Exception ignore) {}
        }

        WorkspaceMeta meta = loadOrInitMeta(userId, hostUserDir);
        ensureContainerRunning(userId, hostUserDir, meta);

        FunAiWorkspaceInfoResponse resp = new FunAiWorkspaceInfoResponse();
        resp.setUserId(userId);
        resp.setContainerName(meta.getContainerName());
        resp.setImage(meta.getImage());
        resp.setHostPort(meta.getHostPort());
        resp.setContainerPort(meta.getContainerPort());
        resp.setHostWorkspaceDir(hostUserDir.toString());
        resp.setHostAppsDir(appsDir.toString());
        resp.setContainerWorkspaceDir(props.getContainerWorkdir());
        resp.setContainerAppsDir(Paths.get(props.getContainerWorkdir(), "apps").toString().replace("\\", "/"));

        return resp;
    }

    @Override
    public FunAiWorkspaceStatusResponse getStatus(Long userId) {
        assertEnabled();
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }

        Path hostUserDir = resolveHostWorkspaceDir(userId);
        WorkspaceMeta meta = tryLoadMeta(hostUserDir);
        String containerName = meta != null ? meta.getContainerName() : containerName(userId);
        Integer hostPort = meta != null ? meta.getHostPort() : null;

        FunAiWorkspaceStatusResponse resp = new FunAiWorkspaceStatusResponse();
        resp.setUserId(userId);
        resp.setContainerName(containerName);
        resp.setHostPort(hostPort);
        resp.setContainerPort(props.getContainerPort());
        resp.setHostWorkspaceDir(hostUserDir.toString());
        String cStatus = queryContainerStatus(containerName);
        resp.setContainerStatus(cStatus);

        return resp;
    }

    @Override
    public FunAiWorkspaceProjectDirResponse ensureAppDir(Long userId, Long appId) {
        if (appId == null) {
            throw new IllegalArgumentException("appId 不能为空");
        }
        // 关键：先做 DB 归属校验，再触发容器/目录副作用（ensureWorkspace 会启动容器、创建目录）
        assertAppOwned(userId, appId);
        FunAiWorkspaceInfoResponse ws = ensureWorkspace(userId);
        Path hostAppDir = resolveHostWorkspaceDir(userId).resolve("apps").resolve(String.valueOf(appId));
        ensureDir(hostAppDir);

        FunAiWorkspaceProjectDirResponse resp = new FunAiWorkspaceProjectDirResponse();
        resp.setUserId(userId);
        resp.setAppId(appId);
        resp.setHostAppDir(hostAppDir.toString());
        resp.setContainerAppDir(Paths.get(ws.getContainerAppsDir(), String.valueOf(appId)).toString().replace("\\", "/"));
        return resp;
    }

    /**
     * 轻量确保应用目录存在（仅宿主机目录，不触发容器启动）。
     * <p>
     * files 子系统（如 /workspace/files/content）会高频调用，避免因 ensure-dir 拉起容器造成 CPU/资源抖动。
     */
    private FunAiWorkspaceProjectDirResponse ensureAppDirHostOnly(Long userId, Long appId) {
        assertEnabled();
        if (userId == null) throw new IllegalArgumentException("userId 不能为空");
        if (appId == null) throw new IllegalArgumentException("appId 不能为空");
        // 归属校验仍然保留（无容器副作用）
        assertAppOwned(userId, appId);
        if (!StringUtils.hasText(props.getHostRoot())) {
            throw new IllegalArgumentException("funai.workspace.hostRoot 未配置");
        }

        Path hostUserDir = resolveHostWorkspaceDir(userId);
        Path appsDir = hostUserDir.resolve("apps");
        Path runDir = hostUserDir.resolve("run");
        ensureDir(hostUserDir);
        ensureDir(appsDir);
        ensureDir(runDir);

        Path hostAppDir = appsDir.resolve(String.valueOf(appId));
        ensureDir(hostAppDir);

        FunAiWorkspaceProjectDirResponse resp = new FunAiWorkspaceProjectDirResponse();
        resp.setUserId(userId);
        resp.setAppId(appId);
        resp.setHostAppDir(hostAppDir.toString());
        // 仅用于前端展示/拼接，不依赖容器存在
        resp.setContainerAppDir(Paths.get(props.getContainerWorkdir(), "apps", String.valueOf(appId)).toString().replace("\\", "/"));
        return resp;
    }

    @Override
    public FunAiWorkspaceProjectDirResponse uploadAppZip(Long userId, Long appId, MultipartFile file, boolean overwrite) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("zip文件不能为空");
        }
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase().endsWith(".zip")) {
            throw new IllegalArgumentException("仅支持上传 .zip 文件");
        }

        assertAppOwned(userId, appId);
        FunAiWorkspaceProjectDirResponse dir = ensureAppDirHostOnly(userId, appId);
        Path hostAppDir = Paths.get(dir.getHostAppDir());

        try {
            ensureDir(hostAppDir);

            // overwrite=true：清空旧内容，但保留 .git / Dockerfile 等关键文件（避免破坏 git 状态/部署构建标准）
            final Set<String> protectedNames = Set.of(".git", "Dockerfile", "Containerfile", ".dockerignore", ".gitignore");
            if (overwrite) {
                try (var stream = Files.list(hostAppDir)) {
                    for (Path p : (Iterable<Path>) stream::iterator) {
                        String fileName = p.getFileName().toString();
                        // 保留关键文件/目录，不删除
                        if (protectedNames.contains(fileName)) {
                            continue;
                        }
                        ZipUtils.deleteDirectoryRecursively(p);
                    }
                }
            } else {
                // overwrite=false：若目录非空则拒绝（.git / Dockerfile 等关键文件除外）
                try (var stream = Files.list(hostAppDir)) {
                    boolean hasNonProtected = stream.anyMatch(p -> !protectedNames.contains(p.getFileName().toString()));
                    if (hasNonProtected) {
                        throw new IllegalArgumentException("应用目录已存在且非空，overwrite=false 时不允许覆盖");
                    }
                }
            }

            // 解压必须同步完成后才返回；中途失败则回滚清理（删除残留内容，但保留 protectedNames）
            // 解压时排除 protectedNames（避免 zip 包覆盖 .git/Dockerfile 等）
            try (InputStream in = file.getInputStream()) {
                ZipUtils.unzipSafely(in, hostAppDir, protectedNames);
            } catch (Exception unzipErr) {
                try {
                    // best-effort：清理解压残留，但保留 protectedNames
                    try (var stream = Files.list(hostAppDir)) {
                        for (Path p : (Iterable<Path>) stream::iterator) {
                            String fileName = p.getFileName().toString();
                            if (protectedNames.contains(fileName)) {
                                continue;
                            }
                            ZipUtils.deleteDirectoryRecursively(p);
                        }
                    }
                } catch (Exception ignore) {
                }
                throw unzipErr;
            }
            return dir;
        } catch (Exception e) {
            log.error("upload app zip failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            throw new RuntimeException("上传并解压失败: " + e.getMessage(), e);
        }
    }

    @Override
    public FunAiWorkspaceRunStatusResponse startDev(Long userId, Long appId) {
        assertEnabled();
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (appId == null) {
            throw new IllegalArgumentException("appId 不能为空");
        }
        assertAppOwned(userId, appId);

        // 切换模式：同一用户同一时间只能运行一个应用
        // - 若当前已在 RUNNING/STARTING 且 appId 不同：先 stop 再启动目标 app
        // - 若当前已在 RUNNING 且 appId 相同：直接返回当前状态
        try {
            FunAiWorkspaceRunStatusResponse cur = getRunStatus(userId);
            String st = cur == null ? null : cur.getState();
            Long runningAppId = cur == null ? null : cur.getAppId();
            if (runningAppId != null && st != null
                    && ("RUNNING".equalsIgnoreCase(st) || "STARTING".equalsIgnoreCase(st))) {
                if (runningAppId.equals(appId)) {
                    if (cur.getMessage() == null || cur.getMessage().isBlank()) {
                        cur.setMessage("已在运行中");
                    }
                    return cur;
                }
                // stopRun 内部会做进程组 kill + 清理 run 元数据 + 落库
                stopRun(userId);
            }
        } catch (Exception ignore) {
        }

        return startManagedRun(userId, appId, "DEV");
    }

    @Override
    public FunAiWorkspaceRunStatusResponse startBuild(Long userId, Long appId) {
        assertEnabled();
        if (userId == null) throw new IllegalArgumentException("userId 不能为空");
        if (appId == null) throw new IllegalArgumentException("appId 不能为空");
        assertAppOwned(userId, appId);

        // 平台拥有最终控制权：先 stop 清理旧进程/端口占用
        try {
            stopRun(userId);
        } catch (Exception ignore) {
        }
        return startManagedRun(userId, appId, "BUILD");
    }

    @Override
    public FunAiWorkspaceRunStatusResponse startPreview(Long userId, Long appId) {
        assertEnabled();
        if (userId == null) throw new IllegalArgumentException("userId 不能为空");
        if (appId == null) throw new IllegalArgumentException("appId 不能为空");
        assertAppOwned(userId, appId);

        // 平台拥有最终控制权：先 stop 清理旧进程/端口占用
        try {
            stopRun(userId);
        } catch (Exception ignore) {
        }
        // preview 语义：尽量把项目“跑起来可访问”（类似部署）。
        // - 全栈/后端项目：通常 scripts.start
        // - 纯前端项目：可能只有 scripts.preview 或 scripts.dev
        // 这里做一个温和的 fallback：start -> preview -> dev；都没有则报错（避免盲跑）。
        Path hostAppDir = resolveHostWorkspaceDir(userId).resolve("apps").resolve(String.valueOf(appId));
        assertAppReadyForRun(userId, appId, hostAppDir, "preview");
        Path pkg = findPackageJsonForRun(hostAppDir, 2);
        if (pkg == null) {
            throw new IllegalArgumentException("未找到 package.json（最大深度=2）：请先上传/创建项目文件");
        }
        String script = pickPreviewScript(pkg);
        if (script == null) {
            String raw;
            try {
                raw = Files.readString(pkg, StandardCharsets.UTF_8);
            } catch (Exception ignore) {
                raw = null;
            }
            List<String> keys = detectScriptKeysBestEffort(raw);
            log.warn("preview script not found: userId={}, appId={}, packageJson={}, detectedScripts={}",
                    userId, appId, pkg, keys);
            throw new IllegalArgumentException("当前项目未定义可用于预览/部署的脚本（package.json=" + pkg + "）。"
                    + "检测到 scripts=" + keys + "。"
                    + "请在 scripts 中添加 start 或 preview（推荐），或至少提供 dev（开发模式）。");
        }
        // 将具体脚本名传入受控运行：START 模式统一做端口/BASE_PATH 注入。
        return startManagedRun(userId, appId, "START:" + script);
    }

    @Override
    public FunAiWorkspaceRunStatusResponse startInstall(Long userId, Long appId) {
        assertEnabled();
        if (userId == null) throw new IllegalArgumentException("userId 不能为空");
        if (appId == null) throw new IllegalArgumentException("appId 不能为空");
        assertAppOwned(userId, appId);

        // 平台拥有最终控制权：避免与预览/其它任务并发导致状态错乱
        try {
            stopRun(userId);
        } catch (Exception ignore) {
        }
        return startManagedRun(userId, appId, "INSTALL");
    }

    /**
     * 受控运行（非阻塞）：统一产出 run/current.json、run/dev.pid、run/dev.log
     * - DEV：npm run dev（并强制端口 + base）
     * - START：npm run start（注入 PORT/HOST/BASE_PATH）
     * - BUILD：npm run build（执行完成后写入 exitCode/finishedAt，并清理 pid 文件）
     * - INSTALL：npm install（执行完成后写入 exitCode/finishedAt，并清理 pid 文件）
     */
    private FunAiWorkspaceRunStatusResponse startManagedRun(Long userId, Long appId, String type) {
        FunAiWorkspaceInfoResponse ws = ensureWorkspace(userId);

        // 重要：run/start 不应隐式创建 apps/{appId} 目录。
        // workspace-dev（大机）无法访问业务库判断 appId 是否真实存在；如果 userId/appId 写错，自动 mkdir 会在宿主机产生垃圾目录。
        // 因此这里改为“只校验，不创建”：目录不存在直接提示先导入项目/创建目录。
        Path hostAppDir = resolveHostWorkspaceDir(userId).resolve("apps").resolve(String.valueOf(appId));
        assertAppReadyForRun(userId, appId, hostAppDir, type);

        String containerName = ws.getContainerName();
        String containerWorkdir = props.getContainerWorkdir();
        String containerAppDir = Paths.get(containerWorkdir, "apps", String.valueOf(appId)).toString().replace("\\", "/");
        String runDir = Paths.get(containerWorkdir, "run").toString().replace("\\", "/");
        String pidFile = runDir + "/dev.pid";
        String metaFile = runDir + "/current.json";
        String startSh = runDir + "/managed-start.sh";

        String raw = (type == null ? "" : type.trim());
        if (raw.isEmpty()) raw = "DEV";
        // 支持形如 "START:start" / "START:preview" / "START:dev"
        String op = raw;
        String selectedScript = null;
        int idx = raw.indexOf(':');
        if (idx > 0) {
            op = raw.substring(0, idx).trim();
            selectedScript = raw.substring(idx + 1).trim();
        }
        op = op.toUpperCase();
        if (selectedScript != null && !selectedScript.isBlank()) {
            selectedScript = selectedScript.trim();
        } else {
            selectedScript = null;
        }

        // 日志保留策略：同一 userId + type 仅保留最近 N 份（忽略 appId，避免 run 目录随着 appId 增长而无限增长）
        try {
            pruneRunLogs(userId, op, Math.max(0, props.getRunLogKeepPerType()));
        } catch (Exception ignore) {
        }

        // 每次受控任务使用独立日志文件，避免 build/install/preview/dev 混在同一个 dev.log（前端无法区分）
        long logTs = System.currentTimeMillis();
        String logFile = runDir + "/run-" + op.toLowerCase() + "-" + appId + "-" + logTs + ".log";

        String npmCacheMode = (props.getNpmCacheMode() == null ? "APP" : props.getNpmCacheMode().trim());
        if (npmCacheMode.isEmpty()) npmCacheMode = "APP";
        npmCacheMode = npmCacheMode.toUpperCase();
        long npmCacheMaxMb = props.getNpmCacheMaxMb();

        // npm cache 策略：避免容器层 ~/.npm 膨胀导致“删项目不回收磁盘”
        // - APP：缓存落到 {APP_DIR}/.npm-cache（删项目目录即可回收）
        // - DISABLED：缓存落到 /tmp 并在任务结束后删除
        // - CONTAINER：保持默认（不推荐）
        String npmCacheSnippet = ""
                + "NPM_CACHE_MODE='" + npmCacheMode.replace("'", "") + "'\n"
                + "NPM_CACHE_MAX_MB='" + Math.max(0, npmCacheMaxMb) + "'\n"
                + "NPM_CACHE_DIR=''\n"
                + "if [ \"$NPM_CACHE_MODE\" = \"APP\" ]; then NPM_CACHE_DIR=\"$APP_DIR/.npm-cache\"; fi\n"
                + "if [ \"$NPM_CACHE_MODE\" = \"DISABLED\" ]; then NPM_CACHE_DIR=\"/tmp/npm-cache-" + userId + "-" + appId + "\"; fi\n"
                + "if [ -n \"$NPM_CACHE_DIR\" ]; then\n"
                + "  mkdir -p \"$NPM_CACHE_DIR\" >/dev/null 2>&1 || true\n"
                + "  export NPM_CONFIG_CACHE=\"$NPM_CACHE_DIR\"\n"
                + "  export npm_config_cache=\"$NPM_CACHE_DIR\"\n"
                + "  echo \"[npm] cache=$NPM_CACHE_DIR mode=$NPM_CACHE_MODE\" >>\"$LOG_FILE\" 2>&1\n"
                + "fi\n"
                + "prune_npm_cache() {\n"
                + "  [ -z \"$NPM_CACHE_DIR\" ] && return 0\n"
                + "  [ \"$NPM_CACHE_MODE\" = \"CONTAINER\" ] && return 0\n"
                + "  [ \"$NPM_CACHE_MAX_MB\" -le 0 ] && return 0\n"
                + "  sz=$(du -sm \"$NPM_CACHE_DIR\" 2>/dev/null | awk '{print $1}' || echo 0)\n"
                + "  [ -z \"$sz\" ] && sz=0\n"
                + "  if [ \"$sz\" -gt \"$NPM_CACHE_MAX_MB\" ]; then\n"
                + "    echo \"[npm] cache too large: ${sz}MB > ${NPM_CACHE_MAX_MB}MB, cleaning...\" >>\"$LOG_FILE\" 2>&1\n"
                + "    rm -rf \"$NPM_CACHE_DIR/_cacache\" \"$NPM_CACHE_DIR/_npx\" 2>/dev/null || true\n"
                + "  fi\n"
                + "}\n";

        String innerScript;
        if ("BUILD".equals(op)) {
            innerScript = ""
                    + "set -e\n"
                    + "APP_DIR='" + containerAppDir + "'\n"
                    + "PID_FILE='" + pidFile + "'\n"
                    + "META_FILE='" + metaFile + "'\n"
                    + "LOG_FILE='" + logFile + "'\n"
                    + "STARTED_AT=$(date +%s)\n"
                    + "echo \"[build] start at $(date -Is)\" >>\"$LOG_FILE\" 2>&1\n"
                    + "cd \"$APP_DIR\" >>\"$LOG_FILE\" 2>&1 || true\n"
                    + "if [ ! -f package.json ]; then\n"
                    + "  pkg=$(find \"$APP_DIR\" -maxdepth 2 -type f -name package.json 2>/dev/null | head -n 1 || true)\n"
                    + "  if [ -n \"$pkg\" ]; then APP_DIR=$(dirname \"$pkg\"); cd \"$APP_DIR\" >>\"$LOG_FILE\" 2>&1; fi\n"
                    + "fi\n"
                    + "code=0\n"
                    + "if [ ! -f package.json ]; then echo \"package.json not found: $APP_DIR\" >>\"$LOG_FILE\"; code=2; else\n"
                    + "  if [ -n \"$NPM_CONFIG_REGISTRY\" ]; then npm config set registry \"$NPM_CONFIG_REGISTRY\" >/dev/null 2>&1 || true; fi\n"
                    + "  if [ -z \"$NPM_CONFIG_REGISTRY\" ] && [ -n \"$npm_config_registry\" ]; then npm config set registry \"$npm_config_registry\" >/dev/null 2>&1 || true; fi\n"
                    + "  reg=$(npm config get registry 2>/dev/null || true)\n"
                    + "  if [ -n \"$reg\" ]; then echo \"[build] npm registry: $reg\" >>\"$LOG_FILE\" 2>&1; fi\n"
                    + npmCacheSnippet
                    + "  if [ ! -d node_modules ]; then echo \"[build] npm install (include dev)...\" >>\"$LOG_FILE\"; npm install --include=dev >>\"$LOG_FILE\" 2>&1 || (echo \"[build] npm install failed, retry with --legacy-peer-deps\" >>\"$LOG_FILE\"; npm install --include=dev --legacy-peer-deps >>\"$LOG_FILE\" 2>&1); fi\n"
                    + "  if [ ! -x node_modules/.bin/tsc ]; then echo \"[build] tsc not found, npm install (include dev)...\" >>\"$LOG_FILE\"; npm install --include=dev >>\"$LOG_FILE\" 2>&1 || (echo \"[build] npm install failed, retry with --legacy-peer-deps\" >>\"$LOG_FILE\"; npm install --include=dev --legacy-peer-deps >>\"$LOG_FILE\" 2>&1); fi\n"
                    + "  prune_npm_cache || true\n"
                    + "  echo \"[build] npm run build\" >>\"$LOG_FILE\" 2>&1\n"
                    + "  set +e\n"
                    + "  npm run build >>\"$LOG_FILE\" 2>&1\n"
                    + "  code=$?\n"
                    + "  set -e\n"
                    + "fi\n"
                    + "if [ \"$NPM_CACHE_MODE\" = \"DISABLED\" ] && [ -n \"$NPM_CACHE_DIR\" ]; then rm -rf \"$NPM_CACHE_DIR\" 2>/dev/null || true; fi\n"
                    + "FINISHED_AT=$(date +%s)\n"
                    + "rm -f \"$PID_FILE\" || true\n"
                    + "printf '{\"appId\":" + appId + ",\"type\":\"BUILD\",\"pid\":null,\"startedAt\":%s,\"finishedAt\":%s,\"exitCode\":%s,\"logPath\":\"" + logFile + "\"}' \"$STARTED_AT\" \"$FINISHED_AT\" \"$code\" > \"$META_FILE\"\n";
        } else if ("INSTALL".equals(op)) {
            innerScript = ""
                    + "set -e\n"
                    + "APP_DIR='" + containerAppDir + "'\n"
                    + "PID_FILE='" + pidFile + "'\n"
                    + "META_FILE='" + metaFile + "'\n"
                    + "LOG_FILE='" + logFile + "'\n"
                    + "STARTED_AT=$(date +%s)\n"
                    + "echo \"[install] start at $(date -Is)\" >>\"$LOG_FILE\" 2>&1\n"
                    + "cd \"$APP_DIR\" >>\"$LOG_FILE\" 2>&1 || true\n"
                    + "if [ ! -f package.json ]; then\n"
                    + "  pkg=$(find \"$APP_DIR\" -maxdepth 2 -type f -name package.json 2>/dev/null | head -n 1 || true)\n"
                    + "  if [ -n \"$pkg\" ]; then APP_DIR=$(dirname \"$pkg\"); cd \"$APP_DIR\" >>\"$LOG_FILE\" 2>&1; fi\n"
                    + "fi\n"
                    + "code=0\n"
                    + "if [ ! -f package.json ]; then echo \"package.json not found: $APP_DIR\" >>\"$LOG_FILE\"; code=2; else\n"
                    + "  if [ -n \"$NPM_CONFIG_REGISTRY\" ]; then npm config set registry \"$NPM_CONFIG_REGISTRY\" >/dev/null 2>&1 || true; fi\n"
                    + "  if [ -z \"$NPM_CONFIG_REGISTRY\" ] && [ -n \"$npm_config_registry\" ]; then npm config set registry \"$npm_config_registry\" >/dev/null 2>&1 || true; fi\n"
                    + "  reg=$(npm config get registry 2>/dev/null || true)\n"
                    + "  if [ -n \"$reg\" ]; then echo \"[install] npm registry: $reg\" >>\"$LOG_FILE\" 2>&1; fi\n"
                    + "  # 自动生成 .npmrc（用于部署时加速）\n"
                    + "  if [ ! -f .npmrc ] && [ -n \"$reg\" ]; then\n"
                    + "    echo \"registry=$reg\" > .npmrc\n"
                    + "    echo \"[install] created .npmrc with registry=$reg\" >>\"$LOG_FILE\" 2>&1\n"
                    + "  fi\n"
                    + npmCacheSnippet
                    + "  echo \"[install] npm install (include dev)\" >>\"$LOG_FILE\" 2>&1\n"
                    + "  set +e\n"
                    + "  npm install --include=dev >>\"$LOG_FILE\" 2>&1 || (echo \"[install] npm install failed, retry with --legacy-peer-deps\" >>\"$LOG_FILE\"; npm install --include=dev --legacy-peer-deps >>\"$LOG_FILE\" 2>&1)\n"
                    + "  code=$?\n"
                    + "  set -e\n"
                    + "  prune_npm_cache || true\n"
                    + "fi\n"
                    + "if [ \"$NPM_CACHE_MODE\" = \"DISABLED\" ] && [ -n \"$NPM_CACHE_DIR\" ]; then rm -rf \"$NPM_CACHE_DIR\" 2>/dev/null || true; fi\n"
                    + "FINISHED_AT=$(date +%s)\n"
                    + "rm -f \"$PID_FILE\" || true\n"
                    + "printf '{\"appId\":" + appId + ",\"type\":\"INSTALL\",\"pid\":null,\"startedAt\":%s,\"finishedAt\":%s,\"exitCode\":%s,\"logPath\":\"" + logFile + "\"}' \"$STARTED_AT\" \"$FINISHED_AT\" \"$code\" > \"$META_FILE\"\n";
        } else if ("START".equals(op)) {
            innerScript = ""
                    + "set -e\n"
                    + "APP_DIR='" + containerAppDir + "'\n"
                    + "PORT='" + ws.getContainerPort() + "'\n"
                    + "PID_FILE='" + pidFile + "'\n"
                    + "META_FILE='" + metaFile + "'\n"
                    + "LOG_FILE='" + logFile + "'\n"
                    // 注意：外部预览入口固定为 /ws/{userId}/，但 Nginx 方案 A 会“剥离 /ws/{userId} 前缀后转发到上游”。
                    // - 对纯前端（vite dev/preview）：仍需要 base=/ws/{userId}/，保证浏览器请求的静态资源路径带 /ws 前缀
                    // - 对后端（server/start）：不应要求项目支持 basePath，默认用 "/"（否则上游会在 /ws 下挂载导致访问 / 404）
                    + "BASE_PATH_ROOT='/'\n"
                    + "RUN_SCRIPT='" + (selectedScript == null ? "start" : selectedScript.replace("'", "")) + "'\n"
                    + "echo \"[preview] start at $(date -Is)\" >>\"$LOG_FILE\" 2>&1\n"
                    + "cd \"$APP_DIR\" >>\"$LOG_FILE\" 2>&1 || true\n"
                    + "if [ ! -f package.json ]; then\n"
                    + "  pkg=$(find \"$APP_DIR\" -maxdepth 2 -type f -name package.json 2>/dev/null | head -n 1 || true)\n"
                    + "  if [ -n \"$pkg\" ]; then APP_DIR=$(dirname \"$pkg\"); cd \"$APP_DIR\" >>\"$LOG_FILE\" 2>&1; fi\n"
                    + "fi\n"
                    + "if [ ! -f package.json ]; then echo \"package.json not found: $APP_DIR\" >>\"$LOG_FILE\"; exit 2; fi\n"
                    + "if [ -n \"$NPM_CONFIG_REGISTRY\" ]; then npm config set registry \"$NPM_CONFIG_REGISTRY\" >/dev/null 2>&1 || true; fi\n"
                    + "if [ -z \"$NPM_CONFIG_REGISTRY\" ] && [ -n \"$npm_config_registry\" ]; then npm config set registry \"$npm_config_registry\" >/dev/null 2>&1 || true; fi\n"
                    + "reg=$(npm config get registry 2>/dev/null || true)\n"
                    + "if [ -n \"$reg\" ]; then echo \"[preview] npm registry: $reg\" >>\"$LOG_FILE\" 2>&1; fi\n"
                    + npmCacheSnippet
                    + "if [ ! -d node_modules ]; then echo \"[preview] npm install (include dev)...\" >>\"$LOG_FILE\"; npm install --include=dev >>\"$LOG_FILE\" 2>&1 || (echo \"[preview] npm install failed, retry with --legacy-peer-deps\" >>\"$LOG_FILE\"; npm install --include=dev --legacy-peer-deps >>\"$LOG_FILE\" 2>&1); fi\n"
                    + "if [ \"$RUN_SCRIPT\" = \"server\" ] && [ ! -x node_modules/.bin/tsx ]; then echo \"[preview] tsx not found, npm install (include dev)...\" >>\"$LOG_FILE\"; npm install --include=dev >>\"$LOG_FILE\" 2>&1 || (echo \"[preview] npm install failed, retry with --legacy-peer-deps\" >>\"$LOG_FILE\"; npm install --include=dev --legacy-peer-deps >>\"$LOG_FILE\" 2>&1); fi\n"
                    + "prune_npm_cache || true\n"
                    // 兼容项目脚本：
                    // - dev/preview：通常是前端(vite)监听 5173；不要导出 PORT，避免同仓库后端也读 PORT=5173 导致 EADDRINUSE
                    // - start/server：通常是后端入口，需要 PORT=5173 以匹配网关转发
                    + "export HOST=\"0.0.0.0\"\n"
                    + "if [ \"$RUN_SCRIPT\" = \"server\" ] || [ \"$RUN_SCRIPT\" = \"start\" ]; then export PORT=\"$PORT\"; fi\n"
                    + "BASE_PATH=\"$BASE_PATH_ROOT\"\n"
                    + "export BASE_PATH=\"$BASE_PATH\"\n"
                    // 对全栈/后端入口（start/server）：尽量走“生产模式”，避免 HTML 引用 /@vite/client、/src/* 等开发期绝对路径
                    + "if [ \"$RUN_SCRIPT\" = \"server\" ] || [ \"$RUN_SCRIPT\" = \"start\" ]; then\n"
                    + "  export NODE_ENV=production\n"
                    + "  echo \"[preview] NODE_ENV=$NODE_ENV\" >>\"$LOG_FILE\" 2>&1\n"
                    + "fi\n"
                    // 对 start/server：尽量先 build（若不存在 build 脚本则不报错）
                    + "if [ \"$RUN_SCRIPT\" = \"server\" ] || [ \"$RUN_SCRIPT\" = \"start\" ]; then\n"
                    + "  echo \"[preview] npm run build --if-present\" >>\"$LOG_FILE\" 2>&1\n"
                    + "  npm run build --if-present >>\"$LOG_FILE\" 2>&1 || true\n"
                    + "fi\n"
                    // 某些 dev 脚本（例如 concurrently）会调用 ps；精简镜像可能缺少 ps，这里提供最小 ps shim
                    + "if ! command -v ps >/dev/null 2>&1; then\n"
                    + "  RUN_DIR=$(dirname \"$PID_FILE\")\n"
                    + "  mkdir -p \"$RUN_DIR/bin\" || true\n"
                    + "  cat > \"$RUN_DIR/bin/ps\" <<'PS'\n"
                    + "#!/usr/bin/env sh\n"
                    + "# minimal ps: supports `ps -o pid --no-headers --ppid <PPID>`\n"
                    + "ppid=\"\"\n"
                    + "while [ $# -gt 0 ]; do\n"
                    + "  if [ \"$1\" = \"--ppid\" ]; then ppid=\"$2\"; shift 2; continue; fi\n"
                    + "  shift\n"
                    + "done\n"
                    + "[ -z \"$ppid\" ] && exit 0\n"
                    + "for f in /proc/[0-9]*/status; do\n"
                    + "  pid=$(echo \"$f\" | awk -F'/' '{print $(NF-1)}')\n"
                    + "  p=$(awk '/^PPid:/{print $2}' \"$f\" 2>/dev/null || true)\n"
                    + "  if [ \"$p\" = \"$ppid\" ]; then echo \"$pid\"; fi\n"
                    + "done\n"
                    + "exit 0\n"
                    + "PS\n"
                    + "  chmod +x \"$RUN_DIR/bin/ps\" || true\n"
                    + "  export PATH=\"$RUN_DIR/bin:$PATH\"\n"
                    + "fi\n"
                    + "export FUNAI_USER_ID='" + userId + "'\n"
                    + "export FUNAI_APP_ID='" + appId + "'\n"
                    + (props.getMongo() != null && props.getMongo().isEnabled()
                    ? props.getMongo().generateMongoEnvVars(appId)
                    : "")
                    + "echo \"[preview] npm run $RUN_SCRIPT on $PORT base=$BASE_PATH\" >>\"$LOG_FILE\" 2>&1\n"
                    + "TARGET_PORT_HEX=$(printf '%04X' \"$PORT\")\n"
                    + "inode=$(awk -v p=\":$TARGET_PORT_HEX\" 'toupper($2) ~ (p\"$\") && $4==\"0A\" {print $10; exit}' /proc/net/tcp 2>/dev/null || true)\n"
                    + "if [ -z \"$inode\" ]; then inode=$(awk -v p=\":$TARGET_PORT_HEX\" 'toupper($2) ~ (p\"$\") && $4==\"0A\" {print $10; exit}' /proc/net/tcp6 2>/dev/null || true); fi\n"
                    + "if [ -n \"$inode\" ]; then\n"
                    + "  echo \"[preview] port $PORT is in use (inode=$inode), trying to kill holder...\" >>\"$LOG_FILE\" 2>&1\n"
                    + "  for p in /proc/[0-9]*; do\n"
                    + "    pid=${p#/proc/}\n"
                    + "    for fd in $p/fd/*; do\n"
                    + "      link=$(readlink \"$fd\" 2>/dev/null || true)\n"
                    + "      if [ \"$link\" = \"socket:[$inode]\" ]; then kill -TERM \"$pid\" 2>/dev/null || true; fi\n"
                    + "    done\n"
                    + "  done\n"
                    + "  sleep 1\n"
                    + "  for p in /proc/[0-9]*; do\n"
                    + "    pid=${p#/proc/}\n"
                    + "    for fd in $p/fd/*; do\n"
                    + "      link=$(readlink \"$fd\" 2>/dev/null || true)\n"
                    + "      if [ \"$link\" = \"socket:[$inode]\" ]; then kill -KILL \"$pid\" 2>/dev/null || true; fi\n"
                    + "    done\n"
                    + "  done\n"
                    + "fi\n"
                    // 若选择的是 preview/dev，则尽量传入 vite 常用参数（不影响不识别参数的脚本：由脚本自己决定是否使用）
                    + "cmd=\"npm run $RUN_SCRIPT\"\n"
                    + "if [ \"$RUN_SCRIPT\" = \"preview\" ] || [ \"$RUN_SCRIPT\" = \"dev\" ]; then\n"
                    + "  script_line=$(node -p \"try{const s=require('./package.json').scripts||{}; s['$RUN_SCRIPT']||''}catch(e){''}\" 2>/dev/null || true)\n"
                    + "  # 如果脚本本身是 vite（而不是 concurrently/自定义 runner），才追加 --host/--port/--base 参数\n"
                    + "  echo \"$script_line\" | grep -qi \"\\bvite\\b\" && cmd=\"npm run $RUN_SCRIPT -- --host 0.0.0.0 --port $PORT --strictPort --base $BASE_PATH\" || true\n"
                    + "\n"
                    + "  # 兼容全栈项目常见写法：dev=concurrently \"npm run dev:client\" \"npm run dev:server\"\n"
                    + "  # 或 dev=concurrently \"npm run client\" \"npm run server:dev\"\n"
                    + "  # 这类脚本里 vite 往往藏在 client 脚本里，直接跑 npm run dev 无法注入 --base，导致浏览器模块脚本请求拿到 text/html（白屏）。\n"
                    + "  if echo \"$script_line\" | grep -qi \"\\bconcurrently\\b\"; then\n"
                    + "    client_script=$(node -p \"try{const s=require('./package.json').scripts||{}; s['dev:client']? 'dev:client' : (s['client']? 'client' : '')}catch(e){''}\" 2>/dev/null || true)\n"
                    + "    client_line=$(node -p \"try{const s=require('./package.json').scripts||{}; const k=(s['dev:client']? 'dev:client' : (s['client']? 'client' : '')); k? (s[k]||'') : ''}catch(e){''}\" 2>/dev/null || true)\n"
                    + "    has_dev_server=$(node -p \"try{const s=require('./package.json').scripts||{}; s['dev:server']? '1':'0'}catch(e){'0'}\" 2>/dev/null || echo 0)\n"
                    + "    has_server_dev=$(node -p \"try{const s=require('./package.json').scripts||{}; s['server:dev']? '1':'0'}catch(e){'0'}\" 2>/dev/null || echo 0)\n"
                    + "    if echo \"$client_line\" | grep -qi \"\\bvite\\b\"; then\n"
                    + "      if [ \"$has_dev_server\" = \"1\" ]; then\n"
                    + "        cmd=\"sh -c 'npm run dev:client -- --host 0.0.0.0 --port $PORT --strictPort --base $BASE_PATH & npm run dev:server; wait'\"\n"
                    + "      elif [ \"$has_server_dev\" = \"1\" ]; then\n"
                    + "        cmd=\"sh -c 'npm run client -- --host 0.0.0.0 --port $PORT --strictPort --base $BASE_PATH & npm run server:dev; wait'\"\n"
                    + "      else\n"
                    + "        if [ -n \"$client_script\" ]; then cmd=\"npm run $client_script -- --host 0.0.0.0 --port $PORT --strictPort --base $BASE_PATH\"; fi\n"
                    + "      fi\n"
                    + "    fi\n"
                    + "  fi\n"
                    + "fi\n"
                    + "setsid sh -c \"$cmd\" >>\"$LOG_FILE\" 2>&1 < /dev/null &\n"
                    + "pid=$!\n"
                    + "echo \"$pid\" > \"$PID_FILE\"\n"
                    + "printf '{\"appId\":" + appId + ",\"type\":\"START\",\"pid\":%s,\"startedAt\":%s,\"logPath\":\"" + logFile + "\"}' \"$pid\" \"$(date +%s)\" > \"$META_FILE\"\n";
        } else {
            // DEV（原有逻辑保持）
            innerScript = ""
                    + "set -e\n"
                    + "APP_DIR='" + containerAppDir + "'\n"
                    + "PORT='" + ws.getContainerPort() + "'\n"
                    + "PID_FILE='" + pidFile + "'\n"
                    + "META_FILE='" + metaFile + "'\n"
                    + "LOG_FILE='" + logFile + "'\n"
                    + (props.getMongo() != null && props.getMongo().isEnabled()
                    ? ""
                    + "export FUNAI_USER_ID='" + userId + "'\n"
                    + "export FUNAI_APP_ID='" + appId + "'\n"
                    + props.getMongo().generateMongoEnvVars(appId)
                    : "")
                    + "echo \"[dev-start] start at $(date -Is)\" >>\"$LOG_FILE\" 2>&1\n"
                    + "cd \"$APP_DIR\" >>\"$LOG_FILE\" 2>&1 || true\n"
                    + "if [ ! -f package.json ]; then\n"
                    + "  pkg=$(find \"$APP_DIR\" -maxdepth 2 -type f -name package.json 2>/dev/null | head -n 1 || true)\n"
                    + "  if [ -n \"$pkg\" ]; then\n"
                    + "    APP_DIR=$(dirname \"$pkg\")\n"
                    + "    echo \"[dev-start] detected project root: $APP_DIR\" >>\"$LOG_FILE\" 2>&1\n"
                    + "    cd \"$APP_DIR\" >>\"$LOG_FILE\" 2>&1\n"
                    + "  fi\n"
                    + "fi\n"
                    + "if [ ! -f package.json ]; then echo \"package.json not found: $APP_DIR\" >>\"$LOG_FILE\"; exit 2; fi\n"
                    + "if [ -n \"$NPM_CONFIG_REGISTRY\" ]; then npm config set registry \"$NPM_CONFIG_REGISTRY\" >/dev/null 2>&1 || true; fi\n"
                    + "if [ -z \"$NPM_CONFIG_REGISTRY\" ] && [ -n \"$npm_config_registry\" ]; then npm config set registry \"$npm_config_registry\" >/dev/null 2>&1 || true; fi\n"
                    + "reg=$(npm config get registry 2>/dev/null || true)\n"
                    + "if [ -n \"$reg\" ]; then echo \"[dev-start] npm registry: $reg\" >>\"$LOG_FILE\" 2>&1; fi\n"
                    + npmCacheSnippet
                    + "if [ ! -d node_modules ]; then echo \"[dev-start] npm install (include dev)...\" >>\"$LOG_FILE\"; npm install --include=dev >>\"$LOG_FILE\" 2>&1 || (echo \"[dev-start] npm install failed, retry with --legacy-peer-deps\" >>\"$LOG_FILE\"; npm install --include=dev --legacy-peer-deps >>\"$LOG_FILE\" 2>&1); fi\n"
                    + "prune_npm_cache || true\n"
                    + "echo \"[dev-start] npm run dev on $PORT\" >>\"$LOG_FILE\" 2>&1\n"
                    + "export CHOKIDAR_USEPOLLING=true\n"
                    + "export CHOKIDAR_INTERVAL=1000\n"
                    + "export WATCHPACK_POLLING=true\n"
                    + "echo \"[dev-start] watch: CHOKIDAR_USEPOLLING=$CHOKIDAR_USEPOLLING interval=$CHOKIDAR_INTERVAL\" >>\"$LOG_FILE\" 2>&1\n"
                    + "TARGET_PORT_HEX=$(printf '%04X' \"$PORT\")\n"
                    + "find_inode() {\n"
                    + "  local inode\n"
                    + "  inode=$(awk -v p=\":$TARGET_PORT_HEX\" 'toupper($2) ~ (p\"$\") && $4==\"0A\" {print $10; exit}' /proc/net/tcp 2>/dev/null || true)\n"
                    + "  if [ -z \"$inode\" ]; then\n"
                    + "    inode=$(awk -v p=\":$TARGET_PORT_HEX\" 'toupper($2) ~ (p\"$\") && $4==\"0A\" {print $10; exit}' /proc/net/tcp6 2>/dev/null || true)\n"
                    + "  fi\n"
                    + "  echo \"$inode\"\n"
                    + "}\n"
                    + "kill_by_inode() {\n"
                    + "  local inode=\"$1\"\n"
                    + "  [ -z \"$inode\" ] && return 0\n"
                    + "  echo \"[dev-start] port $PORT is in use (inode=$inode), trying to kill holder...\" >>\"$LOG_FILE\" 2>&1\n"
                    + "  for p in /proc/[0-9]*; do\n"
                    + "    pid=${p#/proc/}\n"
                    + "    for fd in $p/fd/*; do\n"
                    + "      link=$(readlink \"$fd\" 2>/dev/null || true)\n"
                    + "      if [ \"$link\" = \"socket:[$inode]\" ]; then\n"
                    + "        kill -TERM \"$pid\" 2>/dev/null || true\n"
                    + "      fi\n"
                    + "    done\n"
                    + "  done\n"
                    + "  sleep 1\n"
                    + "  for p in /proc/[0-9]*; do\n"
                    + "    pid=${p#/proc/}\n"
                    + "    for fd in $p/fd/*; do\n"
                    + "      link=$(readlink \"$fd\" 2>/dev/null || true)\n"
                    + "      if [ \"$link\" = \"socket:[$inode]\" ]; then\n"
                    + "        kill -KILL \"$pid\" 2>/dev/null || true\n"
                    + "      fi\n"
                    + "    done\n"
                    + "  done\n"
                    + "}\n"
                    + "inode=$(find_inode)\n"
                    + "if [ -n \"$inode\" ]; then kill_by_inode \"$inode\"; fi\n"
                    + "BASE='/'\n"
                    + "cmd=\"npm run dev\"\n"
                    + "script_line=$(node -p \"try{const s=require('./package.json').scripts||{}; s['dev']||''}catch(e){''}\" 2>/dev/null || true)\n"
                    + "if [ -n \"$script_line\" ]; then echo \"[dev-start] scripts.dev=$script_line\" >>\"$LOG_FILE\" 2>&1; fi\n"
                    + "echo \"$script_line\" | grep -qi \"\\bvite\\b\" && cmd=\"npm run dev -- --host 0.0.0.0 --port $PORT --strictPort --base $BASE\" || true\n"
                    + "if [ \"$cmd\" = \"npm run dev\" ]; then\n"
                    + "  # 兼容全栈项目：dev=concurrently \"npm run dev:client\" \"npm run dev:server\"\n"
                    + "  # 不要把 --host/--port/--base 直接传给 concurrently，否则参数不会透传给 vite。\n"
                    + "  if echo \"$script_line\" | grep -qi \"\\bconcurrently\\b\"; then\n"
                    + "    client_script=$(node -p \"try{const s=require('./package.json').scripts||{}; s['dev:client']? 'dev:client' : (s['client']? 'client' : '')}catch(e){''}\" 2>/dev/null || true)\n"
                    + "    client_line=$(node -p \"try{const s=require('./package.json').scripts||{}; const k=(s['dev:client']? 'dev:client' : (s['client']? 'client' : '')); k? (s[k]||'') : ''}catch(e){''}\" 2>/dev/null || true)\n"
                    + "    has_dev_server=$(node -p \"try{const s=require('./package.json').scripts||{}; s['dev:server']? '1':'0'}catch(e){'0'}\" 2>/dev/null || echo 0)\n"
                    + "    has_server_dev=$(node -p \"try{const s=require('./package.json').scripts||{}; s['server:dev']? '1':'0'}catch(e){'0'}\" 2>/dev/null || echo 0)\n"
                    + "    if echo \"$client_line\" | grep -qi \"\\bvite\\b\"; then\n"
                    + "      if [ \"$has_dev_server\" = \"1\" ]; then\n"
                    + "        cmd=\"sh -c 'npm run dev:client -- --host 0.0.0.0 --port $PORT --strictPort --base $BASE & npm run dev:server; wait'\"\n"
                    + "      elif [ \"$has_server_dev\" = \"1\" ]; then\n"
                    + "        cmd=\"sh -c 'npm run client -- --host 0.0.0.0 --port $PORT --strictPort --base $BASE & npm run server:dev; wait'\"\n"
                    + "      else\n"
                    + "        if [ -n \"$client_script\" ]; then cmd=\"npm run $client_script -- --host 0.0.0.0 --port $PORT --strictPort --base $BASE\"; fi\n"
                    + "      fi\n"
                    + "    fi\n"
                    + "  fi\n"
                    + "fi\n"
                    + "echo \"[dev-start] cmd=$cmd\" >>\"$LOG_FILE\" 2>&1\n"
                    + "setsid sh -c \"$cmd\" >>\"$LOG_FILE\" 2>&1 < /dev/null &\n"
                    + "pid=$!\n"
                    + "echo \"$pid\" > \"$PID_FILE\"\n"
                    + "printf '{\"appId\":" + appId + ",\"type\":\"DEV\",\"pid\":%s,\"startedAt\":%s,\"logPath\":\"" + logFile + "\"}' \"$pid\" \"$(date +%s)\" > \"$META_FILE\"\n";
        }

        // 外层脚本：做互斥 + 写入初始 meta（pid=null）+ 后台启动 innerScript。
        // 重要：DEV/START 的“长期 pid”应由 innerScript（setsid 启动的进程组）写入 current.json，
        // 否则外层 nohup/bash pid 很快退出，会导致 /run/status 误判为 DEAD，从而不返回 previewUrl。
        String initialState = "BUILD".equals(op) ? "BUILDING" : ("INSTALL".equals(op) ? "INSTALLING" : "STARTING");
        boolean managedLongRunning = "DEV".equals(op) || "START".equals(op);

        String script = ""
                + "set -e\n"
                + "RUN_DIR='" + runDir + "'\n"
                + "PID_FILE='" + pidFile + "'\n"
                + "META_FILE='" + metaFile + "'\n"
                + "LOG_FILE='" + logFile + "'\n"
                + "START_SH='" + startSh + "'\n"
                + "KEEP_LOGS='" + Math.max(0, props.getRunLogKeepPerType()) + "'\n"
                + "OP='" + op.toLowerCase() + "'\n"
                + "APP_ID='" + appId + "'\n"
                + "prune_run_logs() {\n"
                + "  [ -z \"$KEEP_LOGS\" ] && return 0\n"
                + "  [ \"$KEEP_LOGS\" -le 0 ] && return 0\n"
                + "  echo \"[prune] keep=$KEEP_LOGS op=$OP appId=$APP_ID pattern=$RUN_DIR/run-$OP-$APP_ID-*.log\" >>\"$LOG_FILE\" 2>&1 || true\n"
                + "  i=0\n"
                + "  for f in $(ls -1t \"$RUN_DIR\"/run-\"$OP\"-\"$APP_ID\"-*.log 2>/dev/null || true); do\n"
                + "    i=$((i+1))\n"
                + "    if [ \"$i\" -gt \"$KEEP_LOGS\" ]; then\n"
                + "      echo \"[prune] delete $f\" >>\"$LOG_FILE\" 2>&1 || true\n"
                + "      rm -f \"$f\" 2>/dev/null || true\n"
                + "    fi\n"
                + "  done\n"
                + "  echo \"[prune] done\" >>\"$LOG_FILE\" 2>&1 || true\n"
                + "  return 0\n"
                + "}\n"
                + "mkdir -p \"$RUN_DIR\"\n"
                + "if [ -f \"$PID_FILE\" ]; then\n"
                + "  pid=$(cat \"$PID_FILE\" 2>/dev/null || true)\n"
                + "  if [ -n \"$pid\" ] && kill -0 \"$pid\" 2>/dev/null; then\n"
                + "    echo \"ALREADY_RUNNING:$pid\"\n"
                + "    exit 42\n"
                + "  fi\n"
                + "fi\n"
                + "rm -f \"$PID_FILE\" || true\n"
                + "ts=$(date +%s)\n"
                + "echo '{\"appId\":" + appId + ",\"type\":\"" + op + "\",\"pid\":null,\"startedAt\":'\"$ts\"',\"logPath\":\"" + logFile + "\"}' > \"$META_FILE\"\n"
                + "cat > \"$START_SH\" <<'EOS'\n"
                + innerScript
                + "EOS\n"
                + "chmod +x \"$START_SH\" || true\n"
                + "nohup bash \"$START_SH\" >>\"$LOG_FILE\" 2>&1 &\n"
                + "pid=$!\n"
                + "echo \"$pid\" > \"$PID_FILE\"\n"
                + "prune_run_logs || true\n"
                + (managedLongRunning
                ? ""
                : "echo '{\"appId\":" + appId + ",\"type\":\"" + op + "\",\"pid\":'\"$pid\"',\"startedAt\":'\"$ts\"',\"logPath\":\"" + logFile + "\"}' > \"$META_FILE\"\n")
                + "echo \"LAUNCHED:" + initialState + "\"\n";

        CommandResult r = docker("exec", containerName, "bash", "-lc", script);
        if (r.getExitCode() == 42) {
            FunAiWorkspaceRunStatusResponse status = getRunStatus(userId);
            if (status.getMessage() == null || status.getMessage().isBlank()) {
                status.setMessage("已存在运行任务，请先停止或等待完成");
            }
            return status;
        }
        if (!r.isSuccess()) {
            throw new RuntimeException("启动任务失败(" + op + "): " + r.getOutput());
        }
        FunAiWorkspaceRunStatusResponse status = getRunStatus(userId);
        if (status.getMessage() == null || status.getMessage().isBlank()) {
            status.setMessage("已触发启动(" + op + ")，请轮询 /run/status 或通过 SSE /realtime/events 查看日志与状态");
        }
        return status;
    }

    @Override
    public FunAiWorkspaceRunStatusResponse stopRun(Long userId) {
        assertEnabled();
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        FunAiWorkspaceInfoResponse ws = ensureWorkspace(userId);

        String runDir = Paths.get(props.getContainerWorkdir(), "run").toString().replace("\\", "/");
        String pidFile = runDir + "/dev.pid";
        String metaFile = runDir + "/current.json";

        String script = ""
                + "set -e\n"
                + "RUN_DIR='" + runDir + "'\n"
                + "PID_FILE='" + pidFile + "'\n"
                + "META_FILE='" + metaFile + "'\n"
                + "if [ -f \"$PID_FILE\" ]; then\n"
                + "  pid=$(cat \"$PID_FILE\" 2>/dev/null || true)\n"
                + "  if [ -n \"$pid\" ]; then\n"
                + "    kill -TERM -- -\"$pid\" 2>/dev/null || true\n"
                + "    sleep 1\n"
                + "    kill -KILL -- -\"$pid\" 2>/dev/null || true\n"
                + "  fi\n"
                + "fi\n"
                + "rm -f \"$PID_FILE\" \"$META_FILE\" || true\n"
                + "echo STOPPED\n";

        CommandResult r = docker("exec", ws.getContainerName(), "bash", "-lc", script);
        if (!r.isSuccess()) {
            log.warn("stop run failed: userId={}, out={}", userId, r.getOutput());
        }
        return getRunStatus(userId);
    }

    @Override
    public FunAiWorkspaceRunStatusResponse getRunStatus(Long userId) {
        assertEnabled();
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        FunAiWorkspaceInfoResponse ws = ensureWorkspace(userId);
        Path hostRunDir = resolveHostWorkspaceDir(userId).resolve("run");
        Path metaPath = hostRunDir.resolve("current.json");

        FunAiWorkspaceRunStatusResponse resp = new FunAiWorkspaceRunStatusResponse();
        resp.setUserId(userId);
        resp.setHostPort(ws.getHostPort());
        resp.setContainerPort(ws.getContainerPort());

        if (Files.notExists(metaPath)) {
            resp.setState("IDLE");
            return resp;
        }

        try {
            FunAiWorkspaceRunMeta meta = objectMapper.readValue(Files.readString(metaPath, StandardCharsets.UTF_8), FunAiWorkspaceRunMeta.class);
            resp.setAppId(meta.getAppId());
            resp.setPid(meta.getPid());
            resp.setLogPath(meta.getLogPath());
            resp.setType(meta.getType());
            resp.setExitCode(meta.getExitCode());

            // pid 为空：说明 start 已触发，但后台脚本尚未写入 pid（npm install 进行中）
            if (meta.getPid() == null) {
                // 先确认容器是否真的在跑：否则会出现“容器已 EXITED，但 run/status 仍一直 STARTING”的假象
                String cStatus = queryContainerStatus(ws.getContainerName());
                if (!"RUNNING".equalsIgnoreCase(cStatus)) {
                    resp.setState("DEAD");
                    resp.setMessage("容器未运行（" + cStatus + "），请先 ensure 再 start；如频繁退出请检查 idle 回收或宿主机资源");
                    return resp;
                }

                String t = meta.getType() == null ? "" : meta.getType().trim().toUpperCase();
                long nowSec = System.currentTimeMillis() / 1000L;
                long startedAt = meta.getStartedAt() == null ? 0L : meta.getStartedAt();
                int timeoutSec = Math.max(30, props.getRunStartingTimeoutSeconds());
                if (startedAt > 0 && nowSec - startedAt >= timeoutSec) {
                    resp.setState("DEAD");
                    resp.setMessage(("BUILD".equals(t) ? "构建" : "启动") + "超时（" + timeoutSec + "s），请查看日志: " + (resp.getLogPath() == null ? "" : resp.getLogPath()));
                } else {
                    if ("BUILD".equals(t) || "INSTALL".equals(t)) {
                        // BUILD：pid=null 可能是“启动中（尚未写入 pid）”，也可能是“已结束且写入 exitCode/finishedAt”
                        if (meta.getExitCode() != null || meta.getFinishedAt() != null) {
                            Integer ec = meta.getExitCode();
                            if (ec != null) {
                                resp.setState(ec == 0 ? "SUCCESS" : "FAILED");
                                if (resp.getMessage() == null || resp.getMessage().isBlank()) {
                                    String okMsg = "INSTALL".equals(t) ? "依赖安装成功" : "构建成功";
                                    String failMsg = "INSTALL".equals(t) ? ("依赖安装失败(exitCode=" + ec + ")") : ("构建失败(exitCode=" + ec + ")");
                                    resp.setMessage(ec == 0 ? okMsg : (failMsg + "，请查看日志: " + (resp.getLogPath() == null ? "" : resp.getLogPath())));
                                }
                            } else {
                                resp.setState("UNKNOWN");
                                if (resp.getMessage() == null || resp.getMessage().isBlank()) {
                                    resp.setMessage(("INSTALL".equals(t) ? "依赖安装" : "构建") + "已结束，但未获取 exitCode；请查看日志: " + (resp.getLogPath() == null ? "" : resp.getLogPath()));
                                }
                            }
                        } else {
                            resp.setState("INSTALL".equals(t) ? "INSTALLING" : "BUILDING");
                            if (resp.getMessage() == null || resp.getMessage().isBlank()) {
                                resp.setMessage(("INSTALL".equals(t) ? "依赖安装中" : "构建中") + "，请稍后重试；可查看日志: " + (resp.getLogPath() == null ? "" : resp.getLogPath()));
                            }
                        }
                    } else {
                        resp.setState("STARTING");
                        if (resp.getMessage() == null || resp.getMessage().isBlank()) {
                            resp.setMessage("启动中（可能在 npm install），请稍后重试；可查看日志: " + (resp.getLogPath() == null ? "" : resp.getLogPath()));
                        }
                    }
                }
                return resp;
            }

            String t = meta.getType() == null ? "" : meta.getType().trim().toUpperCase();
            if ("BUILD".equals(t) || "INSTALL".equals(t)) {
                // BUILD/INSTALL：只关心进程是否存活，不做端口探测
                String check = ""
                        + "pid=" + meta.getPid() + "\n"
                        + "if kill -0 \"$pid\" 2>/dev/null; then\n"
                        + "  echo RUNNING\n"
                        + "else\n"
                        + "  echo DONE\n"
                        + "fi\n";
                CommandResult alive = docker("exec", ws.getContainerName(), "bash", "-lc", check);
                String s = alive.getOutput() == null ? "" : alive.getOutput().trim();
                if (s.contains("RUNNING")) {
                    resp.setState("INSTALL".equals(t) ? "INSTALLING" : "BUILDING");
                } else {
                    // build 已结束：根据 exitCode 给出 SUCCESS/FAILED（若 meta 未写 exitCode，则 UNKNOWN）
                    Integer ec = meta.getExitCode();
                    if (ec != null) {
                        resp.setState(ec == 0 ? "SUCCESS" : "FAILED");
                        if (resp.getMessage() == null || resp.getMessage().isBlank()) {
                            String okMsg = "INSTALL".equals(t) ? "依赖安装成功" : "构建成功";
                            String failMsg = "INSTALL".equals(t) ? ("依赖安装失败(exitCode=" + ec + ")") : ("构建失败(exitCode=" + ec + ")");
                            resp.setMessage(ec == 0 ? okMsg : (failMsg + "，请查看日志: " + (resp.getLogPath() == null ? "" : resp.getLogPath())));
                        }
                    } else {
                        resp.setState("UNKNOWN");
                        if (resp.getMessage() == null || resp.getMessage().isBlank()) {
                            resp.setMessage(("INSTALL".equals(t) ? "依赖安装" : "构建") + "已结束，但未获取 exitCode；请查看日志: " + (resp.getLogPath() == null ? "" : resp.getLogPath()));
                        }
                    }
                }
            } else {
                // DEV/START：进程存活校验（容器内） + 端口就绪判定（/dev/tcp，无需依赖 curl/ss）
                String check = ""
                        + "pid=" + meta.getPid() + "\n"
                        + "port=" + ws.getContainerPort() + "\n"
                        + "if kill -0 \"$pid\" 2>/dev/null; then\n"
                        + "  (echo > /dev/tcp/127.0.0.1/$port) >/dev/null 2>&1 && echo RUNNING || echo STARTING\n"
                        + "else\n"
                        + "  echo DEAD\n"
                        + "fi\n";
                CommandResult alive = docker("exec", ws.getContainerName(), "bash", "-lc", check);
                String s = alive.getOutput() == null ? "" : alive.getOutput().trim();
                if (s.contains("RUNNING")) {
                    resp.setState("RUNNING");
                    resp.setPreviewUrl(buildPreviewUrl(ws));
                } else if (s.contains("STARTING")) {
                    resp.setState("STARTING");
                } else {
                    resp.setState("DEAD");
                    if (resp.getMessage() == null || resp.getMessage().isBlank()) {
                        resp.setMessage("预览进程已退出或未成功启动，请查看日志: " + (resp.getLogPath() == null ? "/workspace/run/dev.log" : resp.getLogPath()));
                    }
                }
            }

            // 诊断（仅 DEV/START）：containerPort 当前监听进程 pid（用于排查端口被旧进程占用，导致 previewUrl 指向旧内容）
            if (!"BUILD".equals(t)) {
                Long listenPid = tryGetListenPid(ws.getContainerName(), ws.getContainerPort());
                resp.setPortListenPid(listenPid);
                // 注意：current.json 的 pid 是 setsid/sh 的“进程组组长”，真正监听端口的通常是 node 子进程
                // 因此这里用“端口监听进程的 pgrp 是否等于 current.json pid”来判断是否同一次 run
                if (listenPid != null && resp.getPid() != null) {
                    Long listenPgrp = tryGetProcessGroupId(ws.getContainerName(), listenPid);
                    if (listenPgrp != null && !listenPgrp.equals(resp.getPid())) {
                        String warn = "诊断：containerPort=" + ws.getContainerPort()
                                + " 当前监听 pid=" + listenPid + "(pgrp=" + listenPgrp + ")，但 current.json pid(pgrp leader)=" + resp.getPid()
                                + "；预览可能命中旧进程/旧项目。建议先 stopRun 再 startDev。";
                        if (resp.getMessage() == null || resp.getMessage().isBlank()) resp.setMessage(warn);
                        else resp.setMessage(resp.getMessage() + "；" + warn);
                    }
                }
            }

            return resp;
        } catch (Exception e) {
            log.warn("read run meta failed: userId={}, error={}", userId, e.getMessage());
            resp.setState("UNKNOWN");
            resp.setMessage("读取运行状态失败: " + e.getMessage());
            return resp;
        }
    }

    @Override
    public void clearRunLog(Long userId, Long appId) {
        assertEnabled();
        if (userId == null || appId == null) {
            throw new IllegalArgumentException("userId/appId 不能为空");
        }
        if (!StringUtils.hasText(props.getHostRoot())) {
            throw new IllegalStateException("hostRoot 未配置，无法清除日志");
        }

        Path hostUserDir = resolveHostWorkspaceDir(userId);
        Path runDir = hostUserDir.resolve("run");
        Path metaPath = runDir.resolve("current.json");
        Path logPath = runDir.resolve("dev.log"); // 兼容旧逻辑：若 current.json 没有 logPath，则退回 dev.log

        // 防误清：若存在 current.json 且 appId 不匹配，则拒绝
        try {
            if (Files.exists(metaPath)) {
                FunAiWorkspaceRunMeta meta = objectMapper.readValue(Files.readString(metaPath, StandardCharsets.UTF_8), FunAiWorkspaceRunMeta.class);
                if (meta != null && meta.getAppId() != null && !meta.getAppId().equals(appId)) {
                    throw new IllegalArgumentException("当前正在运行其它应用(appId=" + meta.getAppId() + ")，请先停止或传入当前运行应用的 appId");
                }
                // 优先清理 current.json 指向的日志文件（每次任务独立日志）
                if (meta != null && meta.getLogPath() != null && !meta.getLogPath().isBlank()) {
                    Path p = resolveHostPathFromContainerPath(hostUserDir, meta.getLogPath());
                    if (p != null) logPath = p;
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception ignore) {
            // current.json 解析失败时不阻塞清理（避免因为旧文件损坏导致无法自助清空日志）
        }

        ensureDir(runDir);
        try {
            Files.write(logPath, new byte[0], StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            throw new RuntimeException("清除日志失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将容器内路径（如 /workspace/run/xxx.log）映射为宿主机路径（{hostRoot}/{userId}/run/xxx.log）。
     * 若路径不符合预期，则返回 null。
     */
    private Path resolveHostPathFromContainerPath(Path hostUserDir, String containerPath) {
        try {
            if (hostUserDir == null || containerPath == null) return null;
            String p = containerPath.trim();
            if (p.isEmpty()) return null;
            String workdir = props.getContainerWorkdir();
            if (!StringUtils.hasText(workdir)) workdir = "/workspace";
            workdir = workdir.trim();
            if (!p.startsWith(workdir)) return null;
            String rel = p.substring(workdir.length());
            while (rel.startsWith("/")) rel = rel.substring(1);
            if (rel.isEmpty()) return null;
            return hostUserDir.resolve(rel);
        } catch (Exception ignore) {
            return null;
        }
    }

    private void pruneRunLogs(Long userId, String type, int keep) {
        // keep<=0：不清理
        if (keep <= 0) return;
        if (userId == null || !StringUtils.hasText(type)) return;
        if (!StringUtils.hasText(props.getHostRoot())) return;

        String op = type.trim().toLowerCase();
        Path hostUserDir = resolveHostWorkspaceDir(userId);
        Path hostRunDir = hostUserDir.resolve("run");
        if (Files.notExists(hostRunDir) || !Files.isDirectory(hostRunDir)) return;

        // run/run-{type}-{appId}-{timestamp}.log
        // 这里按 userId + type 维度清理：run-{type}-*-{timestamp}.log
        String prefix = "run-" + op + "-";
        String suffix = ".log";

        class Item {
            final Path path;
            final long ts;
            Item(Path path, long ts) { this.path = path; this.ts = ts; }
        }

        List<Item> items = new ArrayList<>();
        try (var s = Files.list(hostRunDir)) {
            s.forEach(p -> {
                try {
                    if (p == null || !Files.isRegularFile(p)) return;
                    String name = p.getFileName().toString();
                    if (!name.startsWith(prefix) || !name.endsWith(suffix)) return;
                    String base = name.substring(0, name.length() - suffix.length());
                    int lastDash = base.lastIndexOf('-');
                    if (lastDash < 0 || lastDash + 1 >= base.length()) return;
                    String tsPart = base.substring(lastDash + 1);
                    long ts = Long.parseLong(tsPart);
                    items.add(new Item(p, ts));
                } catch (Exception ignore) {
                }
            });
        } catch (Exception ignore) {
            return;
        }
        if (items.size() <= keep) return;
        items.sort((a, b) -> Long.compare(b.ts, a.ts)); // newest first
        for (int i = keep; i < items.size(); i++) {
            try {
                Files.deleteIfExists(items.get(i).path);
            } catch (Exception ignore) {
            }
        }
    }

    /**
     * 查询容器内指定端口（LISTEN）对应的 pid。
     * 不依赖 ps/ss/lsof（精简镜像常缺失），通过 /proc/net/tcp(+tcp6) + /proc/<pid>/fd 反查 socket inode。
     */
    private Long tryGetListenPid(String containerName, Integer port) {
        if (containerName == null || containerName.isBlank() || port == null) return null;
        try {
            String script = ""
                    + "set -e\n"
                    + "port=" + port + "\n"
                    + "PORT_HEX=$(printf '%04X' \"$port\")\n"
                    + "inode=$(awk -v p=\":$PORT_HEX\" 'toupper($2) ~ (p\"$\") && $4==\"0A\" {print $10; exit}' /proc/net/tcp 2>/dev/null || true)\n"
                    + "if [ -z \"$inode\" ]; then inode=$(awk -v p=\":$PORT_HEX\" 'toupper($2) ~ (p\"$\") && $4==\"0A\" {print $10; exit}' /proc/net/tcp6 2>/dev/null || true); fi\n"
                    + "[ -z \"$inode\" ] && exit 0\n"
                    + "for p in /proc/[0-9]*; do\n"
                    + "  pid=${p#/proc/}\n"
                    + "  for fd in $p/fd/*; do\n"
                    + "    link=$(readlink \"$fd\" 2>/dev/null || true)\n"
                    + "    if [ \"$link\" = \"socket:[$inode]\" ]; then\n"
                    + "      echo \"$pid\"\n"
                    + "      exit 0\n"
                    + "    fi\n"
                    + "  done\n"
                    + "done\n"
                    + "exit 0\n";
            CommandResult r = docker("exec", containerName, "bash", "-lc", script);
            if (r == null || r.getOutput() == null) return null;
            // podman-docker 可能在 stdout 打印告警，这里取最后一个非空行
            String out = normalizeDockerCliOutput(r.getOutput());
            if (out.isBlank()) return null;
            if (!out.matches("\\d+")) return null;
            return Long.parseLong(out);
        } catch (Exception ignore) {
            return null;
        }
    }

    private Long tryGetProcessGroupId(String containerName, Long pid) {
        if (containerName == null || containerName.isBlank() || pid == null || pid <= 0) return null;
        try {
            String script = ""
                    + "pid=" + pid + "\n"
                    + "awk '{print $5}' /proc/$pid/stat 2>/dev/null || true\n";
            CommandResult r = docker("exec", containerName, "bash", "-lc", script);
            if (r == null || r.getOutput() == null) return null;
            String out = normalizeDockerCliOutput(r.getOutput());
            if (out.isBlank()) return null;
            if (!out.matches("\\d+")) return null;
            return Long.parseLong(out);
        } catch (Exception ignore) {
            return null;
        }
    }

    private String buildPreviewUrl(FunAiWorkspaceInfoResponse ws) {
        String base = props.getPreviewBaseUrl();
        if (base == null) return null;
        base = base.trim();
        if (base.isEmpty()) return null;
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (!base.contains("://")) {
            base = "http://" + base;
        }
        String prefix = props.getPreviewPathPrefix();
        if (prefix == null || prefix.isBlank()) {
            prefix = "/ws";
        }
        prefix = prefix.trim();
        if (!prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }
        if (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        if (ws.getUserId() == null) return null;
        return base + prefix + "/" + ws.getUserId() + "/";
    }

    @Override
    public Path exportAppAsZip(Long userId, Long appId) {
        assertEnabled();
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (appId == null) {
            throw new IllegalArgumentException("appId 不能为空");
        }
        assertAppOwned(userId, appId);

        // 确保 workspace 与 app 目录存在
        ensureWorkspace(userId);
        FunAiWorkspaceProjectDirResponse dir = ensureAppDir(userId, appId);
        Path hostAppDir = Paths.get(dir.getHostAppDir());
        if (Files.notExists(hostAppDir) || !Files.isDirectory(hostAppDir)) {
            throw new IllegalArgumentException("应用目录不存在: " + hostAppDir);
        }

        Path runDir = resolveHostWorkspaceDir(userId).resolve("run");
        ensureDir(runDir);

        String fileName = "app_" + appId + "_" + System.currentTimeMillis() + ".zip";
        Path zipPath = runDir.resolve(fileName);

        try (OutputStream os = Files.newOutputStream(zipPath, StandardOpenOption.CREATE_NEW)) {
            ZipUtils.zipDirectory(hostAppDir, os);
        } catch (Exception e) {
            try {
                Files.deleteIfExists(zipPath);
            } catch (Exception ignore) {
            }
            throw new RuntimeException("打包失败: " + e.getMessage(), e);
        }
        return zipPath;
    }

    @Override
    public FunAiWorkspaceFileTreeResponse listFileTree(Long userId, Long appId, String path, Integer maxDepth, Integer maxEntries) {
        assertEnabled();
        assertAppOwned(userId, appId);
        FunAiWorkspaceProjectDirResponse dir = ensureAppDirHostOnly(userId, appId);
        Path root = Paths.get(dir.getHostAppDir());

        int depth = maxDepth == null ? 6 : Math.max(0, Math.min(20, maxDepth));
        int limit = maxEntries == null ? 5000 : Math.max(1, Math.min(20000, maxEntries));

        Path start = resolveSafePath(root, path, true);
        if (Files.notExists(start)) {
            // 空目录树
            FunAiWorkspaceFileTreeResponse resp = new FunAiWorkspaceFileTreeResponse();
            resp.setUserId(userId);
            resp.setAppId(appId);
            resp.setRootPath(normalizeRelPath(root, start));
            resp.setMaxDepth(depth);
            resp.setMaxEntries(limit);
            resp.setNodes(List.of());
            return resp;
        }
        if (!Files.isDirectory(start)) {
            throw new IllegalArgumentException("不是目录: " + normalizeRelPath(root, start));
        }

        Set<String> ignores = defaultIgnoredNames();
        Counter counter = new Counter(limit);
        List<FunAiWorkspaceFileNode> nodes = listDirRecursive(root, start, depth, ignores, counter);

        FunAiWorkspaceFileTreeResponse resp = new FunAiWorkspaceFileTreeResponse();
        resp.setUserId(userId);
        resp.setAppId(appId);
        resp.setRootPath(normalizeRelPath(root, start));
        resp.setMaxDepth(depth);
        resp.setMaxEntries(limit);
        resp.setNodes(nodes);
        return resp;
    }

    @Override
    public FunAiWorkspaceFileReadResponse readFileContent(Long userId, Long appId, String path) {
        assertEnabled();
        assertAppOwned(userId, appId);
        FunAiWorkspaceProjectDirResponse dir = ensureAppDirHostOnly(userId, appId);
        Path root = Paths.get(dir.getHostAppDir());
        Path file = resolveSafePath(root, path, false);
        if (Files.notExists(file) || Files.isDirectory(file)) {
            throw new IllegalArgumentException("文件不存在: " + normalizeRelPath(root, file));
        }
        try {
            long size = Files.size(file);
            if (size > MAX_TEXT_FILE_BYTES) {
                throw new IllegalArgumentException("文件过大（" + size + " bytes），暂不支持在线读取");
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            FunAiWorkspaceFileReadResponse resp = new FunAiWorkspaceFileReadResponse();
            resp.setUserId(userId);
            resp.setAppId(appId);
            resp.setPath(normalizeRelPath(root, file));
            resp.setContent(content);
            resp.setSize(size);
            resp.setLastModifiedMs(Files.getLastModifiedTime(file).toMillis());
            return resp;
        } catch (IOException e) {
            throw new RuntimeException("读取文件失败: " + e.getMessage(), e);
        }
    }

    @Override
    public FunAiWorkspaceFileReadResponse writeFileContent(Long userId, Long appId, String path, String content, boolean createParents, Long expectedLastModifiedMs, boolean forceWrite) {
        assertEnabled();
        assertAppOwned(userId, appId);
        FunAiWorkspaceProjectDirResponse dir = ensureAppDirHostOnly(userId, appId);
        Path root = Paths.get(dir.getHostAppDir());
        Path file = resolveSafePath(root, path, false);

        if (Files.exists(file) && Files.isDirectory(file)) {
            throw new IllegalArgumentException("目标是目录，无法写入文件: " + normalizeRelPath(root, file));
        }

        try {
            if (createParents) {
                Path parent = file.getParent();
                if (parent != null) ensureDir(parent);
            }

            // optimistic lock（forceWrite=true 时跳过校验）
            if (!forceWrite && expectedLastModifiedMs != null) {
                if (Files.exists(file)) {
                    long current = Files.getLastModifiedTime(file).toMillis();
                    if (current != expectedLastModifiedMs) {
                        throw new IllegalStateException("文件已被其他人修改（currentLastModifiedMs=" + current + "），请先重新拉取再保存");
                    }
                } else {
                    // must not exist
                    if (!(expectedLastModifiedMs == 0L || expectedLastModifiedMs == -1L)) {
                        throw new IllegalStateException("文件不存在，expectedLastModifiedMs=" + expectedLastModifiedMs + " 不匹配（可传 0/-1 表示必须不存在）");
                    }
                }
            }

            byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_TEXT_FILE_BYTES) {
                throw new IllegalArgumentException("内容过大（" + bytes.length + " bytes），请分拆或改为上传文件");
            }
            Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            // 写接口默认返回"轻响应"（不读回 content，减少 IO/CPU 与 JSON 体积）
            FunAiWorkspaceFileReadResponse resp = new FunAiWorkspaceFileReadResponse();
            resp.setUserId(userId);
            resp.setAppId(appId);
            resp.setPath(normalizeRelPath(root, file));
            resp.setContent(null);
            resp.setSize((long) bytes.length);
            resp.setLastModifiedMs(Files.getLastModifiedTime(file).toMillis());
            return resp;
        } catch (IllegalStateException e) {
            throw e;
        } catch (IOException e) {
            throw new RuntimeException("写入文件失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void createDirectory(Long userId, Long appId, String path, boolean createParents) {
        assertEnabled();
        assertAppOwned(userId, appId);
        FunAiWorkspaceProjectDirResponse dir = ensureAppDirHostOnly(userId, appId);
        Path root = Paths.get(dir.getHostAppDir());
        Path d = resolveSafePath(root, path, false);
        try {
            if (Files.exists(d) && !Files.isDirectory(d)) {
                throw new IllegalArgumentException("路径已存在且不是目录: " + normalizeRelPath(root, d));
            }
            if (createParents) {
                Files.createDirectories(d);
            } else {
                Files.createDirectory(d);
            }
        } catch (IOException e) {
            throw new RuntimeException("创建目录失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void deletePath(Long userId, Long appId, String path) {
        assertEnabled();
        assertAppOwned(userId, appId);
        FunAiWorkspaceProjectDirResponse dir = ensureAppDirHostOnly(userId, appId);
        Path root = Paths.get(dir.getHostAppDir());
        Path target = resolveSafePath(root, path, false);
        if (target.normalize().equals(root.normalize())) {
            throw new IllegalArgumentException("禁止删除 app 根目录");
        }
        if (Files.notExists(target)) return;
        try {
            ZipUtils.deleteDirectoryRecursively(target);
        } catch (Exception e) {
            throw new RuntimeException("删除失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void movePath(Long userId, Long appId, String fromPath, String toPath, boolean overwrite) {
        assertEnabled();
        assertAppOwned(userId, appId);
        FunAiWorkspaceProjectDirResponse dir = ensureAppDirHostOnly(userId, appId);
        Path root = Paths.get(dir.getHostAppDir());

        Path from = resolveSafePath(root, fromPath, false);
        Path to = resolveSafePath(root, toPath, false);
        if (Files.notExists(from)) {
            throw new IllegalArgumentException("源路径不存在: " + normalizeRelPath(root, from));
        }
        if (to.normalize().equals(root.normalize())) {
            throw new IllegalArgumentException("目标路径非法");
        }
        try {
            Path parent = to.getParent();
            if (parent != null) ensureDir(parent);
            if (Files.exists(to)) {
                if (!overwrite) {
                    throw new IllegalArgumentException("目标已存在: " + normalizeRelPath(root, to));
                }
                ZipUtils.deleteDirectoryRecursively(to);
            }
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("移动失败: " + e.getMessage(), e);
        }
    }

    @Override
    public FunAiWorkspaceFileReadResponse uploadFile(Long userId, Long appId, String path, MultipartFile file, boolean overwrite, boolean createParents) {
        assertEnabled();
        assertAppOwned(userId, appId);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }
        FunAiWorkspaceProjectDirResponse dir = ensureAppDirHostOnly(userId, appId);
        Path root = Paths.get(dir.getHostAppDir());
        Path target = resolveSafePath(root, path, false);
        try {
            if (Files.exists(target) && Files.isDirectory(target)) {
                throw new IllegalArgumentException("目标是目录: " + normalizeRelPath(root, target));
            }
            if (Files.exists(target) && !overwrite) {
                throw new IllegalArgumentException("目标已存在: " + normalizeRelPath(root, target));
            }
            if (createParents) {
                Path parent = target.getParent();
                if (parent != null) ensureDir(parent);
            }
            try (var is = file.getInputStream()) {
                Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return readFileContent(userId, appId, normalizeRelPath(root, target));
        } catch (IOException e) {
            throw new RuntimeException("上传失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Path downloadFile(Long userId, Long appId, String path) {
        assertEnabled();
        assertAppOwned(userId, appId);
        FunAiWorkspaceProjectDirResponse dir = ensureAppDirHostOnly(userId, appId);
        Path root = Paths.get(dir.getHostAppDir());
        Path file = resolveSafePath(root, path, false);
        if (Files.notExists(file) || Files.isDirectory(file)) {
            throw new IllegalArgumentException("文件不存在: " + normalizeRelPath(root, file));
        }
        return file;
    }

    private static class Counter {
        int limit;
        int used;
        Counter(int limit) { this.limit = limit; }
        boolean inc() { used++; return used <= limit; }
        boolean allow() { return used < limit; }
    }

    private Set<String> defaultIgnoredNames() {
        Set<String> s = new HashSet<>();
        s.add("node_modules");
        s.add(".git");
        s.add("dist");
        s.add("build");
        s.add(".next");
        s.add("target");
        // 缓存/内部元数据：前端文件树无需展示，也避免同步到前端造成噪音与性能浪费
        s.add(".npm-cache");
        s.add(".funai");
        return s;
    }

    /**
     * 在宿主机应用目录中查找 package.json（支持项目根不在 apps/{appId} 根目录的情况）。
     * @param hostAppDir apps/{appId} 目录
     * @param maxDepth 最大下探深度（默认与前端 open-editor 的 detectPackageJson 对齐：2）
     */
    private Path findPackageJson(Path hostAppDir, int maxDepth) {
        if (hostAppDir == null || Files.notExists(hostAppDir) || !Files.isDirectory(hostAppDir)) return null;
        int depth = Math.max(0, Math.min(10, maxDepth));
        Set<String> ignores = defaultIgnoredNames();
        try (var stream = Files.walk(hostAppDir, depth + 1)) {
            return stream
                    .filter(p -> p != null && Files.isRegularFile(p))
                    .filter(p -> "package.json".equalsIgnoreCase(p.getFileName().toString()))
                    .filter(p -> {
                        // ignore node_modules/.git 等目录中的 package.json
                        Path rel;
                        try {
                            rel = hostAppDir.normalize().relativize(p.normalize());
                        } catch (Exception e) {
                            rel = null;
                        }
                        if (rel == null) return true;
                        for (Path part : rel) {
                            if (part != null && ignores.contains(part.toString())) return false;
                        }
                        return true;
                    })
                    .findFirst()
                    .orElse(null);
        } catch (Exception ignore) {
            return null;
        }
    }

    /**
     * 为 run/preview 等“需要 scripts”的动作选择最合适的 package.json。
     *
     * <p>常见场景：全栈/monorepo 项目会存在多个 package.json（例如 apps/{appId}/package.json 与 apps/{appId}/server/package.json）。
     * Files.walk 的遍历顺序不稳定，容易误选到子包 package.json（没有 scripts），导致误报“未定义预览脚本”。</p>
     *
     * 选择策略：
     * - 优先 apps/{appId}/package.json（项目根）
     * - 其次：包含 scripts 字段的候选
     * - 再其次：回退到任意一个候选
     */
    private Path findPackageJsonForRun(Path hostAppDir, int maxDepth) {
        if (hostAppDir == null || Files.notExists(hostAppDir) || !Files.isDirectory(hostAppDir)) return null;

        // 1) 强优先：项目根 package.json
        Path rootPkg = hostAppDir.resolve("package.json");
        if (Files.exists(rootPkg) && Files.isRegularFile(rootPkg)) {
            return rootPkg;
        }

        int depth = Math.max(0, Math.min(10, maxDepth));
        Set<String> ignores = defaultIgnoredNames();
        List<Path> candidates;
        try (var stream = Files.walk(hostAppDir, depth + 1)) {
            candidates = stream
                    .filter(p -> p != null && Files.isRegularFile(p))
                    .filter(p -> "package.json".equalsIgnoreCase(p.getFileName().toString()))
                    .filter(p -> {
                        Path rel;
                        try {
                            rel = hostAppDir.normalize().relativize(p.normalize());
                        } catch (Exception e) {
                            rel = null;
                        }
                        if (rel == null) return true;
                        for (Path part : rel) {
                            if (part != null && ignores.contains(part.toString())) return false;
                        }
                        return true;
                    })
                    .toList();
        } catch (Exception ignore) {
            return null;
        }
        if (candidates == null || candidates.isEmpty()) return null;

        // 2) 优先选择包含 scripts 字段的候选
        for (Path p : candidates) {
            try {
                String raw = Files.readString(p, StandardCharsets.UTF_8);
                if (StringUtils.hasText(raw) && raw.contains("\"scripts\"")) {
                    return p;
                }
            } catch (Exception ignore) {
            }
        }

        // 3) 兜底：返回任意一个
        return candidates.get(0);
    }

    /**
     * run/start 前置校验：不允许隐式创建 apps/{appId} 目录，避免 userId/appId 误传造成宿主机垃圾目录。
     */
    private void assertAppReadyForRun(Long userId, Long appId, Path hostAppDir, String op) {
        if (userId == null || appId == null) {
            throw new IllegalArgumentException("userId/appId 不能为空");
        }
        if (hostAppDir == null || Files.notExists(hostAppDir) || !Files.isDirectory(hostAppDir)) {
            String action = "start";
            if (op != null && !op.isBlank()) {
                String x = op.trim();
                int idx = x.indexOf(':');
                if (idx > 0) x = x.substring(0, idx).trim();
                x = x.toUpperCase(Locale.ROOT);
                // 将内部 op 映射为对外 endpoint 名称
                if ("DEV".equals(x)) action = "start";
                else if ("BUILD".equals(x)) action = "build";
                else if ("INSTALL".equals(x)) action = "install";
                else if ("START".equals(x) || "PREVIEW".equals(x)) action = "preview";
                else action = x.toLowerCase(Locale.ROOT);
            }
            throw new IllegalArgumentException("应用目录不存在（appId 可能不存在或尚未导入代码）："
                    + "请先调用 /api/fun-ai/workspace/files/ensure-dir 或 /api/fun-ai/workspace/files/upload-zip，"
                    + "确认 apps/" + appId + " 下存在项目文件后，再执行 /api/fun-ai/workspace/run/" + action);
        }
        // 进一步兜底：绝大多数任务都依赖 package.json；提前给出更友好提示，避免盲跑写日志再失败。
        Path pkg = findPackageJsonForRun(hostAppDir, 2);
        if (pkg == null) {
            throw new IllegalArgumentException("未找到 package.json（最大深度=2）：请先导入/创建项目文件，再执行 run/start（或 build/install/preview）");
        }
    }

    private boolean hasNpmScript(Path packageJson, String scriptName) {
        if (packageJson == null || Files.notExists(packageJson) || !StringUtils.hasText(scriptName)) return false;
        try {
            String json = Files.readString(packageJson, StandardCharsets.UTF_8);
            if (!StringUtils.hasText(json)) return false;
            Map<?, ?> m = objectMapper.readValue(json, Map.class);
            Object scripts = m == null ? null : m.get("scripts");
            if (!(scripts instanceof Map<?, ?> sm)) return false;
            Object v = sm.get(scriptName);
            return v != null && StringUtils.hasText(String.valueOf(v));
        } catch (Exception ignore) {
            // 兜底：少数项目 package.json 可能包含重复 key/非常规格式，Jackson 解析失败会导致误判“没有脚本”。
            // 这里用正则在原始文本中做一次轻量探测，避免明明有 dev 脚本却被判定为不存在。
            try {
                String raw = Files.readString(packageJson, StandardCharsets.UTF_8);
                if (!StringUtils.hasText(raw)) return false;
                // 优先：在 scripts 对象内匹配（更精确）
                java.util.regex.Pattern p1 = java.util.regex.Pattern.compile(
                        "\"scripts\"\\s*:\\s*\\{[\\s\\S]*?\"" + java.util.regex.Pattern.quote(scriptName) + "\"\\s*:\\s*\""
                );
                if (p1.matcher(raw).find()) return true;

                // 再兜底：直接匹配顶层任意位置的精确 key（例如 "dev": "..."），避免 scripts 结构不标准导致误判。
                // 注意：这里匹配的是精确 key（不会把 "dev:server" 当成 "dev"）。
                java.util.regex.Pattern p2 = java.util.regex.Pattern.compile(
                        "\"" + java.util.regex.Pattern.quote(scriptName) + "\"\\s*:\\s*\""
                );
                return p2.matcher(raw).find();
            } catch (Exception ignore2) {
                return false;
            }
        }
    }

    /**
     * preview/部署的脚本选择策略：
     * - dev：优先（开发模式，适配全栈 concurrently + vite 注入）
     * - server：兜底（仅启动后端 API 的场景；很多项目不会在 / 返回页面）
     */
    private String pickPreviewScript(Path packageJson) {
        // 标准化：/run/preview 直接按 dev -> server 选择（不依赖 start/preview）
        if (hasNpmScript(packageJson, "dev")) return "dev";
        // 全栈一体项目常用：scripts.server 启动后端（例如 tsx/express）。
        // 注意：很多后端项目不会对 "/" 提供页面，因此放到最后兜底，避免“预览 URL 打开 404”的错觉。
        if (hasNpmScript(packageJson, "server")) return "server";
        return null;
    }

    /**
     * 从 package.json 原始文本中尽力提取 scripts 的 key 列表（仅用于诊断输出）。
     */
    private List<String> detectScriptKeysBestEffort(String rawJson) {
        if (!StringUtils.hasText(rawJson)) return List.of();
        try {
            // 先尝试快速 JSON 解析（如果成功，结果最准确）
            Map<?, ?> m = objectMapper.readValue(rawJson, Map.class);
            Object scripts = m == null ? null : m.get("scripts");
            if (scripts instanceof Map<?, ?> sm) {
                return sm.keySet().stream()
                        .filter(k -> k != null)
                        .map(k -> String.valueOf(k))
                        .sorted()
                        .toList();
            }
        } catch (Exception ignore) {
        }
        try {
            // 解析失败时用正则在 scripts 块中抓取 key
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("\"scripts\"\\s*:\\s*\\{([\\s\\S]*?)\\}\\s*,");
            java.util.regex.Matcher m = p.matcher(rawJson);
            String block = m.find() ? m.group(1) : rawJson;
            java.util.regex.Pattern pKey = java.util.regex.Pattern.compile("\"([^\"]+)\"\\s*:");
            java.util.regex.Matcher km = pKey.matcher(block);
            java.util.Set<String> out = new java.util.HashSet<>();
            while (km.find()) {
                String k = km.group(1);
                if (k != null && !k.isBlank()) out.add(k.trim());
            }
            return out.stream().sorted().toList();
        } catch (Exception ignore2) {
            return List.of();
        }
    }

    private List<FunAiWorkspaceFileNode> listDirRecursive(Path root, Path dir, int depth, Set<String> ignores, Counter counter) {
        if (depth < 0 || !counter.allow()) return List.of();
        try (var stream = Files.list(dir)) {
            List<Path> children = stream
                    .sorted(Comparator.comparing((Path p) -> !Files.isDirectory(p)).thenComparing(p -> p.getFileName().toString().toLowerCase()))
                    .toList();

            List<FunAiWorkspaceFileNode> out = new ArrayList<>();
            for (Path p : children) {
                if (!counter.allow()) break;
                String name = p.getFileName() == null ? "" : p.getFileName().toString();
                if (ignores.contains(name)) continue;

                FunAiWorkspaceFileNode n = new FunAiWorkspaceFileNode();
                n.setName(name);
                n.setPath(normalizeRelPath(root, p));
                n.setLastModifiedMs(safeLastModifiedMs(p));
                if (Files.isDirectory(p)) {
                    n.setType("DIR");
                    if (counter.inc() && depth > 0) {
                        n.setChildren(listDirRecursive(root, p, depth - 1, ignores, counter));
                    } else {
                        n.setChildren(List.of());
                    }
                } else {
                    n.setType("FILE");
                    n.setSize(safeSize(p));
                    counter.inc();
                }
                out.add(n);
            }
            return out;
        } catch (IOException e) {
            throw new RuntimeException("读取目录失败: " + e.getMessage(), e);
        }
    }

    private Long safeLastModifiedMs(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (Exception ignore) {
            return null;
        }
    }

    private Long safeSize(Path p) {
        try {
            return Files.size(p);
        } catch (Exception ignore) {
            return null;
        }
    }

    private Path resolveSafePath(Path root, String rel, boolean allowEmpty) {
        if (root == null) throw new IllegalArgumentException("root 不能为空");
        String r = rel == null ? "" : rel.trim();
        r = r.replace("\\", "/");
        while (r.startsWith("/")) r = r.substring(1);
        if (!allowEmpty && (r.isEmpty() || ".".equals(r))) {
            throw new IllegalArgumentException("path 不能为空");
        }
        if (r.contains("\0")) {
            throw new IllegalArgumentException("非法 path");
        }
        Path resolved = r.isEmpty() ? root : root.resolve(r);
        resolved = resolved.normalize();
        Path normalizedRoot = root.normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("非法 path（疑似越权访问）");
        }
        return resolved;
    }

    private String normalizeRelPath(Path root, Path p) {
        try {
            Path rp = root.normalize().relativize(p.normalize());
            String s = rp.toString().replace("\\", "/");
            return s.isEmpty() ? "." : s;
        } catch (Exception ignore) {
            return ".";
        }
    }

    @Override
    public void stopRunForIdle(Long userId) {
        if (userId == null) return;
        if (!props.isEnabled()) return;

        Path hostUserDir = resolveHostWorkspaceDir(userId);
        Path runDir = hostUserDir.resolve("run");
        Path pidPath = runDir.resolve("dev.pid");
        Path metaPath = runDir.resolve("current.json");
        if (Files.notExists(pidPath) && Files.notExists(metaPath)) {
            return;
        }

        String containerName = containerNameFromMetaOrDefault(hostUserDir, userId);
        String status = queryContainerStatus(containerName);
        Long pid = readPidFromRunFiles(pidPath, metaPath);

        try {
            // 若容器还在跑且 pid 可用，则尝试 kill；否则只清理 run 文件即可
            if ("RUNNING".equalsIgnoreCase(status) && pid != null && pid > 1) {
                docker("exec", containerName, "bash", "-lc",
                        "kill -TERM -- -" + pid + " 2>/dev/null || true; sleep 1; kill -KILL -- -" + pid + " 2>/dev/null || true");
            }
        } catch (Exception ignore) {
        } finally {
            try {
                Files.deleteIfExists(pidPath);
                Files.deleteIfExists(metaPath);
                Files.deleteIfExists(runDir.resolve("dev.log"));
            } catch (Exception ignore) {
            }
        }

    }

    @Override
    public void stopContainerForIdle(Long userId) {
        if (userId == null) return;
        if (!props.isEnabled()) return;

        Path hostUserDir = resolveHostWorkspaceDir(userId);
        String containerName = containerNameFromMetaOrDefault(hostUserDir, userId);
        String status = queryContainerStatus(containerName);
        if (!"RUNNING".equalsIgnoreCase(status)) {
            return;
        }
        try {
            docker("stop", containerName);
        } catch (Exception ignore) {
        }

    }

    @Override
    public FunAiWorkspaceStatusResponse removeWorkspaceContainer(Long userId) {
        assertEnabled();
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        Path hostUserDir = resolveHostWorkspaceDir(userId);
        String containerName = containerNameFromMetaOrDefault(hostUserDir, userId);

        // 1) 尽量先 stopRun 元数据（不拉起容器）
        try {
            stopRunForIdle(userId);
        } catch (Exception ignore) {
        }

        // 2) 删除容器（强制，含 podman conmon 异常兜底）
        tryRemoveContainer(containerName);

        // 3) 刷新 last-known：容器状态应为 NOT_CREATED
        FunAiWorkspaceStatusResponse resp = new FunAiWorkspaceStatusResponse();
        resp.setUserId(userId);
        resp.setContainerName(containerName);
        resp.setHostWorkspaceDir(hostUserDir.toString());
        resp.setContainerPort(props.getContainerPort());
        resp.setContainerStatus(queryContainerStatus(containerName));

        return resp;
    }

    @Override
    public void cleanupWorkspaceOnAppDeleted(Long userId, Long appId) {
        // 注意：此方法不做 DB 归属校验；由调用方（删除应用接口）保证已校验 app 归属
        if (userId == null || appId == null) return;
        if (props == null || !props.isEnabled()) return;
        if (!StringUtils.hasText(props.getHostRoot())) return;

        Path hostUserDir = resolveHostWorkspaceDir(userId);
        Path hostAppsDir = hostUserDir.resolve("apps");
        // 应用目录结构：{hostRoot}/{userId}/apps/{appId}
        Path hostAppDir = hostAppsDir.resolve(String.valueOf(appId));
        Path hostRunDir = hostUserDir.resolve("run");
        Path metaPath = hostRunDir.resolve("current.json");
        Path pidPath = hostRunDir.resolve("dev.pid");

        // 1) 如果删除的是当前运行 app：尽量 stopRun（仅当容器在 RUNNING）
        try {
            if (Files.exists(metaPath)) {
                FunAiWorkspaceRunMeta meta = objectMapper.readValue(Files.readString(metaPath, StandardCharsets.UTF_8), FunAiWorkspaceRunMeta.class);
                if (meta != null && meta.getAppId() != null && meta.getAppId().equals(appId)) {
                    String containerName = containerName(userId);
                    String cStatus = queryContainerStatus(containerName);
                    if ("RUNNING".equalsIgnoreCase(cStatus)) {
                        // 容器在跑：通过 stopRun 杀掉进程组并清理 run 文件
                        stopRun(userId);
                    } else {
                        // 容器不在跑：仅清理宿主机 run 元数据
                        try {
                            Files.deleteIfExists(pidPath);
                            Files.deleteIfExists(metaPath);
                        } catch (Exception ignore) {
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 清理失败不阻断删除流程
            log.warn("cleanup workspace run meta failed: userId={}, appId={}, err={}", userId, appId, e.getMessage());
        }

        // 2) 删除该 appId 对应的历史 run 日志（避免 run 目录无限累积）
        // 日志命名约定：run/run-{type}-{appId}-{timestamp}.log
        cleanupRunLogsForApp(hostRunDir, userId, appId);

        // 3) 删除 workspace app 目录（宿主机持久化）
        // node_modules 可能很大，且若仍有进程写入会导致 DirectoryNotEmpty。这里做“重试 + 失败隔离”，避免残留占用与后续误判。
        deleteDirBestEffort(hostAppDir, userId, appId);

        // 4) 若用户级容器处于“坏状态”（典型：podman conmon died -> ExitCode=-1），则 best-effort 删除容器，
        // 避免后续 ensure/start 因同名残留而反复失败；下次访问会自动重建。
        try {
            String containerName = containerNameFromMetaOrDefault(hostUserDir, userId);
            if (isContainerBroken(containerName)) {
                log.warn("workspace container looks broken, remove best-effort: userId={}, name={}", userId, containerName);
                tryRemoveContainer(containerName);
            }
        } catch (Exception ignore) {
        }
    }

    private void cleanupRunLogsForApp(Path hostRunDir, Long userId, Long appId) {
        if (hostRunDir == null || appId == null) return;
        try {
            if (Files.notExists(hostRunDir) || !Files.isDirectory(hostRunDir)) return;
        } catch (Exception ignore) {
            return;
        }

        String appStr = String.valueOf(appId);
        String mid = "-" + appStr + "-";
        int deleted = 0;
        int matched = 0;
        RuntimeException last = null;
        try (var stream = Files.list(hostRunDir)) {
            for (Path p : stream.toList()) {
                if (p == null) continue;
                String name = p.getFileName() == null ? "" : p.getFileName().toString();
                if (name.isEmpty()) continue;
                // 仅清理 run-*-{appId}-*.log（避免 appId 子串误删）
                if (!name.startsWith("run-")) continue;
                if (!name.endsWith(".log")) continue;
                if (!name.contains(mid)) continue;
                matched++;
                try {
                    if (Files.deleteIfExists(p)) deleted++;
                } catch (Exception e) {
                    last = new RuntimeException("delete run log failed: " + p + ", err=" + e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            last = new RuntimeException("list run dir failed: " + hostRunDir + ", err=" + e.getMessage(), e);
        }
        if (last != null) {
            log.warn("cleanup run logs failed: userId={}, appId={}, err={}", userId, appId, last.getMessage());
            throw last;
        }
        if (matched > 0) {
            log.info("cleanup run logs done: userId={}, appId={}, runDir={}, matched={}, deleted={}, pattern=run-*{}*.log",
                    userId, appId, hostRunDir, matched, deleted, mid);
        }
    }

    private void deleteDirBestEffort(Path dir, Long userId, Long appId) {
        if (dir == null) return;
        try {
            if (Files.notExists(dir)) return;
        } catch (Exception ignore) {
        }

        // 1) 先尝试多次递归删除（应对短时间内的并发写入）
        int maxAttempts = 3;
        for (int i = 1; i <= maxAttempts; i++) {
            try {
                if (Files.notExists(dir)) return;
                ZipUtils.deleteDirectoryRecursively(dir);
                if (Files.notExists(dir)) {
                    log.info("workspace app dir cleaned: userId={}, appId={}, dir={}, attempt={}", userId, appId, dir, i);
                    return;
            }
        } catch (Exception e) {
                log.warn("cleanup workspace app dir attempt failed: userId={}, appId={}, dir={}, attempt={}, err={}",
                        userId, appId, dir, i, e.getMessage());
                // 小延迟后重试
                try {
                    Thread.sleep(200L * i);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // 2) 仍失败：将目录改名隔离（Quarantine），避免 apps/{appId} 长期残留
        try {
            if (Files.notExists(dir)) return;
            Path parent = dir.getParent();
            if (parent == null) return;
            String newName = dir.getFileName() + ".deleted-" + System.currentTimeMillis();
            Path quarantined = parent.resolve(newName);
            Files.move(dir, quarantined);
            log.warn("workspace app dir quarantined (delete failed): userId={}, appId={}, from={}, to={}",
                    userId, appId, dir, quarantined);

            // 3) 再尝试清理隔离目录（不保证成功，只做 best-effort）
            try {
                ZipUtils.deleteDirectoryRecursively(quarantined);
                if (Files.notExists(quarantined)) {
                    log.info("workspace quarantined dir cleaned: userId={}, appId={}, dir={}", userId, appId, quarantined);
            } else {
                    log.warn("workspace quarantined dir still exists: userId={}, appId={}, dir={}", userId, appId, quarantined);
                }
            } catch (Exception e) {
                log.warn("cleanup quarantined dir failed: userId={}, appId={}, dir={}, err={}", userId, appId, quarantined, e.getMessage());
            }
        } catch (Exception e) {
            log.warn("workspace app dir still exists after cleanup (cannot quarantine): userId={}, appId={}, dir={}, err={}",
                    userId, appId, dir, e.getMessage(), e);
            // Quarantine 也失败：让调用方感知（否则控制面会误以为清理成功）
            throw new RuntimeException("workspace app dir delete/quarantine failed: dir=" + dir + ", err=" + e.getMessage(), e);
        }

        // 最终兜底：apps/{appId} 原路径不应残留；若仍存在，说明删除/隔离均失败
        try {
            if (Files.exists(dir)) {
                throw new RuntimeException("workspace app dir still exists after cleanup: " + dir);
            }
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException("workspace app dir cleanup check failed: " + e.getMessage(), e);
        }
    }

    private void assertEnabled() {
        if (!props.isEnabled()) {
            throw new IllegalStateException("workspace 功能未启用（funai.workspace.enabled=false）");
        }
    }

    private void assertAppOwned(Long userId, Long appId) {
        if (userId == null || appId == null) {
            throw new IllegalArgumentException("userId/appId 不能为空");
        }
        // workspace-node 模式：应用归属由 API 服务器（小机）控制面校验；Workspace 开发服务器（大机）仅负责执行（避免依赖业务 DB）
    }

    private String containerNameFromMetaOrDefault(Path hostUserDir, Long userId) {
        try {
            WorkspaceMeta meta = tryLoadMeta(hostUserDir);
            if (meta != null && StringUtils.hasText(meta.getContainerName())) {
                return meta.getContainerName();
            }
        } catch (Exception ignore) {
        }
        return containerName(userId);
    }

    private Long readPidFromRunFiles(Path pidPath, Path metaPath) {
        try {
            if (Files.exists(metaPath)) {
                FunAiWorkspaceRunMeta meta = objectMapper.readValue(Files.readString(metaPath, StandardCharsets.UTF_8), FunAiWorkspaceRunMeta.class);
                if (meta != null && meta.getPid() != null) {
                    return meta.getPid();
                }
            }
        } catch (Exception ignore) {
        }
        try {
            if (Files.exists(pidPath)) {
                String s = Files.readString(pidPath, StandardCharsets.UTF_8).trim();
                if (!s.isEmpty()) {
                    return Long.parseLong(s);
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private String sanitizePath(String p) {
        if (p == null) return null;
        return p.trim().replaceAll("^[\"']|[\"']$", "");
    }

    private Path resolveHostWorkspaceDir(Long userId) {
        String root = sanitizePath(props.getHostRoot());
        return Paths.get(root, String.valueOf(userId));
    }

    private void ensureDir(Path dir) {
        try {
            if (Files.notExists(dir)) {
                Files.createDirectories(dir);
            }
        } catch (IOException e) {
            throw new RuntimeException("创建目录失败: " + dir + ", error=" + e.getMessage(), e);
        }
    }

    private Path metaFile(Path hostUserDir) {
        return hostUserDir.resolve("workspace-meta.json");
    }

    private WorkspaceMeta tryLoadMeta(Path hostUserDir) {
        Path f = metaFile(hostUserDir);
        if (Files.notExists(f)) return null;
        try {
            return objectMapper.readValue(Files.readAllBytes(f), WorkspaceMeta.class);
        } catch (Exception e) {
            log.warn("读取 workspace meta 失败（将忽略并重建）: file={}, error={}", f, e.getMessage());
            return null;
        }
    }

    private WorkspaceMeta loadOrInitMeta(Long userId, Path hostUserDir) {
        WorkspaceMeta meta = tryLoadMeta(hostUserDir);
        if (meta != null && meta.getHostPort() != null && meta.getContainerPort() != null && StringUtils.hasText(meta.getContainerName())) {
            // 镜像允许随配置演进：若配置镜像与 meta 不一致，则更新 meta（后续 ensureContainerRunning 会按需重建容器）
            try {
                String desired = props.getImage();
                if (StringUtils.hasText(desired) && (meta.getImage() == null || !desired.equals(meta.getImage()))) {
                    meta.setImage(desired);
                    persistMeta(hostUserDir, meta);
                }
            } catch (Exception ignore) {
            }
            return meta;
        }

        WorkspaceMeta m = new WorkspaceMeta();
        m.setContainerPort(props.getContainerPort());
        m.setHostPort(allocateHostPort(userId));
        m.setImage(props.getImage());
        m.setContainerName(containerName(userId));
        m.setCreatedAt(System.currentTimeMillis());
        persistMeta(hostUserDir, m);
        return m;
    }

    /**
     * 仅用于 nginx auth_request：读取 workspace-meta.json 中的 hostPort（不做 ensure/start，避免每个静态资源请求都触发副作用）
     */
    public Integer getHostPortForNginx(Long userId) {
        if (userId == null) return null;
        Path hostUserDir = resolveHostWorkspaceDir(userId);
        WorkspaceMeta meta = tryLoadMeta(hostUserDir);
        if (meta == null) return null;
        return meta.getHostPort();
    }

    private void persistMeta(Path hostUserDir, WorkspaceMeta meta) {
        try {
            byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(meta);
            Files.write(metaFile(hostUserDir), bytes);
        } catch (Exception e) {
            log.warn("写入 workspace meta 失败（不影响后续流程）: dir={}, error={}", hostUserDir, e.getMessage());
        }
    }

    private String containerName(Long userId) {
        return props.getContainerNamePrefix() + userId;
    }

    private int allocateHostPort(Long userId) {
        int base = props.getHostPortBase();
        int scan = Math.max(1, props.getHostPortScanSize());
        int startOffset = (int) (Math.abs(userId) % scan);

        for (int i = 0; i < scan; i++) {
            int p = base + ((startOffset + i) % scan);
            if (isPortFree(p)) {
                return p;
            }
        }
        throw new IllegalStateException("无法分配可用端口（范围不足）：base=" + base + ", scan=" + scan);
    }

    private boolean isPortFree(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("0.0.0.0", port));
            return true;
        } catch (IOException ignore) {
            return false;
        }
    }

    private void ensureContainerRunning(Long userId, Path hostUserDir, WorkspaceMeta meta) {
        String name = meta.getContainerName();
        String networkName = props.getNetworkName();
        // Ensure we can pull private registry images (Harbor/ACR) before any container (re)create.
        ensureRegistryLogin(meta.getImage());

        String status = queryContainerStatus(name);
        // 镜像升级：若容器当前镜像与 meta 期望镜像不一致，则重建容器（确保新镜像能力生效，例如 mongosh）
        if (!"NOT_CREATED".equalsIgnoreCase(status)) {
            try {
                String desired = meta.getImage();
                String current = queryContainerImage(name);
                if (StringUtils.hasText(desired) && StringUtils.hasText(current) && !desired.equals(current)) {
                    log.warn("workspace container image changed, recreate: userId={}, name={}, currentImage={}, desiredImage={}",
                            userId, name, current, desired);
                    tryRemoveContainer(name);
                    status = "NOT_CREATED";
                }
            } catch (Exception ignore) {
            }
        }
        if ("RUNNING".equalsIgnoreCase(status)) {
            // 若启用 Mongo：确保 mongo 的 bind-mount 存在，否则数据会写到容器层导致“重启即丢”
            if (props.getMongo() != null && props.getMongo().isEnabled()) {
                boolean ok = hasExpectedMongoMounts(userId, name);
                if (!ok) {
                    log.warn("mongo mounts missing, recreate workspace container: userId={}, name={}", userId, name);
                    tryRemoveContainer(name);
                    status = "NOT_CREATED";
                }
            }
            if (StringUtils.hasText(networkName)) {
                ensureDockerNetwork(networkName);
                tryConnectContainerNetwork(networkName, name);
            }
            return;
        }
        if (!"NOT_CREATED".equalsIgnoreCase(status)) {
            // 容器存在但非 running：若启用 Mongo 且 mount 缺失，直接重建
            if (props.getMongo() != null && props.getMongo().isEnabled()) {
                boolean ok = hasExpectedMongoMounts(userId, name);
                if (!ok) {
                    log.warn("mongo mounts missing (not running), recreate workspace container: userId={}, name={}, status={}", userId, name, status);
                    tryRemoveContainer(name);
                    status = "NOT_CREATED";
                }
            }
        }
        if (!"NOT_CREATED".equalsIgnoreCase(status)) {
            // 容器存在但非 running：尝试启动
            CommandResult start = docker("start", name);
            if (start.isSuccess()) {
                if (StringUtils.hasText(networkName)) {
                    ensureDockerNetwork(networkName);
                    tryConnectContainerNetwork(networkName, name);
                }
                return;
            }
            log.warn("docker start failed: userId={}, name={}, status={}, out={}", userId, name, status, start.getOutput());

            // start 失败时（podman/docker 都可能发生），不要直接 docker run（会因为同名容器残留而失败）
            // 这里尽量清理同名容器后再重建。
            tryRemoveContainer(name);
        }

        // 不存在：创建并启动（长驻）
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("run");
        cmd.add("-d");
        cmd.add("--name");
        cmd.add(name);
        cmd.add("--restart=always");

        // 资源限制（可选）：不配置则保持历史行为
        if (StringUtils.hasText(props.getDockerMemory())) {
            cmd.add("--memory");
            cmd.add(props.getDockerMemory().trim());
        }
        if (StringUtils.hasText(props.getDockerMemorySwap())) {
            cmd.add("--memory-swap");
            cmd.add(props.getDockerMemorySwap().trim());
        }
        if (props.getDockerCpus() != null && props.getDockerCpus() > 0) {
            cmd.add("--cpus");
            cmd.add(String.valueOf(props.getDockerCpus()));
        }
        if (props.getPidsLimit() != null && props.getPidsLimit() > 0) {
            cmd.add("--pids-limit");
            cmd.add(String.valueOf(props.getPidsLimit()));
        }

        if (StringUtils.hasText(networkName)) {
            ensureDockerNetwork(networkName);
            cmd.add("--network");
            cmd.add(networkName);
        }
        cmd.add("-p");
        cmd.add(meta.getHostPort() + ":" + meta.getContainerPort());
        cmd.add("-v");
        cmd.add(hostUserDir.toString() + ":" + props.getContainerWorkdir());
        cmd.add("-w");
        cmd.add(props.getContainerWorkdir());

        // npm registry（推荐 Verdaccio）：注入为 env，脚本中不再写死 registry
        if (StringUtils.hasText(props.getNpmRegistry())) {
            cmd.add("-e");
            cmd.add("NPM_CONFIG_REGISTRY=" + props.getNpmRegistry());
            cmd.add("-e");
            cmd.add("npm_config_registry=" + props.getNpmRegistry());
        }

        // mongo 连接信息（独立服务器模式）
        if (props.getMongo() != null && props.getMongo().isEnabled()) {
            // 注入连接信息（容器启动时的环境变量）
            cmd.add("-e");
            cmd.add("FUNAI_USER_ID=" + userId);
            cmd.add("-e");
            cmd.add("MONGO_HOST=" + props.getMongo().getHost());
            cmd.add("-e");
            cmd.add("MONGO_PORT=" + props.getMongo().getPort());
            if (StringUtils.hasText(props.getMongo().getUsername())) {
                cmd.add("-e");
                cmd.add("MONGO_USERNAME=" + props.getMongo().getUsername());
            }
            if (StringUtils.hasText(props.getMongo().getPassword())) {
                cmd.add("-e");
                cmd.add("MONGO_PASSWORD=" + props.getMongo().getPassword());
            }
            if (StringUtils.hasText(props.getMongo().getAuthSource())) {
                cmd.add("-e");
                cmd.add("MONGO_AUTH_SOURCE=" + props.getMongo().getAuthSource());
            }
            cmd.add("-e");
            cmd.add("MONGO_DB_PREFIX=" + props.getMongo().getDbNamePrefix());
        }

        // proxy env
        if (StringUtils.hasText(props.getHttpProxy())) {
            cmd.add("-e");
            cmd.add("HTTP_PROXY=" + props.getHttpProxy());
        }
        if (StringUtils.hasText(props.getHttpsProxy())) {
            cmd.add("-e");
            cmd.add("HTTPS_PROXY=" + props.getHttpsProxy());
        }
        String noProxy = buildNoProxy(props.getNoProxy(), props.getNpmRegistry());
        if (StringUtils.hasText(noProxy)) {
            cmd.add("-e");
            cmd.add("NO_PROXY=" + noProxy);
            // 兼容：很多工具/镜像只认小写 no_proxy；npm 也支持 npm_config_noproxy
            cmd.add("-e");
            cmd.add("no_proxy=" + noProxy);
            cmd.add("-e");
            cmd.add("NPM_CONFIG_NOPROXY=" + noProxy);
            cmd.add("-e");
            cmd.add("npm_config_noproxy=" + noProxy);
        }

        cmd.add(meta.getImage());
        // keep-alive
        cmd.add("bash");
        cmd.add("-lc");
        cmd.add(buildContainerBootstrapCommand(userId));

        CommandResult r = commandRunner.run(Duration.ofSeconds(30), cmd);
        if (!r.isSuccess() && isContainerNameAlreadyInUse(r.getOutput(), name)) {
            // 典型场景：podman-docker 下同名容器（即使 EXITED）也会阻止 docker run --name
            // 尝试清理后重试一次，提升 open-editor/ensure 的鲁棒性。
            tryRemoveContainer(name);
            r = commandRunner.run(Duration.ofSeconds(30), cmd);
        }
        if (!r.isSuccess()) {
            throw new RuntimeException("创建 workspace 容器失败: userId=" + userId + ", out=" + r.getOutput());
        }
    }

    /**
     * Best-effort login to registry (Harbor) if credentials are provided.
     * - Prefer funai.workspace.registryUsername/registryPassword
     * - Fallback env: REGISTRY_USERNAME/REGISTRY_PASSWORD
     * - Legacy fallback env: ACR_USERNAME/ACR_PASSWORD
     */
    private void ensureRegistryLogin(String image) {
        try {
            String registry = extractRegistryHost(image);
            if (!StringUtils.hasText(registry)) return;

            String user = firstNonBlank(
                    props.getRegistryUsername(),
                    System.getenv("REGISTRY_USERNAME"),
                    System.getenv("ACR_USERNAME")
            );
            String pwd = firstNonBlank(
                    props.getRegistryPassword(),
                    System.getenv("REGISTRY_PASSWORD"),
                    System.getenv("ACR_PASSWORD")
            );
            if (!StringUtils.hasText(user) || !StringUtils.hasText(pwd)) return;

            List<String> cmd = List.of("docker", "login", registry, "-u", user, "--password-stdin");
            CommandResult r = commandRunner.run(Duration.ofSeconds(30), cmd, pwd);
            if (!r.isSuccess()) {
                log.warn("docker login failed: registry={}, user={}, out={}", registry, user, r.getOutput());
            }
        } catch (Exception e) {
            // best-effort
            log.debug("ensureRegistryLogin ignored: err={}", e.getMessage());
        }
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) return null;
        for (String v : vals) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return null;
    }

    /**
     * Extract registry host from image string.
     * For example:
     *  - 172.21.138.103/funaistudio/img:tag -> 172.21.138.103
     *  - docker.io/library/nginx:latest -> docker.io
     *  - nginx:latest -> null
     */
    private static String extractRegistryHost(String image) {
        if (image == null) return null;
        String s = image.trim();
        int slash = s.indexOf('/');
        if (slash <= 0) return null; // no registry host
        String host = s.substring(0, slash);
        // Heuristic: registry host usually contains '.' or ':' or is 'localhost'
        if (host.contains(".") || host.contains(":") || "localhost".equalsIgnoreCase(host)) {
            return host;
        }
        return null;
    }

    private boolean isContainerNameAlreadyInUse(String output, String containerName) {
        if (output == null) return false;
        String out = output.toLowerCase();
        // podman: the container name "xxx" is already in use ...
        // docker:  Conflict. The container name "/xxx" is already in use ...
        if (!out.contains("already in use") && !out.contains("is already in use")) return false;
        if (containerName == null || containerName.isBlank()) return true;
        return out.contains(containerName.toLowerCase());
    }

    private void tryRemoveContainer(String containerName) {
        if (!StringUtils.hasText(containerName)) return;
        CommandResult rm = docker("rm", "-f", containerName);
        if (rm.isSuccess()) return;

        String out = rm.getOutput() == null ? "" : rm.getOutput();
        // 典型 podman-docker 异常：conmon died / internal libpod error；此时 docker rm 可能删不掉，需要 podman cleanup + rm
        if (looksLikePodmanConmonError(out)) {
            CommandResult cleanup = podman("container", "cleanup", containerName);
            CommandResult prm = podman("rm", "-f", "--time", "0", containerName);
            if (prm.isSuccess()) return;
            log.warn("podman rm failed after cleanup: container={}, cleanupOut={}, rmOut={}",
                    containerName, cleanup.getOutput(), prm.getOutput());
            return;
        }

        // rm 失败通常不致命（比如本来就不存在），但这里至少留一条 debug 便于排查。
        log.debug("docker rm ignored: container={}, out={}", containerName, out);
    }

    private boolean isContainerBroken(String containerName) {
        if (!StringUtils.hasText(containerName)) return false;
        CommandResult r = docker("inspect", "-f", "{{.State.ExitCode}}::{{.State.Error}}", containerName);
        if (!r.isSuccess()) return false;
        String s = normalizeDockerCliOutput(r.getOutput());
        if (!StringUtils.hasText(s) || !s.contains("::")) return false;
        String[] parts = s.split("::", 2);
        String codeStr = parts[0] == null ? "" : parts[0].trim();
        String err = parts[1] == null ? "" : parts[1].trim();
        if ("-1".equals(codeStr)) return true;
        if (!err.isEmpty()) {
            String e = err.toLowerCase(Locale.ROOT);
            return e.contains("conmon") || e.contains("libpod") || e.contains("exit file");
        }
        return false;
    }

    private boolean looksLikePodmanConmonError(String output) {
        if (output == null) return false;
        String o = output.toLowerCase(Locale.ROOT);
        return o.contains("conmon")
                || o.contains("libpod")
                || o.contains("exit file")
                || o.contains("emulate docker cli using podman");
    }

    private CommandResult podman(String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("podman");
        for (String a : args) cmd.add(a);
        // best-effort: podman 可能不存在（真实 Docker 环境），此时返回非 0 并被上层忽略/记录
        return commandRunner.run(CMD_TIMEOUT, cmd);
    }

    /**
     * 将 noProxy 与 npmRegistry host 合并，避免漏配导致 npm 访问内部 registry（例如 verdaccio）走代理被 403。
     */
    private String buildNoProxy(String configured, String npmRegistry) {
        // 基础默认值（不强制覆盖配置，只做补齐）
        Set<String> s = new HashSet<>();
        addNoProxyToken(s, "localhost");
        addNoProxyToken(s, "127.0.0.1");
        addNoProxyToken(s, "host.containers.internal");

        // 合并用户配置
        if (StringUtils.hasText(configured)) {
            for (String part : configured.split(",")) {
                addNoProxyToken(s, part);
            }
        }

        // 从 npmRegistry 解析 host（如 http://verdaccio:4873 -> verdaccio）
        try {
            if (StringUtils.hasText(npmRegistry)) {
                String u = npmRegistry.trim();
                if (!u.contains("://")) u = "http://" + u;
                URI uri = URI.create(u);
                String host = uri.getHost();
                if (StringUtils.hasText(host)) {
                    addNoProxyToken(s, host);
                }
            }
        } catch (Exception ignore) {
        }

        if (s.isEmpty()) return null;
        return String.join(",", s);
    }

    private void addNoProxyToken(Set<String> set, String token) {
        if (set == null || token == null) return;
        String t = token.trim();
        if (t.isEmpty()) return;
        // 去掉可能的端口
        int idx = t.indexOf(':');
        if (idx > 0) t = t.substring(0, idx);
        if (!t.isEmpty()) set.add(t);
    }

    private boolean hasExpectedMongoMounts(Long userId, String containerName) {
        // 独立服务器模式：不再需要检查 mount
        return true;
    }

    private String queryContainerImage(String containerName) {
        CommandResult r = docker("inspect", "-f", "{{.Config.Image}}", containerName);
        if (!r.isSuccess()) return "";
        return normalizeDockerCliOutput(r.getOutput());
    }

    private boolean hasExpectedMount(String containerName, Path hostPath, String containerDest) {
        try {
            if (!StringUtils.hasText(containerName) || hostPath == null || !StringUtils.hasText(containerDest)) return true;
            CommandResult r = docker("inspect", "-f", "{{range .Mounts}}{{.Source}}::{{.Destination}}\n{{end}}", containerName);
            if (!r.isSuccess() || r.getOutput() == null) return true;
            String expectedSrc = hostPath.toString().trim();
            String expectedDst = containerDest.trim();
            for (String line : r.getOutput().split("\\r?\\n")) {
                String s = line == null ? "" : line.trim();
                if (s.isEmpty() || !s.contains("::")) continue;
                String[] parts = s.split("::", 2);
                String src = parts[0] == null ? "" : parts[0].trim();
                String dst = parts[1] == null ? "" : parts[1].trim();
                if (dst.equals(expectedDst)) {
                    return src.equals(expectedSrc);
                }
            }
            return false;
        } catch (Exception ignore) {
            return true;
        }
    }

    private void ensureDockerNetwork(String networkName) {
        if (!StringUtils.hasText(networkName)) return;
        CommandResult inspect = docker("network", "inspect", networkName);
        if (inspect.isSuccess()) return;
        CommandResult create = docker("network", "create", networkName);
        if (!create.isSuccess()) {
            log.warn("docker network create failed: network={}, out={}", networkName, create.getOutput());
        }
    }

    private void tryConnectContainerNetwork(String networkName, String containerName) {
        if (!StringUtils.hasText(networkName) || !StringUtils.hasText(containerName)) return;
        CommandResult r = docker("network", "connect", networkName, containerName);
        if (r.isSuccess()) return;
        String out = normalizeDockerCliOutput(r.getOutput());
        String lower = out == null ? "" : out.toLowerCase();
        if (lower.contains("already exists") || lower.contains("already connected")) {
            return;
        }
        // podman/dokcer 的差异提示/错误信息不影响主流程（容器依旧可用），这里仅打 debug，避免误伤
        log.debug("docker network connect ignored: network={}, container={}, out={}", networkName, containerName, r.getOutput());
    }

    private String buildContainerBootstrapCommand(Long userId) {
        // 独立服务器模式：容器不再需要启动 mongod，只需保持常驻
        return "while true; do sleep 3600; done";
    }

    private String queryContainerStatus(String containerName) {
        CommandResult exist = docker("inspect", "-f", "{{.State.Status}}", containerName);
        if (!exist.isSuccess()) {
            return "NOT_CREATED";
        }
        String s = normalizeDockerCliOutput(exist.getOutput());
        if (s.isEmpty()) return "UNKNOWN";
        if ("running".equalsIgnoreCase(s)) return "RUNNING";
        return s.toUpperCase();
    }

    /**
     * podman-docker 会把告警打印到 stdout：
     * "Emulate Docker CLI using podman. Create /etc/containers/nodocker to quiet msg."
     * 这里做一次清洗，只取最后一个非空行，避免把告警返回给前端。
     */
    private String normalizeDockerCliOutput(String output) {
        if (output == null) return "";
        String[] lines = output.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (!line.isEmpty()) {
                return line;
            }
        }
        return "";
    }

    private CommandResult docker(String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        for (String a : args) cmd.add(a);
        return commandRunner.run(CMD_TIMEOUT, cmd);
    }
}


