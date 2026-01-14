package fun.ai.studio.entity.request;

import lombok.Data;

@Data
public class FunAiWorkspacePathRequest {
    private Long userId;
    private Long appId;
    /**
     * 相对 app 根目录路径（使用 / 分隔），不能为空
     */
    private String path;
    /**
     * mkdir/write/upload 可用：是否自动创建父目录
     */
    private Boolean createParents;
}


