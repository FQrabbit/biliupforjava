package top.sshh.bililiverecoder.service;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.RecordEventData;
import top.sshh.bililiverecoder.entity.RecordEventDTO;
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
 * <p>room.historyId 指向当前稿件，直播归组由本地下播间隔决定
 * 历史事件优先按文件分P定位，再用事件时间和已登记的上游标识保护当前房间状态</p>
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
        if (part == null && room != null && room.getHistoryId() != null && isCurrentSession(room, eventData)) {
            RecordHistory current = historyRepository.findById(room.getHistoryId()).orElse(null);
            if (current != null) return current;
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
            RecordHistory history = historyRepository.findFirstByRoomIdAndSessionIdOrderByIdDesc(eventData.getRoomId(), eventData.getSessionId());
            if (history != null && Objects.equals(history.getRoomId(), eventData.getRoomId())) return history;
        }
        if (room != null && room.getHistoryId() != null && isCurrentSession(room, eventData)) {
            return historyRepository.findById(room.getHistoryId()).orElse(null);
        }
        return null;
    }

    public boolean isCurrentSession(RecordRoom room, RecordEventData eventData) {
        if (room == null) return false;
        if (eventData == null || StringUtils.isBlank(eventData.getSessionId()) || StringUtils.isBlank(room.getSessionId())
                || Objects.equals(room.getSessionId(), eventData.getSessionId())) return true;
        // 未见过的上游标识可能只是本场 Webhook 换了 ID，已登记的旧标识才用于挡住迟到事件
        return historyRepository.findFirstByRoomIdAndSessionIdOrderByIdDesc(room.getRoomId(), eventData.getSessionId()) == null
                && partRepository.findByRoomIdAndSessionIdOrderByIdDesc(room.getRoomId(), eventData.getSessionId()).isEmpty();
    }

    public boolean isCurrentEvent(RecordRoom room, RecordEventDTO event) {
        return !LiveSessionPolicy.isOlderEvent(room, timestamp(event)) && isCurrentSession(room, event.getEventData());
    }

    @Transactional(readOnly = true)
    public RecordHistory resolveEventHistory(RecordRoom room, RecordEventDTO event, RecordHistoryPart part) {
        if (part != null) return resolveHistory(room, event.getEventData(), part);
        if (isCurrentEvent(room, event)) return room.getHistoryId() == null ? null : historyRepository.findById(room.getHistoryId()).orElse(null);
        RecordHistory history = resolveHistory(null, event.getEventData(), null);
        boolean fileClosed = top.sshh.bililiverecoder.entity.RecordEventType.FileClosed.equals(event.getEventType())
                || top.sshh.bililiverecoder.entity.RecordEventType.VideoFileCompletedEvent.equals(event.getEventType());
        // 迟到的文件关闭仍要补齐原分P，文件处理入口不会用它来结束当前录制
        if (fileClosed) return history;
        // 同一稿件可以合并多次重连，上一段迟到的结束事件不能结束当前仍在录制的这份稿件
        return history != null && room != null && Objects.equals(history.getId(), room.getHistoryId()) ? null : history;
    }

    private java.util.Date timestamp(RecordEventDTO event) {
        return event.getEventTimestamp() == null ? event.getDate() : event.getEventTimestamp();
    }

    @Transactional
    public void markCurrentStreamEnded(RecordRoom room, RecordEventDTO event) {
        if (!isCurrentEvent(room, event)) return;
        room.setStreaming(false);
        LiveSessionPolicy.end(room, timestamp(event), LocalDateTime.now());
        room.setWebhookLastSeenAt(LocalDateTime.now());
        roomRepository.saveLiveState(room);
    }

    @Transactional
    public void markSessionEnded(RecordHistory history, RecordEventData eventData, RecordRoom room) {
        if (history == null || history.isProcessingArchived() || history.isSplitClosed()) return;
        LocalDateTime now = LocalDateTime.now();
        if (StringUtils.isNotBlank(eventData.getSessionId())) history.setSessionId(eventData.getSessionId());
        history.setRecording(false);
        history.setStreaming(room != null && Objects.equals(room.getHistoryId(), history.getId()) && room.isStreaming());
        history.setEndTime(now);
        history.setCloseSource("SESSION_ENDED");
        history.setCloseAt(now);
        history.setUpdateTime(now);
        historyRepository.save(history);
    }

    @Transactional
    public void markStreamEnded(RecordHistory history) {
        if (history == null || history.isProcessingArchived() || history.isSplitClosed()) return;
        LocalDateTime now = LocalDateTime.now();
        history.setStreaming(false);
        history.setEndTime(now);
        history.setUpdateTime(now);
        historyRepository.save(history);
    }

    @Transactional
    public void markCurrentRoomStopped(RecordRoom room, RecordEventData eventData, RecordHistory history) {
        RecordEventDTO event = new RecordEventDTO(); event.setEventData(eventData);
        markCurrentRoomStopped(room, event, history);
    }

    @Transactional
    public void markCurrentRoomStopped(RecordRoom room, RecordEventDTO event, RecordHistory history) {
        if (room == null || history != null && !Objects.equals(room.getHistoryId(), history.getId())
                || !isCurrentEvent(room, event)) return;
        room.setRecording(false);
        // 录制重启时直播可能还在线，已经下播的房间也不能被旧 payload 重新打开
        room.setStreaming(room.isStreaming() && event.getEventData().isStreaming());
        // 保留上游标识供迟到事件定位，是否同场由本地下播时间决定
        if (!room.isStreaming()) LiveSessionPolicy.end(room, timestamp(event), LocalDateTime.now());
        room.setUpdateTime(LocalDateTime.now());
        room.setWebhookLastSeenAt(room.getUpdateTime());
        roomRepository.saveLiveState(room);
    }
}
