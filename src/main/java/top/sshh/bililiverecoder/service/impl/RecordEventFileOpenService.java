package top.sshh.bililiverecoder.service.impl;

import com.alibaba.fastjson.JSON;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.repo.*;
import top.sshh.bililiverecoder.service.RecordEventService;
import top.sshh.bililiverecoder.util.LogKvs;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Slf4j
@Component
public class RecordEventFileOpenService implements RecordEventService {

    @Value("${record.work-path}")
    private String workPath;

    @PostConstruct
    public void initWorkPath() {
        workPath = workPath.replaceAll("\\\\\\\\", "\\\\");
        workPath = workPath.replace("\\", "/");
    }

    @Autowired
    private BiliUserRepository biliUserRepository;

    @Autowired
    private RecordRoomRepository roomRepository;

    @Autowired
    private RecordHistoryRepository historyRepository;

    @Autowired
    private RecordHistoryPartRepository historyPartRepository;

    @Autowired
    private LiveMsgRepository liveMsgRepository;


    @Override
    public void processing(RecordEventDTO event) {
        RecordEventData eventData = event.getEventData();
        String relativePath = eventData.getRelativePath();
        log.info("[BLR] {}", LogKvs.event("FileOpen")
                .add("roomId", eventData.getRoomId())
                .add("title", eventData.getTitle())
                .add("filePath", relativePath));
        String sessionId = eventData.getSessionId();
        try {
            Thread.sleep(5000L);
        } catch (Exception e) {
            log.warn("[BLR] {}", LogKvs.event("FileOpen.SleepInterrupted")
                    .add("roomId", eventData.getRoomId())
                    .add("sessionId", eventData.getSessionId())
                    .add("sleepMs", 5000)
                    .add("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()), e);
        }
        String roomId = eventData.getRoomId();
        RecordRoom room = roomRepository.findByRoomId(eventData.getRoomId());
        if (room == null) {
            synchronized (roomId.intern()) {
                room = roomRepository.findByRoomId(eventData.getRoomId());
                if (room == null) {
                    log.error("[BLR] {}", LogKvs.event("FileOpen.MissingRoom")
                            .add("roomId", eventData.getRoomId())
                            .add("title", eventData.getTitle())
                            .add("sessionId", eventData.getSessionId()));
                    room = new RecordRoom();
                    room.setRoomId(eventData.getRoomId());
                    room.setCreateTime(LocalDateTime.now());
                    if (eventData.getName() != null) {
                        room.setUname(eventData.getName());
                    }
                    room.setTitle(eventData.getTitle());
                    room.setHistoryId(-999L);
                    room = roomRepository.save(room);
                }
            }
        } else {
            room.setUname(eventData.getName());
            room.setTitle(eventData.getTitle());
            room.setSessionId(eventData.getSessionId());
            room.setRecording(eventData.isRecording());
            room.setStreaming(eventData.isStreaming());
            room = roomRepository.save(room);
        }
        Optional<RecordHistory> historyOptional = historyRepository.findById(room.getHistoryId());
        RecordHistory history = null;
        if (historyOptional.isPresent()) {
            history = historyOptional.get();
            if (!eventData.getRoomId().equals(history.getRoomId())) {
                log.error("[BLR] {}", LogKvs.event("FileOpen.HistoryRoomMismatch")
                        .add("roomId", eventData.getRoomId())
                        .add("history", JSON.toJSONString(history)));
                history = null;
            }
        }
        //异常情况判断
        if (history == null || (!"blrec".equals(eventData.getSessionId()) && !eventData.getSessionId().equals(history.getSessionId()) && history.getEndTime().isBefore(LocalDateTime.now().minusMinutes(10L)))) {
            log.error("[BLR] {}", LogKvs.event("FileOpen.MissingHistory")
                    .add("roomId", eventData.getRoomId())
                    .add("title", eventData.getTitle())
                    .add("sessionId", eventData.getSessionId()));

            history = new RecordHistory();
            history.setEventId(event.getEventId());
            history.setRoomId(room.getRoomId());
            history.setStartTime(LocalDateTime.now());
            history.setEndTime(LocalDateTime.now());
            history.setTitle(eventData.getTitle());
            history.setSessionId(eventData.getSessionId());
            history.setRecording(eventData.isRecording());
            history.setStreaming(eventData.isStreaming());
            history.setUpload(room.isUpload());
            history = historyRepository.save(history);
            room.setHistoryId(history.getId());
            room = roomRepository.save(room);
        }
        int partCount = historyPartRepository.countByHistoryId(history.getId());

        if(partCount>99){
            log.warn("[BLR] {}", LogKvs.event("FileOpen.PartLimitReached")
                    .add("historyId", history.getId())
                    .add("limit", 100));
            //更新唯一键,更新录制状态
            history.setEventId(history.getEventId()+1);
            history.setSessionId(history.getSessionId()+1);
            history.setRecording(false);
            history.setStreaming(false);
            history = historyRepository.save(history);
            //创建新的录制历史
            history = new RecordHistory();
            history.setEventId(event.getEventId());
            history.setRoomId(room.getRoomId());
            history.setStartTime(LocalDateTime.now());
            history.setEndTime(LocalDateTime.now());
            history.setTitle(eventData.getTitle());
            history.setSessionId(eventData.getSessionId());
            history.setRecording(eventData.isRecording());
            history.setStreaming(eventData.isStreaming());
            history = historyRepository.save(history);
        }
        if ("blrec".equals(sessionId)) {
            relativePath = relativePath.replace(workPath, "");
        }
        String filePath = workPath + File.separator + relativePath;
        // 正常逻辑
        boolean existsPart = historyPartRepository.existsByFilePath(filePath);
        if(existsPart){
            log.warn("[BLR] {}", LogKvs.event("FileOpen.PartExists")
                    .add("roomId", eventData.getRoomId())
                    .add("historyId", history.getId())
                    .add("filePath", filePath));
            return;
        }
        RecordHistoryPart part = new RecordHistoryPart();
        part.setEventId(event.getEventId());
        part.setTitle(LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM月dd日HH点mm分ss秒")));
        part.setLiveTitle(eventData.getTitle());
        part.setAreaName(eventData.getAreaNameChild());
        part.setRoomId(history.getRoomId());
        part.setHistoryId(history.getId());
        part.setFilePath(filePath);
        part.setFileSize(0L);
        part.setSessionId(eventData.getSessionId());
        part.setRecording(eventData.isRecording());
        part.setStartTime(LocalDateTime.now());
        part.setEndTime(LocalDateTime.now());
        part = historyPartRepository.save(part);
        log.info("[BLR] {}", LogKvs.event("FileOpen.Saved")
            .add("roomId", eventData.getRoomId())
            .add("historyId", history.getId())
            .add("partDbId", part.getId())
            .add("partTitle", part.getTitle()));
        log.debug("[BLR] {}", LogKvs.event("FileOpen.DebugPart")
            .add("part", JSON.toJSONString(part)));
        history.setTitle(eventData.getTitle());
        history.setSessionId(eventData.getSessionId());
        history.setRecording(eventData.isRecording());
        history.setStreaming(eventData.isStreaming());
        history.setFilePath(workPath + File.separator + relativePath.substring(0, relativePath.lastIndexOf('/')));
        history.setEndTime(LocalDateTime.now());
        historyRepository.save(history);

    }
}
