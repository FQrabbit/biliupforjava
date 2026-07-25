package top.sshh.bililiverecoder.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.util.LogKvs;
import top.sshh.bililiverecoder.util.TaskUtil;
import top.sshh.bililiverecoder.util.UploadProgressTracker;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
public class RoomDeletionService {

    private final RecordRoomRepository roomRepository;
    private final RecordHistoryRepository historyRepository;
    private final RecordHistoryPartRepository partRepository;
    private final HistoryDeletionService historyDeletionService;
    private final StatsAggregationService statsAggregationService;
    private final UploadProgressTracker uploadProgressTracker;
    private final UploadUserSerialScheduler uploadUserSerialScheduler;

    public RoomDeletionService(RecordRoomRepository roomRepository,
                               RecordHistoryRepository historyRepository,
                               RecordHistoryPartRepository partRepository,
                               HistoryDeletionService historyDeletionService,
                               StatsAggregationService statsAggregationService,
                               UploadProgressTracker uploadProgressTracker,
                               UploadUserSerialScheduler uploadUserSerialScheduler) {
        this.roomRepository = roomRepository;
        this.historyRepository = historyRepository;
        this.partRepository = partRepository;
        this.historyDeletionService = historyDeletionService;
        this.statsAggregationService = statsAggregationService;
        this.uploadProgressTracker = uploadProgressTracker;
        this.uploadUserSerialScheduler = uploadUserSerialScheduler;
    }

    public DeletionPreview preview(Long roomDatabaseId) {
        RecordRoom room = roomRepository.findById(roomDatabaseId).orElse(null);
        if (room == null) {
            return DeletionPreview.notFound(roomDatabaseId);
        }

        List<RecordHistory> histories = historyRepository.findByRoomIdOrderByIdAsc(room.getRoomId());
        List<Long> historyIds = histories.stream()
                .map(RecordHistory::getId)
                .filter(Objects::nonNull)
                .toList();
        List<RecordHistoryPart> parts = historyIds.isEmpty()
                ? List.of()
                : partRepository.findByHistoryIdIn(historyIds);

        long estimatedVideoBytes = parts.stream()
                .mapToLong(part -> Math.max(0L, part.getFileSize()))
                .sum();
        boolean openHistory = histories.stream().anyMatch(history ->
                history.isStreaming() || history.isRecording() || history.getEndTime() == null);
        boolean openPart = parts.stream().anyMatch(part -> part.isRecording() || part.getEndTime() == null);
        boolean recordingActive = room.isStreaming() || room.isRecording() || openHistory || openPart;
        UploadActivity uploadActivity = inspectUploadActivity(histories, parts);
        boolean active = recordingActive || uploadActivity.active();
        String source = resolveWebhookSource(room, parts);

        return new DeletionPreview(
                true,
                room.getId(),
                room.getRoomId(),
                room.getUname(),
                room.getTitle(),
                room.isStreaming(),
                room.isRecording(),
                active,
                recordingActive,
                uploadActivity.active(),
                uploadActivity.historyCount(),
                uploadActivity.activePartCount(),
                histories.size(),
                parts.size(),
                estimatedVideoBytes,
                source,
                StringUtils.isNotBlank(source),
                room.getWebhookLastSeenAt());
    }

    /**
     * 保留同步删除入口，供旧接口和已有调用方继续使用
     */
    public DeletionResult delete(Long roomDatabaseId, DeleteOptions options) {
        return delete(roomDatabaseId, options, ProgressReporter.noop());
    }

    /**
     * 执行删除并在关键阶段回报进度;回调只用于观察进度，不能影响删除流程
     */
    public DeletionResult delete(Long roomDatabaseId,
                                 DeleteOptions options,
                                 ProgressReporter progressReporter) {
        DeleteOptions safeOptions = options == null ? DeleteOptions.roomOnly() : options;
        ProgressReporter reporter = progressReporter == null ? ProgressReporter.noop() : progressReporter;
        if (!safeOptions.deleteHistories()
                && (safeOptions.deleteVideoFiles() || safeOptions.deleteDanmakuFiles() || safeOptions.deleteCoverFiles())) {
            throw new IllegalArgumentException("删除本地文件前必须同时删除录制历史");
        }

        RecordRoom initialRoom = roomRepository.findById(roomDatabaseId).orElse(null);
        if (initialRoom == null) {
            return DeletionResult.notFound(roomDatabaseId);
        }

        synchronized (initialRoom.getRoomId().intern()) {
            report(reporter, "PREPARING", "正在校验删除条件", "正在读取房间和录制历史", 0, 1, 1);
            DeletionPreview current = preview(roomDatabaseId);
            if (!current.found()) {
                return DeletionResult.notFound(roomDatabaseId);
            }
            if (current.active()) {
                if (current.recordingActive() && current.uploadingActive()) {
                    throw new IllegalStateException("房间仍在直播或录制，且存在正在上传或处理的稿件；请先停止录制，并在录制历史中取消上传或强制归档");
                }
                if (current.uploadingActive()) {
                    throw new IllegalStateException("存在正在上传或处理的稿件；请先在录制历史中取消上传或强制归档，等待任务停止后再删除");
                }
                throw new IllegalStateException("房间仍在直播、录制或存在尚未结束的分P，请先停止录制后再删除");
            }

            RecordRoom room = roomRepository.findById(roomDatabaseId).orElseThrow();
            List<RecordHistory> histories = safeOptions.deleteHistories()
                    ? historyRepository.findByRoomIdOrderByIdAsc(room.getRoomId())
                    : List.of();
            long progressTotal = safeOptions.deleteHistories() ? Math.max(1, histories.size()) : 1;

            int deletedHistoryCount = 0;
            int deletedPartCount = 0;
            int localDeleteAttempt = 0;
            int localDeleteSuccess = 0;
            List<Map<String, Object>> failures = new ArrayList<>();

            HistoryDeletionService.DeleteOptions historyOptions = new HistoryDeletionService.DeleteOptions(
                    safeOptions.deleteVideoFiles(),
                    safeOptions.deleteDanmakuFiles(),
                    safeOptions.deleteCoverFiles());
            int historyProcessed = 0;
            for (RecordHistory history : histories) {
                report(reporter, "DELETING_HISTORIES", "正在删除录制历史",
                        "正在处理第 " + (historyProcessed + 1) + " / " + histories.size() + " 条历史",
                        historyProcessed, progressTotal, historyPercent(historyProcessed, progressTotal));
                HistoryDeletionService.DeletionResult deletion = historyDeletionService.delete(history.getId(), historyOptions);
                if (!deletion.deleted()) {
                    historyProcessed++;
                    report(reporter, "DELETING_HISTORIES", "正在删除录制历史",
                            "历史 #" + history.getId() + " 已不存在，继续处理下一条",
                            historyProcessed, progressTotal, historyPercent(historyProcessed, progressTotal));
                    continue;
                }
                deletedHistoryCount++;
                deletedPartCount += deletion.deletedPartCount();
                localDeleteAttempt += deletion.localDeleteAttempt();
                localDeleteSuccess += deletion.localDeleteSuccess();
                for (Map<String, Object> failure : deletion.notDeletedFiles()) {
                    Map<String, Object> item = new LinkedHashMap<>(failure);
                    item.put("historyId", history.getId());
                    failures.add(item);
                }
                historyProcessed++;
                report(reporter, "DELETING_HISTORIES", "正在删除录制历史",
                        "已处理 " + historyProcessed + " / " + histories.size() + " 条历史",
                        historyProcessed, progressTotal, historyPercent(historyProcessed, progressTotal));
            }

            report(reporter, "DELETING_STATISTICS", "正在清理房间统计数据",
                    safeOptions.deleteHistories() ? "正在删除统计页中的房间数据" : "已保留录制历史和统计数据",
                    safeOptions.deleteHistories() ? progressTotal : 1,
                    progressTotal,
                    safeOptions.deleteHistories() ? 88 : 82);
            StatsAggregationService.RoomStatsDeletionResult statsDeletion = safeOptions.deleteHistories()
                    ? statsAggregationService.deleteRoomStats(room.getRoomId())
                    : StatsAggregationService.RoomStatsDeletionResult.empty(room.getRoomId());

            report(reporter, "DELETING_ROOM", "正在删除房间记录", "正在提交最后的数据库变更",
                    progressTotal, progressTotal, 97);
            roomRepository.delete(room);
            DeletionResult result = new DeletionResult(
                    true,
                    true,
                    roomDatabaseId,
                    room.getRoomId(),
                    safeOptions,
                    histories.size(),
                    deletedHistoryCount,
                    deletedPartCount,
                    statsDeletion.deletedTotal(),
                    localDeleteAttempt,
                    localDeleteSuccess,
                    failures);
            log.info("[BLR] {}", LogKvs.event("Room.Delete.Success")
                    .add("roomDatabaseId", roomDatabaseId)
                    .add("roomId", room.getRoomId())
                    .add("deleteHistories", safeOptions.deleteHistories())
                    .add("deleteVideoFiles", safeOptions.deleteVideoFiles())
                    .add("deleteDanmakuFiles", safeOptions.deleteDanmakuFiles())
                    .add("deleteCoverFiles", safeOptions.deleteCoverFiles())
                    .add("deletedHistoryCount", deletedHistoryCount)
                    .add("deletedPartCount", deletedPartCount)
                    .add("deletedStatisticsCount", statsDeletion.deletedTotal())
                    .add("notDeletedCount", failures.size()));
            report(reporter, "DONE", "删除完成",
                    !safeOptions.deleteHistories()
                            ? "房间已删除，录制历史和统计数据已保留"
                            : (failures.isEmpty()
                                ? "房间、所选历史和统计数据已处理"
                                : "数据库已删除，但有部分本地文件未能删除"),
                    progressTotal, progressTotal, 100);
            return result;
        }
    }

    private static int historyPercent(long processed, long total) {
        if (total <= 0) {
            return 70;
        }
        long safeProcessed = Math.max(0, Math.min(processed, total));
        return (int) Math.max(5, Math.min(82, 5 + Math.floor(safeProcessed * 77.0d / total)));
    }

    private void report(ProgressReporter reporter,
                        String phase,
                        String message,
                        String detail,
                        long processed,
                        long total,
                        int percent) {
        try {
            reporter.report(phase, message, detail, processed, total, Math.max(0, Math.min(100, percent)));
        } catch (RuntimeException e) {
            // 进度上报失败不能回滚或中断删除本身
            log.debug("[BLR] room deletion progress reporter failed: {}", e.getMessage());
        }
    }

    private static String resolveWebhookSource(RecordRoom room, List<RecordHistoryPart> parts) {
        if (StringUtils.isNotBlank(room.getWebhookSource())) {
            return room.getWebhookSource();
        }
        if (parts.stream().anyMatch(part -> "blrec".equalsIgnoreCase(part.getSourceType()))) {
            return "BLREC";
        }
        if (parts.stream().anyMatch(part -> "brec".equalsIgnoreCase(part.getSourceType()))) {
            return "BREC";
        }
        if (StringUtils.isNotBlank(room.getSessionId())) {
            return "BREC";
        }
        return null;
    }

    private UploadActivity inspectUploadActivity(List<RecordHistory> histories,
                                                 List<RecordHistoryPart> parts) {
        Set<Long> blockingHistoryIds = new LinkedHashSet<>();
        Set<Long> activePartIds = new LinkedHashSet<>();
        boolean active = false;

        for (RecordHistory history : histories) {
            Long historyId = history.getId();
            if (historyId == null) {
                continue;
            }
            boolean uploadRequested = !history.isForceArchived()
                    && ((history.isUpload() && !history.isPublish()) || history.isEditPartsUploading());
            boolean publishTaskRunning = isRunning(TaskUtil.publishTask.get(historyId));
            if (uploadRequested || publishTaskRunning) {
                active = true;
                blockingHistoryIds.add(historyId);
            }
            List<UploadProgressTracker.Progress> progresses = uploadProgressTracker.listByHistoryId(historyId);
            if (progresses == null) {
                continue;
            }
            for (UploadProgressTracker.Progress progress : progresses) {
                if (progress != null && progress.isActive()) {
                    active = true;
                    blockingHistoryIds.add(historyId);
                    activePartIds.add(progress.getPartId());
                }
            }
        }

        for (RecordHistoryPart part : parts) {
            Long partId = part.getId();
            if (partId == null) {
                continue;
            }
            boolean partTaskRunning = isRunning(TaskUtil.partUploadTask.get(partId));
            boolean partQueued = uploadUserSerialScheduler.hasPendingPart(partId);
            if (partTaskRunning || partQueued) {
                active = true;
                activePartIds.add(partId);
                if (part.getHistoryId() != null) {
                    blockingHistoryIds.add(part.getHistoryId());
                }
            }
        }

        return new UploadActivity(active, blockingHistoryIds.size(), activePartIds.size());
    }

    private static boolean isRunning(Thread thread) {
        return thread != null && thread.isAlive();
    }

    private record UploadActivity(boolean active, int historyCount, int activePartCount) {
    }

    public record DeleteOptions(boolean deleteHistories,
                                boolean deleteVideoFiles,
                                boolean deleteDanmakuFiles,
                                boolean deleteCoverFiles) {
        public static DeleteOptions roomOnly() {
            return new DeleteOptions(false, false, false, false);
        }
    }

    @FunctionalInterface
    public interface ProgressReporter {
        void report(String phase, String message, String detail,
                    long processed, long total, int percent);

        static ProgressReporter noop() {
            return (phase, message, detail, processed, total, percent) -> {
            };
        }
    }

    public record DeletionPreview(boolean found,
                                  Long roomDatabaseId,
                                  String roomId,
                                  String uname,
                                  String title,
                                  boolean streaming,
                                  boolean recording,
                                  boolean active,
                                  boolean recordingActive,
                                  boolean uploadingActive,
                                  int uploadingHistoryCount,
                                  int activeUploadPartCount,
                                  int historyCount,
                                  int partCount,
                                  long estimatedVideoBytes,
                                  String webhookSource,
                                  boolean webhookManaged,
                                  LocalDateTime webhookLastSeenAt) {

        private static DeletionPreview notFound(Long roomDatabaseId) {
            return new DeletionPreview(false, roomDatabaseId, null, null, null,
                    false, false, false, false, false, 0, 0,
                    0, 0, 0, null, false, null);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("found", found);
            data.put("roomDatabaseId", roomDatabaseId);
            data.put("roomId", roomId);
            data.put("uname", uname);
            data.put("title", title);
            data.put("streaming", streaming);
            data.put("recording", recording);
            data.put("active", active);
            data.put("recordingActive", recordingActive);
            data.put("uploadingActive", uploadingActive);
            data.put("uploadingHistoryCount", uploadingHistoryCount);
            data.put("activeUploadPartCount", activeUploadPartCount);
            data.put("historyCount", historyCount);
            data.put("partCount", partCount);
            data.put("estimatedVideoBytes", estimatedVideoBytes);
            data.put("webhookSource", webhookSource);
            data.put("webhookManaged", webhookManaged);
            data.put("webhookLastSeenAt", webhookLastSeenAt);
            return data;
        }
    }

    public record DeletionResult(boolean found,
                                 boolean deleted,
                                 Long roomDatabaseId,
                                 String roomId,
                                 DeleteOptions options,
                                 int requestedHistoryCount,
                                 int deletedHistoryCount,
                                 int deletedPartCount,
                                 long deletedStatisticsCount,
                                 int localDeleteAttempt,
                                 int localDeleteSuccess,
                                 List<Map<String, Object>> notDeletedFiles) {

        private static DeletionResult notFound(Long roomDatabaseId) {
            return new DeletionResult(false, false, roomDatabaseId, null, DeleteOptions.roomOnly(),
                    0, 0, 0, 0L, 0, 0, List.of());
        }

        public Map<String, Object> toMap() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("roomDatabaseId", roomDatabaseId);
            data.put("roomId", roomId);
            data.put("deleteHistories", options.deleteHistories());
            data.put("deleteVideoFiles", options.deleteVideoFiles());
            data.put("deleteDanmakuFiles", options.deleteDanmakuFiles());
            data.put("deleteCoverFiles", options.deleteCoverFiles());
            data.put("requestedHistoryCount", requestedHistoryCount);
            data.put("deletedHistoryCount", deletedHistoryCount);
            data.put("deletedPartCount", deletedPartCount);
            data.put("deletedStatisticsCount", deletedStatisticsCount);
            data.put("localDeleteAttempt", localDeleteAttempt);
            data.put("localDeleteSuccess", localDeleteSuccess);
            data.put("notDeletedFiles", notDeletedFiles);
            return data;
        }
    }
}
