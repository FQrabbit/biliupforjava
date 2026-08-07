package top.sshh.bililiverecoder.repo;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.RoomLiveDanmuUserStats;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface RoomLiveDanmuUserStatsRepository extends CrudRepository<RoomLiveDanmuUserStats, Long> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("delete from RoomLiveDanmuUserStats")
    int deleteAllRows();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("delete from RoomLiveDanmuUserStats s where not exists (select i from RoomLiveEventXmlIssue i where i.partId = s.partId)")
    int deleteAllRowsWithoutXmlIssue();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    void deleteByPartId(Long partId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    void deleteByHistoryId(Long historyId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("delete from RoomLiveDanmuUserStats s where s.roomId = ?1")
    int deleteByRoomId(String roomId);

    boolean existsByPartId(Long partId);

    long countByHistoryId(Long historyId);

    @Query("select sum(s.danmuCount) from RoomLiveDanmuUserStats s where s.historyId = ?1")
    Long sumDanmuCountByHistoryId(Long historyId);

    List<RoomLiveDanmuUserStats> findByIdGreaterThanOrderByIdAsc(Long id, Pageable pageable);

    @Query("""
            select s.uid, s.uname, sum(s.danmuCount)
            from RoomLiveDanmuUserStats s
            where s.historyId = ?1
            group by s.uid, s.uname
            order by sum(s.danmuCount) desc
            """)
    List<Object[]> findTopUsersByHistoryId(Long historyId, Pageable pageable);

    @Query("""
            select s.uid, s.uname, sum(s.danmuCount)
            from RoomLiveDanmuUserStats s
            where s.roomId = ?1
              and (?2 is null or s.liveDate >= ?2)
              and (?3 is null or s.liveDate <= ?3)
            group by s.uid, s.uname
            order by sum(s.danmuCount) desc
            """)
    List<Object[]> findTopUsersByRoomId(String roomId, LocalDate from, LocalDate to, Pageable pageable);
}
