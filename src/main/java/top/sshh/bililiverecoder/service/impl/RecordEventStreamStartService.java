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

    @Override
    public void processing(RecordEventDTO event) {
        RecordEventData eventData = event.getEventData();
        String roomId = eventData.getRoomId();
        RecordRoom room = roomRepository.findByRoomId(eventData.getRoomId());
        if (room == null) {
            synchronized (roomId.intern()) {
                room = roomRepository.findByRoomId(eventData.getRoomId());
                if (room == null) {
                    log.warn("[BLR] {}", LogKvs.event("Room.AutoCreate")
                            .add("roomId", eventData.getRoomId())
                            .add("title", eventData.getTitle())
                            .add("uname", eventData.getName()));
                    room = new RecordRoom();
                    room.setRoomId(eventData.getRoomId());
                    room.setCreateTime(LocalDateTime.now());
                    room.setTitle(eventData.getTitle());
                    room = roomRepository.save(room);
                }
            }
        }
        room.setUname(eventData.getName());
        room.setTitle(eventData.getTitle());
        room.setSessionId(eventData.getSessionId());
        room.setRecording(eventData.isRecording());
        room.setStreaming(eventData.isStreaming());
        room = roomRepository.save(room);
        NotificationEvent notificationEvent = NotificationEvent.of(room, NotificationEventType.LIVE_STREAM_STARTED)
                .add("liveTitle", room.getTitle())
                .add("areaNameParent", eventData.getAreaNameParent())
                .add("areaNameChild", eventData.getAreaNameChild());
        notificationEventPublisher.publish(notificationEvent, room);
    }
}


