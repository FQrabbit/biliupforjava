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

    public DeletionResult delete(Long roomDatabaseId, DeleteOptions options) {
        DeleteOptions safeOptions = options == null ? DeleteOptions.roomOnly() : options;
        if (!safeOptions.deleteHistories()
                && (safeOptions.deleteVideoFiles() || safeOptions.deleteDanmakuFiles() || safeOptions.deleteCoverFiles())) {
            throw new IllegalArgumentException("删除本地文件前必须同时删除录制历史");
        }

        RecordRoom initialRoom = roomRepository.findById(roomDatabaseId).orElse(null);
        if (initialRoom == null) {
            return DeletionResult.notFound(roomDatabaseId);
        }

        synchronized (initialRoom.getRoomId().intern()) {
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

            int deletedHistoryCount = 0;
            int deletedPartCount = 0;
            int localDeleteAttempt = 0;
            int localDeleteSuccess = 0;
            List<Map<String, Object>> failures = new ArrayList<>();

            HistoryDeletionService.DeleteOptions historyOptions = new HistoryDeletionService.DeleteOptions(
                    safeOptions.deleteVideoFiles(),
                    safeOptions.deleteDanmakuFiles(),
                    safeOptions.deleteCoverFiles());
            for (RecordHistory history : histories) {
                HistoryDeletionService.DeletionResult deletion = historyDeletionService.delete(history.getId(), historyOptions);
                if (!deletion.deleted()) {
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
            }

            StatsAggregationService.RoomStatsDeletionResult statsDeletion = safeOptions.deleteHistories()
                    ? statsAggregationService.deleteRoomStats(room.getRoomId())
                    : StatsAggregationService.RoomStatsDeletionResult.empty(room.getRoomId());

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
            return result;
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
