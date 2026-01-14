package fun.ai.studio.entity.response;

import lombok.Data;

@Data
public class FunAiWorkspaceFileReadResponse {
    private Long userId;
    private Long appId;
    private String path;
    private String content;
    private Long size;
    private Long lastModifiedMs;
}


