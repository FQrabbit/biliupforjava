package top.sshh.bililiverecoder.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
public class BiliBiliUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long uid;

    private String uname;

    private String face;

    private String accessToken;
    private String refreshToken;

    @Column(length = 2100)
    private String cookies;

    private LocalDateTime updateTime;

    /**
     * 是否登录
     */
    private boolean login;

    /**
     * 是否启用弹幕
     */
    private boolean enable;

    /**
     * 是否启用SC/上舰发送
     */
    @Column(columnDefinition = "bit default 0")
    private boolean enableSc;

    /** 记录账号连续遇到投稿风控的次数，以及下次允许投稿的时间 */
    private Integer publishRiskFailures = 0;
    private LocalDateTime publishCooldownUntil;
    private LocalDateTime publishLastRiskAt;
    private Integer publishSuccessStreak = 0;
    private LocalDateTime publishNextAllowedAt;
    /** 投稿验证码一直无人处理时，这个账号只允许指定任务自动尝试 */
    private Long publishCaptchaProbeTaskId;
    /** 账号正在等待验证码或人工处理、尚未安排自动尝试时，这里为 null */
    private LocalDateTime publishCaptchaRetryAt;
}
