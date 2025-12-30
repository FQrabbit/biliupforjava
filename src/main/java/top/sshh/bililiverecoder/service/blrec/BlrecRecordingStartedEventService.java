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

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Slf4j
@Service("blrecRecordingStartedEventService")
public class BlrecRecordingStartedEventService implements BlrecEventService {

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
            room = new RecordRoom();
            room.setRoomId(roomId);
            room.setCreateTime(LocalDateTime.now());
            log.info("[BLR] {}", LogKvs.event("Blrec.Room.AutoCreate").add("roomId", roomId));
        }

        // 更新房间基本信息
        room.setUname(String.valueOf(roomInfo.getUid())); // 注意：blrec的room_info里没有uname，我们暂用uid代替
        room.setTitle(roomInfo.getTitle());
        room.setStreaming(roomInfo.getLiveStatus() == 1);
        room.setRecording(true); // 核心：将录制状态设置为 true
        
        // 创建新的录制历史
        RecordHistory history = new RecordHistory();
        history.setRoomId(roomId);
        history.setTitle(roomInfo.getTitle());
        history.setStartTime(LocalDateTime.ofInstant(Instant.ofEpochSecond(roomInfo.getLiveStartTime()), ZoneId.systemDefault()));
        history.setEndTime(history.getStartTime());
        history.setEventId(event.getId()); // 使用blrec的事件ID
        history.setRecording(true);
        history.setStreaming(true);
        history.setUpload(room.isUpload());
        history = historyRepository.save(history);

        // 关联新的历史记录
        room.setHistoryId(history.getId());
        roomRepository.save(room);

        log.info("[BLR] {}", LogKvs.event("Blrec.RecordingStarted.Success")
                .add("roomId", roomId)
                .add("title", roomInfo.getTitle())
                .add("newHistoryId", history.getId()));
    }
}
