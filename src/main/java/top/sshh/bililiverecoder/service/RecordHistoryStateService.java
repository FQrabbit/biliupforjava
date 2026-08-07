package top.sshh.bililiverecoder.service;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.RecordEventData;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 收口录制生命周期的状态归属规则
 *
 * <p>room.historyId 是“当前会话”的指针，不是历史事件的归属依据。历史事件优先按文件分P、
 * 再按 sessionId 定位；只有会话仍是当前会话时，才允许回退到 room.historyId 并改写 room 状态</p>
 */
@Service
public class RecordHistoryStateService {

    private final RecordHistoryRepository historyRepository;
    private final RecordHistoryPartRepository partRepository;
    private final RecordRoomRepository roomRepository;

    public RecordHistoryStateService(RecordHistoryRepository historyRepository,
                                     RecordHistoryPartRepository partRepository,
                                     RecordRoomRepository roomRepository) {
        this.historyRepository = historyRepository;
        this.partRepository = partRepository;
        this.roomRepository = roomRepository;
    }

    @Transactional(readOnly = true)
    public RecordHistory resolveHistory(RecordRoom room, RecordEventData eventData, RecordHistoryPart part) {
        if (part != null && part.getHistoryId() != null) {
            RecordHistory history = historyRepository.findById(part.getHistoryId()).orElse(null);
            if (history != null) return history;
        }
        if (eventData != null && StringUtils.isNotBlank(eventData.getRoomId())
                && StringUtils.isNotBlank(eventData.getSessionId())) {
            for (RecordHistoryPart candidate : partRepository.findByRoomIdAndSessionIdOrderByIdDesc(
                    eventData.getRoomId(), eventData.getSessionId())) {
                if (candidate.getHistoryId() != null) {
                    RecordHistory history = historyRepository.findById(candidate.getHistoryId()).orElse(null);
                    if (history != null) return history;
                }
            }
            RecordHistory history = historyRepository.findBySessionId(eventData.getSessionId());
            if (history != null && Objects.equals(history.getRoomId(), eventData.getRoomId())) return history;
        }
        if (room != null && room.getHistoryId() != null && isCurrentSession(room, eventData)) {
            return historyRepository.findById(room.getHistoryId()).orElse(null);
        }
        return null;
    }

    public boolean isCurrentSession(RecordRoom room, RecordEventData eventData) {
        return room != null && (eventData == null || StringUtils.isBlank(eventData.getSessionId())
                || StringUtils.isBlank(room.getSessionId())
                || Objects.equals(room.getSessionId(), eventData.getSessionId()));
    }

    @Transactional
    public void markSessionEnded(RecordHistory history, RecordEventData eventData) {
        if (history == null || history.isForceArchived()) return;
        LocalDateTime now = LocalDateTime.now();
        if (StringUtils.isNotBlank(eventData.getSessionId())) history.setSessionId(eventData.getSessionId());
        history.setRecording(false);
        history.setStreaming(false);
        history.setEndTime(now);
        history.setUpdateTime(now);
        historyRepository.save(history);
    }

    @Transactional
    public void markStreamEnded(RecordHistory history) {
        if (history == null || history.isForceArchived()) return;
        LocalDateTime now = LocalDateTime.now();
        history.setStreaming(false);
        history.setEndTime(now);
        history.setUpdateTime(now);
        historyRepository.save(history);
    }

    @Transactional
    public void markCurrentRoomStopped(RecordRoom room, RecordEventData eventData, RecordHistory history) {
        if (room == null || history == null || !Objects.equals(room.getHistoryId(), history.getId())
                || !isCurrentSession(room, eventData)) return;
        room.setRecording(false);
        room.setStreaming(false);
        room.setSessionId(null);
        room.setUpdateTime(LocalDateTime.now());
        roomRepository.save(room);
    }
}
