package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import top.sshh.bililiverecoder.entity.BiliBiliUser;
import top.sshh.bililiverecoder.entity.PublishTask;
import top.sshh.bililiverecoder.entity.PublishTaskOperation;
import top.sshh.bililiverecoder.entity.PublishTaskSource;
import top.sshh.bililiverecoder.entity.PublishTaskState;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.lifecycle.ShutdownState;
import top.sshh.bililiverecoder.repo.BiliUserRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.service.impl.HighEnergyCutPublishService;
import top.sshh.bililiverecoder.service.impl.RecordBiliPublishService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublishAccountSchedulerTest {
    @Test
    void captchaOnOneAccountDoesNotBlockAnotherAccount() throws Exception {
        try (Fixture fixture = new Fixture(2)) {
            RecordHistory accountA = fixture.addHistory(1L, 10L);
            RecordHistory accountB = fixture.addHistory(2L, 20L);
            CountDownLatch accountBPublished = new CountDownLatch(1);
            doAnswer(invocation -> { accountBPublished.countDown(); return true; })
                    .when(fixture.publisher).publishRecordHistory(accountB);
            when(fixture.captchas.hasPendingForAccount(10L)).thenReturn(true);

            fixture.scheduler.enqueue(10L, accountA.getId());
            fixture.scheduler.enqueue(20L, accountB.getId());

            assertTrue(accountBPublished.await(2, TimeUnit.SECONDS));
            verify(fixture.publisher, never()).publishRecordHistory(accountA);
        }
    }

    @Test
    void uploadWaitingReleasesWorkersForReadyAccounts() throws Exception {
        try (Fixture fixture = new Fixture(1)) {
            Set<Long> waitingHistoryIds = ConcurrentHashMap.newKeySet();
            CountDownLatch uploadsWaiting = new CountDownLatch(9);
            when(fixture.publisher.preparePublishTask(anyLong(), anyLong())).thenAnswer(invocation -> {
                Long historyId = invocation.getArgument(0);
                boolean waiting = waitingHistoryIds.contains(historyId);
                if (waiting) uploadsWaiting.countDown();
                return waiting
                        ? new RecordBiliPublishService.PreparationResult(false, false, "等待上传", LocalDateTime.now().plusSeconds(30))
                        : new RecordBiliPublishService.PreparationResult(true, false, "已就绪", null);
            });

            for (long index = 1; index <= 9; index++) {
                RecordHistory history = fixture.addHistory(index, index);
                waitingHistoryIds.add(history.getId());
                fixture.scheduler.enqueue(index, history.getId());
            }
            assertTrue(uploadsWaiting.await(3, TimeUnit.SECONDS));

            RecordHistory ready = fixture.addHistory(10L, 10L);
            CountDownLatch readyPublished = new CountDownLatch(1);
            doAnswer(invocation -> { readyPublished.countDown(); return true; })
                    .when(fixture.publisher).publishRecordHistory(ready);
            fixture.scheduler.enqueue(10L, ready.getId());

            assertTrue(readyPublished.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void submissionsForSameAccountAreSerialized() throws Exception {
        try (Fixture fixture = new Fixture(2)) {
            RecordHistory first = fixture.addHistory(1L, 10L);
            RecordHistory second = fixture.addHistory(2L, 10L);
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch finishFirst = new CountDownLatch(1);
            CountDownLatch secondStarted = new CountDownLatch(1);
            doAnswer(invocation -> {
                firstStarted.countDown();
                finishFirst.await(3, TimeUnit.SECONDS);
                return true;
            }).when(fixture.publisher).publishRecordHistory(first);
            doAnswer(invocation -> { secondStarted.countDown(); return true; })
                    .when(fixture.publisher).publishRecordHistory(second);

            fixture.scheduler.enqueue(10L, first.getId());
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            fixture.scheduler.enqueue(10L, second.getId());
            assertFalse(secondStarted.await(150, TimeUnit.MILLISECONDS));

            finishFirst.countDown();
            assertTrue(secondStarted.await(2, TimeUnit.SECONDS));
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final ThreadPoolTaskExecutor executor;
        private final RecordHistoryRepository histories = mock(RecordHistoryRepository.class);
        private final RecordRoomRepository rooms = mock(RecordRoomRepository.class);
        private final BiliUserRepository users = mock(BiliUserRepository.class);
        private final RecordBiliPublishService publisher = mock(RecordBiliPublishService.class);
        private final CaptchaService captchas = mock(CaptchaService.class);
        private final PublishTaskService tasks = taskStore();
        private final PublishAccountScheduler scheduler;
        private final Map<Long, RecordHistory> historyData = new ConcurrentHashMap<>();
        private final Map<String, RecordRoom> roomData = new ConcurrentHashMap<>();

        @SuppressWarnings("unchecked")
        Fixture(int workers) {
            executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(workers);
            executor.setMaxPoolSize(workers);
            executor.setQueueCapacity(64);
            executor.initialize();
            ObjectProvider<RecordBiliPublishService> publisherProvider = mock(ObjectProvider.class);
            when(publisherProvider.getObject()).thenReturn(publisher);
            ObjectProvider<HighEnergyCutPublishService> highEnergyProvider = mock(ObjectProvider.class);
            PublishAccountCooldownService cooldowns = mock(PublishAccountCooldownService.class);
            when(cooldowns.waitMs(anyLong())).thenReturn(0L);
            when(histories.findById(anyLong())).thenAnswer(invocation ->
                    Optional.ofNullable(historyData.get(invocation.getArgument(0))));
            when(rooms.findByRoomId(anyString())).thenAnswer(invocation -> roomData.get(invocation.getArgument(0)));
            when(users.findById(anyLong())).thenAnswer(invocation -> {
                BiliBiliUser user = new BiliBiliUser();
                user.setId(invocation.getArgument(0));
                user.setUid(invocation.getArgument(0));
                user.setLogin(true);
                return Optional.of(user);
            });
            when(publisher.preparePublishTask(anyLong(), anyLong()))
                    .thenReturn(new RecordBiliPublishService.PreparationResult(true, false, "已就绪", null));
            scheduler = new PublishAccountScheduler(executor, executor, publisherProvider, highEnergyProvider,
                    histories, mock(RecordHistoryPartRepository.class), rooms, users, tasks,
                    cooldowns, captchas, mock(ShutdownState.class));
        }

        RecordHistory addHistory(Long historyId, Long accountId) {
            RecordHistory history = new RecordHistory();
            history.setId(historyId);
            history.setRoomId("room-" + historyId);
            historyData.put(historyId, history);
            RecordRoom room = new RecordRoom();
            room.setRoomId(history.getRoomId());
            room.setUploadUserId(accountId);
            roomData.put(room.getRoomId(), room);
            return history;
        }

        @Override
        public void close() {
            scheduler.shutdown();
            executor.shutdown();
        }

        private static PublishTaskService taskStore() {
            PublishTaskService tasks = mock(PublishTaskService.class);
            Map<Long, PublishTask> store = new ConcurrentHashMap<>();
            AtomicLong ids = new AtomicLong();
            when(tasks.accept(anyLong(), anyLong(), any(PublishTaskOperation.class),
                    any(PublishTaskSource.class), any())).thenAnswer(invocation -> {
                Long historyId = invocation.getArgument(0);
                Long accountId = invocation.getArgument(1);
                PublishTask task = new PublishTask();
                task.setId(ids.incrementAndGet());
                task.setHistoryId(historyId);
                task.setAccountId(accountId);
                task.setOperation(invocation.getArgument(2));
                task.setSource(invocation.getArgument(3));
                task.setState(PublishTaskState.READY);
                task.setCreatedAt(LocalDateTime.now());
                task.setUpdatedAt(task.getCreatedAt());
                store.put(task.getId(), task);
                return new PublishTaskService.Admission(true, false, task, "accepted");
            });
            when(tasks.getByAccountAndStates(anyLong(), any())).thenAnswer(invocation -> {
                Long accountId = invocation.getArgument(0);
                Set<PublishTaskState> states = invocation.getArgument(1);
                return new ArrayList<>(store.values().stream().filter(task -> accountId.equals(task.getAccountId())
                        && states.contains(task.getState())).toList());
            });
            when(tasks.claim(anyLong(), any(PublishTaskState.class), anyString(), any(LocalDateTime.class)))
                    .thenAnswer(invocation -> {
                        PublishTask task = store.get(invocation.getArgument(0));
                        if (task == null || !Set.of(PublishTaskState.READY, PublishTaskState.RETRY_WAIT,
                                PublishTaskState.WAITING_ACCOUNT).contains(task.getState())) return null;
                        task.setState(invocation.getArgument(1));
                        return task;
                    });
            when(tasks.setState(anyLong(), any(PublishTaskState.class), any(), any(), any()))
                    .thenAnswer(invocation -> {
                        PublishTask task = store.get(invocation.getArgument(0));
                        if (task == null) return null;
                        task.setState(invocation.getArgument(1));
                        task.setWaitReason(invocation.getArgument(2));
                        task.setResultMessage(invocation.getArgument(3));
                        task.setNextAttemptAt(invocation.getArgument(4));
                        return task;
                    });
            when(tasks.transition(anyLong(), any(), any(PublishTaskState.class), any(), any(), any(), any()))
                    .thenAnswer(invocation -> {
                        PublishTask task = store.get(invocation.getArgument(0));
                        if (task == null) return null;
                        Set<PublishTaskState> expected = invocation.getArgument(1);
                        if (expected != null && !expected.contains(task.getState())) return null;
                        task.setState(invocation.getArgument(2));
                        task.setWaitReason(invocation.getArgument(3));
                        task.setResultMessage(invocation.getArgument(4));
                        task.setNextAttemptAt(invocation.getArgument(5));
                        if (invocation.getArgument(6) != null) task.setRetryCount(invocation.getArgument(6));
                        return task;
                    });
            when(tasks.get(anyLong())).thenAnswer(invocation -> store.get(invocation.getArgument(0)));
            when(tasks.toDto(any(PublishTask.class), any())).thenAnswer(invocation ->
                    new top.sshh.bililiverecoder.entity.PublishTaskStatusDto(
                            ((PublishTask) invocation.getArgument(0)).getId(),
                            ((PublishTask) invocation.getArgument(0)).getHistoryId(),
                            ((PublishTask) invocation.getArgument(0)).getAccountId(),
                            ((PublishTask) invocation.getArgument(0)).getOperation(),
                            ((PublishTask) invocation.getArgument(0)).getState(), null, null, 0, null,
                            null, null, null, null));
            return tasks;
        }
    }
}
