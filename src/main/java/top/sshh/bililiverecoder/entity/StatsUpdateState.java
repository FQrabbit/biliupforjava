package top.sshh.bililiverecoder.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "stats_update_state", indexes = @Index(name = "idx_stats_update_due", columnList = "suppressed,retryAt,historyId"))
public class StatsUpdateState {
    @Id private Long historyId;
    private String roomId;
    @Column(columnDefinition = "bigint default 0", nullable = false)
    private long requestedRevision;
    @Column(columnDefinition = "bigint default 0", nullable = false)
    private long appliedRevision;
    @Column(columnDefinition = "bigint default 0", nullable = false)
    private long rawRevision;
    @Column(columnDefinition = "bigint default 0", nullable = false)
    private long appliedRawRevision;
    @Column(columnDefinition = "int default 0", nullable = false)
    private int reasons;
    @Column(columnDefinition = "int default 0", nullable = false)
    private int failures;
    @Column(columnDefinition = "boolean default false", nullable = false)
    private boolean suppressed;
    @Column(columnDefinition = "boolean default false", nullable = false)
    private boolean blockedXml;
    @Column(length = 64) private String observedContent;
    @Column(length = 64) private String observedMetadata;
    @Column(length = 64) private String appliedContent;
    @Column(length = 64) private String appliedMetadata;
    private LocalDateTime retryAt;
    private LocalDateTime completedAt;
    @Column(length = 512) private String lastError;
}
