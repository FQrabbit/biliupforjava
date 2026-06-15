package top.sshh.bililiverecoder.service.blrec;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.entity.blrec.BlrecDataDTO;
import top.sshh.bililiverecoder.entity.blrec.BlrecEventDTO;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.util.LogKvs;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Slf4j
@Service("blrecVideoFileCompletedEventService")
public class BlrecVideoFileCompletedEventService implements BlrecEventService {

    @Autowired
    private RecordRoomRepository roomRepository;

    @Autowired
    private RecordHistoryRepository historyRepository;

    @Autowired
    private RecordHistoryPartRepository partRepository;

    @Override
    public void processing(BlrecEventDTO event) {
        BlrecDataDTO eventData = event.getData();
        String roomId = eventData.getRoomInfo().getRoomId();
        String filePath = eventData.getPath();

        RecordRoom room = roomRepository.findByRoomId(roomId);
        if (room == null || !room.isRecording()) {
            log.warn("[BLR] {}", LogKvs.event("Blrec.VideoFileCompleted.Skip")
                    .add("reason", "Room not found or not in recording state")
                    .add("roomId", roomId)
                    .add("filePath", filePath));
            return;
        }

        Optional<RecordHistory> historyOpt = historyRepository.findById(room.getHistoryId());
        if (!historyOpt.isPresent()) {
            log.error("[BLR] {}", LogKvs.event("Blrec.VideoFileCompleted.HistoryNotFound")
                    .add("roomId", roomId)
                    .add("historyId", room.getHistoryId())
                    .add("filePath", filePath));
            return;
        }
        RecordHistory history = historyOpt.get();

        // 检查文件是否已存在，防止重复处理
        if (partRepository.existsByFilePath(filePath)) {
            log.warn("[BLR] {}", LogKvs.event("Blrec.VideoFileCompleted.PartExists")
                    .add("roomId", roomId)
                    .add("historyId", history.getId())
                    .add("filePath", filePath));
            return;
        }

        // 创建新的分P记录
        RecordHistoryPart part = new RecordHistoryPart();
        part.setHistoryId(history.getId());
        part.setSourceType("blrec"); // 关键：标记来源为 blrec
        part.setRoomId(roomId);
        part.setEventId(event.getId());
        part.setFilePath(filePath);
        part.setTitle(LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM月dd日HH点mm分ss秒")));
        part.setLiveTitle(history.getTitle());
        part.setPartOrder(partRepository.countByHistoryId(history.getId()) + 1);
        part.setStartTime(history.getStartTime()); // 简单起见，暂用主历史的开始时间
        part.setEndTime(LocalDateTime.now());
        part.setRecording(false); // 文件已完成
        
        partRepository.save(part);

        log.info("[BLR] {}", LogKvs.event("Blrec.VideoFileCompleted.PartSaved")
                .add("roomId", roomId)
                .add("historyId", history.getId())
                .add("partId", part.getId())
                .add("filePath", filePath));
    }
}
