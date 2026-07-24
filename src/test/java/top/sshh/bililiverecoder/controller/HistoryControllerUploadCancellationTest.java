package top.sshh.bililiverecoder.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.service.HistoryMsgQueueCleanupService;
import top.sshh.bililiverecoder.service.UploadPauseService;
import top.sshh.bililiverecoder.util.TaskUtil;
import top.sshh.bililiverecoder.util.UploadProgressTracker;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HistoryControllerUploadCancellationTest {

    private final RecordHistoryRepository historyRepository = mock(RecordHistoryRepository.class);
    private final RecordHistoryPartRepository partRepository = mock(RecordHistoryPartRepository.class);
    private final RecordRoomRepository roomRepository = mock(RecordRoomRepository.class);
    private final UploadPauseService uploadPauseService = mock(UploadPauseService.class);
    private final UploadProgressTracker uploadProgressTracker = mock(UploadProgressTracker.class);
    private final HistoryMsgQueueCleanupService msgQueueCleanupService = mock(HistoryMsgQueueCleanupService.class);
    private final HistoryController controller = new HistoryController();
    private Thread uploadThread;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "historyRepository", historyRepository);
        ReflectionTestUtils.setField(controller, "partRepository", partRepository);
        ReflectionTestUtils.setField(controller, "roomRepository", roomRepository);
        ReflectionTestUtils.setField(controller, "uploadPauseService", uploadPauseService);
        ReflectionTestUtils.setField(controller, "uploadProgressTracker", uploadProgressTracker);
        ReflectionTestUtils.setField(controller, "msgQueueCleanupService", msgQueueCleanupService);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (uploadThread != null && uploadThread.isAlive()) {
            uploadThread.interrupt();
            uploadThread.join(2000L);
        }
        TaskUtil.partUploadTask.clear();
        TaskUtil.publishTask.clear();
    }

    @Test
    void cancellingUploadPausesAndInterruptsRunningPartTask() throws Exception {
        RecordHistory stored = history(21L, true);
        RecordHistoryPart part = part(31L, 21L);
        when(historyRepository.findById(21L)).thenReturn(Optional.of(stored));
        when(partRepository.findByHistoryIdOrderByStartTimeAsc(21L)).thenReturn(List.of(part));
        startUploadThread(part.getId());

        RecordHistory request = history(21L, false);
        Map<String, String> result = controller.update(request);

        assertFalse(stored.isUpload());
        assertEquals("info", result.get("type"));
        assertTrue(result.get("msg").contains("取消上传"));
        verify(uploadPauseService).pauseHistory(21L, "用户已取消稿件上传");
        awaitUploadThreadExit();
    }

    @Test
    void forceArchiveStopsUploadAndClearsEditUploadState() throws Exception {
        RecordHistory stored = history(21L, true);
        stored.setEditPartsUploading(true);
        RecordHistoryPart part = part(31L, 21L);
        when(historyRepository.findById(21L)).thenReturn(Optional.of(stored));
        when(partRepository.findByHistoryIdOrderByStartTimeAsc(21L)).thenReturn(List.of(part));
        when(msgQueueCleanupService.cleanupByHistoryId(eq(21L), any(), eq(false), eq("forceArchive")))
                .thenReturn(HistoryMsgQueueCleanupService.CleanupResult.empty(false));
        startUploadThread(part.getId());

        Map<String, String> result = controller.forceArchive(21L);

        assertTrue(stored.isForceArchived());
        assertFalse(stored.isUpload());
        assertFalse(stored.isEditPartsUploading());
        assertEquals("success", result.get("type"));
        verify(uploadPauseService).pauseHistory(21L, "稿件已强制归档，上传已停止");
        awaitUploadThreadExit();
    }

    private void startUploadThread(Long partId) throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        uploadThread = new Thread(() -> {
            started.countDown();
            try {
                Thread.sleep(30_000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "room-delete-upload-test");
        uploadThread.start();
        assertTrue(started.await(2, TimeUnit.SECONDS));
        TaskUtil.partUploadTask.put(partId, uploadThread);
    }

    private void awaitUploadThreadExit() throws InterruptedException {
        uploadThread.join(2000L);
        assertFalse(uploadThread.isAlive());
    }

    private static RecordHistory history(Long id, boolean upload) {
        RecordHistory history = new RecordHistory();
        history.setId(id);
        history.setRoomId("123");
        history.setUpload(upload);
        return history;
    }

    private static RecordHistoryPart part(Long id, Long historyId) {
        RecordHistoryPart part = new RecordHistoryPart();
        part.setId(id);
        part.setHistoryId(historyId);
        return part;
    }
}
