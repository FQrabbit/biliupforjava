package top.sshh.bililiverecoder.service.impl;

import com.alibaba.fastjson.JSON;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.repo.*;
import top.sshh.bililiverecoder.service.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecordBiliPublishServiceEditUploadTest {
    @TempDir Path directory;

    @Test
    void replacementSurvivesWaitingPreparationAndBecomesReadyAfterUpload() throws Exception {
        Path file = Files.write(directory.resolve("replacement.mp4"), new byte[]{1, 2, 3});
        RecordBiliPublishService service = new RecordBiliPublishService();
        RecordHistoryRepository histories = mock(RecordHistoryRepository.class);
        RecordHistoryPartRepository parts = mock(RecordHistoryPartRepository.class);
        RecordRoomRepository rooms = mock(RecordRoomRepository.class);
        BiliUserRepository users = mock(BiliUserRepository.class);
        StorageRootService roots = mock(StorageRootService.class);
        PartFileLocationService locations = mock(PartFileLocationService.class);
        UploadUserSerialScheduler uploads = mock(UploadUserSerialScheduler.class);
        ReflectionTestUtils.setField(service, "historyRepository", histories);
        ReflectionTestUtils.setField(service, "partRepository", parts);
        ReflectionTestUtils.setField(service, "roomRepository", rooms);
        ReflectionTestUtils.setField(service, "biliUserRepository", users);
        ReflectionTestUtils.setField(service, "storageRootService", roots);
        ReflectionTestUtils.setField(service, "partFileLocationService", locations);
        ReflectionTestUtils.setField(service, "uploadUserSerialScheduler", uploads);

        RecordHistory history = new RecordHistory();
        history.setId(3654L);
        history.setRoomId("846869");
        history.setPublish(true);
        history.setCode(-2);
        history.setSplitSizeBytes(10L * 1073741824L);
        history.setStartTime(LocalDateTime.now().minusHours(2));
        history.setEndTime(LocalDateTime.now().minusHours(1));
        RecordRoom room = new RecordRoom();
        room.setRoomId(history.getRoomId());
        BiliBiliUser user = new BiliBiliUser();
        user.setLogin(true);
        when(histories.findById(history.getId())).thenReturn(Optional.of(history));
        when(rooms.findByRoomId(history.getRoomId())).thenReturn(room);
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(roots.matchTrustedExisting(file)).thenReturn(Optional.of(new StorageRootService.RootMatch(null, "replacement.mp4", file)));
        AtomicReference<RecordHistoryPart> saved = new AtomicReference<>();
        when(parts.findByHistoryIdOrderByStartTimeAsc(history.getId())).thenAnswer(call ->
                saved.get() == null ? List.of() : List.of(saved.get()));
        when(parts.save(any())).thenAnswer(call -> {
            RecordHistoryPart part = call.getArgument(0);
            part.setId(16146L);
            saved.set(part);
            return part;
        });
        when(uploads.submitIfPartNotPending(eq(1L), eq(room.getRoomId()), eq(history.getId()),
                eq(16146L), eq("edit-parts-task"), any())).thenReturn(true);
        PublishTask task = new PublishTask();
        task.setHistoryId(history.getId());
        task.setAccountId(1L);
        task.setRequestSnapshot(JSON.toJSONString(Map.of("items", List.of(Map.of(
                "source", "local", "filePath", file.toString(), "title", "替换分P")))));

        assertFalse(service.prepareEditPartsTask(task).ready());
        RecordHistoryPart replacement = saved.get();
        assertNotNull(replacement);
        assertEquals("EDIT_PART", replacement.getSourceType());
        assertFalse(replacement.isUpload());
        assertNull(replacement.getFileName());
        assertEquals(0, replacement.getUploadRetryCount());

        replacement.setUploadRetryCount(2);
        when(uploads.hasPendingPart(16146L)).thenReturn(true);
        assertFalse(service.prepareEditPartsTask(task).ready());
        assertEquals(2, replacement.getUploadRetryCount());

        replacement.setUpload(true);
        replacement.setFileName("uploaded-platform-file");
        replacement.setCid(42521723326L);
        replacement.setFileSize(12345L);
        when(uploads.hasPendingPart(16146L)).thenReturn(false);
        assertTrue(service.prepareEditPartsTask(task).ready());
        assertEquals("uploaded-platform-file", replacement.getFileName());
        assertEquals(42521723326L, replacement.getCid());
        assertEquals(12345L, replacement.getFileSize());
        verify(parts, times(1)).save(any());
        verify(locations, times(1)).registerPrimary(replacement);
        verify(uploads, times(1)).submitIfPartNotPending(anyLong(), anyString(), anyLong(), anyLong(), anyString(), any());
    }
}
