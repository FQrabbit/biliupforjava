package top.sshh.bililiverecoder.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.LiveMsg;

import java.util.List;
import java.util.stream.Stream;

@Repository
public interface LiveMsgRepository extends CrudRepository<LiveMsg, Long> {

    /**
     * 流式读取全部弹幕数据，用于导出配置时逐条写入 JSON，避免一次性加载全部到内存。
     * 调用方必须在 try-with-resources 中使用，并确保在事务内调用。
     */
    @Query("select m from LiveMsg m order by m.id")
    @Transactional(readOnly = true)
    Stream<LiveMsg> streamAll();

    List<LiveMsg> findByPartIdAndCode(Long partId, int code);

    int countByPartIdAndCode(Long partId, int code);

    @Query("SELECT DISTINCT m.partId FROM LiveMsg m WHERE m.code = ?1")
    List<Long> findDistinctPartIdByCode(int code);

    List<LiveMsg> findByPoolAndCodeAndPartIdInOrderBySendTimeAsc(int pool, int code, List<Long> partIds);
    Page<LiveMsg> findByPoolAndCodeAndPartIdInOrderBySendTimeAsc(int pool, int code, List<Long> partIds, Pageable page);

    /**
     * 查询字幕池弹幕
     * @param partId
     * @param pool
     * @return
     */
    List<LiveMsg> findByPartIdAndPoolAndCidNotNullOrderBySendTimeAsc(Long partId, int pool);

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
                   sum(case when m.pool = 0 and m.code = -1 then 1 else 0 end),
                   sum(case when m.pool = 1 and m.code = -1 then 1 else 0 end)
            from LiveMsg m
            where m.bvid in ?1
            group by m.bvid
            """)
    List<Object[]> aggregateSendStatsByBvids(List<String> bvids);

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
