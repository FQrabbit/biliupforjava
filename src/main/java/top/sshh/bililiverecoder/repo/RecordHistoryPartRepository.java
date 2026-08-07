package top.sshh.bililiverecoder.repo;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface RecordHistoryPartRepository extends CrudRepository<RecordHistoryPart, Long> {

    RecordHistoryPart findByFilePath(String path);

    RecordHistoryPart findByFilePathStartingWith(String path);

    @Query("select p from RecordHistoryPart p where p.roomId = ?1 and (p.recording = true or p.endTime is null) order by p.id desc")
    List<RecordHistoryPart> findOpenCandidatesByRoomId(String roomId);

    @Query("""
            select p from RecordHistoryPart p
            where p.historyId = ?1
            order by
              case when p.partOrder is null or p.partOrder <= 0 then 1 else 0 end asc,
              p.partOrder asc,
              case when p.page <= 0 then 1 else 0 end asc,
              p.page asc,
              p.startTime asc,
              p.id asc
            """)
    List<RecordHistoryPart> findByHistoryId(Long historyId);

    RecordHistoryPart findByHistoryIdAndTitle(Long historyId, String title);

    @Query("""
            select p from RecordHistoryPart p
            where p.historyId = ?1
            order by
              case when p.partOrder is null or p.partOrder <= 0 then 1 else 0 end asc,
              p.partOrder asc,
              case when p.page <= 0 then 1 else 0 end asc,
              p.page asc,
              p.startTime asc,
              p.id asc
            """)
    List<RecordHistoryPart> findByHistoryIdOrderByStartTimeAsc(Long historyId);

    @Query("""
            select p from RecordHistoryPart p
            where p.historyId = ?1
              and p.uploadRetryCount < 9999
              and p.cid is not null
              and p.cid <> 0
            order by
              case when p.partOrder is null or p.partOrder <= 0 then 1 else 0 end asc,
              p.partOrder asc,
              case when p.page <= 0 then 1 else 0 end asc,
              p.page asc,
              p.startTime asc,
              p.id asc
            """)
    List<RecordHistoryPart> findDispatchablePartsByHistoryId(Long historyId);

    List<RecordHistoryPart> findByIdIn(List<Long> ids);

    List<RecordHistoryPart> findByRoomIdAndSessionIdOrderByIdDesc(String roomId, String sessionId);

    List<RecordHistoryPart> findByIdGreaterThanOrderByIdAsc(Long id, Pageable pageable);

    @Query("""
            select p from RecordHistoryPart p
            where p.historyId in ?1
            order by p.historyId asc, p.id asc
            """)
    List<RecordHistoryPart> findByHistoryIdIn(List<Long> historyIds);

    List<RecordHistoryPart> findByRoomIdAndFileDeleteIsFalseAndEndTimeIsBefore(String roomId, LocalDateTime deleteTime);

    @Query("""
            select p from RecordHistoryPart p
            where p.roomId = ?1 and p.endTime < ?2
              and exists (select 1 from PartFileLocation l
                          where l.partId = p.id and l.state = ?3)
            order by p.endTime asc
            """)
    List<RecordHistoryPart> findFileCleanupCandidates(String roomId, LocalDateTime deleteTime,
                                                       top.sshh.bililiverecoder.entity.PartFileLocation.LocationState state);

    List<RecordHistoryPart> findByHistoryIdAndCidIsNotNullOrderByPageAsc(Long historyId);

    int countByHistoryIdAndRecordingIsTrue(Long historyId);

    @Query("select count(p) from RecordHistoryPart p where p.historyId = ?1 and (p.recording = true or p.endTime is null)")
    int countActuallyRecordingPartsByHistoryId(Long historyId);

    int countByHistoryId(Long historyId);

    int countByHistoryIdAndFileNameNotNull(Long historyId);

    @Query("select ifnull(sum(duration),0) from RecordHistoryPart where historyId = ?1")
    float sumHistoryDurationByHistoryId(Long historyId);

    @Query("select ifnull(sum(fileSize),0) from RecordHistoryPart where historyId = ?1")
    long sumHistoryFileSizeByHistoryId(Long historyId);

    @Query("select count(p) from RecordHistoryPart p where p.historyId = ?1 and p.upload = false and (p.uploadRetryCount >= 9999 or (p.deleteFailType is not null and trim(p.deleteFailType) <> ''))")
    int countGiveUpPartsByHistoryId(Long historyId);

    @Query("select count(p) from RecordHistoryPart p where p.historyId = ?1 and p.uploadFlowFallback = true")
    int countUploadFlowFallbackPartsByHistoryId(Long historyId);

    @Query("""
            select p.historyId,
                   count(p),
                   coalesce(sum(p.duration), 0),
                   coalesce(sum(p.fileSize), 0),
                   sum(case when p.fileName is not null then 1 else 0 end),
                   sum(case when p.recording = true or p.endTime is null then 1 else 0 end),
                   sum(case when p.upload = false and (p.uploadRetryCount >= 9999 or (p.deleteFailType is not null and trim(p.deleteFailType) <> '')) then 1 else 0 end),
                   sum(case when p.upload = false
                             and (p.uploadRetryCount >= 9999 or (p.deleteFailType is not null and trim(p.deleteFailType) <> ''))
                             and (p.deleteFailType is null or (trim(p.deleteFailType) <> '' and p.deleteFailType not in ('SKIPPED_THRESHOLD', 'MANUAL_SKIP')))
                            then 1 else 0 end),
                   sum(case when p.uploadFlowFallback = true then 1 else 0 end)
            from RecordHistoryPart p
            where p.historyId in ?1
            group by p.historyId
            """)
    List<Object[]> aggregateListStatsByHistoryIds(List<Long> historyIds);

    @Query("select p from RecordHistoryPart p where p.historyId = ?1 and p.uploadFlowFallback = true order by p.page asc")
    List<RecordHistoryPart> findUploadFlowFallbackPartsByHistoryId(Long historyId);

    @Query("select p.filePath from RecordHistoryPart p where p.historyId = ?1 and p.upload = false and (p.uploadRetryCount >= 9999 or (p.deleteFailType is not null and trim(p.deleteFailType) <> '')) order by p.endTime asc")
    List<String> findGiveUpPartFilePathsByHistoryId(Long historyId);

    @Query("select p from RecordHistoryPart p where p.historyId = ?1 and p.upload = false and (p.uploadRetryCount >= 9999 or (p.deleteFailType is not null and trim(p.deleteFailType) <> '')) order by p.endTime asc")
    List<RecordHistoryPart> findGiveUpPartsByHistoryId(Long historyId);

    // 只计算真正异常的分P（排除低于阈值和手动跳过的预期行为）
    @Query("select count(p) from RecordHistoryPart p where p.historyId = ?1 and p.upload = false and " +
           "(p.uploadRetryCount >= 9999 or (p.deleteFailType is not null and trim(p.deleteFailType) <> '')) and " +
           "(p.deleteFailType is null or (trim(p.deleteFailType) <> '' and p.deleteFailType not in ('SKIPPED_THRESHOLD', 'MANUAL_SKIP')))")
    int countAbnormalPartsByHistoryId(Long historyId);

    boolean existsByFilePath(String filePath);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("delete from RecordHistoryPart p where p.historyId = ?1")
    int deleteByHistoryId(Long historyId);

    // 查询需要上传但未上传的分P（录制已结束、未上传、结束时间在指定范围内）
    List<RecordHistoryPart> findByRoomIdAndRecordingIsFalseAndUploadIsFalseAndEndTimeBetweenOrderByEndTimeAsc(
        String roomId, LocalDateTime startTime, LocalDateTime endTime);

    // 分P上传补偿任务专用：仅返回所属history存在且已开启上传(upload=true)且未投稿(publish=false)的分P
    @Query("""
        select p from RecordHistoryPart p
        where p.roomId = :roomId
          and p.recording = false
          and p.upload = false
          and (p.uploadPaused is null or p.uploadPaused = false)
          and p.uploadRetryCount < 9999
          and (p.deleteFailType is null or p.deleteFailType not in ('SKIPPED_THRESHOLD', 'MANUAL_SKIP'))
          and p.endTime between :startTime and :endTime
          and exists (
              select 1 from RecordHistory h
              where h.id = p.historyId
                and h.upload = true
                and (h.uploadPaused is null or h.uploadPaused = false)
                and h.publish = false
          )
        order by p.endTime asc
        """)
    List<RecordHistoryPart> findPendingUploadPartsWithHistoryUploadEnabled(
        String roomId, LocalDateTime startTime, LocalDateTime endTime);

    @Query("""
        select p from RecordHistoryPart p
        where p.roomId = :roomId
          and p.recording = false
          and p.upload = false
          and (p.uploadPaused is null or p.uploadPaused = false)
          and p.uploadRetryCount < 9999
          and (p.deleteFailType is null or p.deleteFailType not in ('SKIPPED_THRESHOLD', 'MANUAL_SKIP'))
          and p.endTime between :startTime and :endTime
          and (p.sourceType is null or p.sourceType <> 'EDIT_PART')
          and exists (
              select 1 from RecordHistory h
              where h.id = p.historyId
                and h.upload = true
                and (h.uploadPaused is null or h.uploadPaused = false)
                and h.publish = false
          )
        order by p.endTime asc
        """)
    List<RecordHistoryPart> findPendingUploadPartsWithHistoryUploadEnabled(
        String roomId, LocalDateTime startTime, LocalDateTime endTime, Pageable pageable);

    @Query("""
        select p from RecordHistoryPart p
        where p.roomId = :roomId
          and p.recording = false
          and p.upload = false
          and (p.uploadPaused is null or p.uploadPaused = false)
          and p.uploadRetryCount < 9999
          and (p.deleteFailType is null or p.deleteFailType not in ('SKIPPED_THRESHOLD', 'MANUAL_SKIP'))
          and p.endTime between :startTime and :endTime
          and (p.sourceType is null or p.sourceType <> 'EDIT_PART')
          and exists (
              select 1 from RecordHistory h
              where h.id = p.historyId
                and h.upload = true
                and h.forceArchived = false
                and h.code not in (-2, -4)
                and (h.avId is not null and trim(h.avId) <> ''
                     or h.bvId is not null and trim(h.bvId) <> '')
          )
        order by p.endTime asc
        """)
    List<RecordHistoryPart> findPendingUploadPartsOfPublishedHistories(
        String roomId, LocalDateTime startTime, LocalDateTime endTime, Pageable pageable);

    @Query("""
        select count(p) from RecordHistoryPart p
        where p.recording = false
          and p.upload = false
          and (p.uploadPaused is null or p.uploadPaused = false)
          and p.uploadRetryCount < 9999
          and p.endTime is not null
          and exists (
              select 1 from RecordHistory h
              where h.id = p.historyId
                and h.upload = true
                and (h.uploadPaused is null or h.uploadPaused = false)
                and h.publish = false
                and h.forceArchived = false
          )
        """)
    int countPendingUploadPartsWithHistoryUploadEnabled();

    // 查询所属稿件已投稿(publish=true 或 bvId不为空)但自身未上传且未放弃的分P（孤立分P清理用）
    @Query("""
        select p from RecordHistoryPart p
        where p.upload = false
          and (p.uploadPaused is null or p.uploadPaused = false)
          and p.uploadRetryCount < 9999
          and (p.sourceType is null or p.sourceType <> 'EDIT_PART')
          and exists (
              select 1 from RecordHistory h
              where h.id = p.historyId
                and (h.publish = true or (h.bvId is not null and trim(h.bvId) <> ''))
          )
        """)
    List<RecordHistoryPart> findOrphanedPartsOfPublishedHistories();

    @Query("""
        select p from RecordHistoryPart p
        where p.upload = false
          and (p.uploadPaused is null or p.uploadPaused = false)
          and p.uploadRetryCount < 9999
          and exists (
              select 1 from RecordHistory h
              where h.id = p.historyId
                and (h.publish = true or (h.bvId is not null and trim(h.bvId) <> ''))
          )
          and (p.sourceType is null or p.sourceType <> 'EDIT_PART')
        order by p.endTime asc
        """)
    List<RecordHistoryPart> findOrphanedPartsOfPublishedHistories(Pageable pageable);
}
