package fun.ai.studio.entity.request;

import lombok.Data;

@Data
public class FunAiWorkspaceFileWriteRequest {
    private Long userId;
    private Long appId;
    /**
     * 相对 app 根目录路径（使用 / 分隔），不能为空
     */
    private String path;
    /**
     * 文件内容（UTF-8 文本）
     */
    private String content;
    /**
     * 是否自动创建父目录（默认 true）
     */
    private Boolean createParents;

    /**
     * 强制写入（默认 false）。
     * <p>
     * 当 forceWrite=true 时，将跳过 expectedLastModifiedMs 的乐观锁校验，直接写入（用于特殊场景的“覆盖保存”）。
     */
    private Boolean forceWrite;
    /**
     * 乐观锁：期望的 lastModifiedMs
     * - 若文件存在：必须与当前 lastModifiedMs 完全一致，否则拒绝写入（避免并发覆盖）
     * - 若文件不存在：可传 0/-1 表示“必须不存在”
     * - 传 null 则不做并发校验（不推荐）
     */
    private Long expectedLastModifiedMs;
}


