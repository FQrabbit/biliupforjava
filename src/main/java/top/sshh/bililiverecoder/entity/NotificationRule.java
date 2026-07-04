package top.sshh.bililiverecoder.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "notification_rule", indexes = {
        @Index(name = "idx_notification_rule_event", columnList = "eventType"),
        @Index(name = "idx_notification_rule_room", columnList = "roomId")
})
public class NotificationRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String eventType;

    private String eventLabel;

    private String roomId;

    private String roomName;

    private boolean enabled = true;

    @Column(length = 1000)
    private String channelIds;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
