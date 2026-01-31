package fun.ai.studio.workspace;

import fun.ai.studio.workspace.mongo.WorkspaceMongoShellClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 孤立数据清理服务（开发态）
 * 
 * 清理内容：
 * 1. 87 服务器上的应用目录（{hostRoot}/{userId}/apps/{appId}）
 * 2. 87 服务器上的 run 日志（{hostRoot}/{userId}/run/run-*-{appId}-*.log）
 * 3. 88 MongoDB 服务器上的开发态数据库（db_{appId}）
 */
@Service
public class OrphanedDataCleanupService {
    private static final Logger log = LoggerFactory.getLogger(OrphanedDataCleanupService.class);

    private final WorkspaceProperties props;
    private final WorkspaceMongoShellClient mongoClient;

    // run 日志文件名格式：run-{type}-{appId}-{timestamp}.log
    private static final Pattern RUN_LOG_PATTERN = Pattern.compile("^run-[^-]+-([0-9]+)-[0-9]+\\.log$");

    public OrphanedDataCleanupService(WorkspaceProperties props,
                                      WorkspaceMongoShellClient mongoClient) {
        this.props = props;
        this.mongoClient = mongoClient;
    }

    /**
     * 清理孤立数据
     * 
     * @param existingAppIds 数据库中存在的应用 ID 集合
     * @return 清理结果统计
     */
    public CleanupResult cleanOrphanedData(Set<Long> existingAppIds) {
        if (props == null || !props.isEnabled()) {
            log.warn("workspace 未启用，跳过清理");
            return new CleanupResult(0, 0, 0, "workspace not enabled");
        }
        if (!StringUtils.hasText(props.getHostRoot())) {
            log.warn("hostRoot 未配置，跳过清理");
            return new CleanupResult(0, 0, 0, "hostRoot not configured");
        }

        log.info("=== 开始清理开发态孤立数据 ===");
        long startTime = System.currentTimeMillis();

        int cleanedAppDirs = 0;
        int cleanedRunLogs = 0;
        int cleanedDatabases = 0;

        try {
            // 1. 清理磁盘上的孤立数据
            int[] diskResult = cleanOrphanedDiskData(existingAppIds);
            cleanedAppDirs = diskResult[0];
            cleanedRunLogs = diskResult[1];

            // 2. 清理 MongoDB 中的孤立数据库
            cleanedDatabases = cleanOrphanedMongoDatabases(existingAppIds);

            long duration = System.currentTimeMillis() - startTime;
            log.info("=== 开发态孤立数据清理完成，耗时: {}ms ===", duration);
            
            return new CleanupResult(cleanedAppDirs, cleanedRunLogs, cleanedDatabases, "success");
        } catch (Exception e) {
            log.error("开发态孤立数据清理失败", e);
            return new CleanupResult(cleanedAppDirs, cleanedRunLogs, cleanedDatabases, "error: " + e.getMessage());
        }
    }

    /**
     * 清理磁盘上的孤立数据（应用目录和 run 日志）
     * 
     * @return [cleanedAppDirs, cleanedRunLogs]
     */
    private int[] cleanOrphanedDiskData(Set<Long> existingAppIds) {
        Path hostRoot = Paths.get(props.getHostRoot());
        if (!Files.exists(hostRoot) || !Files.isDirectory(hostRoot)) {
            log.warn("hostRoot 不存在或不是目录: {}", hostRoot);
            return new int[]{0, 0};
        }

        int cleanedAppDirs = 0;
        int cleanedRunLogs = 0;

        try (DirectoryStream<Path> userDirs = Files.newDirectoryStream(hostRoot)) {
            for (Path userDir : userDirs) {
                if (!Files.isDirectory(userDir)) continue;

                // 清理应用目录
                Path appsDir = userDir.resolve("apps");
                if (Files.exists(appsDir) && Files.isDirectory(appsDir)) {
                    cleanedAppDirs += cleanOrphanedAppDirs(appsDir, existingAppIds);
                }

                // 清理 run 日志
                Path runDir = userDir.resolve("run");
                if (Files.exists(runDir) && Files.isDirectory(runDir)) {
                    cleanedRunLogs += cleanOrphanedRunLogs(runDir, existingAppIds);
                }
            }
        } catch (IOException e) {
            log.error("遍历用户目录失败: {}", hostRoot, e);
        }

        log.info("清理磁盘数据完成: 应用目录={}, run日志={}", cleanedAppDirs, cleanedRunLogs);
        return new int[]{cleanedAppDirs, cleanedRunLogs};
    }

    /**
     * 清理孤立的应用目录
     */
    private int cleanOrphanedAppDirs(Path appsDir, Set<Long> existingAppIds) {
        int cleaned = 0;
        try (DirectoryStream<Path> appDirs = Files.newDirectoryStream(appsDir)) {
            for (Path appDir : appDirs) {
                if (!Files.isDirectory(appDir)) continue;

                String dirName = appDir.getFileName().toString();
                
                // 跳过隔离目录（.deleted-* 后缀）
                if (dirName.contains(".deleted-")) {
                    log.debug("跳过隔离目录: {}", appDir);
                    continue;
                }

                try {
                    Long appId = Long.parseLong(dirName);
                    if (!existingAppIds.contains(appId)) {
                        log.info("清理孤立应用目录: appId={}, path={}", appId, appDir);
                        deleteDirectoryRecursively(appDir);
                        cleaned++;
                    }
                } catch (NumberFormatException e) {
                    log.debug("跳过非数字目录: {}", dirName);
                }
            }
        } catch (IOException e) {
            log.error("清理应用目录失败: {}", appsDir, e);
        }
        return cleaned;
    }

    /**
     * 清理孤立的 run 日志
     */
    private int cleanOrphanedRunLogs(Path runDir, Set<Long> existingAppIds) {
        int cleaned = 0;
        try (DirectoryStream<Path> logFiles = Files.newDirectoryStream(runDir, "run-*.log")) {
            for (Path logFile : logFiles) {
                String fileName = logFile.getFileName().toString();
                Matcher matcher = RUN_LOG_PATTERN.matcher(fileName);
                
                if (matcher.matches()) {
                    try {
                        Long appId = Long.parseLong(matcher.group(1));
                        if (!existingAppIds.contains(appId)) {
                            log.info("清理孤立run日志: appId={}, file={}", appId, fileName);
                            Files.deleteIfExists(logFile);
                            cleaned++;
                        }
                    } catch (NumberFormatException e) {
                        log.debug("解析appId失败: {}", fileName);
                    }
                }
            }
        } catch (IOException e) {
            log.error("清理run日志失败: {}", runDir, e);
        }
        return cleaned;
    }

    /**
     * 清理 MongoDB 中的孤立数据库
     */
    private int cleanOrphanedMongoDatabases(Set<Long> existingAppIds) {
        if (props.getMongo() == null || !props.getMongo().isEnabled()) {
            log.debug("MongoDB 未启用，跳过数据库清理");
            return 0;
        }

        int cleaned = 0;
        try {
            // 列出所有数据库
            List<String> databases = mongoClient.listDatabases();
            log.info("MongoDB 中的数据库数量: {}", databases.size());

            // 数据库命名格式：db_{appId}
            Pattern dbPattern = Pattern.compile("^db_([0-9]+)$");

            for (String dbName : databases) {
                Matcher matcher = dbPattern.matcher(dbName);
                if (matcher.matches()) {
                    try {
                        Long appId = Long.parseLong(matcher.group(1));
                        if (!existingAppIds.contains(appId)) {
                            log.info("清理孤立MongoDB数据库: appId={}, dbName={}", appId, dbName);
                            boolean dropped = mongoClient.dropDatabase(dbName);
                            if (dropped) {
                                cleaned++;
                            } else {
                                log.warn("删除MongoDB数据库失败: {}", dbName);
                            }
                        }
                    } catch (NumberFormatException e) {
                        log.debug("解析appId失败: {}", dbName);
                    }
                }
            }
        } catch (Exception e) {
            log.error("清理MongoDB数据库失败", e);
        }

        log.info("清理MongoDB数据库完成: 已清理={}", cleaned);
        return cleaned;
    }

    /**
     * 递归删除目录
     */
    private void deleteDirectoryRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;

        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
                for (Path entry : entries) {
                    deleteDirectoryRecursively(entry);
                }
            }
        }
        Files.delete(dir);
    }

    /**
     * 清理结果
     */
    public static class CleanupResult {
        private final int cleanedAppDirs;
        private final int cleanedRunLogs;
        private final int cleanedDatabases;
        private final String message;

        public CleanupResult(int cleanedAppDirs, int cleanedRunLogs, int cleanedDatabases, String message) {
            this.cleanedAppDirs = cleanedAppDirs;
            this.cleanedRunLogs = cleanedRunLogs;
            this.cleanedDatabases = cleanedDatabases;
            this.message = message;
        }

        public int getCleanedAppDirs() {
            return cleanedAppDirs;
        }

        public int getCleanedRunLogs() {
            return cleanedRunLogs;
        }

        public int getCleanedDatabases() {
            return cleanedDatabases;
        }

        public String getMessage() {
            return message;
        }
    }
}
