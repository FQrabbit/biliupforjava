package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.StorageRoot;
import top.sshh.bililiverecoder.repo.LiveMsgRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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
    private RecordHistoryPartRepository partRepository;
    @Mock
    private LiveMsgRepository msgRepository;
    @Mock
    private HistoryMsgQueueCleanupService msgQueueCleanupService;
    @Mock
    private PartFileOperationService partFileOperationService;
    @Mock
    private PartFileLocationService partFileLocationService;
    @Mock
    private StorageRootService storageRootService;
    @Mock
    private RoomLiveEventXmlIssueService xmlIssueService;
    @InjectMocks
    private HistoryDeletionService service;

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
        when(partRepository.deleteByHistoryId(11L)).thenReturn(1);
        when(msgRepository.deleteByHistoryId(11L)).thenReturn(3);
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
        verify(partFileLocationService).resolveCompanions(21L, ".ass");
        verify(partFileLocationService).resolveCompanions(21L, ".png");
        verify(partFileLocationService).resolveCompanions(21L, ".webp");
        verify(partFileOperationService).purgeMetadata(21L);
        verify(historyRepository).delete(history);
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
}
