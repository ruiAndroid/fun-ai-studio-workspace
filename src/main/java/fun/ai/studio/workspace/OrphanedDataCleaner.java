package fun.ai.studio.workspace;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import fun.ai.studio.entity.FunAiApp;
import fun.ai.studio.mapper.FunAiAppMapper;
import fun.ai.studio.workspace.mongo.WorkspaceMongoShellClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 定时清理孤立数据（数据库中已删除的应用在磁盘和 MongoDB 中的残留数据）
 * - 每天凌晨 2 点执行
 * - 清理内容：
 *   1. 87 服务器上的应用目录（{hostRoot}/{userId}/apps/{appId}）
 *   2. 87 服务器上的 run 日志（{hostRoot}/{userId}/run/run-*-{appId}-*.log）
 *   3. 88 MongoDB 服务器上的开发态数据库（db_{appId}）
 */
@Component
public class OrphanedDataCleaner {
    private static final Logger log = LoggerFactory.getLogger(OrphanedDataCleaner.class);

    private final WorkspaceProperties props;
    private final FunAiAppMapper appMapper;
    private final WorkspaceMongoShellClient mongoClient;

    // run 日志文件名格式：run-{type}-{appId}-{timestamp}.log
    private static final Pattern RUN_LOG_PATTERN = Pattern.compile("^run-[^-]+-([0-9]+)-[0-9]+\\.log$");

    public OrphanedDataCleaner(WorkspaceProperties props,
                               FunAiAppMapper appMapper,
                               WorkspaceMongoShellClient mongoClient) {
        this.props = props;
        this.appMapper = appMapper;
        this.mongoClient = mongoClient;
    }

    /**
     * 每天凌晨 2 点执行清理任务
     * cron 格式：秒 分 时 日 月 周
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanOrphanedData() {
        if (props == null || !props.isEnabled()) {
            log.debug("orphaned data cleaner skipped: workspace not enabled");
            return;
        }
        if (!StringUtils.hasText(props.getHostRoot())) {
            log.warn("orphaned data cleaner skipped: hostRoot not configured");
            return;
        }

        log.info("=== 开始清理孤立数据 ===");
        long startTime = System.currentTimeMillis();

        try {
            // 1. 从数据库查询所有存在的应用 ID
            Set<Long> existingAppIds = loadExistingAppIds();
            log.info("数据库中存在的应用数量: {}", existingAppIds.size());

            // 2. 清理磁盘上的孤立数据
            cleanOrphanedDiskData(existingAppIds);

            // 3. 清理 MongoDB 中的孤立数据库
            cleanOrphanedMongoDatabases(existingAppIds);

            long duration = System.currentTimeMillis() - startTime;
            log.info("=== 孤立数据清理完成，耗时: {}ms ===", duration);
        } catch (Exception e) {
            log.error("孤立数据清理失败", e);
        }
    }

    /**
     * 从数据库加载所有存在的应用 ID
     */
    private Set<Long> loadExistingAppIds() {
        try {
            List<FunAiApp> apps = appMapper.selectList(new QueryWrapper<>());
            Set<Long> appIds = new HashSet<>();
            for (FunAiApp app : apps) {
                if (app.getId() != null) {
                    appIds.add(app.getId());
                }
            }
            return appIds;
        } catch (Exception e) {
            log.error("加载应用列表失败", e);
            return new HashSet<>();
        }
    }

    /**
     * 清理磁盘上的孤立数据（应用目录和 run 日志）
     */
    private void cleanOrphanedDiskData(Set<Long> existingAppIds) {
        Path hostRoot = Paths.get(props.getHostRoot());
        if (!Files.exists(hostRoot) || !Files.isDirectory(hostRoot)) {
            log.warn("hostRoot 不存在或不是目录: {}", hostRoot);
            return;
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
    private void cleanOrphanedMongoDatabases(Set<Long> existingAppIds) {
        if (props.getMongo() == null || !props.getMongo().isEnabled()) {
            log.debug("MongoDB 未启用，跳过数据库清理");
            return;
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
}
