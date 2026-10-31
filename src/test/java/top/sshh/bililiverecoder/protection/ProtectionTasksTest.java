package top.sshh.bililiverecoder.protection;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.task.TaskRejectedException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ProtectionTasksTest {
    @TempDir Path directory;
    DiskSpaceGuard guard;
    AtomicLong clock = new AtomicLong();
    @BeforeEach void setup() {
        var settings = DiskSpaceGuardTest.settings(directory);
        guard = new DiskSpaceGuard(settings, new DiskSpaceProbe() {
            @Override public List<Volume> collect(List<Path> paths) {
                return List.of(new Volume("work", 100 * DiskSpaceSettings.GIB, 20 * DiskSpaceSettings.GIB, 80));
            }
        }, new DiskProtectionMarker(settings.marker()), clock::get);
        DiskSpaceGuard.install(guard); guard.initialize();
    }
    @AfterEach void cleanup() { guard.close(); }

    @Test void queuedTasksAreCancelledAndNewSubmissionsRejectedAfterProtection() throws Exception {
        ProtectionTaskExecutor executor = new ProtectionTaskExecutor(); executor.setCorePoolSize(1); executor.setMaxPoolSize(1); executor.initialize();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            var active = executor.submit(() -> { entered.countDown(); release.await(); return true; });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var queued = executor.submit(() -> fail("保护后不应开始队列中的业务"));
            guard.protect("low");
            assertThrows(TaskRejectedException.class, () -> executor.submit(() -> {}));
            release.countDown(); assertTrue(active.get(2, TimeUnit.SECONDS));
            assertThrows(CancellationException.class, () -> queued.get(2, TimeUnit.SECONDS));
        } finally { release.countDown(); executor.shutdown(); }
    }

    @Test void recoveryQueuesWaitUntilApplicationChecksFinish() throws Exception {
        guard.protect("low"); guard.paused(true); clock.set(30000); guard.poll(); guard.beginRecovery();
        ProtectionTaskExecutor executor = new ProtectionTaskExecutor(); executor.initialize();
        try {
            AtomicInteger ran = new AtomicInteger(); var queued = executor.submit(ran::incrementAndGet);
            assertThrows(TimeoutException.class, () -> queued.get(100, TimeUnit.MILLISECONDS)); assertEquals(0, ran.get());
            guard.applicationReady(); assertEquals(1, queued.get(2, TimeUnit.SECONDS));
        } finally { executor.shutdown(); }
    }

    @Test void periodicTasksDoNotExecuteDuringProtection() throws Exception {
        ProtectionTaskScheduler scheduler = new ProtectionTaskScheduler(); scheduler.initialize();
        try {
            guard.protect("low"); AtomicInteger ran = new AtomicInteger();
            var task = scheduler.scheduleAtFixedRate(ran::incrementAndGet, java.time.Duration.ofMillis(20));
            Thread.sleep(100); assertEquals(0, ran.get()); task.cancel(false);
        } finally { scheduler.shutdown(); }
    }
}
