package top.sshh.bililiverecoder.job;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.util.LogKvs;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
public class DeletePartFileJob {


    @Value("${record.work-path}")
    private String workPath;

    @Value("${record.delete.max-retry:72}")
    private int maxDeleteRetry;

    @Autowired
    RecordRoomRepository roomRepository;

    @Autowired
    RecordHistoryPartRepository partRepository;

    @Scheduled(fixedDelay = 3600000, initialDelay = 60000)
    public void deleteFileProcess() {
        List<RecordRoom> roomList = roomRepository.findByDeleteType(3);
        for (RecordRoom room : roomList) {
            LocalDateTime deleteTime = LocalDateTime.now().minusDays(room.getDeleteDay());
            List<RecordHistoryPart> partList = partRepository.findByRoomIdAndFileDeleteIsFalseAndEndTimeIsBefore(room.getRoomId(),deleteTime);
            if (partList.size() > 0) {
                log.info("[BLR] {}", LogKvs.event("DeletePartFileJob.Start")
                        .add("roomId", room.getRoomId())
                        .add("uname", room.getUname())
                        .add("count", partList.size()));
            }
            for (RecordHistoryPart part : partList) {
                String filePath = part.getFilePath();
                File file = new File(filePath);
                if (!file.exists()) {
                    log.info("[BLR] {}", LogKvs.event("DeletePartFileJob.SkipNotExists")
                            .add("roomId", room.getRoomId())
                            .add("uname", room.getUname())
                            .add("filePath", filePath));
                    part.setFileDelete(true);
                    part.setDeleteRetryCount(0);
                    part.setDeleteFailReason(null);
                    partRepository.save(part);
                    continue;
                }

                String failReason = null;
                try {
                    Files.deleteIfExists(file.toPath());
                } catch (Exception e) {
                    failReason = e.getClass().getSimpleName() + ":" + String.valueOf(e.getMessage());
                }

                if (!file.exists()) {
                    log.info("[BLR] {}", LogKvs.event("DeletePartFileJob.Success")
                            .add("roomId", room.getRoomId())
                            .add("uname", room.getUname())
                            .add("filePath", filePath));
                    part.setFileDelete(true);
                    part.setDeleteRetryCount(0);
                    part.setDeleteFailReason(null);
                } else {
                    int nextRetry = part.getDeleteRetryCount() + 1;
                    part.setDeleteRetryCount(nextRetry);

                    if (failReason != null && failReason.length() > 512) {
                        failReason = failReason.substring(0, 512);
                    }
                    part.setDeleteFailReason(failReason);

                    if (nextRetry >= maxDeleteRetry) {
            log.error("[BLR] {}", LogKvs.event("DeletePartFileJob.FailedGiveUp")
                .add("roomId", room.getRoomId())
                .add("uname", room.getUname())
                .add("filePath", filePath)
                .add("retry", nextRetry)
                .add("maxRetry", maxDeleteRetry)
                .add("reason", failReason));
                        part.setFileDelete(true);
                    } else {
            log.warn("[BLR] {}", LogKvs.event("DeletePartFileJob.FailedRetry")
                .add("roomId", room.getRoomId())
                .add("uname", room.getUname())
                .add("filePath", filePath)
                .add("retry", nextRetry)
                .add("maxRetry", maxDeleteRetry)
                .add("reason", failReason));
                        part.setFileDelete(false);
                    }
                }

                partRepository.save(part);
            }
        }
    }
    @Scheduled(fixedDelay = 3600000, initialDelay = 60000)
    public void moveFileProcess() {
        List<RecordRoom> roomList = roomRepository.findByDeleteType(8);
        for (RecordRoom room : roomList) {
            LocalDateTime deleteTime = LocalDateTime.now().minusDays(room.getDeleteDay());
            List<RecordHistoryPart> partList = partRepository.findByRoomIdAndFileDeleteIsFalseAndEndTimeIsBefore(room.getRoomId(),deleteTime);
            if (partList.size() > 0) {
                log.info("[BLR] {}", LogKvs.event("MovePartFileJob.Start")
                        .add("roomId", room.getRoomId())
                        .add("uname", room.getUname())
                        .add("count", partList.size()));
            }
            for (RecordHistoryPart part : partList) {
                String filePath = part.getFilePath();
                String fileName = filePath.substring(filePath.lastIndexOf("/") + 1, filePath.lastIndexOf("."));
                String startDirPath = filePath.substring(0, filePath.lastIndexOf('/') + 1);
                String toDirPath = room.getMoveDir() + filePath.substring(0, filePath.lastIndexOf('/') + 1).replace(workPath, "");
                File toDir = new File(toDirPath);
                if (!toDir.exists()) {
                    toDir.mkdirs();
                }
                File startDir = new File(startDirPath);
                File[] files = startDir.listFiles((file, s) -> s.startsWith(fileName));
                if (files != null && files.length > 0) {
                    for (File file : files) {
                        if(! filePath.startsWith(workPath)){
                            part.setFileDelete(true);
                            part = partRepository.save(part);
                            continue;
                        }
                        if(room.getDeleteType() == 8){
                            try {
                                Files.move(Paths.get(file.getPath()), Paths.get(toDirPath + file.getName()),
                                        StandardCopyOption.REPLACE_EXISTING);
                                log.info("[BLR] {}", LogKvs.event("MovePartFileJob.Success")
                                        .add("roomId", room.getRoomId())
                                        .add("uname", room.getUname())
                                        .add("fileName", file.getName()));
                            } catch (Exception e) {
                                log.error("[BLR] {}", LogKvs.event("MovePartFileJob.Failed")
                                        .add("roomId", room.getRoomId())
                                        .add("uname", room.getUname())
                                        .add("fileName", file.getName())
                                        .add("reason", e.getClass().getSimpleName() + ":" + String.valueOf(e.getMessage())));
                            }
                        }

                    }
                }
                
                part.setFilePath(toDirPath + filePath.substring(filePath.lastIndexOf("/") + 1));
                part.setFileDelete(true);
                part = partRepository.save(part);
            }
        }
    }
}
