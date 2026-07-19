package top.sshh.bililiverecoder.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Table(indexes = {
        @Index(name = "idx_storage_root_type_active", columnList = "rootType,activeForNewFiles"),
        @Index(name = "idx_storage_root_status", columnList = "status")
})
public class StorageRoot {

    public enum RootType { WORK, ARCHIVE }
    public enum RootStatus { ONLINE, OFFLINE, RETIRED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 36)
    private String rootKey = UUID.randomUUID().toString();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RootType rootType;

    @Column(nullable = false, length = 2048)
    private String path;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RootStatus status = RootStatus.OFFLINE;

    private boolean activeForNewFiles;
    private boolean writable;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastCheckedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (rootKey == null || rootKey.isBlank()) rootKey = UUID.randomUUID().toString();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
