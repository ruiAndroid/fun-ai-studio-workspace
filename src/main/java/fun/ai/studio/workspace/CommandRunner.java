package fun.ai.studio.workspace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 轻量命令执行器（用于调用 docker/podman 等）
 */
@Component
public class CommandRunner {
    private static final Logger log = LoggerFactory.getLogger(CommandRunner.class);

    public CommandResult run(Duration timeout, List<String> command) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command 不能为空");
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (out.length() < 32_000) {
                        out.append(line).append('\n');
                    }
                }
            }

            boolean finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                return new CommandResult(124, out + "\n[timeout]");
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
        return run(timeout, cmd);
    }
}


