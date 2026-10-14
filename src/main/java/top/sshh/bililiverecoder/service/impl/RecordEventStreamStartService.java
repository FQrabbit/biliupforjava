package top.sshh.bililiverecoder.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.entity.RecordEventDTO;
import top.sshh.bililiverecoder.entity.RecordEventData;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.notification.NotificationEvent;
import top.sshh.bililiverecoder.notification.NotificationEventType;
import top.sshh.bililiverecoder.notification.NotificationEventPublisher;
import top.sshh.bililiverecoder.notification.LiveNotificationDurationService;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.service.RecordEventService;
import top.sshh.bililiverecoder.util.LogKvs;

import java.time.LocalDateTime;

@Slf4j
@Component
public class RecordEventStreamStartService implements RecordEventService {

    @Autowired
    private RecordRoomRepository roomRepository;

    @Autowired
    private NotificationEventPublisher notificationEventPublisher;

    @Autowired
    private LiveNotificationDurationService liveNotificationDurationService;

    @Autowired
    private top.sshh.bililiverecoder.service.RecordHistoryMergeService historyMergeService;

    @Override
    public void processing(RecordEventDTO event) {
        RecordEventData eventData = event.getEventData();
        String roomId = eventData.getRoomId();
        synchronized (roomId.intern()) {
            RecordRoom room = roomRepository.findByRoomId(roomId);
            if (room == null) {
                log.warn("[BLR] {}", LogKvs.event("Room.AutoCreate")
                        .add("roomId", roomId).add("title", eventData.getTitle()).add("uname", eventData.getName()));
                room = new RecordRoom();
                room.setRoomId(roomId); room.setCreateTime(LocalDateTime.now()); room.setTitle(eventData.getTitle());
                room = roomRepository.saveLiveState(room);
            }
            if (!historyMergeService.beginLive(room,
                    event.getEventTimestamp() == null ? event.getDate() : event.getEventTimestamp(), LocalDateTime.now())) return;
            liveNotificationDurationService.streamStarted(event);
            room.setUname(eventData.getName());
            room.setTitle(eventData.getTitle());
            room.setSessionId(eventData.getSessionId());
            room.setWebhookSource("BREC");
            room.setWebhookLastSeenAt(LocalDateTime.now());
            room.setRecording(eventData.isRecording());
            room.setStreaming(eventData.isStreaming());
            room = roomRepository.saveLiveState(room);
            NotificationEvent notificationEvent = NotificationEvent.of(room, NotificationEventType.LIVE_STREAM_STARTED)
                    .add("liveTitle", room.getTitle())
                    .add("areaNameParent", eventData.getAreaNameParent())
                    .add("areaNameChild", eventData.getAreaNameChild());
            notificationEventPublisher.publish(notificationEvent, room);
        }
    }
}
