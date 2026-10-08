package top.sshh.bililiverecoder.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.repo.*;
import top.sshh.bililiverecoder.util.LogKvs;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
public class RecordHistorySplitService {
    private final RecordHistoryRepository histories;
    private final RecordHistoryPartRepository parts;
    private final RecordRoomRepository rooms;
    private final SystemConfigService configs;
    private final PartPreviewService preview;
    private final TransactionTemplate transactions;
    private final RoomLiveEventRepository events;
    private final RoomLiveDanmuUserStatsRepository danmuStats;
    private final RoomLiveEventParseStateRepository parseStates;
    private final RoomLiveEventXmlIssueRepository xmlIssues;

    @org.springframework.beans.factory.annotation.Autowired
    private ObjectProvider<StatsAggregationService> statsProvider;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private top.sshh.bililiverecoder.lifecycle.ShutdownState shutdownState;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DatabaseMaintenanceState maintenanceState;

    public RecordHistorySplitService(RecordHistoryRepository histories, RecordHistoryPartRepository parts,
                                    RecordRoomRepository rooms, SystemConfigService configs,
                                    PartPreviewService preview, PlatformTransactionManager transactionManager,
                                    RoomLiveEventRepository events, RoomLiveDanmuUserStatsRepository danmuStats,
                                    RoomLiveEventParseStateRepository parseStates, RoomLiveEventXmlIssueRepository xmlIssues) {
        this.histories = histories;
        this.parts = parts;
        this.rooms = rooms;
        this.configs = configs;
        this.preview = preview;
        this.transactions = new TransactionTemplate(transactionManager);
        this.events = events;
        this.danmuStats = danmuStats;
        this.parseStates = parseStates;
        this.xmlIssues = xmlIssues;
    }

    public static String normalizeThreshold(String key, String value) {
        try {
            BigDecimal number = new BigDecimal(value == null ? "" : value.trim());
            boolean duration = SystemConfigService.KEY_SPLIT_DURATION_MINUTES.equals(key);
            if (number.signum() < 0 || number.stripTrailingZeros().scale() > (duration ? 0 : 2)) {
                throw new IllegalArgumentException();
            }
            number.multiply(BigDecimal.valueOf(duration ? 60L : 1073741824L))
                    .setScale(0, RoundingMode.CEILING).longValueExact();
            return number.stripTrailingZeros().toPlainString();
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("拆稿阈值必须为非负数，时长为整数分钟，大小最多两位小数且不能溢出");
        }
    }

    public void initializeNew(RecordHistory history) {
        Map<String, String> values = configs.getAllConfigsMap();
        history.setSplitDurationSeconds(threshold(values, SystemConfigService.KEY_SPLIT_DURATION_MINUTES, 60));
        history.setSplitSizeBytes(threshold(values, SystemConfigService.KEY_SPLIT_SIZE_GB, 1073741824L));
        history.setSplitGroup(UUID.randomUUID().toString());
        history.setSplitSequence(1);
    }

    private long threshold(Map<String, String> values, String key, long multiplier) {
        try {
            return new BigDecimal(normalizeThreshold(key, values.getOrDefault(key, "0")))
                    .multiply(BigDecimal.valueOf(multiplier)).setScale(0, RoundingMode.CEILING).longValueExact();
        } catch (IllegalArgumentException error) {
            return 0;
        }
    }

    // 只在新文件出现时调用，已经收尾但没有下一P时不创建空稿件
    public RecordHistory historyForNewPart(RecordRoom room, RecordHistory history, LocalDateTime start) {
        if (!history.isSplitClosed()) return history;
        synchronized (room.getRoomId().intern()) {
            return transactions.execute(status -> successor(room, histories.findById(history.getId()).orElseThrow(), start));
        }
    }

    private RecordHistory successor(RecordRoom room, RecordHistory previous, LocalDateTime start) {
        RecordHistory next = histories.findBySplitParentId(previous.getId());
        if (next == null) {
            next = new RecordHistory();
            initializeNew(next);
            next.setSplitParentId(previous.getId());
            next.setSplitGroup(previous.getSplitGroup());
            next.setSplitSequence((previous.getSplitSequence() == null ? 1 : previous.getSplitSequence()) + 1);
            next.setRoomId(previous.getRoomId());
            next.setSessionId(room != null && room.getSessionId() != null && Objects.equals(room.getHistoryId(), previous.getId()) ? room.getSessionId() : previous.getSessionId());
            next.setEventId("split:" + previous.getId() + ":" + previous.getSplitBoundaryPartId());
            next.setTitle(previous.getTitle());
            next.setCoverUrl(previous.getCoverUrl());
            next.setLocalCoverPath(previous.getLocalCoverPath());
            next.setFilePath(previous.getFilePath());
            next.setUpload(previous.isUpload());
            next.setStartTime(start);
            next.setEndTime(start);
            boolean current = room != null && Objects.equals(room.getHistoryId(), previous.getId());
            next.setRecording(current && room.isRecording());
            next.setStreaming(current && room.isStreaming());
            next.setUpdateTime(LocalDateTime.now());
            next = histories.save(next);
        }
        if (room != null && Objects.equals(room.getHistoryId(), previous.getId())) {
            room.setHistoryId(next.getId());
            rooms.save(room);
        }
        return next;
    }

    public void reconcile(Long historyId) {
        RecordHistory initial = historyId == null ? null : histories.findById(historyId).orElse(null);
        if (initial == null || !initial.isSplitEnabled() || initial.isPublish() || initial.isProcessingArchived()) return;
        synchronized (initial.getRoomId().intern()) {
            transactions.executeWithoutResult(status -> reconcileInside(historyId));
        }
    }

    private void reconcileInside(Long historyId) {
        RecordHistory history = histories.findById(historyId).orElse(null);
        if (history != null && history.isSplitClosed()) {
            for (RecordHistoryPart part : parts.findByHistoryId(historyId)) {
                if (!part.isRecording() && part.getEndTime() != null && !validDuration(part.getDuration())) captureMetadata(part);
            }
            return;
        }
        while (history != null && history.isSplitEnabled() && !history.isSplitClosed()
                && !history.isPublish() && !history.isProcessingArchived()) {
            List<RecordHistoryPart> ordered = new ArrayList<>(parts.findByHistoryId(history.getId()));
            ordered.sort(Comparator.comparing(RecordHistoryPart::getStartTime, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(RecordHistoryPart::getId));
            BigDecimal size = BigDecimal.ZERO;
            BigDecimal duration = BigDecimal.ZERO;
            RecordHistoryPart boundary = null;
            int position = 0;
            for (RecordHistoryPart part : ordered) {
                if (part.isRecording() || part.getEndTime() == null) break;
                captureMetadata(part);
                if (positive(history.getSplitSizeBytes()) && part.getSplitFileSize() == null
                        || positive(history.getSplitDurationSeconds()) && part.getSplitDuration() == null) break;
                size = size.add(BigDecimal.valueOf(part.getSplitFileSize() == null ? 0 : part.getSplitFileSize()));
                duration = duration.add(BigDecimal.valueOf(part.getSplitDuration() == null ? 0 : part.getSplitDuration()));
                part.setSplitAssigned(true);
                part.setPartOrder(++position);
                parts.save(part);
                boolean sizeReached = positive(history.getSplitSizeBytes()) && size.compareTo(BigDecimal.valueOf(history.getSplitSizeBytes())) >= 0;
                boolean durationReached = positive(history.getSplitDurationSeconds()) && duration.compareTo(BigDecimal.valueOf(history.getSplitDurationSeconds())) >= 0;
                if (sizeReached || durationReached) {
                    history.setSplitReason(sizeReached && durationReached ? "SIZE_AND_DURATION" : sizeReached ? "SIZE" : "DURATION");
                    boundary = part;
                    break;
                }
            }
            if (boundary == null) return;
            history.setSplitBoundaryPartId(boundary.getId());
            history.setSplitClosedAt(LocalDateTime.now());
            history.setRecording(false);
            history.setStreaming(false);
            history.setEndTime(boundary.getEndTime());
            history.setCloseSource("SPLIT_THRESHOLD");
            history.setCloseAt(history.getSplitClosedAt());
            history.setFileSize(size.min(BigDecimal.valueOf(Long.MAX_VALUE)).longValue());
            histories.save(history);
            log.info("[BLR] {}", LogKvs.event("Split.Closed")
                    .add("roomId", history.getRoomId())
                    .add("historyId", history.getId())
                    .add("boundaryPartId", boundary.getId())
                    .add("sizeBytes", size)
                    .add("durationSeconds", duration)
                    .add("sizeLimit", history.getSplitSizeBytes())
                    .add("durationLimit", history.getSplitDurationSeconds())
                    .add("reason", history.getSplitReason()));
            int boundaryIndex = ordered.indexOf(boundary);
            if (boundaryIndex + 1 == ordered.size()) return;
            RecordRoom room = rooms.findByRoomId(history.getRoomId());
            RecordHistory next = successor(room, history, ordered.get(boundaryIndex + 1).getStartTime());
            int order = 0;
            for (RecordHistoryPart tail : ordered.subList(boundaryIndex + 1, ordered.size())) {
                // 后续P在前序结束数据齐全前不会上传，迁移只调整归属与稿件内顺序
                tail.setHistoryId(next.getId());
                tail.setPartOrder(++order);
                parts.save(tail);
                events.reassignHistoryByPartId(tail.getId(), next.getId());
                danmuStats.reassignHistoryByPartId(tail.getId(), next.getId());
                parseStates.reassignHistoryByPartId(tail.getId(), next.getId());
                xmlIssues.reassignHistoryByPartId(tail.getId(), next.getId());
            }
            next.setEndTime(ordered.get(ordered.size() - 1).getEndTime());
            next.setFileSize(ordered.subList(boundaryIndex + 1, ordered.size()).stream().mapToLong(RecordHistoryPart::getFileSize).sum());
            histories.save(next);
            refreshStatsAfterCommit(history.getId(), next.getId());
            history = next;
        }
    }

    private void captureMetadata(RecordHistoryPart part) {
        if (part.getSplitFileSize() == null && part.getFileSize() > 0) part.setSplitFileSize(part.getFileSize());
        if (part.getSplitDuration() == null) {
            double seconds = part.getDuration();
            if (!validDuration(seconds)) {
                Double probed = preview.probeDuration(part.getId());
                if (probed != null) seconds = probed;
            }
            if (validDuration(seconds)) {
                part.setSplitDuration(seconds);
                part.setDuration((float) seconds);
                if (part.getStartTime() == null) part.setStartTime(part.getEndTime().minusNanos((long) (seconds * 1000000000L)));
            }
        }
        parts.save(part);
    }

    private boolean validDuration(double value) { return Double.isFinite(value) && value > 0 && value <= Float.MAX_VALUE; }

    private boolean positive(Long value) { return value != null && value > 0; }

    private void refreshStatsAfterCommit(Long previousId, Long nextId) {
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        // 提交后再取统计服务，避免懒加载代理和异步代理在 AOT 启动时冲突
                        StatsAggregationService stats = statsProvider.getIfAvailable();
                        if (stats == null) return;
                        stats.refreshHistoryStatsAfterCommit(previousId);
                        stats.refreshHistoryStatsAfterCommit(nextId);
                    }
                });
    }

    public boolean canUpload(RecordHistoryPart part) {
        if (part == null) return false;
        RecordHistory history = histories.findById(part.getHistoryId()).orElse(null);
        if (HistoryProcessingPolicy.blocksAutomatic(history)) return false;
        if (!history.isSplitEnabled()) return true;
        // 已投稿稿件的替换素材不参与录制拆稿，也不会产生拆稿归属
        if (history.isPublish() && "EDIT_PART".equals(part.getSourceType())) {
            return !part.isRecording() && part.getEndTime() != null;
        }
        reconcile(history.getId());
        RecordHistoryPart current = parts.findById(part.getId()).orElse(null);
        RecordHistory owner = current == null ? null : histories.findById(current.getHistoryId()).orElse(null);
        if (current != null && (owner != null && owner.isSplitEnabled() || current.getSplitAssigned() != null)
                && !validDuration(current.getDuration())) return false;
        return current != null && !current.isRecording() && current.getEndTime() != null
                && (owner != null && !owner.isSplitEnabled() || Boolean.TRUE.equals(current.getSplitAssigned()));
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 5000)
    public void reconcilePending() {
        if (Thread.currentThread().isInterrupted() || shutdownState != null && shutdownState.isShuttingDown()
                || maintenanceState != null && maintenanceState.isMaintenanceActive()) return;
        for (RecordHistory history : histories.findPendingSplitHistories()) {
            try { reconcile(history.getId()); }
            catch (RuntimeException error) {
                log.warn("[BLR] {}", LogKvs.event("Split.ReconcileFailed").add("historyId", history.getId()), error);
            }
        }
    }
}
