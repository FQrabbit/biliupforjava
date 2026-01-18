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
    private RecordRoomRepository roomRepository;

    @Autowired
    private RecordHistoryRepository historyRepository;

    @Autowired
    private RecordHistoryPartRepository historyPartRepository;


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

            RecordHistory history = null;
            // 优先复用正在录制中的记录（解决长直播过程中因网络抖动/重连导致的 session 变更被切分问题）
            List<RecordHistory> activeHistoryList = historyRepository.findByRoomIdAndRecordingTrueOrderByStartTimeDesc(eventData.getRoomId());
            if (!CollectionUtils.isEmpty(activeHistoryList)) {
                RecordHistory activeHistory = activeHistoryList.get(0);
                // 增加活跃度检查：如果录制中的记录在过去 24 小时内没有任何更新（以 endTime 为准），则视为过时记录
                // 额外检查：如果稿件已经发布（publish=true），说明已经提交审核，无法再通过投稿接口增加分P，必须拆分新稿件
                if (activeHistory.getEndTime() != null && activeHistory.getEndTime().isAfter(now.minusHours(24)) && !activeHistory.isPublish()) {
                    history = activeHistory;
                    log.info("[BLR] {}", LogKvs.event("RecordStarted.ReuseActiveHistory")
                            .add("roomId", roomId)
                            .add("sessionId", eventData.getSessionId())
                            .add("eventId", event.getEventId())
                            .add("historyId", history.getId()));
                } else if (activeHistory.isPublish()) {
                    log.info("[BLR] {}", LogKvs.event("RecordStarted.SkipPublished")
                            .add("roomId", roomId)
                            .add("sessionId", eventData.getSessionId())
                            .add("historyId", activeHistory.getId()));
                } else {
                    log.warn("[BLR] {}", LogKvs.event("RecordStarted.ActiveHistoryStale")
                            .add("roomId", roomId)
                            .add("historyId", activeHistory.getId())
                            .add("lastActivity", activeHistory.getEndTime())
                            .add("reason", "Recording marked true but no activity for 24h"));
                    // 如果是过时记录，级联清理其所有状态
                    activeHistory.setRecording(false);
                    activeHistory.setStreaming(false);
                    if (activeHistory.getEndTime() == null) {
                        activeHistory.setEndTime(now);
                    }
                    historyRepository.save(activeHistory);

                    // 同步清理该历史下的所有僵尸分P
                    historyPartRepository.findByHistoryId(activeHistory.getId()).stream()
                            .filter(p -> p.isRecording() || p.getEndTime() == null)
                            .forEach(p -> {
                                p.setRecording(false);
                                if (p.getEndTime() == null) {
                                    p.setEndTime(now);
                                }
                                historyPartRepository.save(p);
                            });
                }
            }

            if (history == null) {
                // 其次复用最近结束的记录（解决短时间内的重连）
                // 同样需要检查 publish 状态，已发布的稿件无法追加分P
                List<RecordHistory> historyList = historyRepository.findByRoomIdAndEndTimeBetweenOrderByEndTimeAsc(eventData.getRoomId(), now.minusMinutes(20L), now);
                if (!CollectionUtils.isEmpty(historyList)) {
                    // 过滤掉已经发布的稿件
                    historyList = historyList.stream().filter(h -> !h.isPublish()).collect(java.util.stream.Collectors.toList());
                    if (!historyList.isEmpty()) {
                        // 复用最近的一条记录（取列表的最后一条，即 EndTime 最大的）
                        history = historyList.get(historyList.size() - 1);
                        log.info("[BLR] {}", LogKvs.event("RecordStarted.ReuseRecentHistory")
                                .add("roomId", roomId)
                                .add("sessionId", eventData.getSessionId())
                                .add("eventId", event.getEventId())
                                .add("historyId", history.getId()));
                    }
                }
            }

            if (history == null) {
                history = new RecordHistory();
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
