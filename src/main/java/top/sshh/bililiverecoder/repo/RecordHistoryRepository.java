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

    @Query("""
            select h from RecordHistory h
            where lower(coalesce(h.title, '')) like lower(concat('%', ?1, '%'))
               or lower(coalesce(h.bvId, '')) like lower(concat('%', ?1, '%'))
               or lower(coalesce(h.roomId, '')) like lower(concat('%', ?1, '%'))
            order by h.id desc
            """)
    List<RecordHistory> searchForDiagnostic(String query, Pageable pageable);

    @org.springframework.data.jpa.repository.Query("select h from RecordHistory h where h.endTime is not null and h.recording = false and h.streaming = false and h.endTime <= ?1 order by h.endTime asc")
    List<RecordHistory> findMatureCompletedOrderByEndTimeAsc(LocalDateTime endBefore, Pageable pageable);

    @Query("""
            select h from RecordHistory h
            where h.endTime is not null
              and h.endTime <= ?1
              and h.recording = false
              and h.streaming = false
              and (
                  exists (
                      select 1
                      from LiveMsg m
                      where m.code = -1
                        and m.partId in (
                            select p.id
                            from RecordHistoryPart p
                            where p.historyId = h.id
                        )
                  )
                  or (h.publish = true and h.sendReply = false)
              )
            order by h.endTime asc
            """)
    List<RecordHistory> findMsgQueueCleanupCandidates(LocalDateTime endBefore, Pageable pageable);

    @Query("""
            select distinct h
            from LiveMsg m, RecordHistoryPart p, RecordHistory h
            where m.code = -1
              and m.partId = p.id
              and p.historyId = h.id
              and h.endTime is not null
              and h.endTime <= ?1
              and h.recording = false
              and h.streaming = false
            order by h.endTime asc
            """)
    List<RecordHistory> findMsgQueueCleanupCandidatesByPendingMsg(LocalDateTime endBefore, Pageable pageable);

    @Query("""
            select distinct h
            from LiveMsg m, RecordHistoryPart p, RecordHistory h, RecordRoom r
            where m.code = -1
              and m.pool = 0
              and m.partId = p.id
              and p.historyId = h.id
              and r.roomId = p.roomId
              and h.endTime is not null
              and h.endTime <= ?1
              and h.recording = false
              and h.streaming = false
              and h.forceArchived = false
              and h.publish = true
              and h.code = 0
              and p.uploadRetryCount < 9999
              and p.cid is not null
              and p.cid <> 0
              and r.sendDm = true
            order by h.endTime asc
            """)
    List<RecordHistory> findMsgQueueCleanupCandidatesByPendingOrdinaryMsg(LocalDateTime endBefore, Pageable pageable);

    @Query("""
            select distinct h
            from LiveMsg m, RecordHistoryPart p, RecordHistory h, RecordRoom r
            where m.code = -1
              and m.pool = 1
              and m.partId = p.id
              and p.historyId = h.id
              and r.roomId = p.roomId
              and h.endTime is not null
              and h.endTime <= ?1
              and h.recording = false
              and h.streaming = false
              and h.forceArchived = false
              and h.publish = true
              and h.code in (0, -50)
              and p.uploadRetryCount < 9999
              and p.cid is not null
              and p.cid <> 0
              and r.sendSc = true
            order by h.endTime asc
            """)
    List<RecordHistory> findMsgQueueCleanupCandidatesByPendingHighMsg(LocalDateTime endBefore, Pageable pageable);

    @Query("""
            select h
            from RecordHistory h, RecordRoom r
            where h.endTime is not null
              and h.endTime <= ?1
              and h.recording = false
              and h.streaming = false
              and h.forceArchived = false
              and h.publish = true
              and h.code in (0, -50)
              and h.sendReply = false
              and r.roomId = h.roomId
              and (r.sendSc = true or r.sendGiftReply = true)
            order by h.endTime asc
            """)
    List<RecordHistory> findMsgQueueCleanupCandidatesByPendingReply(LocalDateTime endBefore, Pageable pageable);

    @Query("""
            select count(h)
            from RecordHistory h
            where h.endTime is not null
              and h.endTime <= ?1
              and h.recording = false
              and h.streaming = false
              and (
                  exists (
                      select 1
                      from LiveMsg m
                      where m.code = -1
                        and m.partId in (
                            select p.id
                            from RecordHistoryPart p
                            where p.historyId = h.id
                        )
                  )
                  or (h.publish = true and h.sendReply = false)
              )
            """)
    long countMsgQueueCleanupCandidates(LocalDateTime endBefore);
}
