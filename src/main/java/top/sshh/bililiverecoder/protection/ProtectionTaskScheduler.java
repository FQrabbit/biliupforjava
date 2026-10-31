package top.sshh.bililiverecoder.protection;

import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

public class ProtectionTaskScheduler extends ThreadPoolTaskScheduler {
    private Runnable guarded(Runnable task) {
        return () -> { if (!DiskSpaceGuard.blocksBusiness()) task.run(); };
    }
    @Override public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) { return super.schedule(guarded(task), trigger); }
    @Override public ScheduledFuture<?> schedule(Runnable task, Instant time) { return super.schedule(guarded(task), time); }
    @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant start, Duration period) { return super.scheduleAtFixedRate(guarded(task), start, period); }
    @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) { return super.scheduleAtFixedRate(guarded(task), period); }
    @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant start, Duration delay) { return super.scheduleWithFixedDelay(guarded(task), start, delay); }
    @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) { return super.scheduleWithFixedDelay(guarded(task), delay); }
}
