package fun.ai.studio.workspace;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单机版：记录用户最近活跃时间（任何 API 调用或心跳都 touch）
 */
@Component
public class WorkspaceActivityTracker {

    /**
     * wall clock（用于展示/诊断），可能受 NTP/时间跳变影响，不要用于 idle 判定。
     */
    private final ConcurrentHashMap<Long, Long> lastActiveAtMs = new ConcurrentHashMap<>();

    /**
     * monotonic clock（用于 idle 判定），不受系统时间跳变影响。
     */
    private final ConcurrentHashMap<Long, Long> lastTouchNs = new ConcurrentHashMap<>();

    public void touch(Long userId) {
        if (userId == null) return;
        lastActiveAtMs.put(userId, System.currentTimeMillis());
        lastTouchNs.put(userId, System.nanoTime());
    }

    public Long getLastActiveAtMs(Long userId) {
        return userId == null ? null : lastActiveAtMs.get(userId);
    }

    public Long getLastTouchNs(Long userId) {
        return userId == null ? null : lastTouchNs.get(userId);
    }

    public Map<Long, Long> snapshot() {
        return new ConcurrentHashMap<>(lastActiveAtMs);
    }

    public Map<Long, Long> snapshotTouchNs() {
        return new ConcurrentHashMap<>(lastTouchNs);
    }

    public void remove(Long userId) {
        if (userId == null) return;
        lastActiveAtMs.remove(userId);
        lastTouchNs.remove(userId);
    }
}


