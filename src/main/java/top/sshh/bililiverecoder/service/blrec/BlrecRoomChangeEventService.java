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

import java.util.Optional;

@Slf4j
@Service("blrecRoomChangeEventService")
public class BlrecRoomChangeEventService implements BlrecEventService {

    @Autowired
    private RecordRoomRepository roomRepository;

    @Autowired
    private RecordHistoryRepository historyRepository;

    @Override
    public void processing(BlrecEventDTO event) {
        BlrecRoomInfoDTO roomInfo = event.getData().getRoomInfo();
        String roomId = roomInfo.getRoomId();
        String newTitle = roomInfo.getTitle();

        RecordRoom room = roomRepository.findByRoomId(roomId);
        if (room == null) {
            log.warn("[BLR] {}", LogKvs.event("Blrec.RoomChange.RoomNotFound").add("roomId", roomId));
            return;
        }

        // 更新房间标题
        room.setTitle(newTitle);
        roomRepository.save(room);

        // 如果正在录制，也更新当前录制历史的标题
        if (room.isRecording()) {
            Optional<RecordHistory> historyOpt = historyRepository.findById(room.getHistoryId());
            if (historyOpt.isPresent()) {
                RecordHistory history = historyOpt.get();
                history.setTitle(newTitle);
                historyRepository.save(history);
            }
        }
        log.info("[BLR] {}", LogKvs.event("Blrec.RoomChange.Success")
                .add("roomId", roomId)
                .add("newTitle", newTitle));
    }
}
