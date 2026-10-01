package top.sshh.bililiverecoder.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.VideoCommentTask;

import java.util.List;

public interface VideoCommentTaskRepository extends JpaRepository<VideoCommentTask, Long> {
    List<VideoCommentTask> findByHistoryIdOrderBySequenceAsc(Long historyId);

    @Modifying
    @Transactional
    @Query("update VideoCommentTask t set t.state = 'NEEDS_ACTION', "
            + "t.errorMessage = '进程中断时投稿结果未确认，请核对视频评论后再处理', "
            + "t.updatedAt = CURRENT_TIMESTAMP where t.state = 'SUBMITTING'")
    int markInterruptedSubmissions();

    @Modifying
    @Transactional
    @Query("update VideoCommentTask t set t.pinState = 'RETRY', "
            + "t.errorMessage = '进程中断时置顶结果未确认，恢复后将安全重试', "
            + "t.updatedAt = CURRENT_TIMESTAMP where t.pinState = 'SUBMITTING'")
    int markInterruptedPinSubmissions();
}
