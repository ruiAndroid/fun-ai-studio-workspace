package fun.ai.studio.controller.workspace.files;

import fun.ai.studio.common.Result;
import fun.ai.studio.entity.request.FunAiWorkspaceFileWriteRequest;
import fun.ai.studio.entity.request.FunAiWorkspacePathRequest;
import fun.ai.studio.entity.request.FunAiWorkspaceRenameRequest;
import fun.ai.studio.entity.response.FunAiWorkspaceFileReadResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceFileTreeResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceProjectDirResponse;
import fun.ai.studio.service.FunAiWorkspaceService;
import fun.ai.studio.workspace.WorkspaceActivityTracker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

/**
 * Workspace 文件域：文件树/读写/上传下载（宿主机持久化目录）
 */
@RestController
@RequestMapping("/api/fun-ai/workspace/files")
@Tag(name = "Fun AI Workspace 文件", description = "在线编辑器：文件 CRUD、上传/下载、zip 导入/导出")
public class FunAiWorkspaceFileController {
    private static final Logger log = LoggerFactory.getLogger(FunAiWorkspaceFileController.class);

    private final FunAiWorkspaceService workspaceService;
    private final WorkspaceActivityTracker activityTracker;

    public FunAiWorkspaceFileController(FunAiWorkspaceService workspaceService, WorkspaceActivityTracker activityTracker) {
        this.workspaceService = workspaceService;
        this.activityTracker = activityTracker;
    }

    @PostMapping("/ensure-dir")
    @Operation(summary = "确保应用目录存在", description = "确保 /workspace/apps/<appId> 的宿主机目录存在（会先 ensure workspace）")
    public Result<FunAiWorkspaceProjectDirResponse> ensureDir(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId
    ) {
        try {
            activityTracker.touch(userId);
            return Result.success(workspaceService.ensureAppDir(userId, appId));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("ensure app dir failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return Result.error("ensure app dir failed: " + e.getMessage());
        }
    }

    @PostMapping("/upload-zip")
    @Operation(summary = "上传应用 zip 并解压到 workspace 应用目录", description = "上传 zip 后解压到 {hostRoot}/{userId}/apps/{appId}，容器内可见 /workspace/apps/{appId}")
    public Result<FunAiWorkspaceProjectDirResponse> uploadZip(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @Parameter(description = "zip文件", required = true) @RequestParam("file") MultipartFile file,
            @Parameter(description = "是否覆盖已存在目录（默认 true）") @RequestParam(defaultValue = "true") boolean overwrite
    ) {
        try {
            activityTracker.touch(userId);
            return Result.success(workspaceService.uploadAppZip(userId, appId, file, overwrite));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("upload app zip failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return Result.error("upload app zip failed: " + e.getMessage());
        }
    }

    @GetMapping("/tree")
    @Operation(summary = "获取文件树", description = "返回 apps/{appId} 下的目录树（默认忽略 node_modules/.git/dist/build/.next/target）")
    public Result<FunAiWorkspaceFileTreeResponse> tree(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @Parameter(description = "相对路径（默认 .）") @RequestParam(required = false) String path,
            @Parameter(description = "最大递归深度（默认 6）") @RequestParam(required = false) Integer maxDepth,
            @Parameter(description = "最大节点数（默认 5000）") @RequestParam(required = false) Integer maxEntries
    ) {
        try {
            activityTracker.touch(userId);
            return Result.success(workspaceService.listFileTree(userId, appId, path, maxDepth, maxEntries));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("list file tree failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return Result.error("list file tree failed: " + e.getMessage());
        }
    }

    @GetMapping("/content")
    @Operation(summary = "读取文件内容", description = "读取 apps/{appId} 下指定文件（UTF-8 文本，限制 2MB）")
    public Result<FunAiWorkspaceFileReadResponse> read(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @Parameter(description = "相对路径", required = true) @RequestParam String path
    ) {
        try {
            activityTracker.touch(userId);
            return Result.success(workspaceService.readFileContent(userId, appId, path));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("read file failed: userId={}, appId={}, path={}, error={}", userId, appId, path, e.getMessage(), e);
            return Result.error("read file failed: " + e.getMessage());
        }
    }

    @PostMapping("/content")
    @Operation(summary = "写入文件内容", description = "写入 apps/{appId} 下指定文件（UTF-8 文本），支持 expectedLastModifiedMs 乐观锁")
    public Result<FunAiWorkspaceFileReadResponse> write(@RequestBody FunAiWorkspaceFileWriteRequest req) {
        try {
            if (req == null) return Result.error("请求不能为空");
            activityTracker.touch(req.getUserId());
            boolean createParents = req.getCreateParents() == null || req.getCreateParents();
            return Result.success(workspaceService.writeFileContent(
                    req.getUserId(), req.getAppId(), req.getPath(), req.getContent(), createParents, req.getExpectedLastModifiedMs()
            ));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("write file failed: error={}", e.getMessage(), e);
            return Result.error("write file failed: " + e.getMessage());
        }
    }

    @PostMapping("/mkdir")
    @Operation(summary = "创建目录", description = "创建 apps/{appId} 下目录")
    public Result<String> mkdir(@RequestBody FunAiWorkspacePathRequest req) {
        try {
            if (req == null) return Result.error("请求不能为空");
            activityTracker.touch(req.getUserId());
            boolean createParents = req.getCreateParents() == null || req.getCreateParents();
            workspaceService.createDirectory(req.getUserId(), req.getAppId(), req.getPath(), createParents);
            return Result.success("ok");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("mkdir failed: error={}", e.getMessage(), e);
            return Result.error("mkdir failed: " + e.getMessage());
        }
    }

    @PostMapping("/delete")
    @Operation(summary = "删除路径", description = "删除 apps/{appId} 下的文件/目录（递归）")
    public Result<String> delete(@RequestBody FunAiWorkspacePathRequest req) {
        try {
            if (req == null) return Result.error("请求不能为空");
            activityTracker.touch(req.getUserId());
            workspaceService.deletePath(req.getUserId(), req.getAppId(), req.getPath());
            return Result.success("ok");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("delete failed: error={}", e.getMessage(), e);
            return Result.error("delete failed: " + e.getMessage());
        }
    }

    @PostMapping("/move")
    @Operation(summary = "移动/重命名", description = "在 apps/{appId} 内移动/重命名文件或目录")
    public Result<String> move(@RequestBody FunAiWorkspaceRenameRequest req) {
        try {
            if (req == null) return Result.error("请求不能为空");
            activityTracker.touch(req.getUserId());
            boolean overwrite = req.getOverwrite() != null && req.getOverwrite();
            workspaceService.movePath(req.getUserId(), req.getAppId(), req.getFromPath(), req.getToPath(), overwrite);
            return Result.success("ok");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("move failed: error={}", e.getMessage(), e);
            return Result.error("move failed: " + e.getMessage());
        }
    }

    @PostMapping("/upload-file")
    @Operation(summary = "上传单文件", description = "上传单文件到 apps/{appId} 下指定路径")
    public Result<FunAiWorkspaceFileReadResponse> uploadFile(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @Parameter(description = "相对路径", required = true) @RequestParam String path,
            @Parameter(description = "文件", required = true) @RequestParam("file") MultipartFile file,
            @Parameter(description = "是否覆盖（默认 true）") @RequestParam(defaultValue = "true") boolean overwrite,
            @Parameter(description = "是否创建父目录（默认 true）") @RequestParam(defaultValue = "true") boolean createParents
    ) {
        try {
            activityTracker.touch(userId);
            return Result.success(workspaceService.uploadFile(userId, appId, path, file, overwrite, createParents));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("upload file failed: userId={}, appId={}, path={}, error={}", userId, appId, path, e.getMessage(), e);
            return Result.error("upload file failed: " + e.getMessage());
        }
    }

    @GetMapping("/download-file")
    @Operation(summary = "下载单文件", description = "下载 apps/{appId} 下指定文件")
    public ResponseEntity<StreamingResponseBody> downloadFile(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @Parameter(description = "相对路径", required = true) @RequestParam String path
    ) {
        try {
            activityTracker.touch(userId);
            Path filePath = workspaceService.downloadFile(userId, appId, path);
            String filename = filePath.getFileName() == null ? "file" : filePath.getFileName().toString();

            StreamingResponseBody body = os -> {
                try (InputStream is = Files.newInputStream(filePath)) {
                    is.transferTo(os);
                    os.flush();
                }
            };

            ContentDisposition disposition = ContentDisposition.attachment().filename(filename).build();
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("download file failed: userId={}, appId={}, path={}, error={}", userId, appId, path, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/download-zip")
    @Operation(summary = "下载应用目录（zip）", description = "将 {hostRoot}/{userId}/apps/{appId} 打包为 zip 并下载（默认排除 node_modules/.git/dist 等）")
    public ResponseEntity<StreamingResponseBody> downloadAppZip(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @Parameter(description = "是否包含 node_modules（默认 false）") @RequestParam(defaultValue = "false") boolean includeNodeModules
    ) {
        try {
            activityTracker.touch(userId);
            String filename = "app_" + appId + ".zip";

            Path hostAppDir = Paths.get(workspaceService.ensureAppDir(userId, appId).getHostAppDir());

            Set<String> excludes = includeNodeModules
                    ? Set.of(".git", "dist", "build", ".next", "target")
                    : Set.of("node_modules", ".git", "dist", "build", ".next", "target");

            StreamingResponseBody body = outputStream -> {
                fun.ai.studio.workspace.ZipUtils.zipDirectory(hostAppDir, outputStream, excludes);
                outputStream.flush();
            };

            ContentDisposition disposition = ContentDisposition.attachment()
                    .filename(filename)
                    .build();

            return ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .header(HttpHeaders.EXPIRES, "0")
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .body(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("download app zip failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
}


