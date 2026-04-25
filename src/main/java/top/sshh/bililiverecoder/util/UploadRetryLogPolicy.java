package top.sshh.bililiverecoder.util;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class UploadRetryLogPolicy {

    public static final int CHUNK_WARN_RETRY_THRESHOLD = 3;
    public static final int GLOBAL_CHUNK_WARN_FAILURE_THRESHOLD = 20;
    public static final int RECOVERABLE_WARN_THRESHOLD = 3;
    private static final long RECOVERABLE_WINDOW_MS = 30L * 60L * 1000L;
    private static final ConcurrentHashMap<String, RecoverableCounter> RECOVERABLE_COUNTERS = new ConcurrentHashMap<>();

    private UploadRetryLogPolicy() {
    }

    public static boolean shouldWarn(int retryCount) {
        return retryCount >= CHUNK_WARN_RETRY_THRESHOLD;
    }

    public static boolean shouldWarn(int retryCount, int globalFailCount) {
        return retryCount >= CHUNK_WARN_RETRY_THRESHOLD
                || globalFailCount >= GLOBAL_CHUNK_WARN_FAILURE_THRESHOLD;
    }

    public static LogDecision recoverable(String key) {
        long now = System.currentTimeMillis();
        String safeKey = key == null || key.isBlank() ? "unknown" : key;
        RecoverableCounter counter = RECOVERABLE_COUNTERS.compute(safeKey, (k, old) -> {
            if (old == null || now - old.windowStartMs > RECOVERABLE_WINDOW_MS) {
                return new RecoverableCounter(now);
            }
            return old;
        });
        int count = counter.count.incrementAndGet();
        cleanupRecoverable(now);
        return new LogDecision(count >= RECOVERABLE_WARN_THRESHOLD, count, RECOVERABLE_WARN_THRESHOLD);
    }

    private static void cleanupRecoverable(long now) {
        RECOVERABLE_COUNTERS.entrySet().removeIf(entry -> now - entry.getValue().windowStartMs > RECOVERABLE_WINDOW_MS);
    }

    private static final class RecoverableCounter {
        private final long windowStartMs;
        private final AtomicInteger count = new AtomicInteger(0);

        private RecoverableCounter(long windowStartMs) {
            this.windowStartMs = windowStartMs;
        }
    }

    public record LogDecision(boolean warn, int count, int warnThreshold) {
    }
}
