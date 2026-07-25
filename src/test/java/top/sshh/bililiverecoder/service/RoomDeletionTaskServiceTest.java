package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomDeletionTaskServiceTest {

    @Mock
    private RoomDeletionService roomDeletionService;

    @Test
    void startsAndKeepsFinalTaskStatus() {
        RoomDeletionService.DeleteOptions options = RoomDeletionService.DeleteOptions.roomOnly();
        when(roomDeletionService.preview(10L)).thenReturn(preview(10L, 0));
        when(roomDeletionService.delete(eq(10L), eq(options), any(RoomDeletionService.ProgressReporter.class)))
                .thenReturn(deletion(10L, options));

        RoomDeletionTaskService service = new RoomDeletionTaskService(roomDeletionService, Runnable::run);
        RoomDeletionTaskService.StartResult started = service.start(10L, options);

        assertTrue(started.accepted());
        assertTrue(started.found());
        assertTrue(started.taskId() != null && !started.taskId().isBlank());
        Map<String, Object> status = service.status(started.taskId());
        assertFalse((Boolean) status.get("running"));
        assertEquals(100, status.get("percent"));
        assertEquals("DONE", status.get("phase"));
        assertEquals(started.taskId(), service.statusForRoom(10L).get("taskId"));
    }

    @Test
    void preventsTwoActiveTasksForOneRoom() {
        List<Runnable> queued = new ArrayList<>();
        TaskExecutor executor = queued::add;
        RoomDeletionService.DeleteOptions options = RoomDeletionService.DeleteOptions.roomOnly();
        when(roomDeletionService.preview(10L)).thenReturn(preview(10L, 1));

        RoomDeletionTaskService service = new RoomDeletionTaskService(roomDeletionService, executor);
        RoomDeletionTaskService.StartResult started = service.start(10L, options);

        assertTrue(started.accepted());
        assertThrows(IllegalStateException.class, () -> service.start(10L, options));
        assertEquals(1, queued.size());
    }

    @Test
    void rejectsLocalFileDeletionWithoutHistoryDeletion() {
        RoomDeletionService.DeleteOptions options = new RoomDeletionService.DeleteOptions(false, true, false, false);
        RoomDeletionTaskService service = new RoomDeletionTaskService(roomDeletionService, Runnable::run);

        assertThrows(IllegalArgumentException.class, () -> service.start(10L, options));
    }

    private static RoomDeletionService.DeletionPreview preview(Long id, int historyCount) {
        return new RoomDeletionService.DeletionPreview(
                true, id, "123", "anchor", "title", false, false,
                false, false, false, 0, 0, historyCount, 0, 0L,
                null, false, LocalDateTime.now());
    }

    private static RoomDeletionService.DeletionResult deletion(
            Long id, RoomDeletionService.DeleteOptions options) {
        return new RoomDeletionService.DeletionResult(
                true, true, id, "123", options,
                0, 0, 0, 0L, 0, 0, Collections.emptyList());
    }
}
