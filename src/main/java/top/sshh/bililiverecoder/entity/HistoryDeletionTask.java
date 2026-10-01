package top.sshh.bililiverecoder.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "history_deletion_task", indexes = {
        @Index(name = "idx_history_delete_due", columnList = "state,next_attempt_at,id"),
        @Index(name = "uk_history_delete_history", columnList = "history_id", unique = true)
})
public class HistoryDeletionTask {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "history_id", nullable = false, unique = true)
    private Long historyId;

    @Column(name = "room_id", length = 255)
    private String roomId;

    @Column(name = "delete_video", nullable = false)
    private boolean deleteVideo;

    @Column(name = "delete_danmaku", nullable = false)
    private boolean deleteDanmaku;

    @Column(name = "delete_cover", nullable = false)
    private boolean deleteCover;

    @Column(name = "deletion_started", nullable = false)
    private boolean deletionStarted;

    @Column(name = "state", nullable = false, length = 24)
    private String state;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "task_version", nullable = false)
    private Long version;
}
