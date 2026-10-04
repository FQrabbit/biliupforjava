package top.sshh.bililiverecoder.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.service.impl.*;
import top.sshh.bililiverecoder.util.LogKvs;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class RecordEventFactory {

    @Autowired
    private RecordEventRecordStartedService recordEventRecordStartedService;
    @Autowired
    private RecordEventRecordEndService recordEventRecordEndService;
    @Autowired
    private RecordRoomChangeService recordRoomChangeService;
    @Autowired
    private RecordEventStreamStartService recordEventStreamStartService;
    @Autowired
    private RecordEventStreamEndService recordEventStreamEndService;
    @Autowired
    private RecordEventFileOpenService recordEventFileOpenService;
    @Autowired
    private RecordEventFileClosedService recordEventFileClosedService;
    @Autowired
    private RecordEventFilePostService recordEventFilePostService;
    @Autowired
    private RecordEventEmptyService recordEventEmptyService;

    private final Map<String, BlrecRoomInfo> blrecRoomInfoMap = new ConcurrentHashMap<>();
    private final Map<String, BlrecUserInfo> blrecUserInfoMap = new ConcurrentHashMap<>();


    public RecordEventService getEventService(String eventType) {
        return switch (eventType) {
            case RecordEventType.SessionStarted, RecordEventType.RecordingStartedEvent ->
                    recordEventRecordStartedService;
            case RecordEventType.SessionEnded, RecordEventType.RecordingFinishedEvent, RecordEventType.RecordingCancelledEvent ->
                    recordEventRecordEndService;
            case RecordEventType.RoomChangeEvent -> recordRoomChangeService;
            case RecordEventType.StreamStarted, RecordEventType.LiveBeganEvent -> recordEventStreamStartService;
            case RecordEventType.StreamEnded, RecordEventType.LiveEndedEvent -> recordEventStreamEndService;
            case RecordEventType.FileOpening, RecordEventType.VideoFileCreatedEvent -> recordEventFileOpenService;
            case RecordEventType.FileClosed, RecordEventType.VideoFileCompletedEvent -> recordEventFileClosedService;
            case RecordEventType.VideoPostprocessingCompletedEvent -> recordEventFilePostService;
            default -> recordEventEmptyService;
        };
    }

    public boolean isUnsupportedEvent(RecordEventDTO eventDTO) {
        if (eventDTO == null) return false;
        String eventType = StringUtils.isNotBlank(eventDTO.getEventType())
                ? eventDTO.getEventType() : eventDTO.getData() == null ? null : eventDTO.getType();
        return StringUtils.isNotBlank(eventType) && getEventService(eventType) == recordEventEmptyService;
    }

    public void processing(RecordEventDTO eventDTO) {
        if (StringUtils.isBlank(eventDTO.getEventType())) {
            if (eventDTO.getData() != null) {
                eventDTO.setEventType(eventDTO.getType());
                RecordEventData eventData = new RecordEventData();
                eventDTO.setEventData(eventData);
                BlrecRoomInfo roomInfo = eventDTO.getData().getRoomInfo();
                BlrecUserInfo userInfo = eventDTO.getData().getUserInfo();
                eventDTO.setEventId(eventDTO.getId());
                eventData.setSessionId("blrec");
                if (RecordEventType.VideoFileCreatedEvent.equals(eventDTO.getEventType())) eventData.setFileOpenTime(eventDTO.getDate());
                if (RecordEventType.VideoFileCompletedEvent.equals(eventDTO.getEventType())) eventData.setFileCloseTime(eventDTO.getDate());
                eventData.setRecording(false);
                String path = eventDTO.getData().getPath();
                if (StringUtils.isNotBlank(path)) {
                    path = path.replace("\\", "/");
                    eventData.setRelativePath(path);
                }
                String roomId = eventDTO.getData().getRoomId();
                if (StringUtils.isNotBlank(roomId)) {
                    eventData.setRoomId(roomId);
                }
                if (roomInfo != null) {
                    if (StringUtils.isNotBlank(roomInfo.getRoomId())) {
                        blrecRoomInfoMap.put(roomInfo.getRoomId(), roomInfo);
                    }
                } else {
                    roomInfo = blrecRoomInfoMap.get(roomId);
                }
                if (userInfo != null) {
                    if (StringUtils.isNotBlank(userInfo.getUid())) {
                        blrecUserInfoMap.put(userInfo.getUid(), userInfo);
                    }
                } else if (roomInfo != null) {
                    userInfo = blrecUserInfoMap.get(roomInfo.getUid());
                }
                if (roomInfo != null) {
                    eventData.setRoomId(roomInfo.getRoomId());
                    eventData.setShortId(roomInfo.getSortRoomId());
                    eventData.setTitle(roomInfo.getTitle());
                    eventData.setAreaNameParent(roomInfo.getParentAreaName());
                    eventData.setAreaNameChild(roomInfo.getAreaName());
                    eventData.setRecording(roomInfo.getLiveStatus() == 1);
                }
                if (userInfo != null) {
                    eventData.setName(userInfo.getName());
                }

            }
        }
        String eventType = eventDTO.getEventType();
        if (StringUtils.isBlank(eventType)) {
            log.error("[BLR] {}", LogKvs.event("RecordEvent.TypeMissing")
                    .add("eventId", eventDTO.getEventId())
                    .add("type", eventDTO.getType())
                    .add("hasData", eventDTO.getData() != null)
                    .add("hasEventData", eventDTO.getEventData() != null));
            throw new IllegalArgumentException("Webhook 缺少可识别的事件类型");
        }
        RecordEventService eventService = this.getEventService(eventType);
        if (eventService == recordEventEmptyService) {
            log.info("[BLR] {}", LogKvs.event("Webhook.EventIgnored").add("type", eventType));
            return;
        }
        eventService.processing(eventDTO);
    }
}
