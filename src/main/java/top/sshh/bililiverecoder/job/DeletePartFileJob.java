package top.sshh.bililiverecoder.job;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.entity.PartFileLocation;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.service.ArchiveReviewStatusService;
import top.sshh.bililiverecoder.service.PartFileCleanupPolicy;
import top.sshh.bililiverecoder.service.PartFileOperationService;
import top.sshh.bililiverecoder.service.StorageRootService;
import top.sshh.bililiverecoder.util.LogKvs;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class DeletePartFileJob {

    @Autowired
    RecordRoomRepository roomRepository;

    @Autowired
    RecordHistoryPartRepository partRepository;

    @Autowired
    RecordHistoryRepository historyRepository;

    @Autowired
    private ArchiveReviewStatusService archiveReviewStatusService;

    @Autowired
    private PartFileCleanupPolicy partFileCleanupPolicy;

    @Autowired
    private PartFileOperationService partFileOperationService;

    @Autowired
    private StorageRootService storageRootService;

    @Scheduled(fixedDelay = 3600000, initialDelay = 60000)
    public void deleteFileProcess() {
        if (storageRootService.hasPendingWorkPathChange()) return;
        long roundStartNs = System.nanoTime();
        int roomCount = 0;
        int scannedPartCount = 0;
        int skippedPartCount = 0;
        Map<ArchiveReviewStatusService.ReviewState, Integer> reviewCounts =
                new EnumMap<>(ArchiveReviewStatusService.ReviewState.class);
        ArchiveReviewStatusService.ReviewRound reviewRound = archiveReviewStatusService.newRound();
        List<RecordRoom> roomList = roomRepository.findByDeleteType(3);
        roomCount = roomList.size();
        for (RecordRoom room : roomList) {
            LocalDateTime deleteTime = LocalDateTime.now().minusDays(room.getDeleteDay());
            List<RecordHistoryPart> partList = partRepository.findFileCleanupCandidates(
                    room.getRoomId(), deleteTime, PartFileLocation.LocationState.AVAILABLE);
            scannedPartCount += partList.size();
            if (partList.size() > 0) {
                log.info("[BLR] {}", LogKvs.event("DeletePartFileJob.Start")
                        .add("roomId", room.getRoomId())
                        .add("uname", room.getUname())
                        .add("count", partList.size()));
            }
            Map<Long, List<RecordHistoryPart>> grouped = groupByHistory(partList);
            skippedPartCount += partList.size() - grouped.values().stream().mapToInt(List::size).sum();
            for (Map.Entry<Long, List<RecordHistoryPart>> entry : grouped.entrySet()) {
                RecordHistory history = historyRepository.findById(entry.getKey()).orElse(null);
                ArchiveReviewStatusService.ReviewCheckResult review =
                        archiveReviewStatusService.checkForCleanup(history, room, reviewRound);
                reviewCounts.merge(review.state(), 1, Integer::sum);
                if (!review.permitsCleanup()) {
                    skippedPartCount += entry.getValue().size();
                    continue;
                }
                for (RecordHistoryPart part : entry.getValue()) {
                    String filePath = part.getFilePath();
                    if (partFileCleanupPolicy.shouldSkipProtectedArchive(
                            room, history, part, filePath, "DeletePartFileJob", "delete")) {
                        skippedPartCount++;
                        continue;
                    }
                    partFileOperationService.deleteScheduled(part.getId(), review);
                }
            }
        }
        log.info("[BLR] {}", LogKvs.event("DeletePartFileJob.Round.Done")
            .addRoundCount("room", roomCount)
            .addRoundCount("scannedPart", scannedPartCount)
            .addRoundCount("skippedPart", skippedPartCount)
            .add("reviewCounts", reviewCounts)
            .add("platformCircuitOpen", reviewRound.isPlatformUnavailable())
                .addStageCostMs("total", roundStartNs));
    }

    @Scheduled(fixedDelay = 3600000, initialDelay = 60000)
    public void moveFileProcess() {
        if (storageRootService.hasPendingWorkPathChange()) return;
        long roundStartNs = System.nanoTime();
        int roomCount = 0;
        int scannedPartCount = 0;
        int skippedPartCount = 0;
        Map<ArchiveReviewStatusService.ReviewState, Integer> reviewCounts =
                new EnumMap<>(ArchiveReviewStatusService.ReviewState.class);
        ArchiveReviewStatusService.ReviewRound reviewRound = archiveReviewStatusService.newRound();
        List<RecordRoom> roomList = roomRepository.findByDeleteType(8);
        roomCount = roomList.size();
        for (RecordRoom room : roomList) {
            LocalDateTime deleteTime = LocalDateTime.now().minusDays(room.getDeleteDay());
            List<RecordHistoryPart> partList = partRepository.findFileCleanupCandidates(
                    room.getRoomId(), deleteTime, PartFileLocation.LocationState.AVAILABLE);
            scannedPartCount += partList.size();
            if (partList.size() > 0) {
                log.info("[BLR] {}", LogKvs.event("MovePartFileJob.Start")
                        .add("roomId", room.getRoomId())
                        .add("uname", room.getUname())
                        .add("count", partList.size()));
            }
            Map<Long, List<RecordHistoryPart>> grouped = groupByHistory(partList);
            skippedPartCount += partList.size() - grouped.values().stream().mapToInt(List::size).sum();
            for (Map.Entry<Long, List<RecordHistoryPart>> entry : grouped.entrySet()) {
                RecordHistory history = historyRepository.findById(entry.getKey()).orElse(null);
                ArchiveReviewStatusService.ReviewCheckResult review =
                        archiveReviewStatusService.checkForCleanup(history, room, reviewRound);
                reviewCounts.merge(review.state(), 1, Integer::sum);
                if (!review.permitsCleanup()) {
                    skippedPartCount += entry.getValue().size();
                    continue;
                }
                for (RecordHistoryPart part : entry.getValue()) {
                    String filePath = part.getFilePath();
                    if (partFileCleanupPolicy.shouldSkipProtectedArchive(
                            room, history, part, filePath, "MovePartFileJob", "move")) {
                        skippedPartCount++;
                        continue;
                    }
                    if (room.getMoveDir() != null && !room.getMoveDir().isBlank()) {
                        partFileOperationService.moveScheduled(part.getId(), room.getMoveDir(), review);
                    }
                }
            }
        }
        log.info("[BLR] {}", LogKvs.event("MovePartFileJob.Round.Done")
            .addRoundCount("room", roomCount)
            .addRoundCount("scannedPart", scannedPartCount)
            .addRoundCount("skippedPart", skippedPartCount)
            .add("reviewCounts", reviewCounts)
            .add("platformCircuitOpen", reviewRound.isPlatformUnavailable())
                .addStageCostMs("total", roundStartNs));
    }

    private Map<Long, List<RecordHistoryPart>> groupByHistory(List<RecordHistoryPart> parts) {
        Map<Long, List<RecordHistoryPart>> grouped = new LinkedHashMap<>();
        for (RecordHistoryPart part : parts) {
            if (part == null || part.getHistoryId() == null) continue;
            grouped.computeIfAbsent(part.getHistoryId(), ignored -> new java.util.ArrayList<>()).add(part);
        }
        return grouped;
    }
}
