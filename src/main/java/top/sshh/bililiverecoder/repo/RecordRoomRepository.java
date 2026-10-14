package top.sshh.bililiverecoder.repo;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import top.sshh.bililiverecoder.entity.RecordRoom;

import java.util.List;

@Repository
public interface RecordRoomRepository extends CrudRepository<RecordRoom, Long> {

    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("update RecordRoom r set r.liveGroup = ?2, r.liveEventAt = ?3, r.liveEndedAt = ?4 where r.id = ?1")
    void updateLiveClock(Long id, String group, java.time.LocalDateTime eventAt, java.time.LocalDateTime endedAt);

    // 生命周期状态和时间一起提交，普通设置或头像刷新不能把旧时间快照写回来
    @org.springframework.transaction.annotation.Transactional
    default RecordRoom saveLiveState(RecordRoom room) {
        RecordRoom saved = save(room);
        updateLiveClock(saved.getId(), room.getLiveGroup(), room.getLiveEventAt(), room.getLiveEndedAt());
        return saved;
    }

    RecordRoom findByRoomId(String roomId);

    @Query("select r from RecordRoom r order by case when r.sortOrder is null then 1 else 0 end, r.sortOrder asc, r.id asc")
    List<RecordRoom> findAllOrderBySortOrder();

    @Query("select coalesce(max(r.sortOrder), 0) from RecordRoom r")
    Integer findMaxSortOrder();

    List<RecordRoom> findByUpload(boolean upload);

    List<RecordRoom> findBySendDmIsTrue();

    List<RecordRoom> findByDeleteType(int type);
}
