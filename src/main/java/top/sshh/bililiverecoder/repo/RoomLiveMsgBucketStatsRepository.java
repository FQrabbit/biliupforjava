package top.sshh.bililiverecoder.repo;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.RoomLiveMsgBucketStats;

import java.util.List;

@Repository
public interface RoomLiveMsgBucketStatsRepository extends CrudRepository<RoomLiveMsgBucketStats, Long> {

    List<RoomLiveMsgBucketStats> findByHistoryIdOrderByBucketIndexAsc(Long historyId);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    void deleteByHistoryId(Long historyId);
}
