package top.sshh.bililiverecoder.repo;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.HistoryDeletionTask;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Collection;
import java.util.Optional;

public interface HistoryDeletionTaskRepository extends JpaRepository<HistoryDeletionTask, Long> {
    Optional<HistoryDeletionTask> findByHistoryId(Long historyId);

    @Query("select t.historyId from HistoryDeletionTask t where t.id = :id")
    Optional<Long> findHistoryIdByTaskId(@Param("id") Long id);

    List<HistoryDeletionTask> findByHistoryIdIn(Collection<Long> historyIds);

    List<HistoryDeletionTask> findByStateIn(List<String> states);

    List<HistoryDeletionTask> findByStateInAndNextAttemptAtLessThanEqualOrderByIdAsc(
            List<String> states, LocalDateTime dueAt, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from HistoryDeletionTask t where t.id = :id")
    Optional<HistoryDeletionTask> findByIdForUpdate(@Param("id") Long id);

    @Modifying
    @Transactional
    @Query("update HistoryDeletionTask t set t.state = 'PENDING', t.nextAttemptAt = :now, t.updatedAt = :now "
            + "where t.state = 'RUNNING'")
    int recoverInterrupted(@Param("now") LocalDateTime now);

    @Modifying
    @Transactional
    @Query("delete from HistoryDeletionTask t where t.state = 'COMPLETED' and t.updatedAt < :before")
    int deleteOldCompleted(@Param("before") LocalDateTime before);
}
