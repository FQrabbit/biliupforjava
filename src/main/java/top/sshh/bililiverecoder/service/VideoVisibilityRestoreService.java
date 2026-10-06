package top.sshh.bililiverecoder.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import top.sshh.bililiverecoder.entity.BiliBiliUser;
import top.sshh.bililiverecoder.entity.VideoVisibilityRestoreTask;
import top.sshh.bililiverecoder.repo.BiliUserRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.VideoVisibilityRestoreTaskRepository;
import top.sshh.bililiverecoder.util.BiliApi;
import top.sshh.bililiverecoder.util.LogKvs;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
public class VideoVisibilityRestoreService {
    private final VideoVisibilityRestoreTaskRepository repository;
    private final BiliUserRepository users;
    private final TransactionTemplate transactions;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    private final ReentrantLock[] historyLocks = createHistoryLocks();
    @Autowired(required = false)
    private RecordHistoryRepository histories;

    public VideoVisibilityRestoreService(VideoVisibilityRestoreTaskRepository repository, BiliUserRepository users,
                                         PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.users = users;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public VideoVisibilityRestoreTask findByHistoryId(Long historyId) {
        return repository.findByHistoryId(historyId).orElse(null);
    }

    @Transactional(readOnly = true)
    public String historyDeletionBlockReason(Long historyId) {
        VideoVisibilityRestoreTask task = repository.findByHistoryId(historyId).orElse(null);
        if (task == null || "COMPLETE".equals(task.getState())) return null;
        return "视频可见性恢复任务尚未完成（当前状态：" + task.getState()
                + (task.getErrorMessage() == null || task.getErrorMessage().isBlank()
                ? "" : "，" + task.getErrorMessage()) + "），完成恢复后再删除稿件";
    }

    public ForceArchiveGuard tryAcquireForceArchiveGuard(Long historyId) {
        ReentrantLock lock = historyLock(historyId);
        lock.lock();
        String blockReason = historyDeletionBlockReason(historyId);
        if (blockReason != null) {
            lock.unlock();
            return new ForceArchiveGuard(blockReason, null);
        }
        return new ForceArchiveGuard(null, lock);
    }

    @Transactional
    public void deleteCompletedForHistory(Long historyId) {
        VideoVisibilityRestoreTask task = repository.findByHistoryId(historyId).orElse(null);
        if (task != null && "COMPLETE".equals(task.getState())) repository.delete(task);
    }

    @PostConstruct
    public void recoverInterruptedTasks() {
        List<VideoVisibilityRestoreTask> interrupted = repository.findByStateIn(
                List.of("PREPARING", "ACTIVE", "RESTORING"));
        for (VideoVisibilityRestoreTask task : interrupted) {
            task.setState("PENDING");
            task.setNextAttemptAt(LocalDateTime.now());
            task.setUpdatedAt(LocalDateTime.now());
        }
        repository.saveAll(interrupted);
    }

    @Transactional
    public VideoVisibilityRestoreTask ensurePreparing(Long historyId, Long accountId, String aid, int visibility) {
        ReentrantLock lock = historyLock(historyId);
        lock.lock();
        try {
            if (histories != null && histories.findByIdForUpdate(historyId)
                    .map(history -> history.isDeletePending() || history.isProcessingArchived() || history.isSendReply()).orElse(true)) {
                throw new IllegalStateException("稿件已归档或评论流程已结束，不能创建可见性恢复任务");
            }
            VideoVisibilityRestoreTask task = repository.findByHistoryId(historyId).orElseGet(VideoVisibilityRestoreTask::new);
            if (task.getId() != null && !"COMPLETE".equals(task.getState())) return task;
            LocalDateTime now = LocalDateTime.now();
            task.setHistoryId(historyId);
            task.setAccountId(accountId);
            task.setAid(aid);
            task.setRestoreVisibility(visibility);
            task.setState("PREPARING");
            task.setAttemptCount(0);
            task.setNextAttemptAt(null);
            task.setErrorMessage(null);
            if (task.getCreatedAt() == null) task.setCreatedAt(now);
            task.setUpdatedAt(now);
            return repository.save(task);
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    public boolean markVideoPublic(Long taskId) {
        VideoVisibilityRestoreTask current = repository.findById(taskId).orElse(null);
        if (current == null) return false;
        ReentrantLock lock = historyLock(current.getHistoryId());
        lock.lock();
        try {
            VideoVisibilityRestoreTask task = repository.findById(taskId).orElse(null);
            if (task == null || !"PREPARING".equals(task.getState())) return false;
            task.setState("ACTIVE");
            task.setNextAttemptAt(null);
            task.setUpdatedAt(LocalDateTime.now());
            repository.save(task);
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    public void requestRestore(Long taskId) {
        VideoVisibilityRestoreTask current = repository.findById(taskId).orElse(null);
        if (current == null) return;
        ReentrantLock lock = historyLock(current.getHistoryId());
        lock.lock();
        try {
            VideoVisibilityRestoreTask task = repository.findById(taskId).orElse(null);
            if (task == null || !("PREPARING".equals(task.getState()) || "ACTIVE".equals(task.getState()))) return;
            task.setState("PENDING");
            task.setNextAttemptAt(LocalDateTime.now());
            task.setUpdatedAt(LocalDateTime.now());
            repository.save(task);
        } finally {
            lock.unlock();
        }
    }

    public boolean restoreNow(Long taskId) {
        if (taskId == null || !inFlight.add(taskId)) return false;
        try {
            // 同类内部调用不会触发事务代理，先提交领取状态，再请求平台
            VideoVisibilityRestoreTask task = transactions.execute(status -> beginAttempt(taskId));
            if (task == null) return false;
            BiliBiliUser user = users.findById(task.getAccountId()).orElse(null);
            if (user == null || !user.isLogin()) {
                return failedAttempt(task, "原投稿账号未登录，等待重新登录后恢复视频状态");
            }
            try {
                String response = BiliApi.updateVideoVisibility(user, Long.parseLong(task.getAid()),
                        task.getRestoreVisibility());
                JSONObject json = JSON.parseObject(response);
                if (json == null || json.getIntValue("code") != 0) {
                    String message = json == null ? "平台未返回恢复结果" : json.getString("message");
                    return failedAttempt(task, "恢复视频可见性失败：" + message);
                }
                transactions.executeWithoutResult(status -> markComplete(task.getId()));
                log.info("[BLR] {}", LogKvs.event("VideoVisibility.Restore.Success")
                        .add("historyId", task.getHistoryId()).add("aid", task.getAid())
                        .add("accountId", task.getAccountId()));
                return true;
            } catch (Exception error) {
                return failedAttempt(task, "恢复视频可见性异常：" + error.getMessage());
            }
        } finally {
            inFlight.remove(taskId);
        }
    }

    @Scheduled(fixedDelayString = "${publish.visibility-restore-poll-ms:30000}")
    public void processDueTasks() {
        List<VideoVisibilityRestoreTask> due = repository.findByStateAndNextAttemptAtLessThanEqualOrderByUpdatedAtAsc(
                "PENDING", LocalDateTime.now(), PageRequest.of(0, 20));
        due.forEach(task -> restoreNow(task.getId()));
    }

    private VideoVisibilityRestoreTask beginAttempt(Long taskId) {
        VideoVisibilityRestoreTask current = repository.findById(taskId).orElse(null);
        if (current == null) return null;
        ReentrantLock lock = historyLock(current.getHistoryId());
        lock.lock();
        try {
            VideoVisibilityRestoreTask task = repository.findById(taskId).orElse(null);
            if (task == null || !"PENDING".equals(task.getState())) return null;
            if (histories != null && histories.findByIdForUpdate(task.getHistoryId())
                    .map(RecordHistory -> RecordHistory.isDeletePending() || RecordHistory.isProcessingArchived())
                    .orElse(true)) return null;
            task.setState("RESTORING");
            task.setAttemptCount(task.getAttemptCount() + 1);
            task.setUpdatedAt(LocalDateTime.now());
            return repository.save(task);
        } finally {
            lock.unlock();
        }
    }

    private void markComplete(Long taskId) {
        VideoVisibilityRestoreTask task = repository.findById(taskId).orElse(null);
        if (task == null) return;
        task.setState("COMPLETE");
        task.setNextAttemptAt(null);
        task.setErrorMessage(null);
        task.setUpdatedAt(LocalDateTime.now());
        repository.save(task);
    }

    private boolean failedAttempt(VideoVisibilityRestoreTask task, String message) {
        transactions.executeWithoutResult(status -> {
            VideoVisibilityRestoreTask current = repository.findById(task.getId()).orElse(task);
            current.setState("PENDING");
            current.setErrorMessage(message == null || message.length() <= 2000 ? message : message.substring(0, 2000));
            current.setUpdatedAt(LocalDateTime.now());
            current.setNextAttemptAt(LocalDateTime.now().plusMinutes(Math.min(60, Math.max(1, current.getAttemptCount() * 5L))));
            repository.save(current);
            log.warn("[BLR] {}", LogKvs.event("VideoVisibility.Restore.Retry")
                    .add("historyId", current.getHistoryId()).add("aid", current.getAid())
                    .add("attempt", current.getAttemptCount()).addIfNotBlank("reason", current.getErrorMessage()));
        });
        return false;
    }

    private ReentrantLock historyLock(Long historyId) {
        return historyId == null ? historyLocks[0]
                : historyLocks[Math.floorMod(historyId.hashCode(), historyLocks.length)];
    }

    private static ReentrantLock[] createHistoryLocks() {
        ReentrantLock[] locks = new ReentrantLock[256];
        for (int i = 0; i < locks.length; i++) locks[i] = new ReentrantLock();
        return locks;
    }

    public static final class ForceArchiveGuard implements AutoCloseable {
        private final String blockReason;
        private final ReentrantLock lock;

        private ForceArchiveGuard(String blockReason, ReentrantLock lock) {
            this.blockReason = blockReason;
            this.lock = lock;
        }

        public String blockReason() { return blockReason; }

        @Override
        public void close() {
            if (lock != null && lock.isHeldByCurrentThread()) lock.unlock();
        }
    }
}
