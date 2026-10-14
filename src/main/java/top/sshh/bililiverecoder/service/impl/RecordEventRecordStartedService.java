package top.sshh.bililiverecoder.service.impl;

import com.alibaba.fastjson.JSON;
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
import top.sshh.bililiverecoder.service.RecordHistoryMergeService;
import top.sshh.bililiverecoder.util.LogKvs;

import java.time.LocalDateTime;

@Slf4j
@Component
public class RecordEventRecordStartedService implements RecordEventService {

    @Autowired
    private RecordRoomRepository roomRepository;

    @Autowired
    private RecordHistoryRepository historyRepository;

    @Autowired
    private RecordHistoryMergeService historyMergeService;

    @Autowired
    private top.sshh.bililiverecoder.service.RecordHistorySplitService historySplitService;

    @Override
    public void processing(RecordEventDTO event) {
        RecordEventData eventData = event.getEventData();
        String roomId = eventData.getRoomId();
        if (roomId == null || roomId.isBlank()) {
            log.error("[BLR] {}", LogKvs.event("RecordStarted.InvalidPayload")
                    .add("eventId", event.getEventId())
                    .add("reason", "RoomId is null or blank"));
            throw new IllegalArgumentException("录制开始事件缺少房间号");
        }

        synchronized (roomId.intern()) {
            RecordRoom room = roomRepository.findByRoomId(roomId);
            LocalDateTime now = LocalDateTime.now();
            if (room == null) {
                log.warn("[BLR] {}", LogKvs.event("Room.AutoCreate")
                        .add("roomId", roomId)
                        .add("eventId", event.getEventId())
                        .add("sessionId", eventData.getSessionId())
                        .add("reason", "room_not_found"));
                room = new RecordRoom();
                room.setRoomId(roomId);
                room.setCreateTime(now);
                room = roomRepository.saveLiveState(room);
            }

            String currentSessionId = room.getSessionId();
            if (room.isRecording() && java.util.Objects.equals(currentSessionId, eventData.getSessionId())
                    && historyRepository.findFirstByRoomIdAndEventId(roomId, event.getEventId()) != null) return;
            if (!historyMergeService.beginLive(room,
                    event.getEventTimestamp() == null ? event.getDate() : event.getEventTimestamp(), now)) return;
            RecordHistory history = historyMergeService.findReusableHistory(room, eventData.getSessionId(), "RecordStarted");
            room.setUname(eventData.getName());
            room.setTitle(eventData.getTitle());
            room.setSessionId(eventData.getSessionId());
            room.setWebhookSource("BREC");
            room.setWebhookLastSeenAt(now);
            room.setRecording(eventData.isRecording());
            room.setStreaming(eventData.isStreaming());

            // 同场重连可以继续拆分组，已经收尾的稿件本身不能重新打开
            if (history != null && history.isSplitClosed()) {
                roomRepository.saveLiveState(room);
                return;
            }

            if (history == null) {
                history = new RecordHistory();
                if (historySplitService != null) historySplitService.initializeNew(history);
                history.setSplitGroup(room.getLiveGroup());
                history.setRoomId(room.getRoomId());
                history.setStartTime(now);
                history.setUpdateTime(now);
                history.setEndTime(now);
                history.setTitle(eventData.getTitle());
                history.setUpload(room.isUpload());
            } else {
                log.debug("[BLR] {}", LogKvs.event("RecordStarted.ReuseHistory.Detail")
                        .add("roomId", roomId)
                        .add("historyId", history.getId())
                        .add("payload", JSON.toJSONString(history)));
            }

            history.setEventId(event.getEventId());
            history.setSessionId(eventData.getSessionId());
            history.setRecording(eventData.isRecording());
            history.setStreaming(eventData.isStreaming());
            history = historyRepository.save(history);

            room.setHistoryId(history.getId());
            roomRepository.saveLiveState(room);

            log.info("[BLR] {}", LogKvs.event("RecordStarted.Processed")
                    .add("roomId", roomId)
                    .add("uname", room.getUname())
                    .add("title", room.getTitle())
                    .add("sessionId", eventData.getSessionId())
                    .add("eventId", event.getEventId())
                    .add("historyId", history.getId())
                    .add("recording", eventData.isRecording())
                    .add("streaming", eventData.isStreaming()));
        }
    }
}
