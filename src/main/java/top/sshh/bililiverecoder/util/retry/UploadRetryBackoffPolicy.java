package top.sshh.bililiverecoder.util.retry;

import java.util.concurrent.ThreadLocalRandom;

public class UploadRetryBackoffPolicy {

    private static final long DEFAULT_MAX_DELAY_MS = 15000L;
    private static final int MAX_EXP_SHIFT = 4;

    public long nextDelayMs(int retryCount, String errMsg) {
        return nextDecision(retryCount, null, errMsg).delayMs();
    }

    public BackoffDecision nextDecision(int retryCount, Throwable throwable, String errMsg) {
        int retry = Math.max(1, retryCount);
        UploadRetryClassifier.UploadRetryAssessment assessment = UploadRetryClassifier.assess(throwable, errMsg);
        String retryCategory = assessment.category();
        long baseDelay = baseDelayMs(retryCategory);
        long maxDelay = maxDelayMs(retryCategory);
        long exp = baseDelay * (1L << Math.min(MAX_EXP_SHIFT, retry - 1));
        long jitter = ThreadLocalRandom.current().nextLong(baseDelay / 3, baseDelay + 1);
        long delay = Math.min(maxDelay, exp + jitter);
        return new BackoffDecision(delay, retryCategory, assessment.retryable(), assessment.userMessage(), assessment.remoteCode());
    }

    private long baseDelayMs(String retryCategory) {
        return switch (retryCategory) {
            case UploadRetryClassifier.GATEWAY_5XX -> 6000L;
            case UploadRetryClassifier.RATE_LIMIT -> 8000L;
            case UploadRetryClassifier.TIMEOUT -> 5000L;
            case UploadRetryClassifier.DNS -> 4000L;
            case UploadRetryClassifier.NETWORK_IO -> 3000L;
            case UploadRetryClassifier.LOW_SPEED -> 2500L;
            case UploadRetryClassifier.AUTH, UploadRetryClassifier.HTTP_4XX -> 9000L;
            case UploadRetryClassifier.HTTP_5XX -> 5000L;
            case UploadRetryClassifier.REMOTE_THROTTLED -> 3500L;
            default -> 2200L;
        };
    }

    private long maxDelayMs(String retryCategory) {
        return switch (retryCategory) {
            case UploadRetryClassifier.GATEWAY_5XX -> 32000L;
            case UploadRetryClassifier.RATE_LIMIT -> 36000L;
            case UploadRetryClassifier.TIMEOUT -> 26000L;
            case UploadRetryClassifier.DNS -> 24000L;
            case UploadRetryClassifier.NETWORK_IO -> 18000L;
            case UploadRetryClassifier.LOW_SPEED -> 12000L;
            case UploadRetryClassifier.AUTH, UploadRetryClassifier.HTTP_4XX -> 30000L;
            case UploadRetryClassifier.HTTP_5XX -> 28000L;
            case UploadRetryClassifier.REMOTE_THROTTLED -> 16000L;
            default -> DEFAULT_MAX_DELAY_MS;
        };
    }

    public record BackoffDecision(long delayMs, String retryCategory, boolean retryable, String userMessage, String remoteCode) {
    }
}
