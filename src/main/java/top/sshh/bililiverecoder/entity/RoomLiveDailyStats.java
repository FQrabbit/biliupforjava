package top.sshh.bililiverecoder.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "room_live_daily_stats",
        uniqueConstraints = @UniqueConstraint(columnNames = {"roomId", "liveDate"}),
        indexes = {
                @Index(name = "idx_room_live_daily_room_id", columnList = "roomId"),
                @Index(name = "idx_room_live_daily_live_date", columnList = "liveDate")
        })
public class RoomLiveDailyStats {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String roomId;
    private String uname;
    private LocalDate liveDate;

    private int liveCount;
    private long totalDurationSeconds;
    private long averageDurationSeconds;
    private long totalFileSize;
    private long totalMsgCount;
    private long totalNormalMsgCount;
    private long totalAdvancedMsgCount;
    @Column(columnDefinition = "bigint default 0")
    private long totalGiftEventCount;
    @Column(columnDefinition = "bigint default 0")
    private long totalGiftCount;
    @Column(columnDefinition = "bigint default 0")
    private long totalGiftCoin;
    private java.math.BigDecimal totalGiftAmountCny = java.math.BigDecimal.ZERO;
    @Column(columnDefinition = "bigint default 0")
    private long totalScCount;
    private java.math.BigDecimal totalScAmount = java.math.BigDecimal.ZERO;
    @Column(columnDefinition = "bigint default 0")
    private long totalGuardCount;
    @Column(columnDefinition = "bigint default 0")
    private long totalActiveUserCount;
    private int publishedCount;
    private int successfulPublishCount;

    private LocalDateTime statsUpdatedAt;
    private int statsVersion;
}
