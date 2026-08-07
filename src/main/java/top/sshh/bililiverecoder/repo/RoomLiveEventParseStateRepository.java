package top.sshh.bililiverecoder.repo;

import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.RoomLiveEventParseState;

import java.util.List;
import java.util.Collection;

@Repository
public interface RoomLiveEventParseStateRepository extends CrudRepository<RoomLiveEventParseState, Long> {

    RoomLiveEventParseState findByPartId(Long partId);

    List<RoomLiveEventParseState> findByHistoryId(Long historyId);

    List<RoomLiveEventParseState> findByPartIdIn(Collection<Long> partIds);

    List<RoomLiveEventParseState> findBySuccessFalse();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("delete from RoomLiveEventParseState")
    int deleteAllRows();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("delete from RoomLiveEventParseState s where not exists (select i from RoomLiveEventXmlIssue i where i.partId = s.partId)")
    int deleteAllRowsWithoutXmlIssue();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("delete from RoomLiveEventParseState s where s.roomId = ?1")
    int deleteByRoomId(String roomId);

    @org.springframework.data.jpa.repository.Query("select sum(s.danmuCount) from RoomLiveEventParseState s where s.historyId = ?1 and s.success = true")
    Long sumDanmuCountByHistoryId(Long historyId);

    List<RoomLiveEventParseState> findByIdGreaterThanOrderByIdAsc(Long id, Pageable pageable);
}
