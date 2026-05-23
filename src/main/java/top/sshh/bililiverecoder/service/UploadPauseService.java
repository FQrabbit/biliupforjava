package top.sshh.bililiverecoder.service;

import org.apache.commons.lang3.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.util.LogKvs;
import top.sshh.bililiverecoder.util.UploadProgressTracker;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class UploadPauseService {

    private static final int MAX_RESUME_RETRY_ATTEMPTS = 8;
    private static final long RESUME_RETRY_DELAY_MS = 1500L;

    private final Set<Long> pausedHistoryGuards = ConcurrentHashMap.newKeySet();
    private final Set<Long> pausedPartGuards = ConcurrentHashMap.newKeySet();

    private final ScheduledExecutorService resumeRetryScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "upload-resume-retry");
                t.setDaemon(true);
                return t;
            });

    @Autowired
    private RecordHistoryRepository historyRepository;
    @Autowired
    private RecordHistoryPartRepository partRepository;
    @Autowired
    private RecordRoomRepository roomRepository;
    @Autowired
    private UploadProgressTracker uploadProgressTracker;
    @Lazy
    @Autowired
    private UploadServiceFactory uploadServiceFactory;

    public boolean isUploadPaused(RecordHistory history, RecordHistoryPart part) {
        if (history != null && history.getId() != null && pausedHistoryGuards.contains(history.getId())) {
            return true;
        }
        if (part != null && part.getId() != null && pausedPartGuards.contains(part.getId())) {
            return true;
        }
        if (history != null && Boolean.TRUE.equals(history.getUploadPaused())) {
            return true;
        }
        return part != null && Boolean.TRUE.equals(part.getUploadPaused());
    }

    public boolean isUploadPaused(Long historyId, Long partId) {
        if (historyId != null && pausedHistoryGuards.contains(historyId)) {
            return true;
        }
        if (partId != null && pausedPartGuards.contains(partId)) {
            return true;
        }
        RecordHistory history = historyId == null ? null : historyRepository.findById(historyId).orElse(null);
        RecordHistoryPart part = partId == null ? null : partRepository.findById(partId).orElse(null);
        return isUploadPaused(history, part);
    }

    public String pauseMessage(RecordHistory history, RecordHistoryPart part) {
        if (part != null && Boolean.TRUE.equals(part.getUploadPaused()) && StringUtils.isNotBlank(part.getUploadPauseReason())) {
            return part.getUploadPauseReason();
        }
        if (history != null && Boolean.TRUE.equals(history.getUploadPaused()) && StringUtils.isNotBlank(history.getUploadPauseReason())) {
            return history.getUploadPauseReason();
        }
        return "用户已暂停上传，可稍后继续";
    }

    @Transactional
    public Map<String, Object> pauseHistory(Long historyId, String reason) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Optional<RecordHistory> historyOpt = historyRepository.findById(historyId);
        if (historyOpt.isEmpty()) {
            resp.put("type", "warning");
            resp.put("msg", "稿件不存在");
            return resp;
        }
        RecordHistory history = historyOpt.get();
        LocalDateTime now = LocalDateTime.now();
        String pauseReason = StringUtils.defaultIfBlank(reason, "用户暂停稿件上传");
        pausedHistoryGuards.add(historyId);
        history.setUploadPaused(true);
        history.setUploadPausedAt(now);
        history.setUploadPauseReason(pauseReason);
        historyRepository.save(history);

        int affected = 0;
        List<RecordHistoryPart> parts = partRepository.findByHistoryIdOrderByStartTimeAsc(historyId);
        for (RecordHistoryPart part : parts) {
            if (part == null || part.isUpload()) {
                continue;
            }
            pausedPartGuards.add(part.getId());
            part.setUploadPaused(true);
            part.setUploadPausedAt(now);
            part.setUploadPauseReason(pauseReason);
            partRepository.save(part);
            uploadProgressTracker.markPaused(part.getId(), pauseReason);
            affected++;
        }
        resp.put("type", "success");
        resp.put("msg", "已暂停上传");
        resp.put("affected", affected);
        return resp;
    }

    @Transactional
    public Map<String, Object> resumeHistory(Long historyId) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Optional<RecordHistory> historyOpt = historyRepository.findById(historyId);
        if (historyOpt.isEmpty()) {
            resp.put("type", "warning");
            resp.put("msg", "稿件不存在");
            return resp;
        }
        RecordHistory history = historyOpt.get();
        history.setUploadPaused(false);
        history.setUploadPausedAt(null);
        history.setUploadPauseReason(null);
        historyRepository.save(history);
        pausedHistoryGuards.remove(historyId);

        int triggered = 0;
        List<RecordHistoryPart> parts = partRepository.findByHistoryIdOrderByStartTimeAsc(historyId);
        for (RecordHistoryPart part : parts) {
            if (part == null || part.isUpload()) {
                continue;
            }
            part.setUploadPaused(false);
            part.setUploadPausedAt(null);
            part.setUploadPauseReason(null);
            partRepository.save(part);
            pausedPartGuards.remove(part.getId());
            if (triggerPartUpload(part)) {
                triggered++;
            } else {
                scheduleResumeRetry(part.getId(), 0);
            }
        }
        resp.put("type", "success");
        resp.put("msg", "已继续上传");
        resp.put("triggered", triggered);
        return resp;
    }

    @Transactional
    public Map<String, Object> pausePart(Long partId, String reason) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Optional<RecordHistoryPart> partOpt = partRepository.findById(partId);
        if (partOpt.isEmpty()) {
            resp.put("type", "warning");
            resp.put("msg", "分P不存在");
            return resp;
        }
        RecordHistoryPart part = partOpt.get();
        String pauseReason = StringUtils.defaultIfBlank(reason, "用户暂停分P上传");
        pausedPartGuards.add(partId);
        part.setUploadPaused(true);
        part.setUploadPausedAt(LocalDateTime.now());
        part.setUploadPauseReason(pauseReason);
        partRepository.save(part);
        uploadProgressTracker.markPaused(part.getId(), pauseReason);
        resp.put("type", "success");
        resp.put("msg", "已暂停分P上传");
        return resp;
    }

    @Transactional
    public Map<String, Object> resumePart(Long partId) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Optional<RecordHistoryPart> partOpt = partRepository.findById(partId);
        if (partOpt.isEmpty()) {
            resp.put("type", "warning");
            resp.put("msg", "分P不存在");
            return resp;
        }
        RecordHistoryPart part = partOpt.get();
        part.setUploadPaused(false);
        part.setUploadPausedAt(null);
        part.setUploadPauseReason(null);
        partRepository.save(part);
        pausedPartGuards.remove(partId);
        boolean triggered = !part.isUpload() && triggerPartUpload(part);
        if (!triggered && !part.isUpload()) {
            scheduleResumeRetry(part.getId(), 0);
        }
        resp.put("type", "success");
        resp.put("msg", triggered ? "已继续分P上传" : "已取消分P暂停");
        resp.put("triggered", triggered);
        return resp;
    }

    private boolean triggerPartUpload(RecordHistoryPart part) {
        if (part == null || part.isUpload()) {
            return false;
        }
        Optional<RecordHistory> historyOpt = part.getHistoryId() == null ? Optional.empty() : historyRepository.findById(part.getHistoryId());
        if (historyOpt.isEmpty() || !historyOpt.get().isUpload() || historyOpt.get().isPublish()) {
            return false;
        }
        RecordRoom room = roomRepository.findByRoomId(part.getRoomId());
        if (room == null) {
            return false;
        }
        return uploadServiceFactory.getUploadService(room.getLine()).asyncUploadIfNeeded(part);
    }

    private void scheduleResumeRetry(Long partId, int attempt) {
        if (partId == null || attempt >= MAX_RESUME_RETRY_ATTEMPTS) {
            return;
        }
        int nextAttempt = attempt + 1;
        log.debug("[BLR] {}", LogKvs.event("Upload.Resume.RetryScheduled")
                .add("partId", partId)
                .add("attempt", nextAttempt)
                .add("delayMs", RESUME_RETRY_DELAY_MS));
        resumeRetryScheduler.schedule(
                () -> retryPartUpload(partId, nextAttempt),
                RESUME_RETRY_DELAY_MS,
                TimeUnit.MILLISECONDS
        );
    }

    private void retryPartUpload(Long partId, int attempt) {
        try {
            Optional<RecordHistoryPart> partOpt = partRepository.findById(partId);
            if (partOpt.isEmpty()) {
                log.debug("[BLR] {}", LogKvs.event("Upload.Resume.RetryStop")
                        .add("partId", partId)
                        .add("attempt", attempt)
                        .add("reason", "PART_NOT_FOUND"));
                return;
            }
            RecordHistoryPart part = partOpt.get();
            if (part.isUpload()) {
                log.debug("[BLR] {}", LogKvs.event("Upload.Resume.RetryStop")
                        .add("partId", partId)
                        .add("historyId", part.getHistoryId())
                        .add("attempt", attempt)
                        .add("reason", "PART_UPLOADED"));
                return;
            }
            if (isUploadPaused(part.getHistoryId(), part.getId())) {
                log.debug("[BLR] {}", LogKvs.event("Upload.Resume.RetryStop")
                        .add("partId", partId)
                        .add("historyId", part.getHistoryId())
                        .add("attempt", attempt)
                        .add("reason", "PAUSED_AGAIN"));
                return;
            }
            boolean accepted = triggerPartUpload(part);
            log.debug("[BLR] {}", LogKvs.event("Upload.Resume.RetryAttempt")
                    .add("partId", partId)
                    .add("historyId", part.getHistoryId())
                    .add("attempt", attempt)
                    .add("accepted", accepted));
            if (!accepted) {
                scheduleResumeRetry(partId, attempt);
            }
        } catch (Exception ex) {
            log.warn("[BLR] {}", LogKvs.event("Upload.Resume.RetryFailed")
                    .add("partId", partId)
                    .add("attempt", attempt)
                    .addIfNotBlank("err", ex.getMessage())
                    .add("ex", ex.getClass().getSimpleName()), ex);
            scheduleResumeRetry(partId, attempt);
        }
    }
}
