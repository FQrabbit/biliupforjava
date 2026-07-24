package top.sshh.bililiverecoder.repo;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.RoomLiveEvent;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface RoomLiveEventRepository extends CrudRepository<RoomLiveEvent, Long> {

    long countByHistoryId(Long historyId);

    long countByHistoryIdAndType(Long historyId, String type);

    List<RoomLiveEvent> findByHistoryIdAndType(Long historyId, String type);

    List<RoomLiveEvent> findByHistoryIdAndTypeOrderByPartIdAscSendTimeAsc(Long historyId, String type);

    @Query("select count(distinct e.uid) from RoomLiveEvent e where e.historyId = ?1 and e.uid is not null")
    long countDistinctUidByHistoryId(Long historyId);

    @Query("select count(distinct e.giftName) from RoomLiveEvent e where e.historyId = ?1 and e.type = 'GIFT' and e.giftName is not null")
    long countDistinctGiftNameByHistoryId(Long historyId);

    @Query("select sum(e.giftCount) from RoomLiveEvent e where e.historyId = ?1 and e.type = 'GIFT'")
    Long sumGiftCountByHistoryId(Long historyId);

    @Query("select sum(e.scPrice) from RoomLiveEvent e where e.historyId = ?1 and e.type = 'SC'")
    BigDecimal sumScPriceByHistoryId(Long historyId);

    @Query("select sum(e.guardCount) from RoomLiveEvent e where e.historyId = ?1 and e.type = 'GUARD'")
    Long sumGuardCountByHistoryId(Long historyId);

    @Query("""
            select floor(e.sendTime / 60000), e.type, count(e)
            from RoomLiveEvent e
            where e.historyId = ?1
              and e.sendTime is not null
              and e.type in ('DANMU', 'SC', 'GUARD', 'GIFT')
            group by floor(e.sendTime / 60000), e.type
            order by floor(e.sendTime / 60000)
            """)
    List<Object[]> getEventBucketCountByHistoryId(Long historyId);

    @Query("""
            select e.partId, floor(e.sendTime / 60000), e.type, count(e)
            from RoomLiveEvent e
            where e.historyId = ?1
              and e.sendTime is not null
              and e.type in ('DANMU', 'SC', 'GUARD', 'GIFT')
            group by e.partId, floor(e.sendTime / 60000), e.type
            order by e.partId, floor(e.sendTime / 60000)
            """)
    List<Object[]> getEventBucketCountByHistoryIdWithPartId(Long historyId);

    @Query("""
            select e.uid, e.uname, count(e)
            from RoomLiveEvent e
            where e.roomId = ?1
              and e.type = ?2
              and (?3 is null or e.liveDate >= ?3)
              and (?4 is null or e.liveDate <= ?4)
            group by e.uid, e.uname
            order by count(e) desc
            """)
    List<Object[]> findTopUsersByRoomIdAndType(String roomId, String type, LocalDate from, LocalDate to, Pageable pageable);

    @Query("""
            select e.uid, e.uname, count(e)
            from RoomLiveEvent e
            where e.historyId = ?1
              and e.type = ?2
            group by e.uid, e.uname
            order by count(e) desc
            """)
    List<Object[]> findTopUsersByHistoryIdAndType(Long historyId, String type, Pageable pageable);

    @Query("""
            select e.uid, e.uname, sum(e.giftCount)
            from RoomLiveEvent e
            where e.roomId = ?1
              and e.type = 'GIFT'
              and (?2 is null or e.liveDate >= ?2)
              and (?3 is null or e.liveDate <= ?3)
            group by e.uid, e.uname
            order by sum(e.giftCount) desc
            """)
    List<Object[]> findTopGiftUsersByRoomId(String roomId, LocalDate from, LocalDate to, Pageable pageable);

    @Query("""
            select e.uid, e.uname, sum(e.giftCount)
            from RoomLiveEvent e
            where e.historyId = ?1
              and e.type = 'GIFT'
            group by e.uid, e.uname
            order by sum(e.giftCount) desc
            """)
    List<Object[]> findTopGiftUsersByHistoryId(Long historyId, Pageable pageable);

    @Query("""
            select e.giftName, sum(e.giftCount)
            from RoomLiveEvent e
            where e.roomId = ?1
              and e.type = 'GIFT'
              and (?2 is null or e.liveDate >= ?2)
              and (?3 is null or e.liveDate <= ?3)
            group by e.giftName
            order by sum(e.giftCount) desc
            """)
    List<Object[]> findGiftDistributionByRoomId(String roomId, LocalDate from, LocalDate to, Pageable pageable);

    @Query("""
            select e.giftName, sum(e.giftCount)
            from RoomLiveEvent e
            where e.historyId = ?1
              and e.type = 'GIFT'
            group by e.giftName
            order by sum(e.giftCount) desc
            """)
    List<Object[]> findGiftDistributionByHistoryId(Long historyId, Pageable pageable);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    void deleteByPartId(Long partId);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    void deleteByHistoryId(Long historyId);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("delete from RoomLiveEvent e where e.roomId = ?1")
    int deleteByRoomId(String roomId);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update RoomLiveEvent e set e.rawJson = null where e.rawJson is not null")
    int clearRawJson();
}
