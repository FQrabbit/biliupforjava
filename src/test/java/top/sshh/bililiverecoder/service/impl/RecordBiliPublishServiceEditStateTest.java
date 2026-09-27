package top.sshh.bililiverecoder.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.service.PublishAccountScheduler;
import top.sshh.bililiverecoder.lifecycle.ShutdownState;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;

class RecordBiliPublishServiceEditStateTest {

    @Test
    void manualRepublishUsesAccountQueue() {
        RecordBiliPublishService service = spy(new RecordBiliPublishService());
        PublishAccountScheduler scheduler = mock(PublishAccountScheduler.class);
        ReflectionTestUtils.setField(service, "publishAccountScheduler", scheduler);
        ReflectionTestUtils.setField(service, "roomRepository", mock(RecordRoomRepository.class));
        ReflectionTestUtils.setField(service, "shutdownState", mock(ShutdownState.class));
        RecordHistory history = new RecordHistory();
        history.setId(42L);
        history.setPublishUserId(7L);

        service.asyncRepublishRecordHistory(history);

        verify(scheduler).enqueueRepublish(7L, 42L);
        verify(service, never()).editPublishedHistory(history, "republish");
    }

    @Test
    void successfulEditMovesUploadStateToPendingReview() {
        RecordBiliPublishService service = new RecordBiliPublishService();
        RecordHistory history = rejectedUploadingHistory();

        service.markHistoryPendingReviewAfterEdit(history);

        assertFalse(history.isEditPartsUploading());
        assertFalse(history.isForceArchived());
        assertEquals(-1, history.getCode());
        assertEquals("审核中", history.getStatus());
    }

    @Test
    void startupRecoveryClearsInterruptedUploadWithoutChangingOnlineState() {
        RecordHistoryRepository repository = mock(RecordHistoryRepository.class);
        RecordBiliPublishService service = new RecordBiliPublishService();
        ReflectionTestUtils.setField(service, "historyRepository", repository);
        RecordHistory history = rejectedUploadingHistory();
        when(repository.findByEditPartsUploadingTrue()).thenReturn(List.of(history));

        service.recoverInterruptedEditPartsUploads();

        assertFalse(history.isEditPartsUploading());
        assertTrue(history.isForceArchived());
        assertEquals(-2, history.getCode());
        verify(repository).saveAll(List.of(history));
    }

    private RecordHistory rejectedUploadingHistory() {
        RecordHistory history = new RecordHistory();
        history.setPublish(true);
        history.setUpload(true);
        history.setCode(-2);
        history.setForceArchived(true);
        history.setEditPartsUploading(true);
        return history;
    }
}
