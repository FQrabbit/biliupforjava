package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.Test;
import top.sshh.bililiverecoder.entity.HistoryDeletionTask;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.repo.HistoryDeletionTaskRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HistoryDeletionTaskServiceTest {
    private final HistoryDeletionTaskRepository tasks = mock(HistoryDeletionTaskRepository.class);
    private final RecordHistoryRepository histories = mock(RecordHistoryRepository.class);
    private final RecordHistoryPartRepository parts = mock(RecordHistoryPartRepository.class);
    private final HistoryDeletionTaskService service = new HistoryDeletionTaskService(tasks, histories, parts);

    @Test
    void waitingDeletionCanBeCancelledAndClearsHistoryGate() {
        HistoryDeletionTask task = task("RETRY_WAIT", false);
        RecordHistory history = new RecordHistory();
        history.setId(task.getHistoryId());
        history.setDeletePending(true);
        when(tasks.findHistoryIdByTaskId(task.getId())).thenReturn(Optional.of(task.getHistoryId()));
        when(tasks.findByIdForUpdate(task.getId())).thenReturn(Optional.of(task));
        when(histories.findByIdForUpdate(task.getHistoryId())).thenReturn(Optional.of(history));

        HistoryDeletionTaskService.CancelResult result = service.cancel(task.getId());

        assertTrue(result.success());
        assertEquals("CANCELLED", task.getState());
        assertFalse(history.isDeletePending());
        verify(tasks).save(task);
        verify(histories).save(history);
    }

    @Test
    void cancellationIsIdempotentAndDeletionFencePreventsLateCancellation() {
        HistoryDeletionTask cancelled = task("CANCELLED", false);
        when(tasks.findHistoryIdByTaskId(cancelled.getId())).thenReturn(Optional.of(cancelled.getHistoryId()));
        when(tasks.findByIdForUpdate(cancelled.getId())).thenReturn(Optional.of(cancelled));
        HistoryDeletionTaskService.CancelResult repeated = service.cancel(cancelled.getId());
        assertTrue(repeated.success());
        assertTrue(repeated.alreadyCancelled());

        HistoryDeletionTask started = task("RETRY_WAIT", true);
        when(tasks.findHistoryIdByTaskId(started.getId())).thenReturn(Optional.of(started.getHistoryId()));
        when(tasks.findByIdForUpdate(started.getId())).thenReturn(Optional.of(started));
        when(histories.findByIdForUpdate(started.getHistoryId())).thenReturn(Optional.of(new RecordHistory()));
        HistoryDeletionTaskService.CancelResult rejected = service.cancel(started.getId());

        assertFalse(rejected.success());
        assertTrue(rejected.message().contains("已经开始"));
        assertEquals("RETRY_WAIT", started.getState());
    }

    @Test
    void executionFenceIsPersistedBeforeIrreversibleWork() {
        HistoryDeletionTask task = task("RUNNING", false);
        when(tasks.findByIdForUpdate(task.getId())).thenReturn(Optional.of(task));
        when(tasks.save(task)).thenReturn(task);

        assertTrue(service.beginExecution(task.getId()));
        assertTrue(task.isDeletionStarted());
        verify(tasks).save(task);
    }

    @Test
    void deletingAgainReusesCancelledTaskAndResetsItsFence() {
        HistoryDeletionTask cancelled = task("CANCELLED", false);
        RecordHistory history = new RecordHistory();
        history.setId(cancelled.getHistoryId());
        when(histories.findByIdForUpdate(cancelled.getHistoryId())).thenReturn(Optional.of(history));
        when(parts.findByHistoryIdOrderByStartTimeAsc(cancelled.getHistoryId())).thenReturn(java.util.List.of());
        when(tasks.findByHistoryId(cancelled.getHistoryId())).thenReturn(Optional.of(cancelled));
        when(tasks.saveAndFlush(cancelled)).thenReturn(cancelled);

        HistoryDeletionTask reused = service.accept(cancelled.getHistoryId(), "room-2",
                new HistoryDeletionService.DeleteOptions(true, true, false));

        assertSame(cancelled, reused);
        assertEquals("PENDING", reused.getState());
        assertEquals("room-2", reused.getRoomId());
        assertTrue(reused.isDeleteVideo());
        assertTrue(reused.isDeleteDanmaku());
        assertFalse(reused.isDeleteCover());
        assertFalse(reused.isDeletionStarted());
        assertTrue(history.isDeletePending());
    }

    private static HistoryDeletionTask task(String state, boolean started) {
        HistoryDeletionTask task = new HistoryDeletionTask();
        task.setId(8L);
        task.setHistoryId(12L);
        task.setState(state);
        task.setDeletionStarted(started);
        return task;
    }
}
