package fun.ai.studio.entity.response;

import lombok.Data;

/**
 * workspace 基本信息（用于前端拿到路径/端口）
 */
@Data
public class FunAiWorkspaceInfoResponse {
    private Long userId;
    private String containerName;
    private String image;
    private Integer hostPort;
    private Integer containerPort;

    private String hostWorkspaceDir;
    private String hostAppsDir;

    private String containerWorkspaceDir;
    private String containerAppsDir;
}


