package top.sshh.bililiverecoder.repo;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.RecordWebhookInboxEvent;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface RecordWebhookInboxRepository extends JpaRepository<RecordWebhookInboxEvent, Long> {
    Optional<RecordWebhookInboxEvent> findByEventKey(String eventKey);

    List<RecordWebhookInboxEvent> findByStateAndNextAttemptAtLessThanEqualOrderByIdAsc(
            String state, LocalDateTime dueAt, Pageable pageable);

    @Query("select e from RecordWebhookInboxEvent e "
            + "where e.state = :state and e.nextAttemptAt <= :dueAt "
            + "and not exists (select p.id from RecordWebhookInboxEvent p "
            + "where p.lockKey = e.lockKey and p.id < e.id "
            + "and p.state in ('PENDING', 'PROCESSING')) "
            + "order by e.id asc")
    List<RecordWebhookInboxEvent> findDueHeadEvents(@Param("state") String state,
                                                    @Param("dueAt") LocalDateTime dueAt,
                                                    Pageable pageable);

    @Modifying
    @Transactional
    @Query("update RecordWebhookInboxEvent e set e.state = 'PENDING', e.updatedAt = :now "
            + "where e.state = 'PROCESSING'")
    int recoverInterrupted(@Param("now") LocalDateTime now);

    @Modifying
    @Transactional
    @Query("delete from RecordWebhookInboxEvent e where e.updatedAt < :before "
            + "and e.state in ('DONE', 'FAILED')")
    int deleteOldTerminal(@Param("before") LocalDateTime before);
}
