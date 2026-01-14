package fun.ai.studio.entity.response;

import lombok.Data;

/**
 * workspace 运行态（dev/build/run）
 */
@Data
public class FunAiWorkspaceRunStatusResponse {
    private Long userId;
    /**
     * IDLE / STARTING / RUNNING / DEAD / UNKNOWN / BUILDING / INSTALLING / SUCCESS / FAILED
     */
    private String state;
    /**
     * DEV / START / BUILD / INSTALL（与 current.json 的 type 对齐）
     */
    private String type;
    private Long appId;
    private Integer hostPort;
    private Integer containerPort;
    private Long pid;
    /**
     * BUILD/START/DEV 的退出码（若已结束且可获取）
     */
    private Integer exitCode;
    /**
     * 诊断字段：容器内 containerPort 当前实际监听该端口的进程 pid（用于排查“端口被旧进程占用导致预览指向旧内容”）
     * - null：未监听或无法获取
     */
    private Long portListenPid;
    /**
     * 预览地址（后端根据配置生成，前端无需拼 URL）
     */
    private String previewUrl;
    /**
     * 容器内日志路径（挂载后宿主机也可读）
     */
    private String logPath;
    /**
     * 提示信息（例如已在运行）
     */
    private String message;
}


