package top.sshh.bililiverecoder.protection;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

public final class DiskSpaceGuard implements AutoCloseable {
    public enum Phase { RUNNING, QUIESCING, PAUSED, FAILED, RECOVERING }
    private static volatile DiskSpaceGuard current;
    private final DiskSpaceSettings settings;
    private final DiskSpaceProbe probe;
    private final DiskProtectionMarker marker;
    private final LongSupplier clock;
    private final ExecutorService probes = Executors.newSingleThreadExecutor(r -> daemon(r, "disk-space-probe"));
    private final ExecutorService stateIo = Executors.newSingleThreadExecutor(r -> daemon(r, "disk-protection-state"));
    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "disk-space-monitor"));
    private volatile Future<List<DiskSpaceProbe.Volume>> pendingProbe;
    private volatile Phase phase = Phase.RUNNING;
    private volatile List<DiskSpaceProbe.Volume> volumes = List.of();
    private volatile String message = "运行正常";
    private volatile String error = "";
    private volatile String startedAt = "";
    private volatile boolean databaseClosed;
    private volatile long healthySince = -1;
    private volatile Runnable stopAction;
    private volatile Runnable resumeAction;
    private volatile java.util.function.Consumer<String> notifier = ignored -> {};
    private boolean stopDispatched;
    private boolean readyNotified;
    private boolean resuming;
    private volatile boolean closed;
    private volatile long lastSample = -1;
    private final java.util.concurrent.locks.ReentrantLock probeLock = new java.util.concurrent.locks.ReentrantLock();
    private final String csrfToken = UUID.randomUUID().toString();

    public DiskSpaceGuard(DiskSpaceSettings settings, DiskSpaceProbe probe, DiskProtectionMarker marker,
                          LongSupplier clock) {
        settings.validate();
        this.settings = settings; this.probe = probe; this.marker = marker; this.clock = clock;
    }

    public static DiskSpaceGuard current() { return current; }
    static void install(DiskSpaceGuard guard) { current = guard; }
    public static boolean blocksBusiness() { return current != null && current.phase != Phase.RUNNING; }
    public static boolean blocksConnections() {
        return current != null && current.phase != Phase.RUNNING && current.phase != Phase.RECOVERING;
    }
    public DiskSpaceSettings settings() { return settings; }
    public Phase phase() { return phase; }
    public String csrfToken() { return csrfToken; }

    public void initialize() {
        try {
            if (io(() -> marker.exists())) protect("上次已进入磁盘保护，请检查空间后手动恢复");
        } catch (Exception failure) { protect("无法读取磁盘保护状态：" + detail(failure)); }
        poll();
    }

    public void startMonitor() {
        monitor.scheduleWithFixedDelay(this::poll, settings.intervalMillis(), settings.intervalMillis(), TimeUnit.MILLISECONDS);
    }

    public synchronized void attach(Runnable stop, Runnable resume, java.util.function.Consumer<String> notifications) {
        stopAction = stop; resumeAction = resume;
        if (notifications != null) notifier = notifications;
        dispatchStop();
    }

    public synchronized void detach() { stopAction = null; resumeAction = null; stopDispatched = false; }
    public synchronized void suspendResume() { resumeAction = null; }

    public void poll() {
        if (closed || !probeLock.tryLock()) return;
        try {
            Future<List<DiskSpaceProbe.Volume>> pending = pendingProbe;
            if (pending == null) pendingProbe = pending = probes.submit(() -> probe.collect(settings.paths()));
            List<DiskSpaceProbe.Volume> latest = pending.get(settings.probeTimeoutMillis(), TimeUnit.MILLISECONDS);
            pendingProbe = null;
            if (latest.isEmpty()) throw new IllegalStateException("未获取到受保护磁盘容量");
            volumes = latest;
            if (error.startsWith("空间检测失败：")) error = "";
            lastSample = clock.getAsLong();
            boolean low = latest.stream().anyMatch(v -> v.low(settings));
            if (low) protect("磁盘使用率达到保护线或可用空间不足");
            synchronized (this) {
                boolean healthy = latest.stream().allMatch(v -> v.recovered(settings));
                if (healthy) {
                    if (healthySince < 0) healthySince = clock.getAsLong();
                } else { healthySince = -1; readyNotified = false; }
                if (phase != Phase.RUNNING && canResume() && !readyNotified) {
                    readyNotified = true;
                    notifyChange("空间已连续满足恢复条件，可以手动恢复工作");
                }
            }
        } catch (Exception failure) {
            if (!(failure instanceof TimeoutException)) pendingProbe = null;
            healthySince = -1; readyNotified = false;
            error = "空间检测失败：" + detail(failure);
            protect("无法可靠检测磁盘空间：" + detail(failure));
        } finally { probeLock.unlock(); }
    }

    public synchronized void protect(String reason) {
        if (phase == Phase.RUNNING || phase == Phase.RECOVERING) {
            phase = Phase.QUIESCING; startedAt = Instant.now().toString(); message = reason;
            databaseClosed = false; stopDispatched = false;
            notifyChange("磁盘空间保护已触发：" + reason + "。请同时检查外部录制器，它仍可能继续写盘");
            dispatchStop();
        }
    }

    private void dispatchStop() {
        if (phase == Phase.QUIESCING && stopAction != null && !stopDispatched) {
            stopDispatched = true;
            stopAction.run();
        }
    }

    public boolean saveProtection() {
        try { io(() -> { marker.write(); return true; }); return true; }
        catch (Exception failure) { error = "保护状态写入失败：" + detail(failure); return false; }
    }

    public synchronized void paused(boolean stateSaved) {
        databaseClosed = true;
        if (stateSaved) { phase = Phase.PAUSED; message = "数据库已关闭，业务已暂停"; error = ""; }
        else { phase = Phase.FAILED; message = "数据库已关闭，但保护状态未能可靠保存，请释放空间后重新检查"; }
    }

    public synchronized void failed(Throwable failure) {
        resuming = false;
        phase = Phase.FAILED; message = "保护收尾未全部完成，业务入口保持关闭";
        error = detail(failure);
        System.err.println("磁盘保护异常：" + error);
        failure.printStackTrace(System.err);
        notifyChange("磁盘保护异常：" + error);
    }

    public synchronized void stage(String text) { message = text; }
    public synchronized boolean canResume() {
        long now = clock.getAsLong();
        return healthySince >= 0 && lastSample >= 0 && now - lastSample <= settings.intervalMillis() + settings.probeTimeoutMillis()
                && now - healthySince >= settings.stableMillis();
    }

    public boolean requestResume() {
        if (!refreshForRecovery()) return false;
        Runnable action;
        synchronized (this) {
            if (resuming || (phase != Phase.PAUSED && phase != Phase.FAILED) || !canResume() || resumeAction == null) return false;
            resuming = true;
            phase = Phase.QUIESCING; message = "正在重新检查收尾状态";
            action = resumeAction;
        }
        action.run();
        return true;
    }

    public void beginRecovery() throws Exception {
        if (!refreshForRecovery()) throw new IllegalStateException("暂时无法完成新的磁盘检查，请稍后重试");
        synchronized (this) {
            if (!databaseClosed || !canResume()) throw new IllegalStateException("数据库尚未确认关闭，或磁盘尚未满足恢复条件");
        }
        // 恢复期间保留标记，启动失败或进程退出后仍须重新确认
        io(() -> { marker.write(); return true; });
        synchronized (this) { phase = Phase.RECOVERING; message = "正在恢复业务核心"; error = ""; }
    }

    public void applicationReady() throws Exception {
        boolean recovered = !startedAt.isBlank();
        if (!refreshForRecovery()) throw new IllegalStateException("业务开放前无法完成磁盘检查");
        synchronized (this) {
            if (phase != Phase.RUNNING && phase != Phase.RECOVERING) throw new IllegalStateException(message);
        }
        io(() -> { marker.clear(); return true; });
        synchronized (this) {
            if (phase != Phase.RUNNING && phase != Phase.RECOVERING) throw new IllegalStateException(message);
            phase = Phase.RUNNING; message = "运行正常"; databaseClosed = false; error = "";
            stopDispatched = false;
            resuming = false;
        }
        if (recovered) notifyChange("磁盘保护检查完成，业务已恢复运行");
    }

    public synchronized Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("diskProtection", phase != Phase.RUNNING); result.put("phase", phase.name());
        result.put("message", message); result.put("error", error); result.put("startedAt", startedAt);
        result.put("databaseClosed", databaseClosed); result.put("canResume", canResume());
        result.put("stableSeconds", healthySince < 0 ? 0 : Math.max(0, clock.getAsLong() - healthySince) / 1000);
        result.put("requiredStableSeconds", settings.stableMillis() / 1000);
        result.put("stopPercent", settings.stopPercent()); result.put("stopBytes", settings.stopBytes());
        result.put("resumePercent", settings.resumePercent()); result.put("resumeBytes", settings.resumeBytes());
        result.put("volumes", volumes.stream().map(DiskSpaceProbe.Volume::toMap).toList());
        return result;
    }

    private <T> T io(Callable<T> task) throws Exception {
        return stateIo.submit(task).get(settings.probeTimeoutMillis(), TimeUnit.MILLISECONDS);
    }

    private boolean refreshForRecovery() {
        boolean locked = false;
        try {
            locked = probeLock.tryLock(settings.probeTimeoutMillis(), TimeUnit.MILLISECONDS);
            if (!locked) return false;
            poll();
            return lastSample >= 0 && clock.getAsLong() - lastSample <= settings.probeTimeoutMillis();
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        finally { if (locked) probeLock.unlock(); }
    }
    private void notifyChange(String text) {
        try { notifier.accept(text); } catch (RuntimeException ignored) { }
    }
    public static Thread daemon(Runnable task, String name) { Thread thread = new Thread(task, name); thread.setDaemon(true); return thread; }
    public static String detail(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause != cause.getCause()) cause = cause.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
    @Override public void close() {
        closed = true; monitor.shutdownNow(); probes.shutdownNow(); stateIo.shutdownNow();
        if (current == this) current = null;
    }
}
