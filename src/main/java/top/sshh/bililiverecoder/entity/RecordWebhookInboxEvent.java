package top.sshh.bililiverecoder.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "record_webhook_inbox", indexes = {
        @Index(name = "idx_webhook_inbox_due", columnList = "state,next_attempt_at,id"),
        @Index(name = "idx_webhook_inbox_updated", columnList = "updated_at")
})
public class RecordWebhookInboxEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_key", nullable = false, unique = true, length = 64)
    private String eventKey;

    @Column(name = "lock_key", nullable = false, length = 512)
    private String lockKey;

    @Column(name = "event_type", length = 128)
    private String eventType;

    @Column(name = "source_name", nullable = false, length = 32)
    private String source;

    @Column(name = "delay_ms", nullable = false)
    private long delayMs;

    @Lob
    @Column(nullable = false)
    private String payload;

    @Column(nullable = false, length = 16)
    private String state;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Version
    @Column(name = "event_version", nullable = false)
    private Long version;
}
