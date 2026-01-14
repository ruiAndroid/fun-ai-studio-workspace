package fun.ai.studio.entity.request;

import lombok.Data;

@Data
public class FunAiWorkspaceRenameRequest {
    private Long userId;
    private Long appId;
    private String fromPath;
    private String toPath;
    /**
     * 是否允许覆盖（默认 false）
     */
    private Boolean overwrite;
}


