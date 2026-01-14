package fun.ai.studio.entity.response;

import lombok.Data;

@Data
public class FunAiWorkspaceStatusResponse {
    private Long userId;
    private String containerName;
    /**
     * NOT_CREATED / RUNNING / EXITED / UNKNOWN
     */
    private String containerStatus;
    private Integer hostPort;
    private Integer containerPort;
    private String hostWorkspaceDir;
}


