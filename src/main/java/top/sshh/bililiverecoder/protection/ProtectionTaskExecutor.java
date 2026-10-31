package top.sshh.bililiverecoder.protection;

import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

public class ProtectionTaskExecutor extends ThreadPoolTaskExecutor {
    public ProtectionTaskExecutor() {
        setTaskDecorator(task -> () -> {
            // 核心恢复时先完成同步校验，再让已排队的后台任务继续
            try {
                while (DiskSpaceGuard.current() != null && DiskSpaceGuard.current().phase() == DiskSpaceGuard.Phase.RECOVERING) {
                    Thread.sleep(25);
                }
                if (!DiskSpaceGuard.blocksBusiness()) task.run();
                else if (task instanceof Future<?> future) future.cancel(false);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                if (task instanceof Future<?> future) future.cancel(false);
            }
        });
    }
    private void requireAdmission() {
        if (DiskSpaceGuard.blocksConnections()) throw new TaskRejectedException("磁盘空间保护中，后台任务未接收");
    }
    @Override public void execute(Runnable task) { requireAdmission(); super.execute(task); }
    @Override public Future<?> submit(Runnable task) { requireAdmission(); return super.submit(task); }
    @Override public <T> Future<T> submit(Callable<T> task) { requireAdmission(); return super.submit(task); }
}
