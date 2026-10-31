package top.sshh.bililiverecoder.protection;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.config.db.MaintenanceDataSource;
import top.sshh.bililiverecoder.lifecycle.ShutdownState;
import top.sshh.bililiverecoder.service.*;
import top.sshh.bililiverecoder.service.backup.BackupService;
import top.sshh.bililiverecoder.util.TaskUtil;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class DiskSpaceRuntime {
    private final ConfigurableApplicationContext context;
    private final DatabaseMaintenanceState state;
    private final DataSource dataSource;
    private final ShutdownState shutdown;
    private final DiskProtectionNotifications notifications;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private java.util.function.Consumer<String> sender;

    public DiskSpaceRuntime(ConfigurableApplicationContext context, DatabaseMaintenanceState state,
                            DataSource dataSource, ShutdownState shutdown, DiskProtectionNotifications notifications) {
        this.context = context; this.state = state; this.dataSource = dataSource;
        this.shutdown = shutdown; this.notifications = notifications;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() throws Exception {
        if (DiskSpaceGuard.current() == null) return;
        DiskSpaceBootstrap.runningContext = context;
        try { sender = notifications.snapshotSender(); }
        catch (RuntimeException unavailable) { sender = ignored -> {}; System.err.println("磁盘保护通知配置暂时无法载入，保护功能继续生效"); }
    }

    public void activate() throws Exception {
        DiskSpaceGuard guard = DiskSpaceGuard.current();
        if (guard == null) return;
        guard.applicationReady();
        guard.attach(() -> stop(false), () -> stop(true), sender);
    }

    @EventListener(org.springframework.context.event.ContextClosedEvent.class)
    public void closed(org.springframework.context.event.ContextClosedEvent event) {
        if (event.getApplicationContext() != context) return;
        DiskSpaceGuard guard = DiskSpaceGuard.current();
        if (guard != null) guard.detach();
    }

    private void stop(boolean resumeAfterStop) {
        if (!stopping.compareAndSet(false, true)) return;
        DiskSpaceGuard.current().suspendResume();
        Thread worker = new Thread(() -> {
            DiskSpaceGuard guard = DiskSpaceGuard.current();
            try {
                stopCore(guard);
                guard.detach();
                if (resumeAfterStop) guard.beginRecovery();
                else DiskSpaceBootstrap.serveUntilResume(guard);
                DiskSpaceBootstrap.run(top.sshh.bililiverecoder.service.CoreRestartService.applicationArguments());
            } catch (Exception failure) {
                if (!state.isMaintenanceActive()) state.retainDatabasePause();
                guard.failed(failure);
            } finally {
                stopping.set(false);
                if (context.isActive() && guard.phase() == DiskSpaceGuard.Phase.FAILED) {
                    guard.attach(() -> stop(false), () -> stop(true), null);
                }
            }
        }, "disk-protection-control");
        worker.setDaemon(false); worker.start();
    }

    void stopCore(DiskSpaceGuard guard) throws Exception {
        boolean saved = guard.saveProtection();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(guard.settings().drainMillis());
        guard.stage("正在等待已有维护操作到达安全边界");
        while (state.isMaintenanceActive()) {
            if (System.nanoTime() >= deadline) throw new IllegalStateException("已有数据库维护尚未结束，保护继续生效，未强行中断文件替换");
            Thread.sleep(50);
        }
        shutdown.markShuttingDown();
        guard.stage("正在暂停后台任务并等待数据库操作收尾");
        Set<Thread> uploads = new HashSet<>(TaskUtil.partUploadTask.values());
        uploads.addAll(TaskUtil.publishTask.values());
        uploads.stream().filter(Objects::nonNull).forEach(Thread::interrupt);
        List<ThreadPoolExecutor> executors = new ArrayList<>();
        for (ThreadPoolTaskExecutor executor : context.getBeansOfType(ThreadPoolTaskExecutor.class).values()) {
            ThreadPoolExecutor pool = executor.getThreadPoolExecutor(); pool.shutdownNow(); executors.add(pool);
        }
        for (ThreadPoolTaskScheduler scheduler : context.getBeansOfType(ThreadPoolTaskScheduler.class).values()) {
            ThreadPoolExecutor pool = scheduler.getScheduledThreadPoolExecutor(); pool.shutdownNow(); executors.add(pool);
        }
        context.getBean(PartPreviewService.class).shutdown();
        context.getBean(BackupService.class).stopForDiskProtection();
        context.getBean(PublishAccountScheduler.class).shutdown();
        context.getBean(UploadPauseService.class).shutdown();
        context.getBean(UploadUserSerialScheduler.class).shutdown();
        List<ProcessHandle> processes = ProcessHandle.current().descendants().filter(process -> process.info().command()
                .map(command -> java.nio.file.Path.of(command).getFileName().toString().matches("(?i)ff(mpeg|probe)(\\.exe)?"))
                .orElse(false)).toList();
        processes.forEach(ProcessHandle::destroy);
        state.pauseForDiskProtection(remaining(deadline));
        while (executors.stream().anyMatch(pool -> !pool.isTerminated())
                || uploads.stream().anyMatch(thread -> thread != null && thread.isAlive())
                || !context.getBean(PartPreviewService.class).isStopped()
                || !context.getBean(BackupService.class).isStopped() || !ProtectionRequests.isIdle()
                || processes.stream().anyMatch(ProcessHandle::isAlive)) {
            if (System.nanoTime() >= deadline) throw new IllegalStateException("仍有后台任务未退出，数据库未强行关闭，保护继续生效");
            Thread.sleep(25);
        }
        if (!(dataSource instanceof MaintenanceDataSource source)) throw new IllegalStateException("当前连接池不支持安全关闭");
        guard.stage("正在正常关闭数据库");
        source.shutdownForDiskProtection();
        guard.paused(saved);
        context.close();
    }

    private static long remaining(long deadline) { return Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())); }
}
