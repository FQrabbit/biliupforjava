package top.sshh.bililiverecoder.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.entity.data.BiliVideoInfoResponse;
import top.sshh.bililiverecoder.lifecycle.ShutdownState;
import top.sshh.bililiverecoder.repo.BiliUserRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.entity.data.BiliVideoPartInfoResponse;
import top.sshh.bililiverecoder.service.impl.HighEnergyCutPublishService;
import top.sshh.bililiverecoder.service.impl.RecordBiliPublishService;
import top.sshh.bililiverecoder.util.BiliApi;

import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.dao.TransientDataAccessException;

@Slf4j
@Service
public class PublishAccountScheduler {
    private static final int CAPTCHA_AUTO_RETRY_LIMIT = 3;
    private static final long[] CAPTCHA_AUTO_RETRY_MINUTES = {30L, 60L, 120L};
    private static final Set<PublishTaskState> DISPATCHABLE = EnumSet.of(
            PublishTaskState.READY, PublishTaskState.RETRY_WAIT, PublishTaskState.WAITING_ACCOUNT);

    private final TaskExecutor executor;
    private final TaskExecutor highEnergyPrepareExecutor;
    private final ObjectProvider<RecordBiliPublishService> publisher;
    private final ObjectProvider<HighEnergyCutPublishService> highEnergy;
    private final RecordHistoryRepository histories;
    private final RecordHistoryPartRepository parts;
    private final RecordRoomRepository rooms;
    private final BiliUserRepository users;
    private final PublishTaskService tasks;
    private final PublishAccountCooldownService cooldowns;
    private final CaptchaService captchas;
    private final ShutdownState shutdown;
    private final Map<Long, AccountRuntime> accounts = new ConcurrentHashMap<>();
    private final Set<Long> verificationInFlight = ConcurrentHashMap.newKeySet();
    private final AtomicInteger accountCursor = new AtomicInteger();
    private final Object[] admissionLocks = new Object[128];
    private final ThreadLocal<Long> currentTaskId = new ThreadLocal<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "publish-task-scheduler");
        t.setDaemon(true);
        return t;
    });

    public PublishAccountScheduler(@Qualifier("publishAccountExecutor") TaskExecutor executor,
                                   @Qualifier("highEnergyPrepareExecutor") TaskExecutor highEnergyPrepareExecutor,
                                   ObjectProvider<RecordBiliPublishService> publisher,
                                   ObjectProvider<HighEnergyCutPublishService> highEnergy,
                                   RecordHistoryRepository histories, RecordHistoryPartRepository parts,
                                   RecordRoomRepository rooms,
                                   BiliUserRepository users, PublishTaskService tasks,
                                   PublishAccountCooldownService cooldowns, CaptchaService captchas,
                                   ShutdownState shutdown) {
        this.executor = executor;
        this.highEnergyPrepareExecutor = highEnergyPrepareExecutor;
        this.publisher = publisher;
        this.highEnergy = highEnergy;
        this.histories = histories;
        this.parts = parts;
        this.rooms = rooms;
        this.users = users;
        this.tasks = tasks;
        this.cooldowns = cooldowns;
        this.captchas = captchas;
        this.shutdown = shutdown;
        for (int i = 0; i < admissionLocks.length; i++) admissionLocks[i] = new Object();
    }

    @PostConstruct
    public void start() {
        tasks.recoverAfterRestart();
        timer.scheduleWithFixedDelay(this::poll, 5, 30, TimeUnit.SECONDS);
        timer.scheduleWithFixedDelay(tasks::purgeOldTerminalTasks, 1, 6, TimeUnit.HOURS);
        timer.scheduleWithFixedDelay(cooldowns::resetExpiredRisk, 5, 60, TimeUnit.MINUTES);
    }

    public boolean enqueue(Long accountId, Long historyId) {
        RecordHistory history = historyId == null ? null : histories.findById(historyId).orElse(null);
        PublishTaskOperation operation = history != null
                && (history.isPublish() || RecordBiliPublishService.hasOnlineIdentity(history))
                ? PublishTaskOperation.UPDATE : PublishTaskOperation.NEW_PUBLISH;
        return accept(accountId, historyId, operation,
                PublishTaskSource.AUTOMATIC, null).isAccepted();
    }

    public boolean enqueueEdit(Long accountId, Long historyId) {
        return accept(accountId, historyId, PublishTaskOperation.UPDATE,
                PublishTaskSource.MANUAL, null).isAccepted();
    }

    public boolean enqueueRepublish(Long accountId, Long historyId) {
        return accept(accountId, historyId, PublishTaskOperation.REPAIR,
                PublishTaskSource.MANUAL, null).isAccepted();
    }

    public PublishTaskService.Admission accept(Long accountId, Long historyId,
            PublishTaskOperation operation, PublishTaskSource source, Object payload) {
        if (historyId == null) return tasks.accept(historyId, accountId, operation, source, payload);
        Object lock = admissionLocks[Math.floorMod(historyId.hashCode(), admissionLocks.length)];
        PublishTaskService.Admission result;
        synchronized (lock) {
            result = tasks.accept(historyId, accountId, operation, source, payload);
        }
        if (result.isAccepted()) wakeAccount(accountId);
        return result;
    }

    public PublishTaskStatusDto status(Long accountId, Long historyId) {
        if (accountId == null || historyId == null) return null;
        List<PublishTask> active = tasks.getActiveForHistory(historyId).stream()
                .sorted(Comparator.comparing(PublishTask::getCreatedAt)).toList();
        PublishTask task = active.isEmpty() ? tasks.getLatestForHistory(historyId)
                : active.stream().filter(candidate -> candidate.getOperation() != PublishTaskOperation.HIGH_ENERGY)
                .findFirst().orElse(active.get(0));
        if (task == null) return null;
        Integer position = queuePosition(task);
        PublishTaskStatusDto dto = tasks.toDto(task, position);
        dto.setEstimatedEarliestAt(estimateEarliest(task, position));
        return dto;
    }

    public List<PublishTaskStatusDto> statuses(Long accountId, Long historyId) {
        if (historyId == null) return List.of();
        List<PublishTask> historyTasks = tasks.getActiveForHistory(historyId).stream()
                .sorted(Comparator.comparing(PublishTask::getCreatedAt)).toList();
        if (historyTasks.isEmpty()) {
            PublishTask latest = tasks.getLatestForHistory(historyId);
            return latest == null ? List.of() : List.of(tasks.toDto(latest, null));
        }
        Map<Long, List<PublishTask>> queues = new java.util.HashMap<>();
        Map<Long, Long> cooldownWaits = new java.util.HashMap<>();
        return historyTasks.stream().map(task -> {
            List<PublishTask> queue = task.getAccountId() == null ? List.of()
                    : queues.computeIfAbsent(task.getAccountId(), id -> dueQueue(id, LocalDateTime.now()));
            Integer position = queuePosition(task, queue);
            PublishTaskStatusDto dto = tasks.toDto(task, position);
            long waitMs = task.getAccountId() == null ? 0L : cooldownWaits.computeIfAbsent(
                    task.getAccountId(), cooldowns::waitMs);
            dto.setEstimatedEarliestAt(estimateEarliest(task, position, waitMs));
            return dto;
        }).toList();
    }

    public PublishTask task(Long id) { return tasks.get(id); }

    public PublishTaskStatusDto statusByTaskId(Long id) {
        PublishTask task = tasks.get(id);
        if (task == null) return null;
        Integer position = queuePosition(task);
        PublishTaskStatusDto dto = tasks.toDto(task, position);
        dto.setEstimatedEarliestAt(estimateEarliest(task, position));
        return dto;
    }

    private LocalDateTime estimateEarliest(PublishTask task, Integer position) {
        long waitMs = task == null || task.getAccountId() == null ? 0L : cooldowns.waitMs(task.getAccountId());
        return estimateEarliest(task, position, waitMs);
    }

    private LocalDateTime estimateEarliest(PublishTask task, Integer position, long cooldownWaitMs) {
        if (task.getState() == PublishTaskState.WAITING_CAPTCHA
                || task.getState() == PublishTaskState.NEEDS_ACTION
                || task.getState() == PublishTaskState.FAILED
                || task.getState() == PublishTaskState.CANCELLED
                || task.getState() == PublishTaskState.VERIFYING
                || task.getState() == PublishTaskState.SUBMITTING
                || task.getState() == PublishTaskState.PREPARING) return task.getNextAttemptAt();
        if ("ACCOUNT_CAPTCHA".equals(task.getWaitReason())
                || "CAPTCHA_ACCOUNT_BLOCKED".equals(task.getWaitReason())) return null;
        LocalDateTime estimate = task.getNextAttemptAt() == null
                ? LocalDateTime.now().plusNanos(TimeUnit.MILLISECONDS.toNanos(cooldownWaitMs))
                : task.getNextAttemptAt();
        if (position != null && position > 1) estimate = estimate.plusSeconds(30L * (position - 1));
        return estimate;
    }

    public List<PublishTaskStatusDto> statusByTaskIds(List<Long> ids) {
        List<PublishTask> selected = tasks.getByIds(ids);
        LocalDateTime now = LocalDateTime.now();
        Map<Long, List<PublishTask>> queues = new java.util.HashMap<>();
        Map<Long, Long> cooldownWaits = new java.util.HashMap<>();
        return selected.stream().map(task -> {
            List<PublishTask> queue = task.getAccountId() == null ? List.of()
                    : queues.computeIfAbsent(task.getAccountId(), id -> dueQueue(id, now));
            Integer position = queuePosition(task, queue);
            PublishTaskStatusDto dto = tasks.toDto(task, position);
            long waitMs = task.getAccountId() == null ? 0L : cooldownWaits.computeIfAbsent(
                    task.getAccountId(), cooldowns::waitMs);
            dto.setEstimatedEarliestAt(estimateEarliest(task, position, waitMs));
            return dto;
        }).toList();
    }

    private Integer queuePosition(PublishTask task) {
        if (task == null || task.getAccountId() == null) return null;
        return queuePosition(task, dueQueue(task.getAccountId(), LocalDateTime.now()));
    }

    private List<PublishTask> dueQueue(Long accountId, LocalDateTime now) {
        return tasks.getByAccountAndStates(accountId, DISPATCHABLE).stream()
                .filter(candidate -> candidate.getNextAttemptAt() == null
                        || !candidate.getNextAttemptAt().isAfter(now))
                .sorted(Comparator.comparing(PublishTask::getCreatedAt))
                .toList();
    }

    private Integer queuePosition(PublishTask task, List<PublishTask> queue) {
        if (task == null || !DISPATCHABLE.contains(task.getState())
                || (task.getNextAttemptAt() != null && task.getNextAttemptAt().isAfter(LocalDateTime.now()))) {
            return null;
        }
        for (int i = 0; i < queue.size(); i++) {
            if (task.getId().equals(queue.get(i).getId())) return i + 1;
        }
        return null;
    }

    public Map<String, Object> confirmBvid(Long taskId, String bvid) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        PublishTask task = tasks.get(taskId);
        if (task == null || (task.getState() != PublishTaskState.NEEDS_ACTION
                && task.getState() != PublishTaskState.VERIFYING)) {
            result.put("success", false);
            result.put("message", "该任务当前不需要人工核对");
            return result;
        }
        if (bvid == null || !bvid.matches("BV[0-9A-Za-z]{10}")) {
            result.put("success", false);
            result.put("message", "BVID 格式无效");
            return result;
        }
        RecordHistory history = histories.findById(task.getHistoryId()).orElse(null);
        BiliBiliUser user = users.findById(task.getAccountId()).orElse(null);
        if (history == null || user == null || !user.isLogin()) {
            result.put("success", false);
            result.put("message", "稿件不存在或原投稿账号未登录");
            return result;
        }
        if (task.getOperation() != PublishTaskOperation.HIGH_ENERGY
                && history.getBvId() != null && !history.getBvId().isBlank()
                && !history.getBvId().equalsIgnoreCase(bvid)) {
            result.put("success", false);
            result.put("message", "输入的 BVID 与本地已知稿件身份不一致");
            return result;
        }
        try {
            BiliVideoInfoResponse info = BiliApi.getVideoInfo(user, bvid);
            if (info == null || info.getCode() != 0 || info.getData() == null
                    || !bvid.equalsIgnoreCase(info.getData().getBvid())
                    || info.getData().getOwner() == null || user.getUid() == null
                    || !user.getUid().equals(info.getData().getOwner().getMid())) {
                result.put("success", false);
                result.put("message", "无法确认该 BVID 属于任务账号，请检查账号和稿件");
                return result;
            }
            if (task.getOperation() != PublishTaskOperation.HIGH_ENERGY
                    && history.getAvId() != null && !history.getAvId().isBlank()
                    && !history.getAvId().equals(info.getData().getAid())) {
                result.put("success", false);
                result.put("message", "线上 AID 与本地稿件记录不一致");
                return result;
            }
            BiliVideoPartInfoResponse partInfo = BiliApi.getVideoPartInfo(user, bvid);
            if (partInfo == null || partInfo.getData() == null || partInfo.getData().getVideos() == null) {
                result.put("success", false);
                result.put("message", "未能读取线上分P信息，暂不能确认任务结果");
                return result;
            }
            List<BiliVideoPartInfoResponse.Video> online = partInfo.getData().getVideos();
            if (history.isPublish() && history.getPublishUserId() != null
                    && !history.getPublishUserId().equals(task.getAccountId())) {
                result.put("success", false);
                result.put("message", "该稿件已绑定其他原投稿账号，不能用当前账号确认或修改");
                return result;
            }
            if (!matchesTaskContent(task, history, online)) {
                result.put("success", false);
                result.put("message", "账号归属已核实，但线上分P与任务内容不匹配；任务保持待人工处理");
                return result;
            }
            if (task.getOperation() == PublishTaskOperation.NEW_PUBLISH) {
                history.setBvId(info.getData().getBvid());
                history.setAvId(info.getData().getAid());
                history.setPublish(true);
                history.setPublishUserId(task.getAccountId());
                histories.save(history);
            } else if (task.getOperation() == PublishTaskOperation.EDIT_PARTS) {
                Map<String, Object> restored = publisher.getObject().restoreEditPartsOnlineState(history.getId());
                if (!Boolean.TRUE.equals(restored.get("success"))) {
                    result.put("success", false);
                    result.put("message", "线上内容已核对，但本地分P同步失败：" + restored.get("message"));
                    return result;
                }
            }
            if (history.isPublish() && history.getPublishUserId() == null
                    && task.getOperation() != PublishTaskOperation.HIGH_ENERGY) {
                history.setPublishUserId(task.getAccountId());
                history.setBvId(info.getData().getBvid());
                history.setAvId(info.getData().getAid());
                histories.save(history);
            }
            Map<String, Object> snapshot = task.getRequestSnapshot() == null
                    ? new java.util.HashMap<>() : JSON.parseObject(task.getRequestSnapshot(), new TypeReference<Map<String, Object>>() {});
            snapshot.put("confirmedBvid", info.getData().getBvid());
            snapshot.put("confirmedAid", info.getData().getAid());
            tasks.updateSnapshot(taskId, JSON.toJSONString(snapshot), "已人工核对账号归属及线上分P内容");
            tasks.setState(taskId, PublishTaskState.SUCCEEDED, null,
                    "已人工核对账号归属及线上分P内容，确认远端任务成功", null);
            if (task.getOperation() == PublishTaskOperation.HIGH_ENERGY) {
                highEnergy.getObject().completeQueuedTask(tasks.get(taskId));
            }
            if (tasks.releaseCaptchaProbe(task.getAccountId(), taskId, true)) {
                releaseCaptchaBlockedTasks(task.getAccountId());
                wakeAccount(task.getAccountId());
            }
            result.put("success", true);
            result.put("message", "线上身份与任务内容已核对，任务已标记成功");
            result.put("publishDispatch", statusByTaskId(taskId));
            return result;
        } catch (Exception e) {
            log.warn("Manual publish verification failed taskId={} bvid={}", taskId, bvid, e);
            result.put("success", false);
            result.put("message", "线上核对失败：" + e.getMessage());
            return result;
        }
    }

    private boolean matchesTaskContent(PublishTask task, RecordHistory history,
                                       List<BiliVideoPartInfoResponse.Video> online) {
        if (task.getOperation() == PublishTaskOperation.HIGH_ENERGY) {
            try {
                Map<String, Object> snapshot = JSON.parseObject(task.getRequestSnapshot(), new TypeReference<Map<String, Object>>() {});
                String expected = String.valueOf(snapshot.get("uploadedFileName"));
                return online.size() == 1 && expected.equals(online.get(0).getFilename());
            } catch (RuntimeException e) { return false; }
        }
        if (task.getOperation() == PublishTaskOperation.EDIT_PARTS) {
            try {
                Map<String, Object> snapshot = JSON.parseObject(task.getRequestSnapshot(), new TypeReference<Map<String, Object>>() {});
                Object rawVideos = snapshot == null ? null : snapshot.get("submittedVideos");
                if (!(rawVideos instanceof List<?> expectedVideos) || expectedVideos.isEmpty()) return false;
                List<BiliVideoPartInfoResponse.Video> orderedOnline = online.stream()
                        .sorted(Comparator.comparingInt(BiliVideoPartInfoResponse.Video::getPage)).toList();
                if (expectedVideos.size() != orderedOnline.size()) return false;
                for (int index = 0; index < orderedOnline.size(); index++) {
                    Object rawExpected = expectedVideos.get(index);
                    BiliVideoPartInfoResponse.Video actual = orderedOnline.get(index);
                    if (!(rawExpected instanceof Map<?, ?> expected) || actual == null) return false;
                    String filename = expected.get("filename") == null
                            ? "" : String.valueOf(expected.get("filename"));
                    long cid = parseLong(expected.get("cid"));
                    String title = expected.get("title") == null ? "" : String.valueOf(expected.get("title"));
                    boolean hasStableIdentity = false;
                    if (!filename.isBlank()) {
                        if (!filename.equals(actual.getFilename())) return false;
                        hasStableIdentity = true;
                    }
                    if (cid > 0) {
                        if (cid != actual.getCid()) return false;
                        hasStableIdentity = true;
                    }
                    if (!hasStableIdentity) return false;
                    String actualTitle = java.util.Objects.toString(actual.getTitle(),
                            java.util.Objects.toString(actual.getPart(), ""));
                    if (!title.isBlank() && !title.equals(actualTitle)) return false;
                }
                return true;
            } catch (RuntimeException e) { return false; }
        }
        if (task.getOperation() != PublishTaskOperation.NEW_PUBLISH
                && task.getOperation() != PublishTaskOperation.UPDATE
                && task.getOperation() != PublishTaskOperation.REPAIR) return false;
        List<String> expectedFiles = parts.findByHistoryIdOrderByStartTimeAsc(history.getId()).stream()
                .filter(part -> part.isUpload() && part.getFileName() != null && !part.getFileName().isBlank())
                .filter(part -> !"SKIPPED_THRESHOLD".equals(part.getDeleteFailType())
                        && !"TIMESTAMP_JUMP".equals(part.getDeleteFailType()))
                .map(RecordHistoryPart::getFileName).sorted().toList();
        List<String> onlineFiles = online.stream().map(BiliVideoPartInfoResponse.Video::getFilename)
                .filter(java.util.Objects::nonNull).sorted().toList();
        return !expectedFiles.isEmpty() && expectedFiles.equals(onlineFiles);
    }

    private static long parseLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return value == null ? 0L : Long.parseLong(String.valueOf(value)); }
        catch (RuntimeException ignored) { return 0L; }
    }

    public Long currentTaskId() { return currentTaskId.get(); }

    public PublishTask retry(Long id, boolean confirmedNotSubmitted) {
        PublishTask task = tasks.retry(id, confirmedNotSubmitted);
        if (task != null) wakeAccount(task.getAccountId());
        return task;
    }

    public PublishTask cancel(Long id) {
        PublishTask cancelled = tasks.cancel(id);
        if (cancelled != null) {
            captchas.cancelForTask(id);
            if (cancelled.getOperation() == PublishTaskOperation.EDIT_PARTS) {
                publisher.getObject().cleanupTerminalEditPartsTask(cancelled);
            } else if (cancelled.getOperation() == PublishTaskOperation.HIGH_ENERGY) {
                highEnergy.getObject().completeQueuedTask(cancelled);
            }
            if (tasks.releaseCaptchaProbe(cancelled.getAccountId(), id, false)) {
                releaseCaptchaBlockedTasks(cancelled.getAccountId());
            }
            wakeAccount(cancelled.getAccountId());
        }
        return cancelled;
    }

    public List<PublishTask> byIds(List<Long> ids) { return tasks.getByIds(ids); }

    public void cancelForHistory(Long historyId, String reason) {
        for (PublishTask task : tasks.getActiveForHistory(historyId)) {
            if (task.getState() != PublishTaskState.SUBMITTING && task.getState() != PublishTaskState.VERIFYING) {
                tasks.setState(task.getId(), PublishTaskState.CANCELLED, "HISTORY_REMOVED", reason, null);
                captchas.cancelForTask(task.getId());
                if (task.getOperation() == PublishTaskOperation.EDIT_PARTS) {
                    publisher.getObject().cleanupTerminalEditPartsTask(task);
                } else if (task.getOperation() == PublishTaskOperation.HIGH_ENERGY) {
                    highEnergy.getObject().completeQueuedTask(task);
                }
                if (tasks.releaseCaptchaProbe(task.getAccountId(), task.getId(), false)) {
                    releaseCaptchaBlockedTasks(task.getAccountId());
                }
            }
        }
    }

    public boolean hasSubmissionInFlight(Long historyId) {
        return tasks.getActiveForHistory(historyId).stream()
                .anyMatch(task -> task.getState() == PublishTaskState.SUBMITTING
                        || task.getState() == PublishTaskState.VERIFYING);
    }

    public void enqueueAfterCaptcha(Long accountId, Long historyId) {
        if (historyId == null || accountId == null) return;
        tasks.getActiveForHistory(historyId).stream()
                .filter(t -> accountId.equals(t.getAccountId()) && t.getState() == PublishTaskState.WAITING_CAPTCHA)
                .forEach(t -> tasks.setState(t.getId(), PublishTaskState.READY, null,
                        "验证码已提交，等待账号队列", null));
        wakeAccount(accountId);
    }

    public void enqueueAfterCaptcha(Long taskId, Long accountId, Long historyId) {
        if (accountId == null) return;
        if (taskId != null) {
            PublishTask task = tasks.get(taskId);
            if (task != null && accountId.equals(task.getAccountId())
                    && task.getState() == PublishTaskState.WAITING_CAPTCHA) {
                tasks.setState(taskId, PublishTaskState.READY, null, "验证码已提交，等待账号队列",
                        null);
            }
            if (tasks.releaseCaptchaProbe(accountId, taskId, true)) {
                releaseCaptchaBlockedTasks(accountId);
            }
        }
        if (!captchas.hasPendingForAccount(accountId)) releaseCaptchaBlockedTasks(accountId);
        wakeAccount(accountId);
    }

    public void captchaCancelled(CaptchaService.ChallengeStatus challenge) {
        if (challenge == null) return;
        if (challenge.taskId() != null) {
            PublishTask task = tasks.get(challenge.taskId());
            boolean uploadChallenge = challenge.stage() != null && challenge.stage().contains("UPLOAD");
            boolean awaitingUploadChallenge = task != null && uploadChallenge
                    && (task.getState() == PublishTaskState.PREPARING
                    || task.getState() == PublishTaskState.WAITING_UPLOAD);
            if (task != null && (task.getState() == PublishTaskState.WAITING_CAPTCHA
                    || awaitingUploadChallenge)) {
                tasks.setState(task.getId(), PublishTaskState.NEEDS_ACTION, "CAPTCHA_CANCELLED",
                        "验证码已取消；需要重新触发验证码或取消投稿任务", null);
            }
            if (task != null && challenge.accountId() != null
                    && tasks.releaseCaptchaProbe(challenge.accountId(), task.getId(), false)) {
                releaseCaptchaBlockedTasks(challenge.accountId());
            }
        } else if (challenge.historyId() != null) {
            wakeHistory(challenge.historyId());
        }
        if (challenge.accountId() != null) {
            tasks.getByAccountAndStates(challenge.accountId(), EnumSet.of(PublishTaskState.WAITING_ACCOUNT)).stream()
                    .filter(task -> "ACCOUNT_CAPTCHA".equals(task.getWaitReason()))
                    .forEach(task -> tasks.setState(task.getId(), PublishTaskState.READY, null,
                            "验证码已取消，账号任务重新检查", null));
            timer.schedule(() -> wakeAccount(challenge.accountId()), 100, TimeUnit.MILLISECONDS);
        }
    }

    public void wakeAccount(Long id) {
        if (id != null && !shutdown.isShuttingDown()) dispatch(id);
    }

    public void wakeHistory(Long historyId) {
        if (historyId == null || shutdown.isShuttingDown()) return;
        List<PublishTask> active = tasks.getActiveForHistory(historyId);
        active.stream().filter(task -> task.getState() == PublishTaskState.WAITING_UPLOAD)
                .forEach(task -> tasks.setState(task.getId(), PublishTaskState.READY, null,
                        "分P上传状态已变化，重新检查任务", null));
        active.stream().map(PublishTask::getAccountId).filter(java.util.Objects::nonNull)
                .distinct().forEach(this::dispatch);
    }

    public void deferUploadHistory(Long historyId, String message, LocalDateTime retryAt) {
        if (historyId == null) return;
        for (PublishTask task : tasks.getActiveForHistory(historyId)) {
            if (task.getState() == PublishTaskState.PREPARING
                    || task.getState() == PublishTaskState.WAITING_UPLOAD) {
                tasks.setState(task.getId(), PublishTaskState.WAITING_UPLOAD,
                        "PART_UPLOAD_FAILED", message, retryAt);
            }
        }
    }

    private void poll() {
        if (shutdown.isShuttingDown()) return;
        try {
            refreshWaitingTasks();
            List<Long> accountIds = tasks.runnable().stream().map(PublishTask::getAccountId)
                    .filter(java.util.Objects::nonNull).distinct().sorted().toList();
            if (!accountIds.isEmpty()) {
                int start = Math.floorMod(accountCursor.getAndIncrement(), accountIds.size());
                for (int offset = 0; offset < accountIds.size(); offset++) {
                    dispatch(accountIds.get((start + offset) % accountIds.size()));
                }
            }
        } catch (Exception e) {
            log.error("Publish task poll failed", e);
        }
    }

    private void refreshWaitingTasks() {
        for (PublishTask task : tasks.getByStates(EnumSet.of(PublishTaskState.WAITING_CAPTCHA))) {
            if ("PUBLISH_CAPTCHA".equals(task.getWaitReason())) refreshPublishCaptcha(task);
            else if ("UPLOAD_CAPTCHA".equals(task.getWaitReason())) refreshUploadCaptcha(task);
            else if ("ACCOUNT_CAPTCHA".equals(task.getWaitReason())
                    && !captchas.hasPendingForAccount(task.getAccountId())
                    && cooldowns.captchaProbeGate(task.getAccountId()) == null) {
                tasks.setState(task.getId(), PublishTaskState.READY, null,
                        "账号验证码已完成，等待账号队列", task.getNextAttemptAt());
            }
        }
        for (PublishTask task : tasks.getByStates(EnumSet.of(PublishTaskState.WAITING_ACCOUNT))) {
            if ("ACCOUNT_CAPTCHA".equals(task.getWaitReason())
                    && !captchas.hasPendingForAccount(task.getAccountId())
                    && cooldowns.captchaProbeGate(task.getAccountId()) == null) {
                tasks.setState(task.getId(), PublishTaskState.READY, null,
                        "账号验证码已完成，等待账号队列", task.getNextAttemptAt());
            }
        }
        for (PublishTask task : tasks.due(EnumSet.of(PublishTaskState.WAITING_UPLOAD), LocalDateTime.now())) {
            tasks.setState(task.getId(), PublishTaskState.READY, null,
                    "补偿检查：重新确认分P上传状态", null);
        }
        for (PublishTask task : tasks.getByStates(EnumSet.of(PublishTaskState.VERIFYING))) {
            if (!verificationInFlight.add(task.getId())) continue;
            try {
                executor.execute(() -> {
                    try {
                        verifyUnknownResult(task);
                    } finally {
                        verificationInFlight.remove(task.getId());
                    }
                });
            } catch (RejectedExecutionException e) {
                verificationInFlight.remove(task.getId());
            }
        }
    }

    private void refreshPublishCaptcha(PublishTask task) {
        CaptchaService.ChallengeStatus challenge = captchas.challengeForTask(task.getId());
        if (challenge != null && ("PENDING".equals(challenge.state())
                || "SUBMITTED".equals(challenge.state()))) return;
        if (challenge != null && "CONSUMED".equals(challenge.state())) {
            tasks.releaseCaptchaProbe(task.getAccountId(), task.getId(), true);
            tasks.setState(task.getId(), PublishTaskState.READY, null,
                    "验证码已提交，等待账号队列", null);
            releaseCaptchaBlockedTasks(task.getAccountId());
            return;
        }
        if (challenge != null && "CANCELLED".equals(challenge.state())) {
            tasks.setState(task.getId(), PublishTaskState.NEEDS_ACTION, "CAPTCHA_CANCELLED",
                    "验证码已取消，请重新触发验证或取消投稿任务", null);
            return;
        }
        LocalDateTime expiredAt = challenge != null
                ? LocalDateTime.ofInstant(Instant.ofEpochMilli(challenge.expiresAtEpochMs()), ZoneId.systemDefault())
                : task.getNextAttemptAt();
        if (expiredAt == null) expiredAt = task.getUpdatedAt().plusMinutes(5);
        if (expiredAt.isAfter(LocalDateTime.now())) return;
        int retryCount = Math.max(0, task.getCaptchaRetryCount());
        long delayMinutes = CAPTCHA_AUTO_RETRY_MINUTES[Math.min(retryCount,
                CAPTCHA_AUTO_RETRY_MINUTES.length - 1)];
        PublishTask scheduled = tasks.scheduleCaptchaRetry(task.getId(), expiredAt,
                CAPTCHA_AUTO_RETRY_LIMIT, delayMinutes);
        if (scheduled != null) {
            PublishAccountCooldownService.CaptchaProbeGate gate = cooldowns.captchaProbeGate(task.getAccountId());
            holdCaptchaBlockedTasks(task.getAccountId(), task.getId(),
                    gate != null && gate.retryAt() != null
                            ? "CAPTCHA_AUTO_RETRY_WAIT" : "CAPTCHA_ACCOUNT_BLOCKED",
                    gate == null ? null : gate.retryAt());
        }
    }

    private void refreshUploadCaptcha(PublishTask task) {
        if (captchas.hasPendingForTask(task.getId())) return;
        CaptchaService.ChallengeStatus challenge = captchas.challengeForTask(task.getId());
        if (challenge != null && "CONSUMED".equals(challenge.state())) {
            tasks.setState(task.getId(), PublishTaskState.READY, null,
                    "分P上传验证码已提交，重新检查上传状态", null);
            PublishAccountCooldownService.CaptchaProbeGate gate = cooldowns.captchaProbeGate(task.getAccountId());
            if (gate != null && task.getId().equals(gate.taskId())) {
                tasks.prepareCaptchaProbe(task.getAccountId(), task.getId(), LocalDateTime.now());
            }
            releaseCaptchaBlockedTasks(task.getAccountId());
            return;
        }
        if (challenge == null && task.getNextAttemptAt() != null
                && task.getNextAttemptAt().isAfter(LocalDateTime.now())) return;
        tasks.setState(task.getId(), PublishTaskState.NEEDS_ACTION, "UPLOAD_CAPTCHA_EXPIRED",
                "分P上传验证码已过期或无法恢复，请重新触发上传验证", null);
    }

    private void verifyUnknownResult(PublishTask task) {
        RecordHistory history = histories.findById(task.getHistoryId()).orElse(null);
        if (task.getOperation() == PublishTaskOperation.NEW_PUBLISH && history != null && history.isPublish()) {
            tasks.setState(task.getId(), PublishTaskState.SUCCEEDED, null, "数据库已记录投稿成功", null);
            if (tasks.releaseCaptchaProbe(task.getAccountId(), task.getId(), true)) {
                releaseCaptchaBlockedTasks(task.getAccountId());
                wakeAccount(task.getAccountId());
            }
            return;
        }
        String targetBvid = history == null ? null : history.getBvId();
        String expectedAid = history == null ? null : history.getAvId();
        if (task.getOperation() == PublishTaskOperation.HIGH_ENERGY) {
            try {
                Map<String, Object> snapshot = JSON.parseObject(task.getRequestSnapshot(), new TypeReference<Map<String, Object>>() {});
                targetBvid = snapshot == null ? null : String.valueOf(snapshot.get("publishedBvid"));
                expectedAid = snapshot == null ? null : String.valueOf(snapshot.get("publishedAid"));
            } catch (RuntimeException ignored) { targetBvid = null; }
        }
        if (history == null || targetBvid == null || targetBvid.isBlank() || "null".equals(targetBvid)) {
            needsManualVerification(task, "没有可用于核对的 BV 号，请人工确认线上结果");
            return;
        }
        BiliBiliUser user = users.findById(task.getAccountId()).orElse(null);
        if (user == null || !user.isLogin()) {
            needsManualVerification(task, "投稿结果待核对，但原账号登录已失效");
            return;
        }
        try {
            BiliVideoInfoResponse response = BiliApi.getVideoInfo(user, targetBvid);
            if (response == null || response.getCode() != 0 || response.getData() == null
                    || !targetBvid.equalsIgnoreCase(response.getData().getBvid())
                    || response.getData().getOwner() == null || user.getUid() == null
                    || !user.getUid().equals(response.getData().getOwner().getMid())) {
                needsManualVerification(task, "线上查询未能确认该稿件，请人工核对后再决定是否重试");
                return;
            }
            String onlineAid = response.getData().getAid();
            if (expectedAid != null && !expectedAid.isBlank() && !"null".equals(expectedAid)
                    && !expectedAid.equals(onlineAid)) {
                needsManualVerification(task, "线上 BV 号对应的 AID 与本地记录不一致，已暂停自动处理");
                return;
            }
            BiliVideoPartInfoResponse partsResponse = BiliApi.getVideoPartInfo(user, targetBvid);
            if (partsResponse == null || partsResponse.getData() == null
                    || partsResponse.getData().getVideos() == null
                    || !matchesTaskContent(task, history, partsResponse.getData().getVideos())) {
                needsManualVerification(task, "账号和线上稿件身份已确认，但线上内容与本次任务不完全匹配，请人工检查");
                return;
            }
            if (task.getOperation() == PublishTaskOperation.NEW_PUBLISH) {
                history.setPublish(true);
                if (history.getPublishUserId() == null) history.setPublishUserId(task.getAccountId());
                if ((history.getAvId() == null || history.getAvId().isBlank()) && onlineAid != null) {
                    history.setAvId(onlineAid);
                }
                histories.save(history);
            } else if (task.getOperation() == PublishTaskOperation.EDIT_PARTS) {
                Map<String, Object> restored = publisher.getObject().restoreEditPartsOnlineState(history.getId());
                if (!Boolean.TRUE.equals(restored.get("success"))) {
                    needsManualVerification(task, "线上编辑内容已确认，但本地分P同步失败，请人工检查后恢复");
                    return;
                }
            } else if (history.isPublish() && history.getPublishUserId() == null
                    && task.getOperation() != PublishTaskOperation.HIGH_ENERGY) {
                history.setPublishUserId(task.getAccountId());
                histories.save(history);
            }
            tasks.setState(task.getId(), PublishTaskState.SUCCEEDED, null,
                    "已通过账号、BV/AID 和线上分P内容核对，确认任务成功", null);
            if (tasks.releaseCaptchaProbe(task.getAccountId(), task.getId(), true)) {
                releaseCaptchaBlockedTasks(task.getAccountId());
                wakeAccount(task.getAccountId());
            }
            if (task.getOperation() == PublishTaskOperation.HIGH_ENERGY) {
                highEnergy.getObject().completeQueuedTask(tasks.get(task.getId()));
            }
        } catch (Exception e) {
            log.warn("Unable to verify publish result taskId={} historyId={}", task.getId(), task.getHistoryId(), e);
            needsManualVerification(task, "线上核对暂时失败，请人工核对后再决定是否重试");
        }
    }

    private void needsManualVerification(PublishTask task, String message) {
        tasks.setState(task.getId(), PublishTaskState.NEEDS_ACTION, "SUBMISSION_RESULT_UNKNOWN", message, null);
    }

    private void dispatch(Long accountId) {
        AccountRuntime runtime = accounts.computeIfAbsent(accountId, id -> new AccountRuntime());
        PublishTask next;
        synchronized (runtime) {
            if (runtime.running) return;
            LocalDateTime now = LocalDateTime.now();
            List<PublishTask> queue = tasks.getByAccountAndStates(accountId, DISPATCHABLE).stream()
                    .filter(task -> task.getNextAttemptAt() == null || !task.getNextAttemptAt().isAfter(now))
                    .toList();
            if (queue.isEmpty()) return;
            if (captchas.hasPendingForAccount(accountId)) {
                queue.forEach(t -> {
                    CaptchaService.ChallengeStatus own = captchas.pendingChallengeForTask(t.getId());
                    if (own == null) {
                        tasks.setState(t.getId(), PublishTaskState.WAITING_ACCOUNT, "ACCOUNT_CAPTCHA",
                                "同一账号的其他任务正在等待验证码", t.getNextAttemptAt());
                        return;
                    }
                    boolean uploadChallenge = isUploadChallenge(own);
                    if (!uploadChallenge) tasks.prepareCaptchaProbe(accountId, t.getId(), null);
                    LocalDateTime expiresAt = LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(own.expiresAtEpochMs()), ZoneId.systemDefault());
                    tasks.setState(t.getId(), PublishTaskState.WAITING_CAPTCHA,
                            uploadChallenge ? "UPLOAD_CAPTCHA" : "PUBLISH_CAPTCHA",
                            uploadChallenge ? "等待完成分P上传验证码"
                                    : "等待完成投稿验证码；验证码过期后将按间隔自动尝试",
                            expiresAt);
                });
                return;
            }
            PublishAccountCooldownService.CaptchaProbeGate gate = cooldowns.captchaProbeGate(accountId);
            if (gate != null) {
                PublishTask probe = tasks.get(gate.taskId());
                if (probe == null || probe.getState().isTerminal()) {
                    tasks.releaseCaptchaProbe(accountId, gate.taskId(), false);
                    releaseCaptchaBlockedTasks(accountId);
                    gate = null;
                } else if ((gate.retryAt() != null && gate.retryAt().isAfter(now))
                        || !DISPATCHABLE.contains(probe.getState())
                        || (probe.getNextAttemptAt() != null && probe.getNextAttemptAt().isAfter(now))) {
                    LocalDateTime retryAt = gate.retryAt() != null ? gate.retryAt() : probe.getNextAttemptAt();
                    holdCaptchaBlockedTasks(accountId, gate.taskId(),
                            retryAt == null ? "CAPTCHA_ACCOUNT_BLOCKED" : "CAPTCHA_AUTO_RETRY_WAIT", retryAt);
                    return;
                } else {
                    long accountWait = cooldowns.waitMs(accountId);
                    if (accountWait > 0) {
                        LocalDateTime retryAt = now.plusNanos(TimeUnit.MILLISECONDS.toNanos(accountWait));
                        tasks.prepareCaptchaProbe(accountId, gate.taskId(), retryAt);
                        holdCaptchaBlockedTasks(accountId, gate.taskId(), "CAPTCHA_AUTO_RETRY_WAIT", retryAt);
                        timer.schedule(() -> dispatch(accountId), accountWait, TimeUnit.MILLISECONDS);
                        return;
                    }
                    next = probe;
                    runtime.running = true;
                    dispatchTask(accountId, runtime, next);
                    return;
                }
            }
            if (gate == null) releaseCaptchaBlockedTasks(accountId);
            queue = tasks.getByAccountAndStates(accountId, DISPATCHABLE).stream()
                    .filter(task -> task.getNextAttemptAt() == null || !task.getNextAttemptAt().isAfter(now))
                    .toList();
            if (queue.isEmpty()) return;
            long wait = cooldowns.waitMs(accountId);
            if (wait > 0) {
                LocalDateTime nextAllowedAt = LocalDateTime.now().plusNanos(TimeUnit.MILLISECONDS.toNanos(wait));
                queue.forEach(t -> tasks.setState(t.getId(), PublishTaskState.WAITING_ACCOUNT,
                        "ACCOUNT_COOLDOWN", "账号冷却中，之后自动继续",
                        t.getNextAttemptAt() != null && t.getNextAttemptAt().isAfter(nextAllowedAt)
                                ? t.getNextAttemptAt() : nextAllowedAt));
                timer.schedule(() -> dispatch(accountId), wait, TimeUnit.MILLISECONDS);
                return;
            }
            next = queue.stream().min(Comparator.comparing(PublishTask::getCreatedAt)).orElse(null);
            if (next == null) return;
            runtime.running = true;
        }
        dispatchTask(accountId, runtime, next);
    }

    private void dispatchTask(Long accountId, AccountRuntime runtime, PublishTask next) {
        try {
            if (next.getOperation() == PublishTaskOperation.HIGH_ENERGY && !hasHighEnergyArtifact(next)) {
                prepareHighEnergy(next, runtime);
                return;
            }
            executor.execute(() -> runTask(accountId, runtime, next.getId()));
        } catch (RejectedExecutionException e) {
            synchronized (runtime) { runtime.running = false; }
            timer.schedule(() -> dispatch(accountId), 1, TimeUnit.SECONDS);
        }
    }

    private void holdCaptchaBlockedTasks(Long accountId, Long probeTaskId, String reason,
                                         LocalDateTime retryAt) {
        for (PublishTask task : tasks.getByAccountAndStates(accountId, DISPATCHABLE)) {
            if (task.getId().equals(probeTaskId)) continue;
            tasks.setState(task.getId(), PublishTaskState.WAITING_ACCOUNT, reason,
                    reason.equals("CAPTCHA_AUTO_RETRY_WAIT")
                            ? "账号等待指定稿件进行验证码自动尝试"
                            : "账号需要先处理指定稿件的验证码，其他稿件暂缓",
                    retryAt == null || (task.getNextAttemptAt() != null
                            && task.getNextAttemptAt().isAfter(retryAt))
                            ? task.getNextAttemptAt() : retryAt);
        }
    }

    private void releaseCaptchaBlockedTasks(Long accountId) {
        if (accountId == null || captchas.hasPendingForAccount(accountId)
                || cooldowns.captchaProbeGate(accountId) != null) return;
        tasks.getByAccountAndStates(accountId, EnumSet.of(PublishTaskState.WAITING_ACCOUNT)).stream()
                .filter(task -> "ACCOUNT_CAPTCHA".equals(task.getWaitReason())
                        || "CAPTCHA_AUTO_RETRY_WAIT".equals(task.getWaitReason())
                        || "CAPTCHA_ACCOUNT_BLOCKED".equals(task.getWaitReason()))
                .forEach(task -> tasks.setState(task.getId(), PublishTaskState.READY, null,
                        "账号验证码限制已解除，等待账号队列", task.getNextAttemptAt()));
    }

    private static boolean isUploadChallenge(CaptchaService.ChallengeStatus challenge) {
        return challenge != null && challenge.stage() != null
                && challenge.stage().toUpperCase(java.util.Locale.ROOT).contains("UPLOAD");
    }

    private boolean hasHighEnergyArtifact(PublishTask task) {
        if (task == null || task.getRequestSnapshot() == null) return false;
        try {
            Map<String, Object> snapshot = JSON.parseObject(task.getRequestSnapshot(), new TypeReference<Map<String, Object>>() {});
            Object fileName = snapshot.get("uploadedFileName");
            return fileName != null && !String.valueOf(fileName).isBlank();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void prepareHighEnergy(PublishTask queuedTask, AccountRuntime runtime) {
        PublishTask task = tasks.claim(queuedTask.getId(), PublishTaskState.PREPARING,
                UUID.randomUUID().toString(), LocalDateTime.now().plusHours(6));
        if (task == null) {
            synchronized (runtime) { runtime.running = false; }
            return;
        }
        try {
            highEnergyPrepareExecutor.execute(() -> {
                try {
                    RecordHistory history = histories.findById(task.getHistoryId()).orElse(null);
                    BiliBiliUser user = users.findById(task.getAccountId()).orElse(null);
                    if (history == null || history.isForceArchived()) {
                        transitionPreparing(task.getId(), PublishTaskState.NEEDS_ACTION, "HISTORY_UNAVAILABLE",
                                "稿件不存在或已强制归档", null);
                        return;
                    }
                    if (user == null || !user.isLogin()) {
                        transitionPreparing(task.getId(), PublishTaskState.NEEDS_ACTION, "ACCOUNT_NOT_LOGGED_IN",
                                "投稿账号不存在或未登录", null);
                        return;
                    }
                    RecordRoom room = rooms.findByRoomId(history.getRoomId());
                    if (room == null || (history.isPublish()
                            ? history.getPublishUserId() == null || !task.getAccountId().equals(history.getPublishUserId())
                            : !task.getAccountId().equals(room.getUploadUserId()))) {
                        transitionPreparing(task.getId(), PublishTaskState.NEEDS_ACTION,
                                history.isPublish() ? "ORIGINAL_ACCOUNT_MISMATCH" : "ACCOUNT_CHANGED",
                                "高能剪辑任务绑定账号已不符合稿件当前账号归属，请先核对账号", null);
                        return;
                    }
                    Map<String, Object> artifact = highEnergy.getObject().prepareQueuedTask(task, history);
                    tasks.updateSnapshot(task.getId(), JSON.toJSONString(artifact), "高能剪辑产物已准备，等待账号上传队列");
                    PublishTask latest = tasks.get(task.getId());
                    if (latest == null || latest.getState() != PublishTaskState.PREPARING) {
                        if (latest != null && latest.getState() == PublishTaskState.CANCELLED) {
                            highEnergy.getObject().completeQueuedTask(latest);
                        }
                        return;
                    }
                    if (artifact.get("uploadedFileName") == null
                            || String.valueOf(artifact.get("uploadedFileName")).isBlank()) {
                        highEnergy.getObject().uploadPreparedArtifact(task, history, artifact)
                                .whenComplete((uploadedArtifact, uploadError) ->
                                        finishHighEnergyUpload(task.getId(), task.getAccountId(), uploadedArtifact, uploadError));
                        return;
                    }
                    tasks.transition(task.getId(), Set.of(PublishTaskState.PREPARING), PublishTaskState.READY,
                            null, "高能剪辑产物已上传，等待账号投稿队列", null, null);
                } catch (Exception e) {
                    log.error("High-energy preparation failed taskId={}", task.getId(), e);
                    PublishTask failed = tasks.transition(task.getId(), Set.of(PublishTaskState.PREPARING), PublishTaskState.NEEDS_ACTION,
                            "HIGH_ENERGY_PREPARATION_FAILED",
                            "高能剪辑准备失败：" + e.getMessage(), null, null);
                    if (failed == null) {
                        PublishTask latest = tasks.get(task.getId());
                        if (latest != null && latest.getState() == PublishTaskState.CANCELLED) {
                            highEnergy.getObject().completeQueuedTask(latest);
                        }
                    }
                } finally {
                    synchronized (runtime) { runtime.running = false; }
                    dispatch(task.getAccountId());
                }
            });
            synchronized (runtime) { runtime.running = false; }
        } catch (RejectedExecutionException e) {
            tasks.transition(task.getId(), Set.of(PublishTaskState.PREPARING), PublishTaskState.READY,
                    "PREPARATION_QUEUE_FULL", "高能剪辑准备线程繁忙，稍后重试", LocalDateTime.now().plusSeconds(30), null);
            synchronized (runtime) { runtime.running = false; }
            timer.schedule(() -> dispatch(task.getAccountId()), 30, TimeUnit.SECONDS);
        }
    }

    private void finishHighEnergyUpload(Long taskId, Long accountId, Map<String, Object> uploadedArtifact,
                                       Throwable uploadError) {
        PublishTask latest = tasks.get(taskId);
        if (latest == null) return;
        if (latest.getState() == PublishTaskState.CANCELLED) {
            highEnergy.getObject().completeQueuedTask(latest);
            return;
        }
        if (uploadError != null) {
            Throwable cause = uploadError instanceof java.util.concurrent.CompletionException
                    && uploadError.getCause() != null ? uploadError.getCause() : uploadError;
            tasks.transition(taskId, Set.of(PublishTaskState.PREPARING, PublishTaskState.WAITING_CAPTCHA),
                    PublishTaskState.NEEDS_ACTION, "HIGH_ENERGY_UPLOAD_FAILED",
                    "高能剪辑上传失败，需要检查后重试：" + cause.getMessage(), null, null);
            dispatch(accountId);
            return;
        }
        if (latest.getState() != PublishTaskState.PREPARING || uploadedArtifact == null) return;
        tasks.updateSnapshot(taskId, JSON.toJSONString(uploadedArtifact), "高能剪辑产物已上传，等待账号投稿队列");
        tasks.transition(taskId, Set.of(PublishTaskState.PREPARING), PublishTaskState.READY,
                null, "高能剪辑产物已上传，等待账号投稿队列", null, null);
        dispatch(accountId);
    }

    private void runTask(Long accountId, AccountRuntime runtime, Long taskId) {
        boolean submissionStarted = false;
        boolean captchaAutoAttemptStarted = false;
        try {
            PublishTask task = tasks.claim(taskId, PublishTaskState.PREPARING, UUID.randomUUID().toString(),
                    LocalDateTime.now().plusMinutes(30));
            if (task == null) return;
            currentTaskId.set(taskId);
            RecordHistory history = histories.findById(task.getHistoryId()).orElse(null);
            if (history == null || history.isForceArchived()) {
                transitionPreparing(taskId, PublishTaskState.NEEDS_ACTION, "HISTORY_UNAVAILABLE",
                        history == null ? "稿件不存在" : "稿件已强制归档", null);
                return;
            }
            if (history.isPublish() && history.getPublishUserId() != null
                    && !history.getPublishUserId().equals(accountId)) {
                transitionPreparing(taskId, PublishTaskState.NEEDS_ACTION, "ORIGINAL_ACCOUNT_MISMATCH",
                        "已发布稿件必须使用原投稿账号", null);
                return;
            }
            if (history.isPublish() && history.getPublishUserId() == null) {
                transitionPreparing(taskId, PublishTaskState.NEEDS_ACTION, "ORIGINAL_ACCOUNT_MISSING",
                        "旧稿件没有记录原投稿账号；请核对账号权限后再绑定", null);
                return;
            }
            BiliBiliUser user = users.findById(accountId).orElse(null);
            if (user == null || !user.isLogin()) {
                transitionPreparing(taskId, PublishTaskState.NEEDS_ACTION, "ACCOUNT_NOT_LOGGED_IN",
                        "投稿账号不存在或未登录，请重新登录后重试", null);
                return;
            }
            if (captchas.hasPendingForAccount(accountId)) {
                transitionPreparing(taskId, PublishTaskState.WAITING_CAPTCHA, "ACCOUNT_CAPTCHA",
                        "同一账号需要先完成验证码", null);
                return;
            }
            RecordRoom room = rooms.findByRoomId(history.getRoomId());
            if (!history.isPublish() && room != null && !accountId.equals(room.getUploadUserId())) {
                transitionPreparing(taskId, PublishTaskState.NEEDS_ACTION, "ACCOUNT_CHANGED",
                        "房间投稿账号已变化，已受理任务仍绑定原账号；请核对账号后重试", null);
                return;
            }
            if (task.getOperation() == PublishTaskOperation.NEW_PUBLISH
                    || task.getOperation() == PublishTaskOperation.UPDATE
                    || task.getOperation() == PublishTaskOperation.REPAIR) {
                RecordBiliPublishService.PreparationResult prep =
                        publisher.getObject().preparePublishTask(history.getId(), accountId);
                if (prep.needsAction()) {
                    transitionPreparing(taskId, PublishTaskState.NEEDS_ACTION, "PREPARATION_FAILED", prep.message(), null);
                    return;
                }
                if (!prep.ready()) {
                    tasks.transition(taskId, Set.of(PublishTaskState.PREPARING), PublishTaskState.WAITING_UPLOAD,
                            "PARTS_NOT_READY", prep.message(),
                            prep.nextAttemptAt() == null ? LocalDateTime.now().plusSeconds(30) : prep.nextAttemptAt(), null);
                    return;
                }
            }
            if (task.getOperation() == PublishTaskOperation.EDIT_PARTS) {
                RecordBiliPublishService.PreparationResult prep =
                        publisher.getObject().prepareEditPartsTask(task);
                if (prep.needsAction()) {
                    transitionPreparing(taskId, PublishTaskState.NEEDS_ACTION, "PREPARATION_FAILED", prep.message(), null);
                    return;
                }
                if (!prep.ready()) {
                    tasks.transition(taskId, Set.of(PublishTaskState.PREPARING), PublishTaskState.WAITING_UPLOAD,
                            "EDIT_PARTS_UPLOAD", prep.message(),
                            prep.nextAttemptAt() == null ? LocalDateTime.now().plusSeconds(30) : prep.nextAttemptAt(), null);
                    return;
                }
            }
            PublishTask beforeSubmit = tasks.get(taskId);
            RecordHistory beforeSubmitHistory = histories.findById(task.getHistoryId()).orElse(null);
            BiliBiliUser beforeSubmitUser = users.findById(accountId).orElse(null);
            if (beforeSubmit == null || beforeSubmit.getState() != PublishTaskState.PREPARING) return;
            if (beforeSubmitHistory == null || beforeSubmitHistory.isForceArchived()) {
                transitionPreparing(taskId, PublishTaskState.NEEDS_ACTION, "HISTORY_UNAVAILABLE",
                        "稿件在排队准备期间已删除或强制归档", null);
                return;
            }
            if (beforeSubmitUser == null || !beforeSubmitUser.isLogin()) {
                transitionPreparing(taskId, PublishTaskState.NEEDS_ACTION, "ACCOUNT_NOT_LOGGED_IN",
                        "投稿账号在排队期间登录已失效", null);
                return;
            }
            if (captchas.hasPendingForAccount(accountId)) {
                transitionPreparing(taskId, PublishTaskState.WAITING_ACCOUNT, "ACCOUNT_CAPTCHA",
                        "账号需要先完成其他验证码", null);
                return;
            }
            long finalCooldown = cooldowns.waitMs(accountId);
            if (finalCooldown > 0) {
                transitionPreparing(taskId, PublishTaskState.WAITING_ACCOUNT, "ACCOUNT_COOLDOWN",
                        "账号冷却中，之后自动继续", LocalDateTime.now().plusNanos(
                                TimeUnit.MILLISECONDS.toNanos(finalCooldown)));
                return;
            }
            if (transitionPreparing(taskId, PublishTaskState.SUBMITTING, null,
                    "正在执行最终投稿操作", null) == null) return;
            submissionStarted = true;
            captchaAutoAttemptStarted = tasks.markCaptchaAttemptStarted(taskId);
            boolean success = switch (task.getOperation()) {
                case NEW_PUBLISH, UPDATE -> publisher.getObject().publishRecordHistory(history);
                case REPAIR -> history.isPublish()
                        ? publisher.getObject().editPublishedHistory(history, "republish")
                        : publisher.getObject().publishRecordHistory(history);
                case EDIT_PARTS -> publisher.getObject().executeQueuedEditParts(task);
                case HIGH_ENERGY -> highEnergy.getObject().executeQueuedTask(task);
            };
            RecordHistory latest = histories.findById(history.getId()).orElse(history);
            if (!success && task.getOperation() != PublishTaskOperation.HIGH_ENERGY
                    && !latest.isPublish() && !captchas.hasPendingForHistory(history.getId())) {
                RecordBiliPublishService.PreparationResult prep =
                        publisher.getObject().preparePublishTask(history.getId(), accountId);
                if (!prep.ready() && !prep.needsAction()) {
                    tasks.setState(taskId, PublishTaskState.WAITING_UPLOAD, "PARTS_NOT_READY", prep.message(),
                            prep.nextAttemptAt() == null ? LocalDateTime.now().plusSeconds(30) : prep.nextAttemptAt());
                    return;
                }
            }
            if (success || (task.getOperation() == PublishTaskOperation.NEW_PUBLISH && latest.isPublish())) {
                tasks.setState(taskId, PublishTaskState.SUCCEEDED, null, "平台已接受投稿", null);
                cooldowns.recordSuccess(accountId);
                if (task.getOperation() == PublishTaskOperation.HIGH_ENERGY) {
                    highEnergy.getObject().completeQueuedTask(tasks.get(taskId));
                } else if (task.getOperation() == PublishTaskOperation.EDIT_PARTS) {
                    publisher.getObject().cleanupTerminalEditPartsTask(tasks.get(taskId));
                }
            } else if (captchaChallengeNeedsHandling(taskId, history.getId())) {
                CaptchaService.ChallengeStatus challenge = captchas.challengeForTask(taskId);
                if (captchaAutoAttemptStarted && isUploadChallenge(challenge)) {
                    tasks.undoCaptchaAttemptForUploadChallenge(taskId);
                }
                handlePublishCaptcha(taskId, accountId);
            } else if (cooldowns.waitMs(accountId) > 0) {
                long retryDelay = cooldowns.waitMs(accountId);
                tasks.setState(taskId, PublishTaskState.WAITING_ACCOUNT, "ACCOUNT_COOLDOWN",
                        "账号冷却中，之后自动继续",
                        LocalDateTime.now().plusNanos(TimeUnit.MILLISECONDS.toNanos(retryDelay)));
            } else {
                tasks.setState(taskId, PublishTaskState.NEEDS_ACTION, "PUBLISH_NOT_CONFIRMED",
                        "平台未返回明确成功结果，请检查线上稿件后再决定是否重试", null);
            }
        } catch (Exception e) {
            log.error("Publish task failed taskId={} accountId={}", taskId, accountId, e);
            if (submissionStarted && e instanceof PublishSubmissionException submission
                    && submission.getOutcome() == PublishSubmissionException.Outcome.REJECTED
                    && isExplicitTransientRejection(e)) {
                scheduleSafeRetry(taskId, e, PublishTaskState.SUBMITTING);
            } else if (submissionStarted && e instanceof PublishSubmissionException submission
                    && submission.getOutcome() == PublishSubmissionException.Outcome.REJECTED) {
                tasks.setState(taskId, PublishTaskState.NEEDS_ACTION, "PLATFORM_REJECTED",
                        "平台明确拒绝了本次投稿，请根据平台原因处理后再重试：" + e.getMessage(), null);
            } else if (submissionStarted) {
                tasks.setState(taskId, PublishTaskState.VERIFYING, "SUBMISSION_RESULT_UNKNOWN",
                        "提交过程异常，先核对线上状态，确认前不会自动重发", null);
            } else if (isSafeTransientFailure(e)) {
                scheduleSafeRetry(taskId, e, PublishTaskState.PREPARING);
            } else {
                tasks.setState(taskId, PublishTaskState.NEEDS_ACTION, "PREPARATION_FAILED",
                        "投稿准备失败：" + e.getMessage(), null);
            }
        } finally {
            currentTaskId.remove();
            if (submissionStarted) cooldowns.recordSubmissionFinished(accountId);
            settleCaptchaProbe(accountId, taskId);
            synchronized (runtime) { runtime.running = false; }
            dispatch(accountId);
        }
    }

    private boolean captchaChallengeNeedsHandling(Long taskId, Long historyId) {
        return captchas.challengeForTask(taskId) != null || captchas.hasPendingForHistory(historyId);
    }

    private void handlePublishCaptcha(Long taskId, Long accountId) {
        CaptchaService.ChallengeStatus challenge = captchas.challengeForTask(taskId);
        if (challenge != null && isUploadChallenge(challenge)) {
            LocalDateTime expiresAt = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(challenge.expiresAtEpochMs()), ZoneId.systemDefault());
            tasks.setState(taskId, PublishTaskState.WAITING_CAPTCHA, "UPLOAD_CAPTCHA",
                    "等待完成分P上传验证码", expiresAt);
            return;
        }
        if (challenge != null && "CONSUMED".equals(challenge.state())) {
            tasks.releaseCaptchaProbe(accountId, taskId, true);
            tasks.setState(taskId, PublishTaskState.READY, null,
                    "验证码已提交，重新检查投稿结果", null);
            releaseCaptchaBlockedTasks(accountId);
            return;
        }
        tasks.prepareCaptchaProbe(accountId, taskId, null);
        LocalDateTime expiresAt = challenge == null
                ? LocalDateTime.now().plusMinutes(5)
                : LocalDateTime.ofInstant(Instant.ofEpochMilli(challenge.expiresAtEpochMs()), ZoneId.systemDefault());
        tasks.setState(taskId, PublishTaskState.WAITING_CAPTCHA, "PUBLISH_CAPTCHA",
                "等待完成投稿验证码；验证码过期且无人处理后将按 30 分钟、1 小时、2 小时自动尝试",
                expiresAt);
        if (challenge != null && "CANCELLED".equals(challenge.state())) {
            tasks.setState(taskId, PublishTaskState.NEEDS_ACTION, "CAPTCHA_CANCELLED",
                    "验证码已取消，请重新触发验证或取消投稿任务", null);
            return;
        }
        if (challenge != null && "EXPIRED".equals(challenge.state())) {
            PublishTask latest = tasks.get(taskId);
            if (latest != null) refreshPublishCaptcha(latest);
        }
    }

    private void settleCaptchaProbe(Long accountId, Long taskId) {
        PublishAccountCooldownService.CaptchaProbeGate gate = cooldowns.captchaProbeGate(accountId);
        if (gate == null || !taskId.equals(gate.taskId())) return;
        PublishTask task = tasks.get(taskId);
        if (task == null || task.getState().isTerminal()) {
            tasks.releaseCaptchaProbe(accountId, taskId, task != null
                    && task.getState() == PublishTaskState.SUCCEEDED);
            releaseCaptchaBlockedTasks(accountId);
            return;
        }
        if (task.getState() == PublishTaskState.WAITING_CAPTCHA
                || task.getState() == PublishTaskState.WAITING_UPLOAD
                || task.getState() == PublishTaskState.RETRY_WAIT
                || task.getState() == PublishTaskState.VERIFYING) return;
        if (task.getState() == PublishTaskState.NEEDS_ACTION
                && ("CAPTCHA_RETRY_EXHAUSTED".equals(task.getWaitReason())
                || "SUBMISSION_RESULT_UNKNOWN".equals(task.getWaitReason())
                || "SUBMISSION_ID_MISMATCH".equals(task.getWaitReason())
                || "PUBLISH_NOT_CONFIRMED".equals(task.getWaitReason()))) return;
        tasks.releaseCaptchaProbe(accountId, taskId, false);
        releaseCaptchaBlockedTasks(accountId);
    }

    private PublishTask transitionPreparing(Long taskId, PublishTaskState state, String reason,
                                            String message, LocalDateTime nextAttemptAt) {
        return tasks.transition(taskId, Set.of(PublishTaskState.PREPARING), state,
                reason, message, nextAttemptAt, null);
    }

    private void scheduleSafeRetry(Long taskId, Exception error, PublishTaskState expectedState) {
        PublishTask current = tasks.get(taskId);
        if (current == null || current.getState() != expectedState) return;
        int attempt = current.getRetryCount() + 1;
        long[] backoffSeconds = {30, 60, 120, 240, 300};
        if (attempt > backoffSeconds.length) {
            tasks.setState(taskId, PublishTaskState.NEEDS_ACTION, "RETRY_LIMIT_REACHED",
                    "瞬时错误已自动重试 5 次仍未恢复，请检查后手动重试：" + error.getMessage(), null);
            return;
        }
        LocalDateTime retryAt = LocalDateTime.now().plusSeconds(backoffSeconds[attempt - 1]);
        PublishTask retried = tasks.transition(taskId, Set.of(expectedState), PublishTaskState.RETRY_WAIT,
                "SAFE_TRANSIENT_FAILURE", "遇到已明确拒绝且可安全重试的瞬时错误，将于 " + retryAt + " 重试："
                        + error.getMessage(), retryAt, attempt);
        if (retried != null) {
            tasks.updateCaptchaProbeRetryAt(retried.getAccountId(), taskId, retryAt);
        }
    }

    private static boolean isExplicitTransientRejection(Throwable error) {
        String message = error == null ? "" : String.valueOf(error.getMessage()).toLowerCase(java.util.Locale.ROOT);
        if (!message.contains("code=")) return false;
        return message.contains("系统繁忙") || message.contains("服务器繁忙")
                || message.contains("稍后重试") || message.contains("稍后再试")
                || message.contains("temporarily unavailable") || message.contains("server busy");
    }

    private static boolean isSafeTransientFailure(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SocketTimeoutException || current instanceof ConnectException
                    || current instanceof TransientDataAccessException) return true;
        }
        return false;
    }

    @PreDestroy
    public void shutdown() { timer.shutdownNow(); }

    private static final class AccountRuntime { private boolean running; }
}
