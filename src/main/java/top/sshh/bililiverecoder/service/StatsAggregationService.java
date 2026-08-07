package top.sshh.bililiverecoder.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.repo.*;
import top.sshh.bililiverecoder.util.LogKvs;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Slf4j
@Service
public class StatsAggregationService {

    public static final int STATS_VERSION = 3;
    private static final Duration TASK_STATUS_RETENTION = Duration.ofMinutes(10);

    @Autowired
    private RecordHistoryRepository historyRepository;
    @Autowired
    private RecordHistoryPartRepository partRepository;
    @Autowired
    private LiveMsgRepository liveMsgRepository;
    @Autowired
    private RoomLiveEventRepository eventRepository;
    @Autowired
    private RoomLiveDanmuUserStatsRepository danmuUserStatsRepository;
    @Autowired
    private RoomLiveEventParseStateRepository eventParseStateRepository;
    @Autowired
    private RoomLiveGiftCatalogRepository giftCatalogRepository;
    @Autowired
    private RoomLiveGiftCatalogService giftCatalogService;
    @Autowired
    private RecordRoomRepository roomRepository;
    @Autowired
    private RoomLiveSessionStatsRepository sessionStatsRepository;
    @Autowired
    private RoomLiveDailyStatsRepository dailyStatsRepository;
    @Autowired
    private RoomLiveMsgBucketStatsRepository bucketStatsRepository;
    @Autowired
    private DatabaseMaintenanceState databaseMaintenanceState;
    @Lazy
    @Autowired
    private RoomLiveEventParseService roomLiveEventParseService;
    @Autowired
    private RoomLiveEventXmlIssueService xmlIssueService;
    @Autowired
    @Qualifier("myAsyncPool")
    private TaskExecutor taskExecutor;

    private final ReentrantLock statsWriteLock = new ReentrantLock();
    private final Object taskStatusLock = new Object();
    private volatile StatsTaskStatus taskStatus = StatsTaskStatus.idle();

    @Async("myAsyncPool")
    public void refreshRecentCompletedHistoriesAsync(int limit) {
        try {
            refreshRecentCompletedHistories(limit);
        } catch (Throwable e) {
            log.warn("[BLR] {}", LogKvs.event("Stats.RefreshRecent.Failed")
                    .add("limit", limit)
                    .add("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()));
        }
    }

    public Map<String, Object> refreshRecentCompletedHistories(int limit) {
        if (databaseMaintenanceState.isMaintenanceActive()) {
            return statsBusyResult("recent", limit);
        }
        if (!statsWriteLock.tryLock()) {
            return statsBusyResult("recent", limit);
        }
        try {
            int safeLimit = Math.max(1, limit);
            List<RecordHistory> histories = historyRepository.findCompletedOrderByEndTimeDesc(PageRequest.of(0, safeLimit));
            int updated = 0;
            EventParseSummary parseSummary = new EventParseSummary();
            for (RecordHistory history : histories) {
                if (history == null || history.getId() == null) {
                    continue;
                }
                if (aggregateHistory(history, parseSummary)) {
                    updated++;
                }
            }
            if (parseSummary.issueSkipped > 0) {
                log.debug("[BLR] {}", LogKvs.event("RoomLiveEvent.Parse.IssueSummary")
                        .add("historyLimit", safeLimit)
                        .add("historyCount", histories.size())
                        .add("partCount", parseSummary.checked)
                        .add("skipped", parseSummary.issueSkipped)
                        .add("missing", parseSummary.count(RoomLiveEventXmlIssue.IssueType.MISSING_UNEXPECTED))
                        .add("invalid", parseSummary.count(RoomLiveEventXmlIssue.IssueType.INVALID_XML))
                        .add("readFailed", parseSummary.count(RoomLiveEventXmlIssue.IssueType.READ_FAILED))
                        .add("offline", parseSummary.count(RoomLiveEventXmlIssue.IssueType.ROOT_OFFLINE))
                        .add("unresolved", parseSummary.count(RoomLiveEventXmlIssue.IssueType.PATH_UNRESOLVED))
                        .add("internal", parseSummary.count(RoomLiveEventXmlIssue.IssueType.INTERNAL_ERROR)));
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("updated", updated);
            result.put("statsVersion", STATS_VERSION);
            result.put("status", getStatsStatus());
            return result;
        } finally {
            statsWriteLock.unlock();
        }
    }

    public Map<String, Object> backfillMissingStats() {
        if (databaseMaintenanceState.isMaintenanceActive()) {
            return statsBusyResult("backfill", null);
        }
        if (!statsWriteLock.tryLock()) {
            return statsBusyResult("backfill", null);
        }
        try {
            int updated = 0;
            for (RecordHistory history : historyRepository.findByEndTimeIsNotNullOrderByEndTimeDesc()) {
                if (history == null || history.getId() == null) {
                    continue;
                }
                RoomLiveSessionStats stats = sessionStatsRepository.findByHistoryId(history.getId());
                if (stats == null || stats.getStatsVersion() < STATS_VERSION) {
                    if (aggregateHistory(history)) {
                        updated++;
                    }
                }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("updated", updated);
            result.put("statsVersion", STATS_VERSION);
            result.put("status", getStatsStatus());
            return result;
        } finally {
            statsWriteLock.unlock();
        }
    }

    public Map<String, Object> startBackfillMissingStats() {
        return startStatsTask("backfill", "补全未统计", this::runBackfillMissingStatsTask);
    }

    @Async("myAsyncPool")
    public void refreshHistoryStatsAsync(Long historyId) {
        if (databaseMaintenanceState.isMaintenanceActive()) {
            log.info("[BLR] {}", LogKvs.event("Stats.RefreshHistory.SkipMaintenance")
                    .add("historyId", historyId));
            return;
        }
        if (!statsWriteLock.tryLock()) {
            log.info("[BLR] {}", LogKvs.event("Stats.RefreshHistory.SkipBusy")
                    .add("historyId", historyId));
            return;
        }
        try {
            refreshHistoryStatsUnlocked(historyId);
        } catch (Throwable e) {
            log.warn("[BLR] {}", LogKvs.event("Stats.RefreshHistory.Failed")
                    .add("historyId", historyId)
                    .add("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()));
        } finally {
            statsWriteLock.unlock();
        }
    }

    public Map<String, Object> rebuildAllStats() {
        if (databaseMaintenanceState.isMaintenanceActive()) {
            return statsBusyResult("rebuild", null);
        }
        if (!statsWriteLock.tryLock()) {
            return statsBusyResult("rebuild", null);
        }
        try {
            StatsCleanupResult cleanup = cleanupStatsRows();

            int updated = 0;
            for (RecordHistory history : historyRepository.findByEndTimeIsNotNullOrderByEndTimeDesc()) {
                if (history == null || history.getId() == null) {
                    continue;
                }
                if (aggregateHistory(history)) {
                    updated++;
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("updated", updated);
            result.putAll(cleanup.toMap());
            result.put("statsVersion", STATS_VERSION);
            result.put("rebuiltAt", LocalDateTime.now());
            result.put("status", getStatsStatus());
            return result;
        } finally {
            statsWriteLock.unlock();
        }
    }

    public Map<String, Object> startRebuildAllStats() {
        return startStatsTask("rebuild", "重建统计", this::runRebuildAllStatsTask);
    }

    public Map<String, Object> startXmlIssueRecheck(List<Long> partIds) {
        List<Long> safeIds = normalizePartIds(partIds);
        if (safeIds.isEmpty()) {
            throw new IllegalArgumentException("请选择需要重新检查的 XML 记录");
        }
        if (safeIds.size() > 100) {
            throw new IllegalArgumentException("一次最多重新检查 100 个 XML 记录");
        }
        return startXmlIssueRecheckTask(safeIds, "重新检查 XML");
    }

    public void startXmlIssueRecheckByRoot(Long storageRootId) {
        List<Long> partIds = xmlIssueService.activePartIdsByRoot(storageRootId);
        if (!partIds.isEmpty()) {
            startXmlIssueRecheckTask(partIds, "存储恢复后重新检查 XML");
        }
    }

    public Map<String, Object> cleanupStats() {
        if (databaseMaintenanceState.isMaintenanceActive()) {
            return statsBusyResult("cleanup", null);
        }
        if (!statsWriteLock.tryLock()) {
            return statsBusyResult("cleanup", null);
        }
        try {
            StatsCleanupResult cleanup = cleanupStatsRows();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.putAll(cleanup.toMap());
            result.put("statsVersion", STATS_VERSION);
            result.put("cleanedAt", LocalDateTime.now());
            result.put("status", getStatsStatus());
            return result;
        } finally {
            statsWriteLock.unlock();
        }
    }

    public Map<String, Object> startCleanupStats() {
        return startStatsTask("cleanup", "清理缓存", this::runCleanupStatsTask);
    }

    public Map<String, Object> getStatsTaskStatus() {
        synchronized (taskStatusLock) {
            if (!taskStatus.running
                    && !"idle".equals(taskStatus.task)
                    && taskStatus.updatedAt != null
                    && Duration.between(taskStatus.updatedAt, LocalDateTime.now()).compareTo(TASK_STATUS_RETENTION) > 0) {
                taskStatus = StatsTaskStatus.idle();
            }
        }
        return taskStatus.toMap();
    }

    private Map<String, Object> startStatsTask(String task, String title, Runnable runnable) {
        if (databaseMaintenanceState.isMaintenanceActive()) {
            return statsBusyResult(task, null);
        }
        synchronized (taskStatusLock) {
            if (taskStatus.running) {
                Map<String, Object> result = taskStatus.toMap();
                result.put("success", false);
                result.put("busy", true);
                result.put("message", taskStatus.title + "正在执行中，请稍后再试");
                return result;
            }
            taskStatus = StatsTaskStatus.running(task, title);
        }
        try {
            taskExecutor.execute(runnable);
        } catch (RuntimeException e) {
            updateTaskFailed("任务启动失败：" + e.getMessage());
            throw e;
        }
        return taskStatus.toMap();
    }

    private Map<String, Object> startXmlIssueRecheckTask(List<Long> partIds, String title) {
        return startStatsTask("xmlRecheck", title, () -> runXmlIssueRecheckTask(partIds));
    }

    private void runXmlIssueRecheckTask(List<Long> partIds) {
        if (!statsWriteLock.tryLock()) {
            updateTaskBusy("已有统计任务正在执行");
            return;
        }
        try {
            List<Long> safeIds = normalizePartIds(partIds);
            Map<RoomLiveEventXmlIssue.IssueType, Integer> unresolved = new EnumMap<>(RoomLiveEventXmlIssue.IssueType.class);
            Set<Long> historyIds = new LinkedHashSet<>();
            int resolved = 0;
            int processed = 0;
            for (Long partId : safeIds) {
                RecordHistoryPart part = partRepository.findById(partId).orElse(null);
                if (part == null) {
                    processed++;
                    continue;
                }
                updateTaskProgress("正在检查 XML", processed, safeIds.size(), part.getRoomId() + " / " + partId);
                RoomLiveEventParseService.ParseResult parseResult = roomLiveEventParseService.parsePart(part, true);
                if (parseResult.parsed()) {
                    resolved++;
                } else if (parseResult.issueType() != null) {
                    unresolved.merge(parseResult.issueType(), 1, Integer::sum);
                }
                if (part.getHistoryId() != null) {
                    historyIds.add(part.getHistoryId());
                }
                processed++;
                updateTaskProgress("正在检查 XML", processed, safeIds.size(), part.getRoomId() + " / " + partId);
            }
            int refreshed = 0;
            for (Long historyId : historyIds) {
                RecordHistory history = historyRepository.findById(historyId).orElse(null);
                if (history != null && aggregateHistory(history)) {
                    refreshed++;
                }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("checked", processed);
            result.put("resolved", resolved);
            result.put("refreshedHistories", refreshed);
            result.put("missing", unresolved.getOrDefault(RoomLiveEventXmlIssue.IssueType.MISSING_UNEXPECTED, 0));
            result.put("invalid", unresolved.getOrDefault(RoomLiveEventXmlIssue.IssueType.INVALID_XML, 0));
            result.put("readFailed", unresolved.getOrDefault(RoomLiveEventXmlIssue.IssueType.READ_FAILED, 0));
            result.put("offline", unresolved.getOrDefault(RoomLiveEventXmlIssue.IssueType.ROOT_OFFLINE, 0));
            result.put("unresolved", unresolved.getOrDefault(RoomLiveEventXmlIssue.IssueType.PATH_UNRESOLVED, 0));
            result.put("internal", unresolved.getOrDefault(RoomLiveEventXmlIssue.IssueType.INTERNAL_ERROR, 0));
            updateTaskDone("XML 重新检查完成", result);
        } catch (Throwable e) {
            updateTaskFailed("XML 重新检查失败：" + e.getMessage());
            log.warn("[BLR] {}", LogKvs.event("RoomLiveEvent.Parse.RecheckFailed")
                    .add("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()), e);
        } finally {
            statsWriteLock.unlock();
        }
    }

    private void runBackfillMissingStatsTask() {
        if (!statsWriteLock.tryLock()) {
            updateTaskBusy("已有统计任务正在执行");
            return;
        }
        try {
            List<RecordHistory> histories = historyRepository.findByEndTimeIsNotNullOrderByEndTimeDesc();
            List<RecordHistory> targets = histories.stream()
                    .filter(history -> history != null && history.getId() != null)
                    .filter(history -> {
                        RoomLiveSessionStats stats = sessionStatsRepository.findByHistoryId(history.getId());
                        return stats == null || stats.getStatsVersion() < STATS_VERSION;
                    })
                    .collect(Collectors.toList());
            updateTaskProgress("扫描完成", 0, targets.size(), "需要补全 " + targets.size() + " 场");
            int updated = 0;
            for (RecordHistory history : targets) {
                updateTaskProgress("正在补全", updated, targets.size(), history.getRoomId() + " / " + history.getId());
                if (aggregateHistory(history)) {
                    updated++;
                }
                updateTaskProgress("正在补全", updated, targets.size(), history.getRoomId() + " / " + history.getId());
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("updated", updated);
            result.put("statsVersion", STATS_VERSION);
            result.put("status", getStatsStatus());
            updateTaskDone("补全完成", result);
        } catch (Throwable e) {
            updateTaskFailed("补全失败：" + e.getMessage());
            log.warn("[BLR] {}", LogKvs.event("Stats.Backfill.Failed")
                    .add("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()), e);
        } finally {
            statsWriteLock.unlock();
        }
    }

    private void runRebuildAllStatsTask() {
        if (!statsWriteLock.tryLock()) {
            updateTaskBusy("已有统计任务正在执行");
            return;
        }
        try {
            updateTaskProgress("正在清理旧缓存", 0, 1, "清理统计缓存表");
            StatsCleanupResult cleanup = cleanupStatsRowsPreservingXmlIssueCaches();
            int deletedParseStates = eventParseStateRepository.deleteAllRowsWithoutXmlIssue();
            List<RecordHistory> histories = historyRepository.findByEndTimeIsNotNullOrderByEndTimeDesc().stream()
                    .filter(history -> history != null && history.getId() != null)
                    .collect(Collectors.toList());
            int updated = 0;
            for (RecordHistory history : histories) {
                updateTaskProgress("正在重建", updated, histories.size(), history.getRoomId() + " / " + history.getId());
                if (aggregateHistory(history)) {
                    updated++;
                }
                updateTaskProgress("正在重建", updated, histories.size(), history.getRoomId() + " / " + history.getId());
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("updated", updated);
            result.putAll(cleanup.toMap());
            result.put("deletedParseStates", deletedParseStates);
            result.put("statsVersion", STATS_VERSION);
            result.put("rebuiltAt", LocalDateTime.now());
            result.put("status", getStatsStatus());
            updateTaskDone("重建完成", result);
        } catch (Throwable e) {
            updateTaskFailed("重建失败：" + e.getMessage());
            log.warn("[BLR] {}", LogKvs.event("Stats.Rebuild.Failed")
                    .add("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()), e);
        } finally {
            statsWriteLock.unlock();
        }
    }

    private void runCleanupStatsTask() {
        if (!statsWriteLock.tryLock()) {
            updateTaskBusy("已有统计任务正在执行");
            return;
        }
        try {
            updateTaskProgress("正在清理统计缓存", 0, 2, "清理统计汇总表");
            StatsCleanupResult cleanup = cleanupStatsRows();
            updateTaskProgress("正在清理原始 JSON", 1, 2, "清理历史遗留 rawJson 字段");
            int rawJsonRows = eventRepository.clearRawJson();
            updateTaskProgress("清理完成", 2, 2, "统计缓存 " + cleanup.deletedTotal() + " 条，rawJson " + rawJsonRows + " 行");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.putAll(cleanup.toMap());
            result.put("rawJsonRows", rawJsonRows);
            result.put("statsVersion", STATS_VERSION);
            result.put("cleanedAt", LocalDateTime.now());
            result.put("status", getStatsStatus());
            updateTaskDone("清理完成", result);
        } catch (Throwable e) {
            updateTaskFailed("清理失败：" + e.getMessage());
            log.warn("[BLR] {}", LogKvs.event("Stats.Cleanup.Failed")
                    .add("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()), e);
        } finally {
            statsWriteLock.unlock();
        }
    }

    private void updateTaskProgress(String phase, int processed, int total, String detail) {
        synchronized (taskStatusLock) {
            taskStatus = taskStatus.progress(phase, processed, total, detail);
        }
    }

    private void updateTaskDone(String message, Map<String, Object> result) {
        synchronized (taskStatusLock) {
            taskStatus = taskStatus.done(message, result);
        }
    }

    private void updateTaskFailed(String message) {
        synchronized (taskStatusLock) {
            taskStatus = taskStatus.failed(message);
        }
    }

    private void updateTaskBusy(String message) {
        synchronized (taskStatusLock) {
            taskStatus = taskStatus.busy(message);
        }
    }

    public Map<String, Object> cleanupEventRawJson() {
        if (databaseMaintenanceState.isMaintenanceActive()) {
            return statsBusyResult("cleanupEventRawJson", null);
        }
        if (!statsWriteLock.tryLock()) {
            return statsBusyResult("cleanupEventRawJson", null);
        }
        try {
            int updated = eventRepository.clearRawJson();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("updated", updated);
            result.put("message", "已清理事件原始JSON，统计字段已保留");
            result.put("cleanedAt", LocalDateTime.now());
            return result;
        } finally {
            statsWriteLock.unlock();
        }
    }

    public Map<String, Object> cleanupStaleRecordingStates() {
        if (databaseMaintenanceState.isMaintenanceActive()) {
            return statsBusyResult("cleanupStaleRecordingStates", null);
        }
        if (!statsWriteLock.tryLock()) {
            return statsBusyResult("cleanupStaleRecordingStates", null);
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime cutoff = now.minusHours(6);
            int updatedHistories = 0;
            int updatedParts = 0;
            List<Map<String, Object>> items = new ArrayList<>();
            for (RecordHistory history : historyRepository.findByEndTimeIsNotNullOrderByEndTimeDesc()) {
                if (!shouldCleanupStaleRecordingState(history, cutoff)) {
                    continue;
                }
                List<RecordHistoryPart> parts = partRepository.findByHistoryId(history.getId());
                boolean historyChanged = false;
                if (history.isRecording()) {
                    history.setRecording(false);
                    historyChanged = true;
                }
                if (history.isStreaming()) {
                    history.setStreaming(false);
                    historyChanged = true;
                }
                int partChanges = cleanupStaleRecordingParts(history, parts, now);
                if (historyChanged) {
                    history.setUpdateTime(now);
                    historyRepository.save(history);
                    updatedHistories++;
                }
                if (partChanges > 0 || historyChanged) {
                    updatedParts += partChanges;
                    if (items.size() < 50) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("historyId", history.getId());
                        item.put("roomId", history.getRoomId());
                        item.put("title", history.getTitle());
                        item.put("bvId", history.getBvId());
                        item.put("historyUpdated", historyChanged);
                        item.put("partUpdated", partChanges);
                        item.put("endTime", history.getEndTime());
                        items.add(item);
                    }
                }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("updatedHistories", updatedHistories);
            result.put("updatedParts", updatedParts);
            result.put("items", items);
            result.put("message", "已清理旧录制状态：稿件 " + updatedHistories + " 条，分P " + updatedParts + " 条");
            result.put("cleanedAt", now);
            return result;
        } finally {
            statsWriteLock.unlock();
        }
    }

    private boolean shouldCleanupStaleRecordingState(RecordHistory history, LocalDateTime cutoff) {
        if (history == null || history.getId() == null || history.getEndTime() == null) {
            return false;
        }
        if (history.getEndTime().isAfter(cutoff)) {
            return false;
        }
        if (!history.isRecording() && !history.isStreaming()
                && partRepository.countActuallyRecordingPartsByHistoryId(history.getId()) <= 0) {
            return false;
        }
        return history.isPublish() || StringUtils.isNotBlank(history.getBvId());
    }

    private int cleanupStaleRecordingParts(RecordHistory history, List<RecordHistoryPart> parts, LocalDateTime now) {
        if (parts == null || parts.isEmpty()) {
            return 0;
        }
        int updated = 0;
        for (RecordHistoryPart part : parts) {
            if (part == null || part.getId() == null) {
                continue;
            }
            boolean changed = false;
            if (part.isRecording()) {
                part.setRecording(false);
                changed = true;
            }
            if (part.getEndTime() == null) {
                part.setEndTime(resolvePartEndTime(history, part));
                changed = true;
            }
            if (changed) {
                part.setUpdateTime(now);
                partRepository.save(part);
                updated++;
            }
        }
        return updated;
    }

    private LocalDateTime resolvePartEndTime(RecordHistory history, RecordHistoryPart part) {
        if (part.getStartTime() != null && part.getDuration() > 0) {
            return part.getStartTime().plusSeconds(Math.max(1L, Math.round(part.getDuration())));
        }
        if (history.getEndTime() != null) {
            return history.getEndTime();
        }
        if (part.getStartTime() != null) {
            return part.getStartTime();
        }
        return LocalDateTime.now();
    }

    public void refreshHistoryStats(Long historyId) {
        statsWriteLock.lock();
        try {
            refreshHistoryStatsUnlocked(historyId);
        } finally {
            statsWriteLock.unlock();
        }
    }

    public <T> T withStatsWriteLock(Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        statsWriteLock.lock();
        try {
            return action.get();
        } finally {
            statsWriteLock.unlock();
        }
    }

    public RoomStatsDeletionResult deleteRoomStats(String roomId) {
        if (StringUtils.isBlank(roomId)) {
            return RoomStatsDeletionResult.empty(roomId);
        }
        statsWriteLock.lock();
        try {
            int deletedBuckets = bucketStatsRepository.deleteByRoomId(roomId);
            int deletedDailyStats = dailyStatsRepository.deleteByRoomId(roomId);
            int deletedSessionStats = sessionStatsRepository.deleteByRoomId(roomId);
            int deletedDanmuUserStats = danmuUserStatsRepository.deleteByRoomId(roomId);
            int deletedParseStates = eventParseStateRepository.deleteByRoomId(roomId);
            int deletedEvents = eventRepository.deleteByRoomId(roomId);
            long deletedXmlIssues = xmlIssueService.deleteByRoomId(roomId);
            int deletedGiftCatalog = giftCatalogRepository.deleteByRoomId(roomId);
            giftCatalogService.clearRoomState(roomId);
            RoomStatsDeletionResult result = new RoomStatsDeletionResult(
                    roomId,
                    deletedBuckets,
                    deletedDailyStats,
                    deletedSessionStats,
                    deletedDanmuUserStats,
                    deletedParseStates,
                    deletedEvents,
                    deletedXmlIssues,
                    deletedGiftCatalog);
            log.info("[BLR] {}", LogKvs.event("Stats.Room.Delete.Success")
                    .add("roomId", roomId)
                    .add("deletedBucketStats", deletedBuckets)
                    .add("deletedDailyStats", deletedDailyStats)
                    .add("deletedSessionStats", deletedSessionStats)
                    .add("deletedDanmuUserStats", deletedDanmuUserStats)
                    .add("deletedParseStates", deletedParseStates)
                    .add("deletedEvents", deletedEvents)
                    .add("deletedXmlIssues", deletedXmlIssues)
                    .add("deletedGiftCatalog", deletedGiftCatalog)
                    .add("deletedTotalStatistics", result.deletedTotal()));
            return result;
        } finally {
            statsWriteLock.unlock();
        }
    }

    private void refreshHistoryStatsUnlocked(Long historyId) {
        if (historyId == null) {
            return;
        }
        Optional<RecordHistory> historyOptional = historyRepository.findById(historyId);
        if (historyOptional.isEmpty()) {
            return;
        }
        aggregateHistory(historyOptional.get());
    }

    private Map<String, Object> statsBusyResult(String task, Object limit) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("busy", true);
        result.put("task", task);
        if (limit != null) {
            result.put("limit", limit);
        }
        result.put("message", "统计任务正在执行中，请稍后再试");
        result.put("statsVersion", STATS_VERSION);
        return result;
    }

    private StatsCleanupResult cleanupStatsRows() {
        int deletedBuckets = bucketStatsRepository.deleteAllRows();
        int deletedDailyStats = dailyStatsRepository.deleteAllRows();
        int deletedSessionStats = sessionStatsRepository.deleteAllRows();
        int deletedDanmuUserStats = danmuUserStatsRepository.deleteAllRows();
        return new StatsCleanupResult(deletedBuckets, deletedDailyStats, deletedSessionStats, deletedDanmuUserStats);
    }

    private StatsCleanupResult cleanupStatsRowsPreservingXmlIssueCaches() {
        int deletedBuckets = bucketStatsRepository.deleteAllRows();
        int deletedDailyStats = dailyStatsRepository.deleteAllRows();
        int deletedSessionStats = sessionStatsRepository.deleteAllRows();
        int deletedDanmuUserStats = danmuUserStatsRepository.deleteAllRowsWithoutXmlIssue();
        return new StatsCleanupResult(deletedBuckets, deletedDailyStats, deletedSessionStats, deletedDanmuUserStats);
    }

    private record StatsCleanupResult(int deletedBucketStats,
                                      int deletedDailyStats,
                                      int deletedSessionStats,
                                      int deletedDanmuUserStats) {
        int deletedTotal() {
            return deletedBucketStats + deletedDailyStats + deletedSessionStats + deletedDanmuUserStats;
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("deletedBucketStats", deletedBucketStats);
            map.put("deletedDailyStats", deletedDailyStats);
            map.put("deletedSessionStats", deletedSessionStats);
            map.put("deletedDanmuUserStats", deletedDanmuUserStats);
            map.put("deletedTotalStats", deletedTotal());
            return map;
        }
    }

    public record RoomStatsDeletionResult(String roomId,
                                          int deletedBucketStats,
                                          int deletedDailyStats,
                                          int deletedSessionStats,
                                          int deletedDanmuUserStats,
                                          int deletedParseStates,
                                          int deletedEvents,
                                          long deletedXmlIssues,
                                          int deletedGiftCatalog) {

        public static RoomStatsDeletionResult empty(String roomId) {
            return new RoomStatsDeletionResult(roomId, 0, 0, 0, 0, 0, 0, 0L, 0);
        }

        public long deletedTotal() {
            return (long) deletedBucketStats
                    + deletedDailyStats
                    + deletedSessionStats
                    + deletedDanmuUserStats
                    + deletedParseStates
                    + deletedEvents
                    + deletedXmlIssues
                    + deletedGiftCatalog;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("roomId", roomId);
            map.put("deletedBucketStats", deletedBucketStats);
            map.put("deletedDailyStats", deletedDailyStats);
            map.put("deletedSessionStats", deletedSessionStats);
            map.put("deletedDanmuUserStats", deletedDanmuUserStats);
            map.put("deletedParseStates", deletedParseStates);
            map.put("deletedEvents", deletedEvents);
            map.put("deletedXmlIssues", deletedXmlIssues);
            map.put("deletedGiftCatalog", deletedGiftCatalog);
            map.put("deletedTotalStatistics", deletedTotal());
            return map;
        }
    }

    private record StatsTaskStatus(String taskId,
                                   String task,
                                   String title,
                                   boolean running,
                                   boolean success,
                                   boolean busy,
                                   String phase,
                                   String message,
                                   String detail,
                                   int processed,
                                   int total,
                                   int percent,
                                   LocalDateTime startedAt,
                                   LocalDateTime updatedAt,
                                   Map<String, Object> result) {

        private static StatsTaskStatus idle() {
            return new StatsTaskStatus(null, "idle", "空闲", false, true, false,
                    "IDLE", "当前没有统计维护任务", "", 0, 0, 0, null, LocalDateTime.now(), Collections.emptyMap());
        }

        private static StatsTaskStatus running(String task, String title) {
            LocalDateTime now = LocalDateTime.now();
            return new StatsTaskStatus(UUID.randomUUID().toString(), task, title, true, true, false,
                    "STARTING", "正在启动任务", "", 0, 0, 1, now, now, Collections.emptyMap());
        }

        private StatsTaskStatus progress(String phase, int processed, int total, String detail) {
            int safeTotal = Math.max(0, total);
            int safeProcessed = Math.max(0, Math.min(processed, safeTotal));
            int nextPercent = safeTotal <= 0 ? 5 : Math.max(1, Math.min(99, (int) Math.floor(safeProcessed * 100.0d / safeTotal)));
            return new StatsTaskStatus(taskId, task, title, true, true, false,
                    phase, phase, detail == null ? "" : detail, safeProcessed, safeTotal, nextPercent, startedAt, LocalDateTime.now(), result);
        }

        private StatsTaskStatus done(String message, Map<String, Object> result) {
            return new StatsTaskStatus(taskId, task, title, false, true, false,
                    "DONE", message, detail, total, total, 100, startedAt, LocalDateTime.now(),
                    result == null ? Collections.emptyMap() : result);
        }

        private StatsTaskStatus failed(String message) {
            return new StatsTaskStatus(taskId, task, title, false, false, false,
                    "FAILED", message, detail, processed, total, 100, startedAt, LocalDateTime.now(), result);
        }

        private StatsTaskStatus busy(String message) {
            return new StatsTaskStatus(taskId, task, title, false, false, true,
                    "BUSY", message, detail, processed, total, 100, startedAt, LocalDateTime.now(), result);
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("taskId", taskId);
            map.put("task", task);
            map.put("title", title);
            map.put("running", running);
            map.put("success", success);
            map.put("busy", busy);
            map.put("phase", phase);
            map.put("message", message);
            map.put("detail", detail);
            map.put("processed", processed);
            map.put("total", total);
            map.put("percent", percent);
            map.put("startedAt", startedAt);
            map.put("updatedAt", updatedAt);
            map.put("elapsedSeconds", elapsedSeconds());
            map.put("result", result == null ? Collections.emptyMap() : result);
            return map;
        }

        private long elapsedSeconds() {
            if (startedAt == null) {
                return 0L;
            }
            LocalDateTime end = updatedAt == null ? LocalDateTime.now() : updatedAt;
            return Math.max(0L, Duration.between(startedAt, end).getSeconds());
        }
    }

    private boolean aggregateHistory(RecordHistory history) {
        return aggregateHistory(history, null);
    }

    private boolean aggregateHistory(RecordHistory history, EventParseSummary parseSummary) {
        List<RecordHistoryPart> parts = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());
        if (!isHistoryReadyForStats(history, parts)) {
            log.debug("[BLR] {}", LogKvs.event("Stats.Aggregate.SkipActive")
                    .add("roomId", history.getRoomId())
                    .add("historyId", history.getId())
                    .add("recording", history.isRecording())
                    .add("streaming", history.isStreaming())
                    .add("activeParts", countActiveParts(parts)));
            return false;
        }
        LocalDateTime startTime = resolveStartTime(history, parts);
        LocalDateTime endTime = resolveEndTime(history, parts);
        if (startTime == null && endTime == null) {
            return false;
        }
        if (startTime == null) {
            startTime = endTime;
        }
        if (endTime == null) {
            endTime = startTime;
        }

        RecordRoom room = roomRepository.findByRoomId(history.getRoomId());
        List<Long> partIds = parts.stream()
                .map(RecordHistoryPart::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        giftCatalogService.syncRoomGiftCatalog(history.getRoomId(), false);
        for (RecordHistoryPart part : parts) {
            RoomLiveEventParseService.ParseResult parseResult = parseSummary == null
                    ? roomLiveEventParseService.parsePart(part, false)
                    : roomLiveEventParseService.parsePartQuietly(part, false);
            if (parseSummary != null) {
                parseSummary.accept(parseResult);
            }
        }
        EventStats eventStats = buildEventStats(history.getId(), partIds);

        LocalDate liveDate = startTime.toLocalDate();
        LocalDateTime now = LocalDateTime.now();
        RoomLiveSessionStats stats = sessionStatsRepository.findByHistoryId(history.getId());
        if (stats == null) {
            stats = new RoomLiveSessionStats();
            stats.setHistoryId(history.getId());
        }
        stats.setRoomId(history.getRoomId());
        stats.setUname(room == null ? null : room.getUname());
        stats.setTitle(history.getTitle());
        stats.setBvId(history.getBvId());
        stats.setLiveDate(liveDate);
        stats.setStartHour(startTime.getHour());
        stats.setStartTime(startTime);
        stats.setEndTime(endTime);
        stats.setDurationSeconds(resolveDurationSeconds(startTime, endTime, parts));
        stats.setPartCount(parts.size());
        stats.setFileSize(resolveFileSize(history, parts));
        stats.setUploadEnabled(history.isUpload());
        stats.setPublished(history.isPublish());
        stats.setPublishCode(history.getCode());
        stats.setSendReply(history.isSendReply());
        stats.setMsgCount(eventStats.msgCount);
        stats.setNormalMsgCount(eventStats.normalMsgCount);
        stats.setAdvancedMsgCount(eventStats.advancedMsgCount);
        stats.setGiftEventCount(eventStats.giftEventCount);
        stats.setGiftTotalCount(eventStats.giftTotalCount);
        stats.setGiftTotalCoin(eventStats.giftTotalCoin);
        stats.setGiftAmountCny(eventStats.giftAmountCny);
        stats.setGiftTypeCount(eventStats.giftTypeCount);
        stats.setScCount(eventStats.scCount);
        stats.setScAmount(eventStats.scAmount);
        stats.setGuardCount(eventStats.guardCount);
        stats.setActiveUserCount(eventStats.activeUserCount);
        stats.setStatsUpdatedAt(now);
        stats.setStatsVersion(STATS_VERSION);

        bucketStatsRepository.deleteByHistoryId(history.getId());
        BucketPeak peak = saveBucketStats(history.getId(), history.getRoomId(), startTime, parts, eventStats.fromRawEvents, now);
        stats.setPeakMinuteIndex(peak.bucketIndex);
        stats.setPeakMinuteMsgCount(peak.msgCount);
        sessionStatsRepository.save(stats);
        recomputeDailyStats(history.getRoomId(), liveDate, room, now);
        return true;
    }

    private static class EventParseSummary {
        private int checked;
        private int issueSkipped;
        private final Map<RoomLiveEventXmlIssue.IssueType, Integer> issueCounts =
                new EnumMap<>(RoomLiveEventXmlIssue.IssueType.class);

        private void accept(RoomLiveEventParseService.ParseResult result) {
            checked++;
            if (result != null && result.issueType() != null) {
                issueSkipped++;
                issueCounts.merge(result.issueType(), 1, Integer::sum);
            }
        }

        private int count(RoomLiveEventXmlIssue.IssueType type) {
            return issueCounts.getOrDefault(type, 0);
        }
    }

    private List<Long> normalizePartIds(Collection<Long> partIds) {
        if (partIds == null) return List.of();
        return partIds.stream().filter(Objects::nonNull).distinct().toList();
    }

    private boolean isHistoryReadyForStats(RecordHistory history, List<RecordHistoryPart> parts) {
        if (history == null || history.getId() == null || history.getEndTime() == null || history.isRecording() || history.isStreaming()) {
            return false;
        }
        if (parts == null || parts.isEmpty()) {
            return true;
        }
        for (RecordHistoryPart part : parts) {
            if (part != null && (part.isRecording() || part.getEndTime() == null)) {
                return false;
            }
        }
        return true;
    }

    private long countActiveParts(List<RecordHistoryPart> parts) {
        if (parts == null || parts.isEmpty()) {
            return 0L;
        }
        return parts.stream()
                .filter(Objects::nonNull)
                .filter(part -> part.isRecording() || part.getEndTime() == null)
                .count();
    }

    private EventStats buildEventStats(Long historyId, List<Long> partIds) {
        EventStats stats = new EventStats();
        long rawEventCount = eventRepository.countByHistoryId(historyId);
        long parsedDanmuCount = nullToZero(eventParseStateRepository.sumDanmuCountByHistoryId(historyId));
        if (rawEventCount > 0 || parsedDanmuCount > 0) {
            stats.fromRawEvents = rawEventCount > 0 && (partIds.isEmpty() || liveMsgRepository.countByPartIdIn(partIds) == 0);
            stats.normalMsgCount = parsedDanmuCount > 0
                    ? parsedDanmuCount
                    : (partIds.isEmpty() ? 0L : liveMsgRepository.countByPartIdInAndPool(partIds, 0));
            stats.scCount = eventRepository.countByHistoryIdAndType(historyId, RoomLiveEvent.TYPE_SC);
            stats.guardCount = nullToZero(eventRepository.sumGuardCountByHistoryId(historyId));
            stats.advancedMsgCount = stats.scCount + eventRepository.countByHistoryIdAndType(historyId, RoomLiveEvent.TYPE_GUARD);
            stats.msgCount = stats.normalMsgCount + stats.advancedMsgCount;
            stats.giftEventCount = eventRepository.countByHistoryIdAndType(historyId, RoomLiveEvent.TYPE_GIFT);
            GiftValueStats giftValueStats = buildGiftValueStats(historyId);
            stats.giftTotalCount = giftValueStats.giftCount;
            stats.giftTotalCoin = giftValueStats.totalCoin;
            stats.giftAmountCny = giftValueStats.amountCny;
            stats.giftTypeCount = eventRepository.countDistinctGiftNameByHistoryId(historyId);
            stats.scAmount = defaultBigDecimal(eventRepository.sumScPriceByHistoryId(historyId));
            stats.activeUserCount = eventRepository.countDistinctUidByHistoryId(historyId);
            return stats;
        }

        stats.msgCount = partIds.isEmpty() ? 0L : liveMsgRepository.countByPartIdIn(partIds);
        stats.normalMsgCount = partIds.isEmpty() ? 0L : liveMsgRepository.countByPartIdInAndPool(partIds, 0);
        stats.advancedMsgCount = partIds.isEmpty() ? 0L : liveMsgRepository.countByPartIdInAndPool(partIds, 1);
        return stats;
    }

    private GiftValueStats buildGiftValueStats(Long historyId) {
        List<RoomLiveEvent> gifts = eventRepository.findByHistoryIdAndType(historyId, RoomLiveEvent.TYPE_GIFT);
        if (gifts.isEmpty()) {
            return new GiftValueStats(0L, 0L, BigDecimal.ZERO);
        }
        GiftCatalogLookup lookup = loadGiftCatalog(gifts);
        long giftCount = 0L;
        long totalCoin = 0L;
        for (RoomLiveEvent event : gifts) {
            giftCount += eventGiftCount(event);
            totalCoin += resolveGiftCoin(event, lookup).coin();
        }
        return new GiftValueStats(giftCount, totalCoin, giftCatalogService.toCny(totalCoin));
    }

    private String giftCatalogKey(String roomId, Integer giftId) {
        return roomId + "#" + giftId;
    }

    private BucketPeak saveBucketStats(Long historyId, String roomId, LocalDateTime historyStart,
                                       List<RecordHistoryPart> parts, boolean fromRawEvents, LocalDateTime now) {
        List<Long> partIds = parts.stream()
                .map(RecordHistoryPart::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        Map<Long, Long> partOffsetMs = buildPartOffsetMs(historyStart, parts);
        if (fromRawEvents) {
            return saveRawEventBucketStats(historyId, roomId, partOffsetMs, now);
        }
        if (partIds.isEmpty()) {
            return new BucketPeak(null, 0L);
        }
        Map<Integer, RoomLiveMsgBucketStats> bucketMap = new TreeMap<>();
        Integer peakIndex = null;
        long peakCount = 0L;
        for (Object[] row : liveMsgRepository.getMsgBucketCountByPartIds(partIds)) {
            Long partId = row[1] == null ? null : toNumber(row[1]).longValue();
            int bucketIndex = absoluteBucketIndex(row[0], partId, partOffsetMs);
            long msgCount = toNumber(row[2]).longValue();
            RoomLiveMsgBucketStats bucket = bucketMap.computeIfAbsent(bucketIndex,
                    index -> newBucketStats(historyId, roomId, index, now));
            bucket.setMsgCount(msgCount);
            bucket.setNormalMsgCount(toNumber(row[3]).longValue());
            bucket.setAdvancedMsgCount(toNumber(row[4]).longValue());
            if (msgCount > peakCount) {
                peakCount = msgCount;
                peakIndex = bucketIndex;
            }
        }
        mergeEventBucketStats(historyId, roomId, partOffsetMs, now, bucketMap, false);
        bucketStatsRepository.saveAll(bucketMap.values());
        return new BucketPeak(peakIndex, peakCount);
    }

    private BucketPeak saveRawEventBucketStats(Long historyId, String roomId, Map<Long, Long> partOffsetMs, LocalDateTime now) {
        Map<Integer, RoomLiveMsgBucketStats> bucketMap = new TreeMap<>();
        Integer peakIndex = null;
        long peakCount = 0L;
        mergeEventBucketStats(historyId, roomId, partOffsetMs, now, bucketMap, true);
        for (RoomLiveMsgBucketStats bucket : bucketMap.values()) {
            if (bucket.getMsgCount() > peakCount) {
                peakCount = bucket.getMsgCount();
                peakIndex = bucket.getBucketIndex();
            }
        }
        bucketStatsRepository.saveAll(bucketMap.values());
        return new BucketPeak(peakIndex, peakCount);
    }

    private void mergeEventBucketStats(Long historyId, String roomId, Map<Long, Long> partOffsetMs, LocalDateTime now,
                                       Map<Integer, RoomLiveMsgBucketStats> bucketMap,
                                       boolean includeMsgCounts) {
        for (Object[] row : eventRepository.getEventBucketCountByHistoryIdWithPartId(historyId)) {
            Long partId = row[0] == null ? null : toNumber(row[0]).longValue();
            int bucketIndex = absoluteBucketIndex(row[1], partId, partOffsetMs);
            String type = row[2] == null ? "" : row[2].toString();
            long count = toNumber(row[3]).longValue();
            RoomLiveMsgBucketStats bucket = bucketMap.computeIfAbsent(bucketIndex,
                    index -> newBucketStats(historyId, roomId, index, now));
            if (RoomLiveEvent.TYPE_DANMU.equals(type)) {
                if (includeMsgCounts) {
                    bucket.setNormalMsgCount(count);
                    bucket.setMsgCount(bucket.getMsgCount() + count);
                }
            } else if (RoomLiveEvent.TYPE_SC.equals(type)) {
                bucket.setScCount(count);
                if (includeMsgCounts) {
                    bucket.setAdvancedMsgCount(bucket.getAdvancedMsgCount() + count);
                    bucket.setMsgCount(bucket.getMsgCount() + count);
                }
            } else if (RoomLiveEvent.TYPE_GUARD.equals(type)) {
                bucket.setGuardCount(count);
                if (includeMsgCounts) {
                    bucket.setAdvancedMsgCount(bucket.getAdvancedMsgCount() + count);
                    bucket.setMsgCount(bucket.getMsgCount() + count);
                }
            } else if (RoomLiveEvent.TYPE_GIFT.equals(type)) {
                bucket.setGiftEventCount(count);
            }
        }
    }

    private Map<Long, Long> buildPartOffsetMs(LocalDateTime historyStart, List<RecordHistoryPart> parts) {
        Map<Long, Long> result = new HashMap<>();
        if (parts == null || parts.isEmpty()) {
            return result;
        }
        long cumulativeMs = 0L;
        for (RecordHistoryPart part : parts) {
            if (part == null || part.getId() == null) {
                continue;
            }
            long offsetMs = cumulativeMs;
            if (historyStart != null && part.getStartTime() != null) {
                long timeOffsetMs = Math.max(0L, Duration.between(historyStart, part.getStartTime()).toMillis());
                offsetMs = Math.max(timeOffsetMs, cumulativeMs);
            }
            result.put(part.getId(), offsetMs);
            cumulativeMs += partDurationMs(part);
        }
        return result;
    }

    private long partDurationMs(RecordHistoryPart part) {
        if (part.getDuration() > 0) {
            return Math.round(part.getDuration() * 1000.0d);
        }
        if (part.getStartTime() != null && part.getEndTime() != null) {
            return Math.max(0L, Duration.between(part.getStartTime(), part.getEndTime()).toMillis());
        }
        return 0L;
    }

    private int absoluteBucketIndex(Object relativeBucket, Long partId, Map<Long, Long> partOffsetMs) {
        long relativeMs = Math.max(0L, toNumber(relativeBucket).longValue()) * 60_000L;
        long offsetMs = partId == null ? 0L : partOffsetMs.getOrDefault(partId, 0L);
        return (int) Math.floor((offsetMs + relativeMs) / 60_000.0d);
    }

    private RoomLiveMsgBucketStats newBucketStats(Long historyId, String roomId, int bucketIndex, LocalDateTime now) {
        RoomLiveMsgBucketStats bucket = new RoomLiveMsgBucketStats();
        bucket.setHistoryId(historyId);
        bucket.setRoomId(roomId);
        bucket.setBucketIndex(bucketIndex);
        bucket.setBucketStartMs(bucketIndex * 60_000L);
        bucket.setStatsUpdatedAt(now);
        bucket.setStatsVersion(STATS_VERSION);
        return bucket;
    }

    private void recomputeDailyStats(String roomId, LocalDate liveDate, RecordRoom room, LocalDateTime now) {
        if (roomId == null || liveDate == null) {
            return;
        }
        List<RoomLiveSessionStats> sessions = sessionStatsRepository.findByRoomIdAndLiveDate(roomId, liveDate);
        RoomLiveDailyStats daily = dailyStatsRepository.findByRoomIdAndLiveDate(roomId, liveDate);
        if (daily == null) {
            daily = new RoomLiveDailyStats();
            daily.setRoomId(roomId);
            daily.setLiveDate(liveDate);
        }
        daily.setUname(room == null ? null : room.getUname());
        daily.setLiveCount(sessions.size());
        long totalDuration = sessions.stream().mapToLong(RoomLiveSessionStats::getDurationSeconds).sum();
        daily.setTotalDurationSeconds(totalDuration);
        daily.setAverageDurationSeconds(sessions.isEmpty() ? 0L : totalDuration / sessions.size());
        daily.setTotalFileSize(sessions.stream().mapToLong(RoomLiveSessionStats::getFileSize).sum());
        daily.setTotalMsgCount(sessions.stream().mapToLong(RoomLiveSessionStats::getMsgCount).sum());
        daily.setTotalNormalMsgCount(sessions.stream().mapToLong(RoomLiveSessionStats::getNormalMsgCount).sum());
        daily.setTotalAdvancedMsgCount(sessions.stream().mapToLong(RoomLiveSessionStats::getAdvancedMsgCount).sum());
        daily.setTotalGiftEventCount(sessions.stream().mapToLong(RoomLiveSessionStats::getGiftEventCount).sum());
        daily.setTotalGiftCount(sessions.stream().mapToLong(RoomLiveSessionStats::getGiftTotalCount).sum());
        daily.setTotalGiftCoin(sessions.stream().mapToLong(RoomLiveSessionStats::getGiftTotalCoin).sum());
        daily.setTotalGiftAmountCny(sessions.stream().map(RoomLiveSessionStats::getGiftAmountCny).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add));
        daily.setTotalScCount(sessions.stream().mapToLong(RoomLiveSessionStats::getScCount).sum());
        daily.setTotalScAmount(sessions.stream().map(RoomLiveSessionStats::getScAmount).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add));
        daily.setTotalGuardCount(sessions.stream().mapToLong(RoomLiveSessionStats::getGuardCount).sum());
        daily.setTotalActiveUserCount(sessions.stream().mapToLong(RoomLiveSessionStats::getActiveUserCount).sum());
        daily.setPublishedCount((int) sessions.stream().filter(RoomLiveSessionStats::isPublished).count());
        daily.setSuccessfulPublishCount((int) sessions.stream().filter(this::isPublishSuccess).count());
        daily.setStatsUpdatedAt(now);
        daily.setStatsVersion(STATS_VERSION);
        dailyStatsRepository.save(daily);
    }

    public Map<String, Object> getOverview() {
        return getOverview(null, null);
    }

    public Map<String, Object> getOverview(LocalDate from, LocalDate to) {
        List<RoomLiveSessionStats> allSessions = toList(sessionStatsRepository.findAll());
        List<RoomLiveSessionStats> sessions = filterSessionsByDate(allSessions, from, to);
        StatsCoverage coverage = buildStatsCoverage(allSessions);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("statsVersion", STATS_VERSION);
        result.put("totalSessions", sessions.size());
        result.put("totalDurationSeconds", sessions.stream().mapToLong(RoomLiveSessionStats::getDurationSeconds).sum());
        result.put("avgDurationSeconds", averageLong(sessions.stream().mapToLong(RoomLiveSessionStats::getDurationSeconds).sum(), sessions.size()));
        result.put("totalMsgCount", sessions.stream().mapToLong(RoomLiveSessionStats::getMsgCount).sum());
        result.put("totalNormalMsgCount", sessions.stream().mapToLong(RoomLiveSessionStats::getNormalMsgCount).sum());
        result.put("totalAdvancedMsgCount", sessions.stream().mapToLong(RoomLiveSessionStats::getAdvancedMsgCount).sum());
        result.put("totalGiftEventCount", sessions.stream().mapToLong(RoomLiveSessionStats::getGiftEventCount).sum());
        result.put("totalGiftCount", sessions.stream().mapToLong(RoomLiveSessionStats::getGiftTotalCount).sum());
        result.put("totalGiftCoin", sessions.stream().mapToLong(RoomLiveSessionStats::getGiftTotalCoin).sum());
        result.put("totalGiftAmountCny", sessions.stream().map(RoomLiveSessionStats::getGiftAmountCny).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add));
        result.put("totalScCount", sessions.stream().mapToLong(RoomLiveSessionStats::getScCount).sum());
        result.put("totalScAmount", sessions.stream().map(RoomLiveSessionStats::getScAmount).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add));
        result.put("totalGuardCount", sessions.stream().mapToLong(RoomLiveSessionStats::getGuardCount).sum());
        result.put("totalActiveUserCount", sessions.stream().mapToLong(RoomLiveSessionStats::getActiveUserCount).sum());
        long totalDuration = sessions.stream().mapToLong(RoomLiveSessionStats::getDurationSeconds).sum();
        result.put("msgDensityPerMinute", totalDuration <= 0 ? 0.0d : round(resultLong(result, "totalMsgCount") / (totalDuration / 60.0d)));
        int uploadEnabledCount = (int) sessions.stream().filter(RoomLiveSessionStats::isUploadEnabled).count();
        int successCount = (int) sessions.stream().filter(this::isPublishSuccess).count();
        result.put("publishSuccessRate", uploadEnabledCount == 0 ? 0.0d : round(successCount * 100.0d / uploadEnabledCount));
        long[] hourBuckets = buildHourBuckets(sessions);
        result.put("hourBuckets", toLongList(hourBuckets));
        result.put("favoriteHour", favoriteHour(hourBuckets));
        result.put("coverage", coverage.toMap());
        result.put("publishStatusDistribution", buildPublishStatusDistribution(sessions));
        result.put("durationDistribution", buildDurationDistribution(sessions));
        result.put("dailyTrend", buildDailyTrend(filterDailyStatsByDate(toList(dailyStatsRepository.findAll()), from, to)));
        result.put("updatedAt", sessions.stream()
                .map(RoomLiveSessionStats::getStatsUpdatedAt)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null));
        return result;
    }

    public Map<String, Object> getStatsStatus() {
        return buildStatsCoverage(toList(sessionStatsRepository.findAll())).toMap();
    }

    public List<Map<String, Object>> getRoomSummaries() {
        return getRoomSummaries(null, null);
    }

    public List<Map<String, Object>> getRoomSummaries(LocalDate from, LocalDate to) {
        Map<String, List<RoomLiveSessionStats>> byRoom = filterSessionsByDate(toList(sessionStatsRepository.findAll()), from, to).stream()
                .filter(s -> s.getRoomId() != null)
                .collect(Collectors.groupingBy(RoomLiveSessionStats::getRoomId));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, List<RoomLiveSessionStats>> entry : byRoom.entrySet()) {
            result.add(buildRoomSummary(entry.getKey(), entry.getValue()));
        }
        result.sort((a, b) -> Long.compare(asLong(b.get("liveCount")), asLong(a.get("liveCount"))));
        return result;
    }

    public Map<String, Object> getRoomDetail(String roomId) {
        return getRoomDetail(roomId, null, null);
    }

    public Map<String, Object> getRoomDetail(String roomId, LocalDate from, LocalDate to) {
        List<RoomLiveSessionStats> sessions = filterSessionsByDate(sessionStatsRepository.findByRoomId(roomId), from, to);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", buildRoomSummary(roomId, sessions));
        result.put("hourBuckets", toLongList(buildHourBuckets(sessions)));
        result.put("dailyTrend", buildDailyTrend(filterDailyStatsByDate(dailyStatsRepository.findByRoomId(roomId), from, to)));
        List<RoomLiveSessionStats> recentSessions = sessions.stream()
                .sorted(Comparator.comparing(RoomLiveSessionStats::getStartTime, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(50)
                .collect(Collectors.toList());
        result.put("sessions", recentSessions);
        result.put("interactionOverview", buildInteractionOverview(sessions));
        result.put("topDanmuUsers", topUsers(danmuUserStatsRepository.findTopUsersByRoomId(roomId, from, to, PageRequest.of(0, 10))));
        result.put("topScUsers", topUsers(eventRepository.findTopUsersByRoomIdAndType(roomId, RoomLiveEvent.TYPE_SC, from, to, PageRequest.of(0, 10))));
        result.put("topGiftUsers", topUsers(eventRepository.findTopGiftUsersByRoomId(roomId, from, to, PageRequest.of(0, 10))));
        result.put("giftDistribution", namedValues(eventRepository.findGiftDistributionByRoomId(roomId, from, to, PageRequest.of(0, 12))));
        if (!recentSessions.isEmpty()) {
            result.put("selectedHistoryId", recentSessions.get(0).getHistoryId());
            result.put("latestBuckets", bucketStatsRepository.findByHistoryIdOrderByBucketIndexAsc(recentSessions.get(0).getHistoryId()));
            result.put("latestSessionDetail", getSessionDetail(roomId, recentSessions.get(0).getHistoryId()));
        } else {
            result.put("selectedHistoryId", null);
            result.put("latestBuckets", Collections.emptyList());
            result.put("latestSessionDetail", Collections.emptyMap());
        }
        return result;
    }

    public List<RoomLiveMsgBucketStats> getSessionBuckets(String roomId, Long historyId) {
        RoomLiveSessionStats session = sessionStatsRepository.findByHistoryId(historyId);
        if (session == null || roomId == null || !roomId.equals(session.getRoomId())) {
            return Collections.emptyList();
        }
        return bucketStatsRepository.findByHistoryIdOrderByBucketIndexAsc(historyId);
    }

    public Map<String, Object> getSessionDetail(String roomId, Long historyId) {
        return withStatsWriteLock(() -> getSessionDetailUnlocked(roomId, historyId));
    }

    private Map<String, Object> getSessionDetailUnlocked(String roomId, Long historyId) {
        RoomLiveSessionStats session = sessionStatsRepository.findByHistoryId(historyId);
        if (session == null || roomId == null || !roomId.equals(session.getRoomId())) {
            return Collections.emptyMap();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("session", session);
        result.put("buckets", bucketStatsRepository.findByHistoryIdOrderByBucketIndexAsc(historyId));
        result.put("topDanmuUsers", topUsers(danmuUserStatsRepository.findTopUsersByHistoryId(historyId, PageRequest.of(0, 10))));
        result.put("danmuUserDiagnostics", buildDanmuUserDiagnostics(historyId));
        result.put("topScUsers", topUsers(eventRepository.findTopUsersByHistoryIdAndType(historyId, RoomLiveEvent.TYPE_SC, PageRequest.of(0, 10))));
        RoomLiveGiftCatalogService.GiftCatalogSyncResult giftSyncResult = giftCatalogService.syncRoomGiftCatalog(roomId, false);
        List<Map<String, Object>> topGiftUsersByCount = topGiftUsersByHistoryId(historyId, Comparator.comparingLong(GiftUserStats::giftCount).reversed());
        List<Map<String, Object>> topGiftUsersByAmount = topGiftUsersByHistoryId(historyId, Comparator.comparingLong(GiftUserStats::totalCoin).reversed());
        result.put("topGiftUsers", topGiftUsersByCount);
        result.put("topGiftUsersByCount", topGiftUsersByCount);
        result.put("topGiftUsersByAmount", topGiftUsersByAmount);
        result.put("giftDistribution", namedValues(eventRepository.findGiftDistributionByHistoryId(historyId, PageRequest.of(0, 12))));
        result.put("giftPriceDiagnostics", buildGiftPriceDiagnostics(historyId, giftSyncResult));
        return result;
    }

    private Map<String, Object> buildDanmuUserDiagnostics(Long historyId) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<RecordHistoryPart> parts = partRepository.findByHistoryIdOrderByStartTimeAsc(historyId);
        List<RoomLiveEventParseState> states = eventParseStateRepository.findByHistoryId(historyId);
        List<RoomLiveEventXmlIssue> xmlIssues = xmlIssueService.findByHistoryId(historyId);
        Map<Long, RoomLiveEventParseState> stateByPartId = states.stream()
                .filter(state -> state.getPartId() != null)
                .collect(Collectors.toMap(RoomLiveEventParseState::getPartId, state -> state, (a, b) -> a));
        Map<Long, RoomLiveEventXmlIssue> issueByPartId = xmlIssues.stream()
                .filter(issue -> issue.getPartId() != null)
                .collect(Collectors.toMap(RoomLiveEventXmlIssue::getPartId, issue -> issue, (a, b) -> a));

        int partCount = parts.size();
        int stateCount = states.size();
        int successStateCount = 0;
        int failedStateCount = 0;
        int missingStateCount = 0;
        int xmlExistsCount = 0;
        int xmlMissingCount = 0;
        int xmlUnknownCount = 0;
        int activeIssueCount = 0;
        int ignoredIssueCount = 0;
        long parsedDanmuCount = 0L;
        List<Map<String, Object>> partDetails = new ArrayList<>();

        for (RecordHistoryPart part : parts) {
            RoomLiveEventParseState state = part.getId() == null ? null : stateByPartId.get(part.getId());
            RoomLiveEventXmlIssue issue = part.getId() == null ? null : issueByPartId.get(part.getId());
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("partId", part.getId());
            detail.put("filePath", part.getFilePath());
            if (state == null) {
                missingStateCount++;
                xmlUnknownCount++;
                detail.put("state", "missing");
                detail.put("reason", "没有解析状态");
            } else {
                parsedDanmuCount += Math.max(0, state.getDanmuCount());
                if (state.isSuccess()) {
                    successStateCount++;
                } else {
                    failedStateCount++;
                }
                if (issue == null) {
                    xmlExistsCount++;
                } else if (issue.getIssueType() == RoomLiveEventXmlIssue.IssueType.MISSING_UNEXPECTED) {
                    xmlMissingCount++;
                } else {
                    xmlUnknownCount++;
                }
                detail.put("state", state.isSuccess() ? "success" : "failed");
                detail.put("danmuCount", state.getDanmuCount());
                detail.put("parserVersion", state.getParserVersion());
                detail.put("parsedAt", state.getParsedAt());
                detail.put("xmlPath", state.getXmlPath());
                detail.put("xmlExists", issue == null);
                detail.put("errorMessage", state.getErrorMessage());
            }
            if (issue != null) {
                boolean ignored = issue.getIgnoredAt() != null;
                if (ignored) {
                    ignoredIssueCount++;
                } else {
                    activeIssueCount++;
                }
                detail.put("xmlIssueType", issue.getIssueType() == null ? null : issue.getIssueType().name());
                detail.put("xmlIssueIgnoredAt", issue.getIgnoredAt());
                detail.put("xmlIssueSuggestion", issue.getIssueType());
                detail.put("xmlPath", issue.getXmlPath() == null ? detail.get("xmlPath") : issue.getXmlPath());
                detail.put("errorMessage", issue.getErrorMessage() == null ? detail.get("errorMessage") : issue.getErrorMessage());
            }
            partDetails.add(detail);
        }

        long userStatsRows = danmuUserStatsRepository.countByHistoryId(historyId);
        long userStatsDanmuCount = nullToZero(danmuUserStatsRepository.sumDanmuCountByHistoryId(historyId));
        boolean hasDanmu = parsedDanmuCount > 0;
        boolean hasUserStats = userStatsRows > 0;
        String status;
        String message;
        boolean rebuildMayHelp = false;
        if (activeIssueCount > 0) {
            status = xmlMissingCount > 0 ? "missing_xml" : "parse_failed";
            message = "检测到 " + activeIssueCount + " 个 XML 问题，系统已停止自动重试，可在 XML 问题管理中处理";
        } else if (failedStateCount > 0) {
            status = "parse_failed";
            message = "检测到 XML 解析失败，损坏或未完整的 XML 不会反复重试；该场部分弹幕趋势或用户 Top 可能缺失";
            rebuildMayHelp = false;
        } else if (!hasDanmu) {
            status = "no_danmu";
            message = "这场没有解析到弹幕，用户 Top 为空是正常的";
        } else if (hasUserStats) {
            status = userStatsDanmuCount < parsedDanmuCount ? "partial" : "ok";
            message = userStatsDanmuCount < parsedDanmuCount
                    ? "弹幕用户统计存在，但数量少于解析到的弹幕数，可能有部分弹幕缺少用户信息"
                    : "弹幕用户统计正常";
        } else if (xmlExistsCount > 0) {
            status = "missing_user_stats_rebuildable";
            message = "弹幕总数存在，但用户统计为空；检测到 XML 文件仍存在，重建统计有机会补齐";
            rebuildMayHelp = true;
        } else if (xmlMissingCount > 0) {
            status = "missing_xml";
            message = "弹幕总数存在，但用户统计为空；对应 XML 文件已不存在，重建统计也无法恢复用户 Top";
        } else {
            status = "unknown";
            message = "弹幕用户统计为空，但缺少足够的解析状态信息，建议检查 XML 文件和解析日志";
        }

        result.put("status", status);
        result.put("message", message);
        result.put("rebuildMayHelp", rebuildMayHelp);
        result.put("historyId", historyId);
        result.put("partCount", partCount);
        result.put("parseStateCount", stateCount);
        result.put("missingStateCount", missingStateCount);
        result.put("successStateCount", successStateCount);
        result.put("failedStateCount", failedStateCount);
        result.put("parsedDanmuCount", parsedDanmuCount);
        result.put("userStatsRows", userStatsRows);
        result.put("userStatsDanmuCount", userStatsDanmuCount);
        result.put("xmlExistsCount", xmlExistsCount);
        result.put("xmlMissingCount", xmlMissingCount);
        result.put("xmlUnknownCount", xmlUnknownCount);
        result.put("xmlIssueCount", activeIssueCount);
        result.put("xmlIgnoredIssueCount", ignoredIssueCount);
        result.put("parts", partDetails);
        return result;
    }

    private Map<String, Object> buildGiftPriceDiagnostics(Long historyId,
                                                          RoomLiveGiftCatalogService.GiftCatalogSyncResult syncResult) {
        List<RoomLiveEvent> gifts = eventRepository.findByHistoryIdAndType(historyId, RoomLiveEvent.TYPE_GIFT);
        GiftCatalogLookup lookup = loadGiftCatalog(gifts);
        long rawPriceHitCount = 0L;
        long roomCatalogHitCount = 0L;
        long localGiftIdFallbackCount = 0L;
        long localGiftNameFallbackCount = 0L;
        long missingPriceEventCount = 0L;
        long pricedEventCount = 0L;
        long totalCoin = 0L;
        boolean estimated = false;

        for (RoomLiveEvent gift : gifts) {
            GiftCoinResolution resolution = resolveGiftCoin(gift, lookup);
            totalCoin += resolution.coin();
            if (resolution.coin() > 0) {
                pricedEventCount++;
            }
            switch (resolution.source()) {
                case "raw_total", "raw_price" -> rawPriceHitCount++;
                case "room_catalog" -> roomCatalogHitCount++;
                case "local_gift_id" -> localGiftIdFallbackCount++;
                case "local_gift_name" -> {
                    localGiftNameFallbackCount++;
                    estimated = true;
                }
                default -> missingPriceEventCount++;
            }
        }

        String status;
        String message;
        boolean rebuildMayHelp = false;
        if (gifts.isEmpty()) {
            status = "ok";
            message = "本场没有礼物事件";
        } else if (missingPriceEventCount == 0) {
            status = estimated ? "estimated" : "ok";
            message = estimated ? "礼物金额已按本地历史礼物名进行部分估算" : "礼物金额统计正常";
        } else if (syncResult != null && syncResult.failed()) {
            status = "api_failed";
            message = "礼物数量已统计，但价格接口请求失败，已尽量使用本地历史价格兜底";
            rebuildMayHelp = true;
        } else if (pricedEventCount > 0) {
            status = "partial";
            message = "部分礼物缺少价格来源，金额可能偏低";
        } else {
            status = "missing_price";
            message = "礼物数量已统计，但没有可用价格来源，金额暂时为 0";
            rebuildMayHelp = true;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("message", message);
        result.put("rebuildMayHelp", rebuildMayHelp);
        result.put("giftEventCount", gifts.size());
        result.put("pricedEventCount", pricedEventCount);
        result.put("missingPriceEventCount", missingPriceEventCount);
        result.put("rawPriceHitCount", rawPriceHitCount);
        result.put("roomCatalogHitCount", roomCatalogHitCount);
        result.put("localGiftIdFallbackCount", localGiftIdFallbackCount);
        result.put("localGiftNameFallbackCount", localGiftNameFallbackCount);
        result.put("totalCoin", totalCoin);
        result.put("amountCny", giftCatalogService.toCny(totalCoin));
        result.put("apiSyncStatus", syncResult == null ? Collections.emptyMap() : syncResult.toMap());
        return result;
    }

    private Map<String, Object> buildRoomSummary(String roomId, List<RoomLiveSessionStats> sessions) {
        Map<String, Object> map = new LinkedHashMap<>();
        RecordRoom room = roomRepository.findByRoomId(roomId);
        long totalDuration = sessions.stream().mapToLong(RoomLiveSessionStats::getDurationSeconds).sum();
        long totalMsg = sessions.stream().mapToLong(RoomLiveSessionStats::getMsgCount).sum();
        long[] hourBuckets = buildHourBuckets(sessions);
        map.put("roomId", roomId);
        map.put("uname", room != null ? room.getUname() : sessions.stream().map(RoomLiveSessionStats::getUname).filter(Objects::nonNull).findFirst().orElse(""));
        map.put("liveCount", sessions.size());
        map.put("totalDurationSeconds", totalDuration);
        map.put("avgDurationSeconds", averageLong(totalDuration, sessions.size()));
        map.put("totalMsgCount", totalMsg);
        map.put("totalNormalMsgCount", sessions.stream().mapToLong(RoomLiveSessionStats::getNormalMsgCount).sum());
        map.put("totalAdvancedMsgCount", sessions.stream().mapToLong(RoomLiveSessionStats::getAdvancedMsgCount).sum());
        map.put("totalGiftEventCount", sessions.stream().mapToLong(RoomLiveSessionStats::getGiftEventCount).sum());
        map.put("totalGiftCount", sessions.stream().mapToLong(RoomLiveSessionStats::getGiftTotalCount).sum());
        map.put("totalGiftCoin", sessions.stream().mapToLong(RoomLiveSessionStats::getGiftTotalCoin).sum());
        map.put("totalGiftAmountCny", sessions.stream().map(RoomLiveSessionStats::getGiftAmountCny).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add));
        map.put("totalScCount", sessions.stream().mapToLong(RoomLiveSessionStats::getScCount).sum());
        map.put("totalScAmount", sessions.stream().map(RoomLiveSessionStats::getScAmount).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add));
        map.put("totalGuardCount", sessions.stream().mapToLong(RoomLiveSessionStats::getGuardCount).sum());
        map.put("totalActiveUserCount", sessions.stream().mapToLong(RoomLiveSessionStats::getActiveUserCount).sum());
        map.put("msgDensityPerMinute", totalDuration <= 0 ? 0.0d : round(totalMsg / (totalDuration / 60.0d)));
        int uploadEnabledCount = (int) sessions.stream().filter(RoomLiveSessionStats::isUploadEnabled).count();
        int successCount = (int) sessions.stream().filter(this::isPublishSuccess).count();
        map.put("publishSuccessRate", uploadEnabledCount == 0 ? 0.0d : round(successCount * 100.0d / uploadEnabledCount));
        map.put("favoriteHour", favoriteHour(hourBuckets));
        map.put("latestStartTime", sessions.stream().map(RoomLiveSessionStats::getStartTime).filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null));
        return map;
    }

    private Map<String, Object> buildInteractionOverview(List<RoomLiveSessionStats> sessions) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("totalDanmu", sessions.stream().mapToLong(RoomLiveSessionStats::getNormalMsgCount).sum());
        map.put("totalGiftEventCount", sessions.stream().mapToLong(RoomLiveSessionStats::getGiftEventCount).sum());
        map.put("totalGiftCount", sessions.stream().mapToLong(RoomLiveSessionStats::getGiftTotalCount).sum());
        map.put("totalGiftCoin", sessions.stream().mapToLong(RoomLiveSessionStats::getGiftTotalCoin).sum());
        map.put("totalGiftAmountCny", sessions.stream().map(RoomLiveSessionStats::getGiftAmountCny).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add));
        map.put("totalScCount", sessions.stream().mapToLong(RoomLiveSessionStats::getScCount).sum());
        map.put("totalScAmount", sessions.stream().map(RoomLiveSessionStats::getScAmount).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add));
        map.put("totalGuardCount", sessions.stream().mapToLong(RoomLiveSessionStats::getGuardCount).sum());
        map.put("totalActiveUserCount", sessions.stream().mapToLong(RoomLiveSessionStats::getActiveUserCount).sum());
        return map;
    }

    private List<Map<String, Object>> topUsers(List<Object[]> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("uid", row.length > 0 ? row[0] : null);
            map.put("uname", row.length > 1 ? row[1] : null);
            map.put("value", row.length > 2 ? toNumber(row[2]).longValue() : 0L);
            result.add(map);
        }
        return result;
    }

    private List<Map<String, Object>> namedValues(List<Object[]> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : rows) {
            String name = row.length > 0 && row[0] != null ? row[0].toString() : "未知礼物";
            result.add(namedCount(name, row.length > 1 ? toNumber(row[1]).longValue() : 0L));
        }
        return result;
    }

    private List<Map<String, Object>> topGiftUsersByHistoryId(Long historyId, Comparator<GiftUserStats> comparator) {
        List<RoomLiveEvent> gifts = eventRepository.findByHistoryIdAndType(historyId, RoomLiveEvent.TYPE_GIFT);
        if (gifts.isEmpty()) {
            return Collections.emptyList();
        }
        GiftCatalogLookup lookup = loadGiftCatalog(gifts);
        Map<String, GiftUserStats> users = new LinkedHashMap<>();
        for (RoomLiveEvent event : gifts) {
            String key = event.getUid() == null ? "name:" + nullToEmpty(event.getUname()) : "uid:" + event.getUid();
            GiftUserStats stats = users.computeIfAbsent(key, ignored -> new GiftUserStats(event.getUid(), event.getUname()));
            stats.giftCount += eventGiftCount(event);
            stats.eventCount++;
            stats.totalCoin += resolveGiftCoin(event, lookup).coin();
        }
        return users.values().stream()
                .sorted(comparator.thenComparing(GiftUserStats::eventCount, Comparator.reverseOrder()))
                .limit(10)
                .map(this::giftUserStatsMap)
                .collect(Collectors.toList());
    }

    private GiftCatalogLookup loadGiftCatalog(List<RoomLiveEvent> gifts) {
        Map<String, RoomLiveGiftCatalog> catalogByRoomGiftId = new HashMap<>();
        Map<String, List<Integer>> giftIdsByRoom = gifts.stream()
                .filter(event -> event.getGiftId() != null && event.getRoomId() != null)
                .collect(Collectors.groupingBy(RoomLiveEvent::getRoomId,
                        Collectors.mapping(RoomLiveEvent::getGiftId,
                                Collectors.collectingAndThen(Collectors.toSet(), ArrayList::new))));
        for (Map.Entry<String, List<Integer>> entry : giftIdsByRoom.entrySet()) {
            giftCatalogRepository.findByRoomIdAndGiftIdIn(entry.getKey(), entry.getValue()).stream()
                    .filter(item -> item.getRoomId() != null && item.getGiftId() != null)
                    .forEach(item -> catalogByRoomGiftId.put(giftCatalogKey(item.getRoomId(), item.getGiftId()), item));
        }
        Set<Integer> giftIds = gifts.stream()
                .map(RoomLiveEvent::getGiftId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Integer, RoomLiveGiftCatalog> localByGiftId = new HashMap<>();
        if (!giftIds.isEmpty()) {
            giftCatalogRepository.findPricedByGiftIdIn(new ArrayList<>(giftIds)).stream()
                    .filter(item -> item.getGiftId() != null && item.getPriceCoin() != null && item.getPriceCoin() > 0)
                    .forEach(item -> localByGiftId.putIfAbsent(item.getGiftId(), item));
        }
        Set<String> giftNames = gifts.stream()
                .map(RoomLiveEvent::getGiftName)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, RoomLiveGiftCatalog> localByGiftName = new HashMap<>();
        if (!giftNames.isEmpty()) {
            giftCatalogRepository.findPricedByGiftNameIn(new ArrayList<>(giftNames)).stream()
                    .filter(item -> StringUtils.isNotBlank(item.getGiftName()) && item.getPriceCoin() != null && item.getPriceCoin() > 0)
                    .forEach(item -> localByGiftName.putIfAbsent(item.getGiftName(), item));
        }
        return new GiftCatalogLookup(catalogByRoomGiftId, localByGiftId, localByGiftName);
    }

    private long eventGiftCount(RoomLiveEvent event) {
        return event.getGiftCount() == null ? 1L : Math.max(0L, event.getGiftCount());
    }

    private GiftCoinResolution resolveGiftCoin(RoomLiveEvent event, GiftCatalogLookup lookup) {
        long count = eventGiftCount(event);
        Long eventCoin = event.getGiftTotalCoin();
        if (eventCoin != null && eventCoin > 0) {
            return new GiftCoinResolution(eventCoin, "raw_total", false);
        }
        if (event.getGiftPriceCoin() != null && event.getGiftPriceCoin() > 0) {
            eventCoin = event.getGiftPriceCoin() * count;
            return new GiftCoinResolution(Math.max(0L, eventCoin), "raw_price", false);
        }
        if (event.getGiftId() != null) {
            RoomLiveGiftCatalog catalog = lookup.roomCatalogByRoomGiftId().get(giftCatalogKey(event.getRoomId(), event.getGiftId()));
            if (catalog != null && catalog.getPriceCoin() != null) {
                eventCoin = catalog.getPriceCoin() * count;
                return new GiftCoinResolution(Math.max(0L, eventCoin), "room_catalog", false);
            }
        }
        if (event.getGiftId() != null) {
            RoomLiveGiftCatalog catalog = lookup.localByGiftId().get(event.getGiftId());
            if (catalog != null && catalog.getPriceCoin() != null) {
                eventCoin = catalog.getPriceCoin() * count;
                return new GiftCoinResolution(Math.max(0L, eventCoin), "local_gift_id", false);
            }
        }
        if (StringUtils.isNotBlank(event.getGiftName())) {
            RoomLiveGiftCatalog catalog = lookup.localByGiftName().get(event.getGiftName());
            if (catalog != null && catalog.getPriceCoin() != null) {
                eventCoin = catalog.getPriceCoin() * count;
                return new GiftCoinResolution(Math.max(0L, eventCoin), "local_gift_name", true);
            }
        }
        return new GiftCoinResolution(0L, "missing", false);
    }

    private Map<String, Object> giftUserStatsMap(GiftUserStats stats) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("uid", stats.uid);
        map.put("uname", stats.uname);
        map.put("value", stats.giftCount);
        map.put("giftCount", stats.giftCount);
        map.put("eventCount", stats.eventCount);
        map.put("totalCoin", stats.totalCoin);
        map.put("amountCny", giftCatalogService.toCny(stats.totalCoin));
        return map;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private StatsCoverage buildStatsCoverage(List<RoomLiveSessionStats> sessions) {
        List<RecordHistory> completedHistories = historyRepository.findByEndTimeIsNotNullOrderByEndTimeDesc();
        Map<Long, RoomLiveSessionStats> statsByHistoryId = sessions.stream()
                .filter(s -> s.getHistoryId() != null)
                .collect(Collectors.toMap(RoomLiveSessionStats::getHistoryId, s -> s, (a, b) -> a));
        long stale = 0L;
        long pending = 0L;
        List<Map<String, Object>> pendingItems = new ArrayList<>();
        for (RecordHistory history : completedHistories) {
            if (history == null || history.getId() == null) {
                continue;
            }
            RoomLiveSessionStats stats = statsByHistoryId.get(history.getId());
            if (stats == null) {
                pending++;
                addPendingCoverageItem(pendingItems, history, "missing", "没有生成统计记录");
            } else if (stats.getStatsVersion() < STATS_VERSION) {
                stale++;
                pending++;
                addPendingCoverageItem(pendingItems, history, "stale", "统计版本较旧，需要重新生成");
            }
        }
        LocalDateTime updatedAt = sessions.stream()
                .map(RoomLiveSessionStats::getStatsUpdatedAt)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);
        return new StatsCoverage(completedHistories.size(), sessions.size(), pending, stale, updatedAt, pendingItems);
    }

    private void addPendingCoverageItem(List<Map<String, Object>> pendingItems,
                                        RecordHistory history,
                                        String status,
                                        String baseReason) {
        if (pendingItems.size() >= 50 || history == null || history.getId() == null) {
            return;
        }
        List<RecordHistoryPart> parts = partRepository.findByHistoryId(history.getId());
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("historyId", history.getId());
        item.put("roomId", history.getRoomId());
        item.put("title", history.getTitle());
        item.put("bvId", history.getBvId());
        item.put("startTime", history.getStartTime());
        item.put("endTime", history.getEndTime());
        item.put("status", status);
        item.put("reason", pendingCoverageReason(history, parts, baseReason));
        item.put("partCount", parts == null ? 0 : parts.size());
        item.put("activePartCount", countActiveParts(parts));
        pendingItems.add(item);
    }

    private String pendingCoverageReason(RecordHistory history, List<RecordHistoryPart> parts, String baseReason) {
        if (history.getEndTime() == null) {
            return "稿件没有结束时间，暂不统计";
        }
        if (history.isRecording()) {
            return "稿件仍标记为正在录制，重建会跳过";
        }
        if (history.isStreaming()) {
            return "稿件仍标记为直播中，重建会跳过";
        }
        if (parts != null) {
            long recordingParts = parts.stream()
                    .filter(Objects::nonNull)
                    .filter(RecordHistoryPart::isRecording)
                    .count();
            if (recordingParts > 0) {
                return "存在 " + recordingParts + " 个分P仍标记为正在录制，重建会跳过";
            }
            long noEndParts = parts.stream()
                    .filter(Objects::nonNull)
                    .filter(part -> part.getEndTime() == null)
                    .count();
            if (noEndParts > 0) {
                return "存在 " + noEndParts + " 个分P没有结束时间，重建会跳过";
            }
        }
        LocalDateTime startTime = resolveStartTime(history, parts);
        LocalDateTime endTime = resolveEndTime(history, parts);
        if (startTime == null && endTime == null) {
            return "稿件和分P都缺少可用起止时间，无法归档到统计日期";
        }
        return baseReason;
    }

    private List<Map<String, Object>> buildPublishStatusDistribution(List<RoomLiveSessionStats> sessions) {
        long success = sessions.stream().filter(this::isPublishSuccess).count();
        long invisible = sessions.stream().filter(s -> s.getPublishCode() == 62002).count();
        long failed = sessions.stream()
                .filter(s -> s.isUploadEnabled() && s.isPublished() && !isPublishSuccess(s) && s.getPublishCode() != 62002)
                .count();
        long waiting = sessions.stream().filter(s -> s.isUploadEnabled() && !s.isPublished()).count();
        long notSubmitted = sessions.stream().filter(s -> !s.isUploadEnabled()).count();
        List<Map<String, Object>> result = new ArrayList<>();
        result.add(namedCount("投稿成功", success));
        result.add(namedCount("等待投稿", waiting));
        result.add(namedCount("投稿失败/退回", failed));
        result.add(namedCount("稿件不可见", invisible));
        result.add(namedCount("未开启投稿", notSubmitted));
        return result;
    }

    private List<Map<String, Object>> buildDurationDistribution(List<RoomLiveSessionStats> sessions) {
        long under1h = 0L;
        long h1to3 = 0L;
        long h3to6 = 0L;
        long over6h = 0L;
        for (RoomLiveSessionStats session : sessions) {
            long seconds = session.getDurationSeconds();
            if (seconds < 3600L) {
                under1h++;
            } else if (seconds < 10_800L) {
                h1to3++;
            } else if (seconds < 21_600L) {
                h3to6++;
            } else {
                over6h++;
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        result.add(namedCount("0-1h", under1h));
        result.add(namedCount("1-3h", h1to3));
        result.add(namedCount("3-6h", h3to6));
        result.add(namedCount("6h+", over6h));
        return result;
    }

    private List<Map<String, Object>> buildDailyTrend(List<RoomLiveDailyStats> dailyStats) {
        Map<LocalDate, DailyAggregate> byDate = new TreeMap<>();
        for (RoomLiveDailyStats daily : dailyStats) {
            if (daily == null || daily.getLiveDate() == null) {
                continue;
            }
            DailyAggregate aggregate = byDate.computeIfAbsent(daily.getLiveDate(), DailyAggregate::new);
            aggregate.liveCount += daily.getLiveCount();
            aggregate.totalDurationSeconds += daily.getTotalDurationSeconds();
            aggregate.totalMsgCount += daily.getTotalMsgCount();
            aggregate.totalAdvancedMsgCount += daily.getTotalAdvancedMsgCount();
            aggregate.totalGiftCount += daily.getTotalGiftCount();
            aggregate.totalScCount += daily.getTotalScCount();
            aggregate.totalGuardCount += daily.getTotalGuardCount();
            aggregate.publishedCount += daily.getPublishedCount();
            aggregate.successfulPublishCount += daily.getSuccessfulPublishCount();
        }
        List<DailyAggregate> aggregates = new ArrayList<>(byDate.values());
        int from = Math.max(0, aggregates.size() - 30);
        List<Map<String, Object>> result = new ArrayList<>();
        for (DailyAggregate aggregate : aggregates.subList(from, aggregates.size())) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("liveDate", aggregate.liveDate);
            map.put("liveCount", aggregate.liveCount);
            map.put("totalDurationSeconds", aggregate.totalDurationSeconds);
            map.put("averageDurationSeconds", averageLong(aggregate.totalDurationSeconds, aggregate.liveCount));
            map.put("totalMsgCount", aggregate.totalMsgCount);
            map.put("totalAdvancedMsgCount", aggregate.totalAdvancedMsgCount);
            map.put("totalGiftCount", aggregate.totalGiftCount);
            map.put("totalScCount", aggregate.totalScCount);
            map.put("totalGuardCount", aggregate.totalGuardCount);
            map.put("publishedCount", aggregate.publishedCount);
            map.put("successfulPublishCount", aggregate.successfulPublishCount);
            result.add(map);
        }
        return result;
    }

    private List<RoomLiveSessionStats> filterSessionsByDate(List<RoomLiveSessionStats> sessions, LocalDate from, LocalDate to) {
        return sessions.stream()
                .filter(s -> isDateInRange(s.getLiveDate(), from, to))
                .collect(Collectors.toList());
    }

    private List<RoomLiveDailyStats> filterDailyStatsByDate(List<RoomLiveDailyStats> dailyStats, LocalDate from, LocalDate to) {
        return dailyStats.stream()
                .filter(s -> isDateInRange(s.getLiveDate(), from, to))
                .collect(Collectors.toList());
    }

    private boolean isDateInRange(LocalDate value, LocalDate from, LocalDate to) {
        if (value == null) {
            return false;
        }
        if (from != null && value.isBefore(from)) {
            return false;
        }
        return to == null || !value.isAfter(to);
    }

    private Map<String, Object> namedCount(String name, long count) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("value", count);
        return map;
    }

    private LocalDateTime resolveStartTime(RecordHistory history, List<RecordHistoryPart> parts) {
        if (history.getStartTime() != null) {
            return history.getStartTime();
        }
        return parts.stream().map(RecordHistoryPart::getStartTime).filter(Objects::nonNull).min(LocalDateTime::compareTo).orElse(null);
    }

    private LocalDateTime resolveEndTime(RecordHistory history, List<RecordHistoryPart> parts) {
        if (history.getEndTime() != null) {
            return history.getEndTime();
        }
        return parts.stream().map(RecordHistoryPart::getEndTime).filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null);
    }

    private long resolveDurationSeconds(LocalDateTime startTime, LocalDateTime endTime, List<RecordHistoryPart> parts) {
        double partDuration = parts.stream().mapToDouble(RecordHistoryPart::getDuration).filter(v -> v > 0).sum();
        if (partDuration > 0) {
            return Math.round(partDuration);
        }
        if (startTime != null && endTime != null) {
            return Math.max(0L, Duration.between(startTime, endTime).getSeconds());
        }
        return 0L;
    }

    private long resolveFileSize(RecordHistory history, List<RecordHistoryPart> parts) {
        long partFileSize = parts.stream().mapToLong(RecordHistoryPart::getFileSize).sum();
        return partFileSize > 0 ? partFileSize : history.getFileSize();
    }

    private boolean isPublishSuccess(RoomLiveSessionStats stats) {
        return stats.isPublished() && (stats.getPublishCode() == 0 || stats.getPublishCode() == -50);
    }

    private long[] buildHourBuckets(List<RoomLiveSessionStats> sessions) {
        long[] buckets = new long[24];
        for (RoomLiveSessionStats session : sessions) {
            Integer hour = session.getStartHour();
            if (hour != null && hour >= 0 && hour < 24) {
                buckets[hour]++;
            }
        }
        return buckets;
    }

    private Integer favoriteHour(long[] buckets) {
        long max = 0L;
        Integer hour = null;
        for (int i = 0; i < buckets.length; i++) {
            if (buckets[i] > max) {
                max = buckets[i];
                hour = i;
            }
        }
        return hour;
    }

    private long averageLong(long total, int count) {
        return count == 0 ? 0L : total / count;
    }

    private double round(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    private List<Long> toLongList(long[] values) {
        List<Long> result = new ArrayList<>(values.length);
        for (long value : values) {
            result.add(value);
        }
        return result;
    }

    private <T> List<T> toList(Iterable<T> iterable) {
        List<T> list = new ArrayList<>();
        if (iterable != null) {
            iterable.forEach(list::add);
        }
        return list;
    }

    private Number toNumber(Object value) {
        if (value instanceof Number) {
            return (Number) value;
        }
        if (value instanceof BigInteger) {
            return (BigInteger) value;
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        return 0;
    }

    private long asLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return 0L;
    }

    private long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

    private BigDecimal defaultBigDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private long resultLong(Map<String, Object> values, String key) {
        return asLong(values.get(key));
    }

    private record StatsCoverage(long totalHistoryCount, long statsSessionCount, long pendingSessionCount,
                                 long staleSessionCount, LocalDateTime updatedAt,
                                 List<Map<String, Object>> pendingItems) {
        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("totalHistoryCount", totalHistoryCount);
            map.put("statsSessionCount", statsSessionCount);
            map.put("pendingSessionCount", pendingSessionCount);
            map.put("staleSessionCount", staleSessionCount);
            map.put("complete", pendingSessionCount == 0);
            map.put("statsVersion", STATS_VERSION);
            map.put("updatedAt", updatedAt);
            map.put("pendingItems", pendingItems);
            return map;
        }
    }

    private static class DailyAggregate {
        private final LocalDate liveDate;
        private int liveCount;
        private long totalDurationSeconds;
        private long totalMsgCount;
        private long totalAdvancedMsgCount;
        private long totalGiftCount;
        private long totalScCount;
        private long totalGuardCount;
        private int publishedCount;
        private int successfulPublishCount;

        private DailyAggregate(LocalDate liveDate) {
            this.liveDate = liveDate;
        }
    }

    private record BucketPeak(Integer bucketIndex, long msgCount) {
    }

    private static class EventStats {
        private boolean fromRawEvents;
        private long msgCount;
        private long normalMsgCount;
        private long advancedMsgCount;
        private long giftEventCount;
        private long giftTotalCount;
        private long giftTotalCoin;
        private BigDecimal giftAmountCny = BigDecimal.ZERO;
        private long giftTypeCount;
        private long scCount;
        private BigDecimal scAmount = BigDecimal.ZERO;
        private long guardCount;
        private long activeUserCount;
    }

    private record GiftValueStats(long giftCount, long totalCoin, BigDecimal amountCny) {
    }

    private record GiftCatalogLookup(Map<String, RoomLiveGiftCatalog> roomCatalogByRoomGiftId,
                                     Map<Integer, RoomLiveGiftCatalog> localByGiftId,
                                     Map<String, RoomLiveGiftCatalog> localByGiftName) {
    }

    private record GiftCoinResolution(long coin, String source, boolean estimated) {
    }

    private static class GiftUserStats {
        private final Long uid;
        private final String uname;
        private long giftCount;
        private long eventCount;
        private long totalCoin;

        private GiftUserStats(Long uid, String uname) {
            this.uid = uid;
            this.uname = uname;
        }

        private long giftCount() {
            return giftCount;
        }

        private long eventCount() {
            return eventCount;
        }

        private long totalCoin() {
            return totalCoin;
        }
    }
}
