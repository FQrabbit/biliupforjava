package top.sshh.bililiverecoder.util.retry;

import java.util.concurrent.ThreadLocalRandom;

public class UploadRetryBackoffPolicy {

    private static final long MAX_DELAY_MS = 60000L;

    public long nextDelayMs(int retryCount, String errMsg) {
        int retry = Math.max(1, retryCount);
        long base;
        if (containsAny(errMsg, "500", "504")) {
            base = 12000L;
        } else if (containsAny(errMsg, "Low upload speed", "Netty upload failed")) {
            base = 8000L;
        } else {
            base = 5000L;
        }
        long exp = base * (1L << Math.min(5, retry - 1));
        long jitter = ThreadLocalRandom.current().nextLong(500L, 2000L);
        return Math.min(MAX_DELAY_MS, exp + jitter);
    }

    private boolean containsAny(String text, String first, String second) {
        String value = text == null ? "" : text;
        return value.contains(first) || value.contains(second);
    }
}
