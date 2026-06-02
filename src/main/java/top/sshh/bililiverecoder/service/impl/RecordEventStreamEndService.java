package top.sshh.bililiverecoder.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.entity.RecordEventDTO;
import top.sshh.bililiverecoder.entity.RecordEventData;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.service.RecordEventService;
import top.sshh.bililiverecoder.util.LogKvs;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Component
public class RecordEventStreamEndService implements RecordEventService {

    @Autowired
    private RecordRoomRepository roomRepository;

    @Autowired
    private RecordHistoryRepository historyRepository;

    @Override
    public void processing(RecordEventDTO event) {
        RecordEventData eventData = event.getEventData();
        if (eventData == null || eventData.getRoomId() == null) {
            log.warn("[BLR] {}", LogKvs.event("StreamEnd.IgnoredEmpty")
                    .add("eventId", event.getEventId()));
            return;
        }

        log.info("[BLR] {}", LogKvs.event("StreamEnd.Received")
                .add("eventId", event.getEventId())
                .add("roomId", eventData.getRoomId())
                .add("title", eventData.getTitle()));
        RecordRoom room = roomRepository.findByRoomId(eventData.getRoomId());
        if (room == null) {
            log.warn("[BLR] {}", LogKvs.event("StreamEnd.RoomMissing")
                    .add("eventId", event.getEventId())
                    .add("roomId", eventData.getRoomId()));
            return;
        }

        // 直播结束仅清 streaming 标志；录制分P是否结束仍以 FileClosed/后处理/兜底纠偏为准。
        room.setStreaming(false);
        room.setUpdateTime(LocalDateTime.now());
        roomRepository.save(room);

        if (room.getHistoryId() != null) {
            Optional<RecordHistory> historyOptional = historyRepository.findById(room.getHistoryId());
            if (historyOptional.isPresent()) {
                RecordHistory history = historyOptional.get();
                if (!Objects.equals(history.getRoomId(), room.getRoomId()) || history.isForceArchived()) {
                    if (history.isForceArchived()) {
                        room.setHistoryId(-1L);
                        room.setSessionId(null);
                        roomRepository.save(room);
                    }
                    log.info("[BLR] {}", LogKvs.event("StreamEnd.SkipHistoryUpdate")
                            .add("roomId", eventData.getRoomId())
                            .add("historyId", history.getId())
                            .add("forceArchived", history.isForceArchived()));
                    return;
                }
                history.setStreaming(false);
                history.setUpdateTime(LocalDateTime.now());
                history.setEndTime(LocalDateTime.now());
                historyRepository.save(history);
            }
        } else {
            // 当 historyId 为空时，说明录播姬发送了 Webhook 但本地并没有开启录制或录制记录已丢失
            log.info("[BLR] {}", LogKvs.event("StreamEnd.NoRecording")
                    .add("roomId", eventData.getRoomId())
                    .add("msg", "收到下播事件但本地无活跃录制记录。请检查录播姬是否开启了自动录制，或录播姬与本程序的连接是否正常。"));
        }
    }
}
