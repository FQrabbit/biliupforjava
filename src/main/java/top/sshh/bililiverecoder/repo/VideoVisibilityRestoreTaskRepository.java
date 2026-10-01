package top.sshh.bililiverecoder.repo;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import top.sshh.bililiverecoder.entity.VideoVisibilityRestoreTask;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface VideoVisibilityRestoreTaskRepository extends JpaRepository<VideoVisibilityRestoreTask, Long> {
    Optional<VideoVisibilityRestoreTask> findByHistoryId(Long historyId);

    List<VideoVisibilityRestoreTask> findByStateIn(List<String> states);

    List<VideoVisibilityRestoreTask> findByStateAndNextAttemptAtLessThanEqualOrderByUpdatedAtAsc(
            String state, LocalDateTime dueAt, Pageable pageable);
}
