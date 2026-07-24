package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.util.TaskUtil;
import top.sshh.bililiverecoder.util.UploadProgressTracker;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoomDeletionServiceTest {

    @Mock
    private RecordRoomRepository roomRepository;
    @Mock
    private RecordHistoryRepository historyRepository;
    @Mock
    private RecordHistoryPartRepository partRepository;
    @Mock
    private HistoryDeletionService historyDeletionService;
    @Mock
    private StatsAggregationService statsAggregationService;
    @Mock
    private UploadProgressTracker uploadProgressTracker;
    @Mock
    private UploadUserSerialScheduler uploadUserSerialScheduler;
    @InjectMocks
    private RoomDeletionService service;

    @AfterEach
    void clearTaskRegistry() {
        TaskUtil.partUploadTask.clear();
        TaskUtil.publishTask.clear();
    }

    @Test
    void previewAggregatesHistoryPartsAndWebhookSource() {
        RecordRoom room = room(10L, "123");
        room.setWebhookSource("BLREC");
        room.setWebhookLastSeenAt(LocalDateTime.now());
        RecordHistory history = history(21L, "123");
        RecordHistoryPart part = part(31L, 21L, 4096L);
        when(roomRepository.findById(10L)).thenReturn(Optional.of(room));
        when(historyRepository.findByRoomIdOrderByIdAsc("123")).thenReturn(List.of(history));
        when(partRepository.findByHistoryIdIn(List.of(21L))).thenReturn(List.of(part));

        RoomDeletionService.DeletionPreview preview = service.preview(10L);

        assertTrue(preview.found());
        assertFalse(preview.active());
        assertEquals(1, preview.historyCount());
        assertEquals(1, preview.partCount());
        assertEquals(4096L, preview.estimatedVideoBytes());
        assertEquals("BLREC", preview.webhookSource());
        assertTrue(preview.webhookManaged());
        assertEquals(10L, preview.toMap().get("roomDatabaseId"));
        assertEquals(1, preview.toMap().get("historyCount"));
        assertEquals("BLREC", preview.toMap().get("webhookSource"));
    }

    @Test
    void unpublishedUploadBlocksUntilCancelledOrForceArchived() {
        RecordRoom room = room(10L, "123");
        RecordHistory history = history(21L, "123");
        history.setUpload(true);
        RecordHistoryPart part = part(31L, 21L, 4096L);
        when(roomRepository.findById(10L)).thenReturn(Optional.of(room));
        when(historyRepository.findByRoomIdOrderByIdAsc("123")).thenReturn(List.of(history));
        when(partRepository.findByHistoryIdIn(List.of(21L))).thenReturn(List.of(part));

        RoomDeletionService.DeletionPreview uploading = service.preview(10L);

        assertTrue(uploading.active());
        assertTrue(uploading.uploadingActive());
        assertFalse(uploading.recordingActive());
        assertEquals(1, uploading.uploadingHistoryCount());

        history.setUpload(false);
        assertFalse(service.preview(10L).active());

        history.setUpload(true);
        history.setForceArchived(true);
        assertFalse(service.preview(10L).active());
    }

    @Test
    void runningUploadTaskStillBlocksAfterUploadFlagIsCancelled() {
        RecordRoom room = room(10L, "123");
        RecordHistory history = history(21L, "123");
        RecordHistoryPart part = part(31L, 21L, 4096L);
        when(roomRepository.findById(10L)).thenReturn(Optional.of(room));
        when(historyRepository.findByRoomIdOrderByIdAsc("123")).thenReturn(List.of(history));
        when(partRepository.findByHistoryIdIn(List.of(21L))).thenReturn(List.of(part));
        TaskUtil.partUploadTask.put(part.getId(), Thread.currentThread());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.delete(10L, RoomDeletionService.DeleteOptions.roomOnly()));

        assertTrue(error.getMessage().contains("取消上传或强制归档"));
        verify(roomRepository, never()).delete(any());
    }

    @Test
    void streamingRoomBlocksDeletion() {
        RecordRoom room = room(10L, "123");
        room.setStreaming(true);
        when(roomRepository.findById(10L)).thenReturn(Optional.of(room));
        when(historyRepository.findByRoomIdOrderByIdAsc("123")).thenReturn(List.of());

        RoomDeletionService.DeletionPreview preview = service.preview(10L);

        assertTrue(preview.active());
        assertTrue(preview.recordingActive());
        assertFalse(preview.uploadingActive());
    }

    @Test
    void forceArchiveDoesNotBypassUnfinishedRecordingGuard() {
        RecordRoom room = room(10L, "123");
        RecordHistory history = history(21L, "123");
        history.setForceArchived(true);
        history.setEndTime(null);
        when(roomRepository.findById(10L)).thenReturn(Optional.of(room));
        when(historyRepository.findByRoomIdOrderByIdAsc("123")).thenReturn(List.of(history));
        when(partRepository.findByHistoryIdIn(List.of(21L))).thenReturn(List.of());

        RoomDeletionService.DeletionPreview preview = service.preview(10L);

        assertTrue(preview.active());
        assertTrue(preview.recordingActive());
        assertFalse(preview.uploadingActive());
    }

    @Test
    void roomOnlyDeleteDoesNotTouchHistory() {
        RecordRoom room = room(10L, "123");
        when(roomRepository.findById(10L)).thenReturn(Optional.of(room));
        when(historyRepository.findByRoomIdOrderByIdAsc("123")).thenReturn(List.of(history(21L, "123")));
        when(partRepository.findByHistoryIdIn(List.of(21L))).thenReturn(List.of(part(31L, 21L, 1024L)));

        RoomDeletionService.DeletionResult result = service.delete(10L, RoomDeletionService.DeleteOptions.roomOnly());

        assertTrue(result.deleted());
        assertEquals(0, result.deletedHistoryCount());
        verify(historyDeletionService, never()).delete(any(), any());
        verify(statsAggregationService, never()).deleteRoomStats(anyString());
        verify(roomRepository).delete(room);
    }

    @Test
    void localFileOptionsRequireHistoryDeletion() {
        assertThrows(IllegalArgumentException.class, () -> service.delete(10L,
                new RoomDeletionService.DeleteOptions(false, true, false, false)));
        verifyNoInteractions(roomRepository, historyDeletionService);
    }

    @Test
    void activePartBlocksDeleteAtExecutionTime() {
        RecordRoom room = room(10L, "123");
        RecordHistory history = history(21L, "123");
        RecordHistoryPart part = part(31L, 21L, 1024L);
        part.setRecording(true);
        when(roomRepository.findById(10L)).thenReturn(Optional.of(room));
        when(historyRepository.findByRoomIdOrderByIdAsc("123")).thenReturn(List.of(history));
        when(partRepository.findByHistoryIdIn(List.of(21L))).thenReturn(List.of(part));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.delete(10L, RoomDeletionService.DeleteOptions.roomOnly()));

        assertTrue(error.getMessage().contains("停止录制"));
        verify(roomRepository, never()).delete(any());
    }

    @Test
    void historyDeleteResultsAreAggregatedBeforeRoomRemoval() {
        RecordRoom room = room(10L, "123");
        RecordHistory first = history(21L, "123");
        RecordHistory second = history(22L, "123");
        when(roomRepository.findById(10L)).thenReturn(Optional.of(room));
        when(historyRepository.findByRoomIdOrderByIdAsc("123")).thenReturn(List.of(first, second));
        when(partRepository.findByHistoryIdIn(List.of(21L, 22L))).thenReturn(List.of());
        HistoryDeletionService.DeleteOptions historyOptions = new HistoryDeletionService.DeleteOptions(true, true, true);
        Map<String, Object> failedFile = new LinkedHashMap<>();
        failedFile.put("path", "archive/video.flv");
        failedFile.put("reason", "storage offline");
        when(historyDeletionService.delete(21L, historyOptions)).thenReturn(historyResult(21L, 2, List.of()));
        when(historyDeletionService.delete(22L, historyOptions)).thenReturn(historyResult(22L, 3, List.of(failedFile)));
        when(statsAggregationService.deleteRoomStats("123")).thenReturn(
                new StatsAggregationService.RoomStatsDeletionResult(
                        "123", 1, 2, 3, 4, 5, 6, 7L, 8));

        RoomDeletionService.DeletionResult result = service.delete(10L,
                new RoomDeletionService.DeleteOptions(true, true, true, true));

        assertEquals(2, result.deletedHistoryCount());
        assertEquals(5, result.deletedPartCount());
        assertEquals(36L, result.deletedStatisticsCount());
        assertEquals(36L, result.toMap().get("deletedStatisticsCount"));
        assertEquals(1, result.notDeletedFiles().size());
        assertEquals(22L, result.notDeletedFiles().get(0).get("historyId"));
        InOrder deletionOrder = inOrder(historyDeletionService, statsAggregationService, roomRepository);
        deletionOrder.verify(historyDeletionService).delete(21L, historyOptions);
        deletionOrder.verify(historyDeletionService).delete(22L, historyOptions);
        deletionOrder.verify(statsAggregationService).deleteRoomStats("123");
        deletionOrder.verify(roomRepository).delete(room);
    }

    private static RecordRoom room(Long id, String roomId) {
        RecordRoom room = new RecordRoom();
        room.setId(id);
        room.setRoomId(roomId);
        room.setUname("anchor");
        return room;
    }

    private static RecordHistory history(Long id, String roomId) {
        RecordHistory history = new RecordHistory();
        history.setId(id);
        history.setRoomId(roomId);
        history.setEndTime(LocalDateTime.now());
        return history;
    }

    private static RecordHistoryPart part(Long id, Long historyId, long fileSize) {
        RecordHistoryPart part = new RecordHistoryPart();
        part.setId(id);
        part.setHistoryId(historyId);
        part.setFileSize(fileSize);
        part.setEndTime(LocalDateTime.now());
        return part;
    }

    private static HistoryDeletionService.DeletionResult historyResult(Long id,
                                                                        int deletedParts,
                                                                        List<Map<String, Object>> failures) {
        return new HistoryDeletionService.DeletionResult(
                true, true, id, "123", HistoryDeletionService.DeleteOptions.databaseOnly(),
                0, deletedParts, 0, 0, failures, 0, 0, 0, 0, 0);
    }
}
