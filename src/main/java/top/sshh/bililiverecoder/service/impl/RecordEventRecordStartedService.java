package top.sshh.bililiverecoder.service.impl;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import top.sshh.bililiverecoder.entity.RecordEventDTO;
import top.sshh.bililiverecoder.entity.RecordEventData;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.repo.*;
import top.sshh.bililiverecoder.service.RecordEventService;
import top.sshh.bililiverecoder.util.LogKvs;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
public class RecordEventRecordStartedService implements RecordEventService {

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
        String roomId = eventData.getRoomId();
        
        // 全局加锁：确保同一房间的录制开始事件串行处理，防止并发导致的重复记录或状态竞争
        synchronized (roomId.intern()) {
            RecordRoom room = roomRepository.findByRoomId(eventData.getRoomId());
            LocalDateTime now = LocalDateTime.now();
            if(room == null) {
                // 原有的双重检查锁在最外层加锁后已不再需要，但保留逻辑结构
                log.warn("[BLR] {}", LogKvs.event("Room.AutoCreate")
                        .add("roomId", eventData.getRoomId())
                        .add("eventId", event.getEventId())
                        .add("sessionId", eventData.getSessionId())
                        .add("reason", "room_not_found"));
                room = new RecordRoom();
                room.setRoomId(eventData.getRoomId());
                room.setCreateTime(now);
                room = roomRepository.save(room);
            }
            
            room.setUname(eventData.getName());
            room.setTitle(eventData.getTitle());
            room.setSessionId(eventData.getSessionId());
            room.setRecording(eventData.isRecording());
            room.setStreaming(eventData.isStreaming());
            List<RecordHistory> historyList = historyRepository.findByRoomIdAndEndTimeBetweenOrderByEndTimeAsc(eventData.getRoomId(), now.minusMinutes(20L), now);
            RecordHistory history;
            if (CollectionUtils.isEmpty(historyList)) {
                history = new RecordHistory();
                history.setRoomId(room.getRoomId());
                history.setStartTime(now);
                history.setUpdateTime(now);
                history.setEndTime(now);
                history.setTitle(eventData.getTitle());
                history.setUpload(room.isUpload());
            } else {
                // 复用最近的一条记录（取列表的最后一条，即 EndTime 最大的）
                history = historyList.get(historyList.size() - 1);
                log.info("[BLR] {}", LogKvs.event("RecordStarted.ReuseHistory")
                        .add("roomId", roomId)
                        .add("sessionId", eventData.getSessionId())
                        .add("eventId", event.getEventId())
                        .add("historyId", history.getId()));
                log.debug("[BLR] {}", LogKvs.event("RecordStarted.ReuseHistory.Detail")
                        .add("roomId", roomId)
                        .add("historyId", history.getId())
                        .add("payload", JSON.toJSONString(history)));
            }
            history.setEventId(event.getEventId());
            history.setSessionId(eventData.getSessionId());
            history.setRecording(eventData.isRecording());
            history.setStreaming(eventData.isStreaming());
            historyRepository.save(history);
            room.setHistoryId(history.getId());
            roomRepository.save(room);
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
