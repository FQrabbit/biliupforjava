package top.sshh.bililiverecoder.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
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

@Slf4j
@Service
public class UploadUserSerialScheduler {

    private final Executor asyncExecutor;
    private final ConcurrentHashMap<Long, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicInteger> pendingCounts = new ConcurrentHashMap<>();
    private final ScheduledExecutorService rejectFallbackScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "upload-serial-requeue");
                t.setDaemon(true);
                return t;
            });
    private static final int MAX_REQUEUE_ATTEMPTS = 60;
    private static final long REQUEUE_DELAY_MS = 1000L;

    public UploadUserSerialScheduler(@Qualifier("myAsyncPool") Executor asyncExecutor) {
        this.asyncExecutor = asyncExecutor;
    }

    public void submit(Long uploadUserId, String roomId, Long historyId, Long partId, String os, Runnable task) {
        submitInternal(uploadUserId, roomId, historyId, partId, os, task, 0, true);
    }

    private void submitInternal(Long uploadUserId, String roomId, Long historyId, Long partId, String os, Runnable task, int requeueAttempt, boolean countAsNew) {
        if (uploadUserId == null) {
            try {
                CompletableFuture.runAsync(task, asyncExecutor);
            } catch (RejectedExecutionException rejected) {
                scheduleRequeue(uploadUserId, roomId, historyId, partId, os, task, requeueAttempt, "DIRECT_REJECTED", rejected);
            }
            return;
        }
        AtomicInteger counter = pendingCounts.computeIfAbsent(uploadUserId, k -> new AtomicInteger(0));
        int queueDepth = countAsNew ? counter.incrementAndGet() : Math.max(counter.get(), 1);
        log.info("[BLR] {}", LogKvs.event("Upload.SerialScheduler.Enqueued")
                .add("os", os)
                .add("uploadUserId", uploadUserId)
                .add("roomId", roomId)
                .add("historyId", historyId)
                .add("partId", partId)
                .add("queueDepth", queueDepth)
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
                task.run();
            }, asyncExecutor);
        });

        next.whenComplete((ok, ex) -> {
            if (isRejectedException(ex)) {
                if (requeueAttempt < MAX_REQUEUE_ATTEMPTS) {
                    scheduleRequeue(uploadUserId, roomId, historyId, partId, os, task, requeueAttempt, "ASYNC_REJECTED", ex);
                    return;
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
            int left = 0;
            AtomicInteger c = pendingCounts.get(uploadUserId);
            if (c != null) {
                left = c.decrementAndGet();
                if (left <= 0) {
                    pendingCounts.remove(uploadUserId, c);
                }
            }
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
    }

    private void scheduleRequeue(Long uploadUserId, String roomId, Long historyId, Long partId, String os, Runnable task, int requeueAttempt, String rejectStage, Throwable ex) {
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
                () -> submitInternal(uploadUserId, roomId, historyId, partId, os, task, nextAttempt, false),
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
