package fun.ai.studio.controller.workspace.internal;

import fun.ai.studio.workspace.WorkspaceProperties;
import fun.ai.studio.workspace.WorkspaceActivityTracker;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 内部接口：仅供 nginx auth_request 使用
 */
@RestController
@RequestMapping("/api/fun-ai/workspace/internal")
@Hidden
public class FunAiWorkspaceInternalController {
    private static final Logger log = LoggerFactory.getLogger(FunAiWorkspaceInternalController.class);

    private final fun.ai.studio.service.impl.FunAiWorkspaceServiceImpl workspaceServiceImpl;
    private final WorkspaceProperties workspaceProperties;
    private final WorkspaceActivityTracker activityTracker;

    public FunAiWorkspaceInternalController(
            fun.ai.studio.service.impl.FunAiWorkspaceServiceImpl workspaceServiceImpl,
            WorkspaceProperties workspaceProperties,
            WorkspaceActivityTracker activityTracker
    ) {
        this.workspaceServiceImpl = workspaceServiceImpl;
        this.workspaceProperties = workspaceProperties;
        this.activityTracker = activityTracker;
    }

    @GetMapping("/nginx/port")
    @Operation(summary = "（内部）nginx 反代查询端口", description = "供 nginx auth_request 使用：根据 userId 返回 X-WS-Port 头。不做 ensure/start，避免副作用。")
    public ResponseEntity<Void> nginxPort(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            HttpServletRequest request
    ) {
        try {
            // 优先使用共享密钥（解决 auth_request 场景下 remoteAddr 可能不是 127.0.0.1 的问题）
            String required = workspaceProperties == null ? null : workspaceProperties.getNginxAuthToken();
            String tokenHeader = request == null ? null : request.getHeader("X-WS-Token");
            String tokenParam = request == null ? null : request.getParameter("token");
            if (StringUtils.hasText(required)) {
                boolean ok = required.equals(tokenHeader) || required.equals(tokenParam);
                if (!ok) {
                    String remote = request == null ? null : request.getRemoteAddr();
                    log.warn("nginx port unauthorized: userId={}, remoteAddr={}, hasHeader={}, hasParam={}",
                            userId, remote, StringUtils.hasText(tokenHeader), StringUtils.hasText(tokenParam));
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
                }
            } else {
                // 若未配置密钥，则退化为仅允许同机调用（localhost）
                String remote = request == null ? null : request.getRemoteAddr();
                if (remote == null || !(remote.equals("127.0.0.1") || remote.equals("::1") || remote.equals("0:0:0:0:0:0:0:1"))) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
                }
            }

            // 预览流量保活：nginx auth_request 会高频调用该接口；touch 仅更新内存时间戳，无副作用。
            if (activityTracker != null) {
                activityTracker.touch(userId);
            }
            Integer port = workspaceServiceImpl.getHostPortForNginx(userId);
            if (port == null || port <= 0) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
            return ResponseEntity.noContent()
                    .header("X-WS-Port", String.valueOf(port))
                    .build();
        } catch (Exception e) {
            log.warn("nginx port lookup failed: userId={}, error={}", userId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}


