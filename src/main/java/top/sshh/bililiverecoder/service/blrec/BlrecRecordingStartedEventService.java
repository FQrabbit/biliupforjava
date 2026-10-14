package top.sshh.bililiverecoder.service.blrec;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.entity.blrec.BlrecEventDTO;
import top.sshh.bililiverecoder.entity.blrec.BlrecRoomInfoDTO;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.service.RecordHistoryMergeService;
import top.sshh.bililiverecoder.service.RecordHistorySplitService;
import top.sshh.bililiverecoder.util.LogKvs;

import java.time.LocalDateTime;

@Slf4j
@Service("blrecRecordingStartedEventService")
public class BlrecRecordingStartedEventService implements BlrecEventService {
    @Autowired private RecordRoomRepository roomRepository;
    @Autowired private RecordHistoryRepository historyRepository;
    @Autowired private RecordHistoryMergeService historyMergeService;
    @Autowired private RecordHistorySplitService historySplitService;

    @Override
    public void processing(BlrecEventDTO event) {
        BlrecRoomInfoDTO info = event.getData().getRoomInfo();
        String roomId = info == null ? event.getData().getRoomId() : info.getRoomId();
        if (roomId == null) throw new IllegalArgumentException("事件缺少房间号");
        synchronized (roomId.intern()) {
            LocalDateTime now = LocalDateTime.now();
            RecordRoom room = roomRepository.findByRoomId(roomId);
            if (room == null) {
                room = new RecordRoom(); room.setRoomId(roomId); room.setCreateTime(now);
                log.info("[BLR] {}", LogKvs.event("Blrec.Room.AutoCreate").add("roomId", roomId));
            }
            if (historyRepository.findFirstByRoomIdAndEventId(roomId, event.getId()) != null) return;
            if (!historyMergeService.beginLive(room, event.getDate(), now)) return;
            String sessionId = info == null ? null : "blrec:" + roomId + ":" + info.getLiveStartTime();
            RecordHistory history = historyMergeService.findReusableHistory(room, sessionId, "RecordStarted");
            if (info != null) {
                room.setUname(String.valueOf(info.getUid())); room.setTitle(info.getTitle());
            }
            room.setWebhookSource("BLREC"); room.setWebhookLastSeenAt(now);
            room.setStreaming(info == null || info.getLiveStatus() == 1);
            room.setRecording(true); room.setSessionId(sessionId);
            if (history != null && history.isSplitClosed()) {
                roomRepository.saveLiveState(room);
                return;
            }
            if (history == null) {
                history = new RecordHistory(); historySplitService.initializeNew(history);
                history.setSplitGroup(room.getLiveGroup()); history.setRoomId(roomId);
                history.setTitle(room.getTitle()); history.setStartTime(now); history.setEndTime(now);
                history.setUpload(room.isUpload());
            }
            history.setEventId(event.getId()); history.setSessionId(sessionId);
            history.setRecording(true); history.setStreaming(room.isStreaming()); history.setUpdateTime(now);
            history = historyRepository.save(history);
            room.setHistoryId(history.getId()); roomRepository.saveLiveState(room);
        }
    }
}
