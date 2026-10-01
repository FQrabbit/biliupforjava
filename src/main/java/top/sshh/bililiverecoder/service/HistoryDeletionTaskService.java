package top.sshh.bililiverecoder.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.data.domain.PageRequest;
import top.sshh.bililiverecoder.entity.HistoryDeletionTask;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.repo.HistoryDeletionTaskRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class HistoryDeletionTaskService {
    private static final int MAX_ATTEMPTS = 20;

    private final HistoryDeletionTaskRepository tasks;
    private final RecordHistoryRepository histories;
    private final RecordHistoryPartRepository parts;

    public HistoryDeletionTaskService(HistoryDeletionTaskRepository tasks, RecordHistoryRepository histories,
                                      RecordHistoryPartRepository parts) {
        this.tasks = tasks;
        this.histories = histories;
        this.parts = parts;
    }

    @Transactional
    public HistoryDeletionTask accept(Long historyId, String roomId, HistoryDeletionService.DeleteOptions options) {
        RecordHistory history = histories.findByIdForUpdate(historyId).orElse(null);
        if (history == null) return null;
        if (history.isRecording() || parts.findByHistoryIdOrderByStartTimeAsc(historyId).stream()
                .anyMatch(part -> part.isRecording() || part.getEndTime() == null)) {
            throw new IllegalStateException("正在录制的稿件不能删除，请先结束录制");
        }

        HistoryDeletionTask existing = tasks.findByHistoryId(historyId).orElse(null);
        if (existing != null) {
            if ("CANCELLED".equals(existing.getState())) {
                LocalDateTime now = LocalDateTime.now();
                existing.setRoomId(roomId == null ? history.getRoomId() : roomId);
                existing.setDeleteVideo(options.deleteVideo());
                existing.setDeleteDanmaku(options.deleteDanmaku());
                existing.setDeleteCover(options.deleteCover());
                existing.setDeletionStarted(false);
                existing.setState("PENDING");
                existing.setAttemptCount(0);
                existing.setNextAttemptAt(now);
                existing.setLastError(null);
                existing.setUpdatedAt(now);
                history.setDeletePending(true);
                histories.save(history);
                return tasks.saveAndFlush(existing);
            }
            if (!sameOptions(existing, options)) {
                throw new IllegalStateException("该稿件已有删除任务，不能更改删除选项；请等待任务完成或重试原任务");
            }
            return existing;
        }

        LocalDateTime now = LocalDateTime.now();
        history.setDeletePending(true);
        histories.save(history);

        HistoryDeletionTask task = new HistoryDeletionTask();
        task.setHistoryId(historyId);
        task.setRoomId(roomId == null ? history.getRoomId() : roomId);
        task.setDeleteVideo(options.deleteVideo());
        task.setDeleteDanmaku(options.deleteDanmaku());
        task.setDeleteCover(options.deleteCover());
        task.setDeletionStarted(false);
        task.setState("PENDING");
        task.setAttemptCount(0);
        task.setNextAttemptAt(now);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        return tasks.saveAndFlush(task);
    }

    @Transactional(readOnly = true)
    public boolean isDeleting(Long historyId) {
        if (historyId == null) return false;
        return histories.findById(historyId).map(RecordHistory::isDeletePending).orElse(false);
    }

    @Transactional
    public HistoryDeletionTask claim(Long taskId) {
        HistoryDeletionTask task = tasks.findByIdForUpdate(taskId).orElse(null);
        if (task == null || !("PENDING".equals(task.getState()) || "RETRY_WAIT".equals(task.getState()))) return null;
        if (task.getNextAttemptAt() != null && task.getNextAttemptAt().isAfter(LocalDateTime.now())) return null;
        task.setState("RUNNING");
        task.setAttemptCount(task.getAttemptCount() + 1);
        task.setNextAttemptAt(null);
        task.setLastError(null);
        task.setUpdatedAt(LocalDateTime.now());
        return tasks.save(task);
    }

    @Transactional
    public boolean beginExecution(Long taskId) {
        HistoryDeletionTask task = tasks.findByIdForUpdate(taskId).orElse(null);
        if (task == null || !"RUNNING".equals(task.getState())) return false;
        if (task.isDeletionStarted()) return true;
        task.setDeletionStarted(true);
        task.setUpdatedAt(LocalDateTime.now());
        tasks.save(task);
        return true;
    }

    @Transactional
    public CancelResult cancel(Long taskId) {
        Long historyId = tasks.findHistoryIdByTaskId(taskId).orElse(null);
        if (historyId == null) return new CancelResult(false, false, "删除任务不存在", null);

        RecordHistory history = histories.findByIdForUpdate(historyId).orElse(null);
        HistoryDeletionTask task = tasks.findByIdForUpdate(taskId).orElse(null);
        if (task == null) return new CancelResult(false, false, "删除任务不存在", null);
        if ("CANCELLED".equals(task.getState())) return new CancelResult(true, true, "删除任务已取消", task);
        if (task.isDeletionStarted()) return new CancelResult(false, false, "删除已经开始，不能取消；请等待任务完成或检查结果", task);
        if (!("PENDING".equals(task.getState()) || "RETRY_WAIT".equals(task.getState())
                || "NEEDS_ACTION".equals(task.getState()) || "RUNNING".equals(task.getState()))) {
            return new CancelResult(false, false, "当前删除任务状态不能取消", task);
        }

        LocalDateTime now = LocalDateTime.now();
        task.setState("CANCELLED");
        task.setNextAttemptAt(null);
        task.setLastError(null);
        task.setUpdatedAt(now);
        tasks.save(task);
        if (history != null) {
            history.setDeletePending(false);
            histories.save(history);
        }
        return new CancelResult(true, false, "删除任务已取消，稿件队列已恢复", task);
    }

    @Transactional(readOnly = true)
    public boolean canCancel(HistoryDeletionTask task) {
        return task != null && !task.isDeletionStarted()
                && List.of("PENDING", "RETRY_WAIT", "NEEDS_ACTION", "RUNNING").contains(task.getState());
    }

    @Transactional
    public HistoryDeletionTask defer(Long taskId, String reason) {
        HistoryDeletionTask task = tasks.findByIdForUpdate(taskId).orElse(null);
        if (task == null || "COMPLETED".equals(task.getState()) || "CANCELLED".equals(task.getState())) return task;
        LocalDateTime now = LocalDateTime.now();
        task.setLastError(truncate(reason, 2000));
        task.setUpdatedAt(now);
        if (task.getAttemptCount() >= MAX_ATTEMPTS) {
            task.setState("NEEDS_ACTION");
            task.setNextAttemptAt(null);
        } else {
            task.setState("RETRY_WAIT");
            task.setNextAttemptAt(now.plusSeconds(Math.min(300, 15L * task.getAttemptCount())));
        }
        return tasks.save(task);
    }

    @Transactional
    public HistoryDeletionTask retry(Long taskId) {
        HistoryDeletionTask task = tasks.findByIdForUpdate(taskId).orElse(null);
        if (task == null || !"NEEDS_ACTION".equals(task.getState())) return null;
        task.setState("PENDING");
        task.setAttemptCount(0);
        task.setNextAttemptAt(LocalDateTime.now());
        task.setLastError(null);
        task.setUpdatedAt(LocalDateTime.now());
        return tasks.save(task);
    }

    @Transactional(readOnly = true)
    public HistoryDeletionTask findByHistoryId(Long historyId) {
        return tasks.findByHistoryId(historyId).orElse(null);
    }

    @Transactional(readOnly = true)
    public HistoryDeletionTask findById(Long taskId) {
        return tasks.findById(taskId).orElse(null);
    }

    @Transactional(readOnly = true)
    public Map<Long, HistoryDeletionTask> findByHistoryIds(Collection<Long> historyIds) {
        if (historyIds == null || historyIds.isEmpty()) return Map.of();
        return tasks.findByHistoryIdIn(historyIds).stream()
                .collect(Collectors.toMap(HistoryDeletionTask::getHistoryId, Function.identity(), (left, right) -> left));
    }

    @PostConstruct
    public void recoverInterruptedTasks() {
        int recovered = tasks.recoverInterrupted(LocalDateTime.now());
        if (recovered > 0) log.warn("Recovered {} interrupted history deletion tasks", recovered);
    }

    @Transactional(readOnly = true)
    public List<Long> dueTaskIds(int limit) {
        return tasks.findByStateInAndNextAttemptAtLessThanEqualOrderByIdAsc(
                        List.of("PENDING", "RETRY_WAIT"), LocalDateTime.now(),
                        PageRequest.of(0, Math.max(1, Math.min(200, limit))))
                .stream().map(HistoryDeletionTask::getId).toList();
    }

    @Scheduled(cron = "0 40 4 * * *")
    public void pruneCompletedTasks() {
        tasks.deleteOldCompleted(LocalDateTime.now().minusDays(30));
    }

    private static boolean sameOptions(HistoryDeletionTask task, HistoryDeletionService.DeleteOptions options) {
        return task.isDeleteVideo() == options.deleteVideo()
                && task.isDeleteDanmaku() == options.deleteDanmaku()
                && task.isDeleteCover() == options.deleteCover();
    }

    public record CancelResult(boolean success, boolean alreadyCancelled, String message, HistoryDeletionTask task) {}

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
