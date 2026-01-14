package fun.ai.studio.workspace;

import lombok.Data;

/**
 * 持久化在宿主机 workspace 目录下的元信息（避免端口漂移）
 */
@Data
public class WorkspaceMeta {
    private Integer hostPort;
    private Integer containerPort;
    private String image;
    private String containerName;
    private Long createdAt;
}


