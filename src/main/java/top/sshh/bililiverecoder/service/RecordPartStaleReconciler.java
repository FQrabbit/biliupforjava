package top.sshh.bililiverecoder.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.util.LogKvs;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

//当 FileClosed/RecordEnd 丢失时，关闭稳定的录制片段
@Slf4j
@Component
public class RecordPartStaleReconciler {
    private static final int BATCH_SIZE = 200;

    private final RecordHistoryPartRepository partRepository;
    private final RecordPartRecordingStateService recordingStateService;
    private final AtomicLong scanCursor = new AtomicLong();

    public RecordPartStaleReconciler(RecordHistoryPartRepository partRepository,
                                     RecordPartRecordingStateService recordingStateService) {
        this.partRepository = partRepository;
        this.recordingStateService = recordingStateService;
    }

    @Scheduled(fixedDelayString = "${record.part-state-reconcile-interval-ms:60000}", initialDelay = 60000)
    public void reconcileScheduled() {
        reconcileBatch();
    }

    @Transactional
    public int reconcileBatch() {
        int closed = 0;
        long cursor = scanCursor.get();
        List<RecordHistoryPart> candidates = partRepository.findOpenCandidatesAfterId(cursor, PageRequest.of(0, BATCH_SIZE));
        if (candidates.isEmpty() && cursor != 0L) {
            scanCursor.set(0L);
            candidates = partRepository.findOpenCandidatesAfterId(0L, PageRequest.of(0, BATCH_SIZE));
        }
        for (RecordHistoryPart part : candidates) {
            if (part == null || part.getId() == null) continue;
            long partId = part.getId();
            if (part.getHistoryId() == null) { scanCursor.accumulateAndGet(partId, Math::max); continue; }
            RecordPartRecordingStateService.Assessment assessment = recordingStateService.assess(part);
            if (assessment.readyToClose() && recordingStateService.closeIfReady(partId, "scheduled")) {
                closed++;
                log.info("[BLR] {}", LogKvs.event("PartReconcile.Closed")
                        .add("partId", part.getId()).add("historyId", part.getHistoryId())
                        .add("activeSession", assessment.activeSession()).add("fileSizeBytes", assessment.fileSize()));
            }
            scanCursor.accumulateAndGet(partId, Math::max);
        }
        if (closed > 0) {
            log.info("[BLR] {}", LogKvs.event("PartReconcile.Done").add("closed", closed));
        }
        return closed;
    }

}
