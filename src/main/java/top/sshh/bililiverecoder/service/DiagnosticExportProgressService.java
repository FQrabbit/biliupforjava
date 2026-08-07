package top.sshh.bililiverecoder.service;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/**
 * 诊断包导出进度。状态只保留在当前进程中，导出结束后短暂保留供前端完成最后一次轮询。
 */
@Service
public class DiagnosticExportProgressService {

    private static final Duration RETENTION = Duration.ofMinutes(10);
    private static final int LOG_START_PERCENT = 5;
    private static final int LOG_END_PERCENT = 97;

    private final ConcurrentMap<String, ExportTask> tasks = new ConcurrentHashMap<>();

    public String resolveExportId(String requestedId) {
        if (requestedId == null || requestedId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        try {
            return UUID.fromString(requestedId.trim()).toString();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("诊断导出标识无效");
        }
    }

    public void register(String exportId, DiagnosticExportService.ExportPlan plan) {
        purgeExpired();
        WorkEstimate estimate = estimate(plan);
        ExportTask task = new ExportTask(exportId, estimate.totalBytes(), estimate.totalFiles());
        if (tasks.putIfAbsent(exportId, task) != null) {
            throw new IllegalArgumentException("诊断导出标识已被占用");
        }
    }

    public ProgressReporter reporter(String exportId) {
        ExportTask task = requireTask(exportId);
        return task;
    }

    public Map<String, Object> status(String exportId) {
        purgeExpired();
        ExportTask task = tasks.get(exportId);
        return task == null ? null : task.toMap();
    }

    public Map<String, Object> cancel(String exportId) {
        purgeExpired();
        ExportTask task = tasks.get(exportId);
        if (task == null) return null;
        task.requestCancel();
        return task.toMap();
    }

    public void complete(String exportId) {
        ExportTask task = tasks.get(exportId);
        if (task != null) task.complete();
    }

    public void fail(String exportId, Throwable error) {
        ExportTask task = tasks.get(exportId);
        if (task != null) task.fail(error);
    }

    private ExportTask requireTask(String exportId) {
        ExportTask task = tasks.get(exportId);
        if (task == null) throw new IllegalStateException("诊断导出任务不存在或已过期");
        return task;
    }

    private void purgeExpired() {
        LocalDateTime cutoff = LocalDateTime.now().minus(RETENTION);
        tasks.entrySet().removeIf(entry -> entry.getValue().isExpired(cutoff));
    }

    private static WorkEstimate estimate(DiagnosticExportService.ExportPlan plan) {
        long relevantBytes = plan.relevantSource().stream().mapToLong(LogArchiveService.LogFile::size).sum();
        int relevantFiles = plan.relevantSource().size();
        if (!plan.request().isIncludeFullLogs()) {
            return new WorkEstimate(relevantBytes, relevantFiles);
        }
        long fullBytes = plan.fullWindow().stream().mapToLong(LogArchiveService.LogFile::size).sum();
        return new WorkEstimate(relevantBytes + fullBytes, relevantFiles + plan.fullWindow().size());
    }

    public interface ProgressReporter {
        void phase(String phase, String message, int percent);

        FileProgress file(long expectedBytes, String fileName);

        void checkCancelled();

        ProgressReporter NOOP = new ProgressReporter() {
            @Override public void phase(String phase, String message, int percent) { }
            @Override public FileProgress file(long expectedBytes, String fileName) { return FileProgress.NOOP; }
            @Override public void checkCancelled() { }
        };
    }

    public interface FileProgress extends LongConsumer, AutoCloseable {
        FileProgress NOOP = new FileProgress() {
            @Override public void accept(long value) { }
            @Override public void close() { }
        };

        @Override
        void close();
    }

    public static final class ExportCancelledException extends RuntimeException {
        public ExportCancelledException() {
            super("诊断导出已取消");
        }
    }

    private record WorkEstimate(long totalBytes, int totalFiles) { }

    private static final class ExportTask implements ProgressReporter {
        private final String exportId;
        private final long totalBytes;
        private final int totalFiles;
        private final LocalDateTime startedAt = LocalDateTime.now();
        private final AtomicLong processedBytes = new AtomicLong();
        private final AtomicInteger processedFiles = new AtomicInteger();
        private final AtomicBoolean cancelRequested = new AtomicBoolean();
        private volatile String state = "RUNNING";
        private volatile String phase = "PREPARING";
        private volatile String message = "正在准备诊断包";
        private volatile String detail = "";
        private volatile int percent = 1;
        private volatile LocalDateTime updatedAt = startedAt;
        private volatile LocalDateTime terminalAt;

        private ExportTask(String exportId, long totalBytes, int totalFiles) {
            this.exportId = exportId;
            this.totalBytes = Math.max(0, totalBytes);
            this.totalFiles = Math.max(0, totalFiles);
        }

        @Override
        public synchronized void phase(String nextPhase, String nextMessage, int nextPercent) {
            checkCancelled();
            if (!"RUNNING".equals(state)) return;
            phase = nextPhase == null ? phase : nextPhase;
            message = nextMessage == null ? message : nextMessage;
            percent = Math.max(percent, Math.min(98, Math.max(1, nextPercent)));
            updatedAt = LocalDateTime.now();
        }

        @Override
        public FileProgress file(long expectedBytes, String fileName) {
            checkCancelled();
            return new FileProgress() {
                private long localBytes;
                private boolean closed;

                @Override
                public void accept(long value) {
                    if (value <= 0) return;
                    checkCancelled();
                    long remaining = Math.max(0, expectedBytes - localBytes);
                    long delta = Math.min(value, remaining);
                    localBytes += delta;
                    advanceBytes(delta, fileName);
                }

                @Override
                public void close() {
                    if (closed) return;
                    closed = true;
                    long remaining = Math.max(0, expectedBytes - localBytes);
                    if (remaining > 0) advanceBytes(remaining, fileName);
                    if ("RUNNING".equals(state)) {
                        processedFiles.incrementAndGet();
                        updatedAt = LocalDateTime.now();
                    }
                }
            };
        }

        private synchronized void advanceBytes(long delta, String fileName) {
            if (!"RUNNING".equals(state) || delta <= 0) return;
            long next = Math.min(totalBytes, processedBytes.addAndGet(delta));
            if (totalBytes > 0) {
                int nextPercent = LOG_START_PERCENT
                        + (int) Math.floor(next * (LOG_END_PERCENT - LOG_START_PERCENT) / (double) totalBytes);
                percent = Math.max(percent, Math.min(LOG_END_PERCENT, nextPercent));
            }
            detail = fileName == null || fileName.isBlank() ? "" : "正在读取 " + fileName;
            updatedAt = LocalDateTime.now();
        }

        @Override
        public void checkCancelled() {
            if (cancelRequested.get()) throw new ExportCancelledException();
        }

        private synchronized void requestCancel() {
            if ("RUNNING".equals(state)) {
                cancelRequested.set(true);
                state = "CANCELLED";
                phase = "CANCELLED";
                message = "诊断导出已取消";
                terminalAt = updatedAt = LocalDateTime.now();
            }
        }

        private synchronized void complete() {
            if (!"RUNNING".equals(state)) return;
            state = "COMPLETED";
            phase = "DONE";
            message = "诊断包生成完成";
            percent = 100;
            processedBytes.set(totalBytes);
            processedFiles.set(totalFiles);
            terminalAt = updatedAt = LocalDateTime.now();
        }

        private synchronized void fail(Throwable error) {
            if (!"RUNNING".equals(state)) return;
            state = "FAILED";
            phase = "FAILED";
            message = "诊断包生成失败";
            detail = safeError(error);
            terminalAt = updatedAt = LocalDateTime.now();
        }

        private boolean isExpired(LocalDateTime cutoff) {
            return terminalAt != null && terminalAt.isBefore(cutoff);
        }

        private Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("exportId", exportId);
            result.put("state", state);
            result.put("phase", phase);
            result.put("message", message);
            result.put("detail", detail);
            result.put("percent", percent);
            result.put("processedBytes", processedBytes.get());
            result.put("totalBytes", totalBytes);
            result.put("processedFiles", processedFiles.get());
            result.put("totalFiles", totalFiles);
            result.put("startedAt", startedAt);
            result.put("updatedAt", updatedAt);
            result.put("elapsedSeconds", Math.max(0, Duration.between(startedAt, updatedAt).getSeconds()));
            return Collections.unmodifiableMap(result);
        }

        private static String safeError(Throwable error) {
            if (error instanceof ExportCancelledException) return "诊断导出已取消";
            return "导出过程中发生异常，请稍后重试";
        }
    }
}
