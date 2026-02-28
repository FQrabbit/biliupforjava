package top.sshh.bililiverecoder.lifecycle;

import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

import top.sshh.bililiverecoder.util.LogKvs;
import top.sshh.bililiverecoder.util.TaskUtil;

@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class ShutdownCoordinator implements ApplicationListener<ContextClosedEvent> {

    private final ShutdownState shutdownState;

    private final long waitMillis;

    public ShutdownCoordinator(
            ShutdownState shutdownState,
            @Value("${record.shutdown.wait-millis:3000}") long waitMillis
    ) {
        this.shutdownState = shutdownState;
        this.waitMillis = Math.max(0L, waitMillis);
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        if (!shutdownState.markShuttingDown()) {
            return;
        }

        log.info("[BLR] {}", LogKvs.event("Shutdown.Begin"));

        Set<Thread> threads = new HashSet<>();
        threads.addAll(TaskUtil.partUploadTask.values());
        threads.addAll(TaskUtil.publishTask.values());

        for (Thread t : threads) {
            if (t == null) {
                continue;
            }
            try {
                t.interrupt();
            } catch (Exception ignored) {
            }
        }

        if (waitMillis <= 0 || threads.isEmpty()) {
            log.info("[BLR] {}", LogKvs.event("Shutdown.End").add("threads", threads.size()).add("waitMillis", waitMillis));
            return;
        }

        long deadline = System.currentTimeMillis() + waitMillis;
        for (Thread t : threads) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                break;
            }
            if (t == null || !t.isAlive()) {
                continue;
            }
            try {
                t.join(Math.min(remaining, 500L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ignored) {
            }
        }

        log.info("[BLR] {}", LogKvs.event("Shutdown.End").add("threads", threads.size()).add("waitMillis", waitMillis));
    }

    @PreDestroy
    public void printExitOk() {
        if (!shutdownState.isShuttingDown()) {
            return;
        }
        System.out.println();
        System.out.println("############################################################");
        System.out.println("#                                                          #");
        System.out.println("#                        EXIT  OK                          #");
        System.out.println("#                SAFE SHUTDOWN COMPLETED                   #");
        System.out.println("#                                                          #");
        System.out.println("############################################################");
        System.out.println();
        try {
            System.out.flush();
        } catch (Exception ignored) {
        }
    }
}
