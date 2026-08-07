package top.sshh.bililiverecoder.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.util.LogKvs;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/** 补偿 webhook 丢失、乱序造成的“分P已上传但稿件仍在录制”状态 */
@Slf4j
@Component
public class RecordHistoryStateReconciler {

    private final RecordHistoryRepository historyRepository;
    private final RecordHistoryPartRepository partRepository;
    private final RecordRoomRepository roomRepository;

    public RecordHistoryStateReconciler(RecordHistoryRepository historyRepository,
                                        RecordHistoryPartRepository partRepository,
                                        RecordRoomRepository roomRepository) {
        this.historyRepository = historyRepository;
        this.partRepository = partRepository;
        this.roomRepository = roomRepository;
    }

    @Scheduled(fixedDelayString = "${record.history-state-reconcile-interval-ms:300000}", initialDelay = 60000)
    public void reconcileRecentHistories() {
        LocalDateTime since = LocalDateTime.now().minusDays(7);
        List<RecordHistory> candidates = historyRepository.findRecentUnpublishedRecordingHistories(
                since, PageRequest.of(0, 200));
        for (RecordHistory candidate : candidates) {
            try {
                reconcile(candidate.getId());
            } catch (Exception e) {
                log.warn("[BLR] {}", LogKvs.event("HistoryStateReconcile.Failed")
                        .add("historyId", candidate.getId())
                        .add("err", e.getMessage())
                        .add("ex", e.getClass().getSimpleName()), e);
            }
        }
    }

    @Transactional
    public boolean reconcile(Long historyId) {
        RecordHistory history = historyRepository.findById(historyId).orElse(null);
        if (history == null || !history.isRecording() || !history.isUpload() || history.isPublish()
                || history.isForceArchived()) return false;

        RecordRoom room = roomRepository.findByRoomId(history.getRoomId());
        // 当前房间仍明确指向这条 history 且仍在录制，绝不猜测它已经结束
        if (room != null && room.isRecording() && Objects.equals(room.getHistoryId(), history.getId())) return false;

        List<RecordHistoryPart> parts = partRepository.findByHistoryId(history.getId());
        if (parts.isEmpty()) return false;
        boolean hasUploaded = false;
        for (RecordHistoryPart part : parts) {
            if (part.isRecording() || part.getEndTime() == null) return false;
            if (part.isUpload()) {
                hasUploaded = true;
            } else if (!isExplicitSkip(part)) {
                return false;
            }
        }
        if (!hasUploaded) return false;

        LocalDateTime now = LocalDateTime.now();
        history.setRecording(false);
        history.setStreaming(false);
        history.setEndTime(now);
        history.setUpdateTime(now);
        historyRepository.save(history);
        log.info("[BLR] {}", LogKvs.event("HistoryStateReconcile.Fixed")
                .add("roomId", history.getRoomId())
                .add("historyId", history.getId())
                .add("partCount", parts.size())
                .add("roomPointer", room == null ? null : room.getHistoryId())
                .add("roomRecording", room != null && room.isRecording()));
        return true;
    }

    private boolean isExplicitSkip(RecordHistoryPart part) {
        if (part.getUploadRetryCount() < 9999) return false;
        String type = part.getDeleteFailType();
        return "SKIPPED_THRESHOLD".equals(type) || "MANUAL_SKIP".equals(type);
    }
}
