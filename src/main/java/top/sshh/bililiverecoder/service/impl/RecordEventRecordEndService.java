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
import top.sshh.bililiverecoder.service.SystemConfigService;
import top.sshh.bililiverecoder.util.LogKvs;
import top.sshh.bililiverecoder.util.PushNotifyClient;

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
            若%d分钟(可配置)内未收到录制开始事件，
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

    @Autowired
    private SystemConfigService systemConfigService;


    @Override
    public void processing(RecordEventDTO event) {
        RecordEventData eventData = event.getEventData();
        log.info("[BLR] {}", LogKvs.event("RecordEnd.Received")
                .add("eventId", event.getEventId())
                .add("roomId", eventData.getRoomId())
                .add("title", eventData.getTitle())
                .add("sessionId", eventData.getSessionId()));
        RecordRoom room = roomRepository.findByRoomId(eventData.getRoomId());
        if (room.getHistoryId() != null) {
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
                        log.info("[BLR] {}", LogKvs.event("RecordEnd.PartHeal.Done")
                                .add("roomId", eventData.getRoomId())
                                .add("historyId", history.getId())
                                .add("healed", healed));
                    }
                } catch (Exception e) {
                    log.warn("[BLR] {}", LogKvs.event("RecordEnd.PartHeal.Failed")
                            .add("roomId", eventData.getRoomId())
                            .add("historyId", historyOptional.map(RecordHistory::getId).orElse(null))
                            .add("err", e.getMessage())
                            .add("ex", e.getClass().getSimpleName()), e);
                }
            }
        } else {
            // 当 historyId 为空时，说明录播姬发送了录制结束的 Webhook 但本地并没有开启录制或录制记录已丢失
            log.info("[BLR] {}", LogKvs.event("RecordEnd.NoRecording")
                    .add("roomId", eventData.getRoomId())
                    .add("msg", "收到录制结束事件但本地无活跃录制记录。请检查录播姬是否开启了自动录制。"));
        }
        String wxuid = room.getWxuid();
        String pushMsgTags = room.getPushMsgTags();
        if (PushNotifyClient.canSend(room, wxuid, pushMsgTags, "录制结束")) {
            int mergeIntervalMinutes = getMergeIntervalMinutes(eventData.getRoomId());
            Message message = new Message();
            message.setAppToken(wxToken);
            message.setContentType(Message.CONTENT_TYPE_TEXT);
            message.setContent(WX_MSG_FORMAT.formatted(room.getUname(),room.getTitle(),
                    eventData.getAreaNameParent(),eventData.getAreaNameChild(),LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")),
                    mergeIntervalMinutes));
            message.setUid(wxuid);
            PushNotifyClient.sendParallel(room, message);
        }
//        recordBiliPublishService.publishRecordHistory(history);
    }

    private int getMergeIntervalMinutes(String roomId) {
        int mergeIntervalMinutes = 20;
        try {
            String mergeIntervalConfig = systemConfigService.getAllConfigsMap().get(SystemConfigService.KEY_MERGE_INTERVAL_MINUTES);
            if (mergeIntervalConfig != null && !mergeIntervalConfig.isEmpty()) {
                mergeIntervalMinutes = Integer.parseInt(mergeIntervalConfig);
                if (mergeIntervalMinutes < 1) {
                    mergeIntervalMinutes = 1;
                } else if (mergeIntervalMinutes > 1440) {
                    mergeIntervalMinutes = 1440;
                }
            }
        } catch (Exception e) {
            log.warn("[BLR] {}", LogKvs.event("RecordEnd.ParseMergeIntervalFailed")
                    .add("roomId", roomId)
                    .add("error", e.getMessage()));
        }
        return mergeIntervalMinutes;
    }
}


