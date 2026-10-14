package top.sshh.bililiverecoder.service;

import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordRoom;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;

/** 按本地下播间隔归组，上游会话标识只用于追踪事件 */
public final class LiveSessionPolicy {
    private LiveSessionPolicy() {}

    public static boolean begin(RecordRoom room, RecordHistory previous, Date timestamp,
                                LocalDateTime now, int mergeMinutes) {
        if (isOlderEvent(room, timestamp)) return false;
        LocalDateTime ended = room.getLiveEndedAt();
        boolean firstObservation = room.getLiveGroup() == null;
        if (firstObservation && previous != null) {
            // 升级前没有独立下播时间时，只用旧稿件做一次归组初始化
            if (previous.isSplitClosed() || !room.isRecording() && !room.isStreaming()) ended = previous.getEndTime();
        }
        boolean recent = ended == null || !now.isAfter(ended.plusMinutes(mergeMinutes));
        if (firstObservation || !recent) {
            room.setLiveGroup(recent && previous != null && !HistoryProcessingPolicy.blocksAutomatic(previous) && previous.getSplitGroup() != null
                    ? previous.getSplitGroup() : UUID.randomUUID().toString());
        }
        LocalDateTime eventAt = eventTime(timestamp, now);
        if (eventAt != null && (room.getLiveEventAt() == null || ended != null)) room.setLiveEventAt(eventAt);
        room.setLiveEndedAt(null);
        return true;
    }

    public static boolean owns(RecordRoom room, RecordHistory history) {
        return room != null && history != null && room.getLiveGroup() != null
                && Objects.equals(room.getRoomId(), history.getRoomId())
                && Objects.equals(room.getLiveGroup(), history.getSplitGroup());
    }

    public static boolean isOlderEvent(RecordRoom room, Date timestamp) {
        return room != null && room.getLiveEventAt() != null && timestamp != null
                && timestamp.toInstant().isAfter(java.time.Instant.EPOCH)
                && LocalDateTime.ofInstant(timestamp.toInstant(), ZoneId.systemDefault())
                .isBefore(room.getLiveEventAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }

    public static void end(RecordRoom room, LocalDateTime now) {
        end(room, null, now);
    }

    public static void end(RecordRoom room, Date timestamp, LocalDateTime now) {
        // 下播和录制结束可能先后到达，后一个事件不能延长合并窗口
        if (room.getLiveEndedAt() == null) {
            room.setLiveEndedAt(now);
            LocalDateTime eventAt = eventTime(timestamp, now);
            if (eventAt != null && !isOlderEvent(room, timestamp)) room.setLiveEventAt(eventAt);
        }
    }

    private static LocalDateTime eventTime(Date timestamp, LocalDateTime now) {
        if (timestamp == null || !timestamp.toInstant().isAfter(java.time.Instant.EPOCH)) return null;
        LocalDateTime time = LocalDateTime.ofInstant(timestamp.toInstant(), ZoneId.systemDefault());
        return time.isAfter(now) ? null : time;
    }
}
