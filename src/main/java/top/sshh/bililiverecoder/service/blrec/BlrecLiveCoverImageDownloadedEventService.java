package top.sshh.bililiverecoder.service.blrec;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.entity.blrec.BlrecDataDTO;
import top.sshh.bililiverecoder.entity.blrec.BlrecEventDTO;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.util.LogKvs;

import java.util.Optional;

@Slf4j
@Service("blrecLiveCoverImageDownloadedEventService")
public class BlrecLiveCoverImageDownloadedEventService implements BlrecEventService {

    @Autowired
    private RecordRoomRepository roomRepository;

    @Autowired
    private RecordHistoryRepository historyRepository;

    @Override
    public void processing(BlrecEventDTO event) {
        BlrecDataDTO eventData = event.getData();
        String roomId = eventData.getRoomInfo().getRoomId();
        String coverPath = eventData.getPath();

        RecordRoom room = roomRepository.findByRoomId(roomId);
        if (room == null || !room.isRecording()) {
            log.warn("[BLR] {}", LogKvs.event("Blrec.CoverDownloaded.Skip")
                    .add("reason", "Room not found or not in recording state")
                    .add("roomId", roomId)
                    .add("coverPath", coverPath));
            return;
        }

        Optional<RecordHistory> historyOpt = historyRepository.findById(room.getHistoryId());
        if (!historyOpt.isPresent()) {
            log.error("[BLR] {}", LogKvs.event("Blrec.CoverDownloaded.HistoryNotFound")
                    .add("roomId", roomId)
                    .add("historyId", room.getHistoryId())
                    .add("coverPath", coverPath));
            return;
        }

        RecordHistory history = historyOpt.get();
        // 将本地文件路径保存到 coverUrl 字段，投稿时可直接使用
        history.setCoverUrl(coverPath);
        historyRepository.save(history);

        log.info("[BLR] {}", LogKvs.event("Blrec.CoverDownloaded.Success")
                .add("roomId", roomId)
                .add("historyId", history.getId())
                .add("coverPath", coverPath));
    }
}
