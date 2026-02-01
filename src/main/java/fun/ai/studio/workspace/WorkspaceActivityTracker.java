package fun.ai.studio.workspace;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单机版：记录用户最近活跃时间（任何 API 调用或心跳都 touch）
 */
@Component
public class WorkspaceActivityTracker {

    private final ConcurrentHashMap<Long, Long> lastActiveAtMs = new ConcurrentHashMap<>();

    public void touch(Long userId) {
        if (userId == null) return;
        lastActiveAtMs.put(userId, System.currentTimeMillis());
    }

    public Long getLastActiveAtMs(Long userId) {
        return userId == null ? null : lastActiveAtMs.get(userId);
    }

    public Map<Long, Long> snapshot() {
        return new ConcurrentHashMap<>(lastActiveAtMs);
    }

    public void remove(Long userId) {
        if (userId == null) return;
        lastActiveAtMs.remove(userId);
    }
}


