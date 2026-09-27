package top.sshh.bililiverecoder.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.BiliBiliUser;
import top.sshh.bililiverecoder.repo.BiliUserRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

/** 每个账号单独保存投稿冷却状态，全局接口限流仍然照常生效 */
@Slf4j
@Service
public class PublishAccountCooldownService {
    public record CaptchaProbeGate(Long taskId, LocalDateTime retryAt) {}
    private static final long ACCOUNT_INTERVAL_MS = 30_000L;
    private final BiliUserRepository users;
    private final long[] riskBackoffMs;

    public PublishAccountCooldownService(BiliUserRepository users,
            @Value("${publish.account.risk-backoff-minutes:5,15,30,60}") String riskBackoffMinutes) {
        this.users = users;
        this.riskBackoffMs = parseBackoff(riskBackoffMinutes);
    }

    @Transactional
    public void recordRisk(Long accountId) {
        if (accountId == null) return;
        users.findById(accountId).ifPresent(user -> {
            LocalDateTime now = LocalDateTime.now();
            int failures = user.getPublishLastRiskAt() != null
                    && user.getPublishLastRiskAt().isAfter(now.minusHours(24))
                    ? Math.max(0, user.getPublishRiskFailures() == null ? 0 : user.getPublishRiskFailures()) + 1 : 1;
            int index = Math.min(Math.max(0, failures - 1), riskBackoffMs.length - 1);
            user.setPublishRiskFailures(failures);
            user.setPublishLastRiskAt(now);
            user.setPublishSuccessStreak(0);
            extend(user, now.plus(Duration.ofMillis(riskBackoffMs[index])));
            users.save(user);
            log.warn("Publish risk cooldown accountId={} consecutiveFailures={} waitMs={}",
                    accountId, failures, riskBackoffMs[index]);
        });
    }

    @Transactional
    public void pause(Long accountId, long durationMs) {
        if (accountId == null) return;
        users.findById(accountId).ifPresent(user -> {
            extend(user, LocalDateTime.now().plus(Duration.ofMillis(Math.max(0L, durationMs))));
            users.save(user);
        });
    }

    @Transactional
    public void recordSuccess(Long accountId) {
        if (accountId == null) return;
        users.findById(accountId).ifPresent(user -> {
            int streak = Math.max(0, user.getPublishSuccessStreak() == null ? 0 : user.getPublishSuccessStreak()) + 1;
            user.setPublishSuccessStreak(streak);
            if (streak >= 3 && (user.getPublishLastRiskAt() == null
                    || user.getPublishLastRiskAt().isBefore(LocalDateTime.now().minusMinutes(10)))) {
                user.setPublishRiskFailures(0);
                user.setPublishLastRiskAt(null);
                user.setPublishCooldownUntil(null);
                user.setPublishSuccessStreak(0);
            }
            users.save(user);
        });
    }

    @Transactional
    public void recordSubmissionFinished(Long accountId) {
        if (accountId == null) return;
        users.findById(accountId).ifPresent(user -> {
            user.setPublishNextAllowedAt(LocalDateTime.now().plus(Duration.ofMillis(ACCOUNT_INTERVAL_MS)));
            users.save(user);
        });
    }

    @Transactional
    public int resetExpiredRisk() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(24);
        AtomicInteger reset = new AtomicInteger();
        users.findAll().forEach(user -> {
            if (user.getPublishLastRiskAt() == null || !user.getPublishLastRiskAt().isBefore(cutoff)) return;
            user.setPublishRiskFailures(0);
            user.setPublishLastRiskAt(null);
            user.setPublishSuccessStreak(0);
            if (user.getPublishCooldownUntil() != null && user.getPublishCooldownUntil().isBefore(LocalDateTime.now())) {
                user.setPublishCooldownUntil(null);
            }
            users.save(user);
            reset.incrementAndGet();
        });
        return reset.get();
    }

    @Transactional(readOnly = true)
    public long waitMs(Long accountId) {
        if (accountId == null) return 0L;
        return users.findById(accountId).map(user -> {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime until = user.getPublishCooldownUntil();
            if (user.getPublishNextAllowedAt() != null
                    && (until == null || user.getPublishNextAllowedAt().isAfter(until))) {
                until = user.getPublishNextAllowedAt();
            }
            return until == null ? 0L : Math.max(0L, Duration.between(now, until).toMillis());
        }).orElse(0L);
    }

    @Transactional(readOnly = true)
    public CaptchaProbeGate captchaProbeGate(Long accountId) {
        if (accountId == null) return null;
        return users.findById(accountId)
                .filter(user -> user.getPublishCaptchaProbeTaskId() != null)
                .map(user -> new CaptchaProbeGate(user.getPublishCaptchaProbeTaskId(),
                        user.getPublishCaptchaRetryAt()))
                .orElse(null);
    }

    private static void extend(BiliBiliUser user, LocalDateTime until) {
        if (user.getPublishCooldownUntil() == null || user.getPublishCooldownUntil().isBefore(until)) {
            user.setPublishCooldownUntil(until);
        }
    }

    private static long[] parseBackoff(String value) {
        try {
            String[] parts = value.split(",");
            if (parts.length == 0) throw new IllegalArgumentException("empty backoff");
            long[] result = new long[parts.length];
            for (int i = 0; i < parts.length; i++) {
                result[i] = Math.multiplyExact(Long.parseLong(parts[i].trim()), 60_000L);
                if (result[i] <= 0L) throw new IllegalArgumentException("non-positive backoff");
            }
            return result;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid publish.account.risk-backoff-minutes: " + value, e);
        }
    }
}
