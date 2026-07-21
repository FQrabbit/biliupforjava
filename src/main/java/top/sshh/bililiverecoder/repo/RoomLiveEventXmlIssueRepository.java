package top.sshh.bililiverecoder.repo;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import top.sshh.bililiverecoder.entity.RoomLiveEventXmlIssue;

import java.util.Collection;
import java.util.List;

@Repository
public interface RoomLiveEventXmlIssueRepository extends CrudRepository<RoomLiveEventXmlIssue, Long> {

    List<RoomLiveEventXmlIssue> findAllByOrderByLastCheckedAtDesc();

    List<RoomLiveEventXmlIssue> findByPartIdIn(Collection<Long> partIds);

    List<RoomLiveEventXmlIssue> findByStorageRootIdAndIssueType(Long storageRootId,
                                                                 RoomLiveEventXmlIssue.IssueType issueType);

    List<RoomLiveEventXmlIssue> findByHistoryId(Long historyId);

    long deleteByHistoryId(Long historyId);

    long deleteByPartIdIn(Collection<Long> partIds);

    @Modifying
    @Query("delete from RoomLiveEventXmlIssue issue where issue.partId = ?1")
    int deleteByPartIdIfPresent(Long partId);
}
