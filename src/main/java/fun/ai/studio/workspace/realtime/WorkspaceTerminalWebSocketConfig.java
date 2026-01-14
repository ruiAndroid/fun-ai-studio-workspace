package fun.ai.studio.workspace.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.ai.studio.service.FunAiWorkspaceService;
import fun.ai.studio.workspace.WorkspaceActivityTracker;
import fun.ai.studio.workspace.WorkspaceProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WorkspaceTerminalWebSocketConfig implements WebSocketConfigurer {
    private final FunAiWorkspaceService workspaceService;
    private final WorkspaceProperties workspaceProperties;
    private final WorkspaceActivityTracker activityTracker;
    private final ObjectMapper objectMapper;

    public WorkspaceTerminalWebSocketConfig(
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
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new WorkspaceTerminalWebSocketHandler(
                        workspaceService, workspaceProperties, activityTracker, objectMapper
                ), "/api/fun-ai/workspace/ws/terminal")
                .setAllowedOriginPatterns("*");
    }
}


