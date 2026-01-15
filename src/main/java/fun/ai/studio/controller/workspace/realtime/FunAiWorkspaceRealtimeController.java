package fun.ai.studio.controller.workspace.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.ai.studio.common.Result;
import fun.ai.studio.service.FunAiWorkspaceService;
import fun.ai.studio.entity.response.FunAiWorkspaceRunStatusResponse;
import fun.ai.studio.workspace.WorkspaceActivityTracker;
import fun.ai.studio.workspace.WorkspaceProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api/fun-ai/workspace/realtime")
@Tag(name = "Fun AI Workspace 实时通道", description = "在线编辑器实时：SSE（状态/日志），WS 终端请看 /doc/workspace-realtime.md")
public class FunAiWorkspaceRealtimeController {
    private final FunAiWorkspaceService funAiWorkspaceService;
    private final WorkspaceActivityTracker activityTracker;
    private final WorkspaceProperties workspaceProperties;
    private final ObjectMapper objectMapper;

    public FunAiWorkspaceRealtimeController(
            FunAiWorkspaceService funAiWorkspaceService,
            WorkspaceActivityTracker activityTracker,
            WorkspaceProperties workspaceProperties,
            ObjectMapper objectMapper
    ) {
        this.funAiWorkspaceService = funAiWorkspaceService;
        this.activityTracker = activityTracker;
        this.workspaceProperties = workspaceProperties;
        this.objectMapper = objectMapper;
    }

    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            summary = "SSE：推送运行状态（轻量）",
            description = "用于在线编辑器减少轮询。会先做 appId 归属校验，再开始推送。事件：status/error（不再推送日志）。"
    )
    public ResponseEntity<SseEmitter> events(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @Parameter(description = "兼容参数：历史版本可传 withLog，但当前实现不推送日志（默认 false）")
            @RequestParam(defaultValue = "false") boolean withLog,
            @Parameter(description = "兼容参数：历史版本用于过滤日志类型，当前实现忽略") @RequestParam(required = false) String type
    ) {
        // workspace-node 模式：应用归属由 API 服务器（小机）控制面校验；Workspace 开发服务器（大机）仅负责执行与推送
        if (userId == null || appId == null) {
            throw new IllegalArgumentException("userId/appId 不能为空");
        }

        activityTracker.touch(userId);

        // 不设置超时（由前端主动断开/重连）
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean closed = new AtomicBoolean(false);

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setName("ws-sse-" + userId + "-" + appId);
            t.setDaemon(true);
            return t;
        });

        AtomicLong lastHeartbeatMs = new AtomicLong(System.currentTimeMillis());
        AtomicLong lastStatusHash = new AtomicLong(0L);
        AtomicLong lastKeepAliveMs = new AtomicLong(System.currentTimeMillis());

        Runnable tick = () -> {
            if (closed.get()) return;
            try {
                long now = System.currentTimeMillis();

                // 1) 心跳：更新 activity（SSE 长连接本身就代表“在线”）
                if (now - lastHeartbeatMs.get() >= 30_000L) {
                    lastHeartbeatMs.set(now);
                    try {
                        activityTracker.touch(userId);
                    } catch (Exception ignore) {
                    }
                }

                // 2) status：仅在变化时推送（避免无意义刷屏/序列化开销）
                FunAiWorkspaceRunStatusResponse status = funAiWorkspaceService.getRunStatus(userId);
                long h = Objects.hash(status == null ? null : status.getState(),
                        status == null ? null : status.getAppId(),
                        status == null ? null : status.getType(),
                        status == null ? null : status.getPid(),
                        status == null ? null : status.getPreviewUrl(),
                        status == null ? null : status.getLogPath(),
                        status == null ? null : status.getMessage());
                if (h != lastStatusHash.get()) {
                    lastStatusHash.set(h);
                    String json = objectMapper.writeValueAsString(Result.success(status));
                    emitter.send(SseEmitter.event().name("status").data(json, MediaType.APPLICATION_JSON));
                }

                // 3) keep-alive：用 SSE comment 保持连接（前端不会收到 ping 事件）
                if (now - lastKeepAliveMs.get() >= 25_000L) {
                    lastKeepAliveMs.set(now);
                    // 注释行格式：": xxx\n\n"（EventSource 不会触发 message 事件）
                    emitter.send(":" + now + "\n\n");
                }
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data("events error: " + e.getMessage(), MediaType.TEXT_PLAIN));
                } catch (Exception ignore) {
                }
                safeClose(emitter, scheduler, closed);
            }
        };

        // 用 fixedDelay 避免 tick 发生卡顿时堆积（例如 docker exec 偶发变慢）
        // 仅推送 status，因此可把频率降低到 2s，显著降低 CPU/IO 压力
        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(tick, 0L, 2000L, TimeUnit.MILLISECONDS);

        emitter.onCompletion(() -> safeClose(emitter, scheduler, closed));
        emitter.onTimeout(() -> safeClose(emitter, scheduler, closed));
        emitter.onError((ex) -> safeClose(emitter, scheduler, closed));

        // 兜底：如果 emitter 被关闭，取消定时任务
        scheduler.scheduleAtFixedRate(() -> {
            if (closed.get()) {
                future.cancel(true);
            }
        }, 5, 5, TimeUnit.SECONDS);

        // nginx 反代 SSE 时容易被缓冲：建议通过 header 明确禁用缓冲/缓存
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CACHE_CONTROL, "no-cache");
        headers.add("X-Accel-Buffering", "no");
        return ResponseEntity.ok().headers(headers).body(emitter);
    }

    @GetMapping(path = "/log", produces = MediaType.TEXT_PLAIN_VALUE)
    @Operation(
            summary = "获取运行日志文件（非实时）",
            description = "直接返回对应日志文件内容（不做 SSE 增量推送）。优先按 type+appId 选择最新的日志文件；type 取 BUILD/INSTALL/PREVIEW。"
    )
    public ResponseEntity<StreamingResponseBody> getLog(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @Parameter(description = "日志类型（BUILD/INSTALL/PREVIEW），默认 PREVIEW") @RequestParam(required = false) String type,
            @Parameter(description = "可选：仅返回末尾 N 字节（用于大日志快速查看），默认 0=返回全量") @RequestParam(defaultValue = "0") long tailBytes
    ) {
        // 注意：返回类型必须是 ResponseEntity<StreamingResponseBody>，否则 Spring 会按“普通对象 + text/plain”
        // 走 HttpMessageConverter，导致报错：No converter for [Lambda] with preset Content-Type 'text/plain'
        if (userId == null || appId == null) {
            throw new IllegalArgumentException("userId/appId 不能为空");
        }
        activityTracker.touch(userId);

        String uiType = normalizeUiType(type);
        if (uiType == null) uiType = "PREVIEW";
        String op = mapUiTypeToOp(uiType); // BUILD/INSTALL/START
        Path logFile = findLatestLogFile(userId, appId, op);
        if (logFile == null || Files.notExists(logFile) || !Files.isRegularFile(logFile)) {
            throw new IllegalArgumentException("日志文件不存在");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CACHE_CONTROL, "no-cache");
        headers.add("X-Accel-Buffering", "no");
        headers.setContentType(MediaType.TEXT_PLAIN);
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + logFile.getFileName().toString() + "\"");

        if (tailBytes > 0) {
            long tb = tailBytes;
            StreamingResponseBody body = out -> {
                try (RandomAccessFile raf = new RandomAccessFile(logFile.toFile(), "r")) {
                    long size = raf.length();
                    long start = Math.max(0L, size - tb);
                    raf.seek(start);
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = raf.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        out.flush();
                    }
                }
            };
            return ResponseEntity.ok().headers(headers).body(body);
        }

        StreamingResponseBody body = out -> {
            try (InputStream in = Files.newInputStream(logFile)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            }
        };
        return ResponseEntity.ok().headers(headers).body(body);
    }

    @PostMapping("/log/clear")
    @Operation(
            summary = "清除当前运行任务日志",
            description = "清空当前运行任务的日志文件（优先使用 current.json.logPath 指向的文件；兼容旧逻辑回退 dev.log）。" +
                    "由于 realtime 接口在安全配置中为白名单，这里会做 appId 归属校验，并校验 current.json 的 appId 防止误清其它应用日志。"
    )
    public Result<String> clearLog(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId
    ) {
        try {
            if (userId == null || appId == null) {
                return Result.error("userId/appId 不能为空");
            }
            activityTracker.touch(userId);
            funAiWorkspaceService.clearRunLog(userId, appId);
            return Result.success("ok");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            return Result.error("clear log failed: " + e.getMessage());
        }
    }

    private Path findLatestLogFile(Long userId, Long appId, String opLowerOrStart) {
        try {
            String hostRoot = workspaceProperties == null ? null : workspaceProperties.getHostRoot();
            if (hostRoot == null || hostRoot.isBlank()) return null;
            String root = hostRoot.trim().replaceAll("^[\"']|[\"']$", "");
            Path hostUserDir = Paths.get(root, String.valueOf(userId));
            Path runDir = hostUserDir.resolve("run");
            if (Files.notExists(runDir) || !Files.isDirectory(runDir)) return null;

            String op = (opLowerOrStart == null ? "" : opLowerOrStart.trim().toLowerCase());
            if (op.isEmpty()) op = "start";
            String prefix = "run-" + op + "-" + appId + "-";
            String suffix = ".log";

            long bestTs = -1L;
            Path best = null;
            try (var s = Files.list(runDir)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    if (p == null || !Files.isRegularFile(p)) continue;
                    String name = p.getFileName().toString();
                    if (!name.startsWith(prefix) || !name.endsWith(suffix)) continue;
                    String base = name.substring(0, name.length() - suffix.length());
                    int lastDash = base.lastIndexOf('-');
                    if (lastDash < 0 || lastDash + 1 >= base.length()) continue;
                    long ts = Long.parseLong(base.substring(lastDash + 1));
                    if (ts > bestTs) {
                        bestTs = ts;
                        best = p;
                    }
                }
            }
            return best;
        } catch (Exception ignore) {
            return null;
        }
    }

    private String mapUiTypeToOp(String uiType) {
        // UI: BUILD/INSTALL/PREVIEW
        if (uiType == null) return "start";
        String s = uiType.trim().toUpperCase();
        if ("BUILD".equals(s)) return "build";
        if ("INSTALL".equals(s)) return "install";
        // PREVIEW 统一对应 START（受控预览）
        return "start";
    }

    private void safeClose(SseEmitter emitter, ScheduledExecutorService scheduler, AtomicBoolean closed) {
        if (!closed.compareAndSet(false, true)) return;
        try {
            emitter.complete();
        } catch (Exception ignore) {
        }
        try {
            scheduler.shutdownNow();
        } catch (Exception ignore) {
        }
    }

    /**
     * 前端只关心 BUILD/INSTALL/PREVIEW：这里把后端运行态 type（DEV/START/BUILD/INSTALL）映射成 UI type。
     * - START -> PREVIEW
     * - BUILD/INSTALL -> 原样
     * - DEV -> PREVIEW（若你们前端没有 DEV 概念，可把 dev 也当作预览入口）
     */
    private String normalizeUiType(String t) {
        if (t == null) return null;
        String s = t.trim().toUpperCase();
        if (s.isEmpty()) return null;
        if ("START".equals(s)) return "PREVIEW";
        if ("DEV".equals(s)) return "PREVIEW";
        if ("BUILD".equals(s)) return "BUILD";
        if ("INSTALL".equals(s)) return "INSTALL";
        if ("PREVIEW".equals(s)) return "PREVIEW";
        return s;
    }
}


