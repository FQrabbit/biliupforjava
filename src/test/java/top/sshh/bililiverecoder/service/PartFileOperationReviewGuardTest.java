package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.bililiverecoder.entity.PartFileLocation;
import top.sshh.bililiverecoder.entity.PartFileOperation;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.entity.StorageRoot;
import top.sshh.bililiverecoder.repo.PartFileLocationRepository;
import top.sshh.bililiverecoder.repo.PartFileOperationRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PartFileOperationReviewGuardTest {

    @Mock private PartFileOperationRepository operationRepository;
    @Mock private PartFileLocationRepository locationRepository;
    @Mock private PartFileLocationService locationService;
    @Mock private StorageRootService rootService;
    @Mock private PartFileStorageAdapter storage;
    @Mock private ArchiveReviewStatusService reviewService;
    @Mock private RecordHistoryPartRepository partRepository;
    @Mock private RecordHistoryRepository historyRepository;
    @Mock private RecordRoomRepository roomRepository;

    private PartFileOperationService service;

    @BeforeEach
    void setUp() {
        service = new PartFileOperationService(
                operationRepository, locationRepository, locationService, rootService, storage);
        ReflectionTestUtils.setField(service, "archiveReviewStatusService", reviewService);
        ReflectionTestUtils.setField(service, "partRepository", partRepository);
        ReflectionTestUtils.setField(service, "historyRepository", historyRepository);
        ReflectionTestUtils.setField(service, "roomRepository", roomRepository);
    }

    @ParameterizedTest
    @EnumSource(value = ArchiveReviewStatusService.ReviewState.class, names = {"REJECTED", "REQUEST_FAILED"})
    void recoveredScheduledDeleteIsBlockedWhenReviewIsUnsafe(
            ArchiveReviewStatusService.ReviewState reviewState) throws Exception {
        PartFileOperation operation = new PartFileOperation();
        operation.setId(1L);
        operation.setPartId(2L);
        operation.setOperationType(PartFileOperation.OperationType.DELETE);
        operation.setOperationSource(PartFileOperation.OperationSource.SCHEDULED_CLEANUP);
        operation.setSourceLocationId(3L);
        operation.setStatus(PartFileOperation.OperationStatus.PENDING);

        RecordHistoryPart part = new RecordHistoryPart();
        part.setId(2L);
        part.setHistoryId(4L);
        part.setRoomId("room-1");
        RecordHistory history = new RecordHistory();
        history.setId(4L);
        history.setBvId("BV1test");
        RecordRoom room = new RecordRoom();
        room.setRoomId("room-1");
        room.setDeleteType(3);

        PartFileLocation location = new PartFileLocation();
        location.setId(3L);
        location.setPartId(2L);
        location.setStorageRootId(5L);
        location.setRelativePath("video.flv");
        location.setState(PartFileLocation.LocationState.AVAILABLE);
        StorageRoot root = new StorageRoot();
        root.setId(5L);
        Path source = Path.of("work", "video.flv");

        ArchiveReviewStatusService.ReviewRound round = new ArchiveReviewStatusService.ReviewRound();
        ArchiveReviewStatusService.ReviewCheckResult rejected =
                new ArchiveReviewStatusService.ReviewCheckResult(
                        4L, 7L, reviewState,
                        reviewState == ArchiveReviewStatusService.ReviewState.REJECTED ? -2 : null,
                        reviewState == ArchiveReviewStatusService.ReviewState.REJECTED ? "稿件已退回" : "请求超时",
                        LocalDateTime.now());

        when(rootService.hasPendingWorkPathChange()).thenReturn(false);
        when(operationRepository.findByStatusInOrderByCreatedAtAsc(any())).thenReturn(List.of(operation));
        when(operationRepository.findById(1L)).thenReturn(Optional.of(operation));
        when(operationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(partRepository.findById(2L)).thenReturn(Optional.of(part));
        when(historyRepository.findById(4L)).thenReturn(Optional.of(history));
        when(roomRepository.findByRoomId("room-1")).thenReturn(room);
        when(reviewService.newRound()).thenReturn(round);
        when(reviewService.checkForCleanup(history, room, round)).thenReturn(rejected);
        when(locationService.findLocation(3L)).thenReturn(Optional.of(location));
        when(rootService.findById(5L)).thenReturn(Optional.of(root));
        when(rootService.ensureOnline(root)).thenReturn(true);
        when(rootService.resolve(root, "video.flv")).thenReturn(source);
        when(storage.isRegularFile(source)).thenReturn(true);

        service.recoverPendingOperations();

        assertEquals(PartFileOperation.OperationStatus.FAILED, operation.getStatus());
        assertTrue(operation.getErrorMessage().contains("审核保护阻止执行"));
        verify(storage, never()).delete(any());
        verify(reviewService).checkForCleanup(history, room, round);
    }
}
