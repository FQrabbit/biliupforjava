package top.sshh.bililiverecoder.repo;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface RecordHistoryPartRepository extends CrudRepository<RecordHistoryPart, Long> {

    RecordHistoryPart findByFilePath(String path);

    RecordHistoryPart findByFilePathStartingWith(String path);

    List<RecordHistoryPart> findByHistoryId(Long historyId);

    RecordHistoryPart findByHistoryIdAndTitle(Long historyId, String title);

    List<RecordHistoryPart> findByHistoryIdOrderByStartTimeAsc(Long historyId);

    List<RecordHistoryPart> findByIdIn(List<Long> ids);

    List<RecordHistoryPart> findByRoomIdAndFileDeleteIsFalseAndEndTimeIsBefore(String roomId, LocalDateTime deleteTime);

    List<RecordHistoryPart> findByHistoryIdAndCidIsNotNullOrderByPageAsc(Long historyId);

    int countByHistoryIdAndRecordingIsTrue(Long historyId);

    @Query("select count(p) from RecordHistoryPart p where p.historyId = ?1 and (p.recording = true or p.endTime is null)")
    int countActuallyRecordingPartsByHistoryId(Long historyId);

    int countByHistoryId(Long historyId);

    int countByHistoryIdAndFileNameNotNull(Long historyId);

    @Query("select ifnull(sum(duration),0) from RecordHistoryPart where historyId = ?1")
    float sumHistoryDurationByHistoryId(Long historyId);

    @Query("select count(p) from RecordHistoryPart p where p.historyId = ?1 and p.upload = false and p.uploadRetryCount >= 9999")
    int countGiveUpPartsByHistoryId(Long historyId);

    @Query("select p.filePath from RecordHistoryPart p where p.historyId = ?1 and p.upload = false and p.uploadRetryCount >= 9999 order by p.endTime asc")
    List<String> findGiveUpPartFilePathsByHistoryId(Long historyId);

    boolean existsByFilePath(String filePath);

    // 查询需要上传但未上传的分P（录制已结束、未上传、结束时间在指定范围内）
    List<RecordHistoryPart> findByRoomIdAndRecordingIsFalseAndUploadIsFalseAndEndTimeBetweenOrderByEndTimeAsc(
        String roomId, LocalDateTime startTime, LocalDateTime endTime);

    // 分P上传补偿任务专用：仅返回所属history存在且已开启上传(upload=true)的分P，避免无意义重复扫描
    @Query("""
        select p from RecordHistoryPart p
        where p.roomId = :roomId
          and p.recording = false
          and p.upload = false
                    and p.uploadRetryCount < 9999
          and p.endTime between :startTime and :endTime
          and exists (
              select 1 from RecordHistory h
              where h.id = p.historyId
                and h.upload = true
          )
        order by p.endTime asc
        """)
    List<RecordHistoryPart> findPendingUploadPartsWithHistoryUploadEnabled(
        String roomId, LocalDateTime startTime, LocalDateTime endTime);
}
