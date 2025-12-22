package top.sshh.bililiverecoder.service.impl;

import com.zjiecode.wxpusher.client.WxPusher;
import com.zjiecode.wxpusher.client.bean.Message;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.entity.RecordEventDTO;
import top.sshh.bililiverecoder.entity.RecordEventData;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.service.RecordEventService;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
public class RecordEventRecordEndService implements RecordEventService {

    @Value("${record.wx-push-token}")
    private String wxToken;

    private static final String WX_MSG_FORMAT= """
            收到主播%s下播，录制结束
            房间名: %s
            父分区: %s
            子分区: %s
            时间: %s
            若十分钟内未收到录制开始事件，
            则在上传完成后发布视频。
            """;
    @Autowired
    private RecordHistoryRepository historyRepository;
    @Autowired
    private RecordRoomRepository roomRepository;

    @Autowired
    private RecordHistoryPartRepository partRepository;

    @Autowired
    private RecordBiliPublishService recordBiliPublishService;


    @Override
    public void processing(RecordEventDTO event) {
        RecordEventData eventData = event.getEventData();
        log.info("录制结束事件==>{}=={}", eventData.getRoomId(), eventData.getTitle());
        RecordRoom room = roomRepository.findByRoomId(eventData.getRoomId());
        Optional<RecordHistory> historyOptional = historyRepository.findById(room.getHistoryId());
        if (historyOptional.isPresent()) {
            RecordHistory history = historyOptional.get();
            history.setSessionId(eventData.getSessionId());
            history.setEndTime(LocalDateTime.now());
            history.setRecording(false);
            history.setStreaming(false);
            historyRepository.save(history);
            room.setRecording(false);
            room.setStreaming(false);
            room.setSessionId(null);
            roomRepository.save(room);

            // 兜底：录播姬 webhook 不重传，若服务离线导致缺失 FileClosed，则分P可能长期残留 recording=true/endTime=null。
            // 在'录制结束'事件到达时，按磁盘文件是否稳定（10分钟未修改）来纠偏分P结束态。
            try {
                long thresholdMs = 10L * 60L * 1000L;
                long nowMs = System.currentTimeMillis();
                int healed = 0;
                List<RecordHistoryPart> parts = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());
                for (RecordHistoryPart part : parts) {
                    if (!part.isRecording() && part.getEndTime() != null) {
                        continue;
                    }
                    String filePath = part.getFilePath();
                    if (filePath == null) {
                        continue;
                    }
                    File file = new File(filePath);
                    if (!file.exists()) {
                        continue;
                    }
                    if (file.lastModified() > nowMs - thresholdMs) {
                        continue;
                    }
                    boolean changed = false;
                    if (part.isRecording()) {
                        part.setRecording(false);
                        changed = true;
                    }
                    if (part.getEndTime() == null) {
                        part.setEndTime(LocalDateTime.now());
                        changed = true;
                    }
                    if (part.getFileSize() <= 0) {
                        part.setFileSize(file.length());
                        changed = true;
                    }
                    if (part.getDuration() <= 0 && part.getStartTime() != null && part.getEndTime() != null) {
                        try {
                            part.setDuration((float) java.time.Duration.between(part.getStartTime(), part.getEndTime()).getSeconds());
                            changed = true;
                        } catch (Exception ignored) {
                        }
                    }
                    if (changed) {
                        partRepository.save(part);
                        healed++;
                    }
                }
                if (healed > 0) {
                    log.info("录制结束事件兜底纠偏分P完成 healed={} HistoryId={}", healed, history.getId());
                }
            } catch (Exception e) {
                log.warn("录制结束事件兜底纠偏分P失败 RoomId={} Err={}", eventData.getRoomId(), e.getMessage());
            }
        }
        String wxuid = room.getWxuid();
        String pushMsgTags = room.getPushMsgTags();
        if(StringUtils.isNotBlank(wxuid)&&StringUtils.isNotBlank(pushMsgTags)&&pushMsgTags.contains("录制结束")){
            Message message = new Message();
            message.setAppToken(wxToken);
            message.setContentType(Message.CONTENT_TYPE_TEXT);
            message.setContent(WX_MSG_FORMAT.formatted(room.getUname(),room.getTitle(),
                    eventData.getAreaNameParent(),eventData.getAreaNameChild(),LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒"))));
            message.setUid(wxuid);
            WxPusher.send(message);
        }
//        recordBiliPublishService.publishRecordHistory(history);
    }
}
