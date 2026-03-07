package top.sshh.bililiverecoder.util.netty;

public class UploadThrottlePolicy {

    private static final long LOW_SPEED_BASE_INTERVAL_MS = 3000;
    private static final long LOW_SPEED_CONFIRM_DELAY_MS = 10000;
    private static final long LOW_SPEED_MAX_INTERVAL_MS = 30000;
    private static final long LOW_SPEED_RECOVERY_STEP_MS = 500;
    private static final long LOW_SPEED_WARMUP_MS = 15000;
    private static final long LOW_SPEED_MIN_WRITTEN_BYTES = 32 * 1024;
    private static final long ZERO_SPEED_CONFIRM_MS = 60000;
    private static final long LOW_SPEED_MIN_THRESHOLD_BPS = 1024;
    private static final long LOW_SPEED_MAX_THRESHOLD_BPS = 10 * 1024;
    private static final int LOW_SPEED_MIN_CONSECUTIVE = 3;
    private static final double LOW_SPEED_THRESHOLD_RATIO = 0.3D;
    private static final double RATE_LIMITED_EXPECTED_RATIO = 0.8D;
    private static final int EMA_SMOOTH_WINDOW = 5;

    private UploadThrottlePolicy() {
    }

    public static long getBaseIntervalMs() {
        return LOW_SPEED_BASE_INTERVAL_MS;
    }

    public static long getConfirmDelayMs() {
        return LOW_SPEED_CONFIRM_DELAY_MS;
    }

    public static long getMaxIntervalMs() {
        return LOW_SPEED_MAX_INTERVAL_MS;
    }

    public static long getRecoveryStepMs() {
        return LOW_SPEED_RECOVERY_STEP_MS;
    }

    public static long getWarmupMs() {
        return LOW_SPEED_WARMUP_MS;
    }

    public static long getMinWrittenBytes() {
        return LOW_SPEED_MIN_WRITTEN_BYTES;
    }

    public static long getZeroSpeedConfirmMs() {
        return ZERO_SPEED_CONFIRM_MS;
    }

    public static int getEmaSmoothWindow() {
        return EMA_SMOOTH_WINDOW;
    }

    public static int getMinConsecutiveLowSpeedHits() {
        return LOW_SPEED_MIN_CONSECUTIVE;
    }

    public static long dynamicLowSpeedThreshold(long writeLimit) {
        long dynamic = (long) (writeLimit * LOW_SPEED_THRESHOLD_RATIO);
        if (dynamic <= 0) {
            dynamic = LOW_SPEED_MAX_THRESHOLD_BPS;
        }
        if (dynamic < LOW_SPEED_MIN_THRESHOLD_BPS) {
            return LOW_SPEED_MIN_THRESHOLD_BPS;
        }
        return Math.min(dynamic, LOW_SPEED_MAX_THRESHOLD_BPS);
    }

    public static boolean isRateLimitedExpected(long speed, long writeLimit, long threshold) {
        if (writeLimit <= 0 || writeLimit >= Long.MAX_VALUE / 4) {
            return false;
        }
        long expectedFloor = Math.max(threshold, (long) (writeLimit * RATE_LIMITED_EXPECTED_RATIO));
        return speed >= expectedFloor;
    }
}
