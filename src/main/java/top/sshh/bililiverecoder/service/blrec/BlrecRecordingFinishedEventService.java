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
import top.sshh.bililiverecoder.util.LogKvs;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service("blrecRecordingFinishedEventService")
public class BlrecRecordingFinishedEventService implements BlrecEventService {

    @Autowired
    private RecordRoomRepository roomRepository;

    @Autowired
    private RecordHistoryRepository historyRepository;

    @Override
    public void processing(BlrecEventDTO event) {
        String roomId = event.getData().getRoomInfo() == null ? event.getData().getRoomId() : event.getData().getRoomInfo().getRoomId();
        if (roomId == null) throw new IllegalArgumentException("事件缺少房间号");
        synchronized (roomId.intern()) { processingInside(event); }
    }

    private void processingInside(BlrecEventDTO event) {
        BlrecRoomInfoDTO roomInfo = event.getData().getRoomInfo();
        String roomId = roomInfo.getRoomId();

        RecordRoom room = roomRepository.findByRoomId(roomId);
        if (room == null) {
            log.warn("[BLR] {}", LogKvs.event("Blrec.RecordingFinished.RoomNotFound").add("roomId", roomId));
            return;
        }

        RecordHistory current = room.getHistoryId() == null ? null : historyRepository.findById(room.getHistoryId()).orElse(null);
        if (current != null && current.getStartTime() != null && event.getDate() != null
                && java.time.LocalDateTime.ofInstant(event.getDate().toInstant(), java.time.ZoneId.systemDefault()).isBefore(current.getStartTime())) return;

        // 更新房间状态
        room.setRecording(false);
        room.setStreaming(roomInfo.getLiveStatus() == 1);
        roomRepository.save(room);

        // 查找并终结对应的历史记录
        Optional<RecordHistory> historyOpt = historyRepository.findById(room.getHistoryId());
        if (historyOpt.isPresent()) {
            RecordHistory history = historyOpt.get();
            if (history.isSplitClosed()) return;
            if (history.isForceArchived()) {
                room.setHistoryId(-1L);
                room.setSessionId(null);
                roomRepository.save(room);
                log.info("[BLR] {}", LogKvs.event("Blrec.RecordingFinished.SkipForceArchived")
                        .add("roomId", roomId)
                        .add("historyId", history.getId()));
                return;
            }
            history.setRecording(false);
            history.setStreaming(room.isStreaming());
            history.setEndTime(LocalDateTime.now());
            historyRepository.save(history);
            log.info("[BLR] {}", LogKvs.event("Blrec.RecordingFinished.Success")
                    .add("roomId", roomId)
                    .add("historyId", history.getId()));
        } else {
            log.warn("[BLR] {}", LogKvs.event("Blrec.RecordingFinished.HistoryNotFound")
                    .add("roomId", roomId)
                    .add("historyId", room.getHistoryId()));
        }
    }
}
