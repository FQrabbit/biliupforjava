package top.sshh.bililiverecoder.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = "eventId"))
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
        
        if (giveUpPartCount > 0) {
            return "存在异常";
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
            // 只有状态正常的稿件才会进入弹幕发送流程
            if (sendReply) {
                return "已完成";
            } else {
                return "发送弹幕中";
            }
        }
        
        // 其他状态处理
        return switch (code) {
            case -1 -> "审核中";
            case -2 -> "被退回";
            case -10 -> "等待转码";
            case -20 -> "转码失败";
            case -30 -> "审核不通过";
            case -40 -> "审核被锁定";
            default -> "投稿中(Code:" + code + ")";
        };
    }
}
