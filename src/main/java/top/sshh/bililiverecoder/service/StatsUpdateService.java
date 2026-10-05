package top.sshh.bililiverecoder.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.repo.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class StatsUpdateService {
    private final StatsUpdateStore store;
    private final RecordHistoryRepository histories;
    private final RecordHistoryPartRepository parts;
    private final RecordRoomRepository rooms;
    private final RoomLiveSessionStatsRepository sessions;
    private final PartFileLocationService locations;
    private final org.springframework.beans.factory.ObjectProvider<StatsAggregationService> aggregation;
    private final DatabaseMaintenanceState maintenance;
    private final TaskExecutor executor;
    private final JdbcTemplate jdbc;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean scanning;
    private long cursor;
    private volatile LocalDateTime lastScanCompletedAt;
    private volatile long retryAfterNanos;
    private volatile int batchFailures;
    java.util.function.LongSupplier nanoTime = System::nanoTime;

    public StatsUpdateService(StatsUpdateStore store, RecordHistoryRepository histories,
            RecordHistoryPartRepository parts, RecordRoomRepository rooms, RoomLiveSessionStatsRepository sessions,
            PartFileLocationService locations, org.springframework.beans.factory.ObjectProvider<StatsAggregationService> aggregation,
            DatabaseMaintenanceState maintenance, @Qualifier("myAsyncPool") TaskExecutor executor, JdbcTemplate jdbc) {
        this.store = store; this.histories = histories; this.parts = parts; this.rooms = rooms;
        this.sessions = sessions; this.locations = locations; this.aggregation = aggregation;
        this.maintenance = maintenance; this.executor = executor; this.jdbc = jdbc;
    }

    public void requestAfterCommit(Long historyId) { store.afterCommitTransaction(() -> requestContent(historyId)); }

    public void requestContent(Long historyId) {
        request(historyId, StatsUpdateStore.CONTENT, true);
    }
    public void request(Long historyId, int reason, boolean rawChanged) {
        if (historyId == null) return;
        var history = histories.findById(historyId).orElse(null);
        if (history == null) { store.deleteHistory(historyId); return; }
        if (rooms.findByRoomId(history.getRoomId()) == null) return;
        var existing = sessions.findByHistoryId(historyId);
        if (existing != null && existing.isImportedSnapshot()) return;
        store.request(historyId, history.getRoomId(), reason, rawChanged);
        wakeAfterCommit();
    }

    public void reconcile(Long historyId) {
        if (historyId == null) return;
        var history = histories.findById(historyId).orElse(null);
        if (history == null) { store.deleteHistory(historyId); return; }
        inspect(history, new HashMap<>());
        wakeAfterCommit();
    }

    public void priceChanged(RoomLiveGiftCatalog catalog) {
        priceChanged(List.of(catalog));
    }

    public void priceChanged(Collection<RoomLiveGiftCatalog> catalogs) {
        // 同时检查跨房间的 ID 和名称兜底，但原始事件已有金额时不需要刷新
        Set<Integer> giftIds = new LinkedHashSet<>();
        Set<String> giftNames = new LinkedHashSet<>();
        for (var catalog : catalogs) {
            if (catalog.getGiftId() != null) giftIds.add(catalog.getGiftId());
            if (catalog.getGiftName() != null) giftNames.add(catalog.getGiftName());
        }
        Set<Long> affected = new TreeSet<>();
        collectPriceHistories("gift_id", new ArrayList<>(giftIds), affected);
        collectPriceHistories("gift_name", new ArrayList<>(giftNames), affected);
        // 先完成查询，再按历史 ID 顺序入队，同一场直播在这次目录同步中只请求一次
        store.requestPrices(affected);
        if (!affected.isEmpty()) wakeAfterCommit();
    }

    private void collectPriceHistories(String column, List<?> values, Set<Long> affected) {
        for (int offset = 0; offset < values.size(); offset += 200) {
            var group = values.subList(offset, Math.min(offset + 200, values.size()));
            List<Object> args = new ArrayList<>();
            args.add(RoomLiveEvent.TYPE_GIFT); args.addAll(group);
            affected.addAll(jdbc.queryForList("SELECT DISTINCT history_id FROM room_live_event WHERE type=? "
                    + "AND (gift_total_coin IS NULL OR gift_total_coin<=0) AND (gift_price_coin IS NULL OR gift_price_coin<=0) "
                    + "AND " + column + " IN (" + String.join(",", Collections.nCopies(group.size(), "?")) + ")",
                    Long.class, args.toArray()));
        }
    }

    private void wakeAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { wake(); }
            });
        } else wake();
    }

    public synchronized void startScan() {
        if (!scanning) { cursor = 0; scanning = true; }
        wake();
    }

    public void wake() {
        if (maintenance.isMaintenanceActive() || (batchFailures > 0 && nanoTime.getAsLong() - retryAfterNanos < 0)
                || !running.compareAndSet(false, true)) return;
        try { executor.execute(this::runBatch); }
        catch (RuntimeException rejected) {
            running.set(false);
            log.debug("统计后台执行器暂时不可用，待更新记录保留", rejected);
        }
    }

    void runBatch() {
        long started = System.nanoTime();
        try {
            if (maintenance.isMaintenanceActive()) return;
            if (scanning) scanPage(started);
            Map<Long, Boolean> rootHealth = new HashMap<>();
            for (var state : store.due(200)) {
                if (maintenance.isMaintenanceActive() || aggregation.getObject().manualTaskRunning()
                        || System.nanoTime() - started >= 5_000_000_000L) break;
                try { process(state, rootHealth); }
                catch (RuntimeException error) {
                    store.retry(state, error.getMessage());
                    log.warn("统计更新失败，稍后补偿 historyId={}", state.historyId(), error);
                }
                if (System.nanoTime() - started >= 5_000_000_000L) break;
            }
            if (!maintenance.isMaintenanceActive() && !aggregation.getObject().manualTaskRunning()
                    && System.nanoTime() - started < 5_000_000_000L) aggregation.getObject().drainDailyUpdates();
            batchFailures = 0;
        } catch (RuntimeException error) {
            long delaySeconds = deferFailedBatch();
            log.warn("统计后台批次失败，队列保留，{} 秒后重试", delaySeconds, error);
        } catch (Error error) {
            deferFailedBatch();
            // 原生反射错误等会绕过 RuntimeException，先写入日志再交还线程处理
            log.error("统计后台发生严重错误，队列保留，请检查原生程序反射配置或运行环境", error);
            throw error;
        } finally { running.set(false); }
    }

    private long deferFailedBatch() {
        long[] delays = {30, 60, 300};
        long seconds = delays[Math.min(batchFailures, delays.length - 1)];
        batchFailures = Math.min(batchFailures + 1, delays.length);
        retryAfterNanos = nanoTime.getAsLong() + seconds * 1_000_000_000L;
        return seconds;
    }

    private void scanPage(long started) {
        var page = histories.findByIdGreaterThanOrderByIdAsc(cursor, PageRequest.of(0, 200));
        Map<Long, Boolean> rootHealth = new HashMap<>();
        for (var history : page) {
            if (maintenance.isMaintenanceActive() || aggregation.getObject().manualTaskRunning()) return;
            inspect(history, rootHealth);
            cursor = history.getId();
            if (System.nanoTime() - started >= 5_000_000_000L) return;
        }
        if (page.size() < 200) {
            store.pruneDeleted();
            lastScanCompletedAt = LocalDateTime.now();
            scanning = false;
        }
    }

    private void inspect(RecordHistory history, Map<Long, Boolean> roots) {
        if (history.getEndTime() == null || history.isRecording() || history.isStreaming()
                || rooms.findByRoomId(history.getRoomId()) == null) return;
        var before = store.find(history.getId());
        Snapshot snapshot = snapshot(history, before, roots);
        var stats = sessions.findByHistoryId(history.getId());
        store.observe(history.getId(), history.getRoomId(), snapshot.content(), snapshot.metadata(),
                stats != null && stats.getStatsVersion() >= StatsAggregationService.STATS_VERSION,
                stats != null && stats.isImportedSnapshot());
        if (before == null && stats != null && !stats.isImportedSnapshot()) {
            var room = rooms.findByRoomId(history.getRoomId());
            if (!Objects.equals(stats.getTitle(), history.getTitle()) || !Objects.equals(stats.getBvId(), history.getBvId())
                    || stats.isPublished() != history.isPublish() || stats.getPublishCode() != history.getCode()
                    || stats.isUploadEnabled() != history.isUpload() || stats.isSendReply() != history.isSendReply()
                    || !Objects.equals(stats.getRoomId(), history.getRoomId())
                    || !Objects.equals(stats.getUname(), room == null ? null : room.getUname())) {
                store.request(history.getId(), history.getRoomId(), StatsUpdateStore.METADATA, false);
            }
            if (stats.getStatsUpdatedAt() != null && snapshot.newestFileAt() > stats.getStatsUpdatedAt()
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()) {
                store.request(history.getId(), history.getRoomId(), StatsUpdateStore.CONTENT, false);
            }
        }
    }

    private void process(StatsUpdateStore.State state, Map<Long, Boolean> roots) {
        var history = histories.findById(state.historyId()).orElse(null);
        if (history == null) { store.deleteHistory(state.historyId()); return; }
        if (rooms.findByRoomId(history.getRoomId()) == null) { store.deleteHistory(history.getId()); return; }
        if (history.isDeletePending()) { store.retry(state, "历史记录正在删除"); return; }
        var imported = sessions.findByHistoryId(history.getId());
        if (imported != null && imported.isImportedSnapshot()) {
            store.observe(history.getId(), history.getRoomId(), "", "", true, true);
            return;
        }
        Snapshot input = snapshot(history, state, roots);
        var ticket = new StatsUpdateStore.Ticket(state.historyId(), state.requested(), input.content(), input.metadata(), state.rawRevision());
        store.activate(ticket);
        try {
            boolean content = !Objects.equals(state.blockedXml() ? state.observedContent() : state.appliedContent(), input.content());
            boolean complete = aggregation.getObject().updateQueuedHistory(history, input.parts(), content,
                    (state.reasons() & StatsUpdateStore.PRICE) != 0 && state.rawRevision() == state.appliedRawRevision(), input.available());
            if (!complete) store.retry(state, "录制、统计锁或 XML 源暂时不可用");
        } finally { store.deactivate(); }
    }

    Snapshot snapshot(RecordHistory history, StatsUpdateStore.State state, Map<Long, Boolean> roots) {
        List<RecordHistoryPart> items = parts.findByHistoryIdOrderByStartTimeAsc(history.getId());
        List<Object> content = new ArrayList<>();
        content.add(history.getRoomId());
        content.add(history.getStartTime()); content.add(history.getEndTime());
        content.add(state == null ? 0L : state.rawRevision());
        boolean available = true;
        long newestFileAt = 0;
        List<Object> metadata = new ArrayList<>(Arrays.asList(history.getRoomId(), history.getTitle(), history.getBvId(),
                history.isUpload(), history.isPublish(), history.getCode(), history.isSendReply(), history.getFileSize()));
        var room = rooms.findByRoomId(history.getRoomId());
        metadata.add(room == null ? null : room.getUname());
        for (var part : items) {
            content.addAll(Arrays.asList(part.getId(), part.getStartTime(), part.getEndTime(), part.getDuration(), part.isRecording()));
            metadata.addAll(Arrays.asList(part.getId(), part.getFileSize()));
            var xml = locations.inspectCompanionState(part.getId(), ".xml", roots);
            content.add(xml.state()); content.add(xml.expectedPath()); content.add(xml.path());
            if (xml.available()) {
                try {
                    var attrs = Files.readAttributes(xml.path(), java.nio.file.attribute.BasicFileAttributes.class);
                    long modified = attrs.lastModifiedTime().toMillis();
                    newestFileAt = Math.max(newestFileAt, modified);
                    content.add(attrs.size()); content.add(modified);
                } catch (java.io.IOException unavailable) { content.add("unreadable"); available = false; }
            } else available = false;
        }
        return new Snapshot(hash(content), hash(metadata), items, available, newestFileAt);
    }

    private String hash(List<Object> values) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                String text = value == null ? "-1:" : value.toString().length() + ":" + value;
                digest.update(text.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    // 只取消清理开始前的旧历史，扫描期间到达的新变化仍然保留
    public void suppressExisting() {
        Long lastExisting = jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM record_history", Long.class);
        long after = 0;
        List<RecordHistory> page;
        do {
            page = histories.findByIdGreaterThanOrderByIdAsc(after, PageRequest.of(0, 200));
            Map<Long, Boolean> roots = new HashMap<>();
            for (var history : page) {
                if (history.getId() > lastExisting) { after = lastExisting; break; }
                var input = snapshot(history, store.find(history.getId()), roots);
                store.observe(history.getId(), history.getRoomId(), input.content(), input.metadata(), true, false);
                var current = store.find(history.getId());
                if (current != null) store.suppress(history.getId(), current.requested());
                after = history.getId();
            }
        } while (page.size() == 200 && after < lastExisting);
        store.cancelDaily();
    }
    public void restoreManual(Long id) { request(id, StatsUpdateStore.CONTENT, false); }
    public boolean currentAllowed() { return store.activeAllowed(); }
    public boolean hasCurrent() { return store.hasActive(); }
    public void prepareManual(RecordHistory history) {
        store.request(history.getId(), history.getRoomId(), StatsUpdateStore.CONTENT, false);
        var state = store.find(history.getId());
        var input = snapshot(history, state, new HashMap<>());
        store.activate(new StatsUpdateStore.Ticket(history.getId(), state.requested(), input.content(), input.metadata(), state.rawRevision()));
    }
    public void endManual() { store.deactivate(); }
    public void blockCurrentXml() { store.blockActiveXml(); }
    public void drainDaily(StatsAggregationService service) {
        long started = System.nanoTime();
        for (var daily : store.dailyDue()) {
            if (maintenance.isMaintenanceActive() || service.manualTaskRunning()
                    || System.nanoTime() - started >= 5_000_000_000L) break;
            if (!service.updateQueuedDaily(daily, rooms.findByRoomId(daily.roomId()))) break;
        }
    }
    public void commitDaily(StatsUpdateStore.Daily daily, Runnable work) { store.dailyTransaction(daily, work); }
    public void drainManualDaily(StatsAggregationService service, java.util.function.BiConsumer<Integer, Integer> progress) {
        int total = (int)Math.min(Integer.MAX_VALUE, store.pendingDailyCount());
        int processed = 0;
        List<StatsUpdateStore.Daily> page;
        do {
            page = store.dailyDue();
            for (var daily : page) {
                progress.accept(processed, total);
                store.dailyTransaction(daily, () -> service.recomputeDailyStats(daily.roomId(), daily.date(),
                        rooms.findByRoomId(daily.roomId()), LocalDateTime.now()));
                progress.accept(++processed, total);
            }
        } while (page.size() == 200);
    }
    public void deleteRoom(String roomId) { store.deleteRoom(roomId); }
    public Map<String,Object> status() { return store.status(running.get(), lastScanCompletedAt); }
    record Snapshot(String content, String metadata, List<RecordHistoryPart> parts, boolean available, long newestFileAt) {}
}
