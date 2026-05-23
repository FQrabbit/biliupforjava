package top.sshh.bililiverecoder.controller;

import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.service.MultipartUploadSessionService;
import top.sshh.bililiverecoder.service.UploadUserSerialScheduler;
import top.sshh.bililiverecoder.util.UploadProgressTracker;

import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/progress")
public class ProgressController {

    @Autowired
    private UploadProgressTracker tracker;
    @Autowired
    private RecordHistoryPartRepository partRepository;
    @Autowired
    private MultipartUploadSessionService multipartUploadSessionService;
    @Autowired
    private UploadUserSerialScheduler uploadUserSerialScheduler;

    @GetMapping("/part/{partId}")
    public PartProgressResponse part(@PathVariable("partId") Long partId) {
        PartProgressResponse resp = new PartProgressResponse();
        if (partId == null) {
            resp.setFound(false);
            return resp;
        }
        UploadProgressTracker.Progress p = tracker.getByPartId(partId);
        if (p == null) {
            RecordHistoryPart part = partRepository.findById(partId).orElse(null);
            if (part != null && Boolean.TRUE.equals(part.getUploadPaused()) && !part.isUpload()) {
                resp.setFound(true);
                resp.setProgress(pausedProgress(part));
                return resp;
            }
            UploadProgressTracker.Progress sessionProgress = resumableSessionProgress(part, UploadProgressTracker.State.WAITING, "等待上传任务开始");
            if (sessionProgress != null) {
                resp.setFound(true);
                resp.setProgress(sessionProgress);
                return resp;
            }
            resp.setFound(false);
            return resp;
        }
        resp.setFound(true);
        resp.setProgress(p);
        return resp;
    }

    @GetMapping("/history/{historyId}")
    public HistoryProgressResponse history(@PathVariable("historyId") Long historyId) {
        HistoryProgressResponse resp = new HistoryProgressResponse();
        if (historyId == null) {
            resp.setHistoryId(null);
            resp.setActiveCount(0);
            resp.setOverallPercent(0);
            resp.setItems(List.of());
            return resp;
        }

        List<UploadProgressTracker.Progress> list = new java.util.ArrayList<>(tracker.listByHistoryId(historyId));
        Set<Long> existingPartIds = new HashSet<>();
        for (UploadProgressTracker.Progress p : list) {
            if (p != null) {
                existingPartIds.add(p.getPartId());
            }
        }
        int queued = 0;
        for (RecordHistoryPart part : partRepository.findByHistoryId(historyId)) {
            if (part != null && part.getId() != null && uploadUserSerialScheduler.hasPendingPart(part.getId())) {
                queued++;
            }
            if (part == null || part.getId() == null || !Boolean.TRUE.equals(part.getUploadPaused()) || part.isUpload()
                    || existingPartIds.contains(part.getId())) {
                if (part != null && part.getId() != null && !part.isUpload() && !existingPartIds.contains(part.getId())) {
                    UploadProgressTracker.Progress sessionProgress = resumableSessionProgress(part, UploadProgressTracker.State.WAITING, "等待上传任务开始");
                    if (sessionProgress != null) {
                        list.add(sessionProgress);
                        existingPartIds.add(part.getId());
                    }
                }
                continue;
            }
            list.add(pausedProgress(part));
            existingPartIds.add(part.getId());
        }
        list.sort(Comparator.comparingLong(UploadProgressTracker.Progress::getUpdateAtMs).reversed());

        int active = 0;
        int sum = 0;
        int n = 0;
        for (UploadProgressTracker.Progress p : list) {
            if (p == null) continue;
            if (p.isActive()) {
                active++;
                sum += Math.max(0, Math.min(100, p.getPercent()));
                n++;
            }
        }

        resp.setHistoryId(historyId);
        resp.setItems(list);
        resp.setActiveCount(active);
        resp.setQueuedCount(queued);
        resp.setOverallPercent(n <= 0 ? 0 : (int) Math.round(sum * 1.0 / n));
        return resp;
    }

    private UploadProgressTracker.Progress pausedProgress(RecordHistoryPart part) {
        UploadProgressTracker.Progress p = resumableSessionProgress(part, UploadProgressTracker.State.PAUSED, part.getUploadPauseReason());
        if (p != null) {
            p.setUpdateAtMs(part.getUploadPausedAt() == null
                    ? System.currentTimeMillis()
                    : part.getUploadPausedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
            return p;
        }
        p = basePersistedProgress(part);
        p.setState(UploadProgressTracker.State.PAUSED);
        p.setStateMsg(part.getUploadPauseReason());
        p.setUpdateAtMs(part.getUploadPausedAt() == null
                ? System.currentTimeMillis()
                : part.getUploadPausedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        return p;
    }

    private UploadProgressTracker.Progress resumableSessionProgress(RecordHistoryPart part, UploadProgressTracker.State state, String stateMsg) {
        if (part == null || part.getId() == null || part.isUpload()) {
            return null;
        }
        UploadProgressTracker.Progress p = basePersistedProgress(part);
        boolean[] found = {false};
        multipartUploadSessionService.findReusableSession(part.getId(), p.getFileSizeBytes()).ifPresent(session -> {
            found[0] = true;
            int chunkTotal = session.getChunkTotal() == null ? 0 : session.getChunkTotal();
            int chunkDone = multipartUploadSessionService.countCompletedParts(session);
            long chunkSize = session.getChunkSize() == null ? 0L : session.getChunkSize();
            p.setChunkTotal(Math.max(chunkTotal, 0));
            p.setChunkDone(Math.max(chunkDone, 0));
            p.setChunkSizeBytes(Math.max(chunkSize, 0L));
        });
        if (!found[0]) {
            return null;
        }
        p.setPercent(p.getChunkTotal() <= 0 ? 0 : (int) Math.round(p.getChunkDone() * 100.0 / p.getChunkTotal()));
        long uploadedBytes = p.getChunkSizeBytes() <= 0 ? 0L : Math.min(p.getFileSizeBytes(), p.getChunkDone() * p.getChunkSizeBytes());
        p.setUploadedBytes(uploadedBytes);
        p.setRemainingBytes(Math.max(0L, p.getFileSizeBytes() - uploadedBytes));
        p.setState(state);
        p.setStateMsg(stateMsg);
        p.setUpdateAtMs(System.currentTimeMillis());
        return p;
    }

    private UploadProgressTracker.Progress basePersistedProgress(RecordHistoryPart part) {
        UploadProgressTracker.Progress p = new UploadProgressTracker.Progress();
        p.setPartId(part.getId());
        p.setHistoryId(part.getHistoryId() == null ? 0L : part.getHistoryId());
        p.setPage(part.getPage());
        p.setFileSizeBytes(Math.max(0L, part.getFileSize()));
        p.setUploadFlow(part.getUploadFlow());
        return p;
    }

    @Data
    public static class PartProgressResponse {
        private boolean found;
        private UploadProgressTracker.Progress progress;
    }

    @Data
    public static class HistoryProgressResponse {
        private Long historyId;
        private int activeCount;
        private int queuedCount;
        private int overallPercent;
        private List<UploadProgressTracker.Progress> items;
    }
}
