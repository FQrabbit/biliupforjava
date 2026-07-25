package top.sshh.bililiverecoder.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 房间删除后台任务协调器
 *
 * 删除本身不能绑定在浏览器请求生命周期内：浏览器刷新、网络抖动或关闭标签页时，
 * 后端仍然需要继续完成删除并保留最终状态;因此这里把任务状态和执行线程与HTTP请求解耦，前端只负责轮询状态
 * 
 */
@Slf4j
@Service
public class RoomDeletionTaskService {

    private static final long FINISHED_TASK_RETENTION_MINUTES = 30L;

    private final RoomDeletionService roomDeletionService;
    private final TaskExecutor taskExecutor;
    private final ConcurrentMap<String, DeletionTask> tasks = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, String> activeRoomTasks = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, String> latestRoomTasks = new ConcurrentHashMap<>();

    public RoomDeletionTaskService(RoomDeletionService roomDeletionService,
                                   @Qualifier("myAsyncPool") TaskExecutor taskExecutor) {
        this.roomDeletionService = roomDeletionService;
        this.taskExecutor = taskExecutor;
    }

    public StartResult start(Long roomDatabaseId, RoomDeletionService.DeleteOptions options) {
        if (roomDatabaseId == null) {
            throw new IllegalArgumentException("房间标识不能为空");
        }
        RoomDeletionService.DeleteOptions safeOptions = options == null
                ? RoomDeletionService.DeleteOptions.roomOnly()
                : options;
        validateOptions(safeOptions);
        purgeExpiredTasks();

        synchronized (activeRoomTasks) {
            String existingTaskId = activeRoomTasks.get(roomDatabaseId);
            if (existingTaskId != null) {
                DeletionTask existing = tasks.get(existingTaskId);
                if (existing != null && existing.running) {
                    throw new IllegalStateException("该房间已有删除任务正在执行，请等待当前任务完成");
                }
                activeRoomTasks.remove(roomDatabaseId, existingTaskId);
            }

            RoomDeletionService.DeletionPreview preview = roomDeletionService.preview(roomDatabaseId);
            if (!preview.found()) {
                return StartResult.notFound(roomDatabaseId);
            }
            if (preview.active()) {
                throw new IllegalStateException(resolveActiveMessage(preview));
            }

            String taskId = UUID.randomUUID().toString();
            DeletionTask task = new DeletionTask(taskId, roomDatabaseId, preview.roomId(),
                    safeOptions, preview.historyCount());
            tasks.put(taskId, task);
            activeRoomTasks.put(roomDatabaseId, taskId);
            latestRoomTasks.put(roomDatabaseId, taskId);
            try {
                taskExecutor.execute(() -> run(task));
            } catch (RuntimeException e) {
                task.fail("删除任务启动失败，请稍后重试");
                activeRoomTasks.remove(roomDatabaseId, taskId);
                log.error("[BLR] room deletion task rejected, taskId={}, roomDatabaseId={}",
                        taskId, roomDatabaseId, e);
                throw new IllegalStateException("删除任务启动失败，请稍后重试", e);
            }
            return StartResult.accepted(task.toMap());
        }
    }

    public Map<String, Object> status(String taskId) {
        purgeExpiredTasks();
        if (taskId == null || taskId.isBlank()) {
            return Map.of("found", false, "message", "删除任务标识不能为空");
        }
        DeletionTask task = tasks.get(taskId);
        if (task == null) {
            return Map.of("found", false, "message", "删除任务不存在或已过期");
        }
        return task.toMap();
    }

    public Map<String, Object> statusForRoom(Long roomDatabaseId) {
        purgeExpiredTasks();
        if (roomDatabaseId == null) {
            return Map.of("found", false, "message", "房间标识不能为空");
        }
        String taskId = latestRoomTasks.get(roomDatabaseId);
        DeletionTask task = taskId == null ? null : tasks.get(taskId);
        if (task == null) {
            if (taskId != null) {
                latestRoomTasks.remove(roomDatabaseId, taskId);
            }
            return Map.of("found", false, "message", "没有找到该房间的删除任务");
        }
        return task.toMap();
    }

    private void run(DeletionTask task) {
        try {
            RoomDeletionService.DeletionResult deletion = roomDeletionService.delete(
                    task.roomDatabaseId,
                    task.options,
                    task::progress);
            if (!deletion.found()) {
                task.done(false, "房间已不存在，删除任务无需继续", deletion.toMap());
            } else {
                String message;
                if (!deletion.notDeletedFiles().isEmpty()) {
                    message = "房间、录制历史和统计数据已删除，但有 "
                            + deletion.notDeletedFiles().size() + " 个本地文件未删除";
                } else if (!deletion.options().deleteHistories()) {
                    message = "房间删除成功，录制历史和统计数据已保留";
                } else if (deletion.deletedHistoryCount() > 0) {
                    message = "房间、" + deletion.deletedHistoryCount() + " 条录制历史及相关统计数据删除成功";
                } else {
                    message = "房间及相关统计数据删除成功";
                }
                task.done(true, message, deletion.toMap());
            }
        } catch (Exception e) {
            task.fail(resolveErrorMessage(e));
            log.error("[BLR] {}", "Room.Delete.Task.Error taskId=" + task.taskId
                    + ", roomDatabaseId=" + task.roomDatabaseId, e);
        } finally {
            activeRoomTasks.remove(task.roomDatabaseId, task.taskId);
        }
    }

    private void purgeExpiredTasks() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(FINISHED_TASK_RETENTION_MINUTES);
        tasks.entrySet().removeIf(entry -> {
            DeletionTask task = entry.getValue();
            if (task.running || task.updatedAt == null || task.updatedAt.isAfter(cutoff)) {
                return false;
            }
            activeRoomTasks.remove(task.roomDatabaseId, task.taskId);
            latestRoomTasks.remove(task.roomDatabaseId, task.taskId);
            return true;
        });
    }

    private static void validateOptions(RoomDeletionService.DeleteOptions options) {
        if (!options.deleteHistories()
                && (options.deleteVideoFiles() || options.deleteDanmakuFiles() || options.deleteCoverFiles())) {
            throw new IllegalArgumentException("删除本地文件前必须同时删除录制历史");
        }
    }

    private static String resolveActiveMessage(RoomDeletionService.DeletionPreview preview) {
        if (preview.recordingActive() && preview.uploadingActive()) {
            return "房间仍在直播或录制，且存在正在上传或处理的稿件；请先停止录制并取消上传或强制归档";
        }
        if (preview.uploadingActive()) {
            return "存在正在上传或处理的稿件；请先取消上传或强制归档，等待任务停止后再删除";
        }
        return "房间仍在直播、录制或存在尚未结束的分P，请先停止录制后再删除";
    }

    private static String resolveErrorMessage(Exception e) {
        if (e instanceof IllegalStateException || e instanceof IllegalArgumentException) {
            return e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName()
                    : e.getMessage();
        }
        return "删除过程中发生异常：" + e.getClass().getSimpleName();
    }

    public record StartResult(boolean accepted,
                              boolean found,
                              Long roomDatabaseId,
                              String taskId,
                              String message,
                              Map<String, Object> task) {

        private static StartResult accepted(Map<String, Object> task) {
            String taskId = task == null ? null : String.valueOf(task.get("taskId"));
            Long roomDatabaseId = task == null || task.get("roomDatabaseId") == null
                    ? null
                    : ((Number) task.get("roomDatabaseId")).longValue();
            return new StartResult(true, true, roomDatabaseId, taskId, "删除任务已启动",
                    task == null ? Collections.emptyMap() : task);
        }

        private static StartResult notFound(Long roomDatabaseId) {
            return new StartResult(false, false, roomDatabaseId, null, "房间不存在", Collections.emptyMap());
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("accepted", accepted);
            map.put("found", found);
            map.put("roomDatabaseId", roomDatabaseId);
            map.put("taskId", taskId);
            map.put("message", message);
            map.put("task", task == null ? Collections.emptyMap() : task);
            return map;
        }
    }

    private static final class DeletionTask {
        private final String taskId;
        private final Long roomDatabaseId;
        private final String roomId;
        private final RoomDeletionService.DeleteOptions options;
        private final LocalDateTime startedAt;
        private final int historyCount;

        private volatile boolean running = true;
        private volatile boolean success = true;
        private volatile String phase = "STARTING";
        private volatile String message = "正在启动删除任务";
        private volatile String detail = "";
        private volatile long processed;
        private volatile long total;
        private volatile int percent = 1;
        private volatile LocalDateTime updatedAt;
        private volatile Map<String, Object> result = Collections.emptyMap();

        private DeletionTask(String taskId,
                             Long roomDatabaseId,
                             String roomId,
                             RoomDeletionService.DeleteOptions options,
                             int historyCount) {
            this.taskId = taskId;
            this.roomDatabaseId = roomDatabaseId;
            this.roomId = roomId;
            this.options = options;
            this.historyCount = Math.max(0, historyCount);
            this.total = options.deleteHistories() ? Math.max(1, this.historyCount) : 1;
            this.startedAt = LocalDateTime.now();
            this.updatedAt = this.startedAt;
        }

        private synchronized void progress(String phase,
                                            String message,
                                            String detail,
                                            long processed,
                                            long total,
                                            int percent) {
            if (!running) {
                return;
            }
            this.phase = phase == null ? "RUNNING" : phase;
            this.message = message == null ? "正在删除" : message;
            this.detail = detail == null ? "" : detail;
            this.total = Math.max(1, total);
            this.processed = Math.max(0, Math.min(processed, this.total));
            this.percent = Math.max(this.percent, Math.max(1, Math.min(99, percent)));
            this.updatedAt = LocalDateTime.now();
        }

        private synchronized void done(boolean success, String message, Map<String, Object> result) {
            this.running = false;
            this.success = success;
            this.phase = "DONE";
            this.message = message == null ? "删除完成" : message;
            this.detail = result != null && result.get("notDeletedFiles") instanceof java.util.List<?>
                    && !((java.util.List<?>) result.get("notDeletedFiles")).isEmpty()
                    ? "有部分本地文件未删除，请按提示手动处理"
                    : "";
            this.processed = this.total;
            this.percent = 100;
            this.result = immutableCopy(result);
            this.updatedAt = LocalDateTime.now();
        }

        private synchronized void fail(String message) {
            this.running = false;
            this.success = false;
            this.phase = "FAILED";
            this.message = message == null ? "删除失败" : message;
            this.detail = "删除可能已完成部分步骤，请先检查房间、录制历史和本地文件，再根据错误提示重试";
            this.updatedAt = LocalDateTime.now();
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("found", true);
            map.put("taskId", taskId);
            map.put("roomDatabaseId", roomDatabaseId);
            map.put("roomId", roomId);
            map.put("running", running);
            map.put("success", success);
            map.put("phase", phase);
            map.put("message", message);
            map.put("detail", detail);
            map.put("processed", processed);
            map.put("total", total);
            map.put("percent", percent);
            map.put("historyCount", historyCount);
            map.put("deleteHistories", options.deleteHistories());
            map.put("deleteVideoFiles", options.deleteVideoFiles());
            map.put("deleteDanmakuFiles", options.deleteDanmakuFiles());
            map.put("deleteCoverFiles", options.deleteCoverFiles());
            map.put("startedAt", startedAt);
            map.put("updatedAt", updatedAt);
            map.put("elapsedSeconds", elapsedSeconds());
            map.put("result", result == null ? Collections.emptyMap() : result);
            return map;
        }

        private long elapsedSeconds() {
            LocalDateTime end = updatedAt == null ? LocalDateTime.now() : updatedAt;
            return Math.max(0L, Duration.between(startedAt, end).getSeconds());
        }

        private static Map<String, Object> immutableCopy(Map<String, Object> source) {
            if (source == null || source.isEmpty()) {
                return Collections.emptyMap();
            }
            return Collections.unmodifiableMap(new LinkedHashMap<>(source));
        }
    }
}
