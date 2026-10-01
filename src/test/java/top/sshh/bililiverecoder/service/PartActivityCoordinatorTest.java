package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class PartActivityCoordinatorTest {
    @Test
    void uploadAdmissionAndFileOperationsSharePartLease() {
        PartActivityCoordinator coordinator = new PartActivityCoordinator();
        assertTrue(coordinator.tryRegisterUpload(12L));
        assertNull(coordinator.tryBeginFileOperation(12L));

        try (PartActivityCoordinator.UploadScope ignored = coordinator.enterUpload(12L);
             PartActivityCoordinator.OperationLease ownCleanup = coordinator.tryBeginFileOperation(12L)) {
            assertNotNull(ownCleanup);
            assertFalse(coordinator.tryRegisterUpload(12L));
        }

        coordinator.releaseUpload(12L);
        try (PartActivityCoordinator.OperationLease operation = coordinator.tryBeginFileOperation(12L)) {
            assertNotNull(operation);
            assertFalse(coordinator.tryRegisterUpload(12L));
        }
        assertTrue(coordinator.tryRegisterUpload(12L));
        coordinator.releaseUpload(12L);
    }

    @Test
    void admissionAndReleaseRacesNeverAllowAnOperationBesideAnUpload() throws Exception {
        PartActivityCoordinator coordinator = new PartActivityCoordinator();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int attempt = 0; attempt < 500; attempt++) {
                assertTrue(coordinator.tryRegisterUpload(99L));
                CountDownLatch start = new CountDownLatch(1);
                AtomicBoolean operationOverlappedUpload = new AtomicBoolean();
                var register = pool.submit(() -> {
                    await(start);
                    boolean accepted = coordinator.tryRegisterUpload(99L);
                    if (accepted) {
                        try {
                            try (PartActivityCoordinator.OperationLease lease = coordinator.tryBeginFileOperation(99L)) {
                                if (lease != null) operationOverlappedUpload.set(true);
                            }
                        } finally {
                            coordinator.releaseUpload(99L);
                        }
                    }
                });
                var release = pool.submit(() -> {
                    await(start);
                    coordinator.releaseUpload(99L);
                });
                start.countDown();
                register.get(2, TimeUnit.SECONDS);
                release.get(2, TimeUnit.SECONDS);
                assertFalse(operationOverlappedUpload.get());
                coordinator.releaseUpload(99L);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }
}
