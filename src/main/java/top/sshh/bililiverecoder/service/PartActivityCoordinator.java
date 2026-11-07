package top.sshh.bililiverecoder.service;

import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** 上传准入与分P文件操作共用的稿件级互斥协调器 */
@Service
public class PartActivityCoordinator {
    private static final class State {
        int pendingUploads;
        boolean fileOperationActive;
    }

    private static final class HistoryState {
        volatile int uploads;
        int admissions;
        boolean fileOperationActive;
    }

    private final ConcurrentHashMap<Long, State> states = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, HistoryState> histories = new ConcurrentHashMap<>();
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
        if (historyId == null) return tryRegisterUpload(partId);
        AtomicReference<Boolean> accepted = new AtomicReference<>(false);
        histories.compute(historyId, (id, current) -> {
            HistoryState state = current == null ? new HistoryState() : current;
            if (!state.fileOperationActive && tryRegisterUpload(partId)) {
                state.uploads++;
                accepted.set(true);
            }
            return historyIdle(state) ? null : state;
        });
        return accepted.get();
    }

    public boolean tryRegisterHistoryUpload(Long historyId) {
        if (historyId == null) return false;
        AtomicReference<Boolean> accepted = new AtomicReference<>(false);
        histories.compute(historyId, (id, current) -> {
            HistoryState state = current == null ? new HistoryState() : current;
            if (!state.fileOperationActive) {
                state.uploads++;
                accepted.set(true);
            }
            return historyIdle(state) ? null : state;
        });
        return accepted.get();
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
        histories.computeIfPresent(historyId, (ignored, state) -> {
            state.uploads = Math.max(0, state.uploads - 1);
            return historyIdle(state) ? null : state;
        });
    }

    public boolean hasPendingHistory(Long historyId) {
        HistoryState state = historyId == null ? null : histories.get(historyId);
        return state != null && state.uploads > 0;
    }

    public HistoryLease tryBeginHistoryFileOperation(Long historyId) {
        return beginHistoryActivity(historyId, true);
    }

    public HistoryLease tryBeginHistoryAdmission(Long historyId) {
        return beginHistoryActivity(historyId, false);
    }

    private HistoryLease beginHistoryActivity(Long historyId, boolean fileOperation) {
        if (historyId == null) return null;
        AtomicReference<HistoryLease> result = new AtomicReference<>();
        histories.compute(historyId, (id, current) -> {
            HistoryState state = current == null ? new HistoryState() : current;
            if (state.fileOperationActive || (fileOperation && (state.uploads > 0 || state.admissions > 0))) {
                return state;
            }
            if (fileOperation) state.fileOperationActive = true;
            else state.admissions++;
            result.set(new HistoryLease(() -> histories.computeIfPresent(id, (ignored, value) -> {
                if (fileOperation) value.fileOperationActive = false;
                else value.admissions = Math.max(0, value.admissions - 1);
                return historyIdle(value) ? null : value;
            })));
            return state;
        });
        return result.get();
    }

    private static boolean historyIdle(HistoryState state) {
        return state.uploads == 0 && state.admissions == 0 && !state.fileOperationActive;
    }

    public static final class HistoryLease implements AutoCloseable {
        private Runnable release;
        private HistoryLease(Runnable release) { this.release = release; }
        @Override
        public void close() {
            if (release == null) return;
            release.run();
            release = null;
        }
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
