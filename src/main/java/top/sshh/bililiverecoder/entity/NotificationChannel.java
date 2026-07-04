package top.sshh.bililiverecoder.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "notification_channel")
public class NotificationChannel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String type;

    private boolean enabled = true;

    @Column(length = 4000)
    private String configJson;

    @Column(length = 4000)
    private String secretJson;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
