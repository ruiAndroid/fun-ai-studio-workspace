package fun.ai.studio.entity.response;

import lombok.Data;

import java.util.Map;

@Data
public class WorkspaceMongoDocResponse {
    private Long userId;
    private Long appId;
    private String dbName;
    private String collection;
    private String id;
    private Map<String, Object> doc;
}


