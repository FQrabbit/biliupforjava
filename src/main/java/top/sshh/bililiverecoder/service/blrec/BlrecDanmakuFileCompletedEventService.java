package top.sshh.bililiverecoder.service.blrec;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.entity.blrec.BlrecDataDTO;
import top.sshh.bililiverecoder.entity.blrec.BlrecEventDTO;
import top.sshh.bililiverecoder.job.LiveMsgSendSync;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.service.impl.LiveMsgService;
import top.sshh.bililiverecoder.util.LogKvs;

@Slf4j
@Service("blrecDanmakuFileCompletedEventService")
public class BlrecDanmakuFileCompletedEventService implements BlrecEventService {

    @Autowired
    private RecordRoomRepository roomRepository;

    @Autowired
    private RecordHistoryPartRepository partRepository;
    
    @Autowired
    private LiveMsgService liveMsgService;

    @Autowired
    private LiveMsgSendSync liveMsgSendSync;

    @Override
    public void processing(BlrecEventDTO event) {
        BlrecDataDTO eventData = event.getData();
        String roomId = eventData.getRoomInfo().getRoomId();
        String danmakuFilePath = eventData.getPath();

        String pathStem = danmakuFilePath.substring(0, danmakuFilePath.lastIndexOf("."));
        RecordHistoryPart part = null;
        String videoFilePath = null;
        for (String extension : new String[]{".flv", ".mp4", ".mkv", ".ts"}) {
            String candidate = pathStem + extension;
            part = partRepository.findByFilePath(candidate);
            if (part != null) {
                videoFilePath = candidate;
                break;
            }
        }
        if (part == null) {
            part = partRepository.findByFilePathStartingWith(pathStem);
            videoFilePath = part == null ? pathStem : part.getFilePath();
        }

        if (part == null) {
            RecordRoom room = roomRepository.findByRoomId(roomId);
            log.error("[BLR] {}", LogKvs.event("Blrec.DanmakuCompleted.PartNotFound")
                    .add("roomId", roomId)
                    .add("historyId", room == null ? null : room.getHistoryId())
                    .add("videoFilePath", videoFilePath));
            return;
        }

        part.setDanmakuFilePath(danmakuFilePath);
        partRepository.save(part);

        RecordRoom room = roomRepository.findByRoomId(roomId);
        if (room == null || !room.isRecording()) {
            log.info("[BLR] {}", LogKvs.event("Blrec.DanmakuCompleted.LiveMsgSkip")
                    .add("reason", "Room not found or not in recording state")
                    .add("roomId", roomId)
                    .add("partId", part.getId()));
            return;
        }

        // 调用现有的 LiveMsgService 来解析 XML 文件
        // 注意：LiveMsgService 内部会自动将 .flv 路径替换为 .xml
        liveMsgService.processing(part);
        liveMsgSendSync.enqueueHistoryDispatch(part.getHistoryId());
        
        log.info("[BLR] {}", LogKvs.event("Blrec.DanmakuCompleted.Processed")
                .add("roomId", roomId)
                .add("partId", part.getId())
                .add("danmakuFilePath", danmakuFilePath));
    }
}
