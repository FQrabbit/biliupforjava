package top.sshh.bililiverecoder.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "video_comment_task",
        uniqueConstraints = @UniqueConstraint(name = "uk_video_comment_history_sequence",
                columnNames = {"history_id", "sequence_no"}),
        indexes = @Index(name = "idx_video_comment_history_state", columnList = "history_id,state"))
public class VideoCommentTask {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "history_id", nullable = false)
    private Long historyId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "sequence_no", nullable = false)
    private int sequence;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "aid", nullable = false, length = 32)
    private String aid;

    @Lob
    @Column(nullable = false)
    private String content;

    @Column(nullable = false, length = 20)
    private String state;

    @Column(name = "remote_rpid", length = 40)
    private String remoteRpid;

    @Column(name = "root_rpid", length = 40)
    private String rootRpid;

    @Column(name = "pin_state", nullable = false, length = 16)
    private String pinState;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "task_version", nullable = false)
    private Long version;
}
