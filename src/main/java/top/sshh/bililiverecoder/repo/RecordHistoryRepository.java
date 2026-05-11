package top.sshh.bililiverecoder.repo;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import top.sshh.bililiverecoder.entity.RecordHistory;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface RecordHistoryRepository extends CrudRepository<RecordHistory, Long> {

    RecordHistory findBySessionId(String sessionId);

    RecordHistory findByBvId(String bvId);

    List<RecordHistory> findByRoomIdAndEndTimeBetweenOrderByEndTimeAsc(String roomId, LocalDateTime from, LocalDateTime to);

    List<RecordHistory> findByRoomIdAndRecordingTrueOrderByStartTimeDesc(String roomId);

    @org.springframework.data.jpa.repository.Query("""
            select h from RecordHistory h
            where h.roomId = ?1
              and h.publish = false
              and h.forceArchived = false
              and exists (
                  select 1 from RecordHistoryPart p
                  where p.historyId = h.id
                    and (p.recording = true or p.endTime is null)
              )
            order by h.startTime desc
            """)
    List<RecordHistory> findUnpublishedHistoriesWithRecordingPartsByRoomIdOrderByStartTimeDesc(String roomId);

    List<RecordHistory> findByRoomIdAndBvIdAndRecordingAndUploadAndPublishAndEndTimeBetweenOrderByEndTimeAsc(String roomId, String bvId, Boolean record, Boolean upload, Boolean publish, LocalDateTime from, LocalDateTime to);

    List<RecordHistory> findByRoomIdAndRecordingIsFalseAndUploadIsTrueAndPublishIsFalseAndUploadRetryCountLessThanAndEndTimeBetweenOrderByEndTimeAsc(String roomId, int count, LocalDateTime from, LocalDateTime to);

    List<RecordHistory> findByPublishIsTrueAndCode(int code);

    List<RecordHistory> findByPublishIsTrueAndCodeIn(List<Integer> codes);

    List<RecordHistory> findByPublishIsTrueAndSendReplyIsFalseAndCodeIn(List<Integer> codes);

    List<RecordHistory> findByBvIdNotNullAndPublishIsTrueAndCodeLessThan(int code);

    @org.springframework.data.jpa.repository.Query("select r from RecordHistory r where r.bvId is not null and r.publish = true and r.code in (-1, -9, -30, -40)")
    List<RecordHistory> findSyncList();
}
