package top.sshh.bililiverecoder.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.job.LiveMsgSendSync;
import top.sshh.bililiverecoder.repo.LiveMsgRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.util.LogKvs;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Service
public class HistoryMsgQueueCleanupService {

    private static final int ABANDONED_CODE = -3;
    private static final int DEFAULT_OLDER_THAN_DAYS = 7;
    private static final int DEFAULT_LIMIT = 5000;
    private static final int MAX_LIMIT = 20000;
    private static final int IN_BATCH_SIZE = 500;

    private final RecordHistoryRepository historyRepository;
    private final RecordHistoryPartRepository partRepository;
    private final LiveMsgRepository msgRepository;
    private final RecordRoomRepository roomRepository;

    public HistoryMsgQueueCleanupService(RecordHistoryRepository historyRepository,
                                         RecordHistoryPartRepository partRepository,
                                         LiveMsgRepository msgRepository,
                                         RecordRoomRepository roomRepository) {
        this.historyRepository = historyRepository;
        this.partRepository = partRepository;
        this.msgRepository = msgRepository;
        this.roomRepository = roomRepository;
    }

    public CleanupOptions optionsFrom(Map<String, ?> raw) {
        boolean hasExplicitOption = raw != null && (raw.containsKey("ordinary")
                || raw.containsKey("advanced")
                || raw.containsKey("reply")
                || raw.containsKey("forceArchive"));
        boolean ordinary = bool(raw, "ordinary", false);
        boolean advanced = bool(raw, "advanced", false);
        boolean reply = bool(raw, "reply", false);
        boolean forceArchive = bool(raw, "forceArchive", false);
        if (!hasExplicitOption) {
            ordinary = true;
            advanced = true;
            reply = true;
        }
        return new CleanupOptions(ordinary, advanced, reply, forceArchive);
    }

    public int olderThanDaysFrom(Map<String, ?> raw) {
        return clampInt(raw == null ? null : raw.get("olderThanDays"), 0, 3650, DEFAULT_OLDER_THAN_DAYS);
    }

    public int limitFrom(Map<String, ?> raw) {
        return clampInt(raw == null ? null : raw.get("limit"), 1, MAX_LIMIT, DEFAULT_LIMIT);
    }

    @Transactional
    public CleanupResult cleanupByHistoryId(Long historyId, CleanupOptions options, boolean dryRun, String source) {
        if (historyId == null) {
            return CleanupResult.empty(dryRun);
        }
        return cleanupByHistoryIds(List.of(historyId), options, dryRun, source);
    }

    @Transactional
    public CleanupResult cleanupByHistoryIds(Collection<Long> historyIds, CleanupOptions options, boolean dryRun, String source) {
        CleanupOptions safeOptions = options == null ? CleanupOptions.all() : options;
        List<Long> ids = normalizeIds(historyIds);
        if (ids.isEmpty()) {
            return CleanupResult.empty(dryRun);
        }

        List<RecordHistory> histories = new ArrayList<>();
        for (Long id : ids) {
            Optional<RecordHistory> history = historyRepository.findById(id);
            history.ifPresent(histories::add);
        }
        return cleanupHistories(histories, safeOptions, dryRun, source, ids.size(), false);
    }

    @Transactional(readOnly = true)
    public CleanupResult previewHistorical(CleanupOptions options, int olderThanDays, int limit) {
        return cleanupHistorical(options, olderThanDays, limit, true, "preview");
    }

    @Transactional
    public CleanupResult applyHistorical(CleanupOptions options, int olderThanDays, int limit) {
        return cleanupHistorical(options, olderThanDays, limit, false, "historical");
    }

    private CleanupResult cleanupHistorical(CleanupOptions options, int olderThanDays, int limit, boolean dryRun, String source) {
        CleanupOptions safeOptions = options == null ? CleanupOptions.all() : options;
        int safeDays = Math.max(0, olderThanDays);
        int safeLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        LocalDateTime endBefore = LocalDateTime.now().minusDays(safeDays);
        List<RecordHistory> histories = findCleanupCandidates(endBefore, safeLimit, safeOptions);
        boolean mayHaveMore = histories.size() >= safeLimit;
        CleanupResult result = cleanupHistories(histories, safeOptions, dryRun, source, histories.size(), mayHaveMore, true);
        result.olderThanDays = safeDays;
        result.limit = safeLimit;
        result.totalCandidates = histories.size();
        return result;
    }

    private List<RecordHistory> findCleanupCandidates(LocalDateTime endBefore, int limit, CleanupOptions options) {
        PageRequest page = PageRequest.of(0, limit);
        LinkedHashMap<Long, RecordHistory> byId = new LinkedHashMap<>();
        if (options.ordinary) {
            for (RecordHistory history : historyRepository.findMsgQueueCleanupCandidatesByPendingOrdinaryMsg(endBefore, page)) {
                if (history != null && history.getId() != null) {
                    byId.put(history.getId(), history);
                }
            }
        }
        if (options.advanced) {
            for (RecordHistory history : historyRepository.findMsgQueueCleanupCandidatesByPendingHighMsg(endBefore, page)) {
                if (history != null && history.getId() != null) {
                    byId.putIfAbsent(history.getId(), history);
                }
            }
        }
        if (options.reply) {
            for (RecordHistory history : historyRepository.findMsgQueueCleanupCandidatesByPendingReply(endBefore, page)) {
                if (history != null && history.getId() != null) {
                    byId.putIfAbsent(history.getId(), history);
                }
            }
        }
        List<RecordHistory> merged = new ArrayList<>(byId.values());
        merged.sort(Comparator.comparing(RecordHistory::getEndTime));
        if (merged.size() > limit) {
            return new ArrayList<>(merged.subList(0, limit));
        }
        return merged;
    }

    private CleanupResult cleanupHistories(List<RecordHistory> histories,
                                           CleanupOptions options,
                                           boolean dryRun,
                                           String source,
                                           int requestedHistoryCount,
                                           boolean limited) {
        return cleanupHistories(histories, options, dryRun, source, requestedHistoryCount, limited, false);
    }

    private CleanupResult cleanupHistories(List<RecordHistory> histories,
                                           CleanupOptions options,
                                           boolean dryRun,
                                           String source,
                                           int requestedHistoryCount,
                                           boolean limited,
                                           boolean dispatchableOnly) {
        CleanupResult result = CleanupResult.empty(dryRun);
        result.requestedHistoryCount = requestedHistoryCount;
        result.limited = limited;
        if (histories == null || histories.isEmpty()) {
            return result;
        }

        List<Long> historyIds = histories.stream()
                .map(RecordHistory::getId)
                .filter(Objects::nonNull)
                .toList();
        result.historyCount = historyIds.size();
        result.historyIds.addAll(historyIds);

        List<RecordHistoryPart> parts = findParts(historyIds);
        List<Long> partIds = parts.stream()
                .map(RecordHistoryPart::getId)
                .filter(Objects::nonNull)
                .toList();

        if (options.ordinary && !partIds.isEmpty()) {
            result.ordinary = dryRun
                    ? countPendingByPartIdsAndPool(partIds, 0, dispatchableOnly)
                    : markPendingByPartIdsAndPool(partIds, 0, dispatchableOnly);
            if (!dryRun && result.ordinary > 0) {
                LiveMsgSendSync.skipOrdinaryPartIds.addAll(partIds);
            }
        }
        if (options.advanced && !partIds.isEmpty()) {
            result.advanced = dryRun
                    ? countPendingByPartIdsAndPool(partIds, 1, dispatchableOnly)
                    : markPendingByPartIdsAndPool(partIds, 1, dispatchableOnly);
            if (!dryRun && result.advanced > 0) {
                LiveMsgSendSync.skipAdvancedPartIds.addAll(partIds);
            }
        }

        LocalDateTime now = LocalDateTime.now();
        Map<String, RecordRoom> roomCache = new LinkedHashMap<>();
        for (RecordHistory history : histories) {
            boolean changed = false;
            if (options.reply && history.isPublish() && !history.isSendReply()
                    && (!dispatchableOnly || isReplyDispatchable(history, roomCache))) {
                result.reply++;
                if (!dryRun) {
                    history.setSendReply(true);
                    changed = true;
                }
            }
            if (options.forceArchive && !history.isForceArchived()) {
                result.forceArchived++;
                if (!dryRun) {
                    history.setForceArchived(true);
                    history.setUpload(false);
                    history.setRecording(false);
                    history.setStreaming(false);
                    changed = true;
                }
            }
            if (changed) {
                history.setUpdateTime(now);
                historyRepository.save(history);
            }
        }

        if (!dryRun) {
            log.info("[BLR] {}", LogKvs.event("History.MsgQueueCleanup.Done")
                    .add("source", source)
                    .add("historyCount", result.historyCount)
                    .add("ordinary", result.ordinary)
                    .add("advanced", result.advanced)
                    .add("reply", result.reply)
                    .add("forceArchived", result.forceArchived)
                    .add("limited", result.limited));
        }
        return result;
    }

    private List<RecordHistoryPart> findParts(List<Long> historyIds) {
        List<RecordHistoryPart> parts = new ArrayList<>();
        for (int i = 0; i < historyIds.size(); i += IN_BATCH_SIZE) {
            List<Long> batch = historyIds.subList(i, Math.min(i + IN_BATCH_SIZE, historyIds.size()));
            parts.addAll(partRepository.findByHistoryIdIn(batch));
        }
        return parts;
    }

    private int countPendingByPartIdsAndPool(List<Long> partIds, int pool, boolean dispatchableOnly) {
        long total = 0;
        for (int i = 0; i < partIds.size(); i += IN_BATCH_SIZE) {
            List<Long> batch = partIds.subList(i, Math.min(i + IN_BATCH_SIZE, partIds.size()));
            total += dispatchableOnly
                    ? msgRepository.countDispatchablePendingByPartIdsAndPool(batch, pool)
                    : msgRepository.countPendingByPartIdsAndPool(batch, pool);
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    private int markPendingByPartIdsAndPool(List<Long> partIds, int pool, boolean dispatchableOnly) {
        int total = 0;
        for (int i = 0; i < partIds.size(); i += IN_BATCH_SIZE) {
            List<Long> batch = partIds.subList(i, Math.min(i + IN_BATCH_SIZE, partIds.size()));
            total += dispatchableOnly
                    ? msgRepository.markDispatchablePendingByPartIdsAndPool(batch, pool, ABANDONED_CODE)
                    : msgRepository.markPendingByPartIdsAndPool(batch, pool, ABANDONED_CODE);
        }
        return total;
    }

    private boolean isReplyDispatchable(RecordHistory history, Map<String, RecordRoom> roomCache) {
        if (history == null || history.isForceArchived() || (history.getCode() != 0 && history.getCode() != -50)) {
            return false;
        }
        String roomId = history.getRoomId();
        if (roomId == null || roomId.isBlank()) {
            return false;
        }
        RecordRoom room = roomCache.computeIfAbsent(roomId, roomRepository::findByRoomId);
        return room != null && (Boolean.TRUE.equals(room.getSendSc()) || Boolean.TRUE.equals(room.getSendGiftReply()));
    }

    private List<Long> normalizeIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Long> normalized = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id != null && id > 0) {
                normalized.add(id);
            }
        }
        return new ArrayList<>(normalized);
    }

    private boolean bool(Map<String, ?> raw, String key, boolean defaultValue) {
        if (raw == null || !raw.containsKey(key)) {
            return defaultValue;
        }
        Object value = raw.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private int clampInt(Object raw, int min, int max, int defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(String.valueOf(raw));
            return Math.max(min, Math.min(max, value));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public record CleanupOptions(boolean ordinary, boolean advanced, boolean reply, boolean forceArchive) {
        public static CleanupOptions all() {
            return new CleanupOptions(true, true, true, false);
        }
    }

    public static class CleanupResult {
        public boolean dryRun;
        public int requestedHistoryCount;
        public int historyCount;
        public int ordinary;
        public int advanced;
        public int reply;
        public int forceArchived;
        public int olderThanDays;
        public int limit;
        public long totalCandidates;
        public boolean limited;
        public final List<Long> historyIds = new ArrayList<>();

        public static CleanupResult empty(boolean dryRun) {
            CleanupResult result = new CleanupResult();
            result.dryRun = dryRun;
            return result;
        }

        public int totalMessages() {
            return ordinary + advanced;
        }

        public int totalActions() {
            return ordinary + advanced + reply + forceArchived;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("dryRun", dryRun);
            map.put("requestedHistoryCount", requestedHistoryCount);
            map.put("historyCount", historyCount);
            map.put("ordinary", ordinary);
            map.put("advanced", advanced);
            map.put("reply", reply);
            map.put("forceArchived", forceArchived);
            map.put("totalMessages", totalMessages());
            map.put("totalActions", totalActions());
            map.put("olderThanDays", olderThanDays);
            map.put("limit", limit);
            map.put("totalCandidates", totalCandidates);
            map.put("limited", limited);
            map.put("historyIds", historyIds);
            return map;
        }
    }
}
