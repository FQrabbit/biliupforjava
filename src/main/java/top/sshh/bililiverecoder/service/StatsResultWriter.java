package top.sshh.bililiverecoder.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.repo.*;
import java.util.*;

/** 短事务只保存实际差异，并登记需要更新的日汇总 */
@Service
@RequiredArgsConstructor
public class StatsResultWriter {
    private final RoomLiveSessionStatsRepository sessions;
    private final RoomLiveMsgBucketStatsRepository buckets;
    private final RoomLiveDailyStatsRepository daily;
    private final StatsUpdateStore updates;
    private final RecordHistoryRepository histories;

    private boolean canCommit(Long id) {
        if (!updates.hasActive()) return true;
        if (!updates.activeAllowed()) return false;
        var history = histories.findByIdForUpdate(id).orElse(null);
        if (history == null) { updates.deleteHistory(id); return false; }
        return !history.isDeletePending();
    }

    @Transactional
    public boolean save(RoomLiveSessionStats candidate, Collection<RoomLiveMsgBucketStats> computed) {
        if (!canCommit(candidate.getHistoryId())) return false;
        RoomLiveSessionStats previous = sessions.findByHistoryId(candidate.getHistoryId());
        if (previous != null && previous.isImportedSnapshot()) return false;
        Map<Integer, RoomLiveMsgBucketStats> old = new HashMap<>();
        buckets.findByHistoryIdOrderByBucketIndexAsc(candidate.getHistoryId())
                .forEach(bucket -> old.put(bucket.getBucketIndex(), bucket));
        boolean bucketChanged = false;
        for (var value : computed) {
            var existing = old.remove(value.getBucketIndex());
            if (!StatsValues.same(existing, value, "id", "statsUpdatedAt")) {
                value.setId(existing == null ? null : existing.getId());
                buckets.save(value);
                bucketChanged = true;
            }
        }
        if (!old.isEmpty()) {
            buckets.deleteAll(old.values());
            bucketChanged = true;
        }
        boolean changed = bucketChanged || !StatsValues.same(previous, candidate, "id", "statsUpdatedAt");
        if (changed) {
            candidate.setId(previous == null ? null : previous.getId());
            if (previous != null) updates.daily(previous.getRoomId(), previous.getLiveDate());
            sessions.save(candidate);
            updates.daily(candidate.getRoomId(), candidate.getLiveDate());
        }
        updates.completeActive();
        return changed;
    }

    @Transactional
    public boolean metadata(RoomLiveSessionStats candidate, boolean complete) {
        if (!canCommit(candidate.getHistoryId())) return false;
        var previous = sessions.findByHistoryId(candidate.getHistoryId());
        if (previous != null && previous.isImportedSnapshot()) return false;
        if (StatsValues.same(previous, candidate, "id", "statsUpdatedAt")) {
            if (complete) updates.completeMetadataActive();
            return false;
        }
        candidate.setId(previous == null ? null : previous.getId());
        if (previous != null) updates.daily(previous.getRoomId(), previous.getLiveDate());
        sessions.save(candidate);
        updates.daily(candidate.getRoomId(), candidate.getLiveDate());
        if (complete) updates.completeMetadataActive();
        return true;
    }

    @Transactional
    public void saveDaily(RoomLiveDailyStats candidate) {
        var previous = daily.findByRoomIdAndLiveDate(candidate.getRoomId(), candidate.getLiveDate());
        if (candidate.getLiveCount() == 0) {
            if (previous != null) daily.delete(previous);
            return;
        }
        if (!StatsValues.same(previous, candidate, "id", "statsUpdatedAt")) {
            candidate.setId(previous == null ? null : previous.getId());
            daily.save(candidate);
        }
    }
}
