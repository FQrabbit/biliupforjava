package top.sshh.bililiverecoder.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "notification_delivery", indexes = {
        @Index(name = "idx_notification_delivery_create_time", columnList = "createTime"),
        @Index(name = "idx_notification_delivery_event", columnList = "eventType")
})
public class NotificationDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String eventType;

    private String eventLabel;

    private String roomId;

    private String roomName;

    private Long channelId;

    private String channelType;

    private String channelName;

    private String title;

    @Lob
    private String content;

    private String status;

    private int retryCount;

    @Column(length = 2000)
    private String errorMessage;

    private LocalDateTime createTime;

    private LocalDateTime sentTime;
}
