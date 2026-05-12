package top.sshh.bililiverecoder.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "room_live_event_parse_state",
        uniqueConstraints = @UniqueConstraint(columnNames = "partId"),
        indexes = {
                @Index(name = "idx_room_live_event_state_history_id", columnList = "historyId"),
                @Index(name = "idx_room_live_event_state_room_id", columnList = "roomId")
        })
public class RoomLiveEventParseState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long partId;
    private Long historyId;
    private String roomId;

    @Column(length = 2000)
    private String xmlPath;
    private long xmlLastModified;
    private long xmlSize;

    private int eventCount;
    private int danmuCount;
    private int giftCount;
    private int scCount;
    private int guardCount;

    private boolean success;

    @Column(length = 1000)
    private String errorMessage;

    private LocalDateTime parsedAt;
    private int parserVersion;
}
