package top.sshh.bililiverecoder.repo;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import top.sshh.bililiverecoder.entity.RoomLiveEventParseState;

@Repository
public interface RoomLiveEventParseStateRepository extends CrudRepository<RoomLiveEventParseState, Long> {

    RoomLiveEventParseState findByPartId(Long partId);

    @org.springframework.data.jpa.repository.Query("select sum(s.danmuCount) from RoomLiveEventParseState s where s.historyId = ?1 and s.success = true")
    Long sumDanmuCountByHistoryId(Long historyId);
}
