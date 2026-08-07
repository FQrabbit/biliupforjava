package top.sshh.bililiverecoder.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "room_live_session_stats",
        uniqueConstraints = @UniqueConstraint(columnNames = "historyId"),
        indexes = {
                @Index(name = "idx_room_live_session_room_id", columnList = "roomId"),
                @Index(name = "idx_room_live_session_live_date", columnList = "liveDate"),
                @Index(name = "idx_room_live_session_start_time", columnList = "startTime")
        })
public class RoomLiveSessionStats {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long historyId;
    private String roomId;
    private String uname;
    private String title;
    private String bvId;

    private LocalDate liveDate;
    private Integer startHour;
    private LocalDateTime startTime;
    private LocalDateTime endTime;

    private long durationSeconds;
    private int partCount;
    private long fileSize;

    private boolean uploadEnabled;
    private boolean published;
    private int publishCode;
    private boolean sendReply;

    private long msgCount;
    private long normalMsgCount;
    private long advancedMsgCount;
    @Column(columnDefinition = "bigint default 0")
    private long giftEventCount;
    @Column(columnDefinition = "bigint default 0")
    private long giftTotalCount;
    @Column(columnDefinition = "bigint default 0")
    private long giftTotalCoin;
    private java.math.BigDecimal giftAmountCny = java.math.BigDecimal.ZERO;
    @Column(columnDefinition = "bigint default 0")
    private long giftTypeCount;
    @Column(columnDefinition = "bigint default 0")
    private long scCount;
    private java.math.BigDecimal scAmount = java.math.BigDecimal.ZERO;
    @Column(columnDefinition = "bigint default 0")
    private long guardCount;
    @Column(columnDefinition = "bigint default 0")
    private long activeUserCount;
    private long peakMinuteMsgCount;
    private Integer peakMinuteIndex;

    private LocalDateTime statsUpdatedAt;
    private int statsVersion;

    /** 导入的统计快照在源 XML 不存在时不能被后台重建任务覆盖 */
    @Column(columnDefinition = "boolean default false")
    private boolean importedSnapshot;
}
