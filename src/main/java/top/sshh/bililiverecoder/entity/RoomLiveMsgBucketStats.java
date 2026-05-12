package top.sshh.bililiverecoder.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "room_live_msg_bucket_stats",
        uniqueConstraints = @UniqueConstraint(columnNames = {"historyId", "bucketIndex"}),
        indexes = {
                @Index(name = "idx_room_live_bucket_history_id", columnList = "historyId"),
                @Index(name = "idx_room_live_bucket_room_id", columnList = "roomId")
        })
public class RoomLiveMsgBucketStats {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long historyId;
    private String roomId;
    private int bucketIndex;
    private long bucketStartMs;

    private long msgCount;
    private long normalMsgCount;
    private long advancedMsgCount;
    @Column(columnDefinition = "bigint default 0")
    private long giftEventCount;
    @Column(columnDefinition = "bigint default 0")
    private long scCount;
    @Column(columnDefinition = "bigint default 0")
    private long guardCount;

    private LocalDateTime statsUpdatedAt;
    private int statsVersion;
}
