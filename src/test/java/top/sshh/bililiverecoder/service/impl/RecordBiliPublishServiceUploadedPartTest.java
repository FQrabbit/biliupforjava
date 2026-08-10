package top.sshh.bililiverecoder.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.service.PartFileLocationService;
import top.sshh.bililiverecoder.service.RecordPartPathService;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecordBiliPublishServiceUploadedPartTest {

    @Mock
    private RecordHistoryPartRepository partRepository;
    @Mock
    private RecordHistoryRepository historyRepository;
    @Mock
    private RecordRoomRepository roomRepository;
    @Mock
    private PartFileLocationService partFileLocationService;
    @Mock
    private RecordPartPathService partPathService;

    private RecordBiliPublishService service;
    private RecordRoom room;
    private RecordHistory history;

    @BeforeEach
    void setUp() {
        service = new RecordBiliPublishService();
        ReflectionTestUtils.setField(service, "partRepository", partRepository);
        ReflectionTestUtils.setField(service, "historyRepository", historyRepository);
        ReflectionTestUtils.setField(service, "roomRepository", roomRepository);
        ReflectionTestUtils.setField(service, "partFileLocationService", partFileLocationService);
        ReflectionTestUtils.setField(service, "partPathService", partPathService);

        room = new RecordRoom();
        room.setRoomId("room-1");
        room.setUname("tester");
        room.setTid(17);
        room.setUpload(true);
        room.setUploadUserId(null);
        when(roomRepository.findByRoomId("room-1")).thenReturn(room);
        when(partPathService.selectPreferredParts(anyList()))
                .thenAnswer(invocation -> new RecordPartPathService.PartSelection(
                        invocation.getArgument(0), List.of()));

        history = new RecordHistory();
        history.setId(100L);
        history.setRoomId("room-1");
        history.setUpload(true);
        history.setPublish(false);
        history.setRecording(false);
    }

    @Test
    void uploadedPartWithDeletedLocalFileSkipsPathResolution() {
        RecordHistoryPart part = uploadedPart(1L, "server-file-name", 123L);
        when(partRepository.findByHistoryIdOrderByStartTimeAsc(100L)).thenReturn(List.of(part));
        when(partRepository.findById(1L)).thenReturn(Optional.of(part));

        assertFalse(service.publishRecordHistory(history));

        verify(partFileLocationService, never()).resolveReadable(1L);
    }

    @Test
    void uploadedPartWithFilenameOnlyRemainsReusableForLegacyUploadFlow() {
        RecordHistoryPart part = uploadedPart(1L, "legacy-server-file-name", null);
        when(partRepository.findByHistoryIdOrderByStartTimeAsc(100L)).thenReturn(List.of(part));
        when(partRepository.findById(1L)).thenReturn(Optional.of(part));

        assertFalse(service.publishRecordHistory(history));

        verify(partFileLocationService, never()).resolveReadable(1L);
    }

    @Test
    void uploadedPartWithoutFilenameStopsBeforeLocalPathResolution() {
        RecordHistoryPart part = uploadedPart(1L, null, 123L);
        when(partRepository.findByHistoryIdOrderByStartTimeAsc(100L)).thenReturn(List.of(part));
        when(partRepository.findById(1L)).thenReturn(Optional.of(part));

        assertFalse(service.publishRecordHistory(history));

        verify(partFileLocationService, never()).resolveReadable(1L);
    }

    @Test
    void mixedPartsOnlyResolveLocalPathForUnuploadedPart() {
        RecordHistoryPart uploaded = uploadedPart(1L, "server-file-name", 123L);
        RecordHistoryPart pending = uploadedPart(2L, null, null);
        pending.setUpload(false);
        when(partRepository.findByHistoryIdOrderByStartTimeAsc(100L)).thenReturn(List.of(uploaded, pending));
        when(partRepository.findById(1L)).thenReturn(Optional.of(uploaded));
        when(partRepository.findById(2L)).thenReturn(Optional.of(pending));
        when(partFileLocationService.resolveReadable(2L)).thenReturn(
                new PartFileLocationService.FileResolution(
                        PartFileLocationService.LocalFileState.DELETED_BY_POLICY,
                        null, null, null, "file removed by configured policy"));

        assertFalse(service.publishRecordHistory(history));

        verify(partFileLocationService, never()).resolveReadable(1L);
        verify(partFileLocationService).resolveReadable(2L);
    }

    private static RecordHistoryPart uploadedPart(Long id, String fileName, Long cid) {
        RecordHistoryPart part = new RecordHistoryPart();
        part.setId(id);
        part.setHistoryId(100L);
        part.setRoomId("room-1");
        part.setUpload(true);
        part.setFileDelete(true);
        part.setFileName(fileName);
        part.setCid(cid);
        part.setFilePath("D:/recording/video-" + id + ".flv");
        part.setRecording(false);
        part.setEndTime(java.time.LocalDateTime.now());
        return part;
    }
}
