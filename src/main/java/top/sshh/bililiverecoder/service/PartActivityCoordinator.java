package top.sshh.bililiverecoder.service;

import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

/** 上传准入与分P文件操作共用的稿件级互斥协调器 */
@Service
public class PartActivityCoordinator {
    private static final class State {
        int pendingUploads;
        boolean fileOperationActive;
    }

    private final ConcurrentHashMap<Long, State> states = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicInteger> pendingHistoryUploads = new ConcurrentHashMap<>();
    private final ThreadLocal<Long> currentUploadPart = new ThreadLocal<>();

    public boolean tryRegisterUpload(Long partId) {
        if (partId == null) return true;
        AtomicReference<Boolean> accepted = new AtomicReference<>(false);
        states.compute(partId, (id, current) -> {
            State state = current == null ? new State() : current;
            if (state.fileOperationActive) return state;
            state.pendingUploads++;
            accepted.set(true);
            return state;
        });
        return accepted.get();
    }

    public boolean tryRegisterUpload(Long partId, Long historyId) {
        if (!tryRegisterUpload(partId)) return false;
        if (historyId != null) pendingHistoryUploads.computeIfAbsent(historyId, ignored -> new AtomicInteger())
                .incrementAndGet();
        return true;
    }

    public boolean tryRegisterHistoryUpload(Long historyId) {
        if (historyId == null) return false;
        pendingHistoryUploads.computeIfAbsent(historyId, ignored -> new AtomicInteger()).incrementAndGet();
        return true;
    }

    public void releaseUpload(Long partId) {
        if (partId == null) return;
        states.computeIfPresent(partId, (id, state) -> {
            state.pendingUploads = Math.max(0, state.pendingUploads - 1);
            return isIdle(state) ? null : state;
        });
    }

    public void releaseUpload(Long partId, Long historyId) {
        releaseUpload(partId);
        if (historyId == null) return;
        pendingHistoryUploads.computeIfPresent(historyId, (ignored, count) -> {
            int remaining = count.updateAndGet(value -> Math.max(0, value - 1));
            return remaining == 0 ? null : count;
        });
    }

    public boolean hasPendingHistory(Long historyId) {
        AtomicInteger count = historyId == null ? null : pendingHistoryUploads.get(historyId);
        return count != null && count.get() > 0;
    }

    public UploadScope enterUpload(Long partId) {
        Long previous = currentUploadPart.get();
        if (partId == null) currentUploadPart.remove();
        else currentUploadPart.set(partId);
        return () -> {
            if (previous == null) currentUploadPart.remove();
            else currentUploadPart.set(previous);
        };
    }

    public OperationLease tryBeginFileOperation(Long partId) {
        if (partId == null) return null;
        AtomicReference<OperationLease> lease = new AtomicReference<>();
        states.compute(partId, (id, current) -> {
            State state = current == null ? new State() : current;
            int currentUpload = partId.equals(currentUploadPart.get()) ? 1 : 0;
            if (state.fileOperationActive || state.pendingUploads > currentUpload) {
                return state;
            }
            state.fileOperationActive = true;
            lease.set(new OperationLease(this, partId, state));
            return state;
        });
        return lease.get();
    }

    private void finishFileOperation(Long partId, State state) {
        states.computeIfPresent(partId, (id, current) -> {
            if (current != state) return current;
            state.fileOperationActive = false;
            return isIdle(state) ? null : state;
        });
    }

    private boolean isIdle(State state) {
        return state.pendingUploads == 0 && !state.fileOperationActive;
    }

    @FunctionalInterface
    public interface UploadScope extends AutoCloseable {
        @Override void close();
    }

    public static final class OperationLease implements AutoCloseable {
        private final PartActivityCoordinator coordinator;
        private final Long partId;
        private final State state;
        private boolean closed;

        private OperationLease(PartActivityCoordinator coordinator, Long partId, State state) {
            this.coordinator = coordinator;
            this.partId = partId;
            this.state = state;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            coordinator.finishFileOperation(partId, state);
        }
    }
}
