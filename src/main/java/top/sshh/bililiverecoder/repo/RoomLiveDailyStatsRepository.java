package top.sshh.bililiverecoder.repo;

import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.RoomLiveDailyStats;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface RoomLiveDailyStatsRepository extends CrudRepository<RoomLiveDailyStats, Long> {

    RoomLiveDailyStats findByRoomIdAndLiveDate(String roomId, LocalDate liveDate);

    List<RoomLiveDailyStats> findByRoomId(String roomId);

    List<RoomLiveDailyStats> findTop30ByRoomIdOrderByLiveDateDesc(String roomId);

    List<RoomLiveDailyStats> findByIdGreaterThanOrderByIdAsc(Long id, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("delete from RoomLiveDailyStats d where d.roomId = ?1")
    int deleteByRoomId(String roomId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("delete from RoomLiveDailyStats d")
    int deleteAllRows();
}
