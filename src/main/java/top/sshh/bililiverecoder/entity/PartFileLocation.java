package top.sshh.bililiverecoder.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(uniqueConstraints = @UniqueConstraint(
        name = "uk_part_file_location", columnNames = {"partId", "storageRootId", "relativePath"}),
        indexes = {
                @Index(name = "idx_part_file_location_part", columnList = "partId"),
                @Index(name = "idx_part_file_location_root_state", columnList = "storageRootId,state"),
                @Index(name = "idx_part_file_location_part_role", columnList = "partId,role")
        })
public class PartFileLocation {

    public enum LocationRole { PRIMARY, REPLICA }
    public enum LocationState {
        AVAILABLE, PROCESSING, DELETED_BY_POLICY, MOVED_AWAY, MISSING_UNEXPECTED, PROCESS_FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long partId;
    private Long storageRootId;
    @Column(nullable = false, length = 2048)
    private String relativePath;
    @Column(length = 2048)
    private String absolutePathSnapshot;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LocationRole role = LocationRole.PRIMARY;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private LocationState state = LocationState.AVAILABLE;
    private long expectedSize;
    private LocalDateTime lastVerifiedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @Column(length = 512)
    private String errorMessage;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
