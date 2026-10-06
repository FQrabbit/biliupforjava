package top.sshh.bililiverecoder.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class DatabaseMaintenanceState {

    private final AtomicBoolean maintenanceActive = new AtomicBoolean(false);
    private final Map<Thread, Integer> borrowers = new HashMap<>();
    private Thread maintenanceOwner;
    private boolean databasePaused;
    private boolean stopped;

    // 已经借到连接的线程可以继续借连接，让已有事务正常收尾
    public synchronized Thread acquireConnection() throws SQLException {
        Thread caller = Thread.currentThread();
        while (databasePaused && caller != maintenanceOwner && !borrowers.containsKey(caller)) {
            if (stopped) throw new SQLException("数据库连接池已停止");
            try {
                wait();
            } catch (InterruptedException error) {
                caller.interrupt();
                throw new SQLException("等待数据库维护结束时被中断", error);
            }
        }
        if (stopped) throw new SQLException("数据库连接池已停止");
        borrowers.merge(caller, 1, Integer::sum);
        return caller;
    }

    public synchronized void releaseConnection(Thread borrower) {
        borrowers.computeIfPresent(borrower, (thread, count) -> count == 1 ? null : count - 1);
        notifyAll();
    }

    public synchronized void pauseAndDrain(long timeoutMillis) throws InterruptedException {
        if (databasePaused) throw new IllegalStateException("数据库已经暂停，不能重复开始维护");
        maintenanceOwner = Thread.currentThread();
        if (borrowers.containsKey(maintenanceOwner)) {
            maintenanceOwner = null;
            throw new IllegalStateException("维护线程仍持有业务连接，不能关闭数据库");
        }
        databasePaused = true;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
        try {
            while (!borrowers.isEmpty()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IllegalStateException("等待已有数据库操作结束超时，本次压缩已取消");
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
        } catch (InterruptedException | RuntimeException error) {
            resumeDatabase();
            throw error;
        }
    }

    public synchronized void requireMaintenanceOwner() {
        if (stopped || !databasePaused || maintenanceOwner != Thread.currentThread() || !borrowers.isEmpty()) {
            throw new IllegalStateException("数据库连接尚未全部归还，不能替换数据库");
        }
    }

    public synchronized void resumeDatabase() {
        databasePaused = false;
        maintenanceOwner = null;
        notifyAll();
    }

    public synchronized boolean isDatabasePaused() {
        return databasePaused;
    }

    public synchronized void retainDatabasePause() {
        // 维护线程会回到线程池，不能让它下次执行别的任务时绕过暂停
        maintenanceOwner = null;
        databasePaused = true;
    }

    public synchronized int activeConnectionCount() {
        return borrowers.values().stream().mapToInt(Integer::intValue).sum();
    }

    public synchronized void stop() {
        stopped = true;
        notifyAll();
    }

    public boolean isMaintenanceActive() {
        return maintenanceActive.get();
    }

    public boolean tryBeginMaintenance() {
        return maintenanceActive.compareAndSet(false, true);
    }

    public void setMaintenanceActive(boolean active) {
        maintenanceActive.set(active);
    }
}
