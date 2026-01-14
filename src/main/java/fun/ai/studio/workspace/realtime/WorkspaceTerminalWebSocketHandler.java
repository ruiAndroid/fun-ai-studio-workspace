package fun.ai.studio.workspace.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.ai.studio.service.FunAiWorkspaceService;
import fun.ai.studio.workspace.WorkspaceActivityTracker;
import fun.ai.studio.workspace.WorkspaceProperties;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在线编辑器终端（最小可用版）：
 * - 连接：ws://host/api/fun-ai/workspace/ws/terminal?userId=..&appId=..
 * - 入站消息(JSON)：{type:"stdin", data:"ls\\n"} 或 {type:"exec", data:"npm -v"}
 * - 出站消息(JSON)：{type:"stdout", data:"..."} / {type:"error", data:"..."} / {type:"ready", data:"ok"}
 *
 * 说明：这里使用 docker exec -i 启动 bash，不分配 TTY（因此 resize 目前仅占位）。
 */
public class WorkspaceTerminalWebSocketHandler extends TextWebSocketHandler {
    private final FunAiWorkspaceService workspaceService;
    private final WorkspaceProperties workspaceProperties;
    private final WorkspaceActivityTracker activityTracker;
    private final ObjectMapper objectMapper;

    private final ExecutorService ioPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("ws-terminal-io");
        return t;
    });

    public WorkspaceTerminalWebSocketHandler(
            FunAiWorkspaceService workspaceService,
            WorkspaceProperties workspaceProperties,
            WorkspaceActivityTracker activityTracker,
            ObjectMapper objectMapper
    ) {
        this.workspaceService = workspaceService;
        this.workspaceProperties = workspaceProperties;
        this.activityTracker = activityTracker;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        TerminalSessionState st = new TerminalSessionState();
        session.getAttributes().put("TERM_STATE", st);

        Map<String, String> q = parseQuery(session.getUri());
        Long userId = parseLong(q.get("userId"));
        Long appId = parseLong(q.get("appId"));
        if (userId == null || appId == null) {
            send(session, msg("error", "missing userId/appId"));
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        // workspace-node 模式：应用归属由小机控制面校验；大机仅负责执行。

        // 确保容器/目录就绪（编辑器打开时应该已做过，但这里兜底）
        activityTracker.touch(userId);
        workspaceService.ensureAppDir(userId, appId);

        String containerName = (workspaceProperties == null ? "ws-u-" + userId : workspaceProperties.getContainerNamePrefix() + userId);
        String appDir = Paths.get(
                workspaceProperties == null ? "/workspace" : workspaceProperties.getContainerWorkdir(),
                "apps", String.valueOf(appId)
        ).toString().replace("\\", "/");

        ProcessBuilder pb = new ProcessBuilder("docker", "exec", "-i", containerName, "bash");
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        st.process = proc;
        st.userId = userId;
        st.containerName = containerName;
        st.containerAppDir = appDir;

        st.stdin = new BufferedOutputStream(proc.getOutputStream());
        InputStream stdout = new BufferedInputStream(proc.getInputStream());

        // 进入项目目录（失败则退回 /workspace）
        write(st, "cd " + shellEscape(appDir) + " 2>/dev/null || cd " + shellEscape(workspaceProperties == null ? "/workspace" : workspaceProperties.getContainerWorkdir()) + "\n");
        write(st, "export PS1='[ws]$ '\n");

        ioPool.submit(() -> pumpStdout(session, st, stdout));
        ioPool.submit(() -> waitForExit(session, st));
        send(session, msg("ready", "ok"));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        TerminalSessionState st = (TerminalSessionState) session.getAttributes().get("TERM_STATE");
        if (st == null || st.closed.get()) return;

        Map<?, ?> m;
        try {
            m = objectMapper.readValue(message.getPayload(), Map.class);
        } catch (Exception e) {
            // 兼容直接发纯文本
            write(st, message.getPayload() + "\n");
            return;
        }

        String type = m.get("type") == null ? "" : String.valueOf(m.get("type"));
        String data = m.get("data") == null ? "" : String.valueOf(m.get("data"));

        // 连接活跃：触发 touch（避免 idle 回收）
        try {
            if (st.userId != null) activityTracker.touch(st.userId);
        } catch (Exception ignore) {
        }

        switch (type) {
            case "stdin" -> write(st, data);
            case "exec" -> startExecJob(session, st, data);
            case "cancel" -> cancelExecJob(session, st);
            case "ctrl_c" -> ctrlC(session, st);
            case "resize" -> send(session, msg("resize", "ignored")); // 无 tty，先占位
            case "close" -> session.close(CloseStatus.NORMAL);
            default -> write(st, data.endsWith("\n") ? data : (data + "\n"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        TerminalSessionState st = (TerminalSessionState) session.getAttributes().get("TERM_STATE");
        if (st != null) {
            st.close();
        }
    }

    /**
     * 路径B增强：exec 任务模式（非TTY）
     * - 用 docker exec 另起一个 bash -lc，适合 npm install / git / npm run dev 等长任务
     * - 支持实时 stdout + exit + cancel（destroy）
     */
    private void startExecJob(WebSocketSession session, TerminalSessionState st, String cmd) {
        if (st == null || st.closed.get()) return;
        String c = cmd == null ? "" : cmd.trim();
        if (c.isEmpty()) {
            send(session, msg("error", "exec: command is empty"));
            return;
        }
        synchronized (st.execLock) {
            if (st.execProcess != null && st.execProcess.isAlive()) {
                send(session, msg("error", "exec: another job is running, send {type:'cancel'} first"));
                return;
            }
            try {
                String appDirEsc = shellEscape(st.containerAppDir == null ? "/workspace" : st.containerAppDir);
                String full = "cd " + appDirEsc + " && " + c;
                ProcessBuilder pb = new ProcessBuilder("docker", "exec", "-i", st.containerName, "bash", "-lc", full);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                st.execProcess = p;
                send(session, msg("exec_start", c));

                ioPool.submit(() -> {
                    try (InputStream in = new BufferedInputStream(p.getInputStream())) {
                        byte[] buf = new byte[4096];
                        int n;
                        while (!st.closed.get() && (n = in.read(buf)) >= 0) {
                            if (n == 0) continue;
                            String s = new String(buf, 0, n, StandardCharsets.UTF_8);
                            send(session, msg("exec_stdout", s));
                        }
                    } catch (Exception ignore) {
                    }
                });

                ioPool.submit(() -> {
                    int code;
                    try {
                        code = p.waitFor();
                    } catch (Exception e) {
                        code = 137;
                    }
                    send(session, msg("exec_exit", String.valueOf(code)));
                    synchronized (st.execLock) {
                        st.execProcess = null;
                    }
                });
            } catch (Exception e) {
                send(session, msg("error", "exec failed: " + e.getMessage()));
            }
        }
    }

    private void cancelExecJob(WebSocketSession session, TerminalSessionState st) {
        if (st == null) return;
        synchronized (st.execLock) {
            Process p = st.execProcess;
            if (p == null || !p.isAlive()) {
                send(session, msg("exec_cancel", "no running job"));
                return;
            }
            try {
                p.destroy();
                try {
                    Thread.sleep(200);
                } catch (Exception ignore) {
                }
                if (p.isAlive()) {
                    p.destroyForcibly();
                }
                send(session, msg("exec_cancel", "ok"));
            } catch (Exception e) {
                send(session, msg("error", "cancel failed: " + e.getMessage()));
            }
        }
    }

    private void ctrlC(WebSocketSession session, TerminalSessionState st) {
        // 非TTY下真正的 SIGINT 不可靠：
        // - 对 exec 任务：用 cancel 方式终止
        // - 对交互 stdin：尝试写入 ^C 字符（可能无效），并提示前端
        if (st == null) return;
        synchronized (st.execLock) {
            if (st.execProcess != null && st.execProcess.isAlive()) {
                cancelExecJob(session, st);
                return;
            }
        }
        write(st, "\u0003"); // ETX
        send(session, msg("ctrl_c", "sent (non-tty mode, may not interrupt running command)"));
    }

    private void pumpStdout(WebSocketSession session, TerminalSessionState st, InputStream in) {
        byte[] buf = new byte[4096];
        try {
            int n;
            while (!st.closed.get() && (n = in.read(buf)) >= 0) {
                if (n == 0) continue;
                String s = new String(buf, 0, n, StandardCharsets.UTF_8);
                send(session, msg("stdout", s));
            }
        } catch (Exception e) {
            if (!st.closed.get()) {
                try {
                    send(session, msg("error", "stdout error: " + e.getMessage()));
                } catch (Exception ignore) {
                }
            }
        }
    }

    private void waitForExit(WebSocketSession session, TerminalSessionState st) {
        try {
            Process p = st.process;
            if (p == null) return;
            int code = p.waitFor();
            send(session, msg("exit", String.valueOf(code)));
            try {
                session.close(CloseStatus.NORMAL);
            } catch (Exception ignore) {
            }
        } catch (Exception ignore) {
        } finally {
            st.close();
        }
    }

    private synchronized void write(TerminalSessionState st, String data) {
        try {
            if (st == null || st.closed.get() || st.stdin == null) return;
            st.stdin.write(data.getBytes(StandardCharsets.UTF_8));
            st.stdin.flush();
        } catch (Exception ignore) {
        }
    }

    private void send(WebSocketSession session, String json) {
        try {
            if (session != null && session.isOpen()) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (Exception ignore) {
        }
    }

    private String msg(String type, String data) {
        try {
            Map<String, Object> m = new HashMap<>();
            m.put("type", type);
            m.put("data", data);
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            return "{\"type\":\"" + type + "\",\"data\":\"" + (data == null ? "" : data.replace("\"", "\\\"")) + "\"}";
        }
    }

    private Map<String, String> parseQuery(URI uri) {
        Map<String, String> out = new HashMap<>();
        if (uri == null) return out;
        try {
            var params = UriComponentsBuilder.fromUri(uri).build().getQueryParams();
            for (String k : params.keySet()) {
                out.put(k, params.getFirst(k));
            }
        } catch (Exception ignore) {
        }
        return out;
    }

    private Long parseLong(String s) {
        try {
            if (s == null || s.isBlank()) return null;
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private String shellEscape(String s) {
        if (s == null) return "''";
        // 最简单的单引号 escape
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }

    private static class TerminalSessionState {
        Process process;
        OutputStream stdin;
        Long userId;
        String containerName;
        String containerAppDir;
        AtomicBoolean closed = new AtomicBoolean(false);
        final Object execLock = new Object();
        Process execProcess;

        void close() {
            if (!closed.compareAndSet(false, true)) return;
            // cancel exec job
            synchronized (execLock) {
                try {
                    if (execProcess != null) {
                        execProcess.destroy();
                        execProcess.destroyForcibly();
                    }
                } catch (Exception ignore) {
                }
                execProcess = null;
            }
            try {
                if (stdin != null) stdin.close();
            } catch (Exception ignore) {
            }
            try {
                if (process != null) process.destroy();
            } catch (Exception ignore) {
            }
            try {
                if (process != null) process.destroyForcibly();
            } catch (Exception ignore) {
            }
        }
    }
}


