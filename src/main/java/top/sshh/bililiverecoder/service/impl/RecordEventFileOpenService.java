package top.sshh.bililiverecoder.service.impl;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.entity.RecordEventDTO;
import top.sshh.bililiverecoder.entity.RecordEventData;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.service.RecordEventService;
import top.sshh.bililiverecoder.service.RecordHistoryMergeService;
import top.sshh.bililiverecoder.service.PartFileLocationService;
import top.sshh.bililiverecoder.util.LogKvs;

import java.io.File;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Slf4j
@Component
public class RecordEventFileOpenService implements RecordEventService {

    @Value("${record.work-path}")
    private String workPath;

    @Autowired
    private RecordRoomRepository roomRepository;

    @Autowired
    private RecordHistoryRepository historyRepository;

    @Autowired
    private RecordHistoryPartRepository historyPartRepository;

    @Autowired
    private RecordHistoryMergeService historyMergeService;

    @Autowired
    private PartFileLocationService partFileLocationService;

    @PostConstruct
    public void initWorkPath() {
        workPath = workPath.replaceAll("\\\\\\\\", "\\\\");
        workPath = workPath.replace("\\", "/");
    }

    @Override
    public void processing(RecordEventDTO event) {
        RecordEventData eventData = event.getEventData();
        String roomId = eventData.getRoomId();
        if (roomId == null || roomId.isBlank()) {
            log.error("[BLR] {}", LogKvs.event("Webhook.InvalidPayload").add("reason", "RoomId is null or blank"));
            return;
        }

        synchronized (roomId.intern()) {
            try {
                String relativePath = eventData.getRelativePath();
                String incomingSessionId = eventData.getSessionId();

                log.info("[BLR] {}", LogKvs.event("FileOpen.Received")
                        .add("roomId", roomId)
                        .add("sessionId", incomingSessionId)
                        .add("filePath", relativePath));

                RecordRoom room = roomRepository.findByRoomId(roomId);
                if (room == null) {
                    log.warn("[BLR] {}", LogKvs.event("FileOpen.RoomNotFound.AutoCreate")
                            .add("roomId", roomId));
                    room = new RecordRoom();
                    room.setRoomId(roomId);
                    room.setUname(eventData.getName());
                    room.setTitle(eventData.getTitle());
                    room.setCreateTime(LocalDateTime.now());
                    room.setHistoryId(-1L);
                }

                String currentSessionId = room.getSessionId();
                if (incomingSessionId != null && !incomingSessionId.equals(currentSessionId)) {
                    log.debug("[BLR] {}", LogKvs.event("SessionMismatch.Detected")
                            .add("roomId", roomId)
                            .add("reason", "Session changed; choosing history by configured merge interval")
                            .add("currentSessionId", currentSessionId)
                            .add("incomingSessionId", incomingSessionId));

                    LocalDateTime now = LocalDateTime.now();
                    RecordHistory history = historyMergeService.findReusableHistory(
                            roomId, now, incomingSessionId, "SessionMismatch");

                    if (history == null) {
                        history = new RecordHistory();
                        history.setRoomId(roomId);
                        history.setStartTime(fileOpenTimeOrNow(eventData, now));
                        history.setEndTime(history.getStartTime());
                        history.setEventId(event.getEventId());
                        history.setTitle(eventData.getTitle());
                        history.setUpload(room.isUpload());

                        log.info("[BLR] {}", LogKvs.event("SessionMismatch.CreatedNew")
                                .add("roomId", roomId)
                                .add("newSessionId", incomingSessionId));
                    }

                    history.setSessionId(incomingSessionId);
                    history.setRecording(true);
                    history.setStreaming(eventData.isStreaming());
                    history = historyRepository.save(history);

                    room.setHistoryId(history.getId());
                    room.setSessionId(incomingSessionId);
                    room.setRecording(true);
                    room.setStreaming(eventData.isStreaming());
                    room.setUname(eventData.getName());
                    room.setTitle(eventData.getTitle());
                    roomRepository.save(room);

                    log.info("[BLR] {}", LogKvs.event("SessionMismatch.Recovered")
                            .add("roomId", roomId)
                            .add("historyId", history.getId())
                            .add("newSessionId", incomingSessionId));
                }

                Optional<RecordHistory> historyOptional = historyRepository.findById(room.getHistoryId());
                if (historyOptional.isEmpty()) {
                    log.error("[BLR] {}", LogKvs.event("FileOpen.FATAL.HistoryStillNotFound")
                            .add("roomId", roomId)
                            .add("historyId", room.getHistoryId())
                            .add("sessionId", incomingSessionId));
                    return;
                }
                RecordHistory history = historyOptional.get();

                int partCount = historyPartRepository.countByHistoryId(history.getId());
                if (partCount >= 99) {
                    log.warn("[BLR] {}", LogKvs.event("FileOpen.PartLimitReached")
                            .add("historyId", history.getId())
                            .add("limit", 100));
                    return;
                }

                if ("blrec".equals(incomingSessionId)) {
                    relativePath = relativePath.replace(workPath, "");
                }
                String filePath = workPath + File.separator + relativePath;

                if (historyPartRepository.existsByFilePath(filePath)) {
                    log.warn("[BLR] {}", LogKvs.event("FileOpen.PartExists.Skip")
                            .add("roomId", roomId)
                            .add("historyId", history.getId())
                            .add("filePath", filePath));
                    return;
                }

                RecordHistoryPart part = new RecordHistoryPart();
                part.setEventId(event.getEventId());
                part.setTitle(LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM\u6708dd\u65e5HH\u70b9mm\u5206ss\u79d2")));
                part.setLiveTitle(eventData.getTitle());
                part.setAreaName(eventData.getAreaNameChild());
                part.setRoomId(history.getRoomId());
                part.setHistoryId(history.getId());
                part.setFilePath(filePath);
                part.setFileSize(0L);
                part.setPartOrder(partCount + 1);
                part.setSessionId(incomingSessionId);
                part.setRecording(true);
                part.setStartTime(LocalDateTime.now());
                part.setEndTime(null);
                part = historyPartRepository.save(part);
                partFileLocationService.registerPrimary(part);

                log.info("[BLR] {}", LogKvs.event("FileOpen.PartSaved")
                        .add("roomId", roomId)
                        .add("historyId", history.getId())
                        .add("partId", part.getId())
                        .add("partTitle", part.getTitle()));

                history.setTitle(eventData.getTitle());
                history.setRecording(true);
                history.setStreaming(eventData.isStreaming());
                history.setFilePath(workPath + File.separator + relativePath.substring(0, relativePath.lastIndexOf('/')));
                history.setEndTime(LocalDateTime.now());
                historyRepository.save(history);

            } catch (Exception e) {
                log.error("[BLR] {}", LogKvs.event("FileOpen.UnhandledException")
                        .add("roomId", eventData.getRoomId())
                        .add("sessionId", eventData.getSessionId())
                        .add("err", e.getMessage())
                        .add("ex", e.getClass().getSimpleName()), e);
            }
        }
    }

    private LocalDateTime fileOpenTimeOrNow(RecordEventData eventData, LocalDateTime fallback) {
        if (eventData.getFileOpenTime() == null) {
            return fallback;
        }
        return eventData.getFileOpenTime().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
    }
}
