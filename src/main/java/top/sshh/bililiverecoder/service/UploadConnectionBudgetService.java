package top.sshh.bililiverecoder.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.util.LogKvs;

import java.util.concurrent.Semaphore;

@Slf4j
@Service
public class UploadConnectionBudgetService {

    private static volatile UploadConnectionBudgetService instance;

    public static final int DEFAULT_MAX_CONNECTIONS = 3;
    public static final int MIN_MAX_CONNECTIONS = 1;
    public static final int MAX_MAX_CONNECTIONS = 16;

    private volatile int maxConnections = DEFAULT_MAX_CONNECTIONS;
    private volatile Semaphore connectionSemaphore;

    @PostConstruct
    public void init() {
        instance = this;
        connectionSemaphore = new Semaphore(maxConnections, true);
        log.info("[BLR] {}", LogKvs.event("UploadConnectionBudget.Init")
                .add("maxConnections", maxConnections));
    }

    public static UploadConnectionBudgetService getInstance() {
        return instance;
    }

    public void acquire() throws InterruptedException {
        connectionSemaphore.acquire();
    }

    public void release() {
        connectionSemaphore.release();
    }

    public int getAvailablePermits() {
        return connectionSemaphore.availablePermits();
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public void updateMaxConnections(int newMax) {
        newMax = Math.max(MIN_MAX_CONNECTIONS, Math.min(MAX_MAX_CONNECTIONS, newMax));
        int oldMax = this.maxConnections;
        if (oldMax == newMax) {
            return;
        }
        this.maxConnections = newMax;
        this.connectionSemaphore = new Semaphore(newMax, true);
        log.info("[BLR] {}", LogKvs.event("UploadConnectionBudget.Update")
                .add("oldMax", oldMax)
                .add("newMax", newMax));
    }
}
