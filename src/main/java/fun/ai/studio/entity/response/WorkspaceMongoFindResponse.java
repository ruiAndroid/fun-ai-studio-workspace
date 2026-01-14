package fun.ai.studio.entity.response;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class WorkspaceMongoFindResponse {
    private Long userId;
    private Long appId;
    private String dbName;
    private String collection;
    private Integer limit;
    private Integer skip;
    private Integer returned;
    private List<Map<String, Object>> items;
}


