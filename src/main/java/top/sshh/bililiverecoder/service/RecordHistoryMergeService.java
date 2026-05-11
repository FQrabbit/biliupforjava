package top.sshh.bililiverecoder.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.util.LogKvs;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class RecordHistoryMergeService {

    private static final int DEFAULT_MERGE_INTERVAL_MINUTES = 20;
    private static final int MAX_MERGE_INTERVAL_MINUTES = 1440;
    private static final int ACTIVE_HISTORY_STALE_HOURS = 24;

    @Autowired
    private RecordHistoryRepository historyRepository;

    @Autowired
    private RecordHistoryPartRepository historyPartRepository;

    @Autowired
    private SystemConfigService systemConfigService;

    public int getMergeIntervalMinutes(String roomId, String logPrefix) {
        int mergeIntervalMinutes = DEFAULT_MERGE_INTERVAL_MINUTES;
        try {
            String mergeIntervalConfig = systemConfigService.getAllConfigsMap()
                    .get(SystemConfigService.KEY_MERGE_INTERVAL_MINUTES);
            if (mergeIntervalConfig != null && !mergeIntervalConfig.isEmpty()) {
                mergeIntervalMinutes = Integer.parseInt(mergeIntervalConfig);
                if (mergeIntervalMinutes < 1) {
                    mergeIntervalMinutes = 1;
                } else if (mergeIntervalMinutes > MAX_MERGE_INTERVAL_MINUTES) {
                    mergeIntervalMinutes = MAX_MERGE_INTERVAL_MINUTES;
                }
            }
        } catch (Exception e) {
            log.warn("[BLR] {}", LogKvs.event(logPrefix + ".ParseMergeIntervalFailed")
                    .add("roomId", roomId)
                    .add("error", e.getMessage()));
        }
        return mergeIntervalMinutes;
    }

    public RecordHistory findReusableHistory(String roomId, LocalDateTime now, String sessionId, String logPrefix) {
        RecordHistory history = findFreshActiveHistory(roomId, now, sessionId, logPrefix);
        if (history != null) {
            return history;
        }

        history = findHistoryWithOpenPart(roomId, now, sessionId, logPrefix);
        if (history != null) {
            return history;
        }

        return findRecentEndedHistory(roomId, now, sessionId, logPrefix);
    }

    private RecordHistory findFreshActiveHistory(String roomId, LocalDateTime now, String sessionId, String logPrefix) {
        List<RecordHistory> activeHistoryList = historyRepository.findByRoomIdAndRecordingTrueOrderByStartTimeDesc(roomId);
        if (CollectionUtils.isEmpty(activeHistoryList)) {
            return null;
        }

        for (RecordHistory activeHistory : activeHistoryList) {
            if (activeHistory.isPublish()) {
                log.info("[BLR] {}", LogKvs.event(logPrefix + ".SkipPublished")
                        .add("roomId", roomId)
                        .add("sessionId", sessionId)
                        .add("historyId", activeHistory.getId()));
                continue;
            }
            if (activeHistory.isForceArchived()) {
                log.info("[BLR] {}", LogKvs.event(logPrefix + ".SkipForceArchived")
                        .add("roomId", roomId)
                        .add("sessionId", sessionId)
                        .add("historyId", activeHistory.getId()));
                continue;
            }
            if (isFreshActiveHistory(activeHistory, now)) {
                log.info("[BLR] {}", LogKvs.event(logPrefix + ".ReuseActiveHistory")
                        .add("roomId", roomId)
                        .add("sessionId", sessionId)
                        .add("historyId", activeHistory.getId()));
                return activeHistory;
            }
            closeStaleHistory(activeHistory, now, logPrefix, "Recording marked true but no activity for 24h");
        }
        return null;
    }

    private RecordHistory findHistoryWithOpenPart(String roomId, LocalDateTime now, String sessionId, String logPrefix) {
        List<RecordHistory> historyList = historyRepository.findUnpublishedHistoriesWithRecordingPartsByRoomIdOrderByStartTimeDesc(roomId);
        if (CollectionUtils.isEmpty(historyList)) {
            return null;
        }

        for (RecordHistory history : historyList) {
            if (isFreshActiveHistory(history, now)) {
                log.info("[BLR] {}", LogKvs.event(logPrefix + ".ReuseOpenPartHistory")
                        .add("roomId", roomId)
                        .add("sessionId", sessionId)
                        .add("historyId", history.getId()));
                return history;
            }
            closeStaleHistory(history, now, logPrefix, "Open part history has no activity for 24h");
        }
        return null;
    }

    private RecordHistory findRecentEndedHistory(String roomId, LocalDateTime now, String sessionId, String logPrefix) {
        int mergeIntervalMinutes = getMergeIntervalMinutes(roomId, logPrefix);
        List<RecordHistory> historyList = historyRepository.findByRoomIdAndEndTimeBetweenOrderByEndTimeAsc(
                roomId, now.minusMinutes((long) mergeIntervalMinutes), now.plusMinutes(5L));
        if (CollectionUtils.isEmpty(historyList)) {
            return null;
        }

        RecordHistory history = historyList.stream()
                .filter(h -> !h.isPublish())
                .filter(h -> !h.isForceArchived())
                .reduce((previous, current) -> current)
                .orElse(null);
        if (history != null) {
            log.info("[BLR] {}", LogKvs.event(logPrefix + ".ReuseRecentHistory")
                    .add("roomId", roomId)
                    .add("sessionId", sessionId)
                    .add("historyId", history.getId())
                    .add("mergeIntervalMinutes", mergeIntervalMinutes));
        }
        return history;
    }

    private boolean isFreshActiveHistory(RecordHistory history, LocalDateTime now) {
        LocalDateTime lastActivity = lastActivityTime(history);
        return lastActivity != null && lastActivity.isAfter(now.minusHours(ACTIVE_HISTORY_STALE_HOURS));
    }

    private LocalDateTime lastActivityTime(RecordHistory history) {
        if (history.getEndTime() != null) {
            return history.getEndTime();
        }
        if (history.getUpdateTime() != null) {
            return history.getUpdateTime();
        }
        return history.getStartTime();
    }

    private void closeStaleHistory(RecordHistory history, LocalDateTime now, String logPrefix, String reason) {
        log.info("[BLR] {}", LogKvs.event(logPrefix + ".ActiveHistoryStale")
                .add("roomId", history.getRoomId())
                .add("historyId", history.getId())
                .add("lastActivity", lastActivityTime(history))
                .add("reason", reason));

        history.setRecording(false);
        history.setStreaming(false);
        if (history.getEndTime() == null) {
            history.setEndTime(now);
        }
        historyRepository.save(history);

        historyPartRepository.findByHistoryId(history.getId()).stream()
                .filter(p -> p.isRecording() || p.getEndTime() == null)
                .forEach(p -> closeStalePart(p, now));
    }

    private void closeStalePart(RecordHistoryPart part, LocalDateTime now) {
        part.setRecording(false);
        if (part.getEndTime() == null) {
            part.setEndTime(now);
        }
        historyPartRepository.save(part);
    }
}
