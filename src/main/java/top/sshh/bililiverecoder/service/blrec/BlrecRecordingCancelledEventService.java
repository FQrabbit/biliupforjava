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
@Service("blrecRecordingCancelledEventService")
public class BlrecRecordingCancelledEventService implements BlrecEventService {

    @Autowired
    private RecordRoomRepository roomRepository;

    @Autowired
    private RecordHistoryRepository historyRepository;

    @Override
    public void processing(BlrecEventDTO event) {
        BlrecRoomInfoDTO roomInfo = event.getData().getRoomInfo();
        String roomId = roomInfo.getRoomId();

        RecordRoom room = roomRepository.findByRoomId(roomId);
        if (room == null) {
            log.warn("[BLR] {}", LogKvs.event("Blrec.RecordingCancelled.RoomNotFound").add("roomId", roomId));
            return;
        }

        // 更新房间状态
        room.setRecording(false);
        // 通常取消时仍在直播，以事件为准
        room.setStreaming(roomInfo.getLiveStatus() == 1);
        roomRepository.save(room);

        // 查找并终结对应的历史记录
        Optional<RecordHistory> historyOpt = historyRepository.findById(room.getHistoryId());
        if (historyOpt.isPresent()) {
            RecordHistory history = historyOpt.get();
            history.setRecording(false);
            history.setStreaming(room.isStreaming());
            history.setEndTime(LocalDateTime.now());
            // 可以在这里增加一个字段来标记这次录制是被“取消”的
            // history.setStatus("CANCELLED");
            historyRepository.save(history);
            log.info("[BLR] {}", LogKvs.event("Blrec.RecordingCancelled.Success")
                    .add("roomId", roomId)
                    .add("historyId", history.getId()));
        } else {
            log.warn("[BLR] {}", LogKvs.event("Blrec.RecordingCancelled.HistoryNotFound")
                    .add("roomId", roomId)
                    .add("historyId", room.getHistoryId()));
        }
    }
}
