package top.sshh.bililiverecoder.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Table(indexes = {
        @Index(name = "idx_part_file_operation_part_status", columnList = "partId,status"),
        @Index(name = "idx_part_file_operation_status", columnList = "status")
})
public class PartFileOperation {

    public enum OperationType { MOVE, COPY, DELETE }
    public enum OperationStatus { PENDING, RUNNING, SUCCEEDED, SUCCEEDED_WITH_WARNINGS, FAILED }
    public enum OperationSource { STANDARD, SCHEDULED_CLEANUP }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 36)
    private String operationKey = UUID.randomUUID().toString();
    @Column(nullable = false)
    private Long partId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OperationType operationType;
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private OperationSource operationSource;
    private Long sourceLocationId;
    private Long targetRootId;
    @Column(length = 2048)
    private String targetRelativePath;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OperationStatus status = OperationStatus.PENDING;
    private int attemptCount;
    @Column(length = 512)
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (operationKey == null || operationKey.isBlank()) operationKey = UUID.randomUUID().toString();
    }
}
