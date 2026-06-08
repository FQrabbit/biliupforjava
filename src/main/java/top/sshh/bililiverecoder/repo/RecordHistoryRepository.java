package top.sshh.bililiverecoder.repo;

import org.springframework.data.repository.CrudRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.RecordHistory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

@Repository
public interface RecordHistoryRepository extends CrudRepository<RecordHistory, Long> {

    /**
     * 流式读取全部录制历史，用于导出配置时逐条写入 JSON，避免一次性加载全部到内存。
     * 调用方必须在 try-with-resources 中使用，并确保在事务内调用。
     */
    @Query("select h from RecordHistory h order by h.id")
    @Transactional(readOnly = true)
    Stream<RecordHistory> streamAll();

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

    @org.springframework.data.jpa.repository.Query("""
            select h from RecordHistory h
            where h.publish = true
              and h.sendReply = false
              and h.code in (0, -50)
            order by h.id asc
            """)
    List<RecordHistory> findPendingReplyHistories(Pageable pageable);

    List<RecordHistory> findByBvIdNotNullAndPublishIsTrueAndCodeLessThan(int code);

    long countByEndTimeIsNotNull();

    List<RecordHistory> findByEndTimeIsNotNullOrderByEndTimeDesc();

    @org.springframework.data.jpa.repository.Query("select r from RecordHistory r where r.bvId is not null and r.publish = true and r.code in (-1, -9, -30, -40)")
    List<RecordHistory> findSyncList();

    @org.springframework.data.jpa.repository.Query("""
            select h from RecordHistory h
            where h.endTime is not null
              and h.recording = false
              and h.streaming = false
              and not exists (
                  select 1 from RecordHistoryPart p
                  where p.historyId = h.id
                    and (p.recording = true or p.endTime is null)
              )
            order by h.endTime desc
            """)
    List<RecordHistory> findCompletedOrderByEndTimeDesc(Pageable pageable);

    @org.springframework.data.jpa.repository.Query("select h from RecordHistory h where h.endTime is not null and h.recording = false and h.streaming = false and h.endTime <= ?1 order by h.endTime asc")
    List<RecordHistory> findMatureCompletedOrderByEndTimeAsc(LocalDateTime endBefore, Pageable pageable);
}
