package fun.ai.studio.workspace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.OutputStreamWriter;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 轻量命令执行器（用于调用 docker/podman 等）
 */
@Component
public class CommandRunner {
    private static final Logger log = LoggerFactory.getLogger(CommandRunner.class);

    // Small, shared pool for reading process output without blocking caller threads.
    private final ExecutorService ioPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "cmd-io");
        t.setDaemon(true);
        return t;
    });

    public CommandResult run(Duration timeout, List<String> command) {
        return run(timeout, command, null);
    }

    /**
     * Run a command with optional stdin input (UTF-8).
     */
    public CommandResult run(Duration timeout, List<String> command, String stdin) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command 不能为空");
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            if (stdin != null) {
                try (Writer w = new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8)) {
                    w.write(stdin);
                    if (!stdin.endsWith("\n")) {
                        w.write("\n");
                    }
                    w.flush();
                } catch (Exception ignore) {
                }
            }

            StringBuilder out = new StringBuilder();
            CompletableFuture<Void> reader = CompletableFuture.runAsync(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        // Hard cap to keep payloads bounded (we only need last error context).
                        if (out.length() < 32_000) {
                            out.append(line).append('\n');
                        }
                    }
                } catch (Exception ignore) {
                }
            }, ioPool);

            long ms = timeout == null ? 0 : timeout.toMillis();
            boolean finished;
            if (ms <= 0) {
                p.waitFor();
                finished = true;
            } else {
                finished = p.waitFor(ms, TimeUnit.MILLISECONDS);
            }
            if (!finished) {
                try {
                    p.destroy();
                } catch (Exception ignore) {
                }
                try {
                    p.destroyForcibly();
                } catch (Exception ignore) {
                }
                try {
                    p.waitFor(200, TimeUnit.MILLISECONDS);
                } catch (Exception ignore) {
                }
                // Best-effort to drain remaining output quickly.
                try {
                    reader.get(200, TimeUnit.MILLISECONDS);
                } catch (Exception ignore) {
                }
                return new CommandResult(124, out + "\n[timeout]");
            }

            // Give the reader a short window to finish draining output after process exit.
            try {
                reader.get(500, TimeUnit.MILLISECONDS);
            } catch (Exception ignore) {
            }

            return new CommandResult(p.exitValue(), out.toString());
        } catch (Exception e) {
            log.error("run command failed: cmd={}, error={}", command, e.getMessage(), e);
            return new CommandResult(1, "run command failed: " + e.getMessage());
        }
    }

    public CommandResult run(Duration timeout, String... args) {
        List<String> cmd = new ArrayList<>();
        for (String a : args) {
            cmd.add(a);
        }
        return run(timeout, cmd, null);
    }
}


