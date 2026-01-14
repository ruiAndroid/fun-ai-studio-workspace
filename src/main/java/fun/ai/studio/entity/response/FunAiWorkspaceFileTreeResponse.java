package fun.ai.studio.entity.response;

import lombok.Data;

import java.util.List;

@Data
public class FunAiWorkspaceFileTreeResponse {
    private Long userId;
    private Long appId;
    private String rootPath;
    private Integer maxDepth;
    private Integer maxEntries;
    private List<FunAiWorkspaceFileNode> nodes;
}


