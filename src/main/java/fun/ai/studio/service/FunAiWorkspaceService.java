package fun.ai.studio.service;

import fun.ai.studio.entity.response.FunAiWorkspaceInfoResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceFileReadResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceFileTreeResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceProjectDirResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceRunStatusResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceStatusResponse;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;

public interface FunAiWorkspaceService {

    FunAiWorkspaceInfoResponse ensureWorkspace(Long userId);

    FunAiWorkspaceStatusResponse getStatus(Long userId);

    FunAiWorkspaceProjectDirResponse ensureAppDir(Long userId, Long appId);

    /**
     * 上传 zip 并解压到指定 app 目录：
     * 宿主机：{hostRoot}/{userId}/apps/{appId}
     * 容器内：/workspace/apps/{appId}
     */
    FunAiWorkspaceProjectDirResponse uploadAppZip(Long userId, Long appId, MultipartFile file, boolean overwrite);

    /**
     * 启动 dev server（当前阶段：同一用户同时只能运行一个应用）
     */
    FunAiWorkspaceRunStatusResponse startDev(Long userId, Long appId);

    /**
     * 受控构建（非阻塞）：在容器内执行 npm run build，并写入 run/current.json + run/dev.log
     */
    FunAiWorkspaceRunStatusResponse startBuild(Long userId, Long appId);

    /**
     * 受控预览（非阻塞）：在容器内执行 npm run start（全栈项目），并要求绑定到 containerPort（默认 5173）。
     */
    FunAiWorkspaceRunStatusResponse startPreview(Long userId, Long appId);

    /**
     * 受控依赖安装（非阻塞）：在容器内执行 npm install（写入 current.json/dev.log）
     */
    FunAiWorkspaceRunStatusResponse startInstall(Long userId, Long appId);

    /**
     * 停止当前运行任务（如果存在）
     */
    FunAiWorkspaceRunStatusResponse stopRun(Long userId);

    /**
     * 查询当前运行状态
     */
    FunAiWorkspaceRunStatusResponse getRunStatus(Long userId);

    /**
     * 清空当前 run 的 dev.log（宿主机文件：{hostRoot}/{userId}/run/dev.log）。
     * <p>
     * 为避免误清其它应用的运行日志：如果存在 run/current.json 且其 appId 与入参 appId 不一致，将抛出 IllegalArgumentException。
     */
    void clearRunLog(Long userId, Long appId);

    /**
     * 将指定 app 目录打包为 zip 文件并返回 zipPath（位于 run/ 目录下的临时文件）
     */
    Path exportAppAsZip(Long userId, Long appId);

    /**
     * 在线编辑器：读取目录树（默认忽略 node_modules/.git/dist/build/.next/target 等）
     */
    FunAiWorkspaceFileTreeResponse listFileTree(Long userId, Long appId, String path, Integer maxDepth, Integer maxEntries);

    /**
     * 在线编辑器：读取文件内容（UTF-8 文本）
     */
    FunAiWorkspaceFileReadResponse readFileContent(Long userId, Long appId, String path);

    /**
     * 在线编辑器：写入文件内容（UTF-8 文本，带乐观锁）
     */
    FunAiWorkspaceFileReadResponse writeFileContent(Long userId, Long appId, String path, String content, boolean createParents, Long expectedLastModifiedMs, boolean forceWrite);

    /**
     * 在线编辑器：创建目录
     */
    void createDirectory(Long userId, Long appId, String path, boolean createParents);

    /**
     * 在线编辑器：删除文件/目录（递归）
     */
    void deletePath(Long userId, Long appId, String path);

    /**
     * 在线编辑器：移动/重命名
     */
    void movePath(Long userId, Long appId, String fromPath, String toPath, boolean overwrite);

    /**
     * 在线编辑器：上传单文件到指定路径
     */
    FunAiWorkspaceFileReadResponse uploadFile(Long userId, Long appId, String path, MultipartFile file, boolean overwrite, boolean createParents);

    /**
     * 在线编辑器：下载单文件（返回宿主机文件路径用于 controller streaming）
     */
    Path downloadFile(Long userId, Long appId, String path);

    /**
     * 给 idle 回收任务使用：不要因为 stopRun 而拉起容器。
     */
    void stopRunForIdle(Long userId);

    /**
     * 给 idle 回收任务使用：不要拉起容器。
     */
    void stopContainerForIdle(Long userId);

    /**
     * 删除用户 workspace 容器（docker rm -f ws-u-{userId}）。
     * <p>
     * 默认只删除容器本身，不删除宿主机持久化目录（{hostRoot}/{userId}），避免误伤代码与数据。
     * 返回删除后的容器状态（通常为 NOT_CREATED）。
     */
    FunAiWorkspaceStatusResponse removeWorkspaceContainer(Long userId);

    /**
     * 应用被删除后的 workspace 清理（无 DB 归属校验：调用方需确保已校验 app 归属）
     * - 若该 app 正在运行：尝试 stopRun（仅当容器处于 RUNNING 时）
     * - 删除宿主机目录：{hostRoot}/{userId}/apps/{appId}
     * - 必要时清理 run 元数据并将 last-known 运行态落库为 IDLE
     */
    void cleanupWorkspaceOnAppDeleted(Long userId, Long appId);
}


