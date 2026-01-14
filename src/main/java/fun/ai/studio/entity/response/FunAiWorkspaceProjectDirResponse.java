package fun.ai.studio.entity.response;

import lombok.Data;

@Data
public class FunAiWorkspaceProjectDirResponse {
    private Long userId;
    private Long appId;

    private String hostAppDir;
    private String containerAppDir;
}


