package top.sshh.bililiverecoder.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.entity.RecordEventDTO;
import top.sshh.bililiverecoder.entity.RecordEventData;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.service.RecordEventService;
import top.sshh.bililiverecoder.service.RecordPartRecordingStateService;
import top.sshh.bililiverecoder.service.RecordHistoryStateService;
import top.sshh.bililiverecoder.util.LogKvs;

import java.util.List;
import java.util.Objects;

@Slf4j
@Component
public class RecordEventRecordEndService implements RecordEventService {


    @Autowired
    private RecordRoomRepository roomRepository;

    @Autowired
    private RecordHistoryPartRepository partRepository;
    @Autowired
    private RecordPartRecordingStateService recordingStateService;

    @Autowired
    private RecordHistoryStateService historyStateService;

    @Override
    public void processing(RecordEventDTO event) {
        RecordEventData eventData = event.getEventData();
        log.info("[BLR] {}", LogKvs.event("RecordEnd.Received")
                .add("eventId", event.getEventId())
                .add("roomId", eventData.getRoomId())
                .add("title", eventData.getTitle())
                .add("sessionId", eventData.getSessionId()));
        RecordRoom room = roomRepository.findByRoomId(eventData.getRoomId());
        if (room == null) {
            log.info("[BLR] {}", LogKvs.event("RecordEnd.NoRoom")
                    .add("roomId", eventData.getRoomId())
                    .add("sessionId", eventData.getSessionId()));
            return;
        }
        RecordHistory history = historyStateService.resolveHistory(room, eventData, null);
        if (history != null) {
                if (!Objects.equals(history.getRoomId(), room.getRoomId()) || history.isForceArchived()) {
                    log.info("[BLR] {}", LogKvs.event("RecordEnd.SkipHistoryUpdate")
                            .add("roomId", eventData.getRoomId())
                            .add("historyId", history.getId())
                            .add("forceArchived", history.isForceArchived()));
                    return;
                }
                // 旧会话也必须被收尾；但只有它仍是 room 当前会话时才清空房间指针
                historyStateService.markSessionEnded(history, eventData);
                historyStateService.markCurrentRoomStopped(room, eventData, history);

                // 文件关闭事件丢了也要收尾，但得连续观察文件，不能只看一次旧的修改时间
                try {
                    int healed = 0;
                    List<RecordHistoryPart> parts = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());
                    for (RecordHistoryPart part : parts) {
                        if (!part.isRecording() && part.getEndTime() != null) {
                            continue;
                        }
                        if (recordingStateService.closeIfReady(part.getId(), "record-end")) {
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
                            .add("historyId", history.getId())
                            .add("err", e.getMessage())
                            .add("ex", e.getClass().getSimpleName()), e);
                }
        } else {
            // 当 historyId 为空时，说明录播姬发送了录制结束的 Webhook 但本地并没有开启录制或录制记录已丢失
            log.info("[BLR] {}", LogKvs.event("RecordEnd.NoRecording")
                    .add("roomId", eventData.getRoomId())
                    .add("msg", "收到录制结束事件但本地无活跃录制记录。请检查录播姬是否开启了自动录制。"));
        }
//        recordBiliPublishService.publishRecordHistory(history);
    }

}


