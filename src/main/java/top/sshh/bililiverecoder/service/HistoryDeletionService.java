package top.sshh.bililiverecoder.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.HistoryDeletionTask;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.LiveMsgRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
public class HistoryDeletionService {

    private static final List<String> DANMAKU_SUFFIXES = List.of(".xml", ".ass");
    private static final List<String> COVER_SUFFIXES = List.of(
            ".cover.jpg", ".cover.jpeg", ".cover.png", ".cover.webp",
            ".jpg", ".jpeg", ".png", ".webp");

    private final RecordHistoryRepository historyRepository;
    private final LiveMsgRepository liveMsgRepository;
    private final RecordHistoryPartRepository partRepository;
    private final HistoryMsgQueueCleanupService msgQueueCleanupService;
    private final PartFileOperationService partFileOperationService;
    private final PartFileLocationService partFileLocationService;
    private final StorageRootService storageRootService;
    private final PublishAccountScheduler publishAccountScheduler;
    private final VideoCommentTaskService videoCommentTaskService;
    private final VideoVisibilityRestoreService visibilityRestoreService;
    private final StatsAggregationService statsAggregationService;
    private final UploadUserSerialScheduler uploadUserSerialScheduler;
    private final PartActivityCoordinator partActivityCoordinator;
    private final HistoryDeletionTaskService deletionTasks;
    private final HistoryDeletionDataPurger dataPurger;

    public HistoryDeletionService(RecordHistoryRepository historyRepository,
                                  LiveMsgRepository liveMsgRepository,
                                  RecordHistoryPartRepository partRepository,
                                  HistoryMsgQueueCleanupService msgQueueCleanupService,
                                  PartFileOperationService partFileOperationService,
                                  PartFileLocationService partFileLocationService,
                                  StorageRootService storageRootService,
                                  PublishAccountScheduler publishAccountScheduler,
                                  VideoCommentTaskService videoCommentTaskService,
                                  VideoVisibilityRestoreService visibilityRestoreService,
                                  StatsAggregationService statsAggregationService,
                                  UploadUserSerialScheduler uploadUserSerialScheduler,
                                  PartActivityCoordinator partActivityCoordinator,
                                  HistoryDeletionTaskService deletionTasks,
                                  HistoryDeletionDataPurger dataPurger) {
        this.historyRepository = historyRepository;
        this.liveMsgRepository = liveMsgRepository;
        this.partRepository = partRepository;
        this.msgQueueCleanupService = msgQueueCleanupService;
        this.partFileOperationService = partFileOperationService;
        this.partFileLocationService = partFileLocationService;
        this.storageRootService = storageRootService;
        this.publishAccountScheduler = publishAccountScheduler;
        this.videoCommentTaskService = videoCommentTaskService;
        this.visibilityRestoreService = visibilityRestoreService;
        this.statsAggregationService = statsAggregationService;
        this.uploadUserSerialScheduler = uploadUserSerialScheduler;
        this.partActivityCoordinator = partActivityCoordinator;
        this.deletionTasks = deletionTasks;
        this.dataPurger = dataPurger;
    }

    public DeletionResult delete(Long historyId, DeleteOptions options) {
        long totalStartNs = System.nanoTime();
        DeleteOptions safeOptions = options == null ? DeleteOptions.databaseOnly() : options;
        HistoryDeletionTask previous = deletionTasks.findByHistoryId(historyId);
        if (previous != null && "COMPLETED".equals(previous.getState())) {
            return result(previous, true, 0, 0, 0, 0, List.of(), 0, 0, 0, 0, totalStartNs);
        }
        HistoryDeletionTask task;
        try {
            task = deletionTasks.accept(historyId, null, safeOptions);
        } catch (IllegalStateException rejected) {
            RecordHistory history = historyRepository.findById(historyId).orElse(null);
            if (history == null) return DeletionResult.notFound(historyId);
            return blocked(historyId, history.getRoomId(), safeOptions, null, "NEEDS_ACTION",
                    rejected.getMessage(), totalStartNs);
        }
        if (task == null) {
            HistoryDeletionTask existing = deletionTasks.findByHistoryId(historyId);
            return existing == null ? DeletionResult.notFound(historyId)
                    : result(existing, "COMPLETED".equals(existing.getState()), 0, 0, 0, 0,
                    List.of(), 0, 0, 0, 0, totalStartNs);
        }
        return executeTask(task.getId(), totalStartNs);
    }

    @Scheduled(fixedDelayString = "${history.deletion.retry-poll-ms:30000}", initialDelay = 10000)
    public void resumeDueDeletions() {
        for (Long taskId : deletionTasks.dueTaskIds(20)) executeTask(taskId, System.nanoTime());
    }

    public HistoryDeletionTask retry(Long taskId) {
        HistoryDeletionTask retried = deletionTasks.retry(taskId);
        if (retried != null) executeTask(taskId, System.nanoTime());
        return retried;
    }

    private DeletionResult executeTask(Long taskId, long totalStartNs) {
        HistoryDeletionTask task = deletionTasks.claim(taskId);
        if (task == null) return taskStatusResult(taskId, totalStartNs);
        Long historyId = task.getHistoryId();
        RecordHistory history = historyRepository.findById(historyId).orElse(null);
        if (history == null) {
            if (!deletionTasks.beginExecution(taskId)) return taskStatusResult(taskId, totalStartNs);
            HistoryDeletionDataPurger.PurgeResult purged = dataPurger.purge(taskId, historyId);
            return result(deletionTasks.findById(taskId), true, purged.deletedMessages(), purged.deletedParts(),
                    0, 0, List.of(), 0, 0, 0, 0, totalStartNs);
        }
        try {
            if (publishAccountScheduler.hasSubmissionInFlight(historyId)) {
                return defer(task, "投稿请求仍在提交或结果核对中，等待本次操作结束", totalStartNs);
            }
            if (uploadUserSerialScheduler.hasPendingHistory(historyId)
                    || partActivityCoordinator.hasPendingHistory(historyId)) {
                return defer(task, "稿件仍有分P在等待或执行上传，等待上传结束", totalStartNs);
            }
            String postPublishBlock = videoCommentTaskService.historyDeletionBlockReason(historyId);
            if (postPublishBlock != null) return defer(task, postPublishBlock, totalStartNs);
            String visibilityBlock = visibilityRestoreService.historyDeletionBlockReason(historyId);
            if (visibilityBlock != null) return defer(task, visibilityBlock, totalStartNs);

            DeleteOptions options = new DeleteOptions(task.isDeleteVideo(), task.isDeleteDanmaku(), task.isDeleteCover());
            List<RecordHistoryPart> parts = partRepository.findByHistoryIdOrderByStartTimeAsc(historyId);
            List<Long> partIds = parts.stream().map(RecordHistoryPart::getId).filter(java.util.Objects::nonNull).toList();
            if (!partIds.isEmpty() && liveMsgRepository.existsByPartIdInAndCodeIn(partIds, List.of(-2, -4))) {
                return defer(task, "稿件存在弹幕发送结果待核对或仍在发送，核对后才能删除", totalStartNs);
            }

            if (!deletionTasks.beginExecution(taskId)) return taskStatusResult(taskId, totalStartNs);

            publishAccountScheduler.cancelForHistory(historyId, "稿件已删除，未提交的投稿任务已取消");
            msgQueueCleanupService.cleanupByHistoryId(historyId,
                    new HistoryMsgQueueCleanupService.CleanupOptions(true, true, true, false), false, "delete");

            long localDeleteStartNs = System.nanoTime();
            List<Map<String, Object>> notDeletedFiles = new ArrayList<>();
            MutableFileCounts fileCounts = new MutableFileCounts();
            deleteLocalFiles(history, parts, options, fileCounts, notDeletedFiles);
            long localDeleteCostMs = toCostMs(localDeleteStartNs);
            if (!notDeletedFiles.isEmpty()) {
                HistoryDeletionTask deferred = deletionTasks.defer(taskId,
                        "有本地文件尚未删除，保留稿件记录并稍后重试");
                return result(deferred, false, 0, 0, fileCounts.attempted, fileCounts.deleted,
                        notDeletedFiles, 0, localDeleteCostMs, 0, 0, totalStartNs);
            }

            long purgeStartNs = System.nanoTime();
            HistoryDeletionDataPurger.PurgeResult purged = dataPurger.purge(taskId, historyId);
            long purgeCostMs = toCostMs(purgeStartNs);
            HistoryDeletionTask completed = deletionTasks.findById(taskId);
            return result(completed, true, purged.deletedMessages(), purged.deletedParts(),
                    fileCounts.attempted, fileCounts.deleted, List.of(), 0, localDeleteCostMs,
                    purgeCostMs, 0, totalStartNs);
        } catch (Exception error) {
            HistoryDeletionTask deferred = deletionTasks.defer(taskId,
                    "删除过程发生异常：" + error.getClass().getSimpleName() + ": " + error.getMessage());
            return blocked(historyId, history.getRoomId(),
                    new DeleteOptions(task.isDeleteVideo(), task.isDeleteDanmaku(), task.isDeleteCover()),
                    deferred == null ? task.getId() : deferred.getId(), deferred == null ? "NEEDS_ACTION" : deferred.getState(),
                    deferred == null ? error.getMessage() : deferred.getLastError(), totalStartNs);
        }
    }

    private DeletionResult defer(HistoryDeletionTask task, String reason, long totalStartNs) {
        HistoryDeletionTask deferred = deletionTasks.defer(task.getId(), reason);
        return blocked(task.getHistoryId(), task.getRoomId(),
                new DeleteOptions(task.isDeleteVideo(), task.isDeleteDanmaku(), task.isDeleteCover()),
                deferred == null ? task.getId() : deferred.getId(), deferred == null ? "NEEDS_ACTION" : deferred.getState(),
                deferred == null ? reason : deferred.getLastError(), totalStartNs);
    }

    private DeletionResult taskStatusResult(Long taskId, long totalStartNs) {
        // 任务仍在执行或等待退避时，查询其持久状态，不再重复删除
        HistoryDeletionTask task = deletionTasks.findById(taskId);
        return task == null ? DeletionResult.notFound(null) : result(task,
                "COMPLETED".equals(task.getState()), 0, 0, 0, 0, List.of(), 0, 0, 0, 0, totalStartNs);
    }

    private DeletionResult blocked(Long historyId, String roomId, DeleteOptions options, Long taskId,
                                   String state, String reason, long totalStartNs) {
        return result(historyId, roomId, options, taskId, state, false, 0, 0, 0, 0,
                List.of(fileFailure(null, "task", reason)), 0, 0, 0, 0, toCostMs(totalStartNs));
    }

    private DeletionResult result(HistoryDeletionTask task, boolean deleted, int deletedMessages, int deletedParts,
                                  int fileAttempts, int filesDeleted, List<Map<String, Object>> failures,
                                  long msgCostMs, long localCostMs, long partCostMs, long historyCostMs,
                                  long totalStartNs) {
        if (task == null) return DeletionResult.notFound(null);
        return result(task.getHistoryId(), task.getRoomId(),
                new DeleteOptions(task.isDeleteVideo(), task.isDeleteDanmaku(), task.isDeleteCover()),
                task.getId(), task.getState(), deleted, deletedMessages, deletedParts, fileAttempts, filesDeleted,
                failures, msgCostMs, localCostMs, partCostMs, historyCostMs, toCostMs(totalStartNs));
    }

    private DeletionResult result(Long historyId, String roomId, DeleteOptions options, Long taskId, String state,
                                  boolean deleted, int deletedMessages, int deletedParts, int fileAttempts,
                                  int filesDeleted, List<Map<String, Object>> failures, long msgCostMs,
                                  long localCostMs, long partCostMs, long historyCostMs, long totalCostMs) {
        return new DeletionResult(true, deleted, historyId, roomId, options, deletedMessages, deletedParts,
                fileAttempts, filesDeleted, failures, msgCostMs, localCostMs, partCostMs, historyCostMs,
                totalCostMs, taskId, state);
    }

    private void deleteLocalFiles(RecordHistory history,
                                  List<RecordHistoryPart> parts,
                                  DeleteOptions options,
                                  MutableFileCounts counts,
                                  List<Map<String, Object>> failures) {
        if (!options.deleteAnyLocalFile()) {
            return;
        }

        for (RecordHistoryPart part : parts) {
            if (options.deleteVideo()) {
                counts.attempted++;
                List<String> videoFailures = partFileOperationService.deleteAllAvailable(part.getId());
                if (videoFailures.isEmpty()) {
                    counts.deleted++;
                } else {
                    for (String failure : videoFailures) {
                        failures.add(fileFailure(part.getFilePath(), "video", failure));
                    }
                }
            }

            if (options.deleteDanmaku()) {
                statsAggregationService.withStatsWriteLock(() -> {
                    trustedLocalFile(part.getDanmakuFilePath()).ifPresent(path ->
                            deletePath(path, "danmaku", counts, failures));
                    deleteCompanions(part.getId(), DANMAKU_SUFFIXES, "danmaku", counts, failures);
                    return null;
                });
            }
            if (options.deleteCover()) {
                deleteCompanions(part.getId(), COVER_SUFFIXES, "cover", counts, failures);
            }
        }

        if (options.deleteCover()) {
            trustedLocalFile(history.getLocalCoverPath()).ifPresent(path ->
                    deletePath(path, "cover", counts, failures));
            trustedLocalFile(history.getCoverUrl()).ifPresent(path ->
                    deletePath(path, "cover", counts, failures));
        }
    }

    private void deleteCompanions(Long partId,
                                  List<String> suffixes,
                                  String kind,
                                  MutableFileCounts counts,
                                  List<Map<String, Object>> failures) {
        Set<Path> targets = new LinkedHashSet<>();
        for (String suffix : suffixes) {
            targets.addAll(partFileLocationService.resolveCompanions(partId, suffix));
        }
        for (Path target : targets) {
            deletePath(target, kind, counts, failures);
        }
    }

    private void deletePath(Path path,
                            String kind,
                            MutableFileCounts counts,
                            List<Map<String, Object>> failures) {
        counts.attempted++;
        try {
            if (Files.deleteIfExists(path)) {
                counts.deleted++;
            }
        } catch (Exception e) {
            failures.add(fileFailure(normalizePath(path), kind,
                    e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage())));
        }
    }

    private Optional<Path> trustedLocalFile(String filePath) {
        if (filePath == null || filePath.isBlank() || filePath.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) {
            return Optional.empty();
        }
        try {
            Path candidate = Path.of(filePath);
            if (!Files.isRegularFile(candidate)) {
                return Optional.empty();
            }
            return storageRootService.matchTrustedExisting(candidate)
                    .map(StorageRootService.RootMatch::resolvedPath);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static Map<String, Object> fileFailure(String path, String kind, String reason) {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("path", path == null ? "" : path.replace('\\', '/'));
        failure.put("kind", kind);
        failure.put("status", "failed");
        failure.put("reason", reason);
        return failure;
    }

    private static String normalizePath(Path path) {
        return path == null ? "" : path.toString().replace('\\', '/');
    }

    private static long toCostMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000;
    }

    private static final class MutableFileCounts {
        private int attempted;
        private int deleted;
    }

    public record DeleteOptions(boolean deleteVideo, boolean deleteDanmaku, boolean deleteCover) {
        public static DeleteOptions databaseOnly() {
            return new DeleteOptions(false, false, false);
        }

        public boolean deleteAnyLocalFile() {
            return deleteVideo || deleteDanmaku || deleteCover;
        }
    }

    public record DeletionResult(boolean found,
                                 boolean deleted,
                                 Long historyId,
                                 String roomId,
                                 DeleteOptions options,
                                 int deletedMsgCount,
                                 int deletedPartCount,
                                 int localDeleteAttempt,
                                 int localDeleteSuccess,
                                 List<Map<String, Object>> notDeletedFiles,
                                 long msgDeleteCostMs,
                                 long localDeleteCostMs,
                                 long partDeleteCostMs,
                                 long historyDeleteCostMs,
                                 long totalCostMs,
                                 Long deletionTaskId,
                                 String deletionTaskState) {

        private static DeletionResult notFound(Long historyId) {
            return new DeletionResult(false, false, historyId, null, DeleteOptions.databaseOnly(),
                    0, 0, 0, 0, List.of(), 0, 0, 0, 0, 0, null, null);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("historyId", historyId);
            data.put("deleteVideo", options.deleteVideo());
            data.put("deleteDanmaku", options.deleteDanmaku());
            data.put("deleteCover", options.deleteCover());
            data.put("deletedMsgCount", deletedMsgCount);
            data.put("deletedPartCount", deletedPartCount);
            data.put("localDeleteAttempt", localDeleteAttempt);
            data.put("localDeleteSuccess", localDeleteSuccess);
            data.put("msgDeleteCostMs", msgDeleteCostMs);
            data.put("localDeleteCostMs", localDeleteCostMs);
            data.put("partDeleteCostMs", partDeleteCostMs);
            data.put("historyDeleteCostMs", historyDeleteCostMs);
            data.put("totalCostMs", totalCostMs);
            data.put("notDeletedFiles", notDeletedFiles);
            data.put("deletionTaskId", deletionTaskId);
            data.put("deletionTaskState", deletionTaskState);
            return data;
        }
    }
}
