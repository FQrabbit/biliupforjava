package top.sshh.bililiverecoder.service;

import com.alibaba.fastjson.JSON;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.BiliBiliUser;
import top.sshh.bililiverecoder.entity.PublishTask;
import top.sshh.bililiverecoder.entity.PublishTaskOperation;
import top.sshh.bililiverecoder.entity.PublishTaskSource;
import top.sshh.bililiverecoder.entity.PublishTaskState;
import top.sshh.bililiverecoder.entity.PublishTaskStatusDto;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.repo.BiliUserRepository;
import top.sshh.bililiverecoder.repo.PublishTaskRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
public class PublishTaskService {
    private static final Set<PublishTaskState> ACTIVE_STATES = EnumSet.of(
            PublishTaskState.READY, PublishTaskState.PREPARING, PublishTaskState.WAITING_UPLOAD,
            PublishTaskState.WAITING_ACCOUNT, PublishTaskState.WAITING_CAPTCHA,
            PublishTaskState.SUBMITTING, PublishTaskState.VERIFYING, PublishTaskState.RETRY_WAIT,
            PublishTaskState.NEEDS_ACTION);
    private static final Set<PublishTaskState> TERMINAL_STATES = EnumSet.of(
            PublishTaskState.SUCCEEDED, PublishTaskState.FAILED, PublishTaskState.CANCELLED);

    private final PublishTaskRepository tasks;
    private final RecordHistoryRepository histories;
    private final BiliUserRepository users;
    private final Object[] historyLocks = new Object[128];

    public PublishTaskService(PublishTaskRepository tasks, RecordHistoryRepository histories,
                              BiliUserRepository users) {
        this.tasks = tasks;
        this.histories = histories;
        this.users = users;
        for (int i = 0; i < historyLocks.length; i++) historyLocks[i] = new Object();
    }

    @Transactional
    public Admission accept(Long historyId, Long accountId, PublishTaskOperation operation,
                            PublishTaskSource source, Object requestSnapshot) {
        if (historyId == null || accountId == null || operation == null || source == null) {
            return Admission.rejected("缺少稿件、投稿账号或任务类型");
        }
        Object lock = historyLocks[Math.floorMod(historyId.hashCode(), historyLocks.length)];
        synchronized (lock) {
            RecordHistory history = histories.findById(historyId).orElse(null);
            if (history == null) return Admission.rejected("稿件不存在");
            if (history.isForceArchived()) return Admission.rejected("稿件已强制归档");
            if (operation == PublishTaskOperation.NEW_PUBLISH && history.isPublish()) {
                return Admission.rejected("该稿件已有发布记录，请使用稿件更新或分P编辑任务");
            }
            BiliBiliUser user = users.findById(accountId).orElse(null);
            if (user == null || !user.isLogin()) return Admission.rejected("投稿账号不存在或未登录");

            List<PublishTask> active = tasks.findByHistoryIdAndStateInOrderByCreatedAtAsc(historyId, ACTIVE_STATES);
            List<PublishTask> ordinary = active.stream()
                    .filter(task -> task.getOperation() != PublishTaskOperation.HIGH_ENERGY).toList();
            PublishTask existing = active.stream().filter(task -> task.getOperation() == operation).findFirst().orElse(null);
            String snapshot = requestSnapshot == null ? null : JSON.toJSONString(requestSnapshot);
            if (existing != null) {
                if (!Objects.equals(existing.getAccountId(), accountId)) {
                    return Admission.rejected("稿件已有任务绑定其他投稿账号，不能静默更换账号");
                }
                if (operation == PublishTaskOperation.EDIT_PARTS
                        && !sameJson(existing.getRequestSnapshot(), snapshot)) {
                    return Admission.rejected("已有分P编辑任务使用不同请求内容，请等待结束后重试");
                }
                return Admission.duplicate(existing);
            }
            if (operation != PublishTaskOperation.HIGH_ENERGY && !ordinary.isEmpty()) {
                return Admission.rejected("该稿件已有未完成的投稿任务：" + ordinary.get(0).getOperation());
            }
            Long originalAccountId = history.getPublishUserId();
            if (history.isPublish() && originalAccountId != null && !originalAccountId.equals(accountId)) {
                return Admission.rejected("已发布稿件必须使用原投稿账号");
            }

            PublishTask task = new PublishTask();
            task.setHistoryId(historyId);
            task.setAccountId(accountId);
            task.setOperation(operation);
            task.setSource(source);
            task.setState(PublishTaskState.READY);
            task.setCreatedAt(LocalDateTime.now());
            task.setUpdatedAt(task.getCreatedAt());
            task.setRequestSnapshot(snapshot);
            task.setRetryCount(0);
            task.setResultMessage("任务已受理");
            return Admission.accepted(tasks.save(task));
        }
    }

    @Transactional
    public PublishTask transition(Long taskId, Set<PublishTaskState> expected,
                                  PublishTaskState state, String reason, String message,
                                  LocalDateTime nextAttemptAt, Integer retryCount) {
        PublishTask task = tasks.findById(taskId).orElse(null);
        if (task == null || (expected != null && !expected.contains(task.getState()))) return null;
        task.setState(state);
        task.setWaitReason(reason);
        task.setResultMessage(message);
        task.setNextAttemptAt(nextAttemptAt);
        task.setUpdatedAt(LocalDateTime.now());
        if (retryCount != null) task.setRetryCount(retryCount);
        if (state.isTerminal() || state != PublishTaskState.SUBMITTING) {
            task.setClaimToken(null);
            task.setLeaseUntil(null);
        }
        return tasks.save(task);
    }

    @Transactional
    public PublishTask claim(Long taskId, PublishTaskState state, String token, LocalDateTime leaseUntil) {
        PublishTask task = tasks.findById(taskId).orElse(null);
        if (task == null || !Set.of(PublishTaskState.READY, PublishTaskState.RETRY_WAIT,
                PublishTaskState.WAITING_ACCOUNT).contains(task.getState())) return null;
        if (task.getNextAttemptAt() != null && task.getNextAttemptAt().isAfter(LocalDateTime.now())) return null;
        task.setState(state);
        task.setWaitReason(null);
        task.setResultMessage("正在处理");
        task.setClaimToken(token);
        task.setLeaseUntil(leaseUntil);
        task.setNextAttemptAt(null);
        task.setUpdatedAt(LocalDateTime.now());
        return tasks.save(task);
    }

    @Transactional(readOnly = true)
    public PublishTask get(Long taskId) { return tasks.findById(taskId).orElse(null); }

    @Transactional(readOnly = true)
    public PublishTask getLatestForHistory(Long historyId) {
        return tasks.findTopByHistoryIdOrderByCreatedAtDesc(historyId);
    }

    @Transactional(readOnly = true)
    public List<PublishTask> getActiveForHistory(Long historyId) {
        return tasks.findByHistoryIdAndStateInOrderByCreatedAtAsc(historyId, ACTIVE_STATES);
    }

    @Transactional(readOnly = true)
    public PublishTask getUploadTaskForHistory(Long historyId) {
        return getActiveForHistory(historyId).stream()
                .filter(task -> task.getOperation() != PublishTaskOperation.HIGH_ENERGY)
                .filter(task -> task.getState() == PublishTaskState.PREPARING
                        || task.getState() == PublishTaskState.WAITING_UPLOAD)
                .findFirst().orElse(null);
    }

    @Transactional(readOnly = true)
    public List<PublishTask> getByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return tasks.findAllById(ids);
    }

    @Transactional(readOnly = true)
    public List<PublishTask> getByAccountAndStates(Long accountId, Set<PublishTaskState> states) {
        return tasks.findByAccountIdAndStateInOrderByCreatedAtAsc(accountId, states);
    }

    @Transactional(readOnly = true)
    public List<PublishTask> getByStates(Set<PublishTaskState> states) {
        return tasks.findByStateInOrderByCreatedAtAsc(states);
    }

    @Transactional(readOnly = true)
    public List<PublishTask> due(Set<PublishTaskState> states, LocalDateTime now) {
        return tasks.findByStateInAndNextAttemptAtBeforeOrderByCreatedAtAsc(states, now.plusNanos(1));
    }

    @Transactional
    public PublishTask setState(Long taskId, PublishTaskState state, String reason,
                                String message, LocalDateTime nextAttemptAt) {
        return transition(taskId, null, state, reason, message, nextAttemptAt, null);
    }

    @Transactional
    public PublishTask retry(Long taskId, boolean confirmedNotSubmitted) {
        PublishTask task = tasks.findById(taskId).orElse(null);
        if (task == null) return null;
        boolean unknownResult = task.getState() == PublishTaskState.VERIFYING
                || "SUBMISSION_RESULT_UNKNOWN".equals(task.getWaitReason())
                || "SUBMISSION_ID_MISMATCH".equals(task.getWaitReason());
        if (unknownResult && !confirmedNotSubmitted) return null;
        boolean captchaAutoRetryWaiting = task.getState() == PublishTaskState.RETRY_WAIT
                && "CAPTCHA_AUTO_RETRY".equals(task.getWaitReason());
        if (task.getState() != PublishTaskState.NEEDS_ACTION
                && task.getState() != PublishTaskState.FAILED
                && task.getState() != PublishTaskState.VERIFYING
                && !captchaAutoRetryWaiting) return null;
        boolean captchaGateOwner = task.getWaitReason() != null
                && (task.getWaitReason().startsWith("CAPTCHA_")
                || "SUBMISSION_RESULT_UNKNOWN".equals(task.getWaitReason())
                || "SUBMISSION_ID_MISMATCH".equals(task.getWaitReason()));
        boolean resetCaptchaRetryCycle = task.getWaitReason() != null
                && task.getWaitReason().startsWith("CAPTCHA_");
        task.setState(PublishTaskState.READY);
        task.setWaitReason(null);
        task.setResultMessage(confirmedNotSubmitted
                ? "用户确认线上未投稿，已安排重试"
                : captchaAutoRetryWaiting ? "用户手动请求立即重新检查投稿验证码"
                : "用户请求重试");
        task.setNextAttemptAt(null);
        task.setClaimToken(null);
        task.setLeaseUntil(null);
        task.setUpdatedAt(LocalDateTime.now());
        task.setRetryCount(task.getRetryCount() + 1);
        if (resetCaptchaRetryCycle) {
            task.setCaptchaRetryCount(0);
            task.setCaptchaRetryPending(false);
        }
        if (captchaGateOwner) {
            users.findById(task.getAccountId()).ifPresent(user -> {
                if (Objects.equals(user.getPublishCaptchaProbeTaskId(), taskId)) {
                    user.setPublishCaptchaRetryAt(LocalDateTime.now());
                    users.save(user);
                }
            });
        }
        return tasks.save(task);
    }

    @Transactional
    public PublishTask scheduleCaptchaRetry(Long taskId, LocalDateTime challengeExpiredAt,
                                            int maxAttempts, long delayMinutes) {
        PublishTask task = tasks.findById(taskId).orElse(null);
        if (task == null || task.getState() != PublishTaskState.WAITING_CAPTCHA
                || !"PUBLISH_CAPTCHA".equals(task.getWaitReason())) return null;
        BiliBiliUser user = users.findById(task.getAccountId()).orElse(null);
        if (user == null) {
            task.setState(PublishTaskState.NEEDS_ACTION);
            task.setWaitReason("ACCOUNT_NOT_LOGGED_IN");
            task.setResultMessage("验证码已过期，但绑定账号不存在；请检查账号后处理");
            task.setNextAttemptAt(null);
            task.setCaptchaRetryPending(false);
        } else {
            user.setPublishCaptchaProbeTaskId(taskId);
            if (task.getCaptchaRetryCount() >= maxAttempts) {
                user.setPublishCaptchaRetryAt(null);
                task.setState(PublishTaskState.NEEDS_ACTION);
                task.setWaitReason("CAPTCHA_RETRY_EXHAUSTED");
                task.setResultMessage("验证码无人处理，系统已完成 " + maxAttempts
                        + " 次低频自动尝试仍需验证码；账号队列已暂停，请手动验证或重试");
                task.setNextAttemptAt(null);
                task.setCaptchaRetryPending(false);
            } else {
                LocalDateTime base = challengeExpiredAt == null ? LocalDateTime.now() : challengeExpiredAt;
                LocalDateTime retryAt = base.plusMinutes(delayMinutes);
                user.setPublishCaptchaRetryAt(retryAt);
                task.setState(PublishTaskState.RETRY_WAIT);
                task.setWaitReason("CAPTCHA_AUTO_RETRY");
                task.setResultMessage("验证码已过期且无人处理；系统将在 " + retryAt
                        + " 进行第 " + (task.getCaptchaRetryCount() + 1) + "/" + maxAttempts + " 次自动尝试");
                task.setNextAttemptAt(retryAt);
                task.setCaptchaRetryPending(true);
            }
            users.save(user);
        }
        task.setUpdatedAt(LocalDateTime.now());
        return tasks.save(task);
    }

    @Transactional
    public boolean markCaptchaAttemptStarted(Long taskId) {
        PublishTask task = tasks.findById(taskId).orElse(null);
        if (task == null || !task.isCaptchaRetryPending()) return false;
        BiliBiliUser user = users.findById(task.getAccountId()).orElse(null);
        if (user == null || !Objects.equals(user.getPublishCaptchaProbeTaskId(), taskId)) return false;
        task.setCaptchaRetryPending(false);
        task.setCaptchaRetryCount(task.getCaptchaRetryCount() + 1);
        task.setUpdatedAt(LocalDateTime.now());
        user.setPublishCaptchaRetryAt(null);
        users.save(user);
        tasks.save(task);
        return true;
    }

    @Transactional
    public void undoCaptchaAttemptForUploadChallenge(Long taskId) {
        PublishTask task = tasks.findById(taskId).orElse(null);
        if (task == null || task.getCaptchaRetryCount() <= 0) return;
        BiliBiliUser user = users.findById(task.getAccountId()).orElse(null);
        if (user == null || !Objects.equals(user.getPublishCaptchaProbeTaskId(), taskId)) return;
        task.setCaptchaRetryCount(task.getCaptchaRetryCount() - 1);
        task.setUpdatedAt(LocalDateTime.now());
        tasks.save(task);
    }

    @Transactional
    public boolean releaseCaptchaProbe(Long accountId, Long taskId, boolean resetTaskCount) {
        if (accountId == null || taskId == null) return false;
        BiliBiliUser user = users.findById(accountId).orElse(null);
        if (user == null || !Objects.equals(user.getPublishCaptchaProbeTaskId(), taskId)) return false;
        user.setPublishCaptchaProbeTaskId(null);
        user.setPublishCaptchaRetryAt(null);
        users.save(user);
        if (resetTaskCount) {
            PublishTask task = tasks.findById(taskId).orElse(null);
            if (task != null) {
                task.setCaptchaRetryCount(0);
                task.setCaptchaRetryPending(false);
                task.setUpdatedAt(LocalDateTime.now());
                tasks.save(task);
            }
        }
        return true;
    }

    @Transactional
    public void prepareCaptchaProbe(Long accountId, Long taskId, LocalDateTime retryAt) {
        if (accountId == null || taskId == null) return;
        users.findById(accountId).ifPresent(user -> {
            user.setPublishCaptchaProbeTaskId(taskId);
            user.setPublishCaptchaRetryAt(retryAt);
            users.save(user);
        });
    }

    @Transactional
    public boolean updateCaptchaProbeRetryAt(Long accountId, Long taskId, LocalDateTime retryAt) {
        if (accountId == null || taskId == null) return false;
        BiliBiliUser user = users.findById(accountId).orElse(null);
        if (user == null || !Objects.equals(user.getPublishCaptchaProbeTaskId(), taskId)) return false;
        user.setPublishCaptchaRetryAt(retryAt);
        users.save(user);
        return true;
    }

    @Transactional
    public PublishTask updateSnapshot(Long taskId, String requestSnapshot, String resultMessage) {
        PublishTask task = tasks.findById(taskId).orElse(null);
        if (task == null) return null;
        task.setRequestSnapshot(requestSnapshot);
        if (resultMessage != null) task.setResultMessage(resultMessage);
        task.setUpdatedAt(LocalDateTime.now());
        return tasks.save(task);
    }

    @Transactional
    public PublishTask cancel(Long taskId) {
        PublishTask task = tasks.findById(taskId).orElse(null);
        if (task == null || !task.getState().isActive()
                || task.getState() == PublishTaskState.SUBMITTING
                || task.getState() == PublishTaskState.VERIFYING) return null;
        task.setState(PublishTaskState.CANCELLED);
        task.setWaitReason(null);
        task.setResultMessage("任务已取消");
        task.setUpdatedAt(LocalDateTime.now());
        task.setClaimToken(null);
        task.setLeaseUntil(null);
        return tasks.save(task);
    }

    @Transactional(readOnly = true)
    public List<PublishTask> runnable() {
        LocalDateTime now = LocalDateTime.now();
        List<PublishTask> result = new ArrayList<>(tasks.findByStateInOrderByCreatedAtAsc(
                EnumSet.of(PublishTaskState.READY, PublishTaskState.WAITING_ACCOUNT)));
        result.addAll(tasks.findByStateInAndNextAttemptAtBeforeOrderByCreatedAtAsc(
                EnumSet.of(PublishTaskState.RETRY_WAIT), now.plusNanos(1)));
        return result.stream().filter(task -> task.getNextAttemptAt() == null
                || !task.getNextAttemptAt().isAfter(now)).toList();
    }

    @Transactional
    public int recoverAfterRestart() {
        int recovered = 0;
        for (PublishTask task : tasks.findByStateInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                EnumSet.of(PublishTaskState.PREPARING), LocalDateTime.now())) {
            transition(task.getId(), Set.of(PublishTaskState.PREPARING), PublishTaskState.READY,
                    null, "程序重启后恢复准备任务", null, null);
            recovered++;
        }
        for (PublishTask task : tasks.findByStateInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                EnumSet.of(PublishTaskState.SUBMITTING), LocalDateTime.now())) {
            transition(task.getId(), Set.of(PublishTaskState.SUBMITTING), PublishTaskState.VERIFYING,
                    "SUBMISSION_RESULT_UNKNOWN", "程序重启时投稿结果未知，正在核对线上稿件", null, null);
            recovered++;
        }
        return recovered;
    }

    @Transactional
    public int purgeOldTerminalTasks() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(30);
        List<PublishTask> old = tasks.findByStateInAndUpdatedAtBefore(TERMINAL_STATES, cutoff);
        tasks.deleteAll(old);
        return old.size();
    }

    public PublishTaskStatusDto toDto(PublishTask task, Integer position) {
        if (task == null) return null;
        PublishTaskStatusDto dto = new PublishTaskStatusDto(task.getId(), task.getHistoryId(), task.getAccountId(),
                task.getOperation(), task.getState(), task.getWaitReason(), task.getResultMessage(),
                task.getRetryCount(), position, task.getNextAttemptAt(), task.getNextAttemptAt(),
                task.getCreatedAt(), task.getUpdatedAt());
        dto.setCaptchaRetryCount(task.getCaptchaRetryCount());
        dto.setCaptchaRetryLimit(3);
        return dto;
    }

    public static Set<PublishTaskState> activeStates() { return new HashSet<>(ACTIVE_STATES); }

    private static boolean sameJson(String left, String right) {
        if (Objects.equals(left, right)) return true;
        if (left == null || right == null) return false;
        try {
            return Objects.equals(JSON.parse(left), JSON.parse(right));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @Data
    @AllArgsConstructor
    public static class Admission {
        private boolean accepted;
        private boolean alreadyQueued;
        private PublishTask task;
        private String message;

        static Admission accepted(PublishTask task) {
            return new Admission(true, false, task, "任务已加入投稿队列");
        }
        static Admission duplicate(PublishTask task) {
            return new Admission(true, true, task, "稿件已在投稿队列中");
        }
        static Admission rejected(String message) {
            return new Admission(false, false, null, message);
        }
    }
}
