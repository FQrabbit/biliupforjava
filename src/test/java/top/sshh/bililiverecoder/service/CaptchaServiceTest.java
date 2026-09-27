package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CaptchaServiceTest {
    @Test
    void concurrentRequestsForOneTaskStageReuseOneChallenge() throws Exception {
        CaptchaService service = new CaptchaService();
        CountDownLatch start = new CountDownLatch(1);
        Set<String> requestIds = ConcurrentHashMap.newKeySet();
        CompletableFuture<?>[] requests = new CompletableFuture<?>[12];
        for (int i = 0; i < requests.length; i++) {
            requests[i] = CompletableFuture.runAsync(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                requestIds.add(service.deferPublish("voucher", "video", Map.of(),
                        10L, 22L, 31L, "FINAL_SUBMIT", ignored -> {}));
            });
        }
        start.countDown();
        CompletableFuture.allOf(requests).get(2, TimeUnit.SECONDS);

        assertEquals(1, requestIds.size());
        assertEquals(1, service.pendingChallenges().size());
    }

    @Test
    void deferredPublishChallengeResumesWithoutWaitingThread() {
        CaptchaService service = new CaptchaService();
        AtomicReference<Map<String, String>> resumed = new AtomicReference<>();
        String requestId = service.deferPublish("voucher", "video", Map.of(), 10L, 22L, resumed::set);
        assertTrue(service.hasPendingForAccount(10L));
        assertFalse(service.hasPendingForAccount(20L));
        assertTrue(service.submitCaptcha(requestId, Map.of("token", "ok")));
        assertEquals("ok", resumed.get().get("token"));
        assertFalse(service.hasPendingForAccount(10L));
    }

    @Test
    void callbackObservesConsumedStateAndCallbackFailureKeepsAnswerRetryable() {
        CaptchaService service = new CaptchaService();
        AtomicReference<String> requestId = new AtomicReference<>();
        AtomicReference<String> observedState = new AtomicReference<>();
        String completedId = service.deferPublish("voucher", "video", Map.of(), 10L, 22L, 31L,
                "FINAL_SUBMIT", ignored -> observedState.set(service.challengeStatus(requestId.get()).state()));
        requestId.set(completedId);

        assertTrue(service.submitCaptcha(completedId, Map.of("token", "ok")));
        assertEquals("CONSUMED", observedState.get());
        assertFalse(service.hasPendingForAccount(10L));

        AtomicReference<Map<String, String>> recovered = new AtomicReference<>();
        AtomicReference<Boolean> fail = new AtomicReference<>(true);
        String retryableId = service.deferPublish("voucher2", "video2", Map.of(), 11L, 23L, result -> {
            if (fail.getAndSet(false)) throw new IllegalStateException("temporary callback error");
            recovered.set(result);
        });
        assertFalse(service.submitCaptcha(retryableId, Map.of("token", "recover")));
        assertEquals("SUBMITTED", service.challengeStatus(retryableId).state());
        assertTrue(service.submitCaptcha(retryableId, Map.of("token", "recover")));
        assertEquals("recover", recovered.get().get("token"));
    }

    @Test
    void simultaneousChallengesCompleteIndependently() throws Exception {
        CaptchaService service = new CaptchaService();
        CountDownLatch registered = new CountDownLatch(2);
        CompletableFuture<Map<String, String>> a = CompletableFuture.supplyAsync(() -> {
            service.setCaptchaRequired("voucher-a", "A", Map.of());
            registered.countDown();
            return service.waitForCaptcha();
        });
        CompletableFuture<Map<String, String>> b = CompletableFuture.supplyAsync(() -> {
            service.setCaptchaRequired("voucher-b", "B", Map.of());
            registered.countDown();
            return service.waitForCaptcha();
        });
        assertTrue(registered.await(2, TimeUnit.SECONDS));
        assertEquals(2, service.pendingChallenges().size());
        assertFalse(service.submitCaptcha(Map.of("token", "ambiguous")));
        for (CaptchaService.ChallengeStatus challenge : service.pendingChallenges()) {
            assertTrue(service.submitCaptcha(challenge.requestId(), Map.of("token", challenge.filename())));
        }
        assertEquals("A", a.get(2, TimeUnit.SECONDS).get("token"));
        assertEquals("B", b.get(2, TimeUnit.SECONDS).get("token"));
        assertTrue(service.pendingChallenges().isEmpty());
    }
}
