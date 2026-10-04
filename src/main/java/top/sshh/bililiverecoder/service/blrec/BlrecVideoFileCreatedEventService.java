package top.sshh.bililiverecoder.service.blrec;

import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.entity.blrec.BlrecEventDTO;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.service.impl.RecordEventFileOpenService;

@Service("blrecVideoFileCreatedEventService")
public class BlrecVideoFileCreatedEventService implements BlrecEventService {
    private final RecordEventFileOpenService files;
    private final RecordRoomRepository rooms;

    public BlrecVideoFileCreatedEventService(RecordEventFileOpenService files, RecordRoomRepository rooms) {
        this.files = files;
        this.rooms = rooms;
    }

    @Override
    public void processing(BlrecEventDTO event) {
        String roomId = event.getData().getRoomInfo() == null ? event.getData().getRoomId() : event.getData().getRoomInfo().getRoomId();
        RecordRoom room = rooms.findByRoomId(roomId);
        if (room == null) return;
        RecordEventDTO normalized = new RecordEventDTO();
        normalized.setEventId(event.getId());
        normalized.setEventType(RecordEventType.VideoFileCreatedEvent);
        RecordEventData data = new RecordEventData();
        data.setRoomId(roomId);
        data.setSessionId(room.getSessionId());
        data.setRelativePath(event.getData().getPath());
        data.setFileOpenTime(event.getDate());
        data.setTitle(room.getTitle());
        data.setName(room.getUname());
        data.setRecording(room.isRecording());
        data.setStreaming(room.isStreaming());
        normalized.setEventData(data);
        files.processing(normalized);
    }
}
