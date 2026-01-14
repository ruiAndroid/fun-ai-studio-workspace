package fun.ai.studio.entity.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
public class FunAiWorkspaceFileNode {
    @Schema(description = "名称（不含路径）")
    private String name;

    @Schema(description = "相对 app 根目录的路径（使用 / 分隔）")
    private String path;

    @Schema(description = "类型：FILE/DIR")
    private String type;

    @Schema(description = "文件大小（字节），DIR 可为 null")
    private Long size;

    @Schema(description = "最后修改时间戳（ms）")
    private Long lastModifiedMs;

    @Schema(description = "子节点（仅当 type=DIR 且请求需要递归时返回）")
    private List<FunAiWorkspaceFileNode> children;
}


