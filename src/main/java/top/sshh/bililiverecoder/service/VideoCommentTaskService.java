package top.sshh.bililiverecoder.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.VideoCommentTask;
import top.sshh.bililiverecoder.repo.VideoCommentTaskRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.entity.data.BiliReply;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class VideoCommentTaskService {
    private final VideoCommentTaskRepository repository;
    private final ReentrantLock[] historyLocks = createHistoryLocks();
    @Autowired(required = false)
    private RecordHistoryRepository historyRepository;

    public VideoCommentTaskService(VideoCommentTaskRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public VideoCommentTask findById(Long id) {
        return repository.findById(id).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<VideoCommentTask> findByHistoryId(Long historyId) {
        return repository.findByHistoryIdOrderBySequenceAsc(historyId);
    }

    @Transactional(readOnly = true)
    public String historyDeletionBlockReason(Long historyId) {
        return repository.findByHistoryIdOrderBySequenceAsc(historyId).stream()
                .filter(task -> "SUBMITTING".equals(task.getState()) || "NEEDS_ACTION".equals(task.getState())
                        || "SUBMITTING".equals(task.getPinState()))
                .findFirst()
                .map(task -> "评论或置顶任务仍在提交或结果待核对（任务 " + task.getId()
                        + "），请先确认线上评论状态再删除稿件")
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public String forceArchiveBlockReason(Long historyId) {
        return historyDeletionBlockReason(historyId);
    }

    public ForceArchiveGuard tryAcquireForceArchiveGuard(Long historyId) {
        if (historyId == null) return new ForceArchiveGuard("稿件编号无效", null);
        ReentrantLock lock = historyLock(historyId);
        lock.lock();
        String blockReason = forceArchiveBlockReason(historyId);
        if (blockReason != null) {
            lock.unlock();
            return new ForceArchiveGuard(blockReason, null);
        }
        cancelUnsubmittedForHistoryLocked(historyId);
        return new ForceArchiveGuard(null, lock);
    }

    @Transactional
    public int cancelUnsubmittedForHistory(Long historyId) {
        ReentrantLock lock = historyLock(historyId);
        lock.lock();
        try {
            String reason = forceArchiveBlockReason(historyId);
            if (reason != null) throw new IllegalStateException(reason);
            return cancelUnsubmittedForHistoryLocked(historyId);
        } finally {
            lock.unlock();
        }
    }

    private int cancelUnsubmittedForHistoryLocked(Long historyId) {
        int changed = 0;
        LocalDateTime now = LocalDateTime.now();
        for (VideoCommentTask task : repository.findByHistoryIdOrderBySequenceAsc(historyId)) {
            boolean taskChanged = false;
            if ("READY".equals(task.getState())) {
                task.setState("CANCELLED");
                task.setErrorMessage("稿件已强制归档，未发送的评论任务已取消");
                task.setUpdatedAt(now);
                taskChanged = true;
            }
            if ("PENDING".equals(task.getPinState()) || "RETRY".equals(task.getPinState())) {
                task.setPinState("CANCELLED");
                if (task.getErrorMessage() == null || task.getErrorMessage().isBlank()) {
                    task.setErrorMessage("稿件已强制归档，未执行的置顶任务已取消");
                }
                task.setUpdatedAt(now);
                taskChanged = true;
            }
            if (taskChanged) changed++;
        }
        if (changed > 0) repository.saveAll(repository.findByHistoryIdOrderBySequenceAsc(historyId));
        return changed;
    }

    @Transactional
    public void deleteForHistory(Long historyId) {
        repository.deleteAll(repository.findByHistoryIdOrderBySequenceAsc(historyId));
    }

    public boolean blocksAutomaticResume(List<VideoCommentTask> tasks) {
        return tasks.stream().anyMatch(task -> "NEEDS_ACTION".equals(task.getState())
                || "SUBMITTING".equals(task.getState())
                || "SUBMITTING".equals(task.getPinState()));
    }

    @Transactional
    public boolean confirmNotSentAndRetry(Long historyId, Long taskId) {
        VideoCommentTask task = repository.findById(taskId).orElse(null);
        if (task == null || !historyId.equals(task.getHistoryId()) || !"NEEDS_ACTION".equals(task.getState())) {
            return false;
        }
        task.setState("READY");
        task.setErrorMessage(null);
        task.setUpdatedAt(LocalDateTime.now());
        repository.save(task);
        return true;
    }

    @PostConstruct
    public void recoverInterruptedComments() {
        repository.markInterruptedSubmissions();
        repository.markInterruptedPinSubmissions();
    }

    @Transactional
    public List<VideoCommentTask> prepare(Long historyId, Long accountId, List<BiliReply> replies) {
        ReentrantLock lock = historyLock(historyId);
        lock.lock();
        try {
            if (historyRepository != null && historyRepository.findByIdForUpdate(historyId)
                    .map(history -> history.isDeletePending() || history.isForceArchived() || history.isSendReply()).orElse(true)) {
                return repository.findByHistoryIdOrderBySequenceAsc(historyId);
            }
            return prepareLocked(historyId, accountId, replies);
        } finally {
            lock.unlock();
        }
    }

    private List<VideoCommentTask> prepareLocked(Long historyId, Long accountId, List<BiliReply> replies) {
        List<VideoCommentTask> existing = repository.findByHistoryIdOrderBySequenceAsc(historyId);
        List<String> hashes = new ArrayList<>(replies.size());
        for (int i = 0; i < replies.size(); i++) {
            BiliReply reply = replies.get(i);
            hashes.add(hash(historyId, accountId, i, reply.getOid(), reply.getMessage()));
        }
        if (existing.isEmpty()) {
            LocalDateTime now = LocalDateTime.now();
            for (int i = 0; i < replies.size(); i++) {
                BiliReply reply = replies.get(i);
                VideoCommentTask task = new VideoCommentTask();
                task.setHistoryId(historyId);
                task.setAccountId(accountId);
                task.setSequence(i);
                task.setRequestHash(hashes.get(i));
                task.setAid(reply.getOid());
                task.setContent(reply.getMessage());
                task.setState("READY");
                task.setPinState(i == 0 ? "PENDING" : "NONE");
                task.setCreatedAt(now);
                task.setUpdatedAt(now);
                existing.add(task);
            }
            return repository.saveAll(existing);
        }

        boolean same = existing.size() == replies.size();
        if (same) {
            for (int i = 0; i < existing.size(); i++) {
                VideoCommentTask task = existing.get(i);
                if (!java.util.Objects.equals(task.getRequestHash(), hashes.get(i))
                        || !java.util.Objects.equals(task.getAccountId(), accountId)) {
                    same = false;
                    break;
                }
            }
        }
        if (same) return existing;

        boolean untouched = existing.stream().allMatch(task -> "READY".equals(task.getState()));
        if (!untouched) {
            existing.forEach(task -> {
                if (!"SENT".equals(task.getState())) {
                    task.setState("NEEDS_ACTION");
                    task.setErrorMessage("评论内容或账号与已保存任务不一致，请人工核对");
                    task.setUpdatedAt(LocalDateTime.now());
                }
            });
            repository.saveAll(existing);
            return existing;
        }

        if (existing.size() != replies.size()) {
            repository.deleteAll(existing);
            repository.flush();
            existing.clear();
            LocalDateTime now = LocalDateTime.now();
            for (int i = 0; i < replies.size(); i++) {
                BiliReply reply = replies.get(i);
                VideoCommentTask task = new VideoCommentTask();
                task.setHistoryId(historyId);
                task.setAccountId(accountId);
                task.setSequence(i);
                task.setRequestHash(hashes.get(i));
                task.setAid(reply.getOid());
                task.setContent(reply.getMessage());
                task.setState("READY");
                task.setPinState(i == 0 ? "PENDING" : "NONE");
                task.setCreatedAt(now);
                task.setUpdatedAt(now);
                existing.add(task);
            }
            return repository.saveAll(existing);
        }

        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < replies.size(); i++) {
            BiliReply reply = replies.get(i);
            VideoCommentTask task = existing.get(i);
            task.setAccountId(accountId);
            task.setSequence(i);
            task.setRequestHash(hashes.get(i));
            task.setAid(reply.getOid());
            task.setContent(reply.getMessage());
            task.setState("READY");
            task.setPinState(i == 0 ? "PENDING" : "NONE");
            task.setRemoteRpid(null);
            task.setRootRpid(null);
            task.setErrorMessage(null);
            task.setUpdatedAt(now);
        }
        return repository.saveAll(existing);
    }

    @Transactional
    public VideoCommentTask markSubmitting(Long id) {
        VideoCommentTask task = repository.findById(id).orElse(null);
        if (task == null || !"READY".equals(task.getState())) return task;
        ReentrantLock lock = historyLock(task.getHistoryId());
        lock.lock();
        try {
            task = repository.findById(id).orElse(null);
            if (task == null || !"READY".equals(task.getState())) return task;
            if (historyRepository != null && historyRepository.findByIdForUpdate(task.getHistoryId())
                    .map(history -> history.isDeletePending() || history.isForceArchived() || history.isSendReply()).orElse(true)) return task;
            task.setState("SUBMITTING");
            task.setUpdatedAt(LocalDateTime.now());
            task.setErrorMessage(null);
            return repository.save(task);
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    public VideoCommentTask markSent(Long id, String rpid, String rootRpid) {
        VideoCommentTask task = repository.findById(id).orElseThrow();
        task.setState("SENT");
        task.setRemoteRpid(rpid);
        task.setRootRpid(rootRpid);
        task.setUpdatedAt(LocalDateTime.now());
        task.setErrorMessage(null);
        return repository.save(task);
    }

    @Transactional
    public VideoCommentTask markNeedsAction(Long id, String message) {
        VideoCommentTask task = repository.findById(id).orElseThrow();
        task.setState("NEEDS_ACTION");
        task.setErrorMessage(message);
        task.setUpdatedAt(LocalDateTime.now());
        return repository.save(task);
    }

    @Transactional
    public VideoCommentTask markPinned(Long id) {
        VideoCommentTask task = repository.findById(id).orElseThrow();
        task.setPinState("PINNED");
        task.setErrorMessage(null);
        task.setUpdatedAt(LocalDateTime.now());
        return repository.save(task);
    }

    @Transactional
    public VideoCommentTask markPinSubmitting(Long id) {
        VideoCommentTask task = repository.findById(id).orElse(null);
        if (task == null || !"SENT".equals(task.getState())
                || !("PENDING".equals(task.getPinState()) || "RETRY".equals(task.getPinState()))) return task;
        ReentrantLock lock = historyLock(task.getHistoryId());
        lock.lock();
        try {
            task = repository.findById(id).orElse(null);
            if (task == null || !"SENT".equals(task.getState())
                    || !("PENDING".equals(task.getPinState()) || "RETRY".equals(task.getPinState()))) return task;
            if (historyRepository != null && historyRepository.findById(task.getHistoryId())
                    .map(history -> history.isForceArchived() || history.isSendReply()).orElse(true)) return task;
            task.setPinState("SUBMITTING");
            task.setUpdatedAt(LocalDateTime.now());
            task.setErrorMessage(null);
            return repository.save(task);
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    public VideoCommentTask markPinRetry(Long id, String message) {
        VideoCommentTask task = repository.findById(id).orElseThrow();
        task.setPinState("RETRY");
        task.setErrorMessage(message);
        task.setUpdatedAt(LocalDateTime.now());
        return repository.save(task);
    }

    private static String hash(Long historyId, Long accountId, int sequence, String aid, String content) {
        try {
            String data = historyId + "\n" + accountId + "\n" + sequence + "\n" + aid + "\n" + content;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("无法计算评论请求指纹", error);
        }
    }

    private ReentrantLock historyLock(Long historyId) {
        if (historyId == null) return historyLocks[0];
        return historyLocks[Math.floorMod(historyId.hashCode(), historyLocks.length)];
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
