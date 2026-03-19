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
        String retryCategory = classify(throwable, errMsg);
        long baseDelay = baseDelayMs(retryCategory);
        long maxDelay = maxDelayMs(retryCategory);
        long exp = baseDelay * (1L << Math.min(MAX_EXP_SHIFT, retry - 1));
        long jitter = ThreadLocalRandom.current().nextLong(baseDelay / 3, baseDelay + 1);
        long delay = Math.min(maxDelay, exp + jitter);
        return new BackoffDecision(delay, retryCategory);
    }

    private String classify(Throwable throwable, String errMsg) {
        Throwable root = rootCause(throwable);
        String message = (errMsg == null ? "" : errMsg).toLowerCase();
        String rootName = root == null ? "" : root.getClass().getSimpleName().toLowerCase();

        if (message.contains("500")
                || message.contains("502")
                || message.contains("503")
                || message.contains("504")
                || message.contains("gateway")) {
            return "GATEWAY_5XX";
        }
        if (message.contains("429")
                || message.contains("rate limit")
                || message.contains("too many requests")
                || message.contains("频控")
                || message.contains("限流")) {
            return "RATE_LIMIT";
        }
        if (message.contains("low upload speed")) {
            return "LOW_SPEED";
        }
        if (root instanceof java.net.UnknownHostException || rootName.contains("unknownhost")) {
            return "DNS";
        }
        if (root instanceof java.net.SocketTimeoutException
                || root instanceof java.io.InterruptedIOException
                || rootName.contains("timeout")) {
            return "TIMEOUT";
        }
        if (root instanceof java.net.ConnectException
                || root instanceof java.net.NoRouteToHostException
                || root instanceof java.net.SocketException
                || root instanceof java.io.IOException
                || rootName.contains("ssl")
                || message.contains("connection reset")
                || message.contains("broken pipe")
                || message.contains("netty upload failed")) {
            return "NETWORK_IO";
        }
        if (message.contains("401") || message.contains("403") || message.contains("forbidden")) {
            return "AUTH";
        }
        if (message.contains("4xx") || message.contains("400")) {
            return "HTTP_4XX";
        }
        if (message.contains("5xx")) {
            return "HTTP_5XX";
        }
        return "UNKNOWN";
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null && cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return cursor;
    }

    private long baseDelayMs(String retryCategory) {
        return switch (retryCategory) {
            case "GATEWAY_5XX" -> 6000L;
            case "RATE_LIMIT" -> 8000L;
            case "TIMEOUT" -> 5000L;
            case "DNS" -> 4000L;
            case "NETWORK_IO" -> 3000L;
            case "LOW_SPEED" -> 2500L;
            case "AUTH", "HTTP_4XX" -> 9000L;
            case "HTTP_5XX" -> 5000L;
            default -> 2200L;
        };
    }

    private long maxDelayMs(String retryCategory) {
        return switch (retryCategory) {
            case "GATEWAY_5XX" -> 32000L;
            case "RATE_LIMIT" -> 36000L;
            case "TIMEOUT" -> 26000L;
            case "DNS" -> 24000L;
            case "NETWORK_IO" -> 18000L;
            case "LOW_SPEED" -> 12000L;
            case "AUTH", "HTTP_4XX" -> 30000L;
            case "HTTP_5XX" -> 28000L;
            default -> DEFAULT_MAX_DELAY_MS;
        };
    }

    public record BackoffDecision(long delayMs, String retryCategory) {
    }
}
