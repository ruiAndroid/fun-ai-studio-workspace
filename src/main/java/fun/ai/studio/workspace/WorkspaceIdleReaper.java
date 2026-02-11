package fun.ai.studio.workspace;

import fun.ai.studio.service.FunAiWorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 单机版：定时回收 idle workspace
 * - 10 分钟无操作：stop run
 * - 20 分钟无操作：stop 容器
 */
@Component
public class WorkspaceIdleReaper {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceIdleReaper.class);

    private final WorkspaceActivityTracker tracker;
    private final FunAiWorkspaceService workspaceService;
    private final WorkspaceProperties props;

    public WorkspaceIdleReaper(WorkspaceActivityTracker tracker,
                               FunAiWorkspaceService workspaceService,
                               WorkspaceProperties props) {
        this.tracker = tracker;
        this.workspaceService = workspaceService;
        this.props = props;
    }

    @Scheduled(fixedDelay = 60_000L)
    public void sweep() {
        if (!props.isEnabled()) return;

        // idle 判定使用 monotonic clock（nanoTime），避免系统时间跳变导致误回收
        long nowNs = System.nanoTime();
        int stopRunMin = props.getIdleStopRunMinutes();
        int stopContainerMin = props.getIdleStopContainerMinutes();
        // 约定：<=0 表示禁用（避免误配 0 导致“立刻回收”，容器看起来总是 EXITED）
        long stopRunNs = stopRunMin > 0 ? stopRunMin * 60_000_000_000L : Long.MAX_VALUE;
        long stopContainerNs = stopContainerMin > 0 ? stopContainerMin * 60_000_000_000L : Long.MAX_VALUE;

        Map<Long, Long> snap = tracker.snapshotTouchNs();
        for (var e : snap.entrySet()) {
            Long userId = e.getKey();
            Long lastTouch = e.getValue();
            if (userId == null || lastTouch == null) continue;
            long idleNs = nowNs - lastTouch;
            long idleMs = idleNs / 1_000_000L;

            try {
                if (idleNs >= stopContainerNs) {
                    // 先 stop run 再停容器（避免残留 pid/meta）
                    boolean runStopped = workspaceService.stopRunForIdle(userId);
                    if (runStopped) {
                        log.info("idle reaper: stopped run: userId={}, idleMs={}, thresholdMs={}",
                                userId, idleMs, (stopRunNs == Long.MAX_VALUE ? -1 : stopRunNs / 1_000_000L));
                    }
                    log.info("idle reaper: stop container: userId={}, idleMs={}, thresholdMs={}",
                            userId, idleMs, (stopContainerNs == Long.MAX_VALUE ? -1 : stopContainerNs / 1_000_000L));
                    workspaceService.stopContainerForIdle(userId);
                } else if (idleNs >= stopRunNs) {
                    boolean stopped = workspaceService.stopRunForIdle(userId);
                    if (stopped) {
                        log.info("idle reaper: stop run: userId={}, idleMs={}, thresholdMs={}",
                                userId, idleMs, (stopRunNs == Long.MAX_VALUE ? -1 : stopRunNs / 1_000_000L));
                    }
                }
            } catch (Exception ex) {
                log.warn("idle reaper failed: userId={}, idleMs={}, error={}", userId, idleMs, ex.getMessage());
            }
        }
    }
}


