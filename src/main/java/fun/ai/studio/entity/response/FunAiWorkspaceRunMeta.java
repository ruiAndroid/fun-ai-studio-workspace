package fun.ai.studio.entity.response;

import lombok.Data;

/**
 * 存储在 /workspace/run/current.json 的运行元数据
 */
@Data
public class FunAiWorkspaceRunMeta {
    private Long appId;
    /**
     * 运行类型：
     * - DEV：npm run dev（历史默认）
     * - START：npm run start（全栈项目预览）
     * - BUILD：npm run build（构建任务）
     * - INSTALL：npm install（依赖安装/更新）
     */
    private String type;
    private Long pid;
    private Long startedAt; // epoch seconds
    private Long finishedAt; // epoch seconds（可选）
    private Integer exitCode; // 可选：BUILD/START/DEV 退出码
    private String logPath;
}


