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
import top.sshh.bililiverecoder.service.PartFileLocationService;
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

    @Autowired
    private PartFileLocationService partFileLocationService;

    @Autowired
    private top.sshh.bililiverecoder.service.RecordPartPathService partPathService;

    @Autowired
    private top.sshh.bililiverecoder.service.RecordHistorySplitService historySplitService;

    @Autowired
    private top.sshh.bililiverecoder.service.PartPreviewService previewService;

    @Override
    public void processing(BlrecEventDTO event) {
        String roomId = event.getData().getRoomInfo() == null ? event.getData().getRoomId() : event.getData().getRoomInfo().getRoomId();
        if (roomId == null) throw new IllegalArgumentException("事件缺少房间号");
        synchronized (roomId.intern()) { processingInside(event); }
    }

    private void processingInside(BlrecEventDTO event) {
        BlrecDataDTO eventData = event.getData();
        String roomId = eventData.getRoomInfo() == null ? eventData.getRoomId() : eventData.getRoomInfo().getRoomId();
        String filePath = partPathService.resolveWebhookPath(eventData.getPath());

        RecordRoom room = roomRepository.findByRoomId(roomId);
        if (room == null) {
            log.warn("[BLR] {}", LogKvs.event("Blrec.VideoFileCompleted.Skip")
                    .add("reason", "Room not found or not in recording state")
                    .add("roomId", roomId)
                    .add("filePath", filePath));
            return;
        }

        RecordHistoryPart existing = partRepository.findByFilePath(filePath);
        if (existing != null && !roomId.equals(existing.getRoomId())) return;
        Optional<RecordHistory> historyOpt = historyRepository.findById(existing == null ? room.getHistoryId() : existing.getHistoryId());
        if (!historyOpt.isPresent()) {
            log.error("[BLR] {}", LogKvs.event("Blrec.VideoFileCompleted.HistoryNotFound")
                    .add("roomId", roomId)
                    .add("historyId", room.getHistoryId())
                    .add("filePath", filePath));
            return;
        }
        RecordHistory history = historyOpt.get();

        // 检查文件是否已存在，防止重复处理
        if (existing == null) existing = findByCanonicalPath(partRepository.findByHistoryId(history.getId()), filePath);
        if (existing == null && historySplitService != null) history = historySplitService.historyForNewPart(room, history, LocalDateTime.now());

        // 创建新的分P记录
        RecordHistoryPart part = existing == null ? new RecordHistoryPart() : existing;
        part.setHistoryId(history.getId());
        if (existing == null && history.isSplitEnabled()) part.setSplitAssigned(false);
        part.setSourceType("blrec"); // 关键：标记来源为 blrec
        part.setRoomId(roomId);
        if (part.getId() == null) part.setEventId(event.getId());
        part.setFilePath(filePath);
        part.setTitle(LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM月dd日HH点mm分ss秒")));
        part.setLiveTitle(history.getTitle());
        if (part.getId() == null) part.setPartOrder(partRepository.countByHistoryId(history.getId()) + 1);
        if (part.getEndTime() == null) part.setEndTime(event.getDate() == null ? LocalDateTime.now()
                : LocalDateTime.ofInstant(event.getDate().toInstant(), java.time.ZoneId.systemDefault()));
        part.setRecording(false);
        part.setCloseSource("FILE_CLOSED");
        java.io.File file = new java.io.File(filePath);
        if (file.isFile()) part.setFileSize(file.length());
        
        part = partRepository.save(part);
        partFileLocationService.registerPrimary(part);
        if (part.getDuration() <= 0 && previewService != null) {
            Double duration = previewService.probeDuration(part.getId());
            if (duration != null) {
                part.setDuration(duration.floatValue());
                if (part.getStartTime() == null) part.setStartTime(part.getEndTime().minusNanos((long) (duration * 1000000000L)));
                partRepository.save(part);
            }
        }
        if (historySplitService != null) {
            historySplitService.reconcile(history.getId());
        }

        log.info("[BLR] {}", LogKvs.event("Blrec.VideoFileCompleted.PartSaved")
                .add("roomId", roomId)
                .add("historyId", history.getId())
                .add("partId", part.getId())
                .add("filePath", filePath));
    }

    private RecordHistoryPart findByCanonicalPath(Iterable<RecordHistoryPart> parts, String filePath) {
        if (parts == null) {
            return null;
        }
        for (RecordHistoryPart part : parts) {
            if (part != null && partPathService.sameFile(part.getFilePath(), filePath)) {
                return part;
            }
        }
        return null;
    }
}
