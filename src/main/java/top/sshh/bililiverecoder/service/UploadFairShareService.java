package top.sshh.bililiverecoder.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.util.LogKvs;
import top.sshh.bililiverecoder.util.TaskUtil;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class UploadFairShareService {

    private static UploadFairShareService instance;
    private static final long UNLIMITED_THRESHOLD = 100L * 1024 * 1024 * 1024;
    private static final long MIN_TASK_SHARE_BPS = 64 * 1024;
    private final ConcurrentHashMap<Long, AtomicInteger> activeUploadUsers = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        instance = this;
    }

    public static UploadFairShareService getInstance() {
        return instance;
    }

    public long fairShareLimit(long globalLimitBps) {
        return fairShareLimitWithDecision(globalLimitBps).effectiveLimitBps();
    }

    public FairShareDecision fairShareLimitWithDecision(long globalLimitBps) {
        if (globalLimitBps <= 0 || globalLimitBps >= UNLIMITED_THRESHOLD) {
            return new FairShareDecision(globalLimitBps, getActiveUploadUserCountSafe(), false, "UNLIMITED_OR_DISABLED");
        }
        int activeUsers = getActiveUploadUserCount();
        if (activeUsers <= 1) {
            return new FairShareDecision(globalLimitBps, Math.max(activeUsers, 1), false, "SINGLE_ACCOUNT_OR_IDLE");
        }
        long share = globalLimitBps / activeUsers;
        if (share < MIN_TASK_SHARE_BPS) {
            long effectiveLimit = Math.min(globalLimitBps, MIN_TASK_SHARE_BPS);
            return new FairShareDecision(effectiveLimit, activeUsers, true, "MULTI_ACCOUNT_MIN_FLOOR");
        }
        return new FairShareDecision(share, activeUsers, true, "MULTI_ACCOUNT_SPLIT");
    }

    public int recommendConcurrency(long fairShareLimitBps, long realRateBps) {
        int concurrency = 3;
        long basis = fairShareLimitBps;
        if (basis <= 0 || basis >= UNLIMITED_THRESHOLD) {
            basis = realRateBps;
        }
        if (basis > 0) {
            if (basis < 2 * 1024 * 1024) {
                concurrency = 1;
            } else if (basis < 5 * 1024 * 1024) {
                concurrency = 2;
            } else if (basis < 10 * 1024 * 1024) {
                concurrency = 3;
            } else if (basis < 20 * 1024 * 1024) {
                concurrency = 5;
            } else {
                concurrency = 8;
            }
        }
        return Math.max(1, Math.min(concurrency, 8));
    }

    public int getActiveUploadTasks() {
        return Math.max(1, TaskUtil.partUploadTask.size());
    }

    public int getActiveUploadUserCount() {
        return activeUploadUsers.size();
    }

    public void registerUploadUser(Long uploadUserId, String roomId, Long partId, String scene) {
        if (uploadUserId == null) {
            return;
        }
        int userInFlight = activeUploadUsers
                .computeIfAbsent(uploadUserId, key -> new AtomicInteger(0))
                .incrementAndGet();
        log.info("[BLR] {}", LogKvs.event("Upload.FairShare.AccountState")
                .add("action", "REGISTER")
                .add("scene", scene)
                .add("uploadUserId", uploadUserId)
                .add("roomId", roomId)
                .add("partId", partId)
                .add("userInFlight", userInFlight)
                .add("activeUploadUsers", getActiveUploadUserCountSafe())
                .add("activeUploadTasks", getActiveUploadTasks()));
    }

    public void unregisterUploadUser(Long uploadUserId, String roomId, Long partId, String scene) {
        if (uploadUserId == null) {
            return;
        }
        AtomicInteger counter = activeUploadUsers.get(uploadUserId);
        int userInFlight = 0;
        if (counter != null) {
            userInFlight = counter.decrementAndGet();
            if (userInFlight <= 0) {
                activeUploadUsers.remove(uploadUserId, counter);
                userInFlight = 0;
            }
        }
        log.info("[BLR] {}", LogKvs.event("Upload.FairShare.AccountState")
                .add("action", "UNREGISTER")
                .add("scene", scene)
                .add("uploadUserId", uploadUserId)
                .add("roomId", roomId)
                .add("partId", partId)
                .add("userInFlight", userInFlight)
                .add("activeUploadUsers", getActiveUploadUserCountSafe())
                .add("activeUploadTasks", getActiveUploadTasks()));
    }

    private int getActiveUploadUserCountSafe() {
        return Math.max(1, getActiveUploadUserCount());
    }

    public record FairShareDecision(long effectiveLimitBps, int activeUploadUsers, boolean splitApplied, String reason) {
    }
}
