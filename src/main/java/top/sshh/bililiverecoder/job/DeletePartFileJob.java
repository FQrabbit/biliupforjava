package top.sshh.bililiverecoder.job;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.entity.PartFileLocation;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.service.PartFileCleanupPolicy;
import top.sshh.bililiverecoder.service.PartFileOperationService;
import top.sshh.bililiverecoder.service.StorageRootService;
import top.sshh.bililiverecoder.util.LogKvs;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
public class DeletePartFileJob {

    @Autowired
    RecordRoomRepository roomRepository;

    @Autowired
    RecordHistoryPartRepository partRepository;

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
            for (RecordHistoryPart part : partList) {
                String filePath = part.getFilePath();
                if (partFileCleanupPolicy.shouldSkipProtectedArchive(room, part, filePath, "DeletePartFileJob", "delete")) {
                    continue;
                }
                partFileOperationService.delete(part.getId());
            }
        }
        log.info("[BLR] {}", LogKvs.event("DeletePartFileJob.Round.Done")
            .addRoundCount("room", roomCount)
            .addRoundCount("scannedPart", scannedPartCount)
                .addStageCostMs("total", roundStartNs));
    }

    @Scheduled(fixedDelay = 3600000, initialDelay = 60000)
    public void moveFileProcess() {
        if (storageRootService.hasPendingWorkPathChange()) return;
        long roundStartNs = System.nanoTime();
        int roomCount = 0;
        int scannedPartCount = 0;
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
            for (RecordHistoryPart part : partList) {
                String filePath = part.getFilePath();
                if (partFileCleanupPolicy.shouldSkipProtectedArchive(room, part, filePath, "MovePartFileJob", "move")) {
                    continue;
                }
                if (room.getDeleteType() == 8 && room.getMoveDir() != null && !room.getMoveDir().isBlank()) {
                    partFileOperationService.move(part.getId(), room.getMoveDir());
                }
            }
        }
        log.info("[BLR] {}", LogKvs.event("MovePartFileJob.Round.Done")
            .addRoundCount("room", roomCount)
            .addRoundCount("scannedPart", scannedPartCount)
                .addStageCostMs("total", roundStartNs));
    }
}
