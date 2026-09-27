package top.sshh.bililiverecoder.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.util.LogKvs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Slf4j
@Service
public class CaptchaService {
    public record ChallengeStatus(String requestId, String voucher, String filename,
                                  Map<String, Object> extra, Long accountId, Long historyId,
                                  String state, long expiresAtEpochMs, Long taskId, String stage) {}

    private static final class Challenge {
        final ChallengeStatus status;
        final CountDownLatch latch = new CountDownLatch(1);
        final Consumer<Map<String, String>> onSubmit;
        final AtomicBoolean callbackRunning = new AtomicBoolean();
        final AtomicBoolean callbackCompleted = new AtomicBoolean();
        volatile Map<String, String> result;
        volatile String state = "PENDING";

        Challenge(ChallengeStatus status, Consumer<Map<String, String>> onSubmit) {
            this.status = status;
            this.onSubmit = onSubmit;
        }
    }

    private static final long CHALLENGE_TTL_MS = TimeUnit.MINUTES.toMillis(5);
    private final Map<String, Challenge> challenges = new ConcurrentHashMap<>();
    private final ThreadLocal<String> currentRequestId = new ThreadLocal<>();

    public void setCaptchaRequired(String voucher, String filename, Map<String, Object> extraInfo) {
        setCaptchaRequired(voucher, filename, extraInfo, null, null);
    }

    public void setCaptchaRequired(String voucher, String filename, Map<String, Object> extraInfo,
                                   Long accountId, Long historyId) {
        setCaptchaRequired(voucher, filename, extraInfo, accountId, historyId, null, "UPLOAD");
    }

    public synchronized void setCaptchaRequired(String voucher, String filename, Map<String, Object> extraInfo,
                                   Long accountId, Long historyId, Long taskId, String stage) {
        Challenge existing = findActive(accountId, historyId, taskId, stage);
        if (existing != null) {
            currentRequestId.set(existing.status.requestId());
            return;
        }
        String requestId = UUID.randomUUID().toString();
        long expiresAt = System.currentTimeMillis() + CHALLENGE_TTL_MS;
        Challenge challenge = new Challenge(new ChallengeStatus(requestId, voucher, filename,
                extraInfo == null ? Map.of() : new HashMap<>(extraInfo), accountId, historyId,
                "PENDING", expiresAt, taskId, stage), null);
        challenges.put(requestId, challenge);
        currentRequestId.set(requestId);
    }

    public String deferPublish(String voucher, String filename, Map<String, Object> extraInfo,
                               Long accountId, Long historyId, Consumer<Map<String, String>> onSubmit) {
        return deferPublish(voucher, filename, extraInfo, accountId, historyId, null, "PUBLISH", onSubmit);
    }

    public synchronized String deferPublish(String voucher, String filename, Map<String, Object> extraInfo,
                               Long accountId, Long historyId, Long taskId, String stage,
                               Consumer<Map<String, String>> onSubmit) {
        Challenge existing = findActive(accountId, historyId, taskId, stage);
        if (existing != null) return existing.status.requestId();
        String requestId = UUID.randomUUID().toString();
        long expiresAt = System.currentTimeMillis() + CHALLENGE_TTL_MS;
        challenges.put(requestId, new Challenge(new ChallengeStatus(requestId, voucher, filename,
                extraInfo == null ? Map.of() : new HashMap<>(extraInfo), accountId, historyId,
                "PENDING", expiresAt, taskId, stage), onSubmit));
        return requestId;
    }

    public boolean hasPendingForAccount(Long accountId) {
        expireChallenges();
        return accountId != null && challenges.values().stream()
                .anyMatch(challenge -> isActive(challenge) && accountId.equals(challenge.status.accountId()));
    }

    public boolean hasPendingForHistory(Long historyId) {
        expireChallenges();
        return historyId != null && challenges.values().stream()
                .anyMatch(challenge -> isActive(challenge) && historyId.equals(challenge.status.historyId()));
    }

    public boolean hasPendingForTask(Long taskId) {
        expireChallenges();
        return taskId != null && challenges.values().stream()
                .anyMatch(challenge -> isActive(challenge) && taskId.equals(challenge.status.taskId()));
    }

    public ChallengeStatus challengeForTask(Long taskId) {
        expireChallenges();
        if (taskId == null) return null;
        return challenges.values().stream()
                .filter(challenge -> taskId.equals(challenge.status.taskId()))
                .max(Comparator.comparingLong(challenge -> challenge.status.expiresAtEpochMs()))
                .map(CaptchaService::status).orElse(null);
    }

    public ChallengeStatus pendingChallengeForTask(Long taskId) {
        expireChallenges();
        if (taskId == null) return null;
        return challenges.values().stream()
                .filter(CaptchaService::isActive)
                .filter(challenge -> taskId.equals(challenge.status.taskId()))
                .max(Comparator.comparingLong(challenge -> challenge.status.expiresAtEpochMs()))
                .map(CaptchaService::status).orElse(null);
    }

    public List<ChallengeStatus> pendingChallenges() {
        expireChallenges();
        List<ChallengeStatus> result = new ArrayList<>();
        for (Challenge challenge : challenges.values()) {
            if (!isActive(challenge)) continue;
            result.add(status(challenge));
        }
        result.sort(Comparator.comparing(ChallengeStatus::requestId));
        return result;
    }

    public List<ChallengeStatus> recentChallenges() {
        expireChallenges();
        return challenges.values().stream().map(CaptchaService::status)
                .sorted(Comparator.comparing(ChallengeStatus::requestId)).toList();
    }

    public ChallengeStatus challengeStatus(String requestId) {
        expireChallenges();
        Challenge challenge = challenges.get(requestId);
        return challenge == null ? null : status(challenge);
    }

    public boolean isCaptchaRequired() { return !pendingChallenges().isEmpty(); }

    private ChallengeStatus firstPending() {
        return pendingChallenges().stream().findFirst().orElse(null);
    }

    public String getVoucher() {
        ChallengeStatus status = firstPending();
        return status == null ? null : status.voucher();
    }

    public String getFilename() {
        ChallengeStatus status = firstPending();
        return status == null ? null : status.filename();
    }

    public Map<String, Object> getExtraInfo() {
        ChallengeStatus status = firstPending();
        return status == null ? null : status.extra();
    }

    public Map<String, String> waitForCaptcha() {
        String requestId = currentRequestId.get();
        Challenge challenge = challenges.get(requestId);
        if (challenge == null) {
            currentRequestId.remove();
            return null;
        }
        try {
            if (challenge.state.equals("SUBMITTED") || challenge.result != null
                    || challenge.latch.await(Math.max(1L,
                    challenge.status.expiresAtEpochMs() - System.currentTimeMillis()), TimeUnit.MILLISECONDS)) {
                synchronized (challenge) {
                    if (challenge.result == null) return null;
                    Map<String, String> result = new HashMap<>(challenge.result);
                    challenge.result = null;
                    challenge.state = "CONSUMED";
                    return result;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[BLR] {}", LogKvs.event("Captcha.WaitInterrupted")
                    .add("filename", challenge.status.filename()).add("requestId", requestId), e);
        } finally {
            currentRequestId.remove();
        }
        expireChallenges();
        return null;
    }

    public boolean submitCaptcha(String requestId, Map<String, String> result) {
        expireChallenges();
        Challenge challenge = challenges.get(requestId);
        if (challenge == null) return false;
        synchronized (challenge) {
            if (challenge.state.equals("CONSUMED")) return true;
            if (challenge.state.equals("CANCELLED") || challenge.state.equals("EXPIRED")) return false;
            if (challenge.result == null) {
                challenge.result = new HashMap<>(result == null ? Map.of() : result);
                challenge.state = "SUBMITTED";
                challenge.latch.countDown();
            }
        }
        if (challenge.onSubmit == null || challenge.callbackCompleted.get()) return true;
        if (!challenge.callbackRunning.compareAndSet(false, true)) return true;
        Map<String, String> submitted = new HashMap<>(challenge.result);
        synchronized (challenge) {
            challenge.result = null;
            challenge.state = "CONSUMED";
        }
        try {
            challenge.onSubmit.accept(submitted);
            challenge.callbackCompleted.set(true);
            return true;
        } catch (RuntimeException e) {
            synchronized (challenge) {
                if (challenge.state.equals("CONSUMED")) {
                    challenge.result = submitted;
                    challenge.state = "SUBMITTED";
                }
            }
            challenge.callbackRunning.set(false);
            log.error("Captcha result callback failed requestId={}", requestId, e);
            return false;
        }
    }

    public boolean submitCaptcha(Map<String, String> result) {
        List<ChallengeStatus> pending = pendingChallenges();
        return pending.size() == 1 && submitCaptcha(pending.get(0).requestId(), result);
    }

    public boolean cancel(String requestId) {
        Challenge challenge = challenges.get(requestId);
        if (challenge == null) return false;
        synchronized (challenge) {
            if (!isActive(challenge)) return false;
            challenge.result = null;
            challenge.state = "CANCELLED";
            challenge.latch.countDown();
            return true;
        }
    }

    public void cancelForTask(Long taskId) {
        if (taskId == null) return;
        challenges.values().stream().filter(challenge -> taskId.equals(challenge.status.taskId()))
                .map(challenge -> challenge.status.requestId()).toList().forEach(this::cancel);
    }

    private Challenge findActive(Long accountId, Long historyId, Long taskId, String stage) {
        if (accountId == null && historyId == null && taskId == null) return null;
        expireChallenges();
        return challenges.values().stream()
                .filter(CaptchaService::isActive)
                .filter(challenge -> java.util.Objects.equals(accountId, challenge.status.accountId())
                        && java.util.Objects.equals(historyId, challenge.status.historyId())
                        && java.util.Objects.equals(taskId, challenge.status.taskId())
                        && java.util.Objects.equals(stage, challenge.status.stage()))
                .findFirst().orElse(null);
    }

    private void expireChallenges() {
        long now = System.currentTimeMillis();
        challenges.entrySet().removeIf(entry -> {
            Challenge challenge = entry.getValue();
            if (challenge.status.expiresAtEpochMs() > now) return false;
            if (isActive(challenge)) {
                synchronized (challenge) {
                    challenge.result = null;
                    challenge.state = "EXPIRED";
                    challenge.latch.countDown();
                }
                log.warn("Captcha challenge expired requestId={} taskId={} historyId={} accountId={}",
                        challenge.status.requestId(), challenge.status.taskId(),
                        challenge.status.historyId(), challenge.status.accountId());
                return false;
            }
            return challenge.status.expiresAtEpochMs() + CHALLENGE_TTL_MS <= now;
        });
    }

    private static boolean isActive(Challenge challenge) {
        return "PENDING".equals(challenge.state) || "SUBMITTED".equals(challenge.state);
    }

    private static ChallengeStatus status(Challenge challenge) {
        ChallengeStatus original = challenge.status;
        return new ChallengeStatus(original.requestId(), original.voucher(), original.filename(), original.extra(),
                original.accountId(), original.historyId(), challenge.state, original.expiresAtEpochMs(),
                original.taskId(), original.stage());
    }
}
