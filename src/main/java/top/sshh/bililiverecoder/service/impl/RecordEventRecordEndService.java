package top.sshh.bililiverecoder.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
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
import top.sshh.bililiverecoder.util.LogKvs;

import java.io.File;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Component
public class RecordEventRecordEndService implements RecordEventService {


    @Autowired
    private RecordHistoryRepository historyRepository;
    @Autowired
    private RecordRoomRepository roomRepository;

    @Autowired
    private RecordHistoryPartRepository partRepository;

    @Override
    public void processing(RecordEventDTO event) {
        RecordEventData eventData = event.getEventData();
        log.info("[BLR] {}", LogKvs.event("RecordEnd.Received")
                .add("eventId", event.getEventId())
                .add("roomId", eventData.getRoomId())
                .add("title", eventData.getTitle())
                .add("sessionId", eventData.getSessionId()));
        RecordRoom room = roomRepository.findByRoomId(eventData.getRoomId());
        if (room == null) {
            log.info("[BLR] {}", LogKvs.event("RecordEnd.NoRoom")
                    .add("roomId", eventData.getRoomId())
                    .add("sessionId", eventData.getSessionId()));
            return;
        }
        if (StringUtils.isNotBlank(room.getSessionId())
                && StringUtils.isNotBlank(eventData.getSessionId())
                && !room.getSessionId().equals(eventData.getSessionId())) {
            log.info("[BLR] {}", LogKvs.event("RecordEnd.IgnoreStaleSession")
                    .add("roomId", eventData.getRoomId())
                    .add("currentSessionId", room.getSessionId())
                    .add("incomingSessionId", eventData.getSessionId())
                    .add("historyId", room.getHistoryId()));
            return;
        }
        if (room.getHistoryId() != null) {
            Optional<RecordHistory> historyOptional = historyRepository.findById(room.getHistoryId());
            if (historyOptional.isPresent()) {
                RecordHistory history = historyOptional.get();
                if (!Objects.equals(history.getRoomId(), room.getRoomId()) || history.isForceArchived()) {
                    room.setRecording(false);
                    room.setStreaming(false);
                    room.setSessionId(null);
                    if (history.isForceArchived()) {
                        room.setHistoryId(-1L);
                    }
                    roomRepository.save(room);
                    log.info("[BLR] {}", LogKvs.event("RecordEnd.SkipHistoryUpdate")
                            .add("roomId", eventData.getRoomId())
                            .add("historyId", history.getId())
                            .add("forceArchived", history.isForceArchived()));
                    return;
                }
                history.setSessionId(eventData.getSessionId());
                history.setEndTime(LocalDateTime.now());
                history.setRecording(false);
                history.setStreaming(false);
                historyRepository.save(history);
                room.setRecording(false);
                room.setStreaming(false);
                room.setSessionId(null);
                roomRepository.save(room);

                // 兜底：录播姬 webhook 不重传，若服务离线导致缺失 FileClosed，则分P可能长期残留 recording=true/endTime=null。
                // 在'录制结束'事件到达时，按磁盘文件是否稳定（10分钟未修改）来纠偏分P结束态。
                try {
                    long thresholdMs = 10L * 60L * 1000L;
                    long nowMs = System.currentTimeMillis();
                    int healed = 0;
                    List<RecordHistoryPart> parts = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());
                    for (RecordHistoryPart part : parts) {
                        if (!part.isRecording() && part.getEndTime() != null) {
                            continue;
                        }
                        String filePath = part.getFilePath();
                        if (filePath == null) {
                            continue;
                        }
                        File file = new File(filePath);
                        if (!file.exists()) {
                            continue;
                        }
                        if (file.lastModified() > nowMs - thresholdMs) {
                            continue;
                        }
                        boolean changed = false;
                        if (part.isRecording()) {
                            part.setRecording(false);
                            changed = true;
                        }
                        if (part.getEndTime() == null) {
                            part.setEndTime(LocalDateTime.now());
                            changed = true;
                        }
                        if (part.getFileSize() <= 0) {
                            part.setFileSize(file.length());
                            changed = true;
                        }
                        if (part.getDuration() <= 0 && part.getStartTime() != null && part.getEndTime() != null) {
                            try {
                                part.setDuration((float) java.time.Duration.between(part.getStartTime(), part.getEndTime()).getSeconds());
                                changed = true;
                            } catch (Exception ignored) {
                            }
                        }
                        if (changed) {
                            partRepository.save(part);
                            healed++;
                        }
                    }
                    if (healed > 0) {
                        log.info("[BLR] {}", LogKvs.event("RecordEnd.PartHeal.Done")
                                .add("roomId", eventData.getRoomId())
                                .add("historyId", history.getId())
                                .add("healed", healed));
                    }
                } catch (Exception e) {
                    log.warn("[BLR] {}", LogKvs.event("RecordEnd.PartHeal.Failed")
                            .add("roomId", eventData.getRoomId())
                            .add("historyId", historyOptional.map(RecordHistory::getId).orElse(null))
                            .add("err", e.getMessage())
                            .add("ex", e.getClass().getSimpleName()), e);
                }
            }
        } else {
            // 当 historyId 为空时，说明录播姬发送了录制结束的 Webhook 但本地并没有开启录制或录制记录已丢失
            log.info("[BLR] {}", LogKvs.event("RecordEnd.NoRecording")
                    .add("roomId", eventData.getRoomId())
                    .add("msg", "收到录制结束事件但本地无活跃录制记录。请检查录播姬是否开启了自动录制。"));
        }
//        recordBiliPublishService.publishRecordHistory(history);
    }

}


