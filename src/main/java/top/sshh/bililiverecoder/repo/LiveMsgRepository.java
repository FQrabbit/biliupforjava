package top.sshh.bililiverecoder.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.LiveMsg;

import java.util.List;

@Repository
public interface LiveMsgRepository extends CrudRepository<LiveMsg, Long> {

    List<LiveMsg> findByIdGreaterThanOrderByIdAsc(Long id, Pageable pageable);

    List<LiveMsg> findByPartIdAndCode(Long partId, int code);

    int countByPartIdAndCode(Long partId, int code);

    @Query("SELECT DISTINCT m.partId FROM LiveMsg m WHERE m.code = ?1")
    List<Long> findDistinctPartIdByCode(int code);

    List<LiveMsg> findByPoolAndCodeAndPartIdInOrderBySendTimeAsc(int pool, int code, List<Long> partIds);
    Page<LiveMsg> findByPoolAndCodeAndPartIdInOrderBySendTimeAsc(int pool, int code, List<Long> partIds, Pageable page);

    Page<LiveMsg> findByPartIdAndPoolAndCodeOrderBySendTimeAsc(Long partId, int pool, int code, Pageable page);

    @Query("""
            select distinct m.partId
            from LiveMsg m
            where m.pool = 0
              and m.code = -1
              and m.partId in (
                  select p.id
                  from RecordHistoryPart p
                  where p.uploadRetryCount < 9999
                    and p.cid is not null
                    and p.cid <> 0
                    and exists (
                        select 1
                        from RecordHistory h
                        where h.id = p.historyId
                          and h.publish = true
                          and h.code = 0
                          and h.sendReply = true
                    )
                    and exists (
                        select 1
                        from RecordRoom r
                        where r.roomId = p.roomId
                          and r.sendDm = true
                    )
              )
            order by m.partId asc
            """)
    List<Long> findPendingNormalDispatchPartIds(Pageable page);

    @Query("""
            select distinct m.partId
            from LiveMsg m
            where m.pool = 1
              and m.code = -1
              and m.partId in (
                  select p.id
                  from RecordHistoryPart p
                  where p.uploadRetryCount < 9999
                    and p.cid is not null
                    and p.cid <> 0
                    and exists (
                        select 1
                        from RecordHistory h
                        where h.id = p.historyId
                          and h.publish = true
                          and h.code in (0, -50)
                          and h.sendReply = true
                    )
                    and exists (
                        select 1
                        from RecordRoom r
                        where r.roomId = p.roomId
                          and r.sendSc = true
                    )
              )
            order by m.partId asc
            """)
    List<Long> findPendingHighDispatchPartIds(Pageable page);

    @Query("""
            select distinct m.partId
            from LiveMsg m
            where m.pool = 0
              and m.code = -1
              and m.partId in (
                  select p.id
                  from RecordHistoryPart p
                  where p.historyId = ?1
                    and p.uploadRetryCount < 9999
                    and p.cid is not null
                    and p.cid <> 0
                    and exists (
                        select 1
                        from RecordHistory h
                        where h.id = p.historyId
                          and h.publish = true
                          and h.code = 0
                          and h.sendReply = true
                    )
                    and exists (
                        select 1
                        from RecordRoom r
                        where r.roomId = p.roomId
                          and r.sendDm = true
                    )
              )
            order by m.partId asc
            """)
    List<Long> findPendingNormalDispatchPartIdsByHistoryId(Long historyId, Pageable page);

    @Query("""
            select distinct m.partId
            from LiveMsg m
            where m.pool = 1
              and m.code = -1
              and m.partId in (
                  select p.id
                  from RecordHistoryPart p
                  where p.historyId = ?1
                    and p.uploadRetryCount < 9999
                    and p.cid is not null
                    and p.cid <> 0
                    and exists (
                        select 1
                        from RecordHistory h
                        where h.id = p.historyId
                          and h.publish = true
                          and h.code in (0, -50)
                          and h.sendReply = true
                    )
                    and exists (
                        select 1
                        from RecordRoom r
                        where r.roomId = p.roomId
                          and r.sendSc = true
                    )
              )
            order by m.partId asc
            """)
    List<Long> findPendingHighDispatchPartIdsByHistoryId(Long historyId, Pageable page);

    /**
     * 查询字幕池弹幕
     * @param partId
     * @param pool
     * @return
     */
    List<LiveMsg> findByPartIdAndPoolAndCidNotNullOrderBySendTimeAsc(Long partId, int pool);

    List<LiveMsg> findByPartIdInAndPoolAndCidNotNullOrderByPartIdAscSendTimeAsc(List<Long> partIds, int pool);

    int countByPartId(Long partId);

    @Query("select count(m) from LiveMsg m where m.partId in ?1")
    long countByPartIdIn(List<Long> partIds);

    @Query("select count(m) from LiveMsg m where m.partId in ?1 and m.pool = ?2")
    long countByPartIdInAndPool(List<Long> partIds, int pool);

    int countByBvid(String bvId);

    int countByBvidAndCode(String bvId, Integer code);

    int countByBvidAndPoolAndCode(String bvId, int pool, int code);

    int countByBvidAndPool(String bvId, int pool);

    int countByBvidAndPoolAndContextStartingWith(String bvId, int pool, String prefix);

    @Query("select count(m) from LiveMsg m where m.partId in ?1 and m.pool = ?2 and m.code = -1")
    long countPendingByPartIdsAndPool(List<Long> partIds, int pool);

    @Query("select count(m) from LiveMsg m where m.partId in ?1 and m.pool = ?2 and m.code <> 0 and m.code <> -1")
    long countFailedByPartIdsAndPool(List<Long> partIds, int pool);

    @Query("select count(m) from LiveMsg m where m.bvid = ?1 and m.code <> 0 and m.code <> -1")
    long countFailedByBvid(String bvid);

    @Query("""
            select count(m)
            from LiveMsg m
            where m.partId in ?1
              and m.pool = ?2
              and m.code = -1
              and m.partId in (
                  select p.id
                  from RecordHistoryPart p, RecordHistory h, RecordRoom r
                  where p.id in ?1
                    and p.historyId = h.id
                    and r.roomId = p.roomId
                    and h.forceArchived = false
                    and h.recording = false
                    and h.streaming = false
                    and h.publish = true
                    and p.uploadRetryCount < 9999
                    and p.cid is not null
                    and p.cid <> 0
                    and (
                        (?2 = 0 and h.code = 0 and r.sendDm = true)
                        or (?2 = 1 and h.code in (0, -50) and r.sendSc = true)
                    )
              )
            """)
    long countDispatchablePendingByPartIdsAndPool(List<Long> partIds, int pool);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update LiveMsg m set m.code = ?3 where m.bvid = ?1 and m.pool = ?2 and m.code = -1")
    int markPendingByBvidAndPool(String bvId, int pool, int code);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update LiveMsg m set m.code = ?3 where m.partId in ?1 and m.pool = ?2 and m.code = -1")
    int markPendingByPartIdsAndPool(List<Long> partIds, int pool, int code);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update LiveMsg m
            set m.code = ?3
            where m.partId in ?1
              and m.pool = ?2
              and m.code = -1
              and m.partId in (
                  select p.id
                  from RecordHistoryPart p, RecordHistory h, RecordRoom r
                  where p.id in ?1
                    and p.historyId = h.id
                    and r.roomId = p.roomId
                    and h.forceArchived = false
                    and h.recording = false
                    and h.streaming = false
                    and h.publish = true
                    and p.uploadRetryCount < 9999
                    and p.cid is not null
                    and p.cid <> 0
                    and (
                        (?2 = 0 and h.code = 0 and r.sendDm = true)
                        or (?2 = 1 and h.code in (0, -50) and r.sendSc = true)
                    )
              )
            """)
    int markDispatchablePendingByPartIdsAndPool(List<Long> partIds, int pool, int code);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update LiveMsg m
            set m.code = -1
            where m.partId in ?1
              and m.pool = ?2
              and m.code <> 0
              and m.code <> -1
              and m.code not in ?3
              and m.partId in (
                  select p.id
                  from RecordHistoryPart p, RecordHistory h, RecordRoom r
                  where p.id in ?1
                    and p.historyId = h.id
                    and r.roomId = p.roomId
                    and h.forceArchived = false
                    and h.recording = false
                    and h.streaming = false
                    and h.publish = true
                    and p.uploadRetryCount < 9999
                    and p.cid is not null
                    and p.cid <> 0
                    and (
                        (?2 = 0 and h.code = 0 and r.sendDm = true)
                        or (?2 = 1 and h.code in (0, -50) and r.sendSc = true)
                    )
              )
            """)
    int retryDispatchableFailedByPartIdsAndPool(List<Long> partIds, int pool, List<Integer> excludedCodes);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update LiveMsg m
            set m.code = -1
            where m.partId in ?1
              and m.pool = ?2
              and m.code <> 0
              and m.code <> -1
              and m.partId in (
                  select p.id
                  from RecordHistoryPart p, RecordHistory h, RecordRoom r
                  where p.id in ?1
                    and p.historyId = h.id
                    and r.roomId = p.roomId
                    and h.forceArchived = false
                    and h.recording = false
                    and h.streaming = false
                    and h.publish = true
                    and p.uploadRetryCount < 9999
                    and p.cid is not null
                    and p.cid <> 0
                    and (
                        (?2 = 0 and h.code = 0 and r.sendDm = true)
                        or (?2 = 1 and h.code in (0, -50) and r.sendSc = true)
                    )
              )
            """)
    int forceRetryDispatchableFailedByPartIdsAndPool(List<Long> partIds, int pool);

    @Query("""
            select m.bvid,
                   count(m),
                   sum(case when m.code = 0 then 1 else 0 end),
                   sum(case when m.pool = 0 then 1 else 0 end),
                   sum(case when m.pool = 1 and m.context like ?2 then 1 else 0 end),
                   sum(case when m.pool = 1 and m.context like ?3 then 1 else 0 end),
                   sum(case when m.pool = 0 and m.code = -1 then 1 else 0 end),
                   sum(case when m.pool = 1 and m.code = -1 then 1 else 0 end)
            from LiveMsg m
            where m.bvid in ?1
            group by m.bvid
            """)
    List<Object[]> aggregateListStatsByBvids(List<String> bvids, String scLike, String guardLike);

    @Query("""
            select m.bvid,
                   sum(case when m.code = 0 then 1 else 0 end),
                   sum(case when m.pool = 0 and m.code = 0 then 1 else 0 end),
                   sum(case when m.pool = 1 and m.code = 0 then 1 else 0 end),
                   sum(case when m.pool = 0 and m.code = -1 then 1 else 0 end),
                   sum(case when m.pool = 1 and m.code = -1 then 1 else 0 end)
            from LiveMsg m
            where m.bvid in ?1
            group by m.bvid
            """)
    List<Object[]> aggregateSendStatsByBvids(List<String> bvids);

    @Query("""
            select p.historyId, count(m)
            from LiveMsg m, RecordHistoryPart p
            where m.partId = p.id
              and p.historyId in ?1
              and m.pool = 1
              and m.cid is not null
            group by p.historyId
            """)
    List<Object[]> aggregateHighReplyLineCountByHistoryIds(List<Long> historyIds);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("delete from LiveMsg where partId = ?1")
    int deleteByPartId(Long partId);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("delete from LiveMsg m where m.partId in (select p.id from RecordHistoryPart p where p.historyId = ?1)")
    int deleteByHistoryId(Long historyId);

    List<LiveMsg> queryByBvid(String bvid);

    List<LiveMsg> queryByCid(Long cid);

    List<LiveMsg> queryByPartId(Long partId);

    @Query("""
            select floor(m.sendTime / 60000), count(m)
            from LiveMsg m
            where m.partId = ?1
            group by floor(m.sendTime / 60000)
            """)
    List<Object[]> getMsgCountByMinute(Long partId);

    @Query("""
            select floor(m.sendTime / 1000), count(m)
            from LiveMsg m
            where m.partId = ?1
            group by floor(m.sendTime / 1000)
            """)
    List<Object[]> getMsgCountBySecond(Long partId);

    @Query("""
            select floor(m.sendTime / 60000),
                   m.partId,
                   count(m),
                   sum(case when m.pool = 0 then 1 else 0 end),
                   sum(case when m.pool = 1 then 1 else 0 end)
            from LiveMsg m
            where m.partId in ?1
              and m.sendTime is not null
            group by m.partId, floor(m.sendTime / 60000)
            order by m.partId, floor(m.sendTime / 60000)
            """)
    List<Object[]> getMsgBucketCountByPartIds(List<Long> partIds);
}
