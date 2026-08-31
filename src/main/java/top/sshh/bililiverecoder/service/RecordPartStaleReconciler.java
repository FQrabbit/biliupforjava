package top.sshh.bililiverecoder.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

import java.io.File;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

//当 FileClosed/RecordEnd 丢失时，关闭稳定的录制片段
@Slf4j
@Component
public class RecordPartStaleReconciler {
    private static final int BATCH_SIZE = 200;

    private final RecordHistoryPartRepository partRepository;
    private final RecordHistoryRepository historyRepository;
    private final RecordRoomRepository roomRepository;
    private final PartFileLocationService locationService;
    private final AtomicLong scanCursor = new AtomicLong();

    @Value("${record.part-stable-threshold-ms:600000}")
    private long stableThresholdMs = 600000L;

    public RecordPartStaleReconciler(RecordHistoryPartRepository partRepository,
                                     RecordHistoryRepository historyRepository,
                                     RecordRoomRepository roomRepository,
                                     PartFileLocationService locationService) {
        this.partRepository = partRepository;
        this.historyRepository = historyRepository;
        this.roomRepository = roomRepository;
        this.locationService = locationService;
    }

    @Scheduled(fixedDelayString = "${record.part-state-reconcile-interval-ms:300000}", initialDelay = 60000)
    public void reconcileScheduled() {
        reconcileBatch();
    }

    @Transactional
    public int reconcileBatch() {
        long nowMs = System.currentTimeMillis();
        int closed = 0;
        Set<Long> touchedHistories = new HashSet<>();
        long cursor = scanCursor.get();
        List<RecordHistoryPart> candidates = partRepository.findOpenCandidatesAfterId(cursor, PageRequest.of(0, BATCH_SIZE));
        if (candidates.isEmpty() && cursor != 0L) {
            scanCursor.set(0L);
            return 0;
        }
        for (RecordHistoryPart part : candidates) {
            if (part == null || part.getId() == null) continue;
            long partId = part.getId();
            RecordRoom room = roomRepository.findByRoomId(part.getRoomId());
            if (part.getHistoryId() == null) { scanCursor.accumulateAndGet(partId, Math::max); continue; }
            if (room == null || (room.isRecording() && part.getHistoryId().equals(room.getHistoryId()))) { scanCursor.accumulateAndGet(partId, Math::max); continue; }

            PartFileLocationService.FileResolution resolution = locationService.resolveReadable(part.getId());
            if (!resolution.available()) { scanCursor.accumulateAndGet(partId, Math::max); continue; }
            File file = resolution.path().toFile();
            if (!file.isFile() || file.length() <= 0 || file.lastModified() <= 0
                    || file.lastModified() > nowMs - Math.max(0L, stableThresholdMs)) { scanCursor.accumulateAndGet(partId, Math::max); continue; }

            LocalDateTime endTime = Instant.ofEpochMilli(file.lastModified())
                    .atZone(ZoneId.systemDefault()).toLocalDateTime();
            if (part.getStartTime() != null && endTime.isBefore(part.getStartTime())) {
                log.warn("[BLR] {}", LogKvs.event("PartReconcile.Skip.InvalidFileTime")
                        .add("partId", part.getId()).add("historyId", part.getHistoryId())
                        .add("filePath", file.getAbsolutePath()));
                scanCursor.accumulateAndGet(partId, Math::max);
                continue;
            }

            boolean changed = part.isRecording() || part.getEndTime() == null
                    || part.getFileSize() != file.length();
            part.setRecording(false);
            part.setEndTime(part.getEndTime() == null ? endTime : part.getEndTime());
            part.setFileSize(file.length());
            if (part.getDuration() <= 0 && part.getStartTime() != null && part.getEndTime() != null) {
                long duration = java.time.Duration.between(part.getStartTime(), part.getEndTime()).getSeconds();
                if (duration > 0) {
                    part.setDuration(duration);
                    changed = true;
                }
            }
            part.setUpdateTime(LocalDateTime.now());
            if (changed) {
                partRepository.save(part);
                closed++;
                touchedHistories.add(part.getHistoryId());
                log.info("[BLR] {}", LogKvs.event("PartReconcile.Closed")
                        .add("partId", part.getId()).add("historyId", part.getHistoryId())
                        .add("filePath", file.getAbsolutePath()).add("fileSizeBytes", file.length()));
            }
            scanCursor.accumulateAndGet(partId, Math::max);
        }
        for (Long historyId : touchedHistories) {
            closeHistoryWhenNoOpenParts(historyId);
        }
        if (closed > 0) {
            log.info("[BLR] {}", LogKvs.event("PartReconcile.Done").add("closed", closed));
        }
        return closed;
    }

    private void closeHistoryWhenNoOpenParts(Long historyId) {
        RecordHistory history = historyRepository.findById(historyId).orElse(null);
        if (history == null || !history.isRecording() || history.isForceArchived()) return;
        RecordRoom room = roomRepository.findByRoomId(history.getRoomId());
        if (room != null && room.isRecording() && historyId.equals(room.getHistoryId())) return;
        if (partRepository.countActuallyRecordingPartsByHistoryId(historyId) > 0) return;
        LocalDateTime now = LocalDateTime.now();
        history.setRecording(false);
        history.setStreaming(false);
        if (history.getEndTime() == null) history.setEndTime(now);
        history.setUpdateTime(now);
        historyRepository.save(history);
        log.info("[BLR] {}", LogKvs.event("PartReconcile.HistoryClosed").add("historyId", historyId));
    }
}
