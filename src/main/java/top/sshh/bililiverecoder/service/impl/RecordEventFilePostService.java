package top.sshh.bililiverecoder.service.impl;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.entity.RecordEventDTO;
import top.sshh.bililiverecoder.entity.RecordEventData;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.service.RecordEventService;
import top.sshh.bililiverecoder.util.LogKvs;

import java.io.File;

@Slf4j
@Component
public class RecordEventFilePostService implements RecordEventService {

    @Value("${record.work-path}")
    private String workPath;

    @Autowired
    private RecordHistoryPartRepository historyPartRepository;

    @PostConstruct
    public void initWorkPath() {
        workPath = workPath.replaceAll("\\\\\\\\", "\\\\");
        workPath = workPath.replace("\\", "/");
    }


    @Override
    public void processing(RecordEventDTO event) {
        RecordEventData eventData = event.getEventData();
        String sessionId = eventData.getSessionId();
        String relativePath = eventData.getRelativePath();
        log.info("[BLR] {}", LogKvs.event("FilePost.Received")
                .add("eventId", event.getEventId())
                .add("sessionId", sessionId)
                .add("relativePath", relativePath)
                .add("roomId", eventData.getRoomId()));
        if ("blrec".equals(sessionId)) {
            relativePath = relativePath.replace(workPath, "");
        }
        String filePath = workPath + File.separator + relativePath;
        // 正常逻辑
        String name = filePath.substring(0, filePath.lastIndexOf('.'));
        RecordHistoryPart part = historyPartRepository.findByFilePathStartingWith(name);
        if (part == null) {
            log.warn("[BLR] {}", LogKvs.event("FilePost.PartMissing")
                    .add("eventId", event.getEventId())
                    .add("sessionId", sessionId)
                    .add("relativePath", relativePath)
                    .add("filePath", filePath));
            return;
        }
        File vidleFile = new File(filePath);
        long fileSize = 0;
        if (vidleFile.exists()) {
            fileSize = vidleFile.length();
        } else {
            log.error("[BLR] {}", LogKvs.event("FilePost.FileMissing")
                    .add("eventId", event.getEventId())
                    .add("sessionId", sessionId)
                    .add("roomId", eventData.getRoomId())
                    .add("partId", part.getId())
                    .add("filePath", filePath));
            fileSize = eventData.getFileSize();
        }
        part.setRecording(false);
        part.setFilePath(filePath);
        part.setFileSize(fileSize);
        part = historyPartRepository.save(part);
        log.info("[BLR] {}", LogKvs.event("FilePost.Saved")
            .add("eventId", event.getEventId())
            .add("sessionId", sessionId)
            .add("roomId", eventData.getRoomId())
            .add("partId", part.getId())
            .add("filePath", filePath)
            .add("fileSize", fileSize));
    }
}
