package top.sshh.bililiverecoder.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "publish_task", indexes = {
        @Index(name = "idx_publish_task_account_state_order", columnList = "account_id,state,created_at"),
        @Index(name = "idx_publish_task_history_state", columnList = "history_id,state"),
        @Index(name = "idx_publish_task_state_due", columnList = "state,next_attempt_at")
})
public class PublishTask {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "history_id")
    private Long historyId;

    @Column(name = "account_id")
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PublishTaskOperation operation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PublishTaskSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private PublishTaskState state;

    @Column(length = 64)
    private String waitReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(nullable = false)
    private int retryCount;

    @Column(name = "captcha_retry_count", nullable = false)
    private int captchaRetryCount;

    @Column(name = "captcha_retry_pending", nullable = false)
    private boolean captchaRetryPending;

    @Column(length = 2000)
    private String resultMessage;

    @Lob
    private String requestSnapshot;

    @Column(length = 80)
    private String claimToken;

    private LocalDateTime leaseUntil;

    @Version
    @Column(name = "task_version", nullable = false)
    private Long version;
}
