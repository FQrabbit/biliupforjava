package top.sshh.bililiverecoder.util.netty;

public class SlowSpeedJudge {

    public enum Classification {
        NORMAL,
        RATE_LIMITED_EXPECTED,
        ABNORMAL_SLOW
    }

    public static class Snapshot {
        private final long rawSpeed;
        private final long smoothSpeed;
        private final long threshold;
        private final Classification classification;

        public Snapshot(long rawSpeed, long smoothSpeed, long threshold, Classification classification) {
            this.rawSpeed = rawSpeed;
            this.smoothSpeed = smoothSpeed;
            this.threshold = threshold;
            this.classification = classification;
        }

        public long getRawSpeed() {
            return rawSpeed;
        }

        public long getSmoothSpeed() {
            return smoothSpeed;
        }

        public long getThreshold() {
            return threshold;
        }

        public Classification getClassification() {
            return classification;
        }
    }

    private double ema = -1;
    private final double alpha;
    private int consecutiveLowHits;

    public SlowSpeedJudge(int smoothWindow) {
        int window = Math.max(smoothWindow, 2);
        this.alpha = 2.0 / (window + 1.0);
    }

    public Snapshot evaluate(long speed, long writeLimit) {
        if (ema < 0) {
            ema = speed;
        } else {
            ema = alpha * speed + (1 - alpha) * ema;
        }
        long smoothSpeed = Math.max(0, Math.round(ema));
        long threshold = UploadThrottlePolicy.dynamicLowSpeedThreshold(writeLimit);
        if (UploadThrottlePolicy.isRateLimitedExpected(smoothSpeed, writeLimit, threshold)) {
            consecutiveLowHits = 0;
            return new Snapshot(speed, smoothSpeed, threshold, Classification.RATE_LIMITED_EXPECTED);
        }
        if (smoothSpeed < threshold) {
            consecutiveLowHits++;
            if (consecutiveLowHits >= UploadThrottlePolicy.getMinConsecutiveLowSpeedHits()) {
                return new Snapshot(speed, smoothSpeed, threshold, Classification.ABNORMAL_SLOW);
            }
            return new Snapshot(speed, smoothSpeed, threshold, Classification.NORMAL);
        }
        consecutiveLowHits = 0;
        return new Snapshot(speed, smoothSpeed, threshold, Classification.NORMAL);
    }

    public int getConsecutiveLowHits() {
        return consecutiveLowHits;
    }
}
