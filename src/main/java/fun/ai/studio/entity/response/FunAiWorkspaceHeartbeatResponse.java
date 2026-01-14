package fun.ai.studio.entity.response;

import lombok.Data;

@Data
public class FunAiWorkspaceHeartbeatResponse {
    private Long userId;
    private Long serverTimeMs;
    private String message;
}


