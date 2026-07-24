package top.sshh.bililiverecoder.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.repo.LiveMsgRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.util.LogKvs;

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
    private final RecordHistoryPartRepository partRepository;
    private final LiveMsgRepository msgRepository;
    private final HistoryMsgQueueCleanupService msgQueueCleanupService;
    private final PartFileOperationService partFileOperationService;
    private final PartFileLocationService partFileLocationService;
    private final StorageRootService storageRootService;
    private final RoomLiveEventXmlIssueService xmlIssueService;

    public HistoryDeletionService(RecordHistoryRepository historyRepository,
                                  RecordHistoryPartRepository partRepository,
                                  LiveMsgRepository msgRepository,
                                  HistoryMsgQueueCleanupService msgQueueCleanupService,
                                  PartFileOperationService partFileOperationService,
                                  PartFileLocationService partFileLocationService,
                                  StorageRootService storageRootService,
                                  RoomLiveEventXmlIssueService xmlIssueService) {
        this.historyRepository = historyRepository;
        this.partRepository = partRepository;
        this.msgRepository = msgRepository;
        this.msgQueueCleanupService = msgQueueCleanupService;
        this.partFileOperationService = partFileOperationService;
        this.partFileLocationService = partFileLocationService;
        this.storageRootService = storageRootService;
        this.xmlIssueService = xmlIssueService;
    }

    public DeletionResult delete(Long historyId, DeleteOptions options) {
        long totalStartNs = System.nanoTime();
        DeleteOptions safeOptions = options == null ? DeleteOptions.databaseOnly() : options;
        Optional<RecordHistory> historyOptional = historyRepository.findById(historyId);
        if (historyOptional.isEmpty()) {
            return DeletionResult.notFound(historyId);
        }

        RecordHistory history = historyOptional.get();
        List<RecordHistoryPart> parts = partRepository.findByHistoryIdOrderByStartTimeAsc(historyId);
        msgQueueCleanupService.cleanupByHistoryId(historyId,
                new HistoryMsgQueueCleanupService.CleanupOptions(true, true, true, false),
                false,
                "delete");

        long msgDeleteStartNs = System.nanoTime();
        int deletedMsgCount = msgRepository.deleteByHistoryId(historyId);
        long msgDeleteCostMs = toCostMs(msgDeleteStartNs);

        List<Map<String, Object>> notDeletedFiles = new ArrayList<>();
        MutableFileCounts fileCounts = new MutableFileCounts();
        long localDeleteStartNs = System.nanoTime();
        deleteLocalFiles(history, parts, safeOptions, fileCounts, notDeletedFiles);
        long localDeleteCostMs = toCostMs(localDeleteStartNs);

        long partDeleteStartNs = System.nanoTime();
        xmlIssueService.deleteByHistoryId(historyId);
        for (RecordHistoryPart part : parts) {
            partFileOperationService.purgeMetadata(part.getId());
        }
        int deletedPartCount = partRepository.deleteByHistoryId(historyId);
        long partDeleteCostMs = toCostMs(partDeleteStartNs);

        long historyDeleteStartNs = System.nanoTime();
        historyRepository.delete(history);
        long historyDeleteCostMs = toCostMs(historyDeleteStartNs);
        long totalCostMs = toCostMs(totalStartNs);

        DeletionResult result = new DeletionResult(
                true,
                true,
                historyId,
                history.getRoomId(),
                safeOptions,
                deletedMsgCount,
                deletedPartCount,
                fileCounts.attempted,
                fileCounts.deleted,
                notDeletedFiles,
                msgDeleteCostMs,
                localDeleteCostMs,
                partDeleteCostMs,
                historyDeleteCostMs,
                totalCostMs);

        log.info("[BLR] {}", LogKvs.event("History.Delete.Success")
                .add("historyId", historyId)
                .add("roomId", history.getRoomId())
                .add("deleteVideo", safeOptions.deleteVideo())
                .add("deleteDanmaku", safeOptions.deleteDanmaku())
                .add("deleteCover", safeOptions.deleteCover())
                .add("deletedMsgCount", deletedMsgCount)
                .add("deletedPartCount", deletedPartCount)
                .add("localDeleteAttempt", fileCounts.attempted)
                .add("localDeleteSuccess", fileCounts.deleted)
                .add("notDeletedCount", notDeletedFiles.size())
                .addStageField("msgDelete", "costMs", msgDeleteCostMs)
                .addStageField("localDelete", "costMs", localDeleteCostMs)
                .addStageField("partDelete", "costMs", partDeleteCostMs)
                .addStageField("historyDelete", "costMs", historyDeleteCostMs)
                .addStageField("total", "costMs", totalCostMs));
        return result;
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
                trustedLocalFile(part.getDanmakuFilePath()).ifPresent(path ->
                        deletePath(path, "danmaku", counts, failures));
                deleteCompanions(part.getId(), DANMAKU_SUFFIXES, "danmaku", counts, failures);
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
                                 long totalCostMs) {

        private static DeletionResult notFound(Long historyId) {
            return new DeletionResult(false, false, historyId, null, DeleteOptions.databaseOnly(),
                    0, 0, 0, 0, List.of(), 0, 0, 0, 0, 0);
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
            return data;
        }
    }
}
