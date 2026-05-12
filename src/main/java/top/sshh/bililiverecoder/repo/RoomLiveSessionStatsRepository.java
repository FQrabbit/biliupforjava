package top.sshh.bililiverecoder.repo;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import top.sshh.bililiverecoder.entity.RoomLiveSessionStats;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface RoomLiveSessionStatsRepository extends CrudRepository<RoomLiveSessionStats, Long> {

    RoomLiveSessionStats findByHistoryId(Long historyId);

    List<RoomLiveSessionStats> findByHistoryIdIn(List<Long> historyIds);

    List<RoomLiveSessionStats> findByRoomId(String roomId);

    List<RoomLiveSessionStats> findByStatsVersionLessThan(int statsVersion);

    List<RoomLiveSessionStats> findByRoomIdAndLiveDate(String roomId, LocalDate liveDate);

    List<RoomLiveSessionStats> findTop20ByRoomIdOrderByStartTimeDesc(String roomId);

    @Query("select s from RoomLiveSessionStats s order by s.startTime desc")
    List<RoomLiveSessionStats> findLatest(Pageable pageable);
}
