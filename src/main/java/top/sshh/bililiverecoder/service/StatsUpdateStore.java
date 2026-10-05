package top.sshh.bililiverecoder.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;

@Service
public class StatsUpdateStore {
    public static final int CONTENT = 1, METADATA = 2, PRICE = 4;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TransactionTemplate afterCommitTx;
    private final boolean mysql;
    private final ThreadLocal<Ticket> active = new ThreadLocal<>();

    public StatsUpdateStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        tx = new TransactionTemplate(manager);
        afterCommitTx = new TransactionTemplate(manager);
        afterCommitTx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        mysql = Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)
                c -> c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql")));
    }

    public void afterCommitTransaction(Runnable work) { afterCommitTx.executeWithoutResult(status -> work.run()); }

    private void ensure(Long historyId, String roomId) {
        if (mysql) jdbc.update("INSERT IGNORE INTO stats_update_state(history_id,room_id) VALUES (?,?)", historyId, roomId);
        else jdbc.update("MERGE INTO stats_update_state t USING (VALUES (?,?)) s(history_id,room_id) "
                + "ON t.history_id=s.history_id WHEN NOT MATCHED THEN INSERT(history_id,room_id) VALUES(s.history_id,s.room_id)", historyId, roomId);
    }

    public State find(Long id) {
        List<State> states = jdbc.query("SELECT * FROM stats_update_state WHERE history_id=?", this::read, id);
        return states.isEmpty() ? null : states.get(0);
    }

    public void request(Long id, String roomId, int reason, boolean rawDataChanged) {
        if (id == null) return;
        tx.executeWithoutResult(status -> {
            if (jdbc.queryForList("SELECT id FROM record_history WHERE id=? FOR UPDATE", Long.class, id).isEmpty()) return;
            ensure(id, roomId);
            State s = jdbc.queryForObject("SELECT * FROM stats_update_state WHERE history_id=? FOR UPDATE", this::read, id);
            jdbc.update("UPDATE stats_update_state SET room_id=?, requested_revision=requested_revision+1, "
                    + "raw_revision=raw_revision+?, reasons=?, suppressed=false, blocked_xml=?, "
                    + "applied_content=CASE WHEN ? THEN NULL ELSE applied_content END, retry_at=NULL, failures=0, last_error=NULL WHERE history_id=?",
                    roomId, rawDataChanged ? 1 : 0, s.reasons() | reason,
                    s.blockedXml() && !rawDataChanged && (reason & CONTENT) == 0,
                    !rawDataChanged && (reason & CONTENT) != 0, id);
        });
    }

    public void observe(Long id, String roomId, String content, String metadata, boolean existing, boolean protect) {
        // 无变化的扫描只读，不跟正在投稿或同步目录的事务争抢历史行锁
        State observed = find(id);
        if (observed != null && (protect ? observed.suppressed()
                : Objects.equals(roomId, observed.roomId())
                && Objects.equals(content, observed.observedContent())
                && Objects.equals(metadata, observed.observedMetadata()))) return;
        tx.executeWithoutResult(status -> {
            if (jdbc.queryForList("SELECT id FROM record_history WHERE id=? FOR UPDATE", Long.class, id).isEmpty()) return;
            ensure(id, roomId);
            State s = jdbc.queryForObject("SELECT * FROM stats_update_state WHERE history_id=? FOR UPDATE", this::read, id);
            if (protect) {
                if (!s.suppressed()) jdbc.update("UPDATE stats_update_state SET suppressed=true, applied_revision=requested_revision, reasons=0 WHERE history_id=?", id);
                return;
            }
            if (s.observedContent() == null && existing && (s.reasons() & CONTENT) == 0) {
                jdbc.update("UPDATE stats_update_state SET observed_content=?, observed_metadata=?, applied_content=?, applied_metadata=? WHERE history_id=?",
                        content, metadata, content, metadata, id);
                return;
            }
            boolean c = !Objects.equals(content, s.observedContent());
            boolean m = !Objects.equals(metadata, s.observedMetadata());
            if (!c && !m) return;
            // 清理后的首次建基线不恢复旧缓存，后续真实变化才重新入队
            boolean keepSuppressed = s.suppressed() && s.observedContent() == null;
            jdbc.update("UPDATE stats_update_state SET room_id=?, observed_content=?, observed_metadata=?, "
                    + "requested_revision=requested_revision+?, reasons=?, suppressed=?, blocked_xml=?, retry_at=NULL, failures=0, last_error=NULL WHERE history_id=?",
                    roomId, content, metadata, keepSuppressed ? 0 : 1,
                    keepSuppressed ? 0 : s.reasons() | (c ? CONTENT : 0) | (m ? METADATA : 0), keepSuppressed,
                    s.blockedXml() && !c, id);
        });
    }

    public void requestPrices(Collection<Long> histories) {
        List<Long> ids = histories.stream().filter(Objects::nonNull).distinct().sorted().toList();
        for (int offset = 0; offset < ids.size(); offset += 200) {
            var group = ids.subList(offset, Math.min(offset + 200, ids.size()));
            // 补价只更新队列表，不锁历史行，提交结果时仍会检查历史是否存在和正在删除
            String source = "SELECT h.id AS history_id,h.room_id FROM record_history h "
                    + "WHERE h.id IN (" + String.join(",", Collections.nCopies(group.size(), "?")) + ") "
                    + "AND EXISTS (SELECT 1 FROM record_room r WHERE r.room_id=h.room_id) "
                    + "AND NOT EXISTS (SELECT 1 FROM room_live_session_stats s WHERE s.history_id=h.id AND s.imported_snapshot=true)";
            tx.executeWithoutResult(status -> {
                if (mysql) {
                    jdbc.update("INSERT INTO stats_update_state(history_id,room_id,requested_revision,reasons) "
                                    + "SELECT p.history_id,p.room_id,1,4 FROM (" + source + ") p "
                                    + "ON DUPLICATE KEY UPDATE room_id=VALUES(room_id),requested_revision=requested_revision+1, "
                                    + "reasons=CASE WHEN reasons<4 THEN reasons+4 ELSE reasons END,suppressed=false, "
                                    + "retry_at=NULL,failures=0,last_error=NULL", group.toArray());
                } else {
                    jdbc.update("MERGE INTO stats_update_state t USING (" + source + ") p ON t.history_id=p.history_id "
                                    + "WHEN MATCHED THEN UPDATE SET room_id=p.room_id,requested_revision=t.requested_revision+1, "
                                    + "reasons=CASE WHEN t.reasons<4 THEN t.reasons+4 ELSE t.reasons END,suppressed=false, "
                                    + "retry_at=NULL,failures=0,last_error=NULL "
                                    + "WHEN NOT MATCHED THEN INSERT(history_id,room_id,requested_revision,reasons) VALUES(p.history_id,p.room_id,1,4)",
                            group.toArray());
                }
            });
        }
    }

    public List<State> due(int limit) {
        return jdbc.query("SELECT * FROM stats_update_state WHERE suppressed=false AND requested_revision>applied_revision "
                + "AND (blocked_xml=false OR reasons>=2) AND (retry_at IS NULL OR retry_at<=?) ORDER BY history_id LIMIT ?", this::read, LocalDateTime.now(), limit);
    }

    public void activate(Ticket ticket) { active.set(ticket); }
    public boolean activeAllowed() {
        var ticket = active.get();
        if (ticket == null) return true;
        var state = find(ticket.historyId());
        return state != null && !state.suppressed() && state.applied() < ticket.revision()
                && state.requested() >= ticket.revision();
    }
    public boolean hasActive() { return active.get() != null; }
    public void deactivate() { active.remove(); }
    public void blockActiveXml() {
        Ticket ticket = active.get();
        if (ticket != null) jdbc.update("UPDATE stats_update_state SET blocked_xml=true, reasons=1, observed_content=?, retry_at=NULL, "
                        + "last_error=? WHERE history_id=? AND requested_revision=?",
                ticket.content(), "XML 解析问题已缓存，等待文件变化或手动复检", ticket.historyId(), ticket.revision());
    }
    public void completeMetadataActive() {
        Ticket ticket = active.get();
        if (ticket == null) return;
        var current = find(ticket.historyId());
        if (current != null && current.blockedXml()) {
            jdbc.update("UPDATE stats_update_state SET reasons=1, applied_metadata=?, observed_metadata=?, completed_at=? "
                            + "WHERE history_id=? AND requested_revision=? AND blocked_xml=true",
                    ticket.metadata(), ticket.metadata(), LocalDateTime.now(), ticket.historyId(), ticket.revision());
        } else complete(ticket);
    }
    public void completeActive() {
        Ticket ticket = active.get();
        if (ticket != null) complete(ticket);
    }
    // 计算期间的新请求保留新版本，完成旧版本不能把它们清掉
    public void complete(Ticket ticket) {
        jdbc.update("UPDATE stats_update_state SET applied_revision=?, applied_content=?, applied_metadata=?, applied_raw_revision=?, "
                + "reasons=CASE WHEN requested_revision=? THEN 0 ELSE reasons END, "
                + "observed_content=CASE WHEN requested_revision=? THEN ? ELSE observed_content END, "
                + "observed_metadata=CASE WHEN requested_revision=? THEN ? ELSE observed_metadata END, "
                + "blocked_xml=false, retry_at=NULL, failures=0, last_error=NULL, completed_at=? WHERE history_id=? AND applied_revision<?",
                ticket.revision(), ticket.content(), ticket.metadata(), ticket.rawRevision(), ticket.revision(), ticket.revision(), ticket.content(),
                ticket.revision(), ticket.metadata(), LocalDateTime.now(), ticket.historyId(), ticket.revision());
    }

    public void retry(State state, String message) {
        long[] seconds = {5, 30, 60, 300};
        jdbc.update("UPDATE stats_update_state SET failures=failures+1, retry_at=?, last_error=? WHERE history_id=? AND requested_revision=?",
                LocalDateTime.now().plusSeconds(seconds[Math.min(state.failures(), seconds.length - 1)]),
                message == null ? null : message.substring(0, Math.min(512, message.length())), state.historyId(), state.requested());
    }

    public void daily(String roomId, LocalDate date) {
        if (roomId == null || date == null) return;
        if (mysql) jdbc.update("INSERT INTO stats_daily_update_state(room_id,live_date,requested_revision) VALUES (?,?,1) "
                + "ON DUPLICATE KEY UPDATE requested_revision=requested_revision+1", roomId, date);
        else jdbc.update("MERGE INTO stats_daily_update_state t USING (VALUES (?,?)) s(room_id,live_date) "
                + "ON t.room_id=s.room_id AND t.live_date=s.live_date "
                + "WHEN MATCHED THEN UPDATE SET requested_revision=t.requested_revision+1 "
                + "WHEN NOT MATCHED THEN INSERT(room_id,live_date,requested_revision) VALUES(s.room_id,s.live_date,1)", roomId, date);
    }

    public long pendingDailyCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM stats_daily_update_state WHERE requested_revision>applied_revision", Long.class);
    }
    public List<Daily> dailyDue() {
        return jdbc.query("SELECT * FROM stats_daily_update_state WHERE requested_revision>applied_revision ORDER BY live_date,room_id LIMIT 200",
                (r,n) -> new Daily(r.getString("room_id"), r.getDate("live_date").toLocalDate(), r.getLong("requested_revision")));
    }
    public void completeDaily(Daily d) {
        jdbc.update("UPDATE stats_daily_update_state SET applied_revision=? WHERE room_id=? AND live_date=? AND applied_revision<?",
                d.revision(), d.roomId(), d.date(), d.revision());
    }
    public void dailyTransaction(Daily d, Runnable work) {
        tx.executeWithoutResult(status -> { work.run(); completeDaily(d); });
    }
    public void suppress(Long id, long revision) {
        jdbc.update("UPDATE stats_update_state SET suppressed=true, applied_revision=requested_revision, reasons=0, "
                + "retry_at=NULL, last_error=NULL WHERE history_id=? AND requested_revision=?", id, revision);
    }
    public void cancelDaily() { jdbc.update("UPDATE stats_daily_update_state SET applied_revision=requested_revision"); }
    public void suppressAll() {
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE stats_update_state SET suppressed=true, applied_revision=requested_revision, reasons=0, retry_at=NULL, last_error=NULL");
            jdbc.update("UPDATE stats_daily_update_state SET applied_revision=requested_revision");
        });
    }
    public void deleteHistory(Long id) { jdbc.update("DELETE FROM stats_update_state WHERE history_id=?", id); }
    public void deleteRoom(String roomId) {
        jdbc.update("DELETE FROM stats_update_state WHERE room_id=?", roomId);
        jdbc.update("DELETE FROM stats_daily_update_state WHERE room_id=?", roomId);
    }
    public void pruneDeleted() {
        jdbc.update("DELETE FROM stats_update_state WHERE NOT EXISTS (SELECT 1 FROM record_history h WHERE h.id=stats_update_state.history_id)");
    }
    public Map<String,Object> status(boolean running, LocalDateTime scannedAt) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("pendingCount", jdbc.queryForObject("SELECT COUNT(*) FROM stats_update_state WHERE suppressed=false AND requested_revision>applied_revision", Long.class));
        result.put("retryCount", jdbc.queryForObject("SELECT COUNT(*) FROM stats_update_state WHERE suppressed=false AND requested_revision>applied_revision AND (failures>0 OR blocked_xml=true)", Long.class));
        result.put("running", running);
        result.put("lastCompletedAt", jdbc.queryForObject("SELECT MAX(completed_at) FROM stats_update_state", LocalDateTime.class));
        result.put("lastScanCompletedAt", scannedAt);
        return result;
    }
    private State read(ResultSet r, int row) throws SQLException {
        var retry = r.getTimestamp("retry_at");
        return new State(r.getLong("history_id"), r.getString("room_id"), r.getLong("requested_revision"),
                r.getLong("applied_revision"), r.getLong("raw_revision"), r.getInt("reasons"), r.getBoolean("suppressed"),
                r.getString("observed_content"), r.getString("observed_metadata"), r.getString("applied_content"),
                r.getString("applied_metadata"), r.getInt("failures"), retry == null ? null : retry.toLocalDateTime(), r.getBoolean("blocked_xml"), r.getLong("applied_raw_revision"));
    }
    public record State(Long historyId, String roomId, long requested, long applied, long rawRevision, int reasons,
                        boolean suppressed, String observedContent, String observedMetadata, String appliedContent,
                        String appliedMetadata, int failures, LocalDateTime retryAt, boolean blockedXml, long appliedRawRevision) {}
    public record Ticket(Long historyId, long revision, String content, String metadata, long rawRevision) {}
    public record Daily(String roomId, LocalDate date, long revision) {}
}
