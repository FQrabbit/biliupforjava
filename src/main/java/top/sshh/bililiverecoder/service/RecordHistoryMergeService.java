package top.sshh.bililiverecoder.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.util.LogKvs;

import java.time.LocalDateTime;
import java.util.Date;

@Slf4j
@Service
public class RecordHistoryMergeService {
    @Autowired
    private RecordHistoryRepository historyRepository;
    @Autowired
    private SystemConfigService systemConfigService;

    public int getMergeIntervalMinutes(String roomId, String logPrefix) {
        return PublishReadinessService.mergeIntervalMinutes(systemConfigService.getAllConfigsMap());
    }

    public boolean beginLive(RecordRoom room, Date timestamp, LocalDateTime now) {
        RecordHistory current = room.getHistoryId() == null ? null : historyRepository.findById(room.getHistoryId()).orElse(null);
        if (current != null && current.getSplitGroup() == null && !current.isProcessingArchived() && !current.isDeletePending()) {
            current.setSplitGroup(java.util.UUID.randomUUID().toString());
            current.setSplitSequence(1);
            historyRepository.save(current);
        }
        boolean accepted = LiveSessionPolicy.begin(room, current, timestamp, now, getMergeIntervalMinutes(room.getRoomId(), "RecordStarted"));
        if (accepted && current != null && !canReuse(room, current)) room.setHistoryId(-1L);
        return accepted;
    }

    public RecordHistory findReusableHistory(RecordRoom room, String sessionId, String logPrefix) {
        RecordHistory history = room.getHistoryId() == null ? null : historyRepository.findById(room.getHistoryId()).orElse(null);
        if (history == null && room.getLiveGroup() != null) {
            // 重启恢复时指针可能还没保存，只找本地归组内的最新稿件
            history = historyRepository.findFirstByRoomIdAndSplitGroupOrderByIdDesc(room.getRoomId(), room.getLiveGroup());
        }
        if (!canReuse(room, history)) return null;
        log.info("[BLR] {}", LogKvs.event(logPrefix + ".ReuseRecentHistory")
                .add("roomId", room.getRoomId()).add("sessionId", sessionId).add("historyId", history.getId())
                .add("mergeIntervalMinutes", getMergeIntervalMinutes(room.getRoomId(), logPrefix)));
        return history;
    }

    private boolean canReuse(RecordRoom room, RecordHistory history) {
        return LiveSessionPolicy.owns(room, history) && !HistoryProcessingPolicy.blocksAutomatic(history)
                && (!history.isPublish() || history.isSplitClosed());
    }
}
