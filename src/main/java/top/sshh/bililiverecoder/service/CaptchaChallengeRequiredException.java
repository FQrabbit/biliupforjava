package top.sshh.bililiverecoder.service;

/** 投稿上传遇到验证码时，用于暂停当前执行并在验证完成后恢复 */
public final class CaptchaChallengeRequiredException extends RuntimeException {
    private final String requestId;

    public CaptchaChallengeRequiredException(String requestId) {
        super("等待验证码处理");
        this.requestId = requestId;
    }

    public String getRequestId() {
        return requestId;
    }

    public static CaptchaChallengeRequiredException find(Throwable error) {
        Throwable current = error;
        java.util.Set<Throwable> visited = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        while (current != null && visited.add(current)) {
            if (current instanceof CaptchaChallengeRequiredException required) return required;
            current = current.getCause();
        }
        return null;
    }
}
