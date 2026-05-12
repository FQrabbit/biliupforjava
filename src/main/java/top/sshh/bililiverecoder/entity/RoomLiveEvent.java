package top.sshh.bililiverecoder.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "room_live_event",
        indexes = {
                @Index(name = "idx_room_live_event_history_id", columnList = "historyId"),
                @Index(name = "idx_room_live_event_part_id", columnList = "partId"),
                @Index(name = "idx_room_live_event_room_type", columnList = "roomId,type"),
                @Index(name = "idx_room_live_event_live_date", columnList = "liveDate"),
                @Index(name = "idx_room_live_event_uid", columnList = "uid")
        })
public class RoomLiveEvent {

    public static final String TYPE_DANMU = "DANMU";
    public static final String TYPE_GIFT = "GIFT";
    public static final String TYPE_SC = "SC";
    public static final String TYPE_GUARD = "GUARD";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long historyId;
    private Long partId;
    private String roomId;
    private LocalDate liveDate;
    private String type;

    private Long uid;
    private String uname;
    private Long sendTime;

    @Column(length = 500)
    private String content;

    @Column(length = 4000)
    private String rawJson;

    private Integer giftId;
    private String giftName;
    private Long giftCount;
    private Long giftPriceCoin;
    private Long giftTotalCoin;
    private String giftCoinType;

    private BigDecimal scPrice;
    private Integer scDisplaySeconds;

    private Integer guardLevel;
    private Integer guardCount;

    private LocalDateTime createdAt;
}
