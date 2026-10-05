package top.sshh.bililiverecoder.config;

import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.service.StatsUpdateService;
import top.sshh.bililiverecoder.service.StatsUpdateStore;
import java.util.*;

/** 只监听影响统计的字段，入队跟随调用方事务，提交后再唤醒后台 */
@Component
public class StatsRepositoryNotifications implements BeanPostProcessor {
    private final ObjectProvider<StatsUpdateService> updates;
    private final ObjectProvider<StatsUpdateStore> store;
    private final ObjectProvider<JdbcTemplate> database;

    public StatsRepositoryNotifications(ObjectProvider<StatsUpdateService> updates,
            ObjectProvider<StatsUpdateStore> store, ObjectProvider<JdbcTemplate> database) {
        this.updates = updates; this.store = store; this.database = database;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String name) {
        if (!(bean instanceof Advised proxy) || !Set.of("recordHistoryRepository", "recordHistoryPartRepository",
                "roomLiveEventParseStateRepository", "liveMsgRepository", "recordRoomRepository").contains(name)) return bean;
        proxy.addAdvice(0, (MethodInterceptor) invocation -> {
            String method = invocation.getMethod().getName();
            Object[] args = invocation.getArguments();
            Set<Long> previous = new HashSet<>();
            Map<String, String> previousNames = new HashMap<>();
            // 调用方可能已经改过托管实体，所以从库中读取真正的旧值
            Map<Long, List<?>> previousHistories = new HashMap<>();
            Map<Long, List<?>> previousParts = new HashMap<>();
            Map<Long, String> previousMessages = new HashMap<>();
            if (method.startsWith("save") && args.length > 0) for (Object value : values(args[0])) {
                if (value instanceof RecordHistory history && history.getId() != null) {
                    var old = database.getObject().query("SELECT room_id,title,bv_id,upload,publish,code,send_reply,recording,streaming,file_size,start_time,end_time FROM record_history WHERE id=?", (r,n) -> {
                        var h = new RecordHistory(); h.setRoomId(r.getString("room_id")); h.setTitle(r.getString("title"));
                        h.setBvId(r.getString("bv_id")); h.setUpload(r.getBoolean("upload")); h.setPublish(r.getBoolean("publish"));
                        h.setCode(r.getInt("code")); h.setSendReply(r.getBoolean("send_reply")); h.setRecording(r.getBoolean("recording"));
                        h.setStreaming(r.getBoolean("streaming")); h.setFileSize(r.getLong("file_size"));
                        var start = r.getTimestamp("start_time"); var end = r.getTimestamp("end_time");
                        h.setStartTime(start == null ? null : start.toLocalDateTime()); h.setEndTime(end == null ? null : end.toLocalDateTime());
                        return historyValues(h);
                    }, history.getId());
                    if (!old.isEmpty()) previousHistories.put(history.getId(), old.get(0));
                } else if (value instanceof RecordHistoryPart part && part.getId() != null) {
                    var old = database.getObject().query("SELECT history_id,duration,file_size,recording,part_order,page,start_time,end_time FROM record_history_part WHERE id=?", (r,n) -> {
                        var partBefore = new RecordHistoryPart(); partBefore.setHistoryId(r.getObject("history_id", Long.class));
                        partBefore.setDuration(r.getFloat("duration")); partBefore.setFileSize(r.getLong("file_size"));
                        partBefore.setRecording(r.getBoolean("recording")); partBefore.setPartOrder(r.getObject("part_order", Integer.class));
                        partBefore.setPage(r.getInt("page"));
                        var start = r.getTimestamp("start_time"); var end = r.getTimestamp("end_time");
                        partBefore.setStartTime(start == null ? null : start.toLocalDateTime()); partBefore.setEndTime(end == null ? null : end.toLocalDateTime());
                        return partValues(partBefore);
                    }, part.getId());
                    if (!old.isEmpty()) previousParts.put(part.getId(), old.get(0));
                } else if (value instanceof LiveMsg msg && msg.getId() != null) {
                    var old = database.getObject().query("SELECT part_id,pool,send_time FROM live_msg WHERE id=?", (r,n) ->
                            r.getObject("part_id") + ":" + r.getInt("pool") + ":" + r.getObject("send_time"), msg.getId());
                    if (!old.isEmpty()) previousMessages.put(msg.getId(), old.get(0));
                }
            }
            if (name.equals("recordRoomRepository") && method.startsWith("save") && args.length > 0) {
                for (Object value : values(args[0])) if (value instanceof RecordRoom room) {
                    var old = database.getObject().queryForList("SELECT uname FROM record_room WHERE room_id=?", String.class, room.getRoomId());
                    previousNames.put(room.getRoomId(), old.isEmpty() ? null : old.get(0));
                }
            }
            if (name.equals("recordHistoryPartRepository") && method.startsWith("save") && args.length > 0) {
                for (Object value : values(args[0])) {
                    if (value instanceof RecordHistoryPart part && part.getId() != null && previousParts.containsKey(part.getId())) {
                        Object oldHistory = previousParts.get(part.getId()).get(0);
                        if (!Objects.equals(oldHistory, part.getHistoryId())) previous.add((Long) oldHistory);
                    }
                }
            }
            if (name.equals("liveMsgRepository") && method.equals("deleteByPartId") && args.length > 0) {
                previous.addAll(database.getObject().queryForList("SELECT history_id FROM record_history_part WHERE id=?", Long.class, args[0]));
            }
            if (method.equals("deleteByHistoryId") && args.length > 0 && (name.equals("recordHistoryPartRepository") || name.equals("liveMsgRepository"))) previous.add((Long) args[0]);
            if (name.equals("recordHistoryPartRepository") && method.equals("deleteById") && args.length > 0) previous.addAll(database.getObject()
                    .queryForList("SELECT history_id FROM record_history_part WHERE id=?", Long.class, args[0]));
            if (name.equals("liveMsgRepository") && method.equals("delete") && args.length > 0 && args[0] instanceof LiveMsg msg) previous.addAll(database.getObject()
                    .queryForList("SELECT history_id FROM record_history_part WHERE id=?", Long.class, msg.getPartId()));
            Object result = invocation.proceed();
            if (method.startsWith("save")) for (Object value : values(result)) {
                if (value instanceof RecordHistory history && !Objects.equals(previousHistories.get(history.getId()), historyValues(history))) updates.getObject().reconcile(history.getId());
                else if (value instanceof RecordHistoryPart part && !Objects.equals(previousParts.get(part.getId()), partValues(part))) updates.getObject().reconcile(part.getHistoryId());
                else if (value instanceof RoomLiveEventParseState state && state.isSuccess()) updates.getObject().requestContent(state.getHistoryId());
                else if (value instanceof LiveMsg msg && !Objects.equals(previousMessages.get(msg.getId()),
                        msg.getPartId() + ":" + msg.getPool() + ":" + msg.getSendTime())) previous.addAll(database.getObject()
                        .queryForList("SELECT history_id FROM record_history_part WHERE id=?", Long.class, msg.getPartId()));
                else if (value instanceof RecordRoom room && !Objects.equals(previousNames.get(room.getRoomId()), room.getUname())) {
                    for (Long id : database.getObject().queryForList("SELECT id FROM record_history WHERE room_id=? AND end_time IS NOT NULL", Long.class, room.getRoomId())) {
                        updates.getObject().request(id, StatsUpdateStore.METADATA, false);
                    }
                }
            }
            for (Long id : previous) {
                if (name.equals("liveMsgRepository")) updates.getObject().requestContent(id);
                else updates.getObject().reconcile(id);
            }
            if (name.equals("recordHistoryRepository") && args.length > 0) {
                if (method.equals("deleteById")) store.getObject().deleteHistory((Long) args[0]);
                else if (method.equals("delete")) store.getObject().deleteHistory(((RecordHistory) args[0]).getId());
                else if (method.equals("deleteAll")) for (Object value : values(args[0])) store.getObject().deleteHistory(((RecordHistory) value).getId());
            }
            return result;
        });
        return bean;
    }

    private List<?> historyValues(RecordHistory h) {
        return Arrays.asList(h.getRoomId(), h.getTitle(), h.getBvId(), h.isUpload(), h.isPublish(), h.getCode(),
                h.isSendReply(), h.isRecording(), h.isStreaming(), h.getFileSize(), h.getStartTime(), h.getEndTime());
    }
    private List<?> partValues(RecordHistoryPart p) {
        return Arrays.asList(p.getHistoryId(), p.getDuration(), p.getFileSize(), p.isRecording(), p.getPartOrder(),
                p.getPage(), p.getStartTime(), p.getEndTime());
    }

    private Iterable<?> values(Object value) {
        if (value == null) return List.of();
        return value instanceof Iterable<?> iterable ? iterable : List.of(value);
    }
}
