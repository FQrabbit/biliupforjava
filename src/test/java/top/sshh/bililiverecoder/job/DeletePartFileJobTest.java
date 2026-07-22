package top.sshh.bililiverecoder.job;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sshh.bililiverecoder.entity.PartFileLocation;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.service.ArchiveReviewStatusService;
import top.sshh.bililiverecoder.service.PartFileCleanupPolicy;
import top.sshh.bililiverecoder.service.PartFileOperationService;
import top.sshh.bililiverecoder.service.StorageRootService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeletePartFileJobTest {

    @Mock
    private RecordRoomRepository roomRepository;
    @Mock
    private RecordHistoryPartRepository partRepository;
    @Mock
    private RecordHistoryRepository historyRepository;
    @Mock
    private ArchiveReviewStatusService reviewService;
    @Mock
    private PartFileCleanupPolicy cleanupPolicy;
    @Mock
    private PartFileOperationService operationService;
    @Mock
    private StorageRootService storageRootService;
    @InjectMocks
    private DeletePartFileJob job;

    private RecordRoom room;
    private RecordHistory history;
    private RecordHistoryPart first;
    private RecordHistoryPart second;
    private ArchiveReviewStatusService.ReviewRound round;

    @BeforeEach
    void setUp() {
        room = new RecordRoom();
        room.setRoomId("room-1");
        room.setDeleteType(3);
        room.setDeleteDay(6);
        history = new RecordHistory();
        history.setId(20L);
        history.setBvId("BV1test");
        first = part(1L);
        second = part(2L);
        round = new ArchiveReviewStatusService.ReviewRound();

        when(storageRootService.hasPendingWorkPathChange()).thenReturn(false);
        when(roomRepository.findByDeleteType(3)).thenReturn(List.of(room));
        when(partRepository.findFileCleanupCandidates(eq("room-1"), any(LocalDateTime.class),
                eq(PartFileLocation.LocationState.AVAILABLE))).thenReturn(List.of(first, second));
        when(historyRepository.findById(20L)).thenReturn(Optional.of(history));
        when(reviewService.newRound()).thenReturn(round);
    }

    @Test
    void checksOnceAndSubmitsEveryPartWhenApproved() {
        ArchiveReviewStatusService.ReviewCheckResult approved = result(ArchiveReviewStatusService.ReviewState.PASSED);
        when(reviewService.checkForCleanup(history, room, round)).thenReturn(approved);

        job.deleteFileProcess();

        verify(reviewService).checkForCleanup(history, room, round);
        verify(operationService).deleteScheduled(1L, approved);
        verify(operationService).deleteScheduled(2L, approved);
    }

    @Test
    void skipsWholeGroupWhenRejected() {
        when(reviewService.checkForCleanup(history, room, round))
                .thenReturn(result(ArchiveReviewStatusService.ReviewState.REJECTED));

        job.deleteFileProcess();

        verify(reviewService).checkForCleanup(history, room, round);
        verifyNoInteractions(operationService);
    }

    private RecordHistoryPart part(long id) {
        RecordHistoryPart part = new RecordHistoryPart();
        part.setId(id);
        part.setHistoryId(20L);
        part.setRoomId("room-1");
        part.setFilePath("video-" + id + ".flv");
        return part;
    }

    private ArchiveReviewStatusService.ReviewCheckResult result(ArchiveReviewStatusService.ReviewState state) {
        return new ArchiveReviewStatusService.ReviewCheckResult(
                20L, 7L, state, state == ArchiveReviewStatusService.ReviewState.PASSED ? 0 : -2,
                state.name(), LocalDateTime.now());
    }
}
