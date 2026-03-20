package top.sshh.bililiverecoder.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = "eventId"),
       indexes = {
           @Index(name = "idx_record_history_room_id", columnList = "roomId"),
           @Index(name = "idx_end_time", columnList = "endTime"),
           @Index(name = "idx_bv_id", columnList = "bvId"),
           @Index(name = "idx_publish_code_reply", columnList = "publish, code, sendReply")
       })
public class RecordHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String roomId;

    @Transient
    private String roomName;

    private String avId;

    private String bvId;

    private String title;

    /**
     * 封面url
     */
    private String coverUrl;

    private String eventId;

    private String sessionId;

    private String filePath;

    private long fileSize;

    private boolean recording;
    private boolean streaming;

    // 是否上传
    private boolean upload;

    // 是否发布成功
    private boolean publish;

    //是否已发布评论
    private boolean sendReply;

    private int code = -1;

    private int uploadRetryCount = 0;

    private LocalDateTime startTime;
    private LocalDateTime endTime;

    private LocalDateTime updateTime;


    @Transient
    private int partCount;

    @Transient
    private float partDuration;

    @Transient
    private int uploadPartCount;

    @Transient
    private int recordPartCount;

    // 分P上传补偿任务中“永久放弃”的分P（例如无法读取时长/大小）
    @Transient
    private int giveUpPartCount;
    // 真正异常的分P数量（排除低于阈值SKIPPED_THRESHOLD和手动跳过MANUAL_SKIP的分P）
    @Transient
    private int abnormalPartCount;
    // 是否处于等待投稿状态（分P已上传完毕，等待合并间隔时间）
    @Transient
    private boolean waitingForPublish;

    @Transient
    private List<String> giveUpPartFiles;

    @Transient
    private List<String> giveUpPartReasons;

    @Transient
    private List<String> giveUpPartTypes;

    @Transient
    private int msgCount;

    @Transient
    private int successMsgCount;

    @Transient
    private int normalMsgCount;

    @Transient
    private int scMsgCount;

    @Transient
    private int guardMsgCount;

    /**
     * 待发送普通弹幕数量（仅用于前端展示，不入库）
     */
    @Transient
    private int pendingNormalMsgCount;

    /**
     * 待发送高级弹幕(SC/上舰)数量（仅用于前端展示，不入库）
     */
    @Transient
    private int pendingHighMsgCount;

    /**
     * 房间是否开启普通弹幕发送（仅用于前端展示，不入库）
     */
    @Transient
    private Boolean roomSendDm;

    /**
     * 房间是否开启高级弹幕/SC发送（仅用于前端展示，不入库）
     */
    @Transient
    private Boolean roomSendSc;


    @Transient
    private LocalDateTime from;
    @Transient
    private LocalDateTime to;

    /**
     * 获取稿件状态描述
     * 不修改数据库结构，仅做逻辑映射
     */
    public String getStatus() {
        if (recording) {
            return "正在录制";
        }
        
        // 只有真正的异常（不包括低于阈值的跳过）时才显示"存在异常"
        if (abnormalPartCount > 0) {
            return "存在异常";
        }

        // 等待投稿状态（分P已上传完毕，等待合并间隔时间）
        if (waitingForPublish) {
            return "等待投稿";
        }

        // 如果没有开启上传，或者手动关闭了上传
        if (!upload) {
            return "录制完成（未上传）";
        }
        // 已经标记为上传，但还没有发布成功（publish=true表示B站接口返回成功并获取到avid/bvid）
        if (!publish) {
            if (uploadRetryCount > 0) {
                return "上传中(重试" + uploadRetryCount + ")";
            }
            return "上传中";
        }
        // 已经发布(publish=true)，根据B站稿件状态code判断
        // 0: 开放浏览, -50: 仅自己可见
        if (code == 0 || code == -50) {
            // 仅自己可见的稿件(-50)不能直接发送普通弹幕，弹幕处理被主动跳过
            // 所以直接返回"已完成"，避免状态卡在"弹幕发送中"
            if (code == -50) {
                return "已完成";
            }

            // 说明：sendReply 在数据库里表示“SC/上舰评论是否已发送”（历史字段名沿用，不改表）
            // 普通/高级弹幕是否发送，由房间开关决定；待发送数量由 Controller 统计后回填到 transient 字段
            // 如果没有回填房间开关（例如某些非列表接口直接返回 entity），则保持旧逻辑，避免误判
            if (roomSendDm == null && roomSendSc == null) {
                return sendReply ? "已完成" : "发送弹幕中";
            }

            boolean dmEnabled = Boolean.TRUE.equals(roomSendDm);
            boolean scEnabled = Boolean.TRUE.equals(roomSendSc);
            int pendingNormal = Math.max(0, pendingNormalMsgCount);
            int pendingHigh = Math.max(0, pendingHighMsgCount);
            int pending = pendingNormal + pendingHigh;

            // 如果弹幕/SC 全部关闭，就不要卡在“发送中”
            if (!dmEnabled && !scEnabled) {
                return "已完成";
            }

            // 先看评论（SC列表）是否需要发送
            if (scEnabled && !sendReply && pendingHigh > 0) {
                return "发送弹幕中";
            }

            // 评论已处理完，再看弹幕队列是否还有待发送
            if (pending > 0) {
                return "弹幕发送中";
            }
            return "已完成";
        }
        
        // 其他状态处理
        return switch (code) {
            case -1 -> "审核中";
            case -2 -> "被退回";
            case -4 -> "被锁定";
            case -9 -> "转码中";
            case -10 -> "等待转码";
            case -20 -> "转码失败";
            case -30 -> "已提交";
            case -40 -> "定时发布";
            case 62002 -> "稿件不可见";
            case -100 -> "已删除";
            default -> "可能投稿中(Code:" + code + ")";
        };
    }
}
