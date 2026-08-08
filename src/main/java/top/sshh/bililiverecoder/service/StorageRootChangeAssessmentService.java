package top.sshh.bililiverecoder.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.entity.PartFileLocation;
import top.sshh.bililiverecoder.entity.StorageRoot;
import top.sshh.bililiverecoder.repo.PartFileLocationRepository;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 把可能比较耗时的历史文件检查放到请求线程之外执行
 * 结果只保存在内存里，重启后仍待确认的目录变更需要重新检查
 */
@Service
public class StorageRootChangeAssessmentService {

    public enum State { IDLE, RUNNING, SUCCEEDED, FAILED, CANCELLED }

    public record Snapshot(String changeId,
                           State state,
                           int total,
                           int checked,
                           int matched,
                           int missing,
                           int sizeMismatch,
                           String message,
                           String startedAt,
                           String finishedAt) {
        public boolean complete() {
            return state == State.SUCCEEDED && checked >= total;
        }

        public boolean validForRemap() {
            return complete() && missing == 0 && sizeMismatch == 0;
        }
    }

    private static final class Job {
        private final String changeId;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile State state = State.RUNNING;
        private volatile int total;
        private volatile int checked;
        private volatile int matched;
        private volatile int missing;
        private volatile int sizeMismatch;
        private volatile String message = "正在检查历史文件";
        private final String startedAt = LocalDateTime.now().toString();
        private volatile String finishedAt;

        private Job(String changeId) {
            this.changeId = changeId;
        }

        private Snapshot snapshot() {
            return new Snapshot(changeId, state, total, checked, matched, missing,
                    sizeMismatch, message, startedAt, finishedAt);
        }
    }

    private final StorageRootService rootService;
    private final PartFileLocationRepository locationRepository;
    private final TaskExecutor executor;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    public StorageRootChangeAssessmentService(StorageRootService rootService,
                                              PartFileLocationRepository locationRepository,
                                              @Qualifier("taskExecutor") TaskExecutor executor) {
        this.rootService = rootService;
        this.locationRepository = locationRepository;
        this.executor = executor;
    }

    public String currentChangeId() {
        return changeId(rootService.workPathChange());
    }

    public Snapshot snapshot() {
        String changeId = currentChangeId();
        Job job = jobs.get(changeId);
        return job == null ? new Snapshot(changeId, State.IDLE, 0, 0, 0, 0, 0,
                "尚未开始检查", null, null) : job.snapshot();
    }

    public Snapshot start() {
        StorageRootService.WorkPathChange change = rootService.workPathChange();
        if (!change.pending() || change.activeRoot() == null) {
            throw new IllegalStateException("work path change is not pending");
        }
        String changeId = changeId(change);
        Job existing = jobs.get(changeId);
        if (existing != null && (existing.state == State.RUNNING || existing.state == State.SUCCEEDED)) {
            return existing.snapshot();
        }
        Job job = new Job(changeId);
        jobs.put(changeId, job);
        executor.execute(() -> run(job, change));
        return job.snapshot();
    }

    public Snapshot cancel() {
        Job job = jobs.get(currentChangeId());
        if (job == null || job.state != State.RUNNING) return snapshot();
        job.cancelled.set(true);
        job.state = State.CANCELLED;
        job.message = "检查已取消";
        job.finishedAt = LocalDateTime.now().toString();
        return job.snapshot();
    }

    public boolean isValidForRemap(String requestedChangeId) {
        if (requestedChangeId == null || !requestedChangeId.equals(currentChangeId())) return false;
        Snapshot result = snapshot();
        return result.validForRemap();
    }

    public static String changeId(StorageRootService.WorkPathChange change) {
        if (change == null || !change.pending() || change.activeRoot() == null) return "";
        String value = String.valueOf(change.activeRoot().getId()) + "|"
                + change.activeRoot().getPath() + "|" + change.configuredPath();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private void run(Job job, StorageRootService.WorkPathChange change) {
        try {
            Path targetRoot = StorageRootService.normalizeAbsolute(change.configuredPath());
            if (!Files.isDirectory(targetRoot) || !Files.isReadable(targetRoot) || !Files.isWritable(targetRoot)) {
                fail(job, "新目录必须是可读写目录");
                return;
            }
            List<PartFileLocation> locations = locationRepository
                    .findByStorageRootIdOrderByIdAsc(change.activeRoot().getId()).stream()
                    .filter(location -> location.getState() == PartFileLocation.LocationState.AVAILABLE)
                    .toList();
            job.total = locations.size();
            for (PartFileLocation location : locations) {
                if (job.cancelled.get()) return;
                checkLocation(job, targetRoot, location);
                job.checked++;
            }
            if (!job.cancelled.get()) {
                job.state = State.SUCCEEDED;
                job.message = job.missing == 0 && job.sizeMismatch == 0
                        ? "历史文件校验通过" : "历史文件存在缺失或大小不一致";
                job.finishedAt = LocalDateTime.now().toString();
            }
        } catch (Exception e) {
            fail(job, e.getMessage() == null ? "历史文件检查失败" : e.getMessage());
        }
    }

    private void checkLocation(Job job, Path targetRoot, PartFileLocation location) {
        try {
            Path relative = Paths.get(location.getRelativePath() == null ? "" : location.getRelativePath()).normalize();
            Path candidate = targetRoot.resolve(relative).normalize();
            if (!isUnder(targetRoot, candidate) || !Files.isRegularFile(candidate)) {
                job.missing++;
                return;
            }
            try {
                if (Files.size(candidate) != location.getExpectedSize()) {
                    job.sizeMismatch++;
                    return;
                }
            } catch (Exception e) {
                job.sizeMismatch++;
                return;
            }
            job.matched++;
        } catch (Exception e) {
            job.missing++;
        }
    }

    private void fail(Job job, String message) {
        job.state = State.FAILED;
        job.message = message;
        job.finishedAt = LocalDateTime.now().toString();
    }

    private static boolean isUnder(Path base, Path child) {
        if (java.io.File.separatorChar != '\\') return child.startsWith(base);
        String baseValue = base.toString().toLowerCase(java.util.Locale.ROOT);
        String childValue = child.toString().toLowerCase(java.util.Locale.ROOT);
        return childValue.equals(baseValue)
                || childValue.startsWith(baseValue + java.io.File.separator);
    }
}
