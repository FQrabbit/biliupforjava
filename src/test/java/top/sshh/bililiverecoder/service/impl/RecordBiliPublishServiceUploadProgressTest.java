package top.sshh.bililiverecoder.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.entity.data.BiliVideoPartInfoResponse;
import top.sshh.bililiverecoder.repo.*;
import top.sshh.bililiverecoder.service.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecordBiliPublishServiceUploadProgressTest {
    @TempDir Path directory;
    private RecordBiliPublishService service;
    private RecordHistoryPartRepository parts;
    private RecordHistorySplitService split;
    private UploadUserSerialScheduler uploads;
    private PublishAccountScheduler scheduler;
    private RecordPartUploadService uploader;
    private RecordHistory history;
    private RecordHistoryPart part;
    private AtomicReference<RecordHistoryPart> stored;
    private AtomicReference<Runnable> queued;

    @BeforeEach
    void setup() throws Exception {
        service = spy(new RecordBiliPublishService());
        RecordHistoryRepository histories = mock(RecordHistoryRepository.class);
        RecordRoomRepository rooms = mock(RecordRoomRepository.class);
        parts = mock(RecordHistoryPartRepository.class);
        split = mock(RecordHistorySplitService.class);
        uploads = mock(UploadUserSerialScheduler.class);
        scheduler = mock(PublishAccountScheduler.class);
        uploader = mock(RecordPartUploadService.class);
        UploadServiceFactory factory = mock(UploadServiceFactory.class);
        PartFileLocationService locations = mock(PartFileLocationService.class);
        RecordPartPathService paths = mock(RecordPartPathService.class);
        ReflectionTestUtils.setField(service, "historyRepository", histories);
        ReflectionTestUtils.setField(service, "roomRepository", rooms);
        ReflectionTestUtils.setField(service, "partRepository", parts);
        ReflectionTestUtils.setField(service, "historySplitService", split);
        ReflectionTestUtils.setField(service, "uploadUserSerialScheduler", uploads);
        ReflectionTestUtils.setField(service, "publishAccountScheduler", scheduler);
        ReflectionTestUtils.setField(service, "uploadServiceFactory", factory);
        ReflectionTestUtils.setField(service, "partFileLocationService", locations);
        ReflectionTestUtils.setField(service, "partPathService", paths);

        history = new RecordHistory();
        history.setId(3694L);
        history.setRoomId("6471735");
        RecordRoom room = new RecordRoom();
        room.setRoomId(history.getRoomId());
        room.setUploadUserId(1L);
        Path file = Files.write(directory.resolve("late.flv"), new byte[]{1, 2, 3});
        part = new RecordHistoryPart();
        part.setId(16302L);
        part.setHistoryId(history.getId());
        part.setRoomId(history.getRoomId());
        part.setFilePath(file.toString());
        part.setFileSize(Files.size(file));
        part.setDuration(3600);
        part.setStartTime(LocalDateTime.now().minusHours(2));
        part.setEndTime(LocalDateTime.now().minusHours(1));
        stored = new AtomicReference<>(part);
        queued = new AtomicReference<>();
        when(histories.findById(history.getId())).thenReturn(Optional.of(history));
        when(rooms.findByRoomId(history.getRoomId())).thenReturn(room);
        when(parts.findByHistoryIdOrderByStartTimeAsc(history.getId())).thenReturn(List.of(part));
        when(parts.findById(part.getId())).thenAnswer(call -> Optional.ofNullable(stored.get()));
        when(paths.selectPreferredParts(anyList())).thenAnswer(call ->
                new RecordPartPathService.PartSelection(call.getArgument(0), List.of()));
        when(locations.resolveReadable(part.getId())).thenReturn(new PartFileLocationService.FileResolution(
                PartFileLocationService.LocalFileState.AVAILABLE_WORK, file, new PartFileLocation(), null, null));
        when(split.canUpload(part)).thenReturn(true);
        when(factory.getUploadService(any())).thenReturn(uploader);
        when(uploads.submitIfPartNotPending(eq(1L), eq(room.getRoomId()), eq(history.getId()),
                eq(part.getId()), eq("publish-task"), any())).thenAnswer(call -> {
            queued.set(call.getArgument(5));
            return true;
        });
    }

    @Test
    void blockedPartWaitsWithoutEnteringUploadQueue() {
        when(split.canUpload(part)).thenReturn(false);

        RecordBiliPublishService.PreparationResult result = service.preparePublishTask(history.getId(), 1L);

        assertWaiting(result);
        verify(uploads, never()).submitIfPartNotPending(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(uploader, scheduler);
    }

    @AfterEach
    void clearHistorySuspension() {
        Map<?, ?> suspensions = (Map<?, ?>) ReflectionTestUtils.getField(RecordBiliPublishService.class, "suspendMap");
        suspensions.remove(history.getId());
    }

    @Test
    void uploadReturningWithoutProgressDoesNotWakeWaitingTask() {
        assertWaiting(service.preparePublishTask(history.getId(), 1L));

        queued.get().run();

        verify(uploader).upload(part);
        verifyNoInteractions(scheduler);
        assertFalse(part.isUpload());
    }

    @Test
    void persistedUploadResultWakesTaskAndNextPreparationIsReady() {
        assertWaiting(service.preparePublishTask(history.getId(), 1L));
        doAnswer(call -> {
            RecordHistoryPart completed = new RecordHistoryPart();
            completed.setUpload(true);
            completed.setFileName("uploaded-file");
            stored.set(completed);
            part.setUpload(true);
            part.setFileName("uploaded-file");
            return null;
        }).when(uploader).upload(part);

        queued.get().run();

        verify(scheduler).wakeHistory(history.getId());
        assertTrue(service.preparePublishTask(history.getId(), 1L).ready());
        verify(uploads, times(1)).submitIfPartNotPending(any(), any(), any(), any(), any(), any());
    }

    @Test
    void incompleteUploadMetadataDoesNotWakeTask() {
        assertWaiting(service.preparePublishTask(history.getId(), 1L));
        doAnswer(call -> { part.setUpload(true); return null; }).when(uploader).upload(part);

        queued.get().run();

        verifyNoInteractions(scheduler);
    }

    @Test
    void thresholdSkipWakesTaskToContinueWithRemainingParts() {
        assertWaiting(service.preparePublishTask(history.getId(), 1L));
        doAnswer(call -> { part.setDeleteFailType("SKIPPED_THRESHOLD"); return null; }).when(uploader).upload(part);

        queued.get().run();

        verify(scheduler).wakeHistory(history.getId());
    }

    @Test
    void uploadFailureDefersHistoryWithoutImmediateWake() {
        assertWaiting(service.preparePublishTask(history.getId(), 1L));
        doThrow(new IllegalStateException("upload failed")).when(uploader).upload(part);

        queued.get().run();

        verify(scheduler).deferUploadHistory(eq(history.getId()), anyString(), any(LocalDateTime.class));
        verify(scheduler, never()).wakeHistory(any());
        assertWaiting(service.preparePublishTask(history.getId(), 1L));
        verify(uploads, times(1)).submitIfPartNotPending(any(), any(), any(), any(), any(), any());
    }

    @Test
    void rateLimitedOnlineSnapshotWaitsWithoutUploadingOrClearingLocalResults() {
        history.setPublish(true);
        history.setPublishUserId(1L);
        history.setBvId("BV1Lypu6CEEs");
        ReflectionTestUtils.setField(service, "biliUserRepository", mock(BiliUserRepository.class));
        BiliVideoPartInfoResponse rejected = new BiliVideoPartInfoResponse();
        rejected.setCode(-702);
        rejected.setMessage("请求频率过高，请稍后再试");
        doReturn(rejected).when(service).loadOnlinePartInfo(any(), eq(history));
        part.setUpload(true);
        part.setFileName("existing-file");
        part.setCid(42626518234L);

        RecordBiliPublishService.PreparationResult result = service.preparePublishTask(history.getId(), 1L);

        assertWaiting(result);
        assertEquals("existing-file", part.getFileName());
        assertEquals(42626518234L, part.getCid());
        assertTrue(part.isUpload());
        verifyNoInteractions(uploads, uploader, scheduler);
        verify(parts, never()).save(any());
    }

    private void assertWaiting(RecordBiliPublishService.PreparationResult result) {
        assertFalse(result.ready());
        assertFalse(result.needsAction());
        assertNotNull(result.nextAttemptAt());
        assertTrue(result.nextAttemptAt().isAfter(LocalDateTime.now().plusSeconds(20)));
    }
}
