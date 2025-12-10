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

    List<RecordHistory> findByRoomIdAndBvIdAndRecordingAndUploadAndPublishAndEndTimeBetweenOrderByEndTimeAsc(String roomId, String bvId, Boolean record, Boolean upload, Boolean publish, LocalDateTime from, LocalDateTime to);

    List<RecordHistory> findByRoomIdAndRecordingIsFalseAndUploadIsTrueAndPublishIsFalseAndUploadRetryCountLessThanAndEndTimeBetweenOrderByEndTimeAsc(String roomId, int count, LocalDateTime from, LocalDateTime to);

    List<RecordHistory> findByPublishIsTrueAndCode(int code);

    List<RecordHistory> findByBvIdNotNullAndPublishIsTrueAndCodeLessThan(int code);

    @org.springframework.data.jpa.repository.Query("select r from RecordHistory r where r.bvId is not null and r.publish = true and r.code in (-1, -9, -30, -40)")
    List<RecordHistory> findSyncList();
}
