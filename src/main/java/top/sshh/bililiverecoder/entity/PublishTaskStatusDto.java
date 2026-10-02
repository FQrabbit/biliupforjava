package top.sshh.bililiverecoder.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class PublishTaskStatusDto {
    private Long taskId;
    private Long historyId;
    private Long accountId;
    private PublishTaskOperation operation;
    private PublishTaskState state;
    private String waitReason;
    private String resultMessage;
    private int retryCount;
    private Integer queuePosition;
    private LocalDateTime estimatedEarliestAt;
    private LocalDateTime nextAttemptAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private int captchaRetryCount;
    private int captchaRetryLimit = 3;

    public PublishTaskStatusDto(Long taskId, Long historyId, Long accountId, PublishTaskOperation operation,
                                PublishTaskState state, String waitReason, String resultMessage,
                                int retryCount, Integer queuePosition, LocalDateTime estimatedEarliestAt,
                                LocalDateTime nextAttemptAt, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.taskId = taskId;
        this.historyId = historyId;
        this.accountId = accountId;
        this.operation = operation;
        this.state = state;
        this.waitReason = waitReason;
        this.resultMessage = resultMessage;
        this.retryCount = retryCount;
        this.queuePosition = queuePosition;
        this.estimatedEarliestAt = estimatedEarliestAt;
        this.nextAttemptAt = nextAttemptAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getLabel() {
        if (state == null) return "投稿状态未知";
        return switch (state) {
            case READY -> "已受理，等待投稿";
            case PREPARING -> "准备投稿材料";
            case WAITING_UPLOAD -> switch (waitReason == null ? "" : waitReason) {
                case "RECORDING" -> "等待录制结束";
                case "MERGE_INTERVAL" -> "等待合并";
                case "RECORDING_END_UNKNOWN" -> "等待确认录制结束";
                default -> "等待分P上传";
            };
            case WAITING_ACCOUNT -> "等待账号可用";
            case WAITING_CAPTCHA -> "等待验证码";
            case SUBMITTING -> "正在投稿";
            case VERIFYING -> "正在核对投稿结果";
            case RETRY_WAIT -> "等待自动重试";
            case NEEDS_ACTION -> "需要处理";
            case SUCCEEDED -> "投稿已完成";
            case FAILED -> "投稿失败";
            case CANCELLED -> "已取消";
        };
    }

    public String getDetail() {
        return resultMessage;
    }

    public Integer getPosition() { return queuePosition; }

    public String getCode() {
        if (state == PublishTaskState.WAITING_CAPTCHA) return "CAPTCHA";
        if (state == PublishTaskState.WAITING_UPLOAD) return "UPLOAD";
        if (state == PublishTaskState.WAITING_ACCOUNT) return "ACCOUNT";
        if (state == PublishTaskState.NEEDS_ACTION || state == PublishTaskState.FAILED) return "ACTION";
        return state == null ? null : state.name();
    }

    public String getWaitReasonLabel() {
        if (waitReason == null) return null;
        return switch (waitReason) {
            case "PARTS_NOT_READY", "EDIT_PARTS_UPLOAD" -> "正在准备或上传分P";
            case "RECORDING" -> "稿件仍在录制";
            case "MERGE_INTERVAL" -> "等待短时开播合并";
            case "RECORDING_END_UNKNOWN" -> "尚未确认录制结束时间";
            case "ACCOUNT_COOLDOWN" -> "账号处于投稿冷却";
            case "ACCOUNT_CAPTCHA", "PUBLISH_CAPTCHA", "UPLOAD_CAPTCHA" -> "账号需要完成验证码";
            case "SUBMISSION_RESULT_UNKNOWN" -> "平台结果待核对";
            case "CAPTCHA_AUTO_RETRY" -> "验证码过期，等待低频自动尝试";
            case "CAPTCHA_AUTO_RETRY_WAIT" -> "同账号的验证码自动尝试等待中";
            case "CAPTCHA_RETRY_EXHAUSTED" -> "自动尝试次数已用完，账号队列已暂停";
            case "CAPTCHA_ACCOUNT_BLOCKED" -> "账号等待验证码任务恢复";
            case "CAPTCHA_CANCELLED" -> "验证码已取消，需要手动处理";
            case "CAPTCHA_EXPIRED" -> "验证码已过期，需要重新验证";
            case "UPLOAD_CAPTCHA_EXPIRED" -> "分P上传验证码已过期，需要重新验证";
            case "ACCOUNT_NOT_LOGGED_IN" -> "投稿账号登录失效";
            case "ORIGINAL_ACCOUNT_MISSING" -> "旧稿件缺少原投稿账号";
            case "ACCOUNT_CHANGED" -> "房间投稿账号已变化";
            default -> waitReason;
        };
    }
}
