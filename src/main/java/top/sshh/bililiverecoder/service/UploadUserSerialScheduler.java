package top.sshh.bililiverecoder.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.util.LogKvs;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class UploadUserSerialScheduler {

    private final Executor asyncExecutor;
    private final ObjectProvider<UploadPauseService> uploadPauseServiceProvider;
    @Autowired(required = false)
    private PublishTaskService publishTaskService;
    @Autowired(required = false)
    private CaptchaService captchaService;
    @Autowired(required = false)
    private PartActivityCoordinator partActivityCoordinator;
    @Autowired(required = false)
    private HistoryUploadAdmissionService historyUploadAdmissionService;
    private final ConcurrentHashMap<Long, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicInteger> pendingCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicInteger> pendingPartCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicInteger> pendingHistoryCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, CompletableFuture<?>> fixedCaptchaWaiters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, CompletableFuture<?>> fixedTaskUploads = new ConcurrentHashMap<>();
    private final java.util.Set<Long> runningFixedTaskUploads = ConcurrentHashMap.newKeySet();
    private final Object fixedTaskUploadLock = new Object();
    private final ThreadLocal<Long> executingPartId = new ThreadLocal<>();
    private final ScheduledExecutorService rejectFallbackScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "upload-serial-requeue");
                t.setDaemon(true);
                return t;
            });
    private static final int MAX_REQUEUE_ATTEMPTS = 60;
    private static final long REQUEUE_DELAY_MS = 1000L;

    public UploadUserSerialScheduler(@Qualifier("uploadExecutor") Executor asyncExecutor,
                                     ObjectProvider<UploadPauseService> uploadPauseServiceProvider) {
        this.asyncExecutor = asyncExecutor;
        this.uploadPauseServiceProvider = uploadPauseServiceProvider;
    }

    public void submit(Long uploadUserId, String roomId, Long historyId, Long partId, String os, Runnable task) {
        Long accountId = resolveQueueAccount(uploadUserId, historyId, partId, os);
        if (accountId != null) submitInternal(accountId, roomId, historyId, partId, os, task, 0, true, false);
    }

    public boolean submitIfPartNotPending(Long uploadUserId, String roomId, Long historyId, Long partId, String os, Runnable task) {
        Long accountId = resolveQueueAccount(uploadUserId, historyId, partId, os);
        return accountId != null
                && submitInternal(accountId, roomId, historyId, partId, os, task, 0, true, true);
    }

    private Long resolveQueueAccount(Long configuredAccountId, Long historyId, Long partId, String os) {
        Long accountId = publishTaskService == null
                ? configuredAccountId
                : publishTaskService.resolveUploadAccountId(historyId, configuredAccountId);
        if (accountId == null) {
            log.warn("[BLR] {}", LogKvs.event("Upload.SerialScheduler.NoStableAccount")
                    .add("os", os).add("historyId", historyId).add("partId", partId));
        }
        return accountId;
    }

    public <T> CompletableFuture<T> submitForFixedAccount(Long accountId, Callable<T> task) {
        return submitForFixedAccount(accountId, null, null, null, task);
    }

    public <T> CompletableFuture<T> submitForFixedAccount(Long accountId, Long historyId, Long taskId,
                                                           String captchaStage, Callable<T> task) {
        // 该入口用于已持久化账号任务，必须沿用任务受理时固定的账号
        if (accountId == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("投稿任务没有固定账号"));
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        if (historyId != null) {
            if (historyUploadAdmissionService == null || !historyUploadAdmissionService.tryRegisterHistoryUpload(historyId)) {
                return CompletableFuture.failedFuture(new IllegalStateException("稿件正在删除或已归档，不能继续上传"));
            }
            pendingHistoryCounts.computeIfAbsent(historyId, ignored -> new AtomicInteger()).incrementAndGet();
            result.whenComplete((value, error) -> {
                AtomicInteger count = pendingHistoryCounts.get(historyId);
                if (count != null && count.decrementAndGet() <= 0) pendingHistoryCounts.remove(historyId, count);
                if (partActivityCoordinator != null) partActivityCoordinator.releaseUpload(null, historyId);
            });
        }
        if (taskId != null) {
            if (fixedTaskUploads.putIfAbsent(taskId, result) != null) {
                result.completeExceptionally(new IllegalStateException("同一投稿任务已有上传操作正在排队"));
                return result;
            }
            result.whenComplete((value, error) -> fixedTaskUploads.remove(taskId, result));
        }
        return enqueueFixedAccountAttempt(accountId, historyId, taskId, captchaStage, task, result);
    }

    private <T> CompletableFuture<T> enqueueFixedAccountAttempt(Long accountId, Long historyId, Long taskId,
                                                                 String captchaStage, Callable<T> task,
                                                                 CompletableFuture<T> result) {
        if (result.isDone()) return result;
        CompletableFuture<Void> next = tails.compute(accountId, (uid, tail) -> {
            CompletableFuture<Void> safeTail = tail == null ? CompletableFuture.completedFuture(null)
                    : tail.exceptionally(ex -> null);
            return safeTail.thenRunAsync(() -> {
                boolean uploadMarkedRunning = false;
                try {
                    if (result.isDone()) return;
                    if (taskId != null) {
                        synchronized (fixedTaskUploadLock) {
                            if (result.isDone()) return;
                            runningFixedTaskUploads.add(taskId);
                            uploadMarkedRunning = true;
                        }
                    }
                    if (taskId != null && publishTaskService != null) {
                        top.sshh.bililiverecoder.entity.PublishTask publishTask = publishTaskService.get(taskId);
                        if (publishTask == null || publishTask.getState() != top.sshh.bililiverecoder.entity.PublishTaskState.PREPARING) {
                            throw new IllegalStateException("投稿任务已取消或不再等待上传");
                        }
                    }
                    T value;
                    if (uploadMarkedRunning) {
                        try {
                            value = task.call();
                        } finally {
                            synchronized (fixedTaskUploadLock) {
                                runningFixedTaskUploads.remove(taskId);
                            }
                            uploadMarkedRunning = false;
                        }
                    } else {
                        value = task.call();
                    }
                    result.complete(value);
                } catch (Throwable error) {
                    CaptchaChallengeRequiredException captcha = CaptchaChallengeRequiredException.find(error);
                    if (captcha != null && captchaService != null) {
                        if (taskId != null && publishTaskService != null) {
                            top.sshh.bililiverecoder.entity.PublishTask waitingTask = publishTaskService.transition(taskId,
                                    java.util.Set.of(top.sshh.bililiverecoder.entity.PublishTaskState.PREPARING),
                                    top.sshh.bililiverecoder.entity.PublishTaskState.WAITING_CAPTCHA,
                                    captchaStage == null ? "UPLOAD_CAPTCHA" : captchaStage,
                                    "上传遇到验证码，等待用户完成验证", null, null);
                            if (waitingTask == null) {
                                result.completeExceptionally(new IllegalStateException("投稿任务已取消，忽略后续验证码等待"));
                                return;
                            }
                            fixedCaptchaWaiters.put(taskId, result);
                        }
                        boolean waiting = captchaService.whenSubmitted(captcha.getRequestId(), () -> {
                            if (result.isDone()) return;
                            if (taskId != null) fixedCaptchaWaiters.remove(taskId, result);
                            if (taskId != null && publishTaskService != null) {
                                top.sshh.bililiverecoder.entity.PublishTask resumed = publishTaskService.transition(taskId,
                                        java.util.Set.of(top.sshh.bililiverecoder.entity.PublishTaskState.WAITING_CAPTCHA),
                                        top.sshh.bililiverecoder.entity.PublishTaskState.PREPARING,
                                        null, "验证码已提交，恢复账号上传队列", null, null);
                                if (resumed == null) {
                                    result.completeExceptionally(new IllegalStateException("投稿任务已取消，已忽略验证码回调"));
                                    return;
                                }
                            }
                            enqueueFixedAccountAttempt(accountId, historyId, taskId, captchaStage, task, result);
                        });
                        if (waiting) {
                        log.info("[BLR] {}", LogKvs.event("Upload.SerialScheduler.PausedForCaptcha")
                                .add("uploadUserId", accountId).add("requestId", captcha.getRequestId()));
                        return;
                        }
                        if (taskId != null) fixedCaptchaWaiters.remove(taskId, result);
                        if (taskId != null && publishTaskService != null) {
                            publishTaskService.transition(taskId,
                                    java.util.Set.of(top.sshh.bililiverecoder.entity.PublishTaskState.WAITING_CAPTCHA),
                                    top.sshh.bililiverecoder.entity.PublishTaskState.PREPARING,
                                    null, "验证码已过期，上传任务将回到错误处理", null, null);
                        }
                    }
                    result.completeExceptionally(error);
                    throw new CompletionException(error);
                } finally {
                    if (uploadMarkedRunning) {
                        synchronized (fixedTaskUploadLock) {
                            runningFixedTaskUploads.remove(taskId);
                        }
                    }
                }
            }, asyncExecutor);
        });
        next.whenComplete((ignored, error) -> {
            tails.compute(accountId, (uid, current) -> current == next ? null : current);
            if (error != null) result.completeExceptionally(error);
        });
        return result;
    }

    public boolean cancelWaitingFixedUpload(Long taskId) {
        if (taskId == null) return false;
        CompletableFuture<?> waiting = fixedTaskUploads.get(taskId);
        if (waiting == null) return false;
        synchronized (fixedTaskUploadLock) {
            if (runningFixedTaskUploads.contains(taskId)) return false;
            fixedCaptchaWaiters.remove(taskId, waiting);
            return waiting.completeExceptionally(
                    new java.util.concurrent.CancellationException("稿件删除或任务取消，上传队列等待已解除"));
        }
    }

    public boolean hasPendingPart(Long partId) {
        if (partId == null) {
            return false;
        }
        AtomicInteger counter = pendingPartCounts.get(partId);
        return counter != null && counter.get() > 0;
    }

    public boolean hasPendingHistory(Long historyId) {
        if (historyId == null) return false;
        AtomicInteger counter = pendingHistoryCounts.get(historyId);
        return counter != null && counter.get() > 0;
    }

    public boolean hasPendingPartOutsideCurrentExecution(Long partId) {
        if (partId == null) return false;
        AtomicInteger counter = pendingPartCounts.get(partId);
        if (counter == null) return false;
        int currentTaskCount = isCurrentPartExecution(partId) ? 1 : 0;
        return counter.get() > currentTaskCount;
    }

    public boolean isCurrentPartExecution(Long partId) {
        return partId != null && partId.equals(executingPartId.get());
    }

    /**
     * 获取所有待上传文件总数
     */
    public int getTotalPendingUploadCount() {
        return pendingPartCounts.values().stream()
                .mapToInt(AtomicInteger::get)
                .sum();
    }

    private boolean submitInternal(Long uploadUserId, String roomId, Long historyId, Long partId, String os, Runnable task, int requeueAttempt, boolean countAsNew, boolean dedupeByPart) {
        if (partId != null && isUploadPaused(historyId, partId)) {
            if (!countAsNew) releasePending(uploadUserId, historyId, partId);
            log.info("[BLR] {}", LogKvs.event("Upload.SerialScheduler.SkipPaused")
                    .add("os", os)
                    .add("uploadUserId", uploadUserId)
                    .add("roomId", roomId)
                    .add("historyId", historyId)
                    .add("partId", partId)
                    .add("requeueAttempt", requeueAttempt));
            return false;
        }
        if (uploadUserId == null) {
            try {
                CompletableFuture.runAsync(task, asyncExecutor);
            } catch (RejectedExecutionException rejected) {
                scheduleRequeue(uploadUserId, roomId, historyId, partId, os, task, requeueAttempt, "DIRECT_REJECTED", rejected, dedupeByPart);
            }
            return true;
        }
        boolean registeredPartActivity = countAsNew && partId != null && partActivityCoordinator != null;
        boolean activityRegistered = false;
        if (registeredPartActivity) {
            activityRegistered = historyUploadAdmissionService != null && historyId != null
                    ? historyUploadAdmissionService.tryRegisterUpload(historyId, partId)
                    : partActivityCoordinator.tryRegisterUpload(partId, historyId);
        }
        if (registeredPartActivity && !activityRegistered) {
            log.info("[BLR] {}", LogKvs.event("Upload.SerialScheduler.PartOperationInProgress")
                    .add("uploadUserId", uploadUserId).add("partId", partId).add("os", os));
            return false;
        }
        AtomicInteger counter = pendingCounts.computeIfAbsent(uploadUserId, k -> new AtomicInteger(0));
        int pendingPartQueueDepth = 0;
        if (partId != null && countAsNew) {
            if (dedupeByPart) {
                AtomicBoolean duplicated = new AtomicBoolean(false);
                AtomicInteger partCounter = pendingPartCounts.compute(partId, (id, existing) -> {
                    if (existing == null) {
                        return new AtomicInteger(1);
                    }
                    int current = existing.get();
                    if (current > 0) {
                        duplicated.set(true);
                        return existing;
                    }
                    existing.incrementAndGet();
                    return existing;
                });
                if (duplicated.get()) {
                    if (activityRegistered) partActivityCoordinator.releaseUpload(partId, historyId);
                    int depth = partCounter == null ? 1 : Math.max(1, partCounter.get());
                    log.debug("[BLR] {}", LogKvs.event("Upload.SerialScheduler.DuplicatePartSkipped")
                            .add("os", os)
                            .add("uploadUserId", uploadUserId)
                            .add("roomId", roomId)
                            .add("historyId", historyId)
                            .add("partId", partId)
                            .add("pendingPartQueueDepth", depth));
                    return false;
                }
                pendingPartQueueDepth = partCounter == null ? 1 : Math.max(1, partCounter.get());
            } else {
                AtomicInteger partCounter = pendingPartCounts.computeIfAbsent(partId, k -> new AtomicInteger(0));
                pendingPartQueueDepth = partCounter.incrementAndGet();
            }
        }
        if (historyId != null && countAsNew) {
            pendingHistoryCounts.computeIfAbsent(historyId, k -> new AtomicInteger(0)).incrementAndGet();
        }
        int queueDepth = countAsNew ? counter.incrementAndGet() : Math.max(counter.get(), 1);
        log.info("[BLR] {}", LogKvs.event("Upload.SerialScheduler.Enqueued")
                .add("os", os)
                .add("uploadUserId", uploadUserId)
                .add("roomId", roomId)
                .add("historyId", historyId)
                .add("partId", partId)
                .add("queueDepth", queueDepth)
                .add("pendingPartQueueDepth", pendingPartQueueDepth)
                .add("requeueAttempt", requeueAttempt));

        CompletableFuture<Void> next = tails.compute(uploadUserId, (uid, tail) -> {
            CompletableFuture<Void> safeTail = tail == null ? CompletableFuture.completedFuture(null) : tail.exceptionally(ex -> {
                log.warn("[BLR] {}", LogKvs.event("Upload.SerialScheduler.TailRecovered")
                        .add("os", os)
                        .add("uploadUserId", uid)
                        .add("roomId", roomId)
                        .add("historyId", historyId)
                        .add("partId", partId)
                        .addIfNotBlank("err", ex.getMessage())
                        .add("ex", ex.getClass().getSimpleName()), ex);
                return null;
            });
            return safeTail.thenRunAsync(() -> {
                log.info("[BLR] {}", LogKvs.event("Upload.SerialScheduler.Dispatch")
                        .add("os", os)
                        .add("uploadUserId", uid)
                        .add("roomId", roomId)
                        .add("historyId", historyId)
                        .add("partId", partId)
                        .add("thread", Thread.currentThread().getName()));
                Long previousPartId = executingPartId.get();
                if (partId == null) executingPartId.remove();
                else executingPartId.set(partId);
                try {
                    if (partActivityCoordinator == null || partId == null) {
                        task.run();
                    } else {
                        try (PartActivityCoordinator.UploadScope ignored = partActivityCoordinator.enterUpload(partId)) {
                            task.run();
                        }
                    }
                } catch (RuntimeException | Error error) {
                    CaptchaChallengeRequiredException captcha = CaptchaChallengeRequiredException.find(error);
                    if (captcha == null || captchaService == null
                            || !captchaService.whenSubmitted(captcha.getRequestId(),
                            () -> scheduleCaptchaUploadResume(uploadUserId, roomId, historyId,
                                    partId, os, task, captcha.getRequestId(), 0))) {
                        throw error;
                    }
                    log.info("[BLR] {}", LogKvs.event("Upload.SerialScheduler.PausedForCaptcha")
                            .add("uploadUserId", uploadUserId).add("historyId", historyId)
                            .add("partId", partId).add("requestId", captcha.getRequestId()));
                } finally {
                    if (previousPartId == null) executingPartId.remove();
                    else executingPartId.set(previousPartId);
                }
            }, asyncExecutor);
        });

        next.whenComplete((ok, ex) -> {
            if (isRejectedException(ex)) {
                if (requeueAttempt < MAX_REQUEUE_ATTEMPTS) {
                    tails.compute(uploadUserId, (uid, current) -> current == next ? null : current);
                    try {
                        scheduleRequeue(uploadUserId, roomId, historyId, partId, os, task,
                                requeueAttempt, "ASYNC_REJECTED", ex, dedupeByPart);
                        return;
                    } catch (RejectedExecutionException schedulerRejected) {
                        log.error("[BLR] {}", LogKvs.event("Upload.SerialScheduler.RequeueRejected")
                                .add("os", os).add("uploadUserId", uploadUserId)
                                .add("partId", partId).addIfNotBlank("err", schedulerRejected.getMessage()));
                    }
                }
                log.error("[BLR] {}", LogKvs.event("Upload.SerialScheduler.RequeueGiveUp")
                        .add("os", os)
                        .add("uploadUserId", uploadUserId)
                        .add("roomId", roomId)
                        .add("historyId", historyId)
                        .add("partId", partId)
                        .add("requeueAttempt", requeueAttempt)
                        .addIfNotBlank("err", ex.getMessage())
                        .add("ex", ex.getClass().getSimpleName()), ex);
            }
            tails.compute(uploadUserId, (uid, current) -> current == next ? null : current);
            int left = releasePending(uploadUserId, historyId, partId);
            if (ex == null) {
                log.info("[BLR] {}", LogKvs.event("Upload.SerialScheduler.Completed")
                        .add("os", os)
                        .add("uploadUserId", uploadUserId)
                        .add("roomId", roomId)
                        .add("historyId", historyId)
                        .add("partId", partId)
                        .add("leftQueueDepth", Math.max(left, 0)));
            } else {
                log.error("[BLR] {}", LogKvs.event("Upload.SerialScheduler.Failed")
                        .add("os", os)
                        .add("uploadUserId", uploadUserId)
                        .add("roomId", roomId)
                        .add("historyId", historyId)
                        .add("partId", partId)
                        .add("leftQueueDepth", Math.max(left, 0))
                        .addIfNotBlank("err", ex.getMessage())
                        .add("ex", ex.getClass().getSimpleName()), ex);
            }
        });
        return true;
    }

    private int releasePending(Long uploadUserId, Long historyId, Long partId) {
        int left = 0;
        AtomicInteger count = pendingCounts.get(uploadUserId);
        if (count != null) {
            left = count.updateAndGet(value -> Math.max(0, value - 1));
            if (left == 0) pendingCounts.remove(uploadUserId, count);
        }
        if (partId != null) {
            if (partActivityCoordinator != null) partActivityCoordinator.releaseUpload(partId, historyId);
            AtomicInteger partCount = pendingPartCounts.get(partId);
            if (partCount != null) {
                int remaining = partCount.updateAndGet(value -> Math.max(0, value - 1));
                if (remaining == 0) pendingPartCounts.remove(partId, partCount);
            }
        }
        if (historyId != null) {
            AtomicInteger historyCount = pendingHistoryCounts.get(historyId);
            if (historyCount != null) {
                int remaining = historyCount.updateAndGet(value -> Math.max(0, value - 1));
                if (remaining == 0) pendingHistoryCounts.remove(historyId, historyCount);
            }
        }
        return left;
    }

    private void scheduleCaptchaUploadResume(Long uploadUserId, String roomId, Long historyId,
                                             Long partId, String os, Runnable task,
                                             String requestId, int attempt) {
        if (attempt >= 240 || captchaService == null) {
            log.warn("[BLR] {}", LogKvs.event("Upload.SerialScheduler.CaptchaResumeExpired")
                    .add("uploadUserId", uploadUserId).add("historyId", historyId)
                    .add("partId", partId).add("requestId", requestId));
            return;
        }
        rejectFallbackScheduler.schedule(() -> {
            CaptchaService.ChallengeStatus challenge = captchaService.challengeStatus(requestId);
            if (challenge == null || !"SUBMITTED".equals(challenge.state())) return;
            boolean accepted = submitInternal(uploadUserId, roomId, historyId, partId, os,
                    task, 0, true, true);
            if (!accepted) {
                scheduleCaptchaUploadResume(uploadUserId, roomId, historyId, partId,
                        os, task, requestId, attempt + 1);
            }
        }, REQUEUE_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private boolean isUploadPaused(Long historyId, Long partId) {
        UploadPauseService uploadPauseService = uploadPauseServiceProvider.getIfAvailable();
        return uploadPauseService != null && uploadPauseService.isUploadPaused(historyId, partId);
    }

    private void scheduleRequeue(Long uploadUserId, String roomId, Long historyId, Long partId, String os, Runnable task, int requeueAttempt, String rejectStage, Throwable ex, boolean dedupeByPart) {
        int nextAttempt = requeueAttempt + 1;
        log.warn("[BLR] {}", LogKvs.event("Upload.SerialScheduler.RequeueOnRejected")
                .add("os", os)
                .add("uploadUserId", uploadUserId)
                .add("roomId", roomId)
                .add("historyId", historyId)
                .add("partId", partId)
                .add("rejectStage", rejectStage)
                .add("requeueAttempt", nextAttempt)
                .add("delayMs", REQUEUE_DELAY_MS)
                .addIfNotBlank("err", ex != null ? ex.getMessage() : null)
                .add("ex", ex != null ? ex.getClass().getSimpleName() : "unknown"), ex);
        rejectFallbackScheduler.schedule(
            () -> submitInternal(uploadUserId, roomId, historyId, partId, os, task, nextAttempt, false, dedupeByPart),
                REQUEUE_DELAY_MS,
                TimeUnit.MILLISECONDS
        );
    }

    private boolean isRejectedException(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        if (throwable instanceof RejectedExecutionException) {
            return true;
        }
        return isRejectedException(throwable.getCause());
    }
}
