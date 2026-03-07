package top.sshh.bililiverecoder.util;

import lombok.Data;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// 录制分P上传实时进度（内存态）。
@Component
public class UploadProgressTracker {

    public enum State {
        UPLOADING,
        RETRY_WAIT,
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
        private int percent;
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
        long now = System.currentTimeMillis();
        byPartId.compute(partId, (k, old) -> {
            Progress p = (old != null) ? old : new Progress();
            p.setPartId(partId);
            p.setHistoryId(historyId);
            p.setPage(page);
            p.setChunkTotal(Math.max(chunkTotal, 0));
            if (p.getChunkDone() < 0) p.setChunkDone(0);
            p.setPercent(calcPercent(p.getChunkDone(), p.getChunkTotal()));
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
            p.setPartId(partId);
            p.setHistoryId(historyId);
            p.setPage(page);
            p.setChunkDone(Math.max(chunkDone, 0));
            p.setChunkTotal(Math.max(chunkTotal, 0));
            p.setPercent(calcPercent(p.getChunkDone(), p.getChunkTotal()));
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
        p.setPercent(src.getPercent());
        p.setState(src.getState());
        p.setStateMsg(src.getStateMsg());
        p.setRetryCount(src.getRetryCount());
        p.setBackoffMs(src.getBackoffMs());
        p.setUpdateAtMs(src.getUpdateAtMs());
        return p;
    }
}
