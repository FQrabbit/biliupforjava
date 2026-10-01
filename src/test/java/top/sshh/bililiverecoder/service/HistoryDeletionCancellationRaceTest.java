package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.HistoryDeletionTask;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.repo.HistoryDeletionTaskRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = {
        "spring.datasource.hikari.jdbc-url=jdbc:h2:mem:history-deletion-cancel-race;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(HistoryDeletionTaskService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class HistoryDeletionCancellationRaceTest {
    @Autowired
    private RecordHistoryRepository histories;
    @Autowired
    private HistoryDeletionTaskRepository tasks;
    @Autowired
    private HistoryDeletionTaskService service;

    @BeforeEach
    void clearPersistedTestRows() {
        tasks.deleteAllInBatch();
        histories.deleteAll();
    }

    @Test
    void cancellationAndExecutionFenceCannotBothWin() throws Exception {
        RecordHistory history = new RecordHistory();
        history.setRoomId("cancel-race-room");
        history = histories.save(history);
        HistoryDeletionTask task = service.accept(history.getId(), history.getRoomId(),
                HistoryDeletionService.DeleteOptions.databaseOnly());
        assertNotNull(task);
        assertNotNull(service.claim(task.getId()));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> execution = executor.submit(() -> {
                ready.countDown();
                start.await();
                return service.beginExecution(task.getId());
            });
            Future<HistoryDeletionTaskService.CancelResult> cancellation = executor.submit(() -> {
                ready.countDown();
                start.await();
                return service.cancel(task.getId());
            });

            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            boolean executionStarted = execution.get(5, java.util.concurrent.TimeUnit.SECONDS);
            HistoryDeletionTaskService.CancelResult cancelResult = cancellation.get(5,
                    java.util.concurrent.TimeUnit.SECONDS);

            HistoryDeletionTask storedTask = tasks.findById(task.getId()).orElseThrow();
            RecordHistory storedHistory = histories.findById(history.getId()).orElseThrow();
            assertFalse(executionStarted && cancelResult.success(), "执行标记和取消不能同时成功");
            if (executionStarted) {
                assertFalse(cancelResult.success());
                assertTrue(storedTask.isDeletionStarted());
                assertEquals("RUNNING", storedTask.getState());
                assertTrue(storedHistory.isDeletePending());
            } else {
                assertTrue(cancelResult.success());
                assertEquals("CANCELLED", storedTask.getState());
                assertFalse(storedTask.isDeletionStarted());
                assertFalse(storedHistory.isDeletePending());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void restartRecoveryKeepsFenceForTasksThatMayHaveDeletedFiles() {
        RecordHistory beforeExecutionHistory = history("restart-before-execution");
        RecordHistory afterExecutionHistory = history("restart-after-execution");
        HistoryDeletionTask beforeExecution = service.accept(beforeExecutionHistory.getId(),
                beforeExecutionHistory.getRoomId(), HistoryDeletionService.DeleteOptions.databaseOnly());
        HistoryDeletionTask afterExecution = service.accept(afterExecutionHistory.getId(),
                afterExecutionHistory.getRoomId(), HistoryDeletionService.DeleteOptions.databaseOnly());
        assertNotNull(service.claim(beforeExecution.getId()));
        assertNotNull(service.claim(afterExecution.getId()));
        assertTrue(service.beginExecution(afterExecution.getId()));

        assertEquals(2, tasks.recoverInterrupted(LocalDateTime.now()));

        HistoryDeletionTask recoveredBeforeExecution = tasks.findById(beforeExecution.getId()).orElseThrow();
        HistoryDeletionTask recoveredAfterExecution = tasks.findById(afterExecution.getId()).orElseThrow();
        assertEquals("PENDING", recoveredBeforeExecution.getState());
        assertFalse(recoveredBeforeExecution.isDeletionStarted());
        assertEquals("PENDING", recoveredAfterExecution.getState());
        assertTrue(recoveredAfterExecution.isDeletionStarted());
    }

    private RecordHistory history(String roomId) {
        RecordHistory history = new RecordHistory();
        history.setRoomId(roomId);
        return histories.save(history);
    }
}
