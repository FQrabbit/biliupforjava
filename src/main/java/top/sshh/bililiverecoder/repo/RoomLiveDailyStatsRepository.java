package top.sshh.bililiverecoder.repo;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import top.sshh.bililiverecoder.entity.RoomLiveDailyStats;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface RoomLiveDailyStatsRepository extends CrudRepository<RoomLiveDailyStats, Long> {

    RoomLiveDailyStats findByRoomIdAndLiveDate(String roomId, LocalDate liveDate);

    List<RoomLiveDailyStats> findByRoomId(String roomId);

    List<RoomLiveDailyStats> findTop30ByRoomIdOrderByLiveDateDesc(String roomId);
}
