package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.sshh.bililiverecoder.entity.BiliBiliUser;
import top.sshh.bililiverecoder.entity.PublishTask;
import top.sshh.bililiverecoder.entity.PublishTaskOperation;
import top.sshh.bililiverecoder.entity.PublishTaskSource;
import top.sshh.bililiverecoder.entity.PublishTaskState;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.repo.BiliUserRepository;
import top.sshh.bililiverecoder.repo.PublishTaskRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublishTaskServiceTest {
    @Test
    void manualRequestIsSavedDuringMergeWhileAutomaticRequestStillWaits() {
        when(readiness.check(any(RecordHistory.class))).thenReturn(
                new PublishReadinessService.Check(false, "MERGE_INTERVAL", "等待合并", LocalDateTime.now().plusMinutes(10)));
        assertFalse(service.accept(1L, 10L, PublishTaskOperation.NEW_PUBLISH,
                PublishTaskSource.AUTOMATIC, null).isAccepted());
        var manual = service.accept(1L, 10L, PublishTaskOperation.NEW_PUBLISH, PublishTaskSource.MANUAL, null);
        assertTrue(manual.isAccepted());
        assertEquals(PublishTaskSource.MANUAL, manual.getTask().getSource());
        assertEquals(PublishTaskState.READY, manual.getTask().getState());
    }

    @Test
    void manualRequestReusesMergeWaitingTaskWithoutChangingAccountOrQueueOrder() {
        var first = service.accept(1L, 10L, PublishTaskOperation.NEW_PUBLISH, PublishTaskSource.AUTOMATIC, null);
        PublishTask original = first.getTask();
        LocalDateTime queuedAt = original.getCreatedAt();
        original.setState(PublishTaskState.WAITING_UPLOAD);
        original.setWaitReason("MERGE_INTERVAL");
        original.setNextAttemptAt(LocalDateTime.now().plusMinutes(10));
        when(readiness.check(any(RecordHistory.class))).thenReturn(
                new PublishReadinessService.Check(false, "MERGE_INTERVAL", "等待合并", original.getNextAttemptAt()));
        var manual = service.accept(1L, 10L, PublishTaskOperation.NEW_PUBLISH, PublishTaskSource.MANUAL, null);
        assertTrue(manual.isAlreadyQueued());
        assertEquals(original.getId(), manual.getTask().getId());
        assertEquals(queuedAt, manual.getTask().getCreatedAt());
        assertEquals(10L, manual.getTask().getAccountId());
        assertEquals(PublishTaskSource.MANUAL, manual.getTask().getSource());
        assertEquals(PublishTaskState.READY, manual.getTask().getState());
        assertEquals(null, manual.getTask().getNextAttemptAt());
        assertEquals(1, stored.size());
        manual.getTask().setState(PublishTaskState.WAITING_ACCOUNT);
        manual.getTask().setWaitReason("ACCOUNT_COOLDOWN");
        manual.getTask().setNextAttemptAt(LocalDateTime.now().plusMinutes(30));
        service.accept(1L, 10L, PublishTaskOperation.NEW_PUBLISH, PublishTaskSource.MANUAL, null);
        assertEquals(PublishTaskState.WAITING_ACCOUNT, manual.getTask().getState());
        assertEquals("ACCOUNT_COOLDOWN", manual.getTask().getWaitReason());
        assertTrue(manual.getTask().getNextAttemptAt().isAfter(LocalDateTime.now()));
    }

    @Test
    void newRequestDuringRecordingIsRejectedWithoutSavingTask() {
        when(readiness.check(any(RecordHistory.class))).thenReturn(
                new PublishReadinessService.Check(false, "RECORDING", "录制中，暂不能投稿", null));
        var result = service.accept(1L, 10L, PublishTaskOperation.NEW_PUBLISH, PublishTaskSource.MANUAL, null);
        assertFalse(result.isAccepted());
        assertTrue(stored.isEmpty());
        assertEquals("录制中，暂不能投稿", result.getMessage());
    }

    @Test
    void existingTaskIsReturnedEvenIfRecordingResumes() {
        var first = service.accept(1L, 10L, PublishTaskOperation.NEW_PUBLISH, PublishTaskSource.MANUAL, null);
        when(readiness.check(any(RecordHistory.class))).thenReturn(
                new PublishReadinessService.Check(false, "RECORDING", "录制中", null));
        var duplicate = service.accept(1L, 10L, PublishTaskOperation.NEW_PUBLISH, PublishTaskSource.MANUAL, null);
        assertTrue(duplicate.isAlreadyQueued());
        assertEquals(first.getTask().getId(), duplicate.getTask().getId());
        assertEquals(1, stored.size());
    }
    private final PublishTaskRepository repository = mock(PublishTaskRepository.class);
    private final RecordHistoryRepository histories = mock(RecordHistoryRepository.class);
    private final BiliUserRepository users = mock(BiliUserRepository.class);
    private final PublishReadinessService readiness = mock(PublishReadinessService.class);
    private final List<PublishTask> stored = new ArrayList<>();
    private PublishTaskService service;

    @BeforeEach
    void setUp() {
        stored.clear();
        service = new PublishTaskService(repository, histories, users, readiness);
        when(readiness.check(any(RecordHistory.class))).thenReturn(PublishReadinessService.Check.ready());
        RecordHistory history = new RecordHistory();
        history.setId(1L);
        history.setPublish(false);
        when(histories.findById(1L)).thenReturn(Optional.of(history));
        when(histories.findByIdForUpdate(1L)).thenReturn(Optional.of(history));
        BiliBiliUser account = new BiliBiliUser();
        account.setId(10L);
        account.setLogin(true);
        when(users.findById(anyLong())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            return id.equals(10L) ? Optional.of(account) : Optional.empty();
        });
        when(repository.findByHistoryIdAndStateInOrderByCreatedAtAsc(anyLong(), any()))
                .thenAnswer(invocation -> stored.stream()
                        .filter(task -> task.getHistoryId().equals(invocation.getArgument(0)))
                        .filter(task -> task.getState().isActive()).toList());
        when(repository.findById(anyLong())).thenAnswer(invocation -> stored.stream()
                .filter(task -> task.getId().equals(invocation.getArgument(0))).findFirst());
        AtomicLong ids = new AtomicLong();
        when(repository.save(any(PublishTask.class))).thenAnswer(invocation -> {
            PublishTask task = invocation.getArgument(0);
            if (task.getId() == null) task.setId(ids.incrementAndGet());
            if (!stored.contains(task)) stored.add(task);
            return task;
        });
    }

    @Test
    void repeatedRequestReusesTaskAndHighEnergyRemainsIndependent() {
        PublishTaskService.Admission first = service.accept(1L, 10L, PublishTaskOperation.NEW_PUBLISH,
                PublishTaskSource.MANUAL, null);
        PublishTaskService.Admission duplicate = service.accept(1L, 10L, PublishTaskOperation.NEW_PUBLISH,
                PublishTaskSource.MANUAL, null);
        PublishTaskService.Admission highEnergy = service.accept(1L, 10L, PublishTaskOperation.HIGH_ENERGY,
                PublishTaskSource.MANUAL, null);

        assertTrue(first.isAccepted());
        assertFalse(first.isAlreadyQueued());
        assertTrue(duplicate.isAccepted());
        assertTrue(duplicate.isAlreadyQueued());
        assertEquals(first.getTask().getId(), duplicate.getTask().getId());
        assertTrue(highEnergy.isAccepted());
        assertEquals(2, stored.size());
    }

    @Test
    void changedEditPayloadIsRejectedWithoutReplacingAcceptedSnapshot() {
        Map<String, Object> original = Map.of("sessionId", "session-a", "items", List.of(Map.of("title", "A")));
        PublishTaskService.Admission first = service.accept(1L, 10L, PublishTaskOperation.EDIT_PARTS,
                PublishTaskSource.MANUAL, original);
        PublishTaskService.Admission conflict = service.accept(1L, 10L, PublishTaskOperation.EDIT_PARTS,
                PublishTaskSource.MANUAL,
                Map.of("sessionId", "session-b", "items", List.of(Map.of("title", "B"))));

        assertTrue(first.isAccepted());
        assertFalse(conflict.isAccepted());
        assertEquals(1, stored.size());
        assertTrue(stored.get(0).getRequestSnapshot().contains("session-a"));
        assertEquals(PublishTaskState.READY, stored.get(0).getState());
    }

    @Test
    void publishedHistoryRejectsDifferentAccount() {
        RecordHistory history = histories.findById(1L).orElseThrow();
        history.setPublish(true);
        history.setPublishUserId(99L);

        PublishTaskService.Admission result = service.accept(1L, 10L, PublishTaskOperation.EDIT_PARTS,
                PublishTaskSource.MANUAL, Map.of("items", List.of()));

        assertFalse(result.isAccepted());
        assertTrue(result.getMessage().contains("原投稿账号"));
        assertTrue(stored.isEmpty());
    }

    @Test
    void uploadAccountStaysBoundToAcceptedTaskOrOriginalPublishedAccount() {
        PublishTask accepted = new PublishTask();
        accepted.setId(500L);
        accepted.setHistoryId(1L);
        accepted.setAccountId(10L);
        accepted.setOperation(PublishTaskOperation.NEW_PUBLISH);
        accepted.setSource(PublishTaskSource.MANUAL);
        accepted.setState(PublishTaskState.READY);
        stored.add(accepted);

        assertEquals(10L, service.resolveUploadAccountId(1L, 99L));

        RecordHistory history = histories.findById(1L).orElseThrow();
        history.setPublish(true);
        history.setPublishUserId(77L);
        assertEquals(77L, service.resolveUploadAccountId(1L, 99L));

        stored.clear();
        history.setPublishUserId(null);
        assertEquals(null, service.resolveUploadAccountId(1L, 99L));
    }

    @Test
    void expiredCaptchaSchedulesPersistedProbeAndExhaustionBlocksTheAccount() {
        BiliBiliUser account = users.findById(10L).orElseThrow();
        PublishTask task = captchaTask(101L, 0);
        LocalDateTime expiredAt = LocalDateTime.now().minusSeconds(2);

        PublishTask scheduled = service.scheduleCaptchaRetry(task.getId(), expiredAt, 3, 30);

        assertEquals(PublishTaskState.RETRY_WAIT, scheduled.getState());
        assertTrue(scheduled.isCaptchaRetryPending());
        assertEquals(expiredAt.plusMinutes(30), scheduled.getNextAttemptAt());
        assertEquals(task.getId(), account.getPublishCaptchaProbeTaskId());
        assertEquals(scheduled.getNextAttemptAt(), account.getPublishCaptchaRetryAt());

        assertTrue(service.markCaptchaAttemptStarted(task.getId()));
        assertEquals(1, task.getCaptchaRetryCount());
        assertFalse(task.isCaptchaRetryPending());
        assertEquals(task.getId(), account.getPublishCaptchaProbeTaskId());
        assertEquals(null, account.getPublishCaptchaRetryAt());

        PublishTask exhausted = captchaTask(102L, 3);
        PublishTask blocked = service.scheduleCaptchaRetry(exhausted.getId(), expiredAt, 3, 120);
        assertEquals(PublishTaskState.NEEDS_ACTION, blocked.getState());
        assertEquals("CAPTCHA_RETRY_EXHAUSTED", blocked.getWaitReason());
        assertEquals(exhausted.getId(), account.getPublishCaptchaProbeTaskId());
        assertEquals(null, account.getPublishCaptchaRetryAt());
    }

    @Test
    void userCanSkipCaptchaRetryWaitWithoutConsumingAnAutomaticAttempt() {
        BiliBiliUser account = users.findById(10L).orElseThrow();
        PublishTask task = captchaTask(104L, 1);
        task.setState(PublishTaskState.RETRY_WAIT);
        task.setWaitReason("CAPTCHA_AUTO_RETRY");
        task.setNextAttemptAt(LocalDateTime.now().plusMinutes(30));
        account.setPublishCaptchaProbeTaskId(task.getId());
        account.setPublishCaptchaRetryAt(task.getNextAttemptAt());

        PublishTask retried = service.retry(task.getId(), false);

        assertEquals(PublishTaskState.READY, retried.getState());
        assertEquals("用户手动请求立即重新检查投稿验证码", retried.getResultMessage());
        assertEquals(0, retried.getCaptchaRetryCount());
        assertFalse(retried.isCaptchaRetryPending());
        assertEquals(task.getId(), account.getPublishCaptchaProbeTaskId());
        assertTrue(account.getPublishCaptchaRetryAt().isBefore(LocalDateTime.now().plusSeconds(1)));
    }

    @Test
    void retryDeadlineUpdatesOnlyTheAccountCaptchaProbeOwner() {
        BiliBiliUser account = users.findById(10L).orElseThrow();
        PublishTask task = captchaTask(105L, 1);
        account.setPublishCaptchaProbeTaskId(task.getId());
        LocalDateTime retryAt = LocalDateTime.now().plusMinutes(2);

        assertTrue(service.updateCaptchaProbeRetryAt(10L, task.getId(), retryAt));
        assertFalse(service.updateCaptchaProbeRetryAt(10L, task.getId() + 1, retryAt));
        assertEquals(retryAt, account.getPublishCaptchaRetryAt());
    }

    private PublishTask captchaTask(Long id, int usedAttempts) {
        PublishTask task = new PublishTask();
        task.setId(id);
        task.setHistoryId(1L);
        task.setAccountId(10L);
        task.setOperation(PublishTaskOperation.NEW_PUBLISH);
        task.setSource(PublishTaskSource.MANUAL);
        task.setState(PublishTaskState.WAITING_CAPTCHA);
        task.setWaitReason("PUBLISH_CAPTCHA");
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        task.setCaptchaRetryCount(usedAttempts);
        stored.add(task);
        return task;
    }
}
