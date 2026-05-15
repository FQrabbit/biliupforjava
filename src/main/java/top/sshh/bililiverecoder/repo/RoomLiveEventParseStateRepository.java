package top.sshh.bililiverecoder.repo;

import org.springframework.data.repository.CrudRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.RoomLiveEventParseState;

import java.util.List;

@Repository
public interface RoomLiveEventParseStateRepository extends CrudRepository<RoomLiveEventParseState, Long> {

    RoomLiveEventParseState findByPartId(Long partId);

    List<RoomLiveEventParseState> findByHistoryId(Long historyId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("delete from RoomLiveEventParseState")
    int deleteAllRows();

    @org.springframework.data.jpa.repository.Query("select sum(s.danmuCount) from RoomLiveEventParseState s where s.historyId = ?1 and s.success = true")
    Long sumDanmuCountByHistoryId(Long historyId);
}
