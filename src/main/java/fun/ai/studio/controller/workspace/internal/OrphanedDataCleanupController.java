package fun.ai.studio.controller.workspace.internal;

import fun.ai.studio.common.Result;
import fun.ai.studio.workspace.OrphanedDataCleanupService;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 孤立数据清理内部接口（workspace）
 * 
 * 由主项目（91）调用，清理开发态的孤立数据
 */
@RestController
@RequestMapping("/api/fun-ai/workspace/internal")
@Hidden
public class OrphanedDataCleanupController {

    private final OrphanedDataCleanupService cleanupService;

    public OrphanedDataCleanupController(OrphanedDataCleanupService cleanupService) {
        this.cleanupService = cleanupService;
    }

    /**
     * 清理孤立数据
     * 
     * @param request 包含 existingAppIds 的请求
     * @return 清理结果
     */
    @PostMapping("/cleanup-orphaned")
    public Result<Map<String, Object>> cleanupOrphanedData(@RequestBody Map<String, Object> request) {
        try {
            Object appIdsObj = request.get("existingAppIds");
            if (!(appIdsObj instanceof List)) {
                return Result.error("existingAppIds 参数格式错误");
            }

            @SuppressWarnings("unchecked")
            List<Object> appIdsList = (List<Object>) appIdsObj;
            Set<Long> existingAppIds = appIdsList.stream()
                    .filter(id -> id instanceof Number)
                    .map(id -> ((Number) id).longValue())
                    .collect(Collectors.toSet());

            OrphanedDataCleanupService.CleanupResult result = cleanupService.cleanOrphanedData(existingAppIds);

            Map<String, Object> data = new HashMap<>();
            data.put("cleanedAppDirs", result.getCleanedAppDirs());
            data.put("cleanedRunLogs", result.getCleanedRunLogs());
            data.put("cleanedDatabases", result.getCleanedDatabases());
            data.put("message", result.getMessage());

            return Result.success(data);
        } catch (Exception e) {
            return Result.error("清理失败: " + e.getMessage());
        }
    }
}
