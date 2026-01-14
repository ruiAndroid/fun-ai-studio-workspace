package fun.ai.studio.entity.response;

import lombok.Data;

import java.util.List;

@Data
public class WorkspaceMongoCollectionsResponse {
    private Long userId;
    private Long appId;
    private String dbName;
    private List<String> collections;
}


