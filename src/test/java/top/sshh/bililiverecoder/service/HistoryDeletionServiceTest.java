package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.StorageRoot;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.LiveMsgRepository;
import top.sshh.bililiverecoder.entity.HistoryDeletionTask;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HistoryDeletionServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    private RecordHistoryRepository historyRepository;
    @Mock
    private LiveMsgRepository liveMsgRepository;
    @Mock
    private RecordHistoryPartRepository partRepository;
    @Mock
    private HistoryMsgQueueCleanupService msgQueueCleanupService;
    @Mock
    private PartFileOperationService partFileOperationService;
    @Mock
    private PartFileLocationService partFileLocationService;
    @Mock
    private StorageRootService storageRootService;
    @Mock
    private PublishAccountScheduler publishAccountScheduler;
    @Mock
    private VideoCommentTaskService videoCommentTaskService;
    @Mock
    private VideoVisibilityRestoreService visibilityRestoreService;
    @Mock
    private StatsAggregationService statsAggregationService;
    @Mock
    private UploadUserSerialScheduler uploadUserSerialScheduler;
    @Mock
    private PartActivityCoordinator partActivityCoordinator;
    @Mock
    private HistoryDeletionTaskService deletionTasks;
    @Mock
    private HistoryDeletionDataPurger dataPurger;
    @InjectMocks
    private HistoryDeletionService service;
    private final AtomicReference<HistoryDeletionTask> acceptedTask = new AtomicReference<>();

    @BeforeEach
    void allowLockedDanmakuDeletesInUnitTests() {
        lenient().doAnswer(invocation -> {
            java.util.function.Supplier<?> action = invocation.getArgument(0);
            return action.get();
        }).when(statsAggregationService).withStatsWriteLock(any());
        lenient().when(deletionTasks.findByHistoryId(any())).thenReturn(null);
        lenient().when(deletionTasks.accept(any(), any(), any())).thenAnswer(invocation -> {
            Long historyId = invocation.getArgument(0);
            HistoryDeletionService.DeleteOptions options = invocation.getArgument(2);
            HistoryDeletionTask accepted = task(historyId, options);
            accepted.setState("PENDING");
            acceptedTask.set(accepted);
            return accepted;
        });
        lenient().when(deletionTasks.claim(any())).thenAnswer(invocation -> {
            HistoryDeletionTask current = acceptedTask.get();
            if (current != null) current.setState("RUNNING");
            return current;
        });
        lenient().when(deletionTasks.beginExecution(any())).thenAnswer(invocation -> {
            HistoryDeletionTask current = acceptedTask.get();
            if (current != null) current.setDeletionStarted(true);
            return true;
        });
        lenient().when(deletionTasks.findById(any())).thenAnswer(invocation -> acceptedTask.get());
        lenient().when(deletionTasks.defer(any(), any())).thenAnswer(invocation -> {
            HistoryDeletionTask deferred = acceptedTask.get();
            if (deferred == null) return null;
            deferred.setState("RETRY_WAIT");
            deferred.setLastError(invocation.getArgument(1));
            return deferred;
        });
        lenient().when(dataPurger.purge(any(), any())).thenAnswer(invocation -> {
            HistoryDeletionTask current = acceptedTask.get();
            if (current != null) current.setState("COMPLETED");
            return new HistoryDeletionDataPurger.PurgeResult(3, 1);
        });
    }

    @Test
    void deletesPersistedLocalPathsAndExpandedCompanionFormats() throws Exception {
        Path cover = Files.writeString(tempDir.resolve("actual-cover.webp"), "cover");
        Path danmaku = Files.writeString(tempDir.resolve("actual-danmaku.ass"), "danmaku");
        Path companion = Files.writeString(tempDir.resolve("video.png"), "companion");
        RecordHistory history = history(11L);
        history.setLocalCoverPath(cover.toString());
        history.setCoverUrl("https://example.invalid/remote-cover.jpg");
        RecordHistoryPart part = part(21L, 11L);
        part.setDanmakuFilePath(danmaku.toString());
        when(historyRepository.findById(11L)).thenReturn(Optional.of(history));
        when(partRepository.findByHistoryIdOrderByStartTimeAsc(11L)).thenReturn(List.of(part));
        when(partFileLocationService.resolveCompanions(eq(21L), any(String.class))).thenAnswer(invocation ->
                ".png".equals(invocation.getArgument(1)) ? List.of(companion) : List.of());
        when(storageRootService.matchTrustedExisting(any(Path.class))).thenAnswer(invocation -> {
            Path path = invocation.getArgument(0);
            StorageRoot root = new StorageRoot();
            root.setId(1L);
            return Optional.of(new StorageRootService.RootMatch(root, path.getFileName().toString(), path));
        });

        HistoryDeletionService.DeletionResult result = service.delete(11L,
                new HistoryDeletionService.DeleteOptions(false, true, true));

        assertTrue(result.deleted());
        assertFalse(Files.exists(cover));
        assertFalse(Files.exists(danmaku));
        assertFalse(Files.exists(companion));
        assertEquals(3, result.localDeleteAttempt());
        assertEquals(3, result.localDeleteSuccess());
        assertTrue(result.notDeletedFiles().isEmpty());
        verify(publishAccountScheduler).cancelForHistory(11L, "稿件已删除，未提交的投稿任务已取消");
        verify(partFileLocationService).resolveCompanions(21L, ".ass");
        verify(partFileLocationService).resolveCompanions(21L, ".png");
        verify(partFileLocationService).resolveCompanions(21L, ".webp");
        verify(dataPurger).purge(any(), eq(11L));
        var order = inOrder(deletionTasks, publishAccountScheduler, msgQueueCleanupService, dataPurger);
        order.verify(deletionTasks).beginExecution(33L);
        order.verify(publishAccountScheduler).cancelForHistory(11L, "稿件已删除，未提交的投稿任务已取消");
        order.verify(msgQueueCleanupService).cleanupByHistoryId(eq(11L), any(), eq(false), eq("delete"));
        order.verify(dataPurger).purge(any(), eq(11L));
    }

    @Test
    void refusesDeleteWhileCommentResultNeedsManualReview() {
        RecordHistory history = history(11L);
        when(historyRepository.findById(11L)).thenReturn(Optional.of(history));
        when(videoCommentTaskService.historyDeletionBlockReason(11L)).thenReturn("评论结果待核对");

        HistoryDeletionService.DeletionResult result = service.delete(11L,
                HistoryDeletionService.DeleteOptions.databaseOnly());

        assertFalse(result.deleted());
        assertEquals("评论结果待核对", result.notDeletedFiles().get(0).get("reason"));
        verify(historyRepository, never()).delete(any(RecordHistory.class));
        verify(visibilityRestoreService, never()).historyDeletionBlockReason(any());
    }

    @Test
    void refusesDeleteUntilVisibilityRestoreCompletes() {
        RecordHistory history = history(11L);
        when(historyRepository.findById(11L)).thenReturn(Optional.of(history));
        when(visibilityRestoreService.historyDeletionBlockReason(11L)).thenReturn("恢复任务正在重试");

        HistoryDeletionService.DeletionResult result = service.delete(11L,
                HistoryDeletionService.DeleteOptions.databaseOnly());

        assertFalse(result.deleted());
        assertEquals("恢复任务正在重试", result.notDeletedFiles().get(0).get("reason"));
        verify(historyRepository, never()).delete(any(RecordHistory.class));
    }

    @Test
    void refusesDeleteWhileUploadIsQueuedOrRunning() {
        RecordHistory history = history(11L);
        when(historyRepository.findById(11L)).thenReturn(Optional.of(history));
        when(uploadUserSerialScheduler.hasPendingHistory(11L)).thenReturn(true);

        HistoryDeletionService.DeletionResult result = service.delete(11L,
                HistoryDeletionService.DeleteOptions.databaseOnly());

        assertFalse(result.deleted());
        assertTrue(result.notDeletedFiles().get(0).get("reason").toString().contains("仍有分P"));
        verify(historyRepository, never()).delete(any(RecordHistory.class));
        verify(deletionTasks, never()).beginExecution(any());
        verify(publishAccountScheduler, never()).cancelForHistory(any(), any());
        verify(msgQueueCleanupService, never()).cleanupByHistoryId(any(), any(), anyBoolean(), any());
    }

    @Test
    void missingHistoryStillPersistsDeletionFenceBeforePurgingRecords() {
        when(historyRepository.findById(11L)).thenReturn(Optional.empty());

        HistoryDeletionService.DeletionResult result = service.delete(11L,
                HistoryDeletionService.DeleteOptions.databaseOnly());

        assertTrue(result.deleted());
        assertTrue(acceptedTask.get().isDeletionStarted());
        var order = inOrder(deletionTasks, dataPurger);
        order.verify(deletionTasks).beginExecution(33L);
        order.verify(dataPurger).purge(33L, 11L);
        verify(publishAccountScheduler, never()).cancelForHistory(any(), any());
    }

    @Test
    void databaseOnlyDeleteNeverTouchesLocalFiles() throws Exception {
        Path danmaku = Files.writeString(tempDir.resolve("keep.xml"), "danmaku");
        RecordHistory history = history(11L);
        RecordHistoryPart part = part(21L, 11L);
        part.setDanmakuFilePath(danmaku.toString());
        when(historyRepository.findById(11L)).thenReturn(Optional.of(history));
        when(partRepository.findByHistoryIdOrderByStartTimeAsc(11L)).thenReturn(List.of(part));

        HistoryDeletionService.DeletionResult result = service.delete(11L,
                HistoryDeletionService.DeleteOptions.databaseOnly());

        assertTrue(result.deleted());
        assertTrue(Files.exists(danmaku));
        verify(publishAccountScheduler).cancelForHistory(11L, "稿件已删除，未提交的投稿任务已取消");
        verify(partFileOperationService, never()).deleteAllAvailable(any());
        verify(partFileLocationService, never()).resolveCompanions(any(), any());
        verify(storageRootService, never()).matchTrustedExisting(any());
    }

    private static RecordHistory history(Long id) {
        RecordHistory history = new RecordHistory();
        history.setId(id);
        history.setRoomId("123");
        return history;
    }

    private static RecordHistoryPart part(Long id, Long historyId) {
        RecordHistoryPart part = new RecordHistoryPart();
        part.setId(id);
        part.setHistoryId(historyId);
        part.setFilePath("video.flv");
        return part;
    }

    private static HistoryDeletionTask task(Long historyId, HistoryDeletionService.DeleteOptions options) {
        HistoryDeletionTask task = new HistoryDeletionTask();
        task.setId(33L);
        task.setHistoryId(historyId);
        task.setRoomId("123");
        task.setDeleteVideo(options.deleteVideo());
        task.setDeleteDanmaku(options.deleteDanmaku());
        task.setDeleteCover(options.deleteCover());
        task.setState("RUNNING");
        return task;
    }
}
