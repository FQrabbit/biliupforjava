package top.sshh.bililiverecoder.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import top.sshh.bililiverecoder.entity.PublishTask;
import top.sshh.bililiverecoder.entity.PublishTaskState;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public interface PublishTaskRepository extends JpaRepository<PublishTask, Long> {
    PublishTask findTopByHistoryIdOrderByCreatedAtDesc(Long historyId);

    List<PublishTask> findByHistoryIdAndStateInOrderByCreatedAtAsc(Long historyId,
                                                                   Collection<PublishTaskState> states);

    List<PublishTask> findByStateInOrderByCreatedAtAsc(Collection<PublishTaskState> states);

    List<PublishTask> findByAccountIdAndStateInOrderByCreatedAtAsc(Long accountId,
                                                                   Collection<PublishTaskState> states);

    List<PublishTask> findByStateInAndNextAttemptAtBeforeOrderByCreatedAtAsc(
            Collection<PublishTaskState> states, LocalDateTime dueAt);

    List<PublishTask> findByStateInAndUpdatedAtBefore(Collection<PublishTaskState> states,
                                                       LocalDateTime olderThan);

    List<PublishTask> findByStateInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
            Collection<PublishTaskState> states, LocalDateTime olderThan);
}
