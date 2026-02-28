package top.sshh.bililiverecoder.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import top.sshh.bililiverecoder.lifecycle.ShutdownState;
import top.sshh.bililiverecoder.util.LogKvs;

@Slf4j
@Component
public class WebhookEventDispatcher {

    private final TaskExecutor webhookExecutor;

    private final TaskScheduler scheduler;

    private final ShutdownState shutdownState;

    private final ConcurrentHashMap<String, SerialExecutor> executors = new ConcurrentHashMap<>();

    private final int maxPendingPerKey;

    private final long idleTtlMillis;

    private final ScheduledFuture<?> cleanupFuture;

    public WebhookEventDispatcher(
            @Qualifier("webhookExecutor") TaskExecutor webhookExecutor,
            @Qualifier("webhookTaskScheduler") TaskScheduler scheduler,
            ShutdownState shutdownState,
            // 每个 lockKey 的最大待处理任务数；过大可能导致内存增长，过小可能导致 503 重试
            @org.springframework.beans.factory.annotation.Value("${record.webhook.max-pending-per-key:200}") int maxPendingPerKey,
            @org.springframework.beans.factory.annotation.Value("${record.webhook.idle-ttl-minutes:120}") long idleTtlMinutes
    ) {
        this.webhookExecutor = Objects.requireNonNull(webhookExecutor, "webhookExecutor");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.shutdownState = Objects.requireNonNull(shutdownState, "shutdownState");
        this.maxPendingPerKey = Math.max(10, maxPendingPerKey);
        this.idleTtlMillis = Duration.ofMinutes(Math.max(10, idleTtlMinutes)).toMillis();

        // 定期清理不再使用的 lockKey，避免 relativePath 键无限增长
        this.cleanupFuture = this.scheduler.scheduleAtFixedRate(this::cleanupIdleExecutors, Duration.ofMinutes(10));
    }

    /**
     * @return true 表示成功入队；false 表示队列压力过大，应让发送方重试
     */
    public boolean submit(String lockKey, long delayMs, Runnable task) {
        if (shutdownState.isShuttingDown()) {
            return false;
        }
        if (task == null) {
            return true;
        }
        if (lockKey == null || lockKey.isBlank()) {
            lockKey = "brec:unknown";
        }

        SerialExecutor serialExecutor = executors.computeIfAbsent(lockKey, k -> new SerialExecutor(webhookExecutor, maxPendingPerKey));
        serialExecutor.touch();

        Runnable wrapped = () -> {
            try {
                task.run();
            } finally {
                serialExecutor.touch();
            }
        };

        if (delayMs > 0) {
            // 延迟不占用工作线程
            if (!serialExecutor.tryReserve()) {
                return false;
            }
            scheduler.schedule(() -> serialExecutor.executeReserved(wrapped), new Date(System.currentTimeMillis() + delayMs));
            return true;
        }

        return serialExecutor.tryExecute(wrapped);
    }

    private void cleanupIdleExecutors() {
        try {
            long now = System.currentTimeMillis();
            for (Map.Entry<String, SerialExecutor> entry : executors.entrySet()) {
                SerialExecutor executor = entry.getValue();
                if (executor == null) {
                    continue;
                }
                // First do a quick time check to avoid unnecessary locking for recent executors.
                if ((now - executor.lastUsedAtMillis) <= idleTtlMillis) {
                    continue;
                }
                // Re-check both the idle state and lastUsedAtMillis atomically.
                synchronized (executor) {
                    if (executor.isIdle() && (now - executor.lastUsedAtMillis) > idleTtlMillis) {
                        executors.remove(entry.getKey(), executor);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[BLR] {}", LogKvs.event("Webhook.CleanupError")
                    .add("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()));
        }
    }

    @PreDestroy
    public void shutdown() {
        try {
            cleanupFuture.cancel(false);
        } catch (Exception ignored) {
        }
        // scheduler 由 Spring 管理，这里不主动 shutdown
    }

    private static final class SerialExecutor {
        private final TaskExecutor executor;
        private final int maxPending;

        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        private Runnable active;

        private int pending;

        private volatile long lastUsedAtMillis = System.currentTimeMillis();

        private SerialExecutor(TaskExecutor executor, int maxPending) {
            this.executor = executor;
            this.maxPending = maxPending;
        }

        private synchronized boolean tryExecute(Runnable task) {
            if (pending >= maxPending) {
                return false;
            }
            pending++;
            tasks.offer(wrap(task));
            if (active == null) {
                scheduleNext();
            }
            return true;
        }

        /**
         * 给延迟任务预留一个 pending 名额。
         */
        private synchronized boolean tryReserve() {
            if (pending >= maxPending) {
                return false;
            }
            pending++;
            return true;
        }

        private synchronized void executeReserved(Runnable task) {
            tasks.offer(wrap(task));
            if (active == null) {
                scheduleNext();
            }
        }

        private Runnable wrap(Runnable task) {
            return () -> {
                try {
                    task.run();
                } finally {
                    onTaskFinished();
                }
            };
        }

        private synchronized void onTaskFinished() {
            pending = Math.max(0, pending - 1);
            active = null;
            scheduleNext();
        }

        private synchronized void scheduleNext() {
            if ((active = tasks.poll()) != null) {
                try {
                    executor.execute(active);
                } catch (RejectedExecutionException rejected) {
                    // 线程池满：把 active 放回队列头，等待后续清理；并释放 pending
                    tasks.addFirst(active);
                    active = null;
                    pending = Math.max(0, pending - 1);
                    throw rejected;
                }
            }
        }

        private void touch() {
            lastUsedAtMillis = System.currentTimeMillis();
        }

        private synchronized boolean isIdle() {
            return active == null && tasks.isEmpty() && pending == 0;
        }
    }

}
