package top.sshh.bililiverecoder.service;

/** 区分平台明确拒绝投稿和投稿结果暂时无法确定这两种情况 */
public final class PublishSubmissionException extends RuntimeException {
    public enum Outcome { REJECTED, UNKNOWN }

    private final Outcome outcome;

    private PublishSubmissionException(Outcome outcome, String message, Throwable cause) {
        super(message, cause);
        this.outcome = outcome;
    }

    public static PublishSubmissionException rejected(Integer code, String message) {
        return new PublishSubmissionException(Outcome.REJECTED,
                "平台明确拒绝投稿：code=" + code + ", message=" + message, null);
    }

    public static PublishSubmissionException unknown(String message, Throwable cause) {
        return new PublishSubmissionException(Outcome.UNKNOWN,
                "投稿请求已发出，但无法确认平台结果：" + message, cause);
    }

    public Outcome getOutcome() { return outcome; }
}
