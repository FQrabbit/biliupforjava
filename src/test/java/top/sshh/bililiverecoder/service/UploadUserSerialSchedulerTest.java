package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import top.sshh.bililiverecoder.entity.PublishTask;
import top.sshh.bililiverecoder.entity.PublishTaskState;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class UploadUserSerialSchedulerTest {
    @Test
    void currentUploadCanRunItsOwnPostUploadFileOperation() {
        ObjectProvider<UploadPauseService> pauseServiceProvider = new DefaultListableBeanFactory().getBeanProvider(UploadPauseService.class);
        UploadUserSerialScheduler scheduler = new UploadUserSerialScheduler(Runnable::run, pauseServiceProvider);

        assertTrue(scheduler.submitIfPartNotPending(3L, "room", 11L, 21L, "test", () -> {
            assertTrue(scheduler.isCurrentPartExecution(21L));
            assertFalse(scheduler.hasPendingPartOutsideCurrentExecution(21L));
            assertTrue(scheduler.hasPendingHistory(11L));
        }));

        assertFalse(scheduler.isCurrentPartExecution(21L));
        assertFalse(scheduler.hasPendingHistory(11L));
    }

    @Test
    void captchaReleasesUploadSlotAndResumesInTheSameAccountQueue() {
        ObjectProvider<UploadPauseService> pauseServiceProvider = new DefaultListableBeanFactory().getBeanProvider(UploadPauseService.class);
        UploadUserSerialScheduler scheduler = new UploadUserSerialScheduler(Runnable::run, pauseServiceProvider);
        CaptchaService captchaService = new CaptchaService();
        ReflectionTestUtils.setField(scheduler, "captchaService", captchaService);
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<String> requestId = new AtomicReference<>();
        AtomicReference<String> consumedAnswer = new AtomicReference<>();
        CountDownLatch resumed = new CountDownLatch(1);

        assertTrue(scheduler.submitIfPartNotPending(10L, "room-a", 22L, 31L, "test", () -> {
            if (attempts.incrementAndGet() == 1) {
                requestId.set(captchaService.setCaptchaRequiredForPart("voucher", "part.mp4", Map.of(),
                        10L, 22L, null, 31L, "PART_UPLOAD"));
                throw new CaptchaChallengeRequiredException(requestId.get());
            }
            consumedAnswer.set(captchaService.consumeSubmittedAnswer(10L, 22L, null, 31L, "PART_UPLOAD").get("token"));
            resumed.countDown();
        }));

        assertEquals(1, attempts.get());
        assertFalse(scheduler.hasPendingPart(31L));
        AtomicInteger otherAccountRuns = new AtomicInteger();
        assertTrue(scheduler.submitIfPartNotPending(20L, "room-b", 44L, 55L, "test", otherAccountRuns::incrementAndGet));
        assertEquals(1, otherAccountRuns.get());

        assertTrue(captchaService.submitCaptcha(requestId.get(), Map.of("token", "verified")));
        try {
            assertTrue(resumed.await(3, TimeUnit.SECONDS));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
        assertEquals(2, attempts.get());
        assertEquals("verified", consumedAnswer.get());
        long releaseDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (scheduler.hasPendingPart(31L) && System.nanoTime() < releaseDeadline) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError(error);
            }
        }
        assertFalse(scheduler.hasPendingPart(31L));
    }

    @Test
    void cancellingFixedUploadWaitForCaptchaReleasesHistoryUploadAdmission() {
        ObjectProvider<UploadPauseService> pauseServiceProvider = new DefaultListableBeanFactory().getBeanProvider(UploadPauseService.class);
        UploadUserSerialScheduler scheduler = new UploadUserSerialScheduler(Runnable::run, pauseServiceProvider);
        CaptchaService captchaService = new CaptchaService();
        PublishTask task = new PublishTask();
        task.setId(41L);
        task.setState(PublishTaskState.PREPARING);
        PublishTaskService taskService = mock(PublishTaskService.class);
        when(taskService.get(41L)).thenReturn(task);
        when(taskService.transition(anyLong(), any(), any(PublishTaskState.class), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Set<PublishTaskState> expected = invocation.getArgument(1);
                    if (!expected.contains(task.getState())) return null;
                    task.setState(invocation.getArgument(2));
                    return task;
                });
        HistoryUploadAdmissionService admission = mock(HistoryUploadAdmissionService.class);
        when(admission.tryRegisterHistoryUpload(22L)).thenReturn(true);
        ReflectionTestUtils.setField(scheduler, "captchaService", captchaService);
        ReflectionTestUtils.setField(scheduler, "publishTaskService", taskService);
        ReflectionTestUtils.setField(scheduler, "historyUploadAdmissionService", admission);
        String requestId = captchaService.setCaptchaRequired("voucher", "clip.mp4", Map.of(),
                10L, 22L, 41L, "HIGH_ENERGY_UPLOAD_CAPTCHA");

        var upload = scheduler.submitForFixedAccount(10L, 22L, 41L, "HIGH_ENERGY_UPLOAD_CAPTCHA",
                () -> { throw new CaptchaChallengeRequiredException(requestId); });

        assertTrue(scheduler.hasPendingHistory(22L));
        assertEquals(PublishTaskState.WAITING_CAPTCHA, task.getState());
        captchaService.cancelForTask(41L);
        assertTrue(scheduler.cancelWaitingFixedUpload(41L));

        assertTrue(upload.isCompletedExceptionally());
        assertFalse(scheduler.hasPendingHistory(22L));
        assertFalse(captchaService.submitCaptcha(requestId, Map.of("token", "late")));
    }

    @Test
    void cancellingQueuedFixedUploadSkipsItAndReleasesHistoryAdmission() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ObjectProvider<UploadPauseService> pauseServiceProvider = new DefaultListableBeanFactory().getBeanProvider(UploadPauseService.class);
            UploadUserSerialScheduler scheduler = new UploadUserSerialScheduler(executor, pauseServiceProvider);
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch finishFirst = new CountDownLatch(1);
            var first = scheduler.submitForFixedAccount(10L, () -> {
                firstStarted.countDown();
                finishFirst.await();
                return "first";
            });
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

            PublishTask task = new PublishTask();
            task.setId(42L);
            task.setState(PublishTaskState.PREPARING);
            PublishTaskService taskService = mock(PublishTaskService.class);
            when(taskService.get(42L)).thenReturn(task);
            HistoryUploadAdmissionService admission = mock(HistoryUploadAdmissionService.class);
            when(admission.tryRegisterHistoryUpload(23L)).thenReturn(true);
            ReflectionTestUtils.setField(scheduler, "publishTaskService", taskService);
            ReflectionTestUtils.setField(scheduler, "historyUploadAdmissionService", admission);
            AtomicInteger queuedUploadRuns = new AtomicInteger();
            var queued = scheduler.submitForFixedAccount(10L, 23L, 42L, "HIGH_ENERGY_UPLOAD_CAPTCHA",
                    queuedUploadRuns::incrementAndGet);

            assertTrue(scheduler.hasPendingHistory(23L));
            assertTrue(scheduler.cancelWaitingFixedUpload(42L));
            assertTrue(queued.isCompletedExceptionally());
            assertFalse(scheduler.hasPendingHistory(23L));

            finishFirst.countDown();
            first.get(2, TimeUnit.SECONDS);
            var barrier = scheduler.submitForFixedAccount(10L, () -> "done");
            assertEquals("done", barrier.get(2, TimeUnit.SECONDS));
            assertEquals(0, queuedUploadRuns.get());
        } finally {
            executor.shutdownNow();
        }
    }
}
