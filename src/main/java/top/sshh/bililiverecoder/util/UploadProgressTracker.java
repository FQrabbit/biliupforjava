package top.sshh.bililiverecoder.util;

import lombok.Data;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// 录制分P上传实时进度（内存态）
@Component
public class UploadProgressTracker {

    private static final long MIN_SPEED_SAMPLE_INTERVAL_MS = 1500L;

    public enum State {
        WAITING,
        UPLOADING,
        RETRY_WAIT,
        PAUSED,
        SUCCESS,
        FAILED
    }

    @Data
    public static class Progress {
        private long partId;
        private long historyId;
        private Integer page;
        private int chunkDone;
        private int chunkTotal;
        private long chunkSizeBytes;
        private long fileSizeBytes;
        private long uploadedBytes;
        private long remainingBytes;
        private long etaSeconds;
        private String uploadFlow;
        private int percent;
        private long speed;
        private int speedSampleCount;
        private long speedUploadedBytes;
        private long speedSampleAtMs;
        private State state;
        private String stateMsg;
        private Integer retryCount;
        private Long backoffMs;
        private long updateAtMs;

        public boolean isActive() {
            return state == State.UPLOADING || state == State.RETRY_WAIT;
        }
    }

    private static final Duration EXPIRE = Duration.ofMinutes(10);

    private final ConcurrentHashMap<Long, Progress> byPartId = new ConcurrentHashMap<>();

    public void start(long partId, long historyId, Integer page, int chunkTotal) {
        start(partId, historyId, page, chunkTotal, 0L, 0L, null);
    }

    public void start(long partId, long historyId, Integer page, int chunkTotal, long chunkSizeBytes, String uploadFlow) {
        start(partId, historyId, page, chunkTotal, chunkSizeBytes, 0L, uploadFlow);
    }

    public void start(long partId, long historyId, Integer page, int chunkTotal, long chunkSizeBytes, long fileSizeBytes, String uploadFlow) {
        long now = System.currentTimeMillis();
        Progress existing = byPartId.get(partId);
        boolean preserveProgress = existing != null
                && existing.getChunkDone() > 0
                && (existing.getFileSizeBytes() <= 0 || fileSizeBytes <= 0 || existing.getFileSizeBytes() == fileSizeBytes)
                && (existing.getChunkTotal() <= 0 || chunkTotal <= 0 || existing.getChunkTotal() == chunkTotal);
        byPartId.compute(partId, (k, old) -> {
            Progress p = (old != null) ? old : new Progress();
            p.setPartId(partId);
            p.setHistoryId(historyId);
            p.setPage(page);
            p.setChunkTotal(Math.max(chunkTotal, 0));
            p.setChunkSizeBytes(Math.max(chunkSizeBytes, 0L));
            p.setFileSizeBytes(Math.max(fileSizeBytes, 0L));
            p.setChunkDone(preserveProgress && old != null ? Math.max(0, old.getChunkDone()) : 0);
            updateByteProgress(p);
            p.setUploadFlow(uploadFlow);
            p.setPercent(calcPercent(p.getChunkDone(), p.getChunkTotal()));
            p.setSpeed(0L);
            p.setEtaSeconds(0L);
            p.setSpeedSampleCount(0);
            p.setSpeedUploadedBytes(p.getUploadedBytes());
            p.setSpeedSampleAtMs(now);
            p.setState(State.UPLOADING);
            p.setStateMsg(null);
            p.setRetryCount(null);
            p.setBackoffMs(null);
            p.setUpdateAtMs(now);
            return p;
        });
        cleanupExpired(now);
    }

    public void updateChunkDone(long partId, long historyId, Integer page, int chunkDone, int chunkTotal) {
        long now = System.currentTimeMillis();
        byPartId.compute(partId, (k, old) -> {
            Progress p = (old != null) ? old : new Progress();
            long oldSpeedUploadedBytes = old == null ? 0L : old.getSpeedUploadedBytes();
            long oldSpeedSampleAtMs = old == null ? 0L : old.getSpeedSampleAtMs();
            p.setPartId(partId);
            p.setHistoryId(historyId);
            p.setPage(page);
            p.setChunkDone(Math.max(chunkDone, 0));
            p.setChunkTotal(Math.max(chunkTotal, 0));
            p.setPercent(calcPercent(p.getChunkDone(), p.getChunkTotal()));
            updateByteProgress(p);
            if (old != null) {
                if (oldSpeedSampleAtMs <= 0) {
                    oldSpeedSampleAtMs = old.getUpdateAtMs();
                    oldSpeedUploadedBytes = old.getUploadedBytes();
                }
                p.setSpeed(Math.max(0L, old.getSpeed()));
                p.setSpeedSampleCount(Math.max(0, old.getSpeedSampleCount()));
                p.setSpeedUploadedBytes(oldSpeedUploadedBytes);
                p.setSpeedSampleAtMs(oldSpeedSampleAtMs);
            }
            if (old != null && oldSpeedSampleAtMs > 0 && now > oldSpeedSampleAtMs) {
                long elapsedMs = now - oldSpeedSampleAtMs;
                long deltaBytes = Math.max(0L, p.getUploadedBytes() - oldSpeedUploadedBytes);
                if (deltaBytes > 0) {
                    if (elapsedMs >= MIN_SPEED_SAMPLE_INTERVAL_MS) {
                        long instantSpeed = Math.max(0L, Math.round(deltaBytes * 1000.0 / elapsedMs));
                        long previousSpeed = Math.max(0L, old.getSpeed());
                        p.setSpeed(previousSpeed > 0 ? Math.round(previousSpeed * 0.65 + instantSpeed * 0.35) : instantSpeed);
                        p.setSpeedSampleCount(Math.min(Integer.MAX_VALUE, Math.max(0, old.getSpeedSampleCount()) + 1));
                        p.setSpeedUploadedBytes(p.getUploadedBytes());
                        p.setSpeedSampleAtMs(now);
                    }
                } else {
                    p.setSpeedUploadedBytes(p.getUploadedBytes());
                    p.setSpeedSampleAtMs(now);
                }
            }
            if (p.getSpeed() > 0 && p.getSpeedSampleCount() >= 2) {
                p.setEtaSeconds((long) Math.ceil(p.getRemainingBytes() * 1.0 / p.getSpeed()));
            } else {
                p.setEtaSeconds(0L);
            }
            p.setState(State.UPLOADING);
            p.setStateMsg(null);
            p.setRetryCount(null);
            p.setBackoffMs(null);
            p.setUpdateAtMs(now);
            return p;
        });
        cleanupExpired(now);
    }

    public void markRetryWait(long partId, String msg) {
        markRetryWait(partId, msg, null, null);
    }

    public void markRetryWait(long partId, String msg, Integer retryCount, Long backoffMs) {
        long now = System.currentTimeMillis();
        byPartId.computeIfPresent(partId, (k, p) -> {
            p.setState(State.RETRY_WAIT);
            p.setStateMsg(msg);
            p.setRetryCount(retryCount);
            p.setBackoffMs(backoffMs);
            p.setUpdateAtMs(now);
            return p;
        });
        cleanupExpired(now);
    }

    public void markFailed(long partId, String msg) {
        long now = System.currentTimeMillis();
        byPartId.computeIfPresent(partId, (k, p) -> {
            p.setState(State.FAILED);
            p.setStateMsg(msg);
            p.setRetryCount(null);
            p.setBackoffMs(null);
            p.setUpdateAtMs(now);
            return p;
        });
        cleanupExpired(now);
    }

    public void markPaused(long partId, String msg) {
        long now = System.currentTimeMillis();
        byPartId.computeIfPresent(partId, (k, p) -> {
            p.setState(State.PAUSED);
            p.setStateMsg(msg);
            p.setRetryCount(null);
            p.setBackoffMs(null);
            p.setSpeed(0L);
            p.setEtaSeconds(0L);
            p.setUpdateAtMs(now);
            return p;
        });
        cleanupExpired(now);
    }

    public void markSuccessAndRemove(long partId) {
        byPartId.remove(partId);
    }

    public void remove(long partId) {
        byPartId.remove(partId);
    }

    public Progress getByPartId(long partId) {
        cleanupExpired(System.currentTimeMillis());
        Progress p = byPartId.get(partId);
        return p == null ? null : copy(p);
    }

    public List<Progress> listByHistoryId(long historyId) {
        cleanupExpired(System.currentTimeMillis());
        if (byPartId.isEmpty()) {
            return Collections.emptyList();
        }
        List<Progress> list = new ArrayList<>();
        for (Progress p : byPartId.values()) {
            if (p != null && p.getHistoryId() == historyId) {
                list.add(copy(p));
            }
        }
        return list;
    }

    public Map<Long, Progress> snapshotAll() {
        cleanupExpired(System.currentTimeMillis());
        return Collections.unmodifiableMap(byPartId);
    }

    private static int calcPercent(int done, int total) {
        if (total <= 0) return 0;
        int d = Math.min(Math.max(done, 0), total);
        return (int) Math.floor((d * 100.0) / total);
    }

    private static void updateByteProgress(Progress p) {
        long chunkSizeBytes = Math.max(p.getChunkSizeBytes(), 0L);
        long fileSizeBytes = Math.max(p.getFileSizeBytes(), 0L);
        long uploadedBytes = chunkSizeBytes > 0
                ? Math.max(0L, p.getChunkDone()) * chunkSizeBytes
                : 0L;
        if (fileSizeBytes > 0) {
            uploadedBytes = Math.min(uploadedBytes, fileSizeBytes);
        }
        p.setUploadedBytes(uploadedBytes);
        p.setRemainingBytes(fileSizeBytes > 0 ? Math.max(0L, fileSizeBytes - uploadedBytes) : 0L);
    }

    private void cleanupExpired(long nowMs) {
        long expireBefore = nowMs - EXPIRE.toMillis();
        for (Map.Entry<Long, Progress> e : byPartId.entrySet()) {
            Progress p = e.getValue();
            if (p == null || p.getUpdateAtMs() < expireBefore) {
                byPartId.remove(e.getKey());
            }
        }
    }

    private static Progress copy(Progress src) {
        Progress p = new Progress();
        p.setPartId(src.getPartId());
        p.setHistoryId(src.getHistoryId());
        p.setPage(src.getPage());
        p.setChunkDone(src.getChunkDone());
        p.setChunkTotal(src.getChunkTotal());
        p.setChunkSizeBytes(src.getChunkSizeBytes());
        p.setFileSizeBytes(src.getFileSizeBytes());
        p.setUploadedBytes(src.getUploadedBytes());
        p.setRemainingBytes(src.getRemainingBytes());
        p.setEtaSeconds(src.getEtaSeconds());
        p.setUploadFlow(src.getUploadFlow());
        p.setPercent(src.getPercent());
        p.setSpeed(src.getSpeed());
        p.setSpeedSampleCount(src.getSpeedSampleCount());
        p.setSpeedUploadedBytes(src.getSpeedUploadedBytes());
        p.setSpeedSampleAtMs(src.getSpeedSampleAtMs());
        p.setState(src.getState());
        p.setStateMsg(src.getStateMsg());
        p.setRetryCount(src.getRetryCount());
        p.setBackoffMs(src.getBackoffMs());
        p.setUpdateAtMs(src.getUpdateAtMs());
        return p;
    }
}
