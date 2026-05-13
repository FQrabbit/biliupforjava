package top.sshh.bililiverecoder.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "room_live_danmu_user_stats",
        uniqueConstraints = @UniqueConstraint(columnNames = {"partId", "uid", "uname"}),
        indexes = {
                @Index(name = "idx_room_live_danmu_user_history_id", columnList = "historyId"),
                @Index(name = "idx_room_live_danmu_user_room_date", columnList = "roomId,liveDate"),
                @Index(name = "idx_room_live_danmu_user_uid", columnList = "uid")
        })
public class RoomLiveDanmuUserStats {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long historyId;
    private Long partId;
    private String roomId;
    private LocalDate liveDate;

    private Long uid;
    private String uname;

    @Column(columnDefinition = "bigint default 0")
    private long danmuCount;

    private LocalDateTime statsUpdatedAt;
    private int parserVersion;
}
