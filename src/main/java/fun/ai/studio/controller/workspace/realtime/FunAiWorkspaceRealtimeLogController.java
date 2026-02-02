package fun.ai.studio.controller.workspace.realtime;

import fun.ai.studio.workspace.WorkspaceActivityTracker;
import fun.ai.studio.workspace.WorkspaceProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
@RequestMapping("/api/fun-ai/workspace/realtime")
@Tag(name = "Fun AI Workspace 实时通道", description = "仅保留日志拉取接口（非实时）")
public class FunAiWorkspaceRealtimeLogController {
    private final WorkspaceProperties workspaceProperties;
    private final WorkspaceActivityTracker activityTracker;

    public FunAiWorkspaceRealtimeLogController(
            WorkspaceProperties workspaceProperties,
            WorkspaceActivityTracker activityTracker
    ) {
        this.workspaceProperties = workspaceProperties;
        this.activityTracker = activityTracker;
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
        if (userId == null || appId == null) {
            throw new IllegalArgumentException("userId/appId 不能为空");
        }
        if (activityTracker != null) {
            activityTracker.touch(userId);
        }

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

