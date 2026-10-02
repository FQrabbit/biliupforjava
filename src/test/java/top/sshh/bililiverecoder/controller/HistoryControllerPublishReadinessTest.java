package top.sshh.bililiverecoder.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.entity.PublishTaskSource;
import top.sshh.bililiverecoder.service.PublishReadinessService;
import top.sshh.bililiverecoder.service.PublishAccountScheduler;
import top.sshh.bililiverecoder.service.impl.RecordBiliPublishService;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class HistoryControllerPublishReadinessTest {
    @Test
    void blockedManualRequestPreservesHistoryAndReturnsWaitingReason() {
        for (String reason : new String[]{"RECORDING", "RECORDING_END_UNKNOWN"}) {
            HistoryController controller = new HistoryController();
            RecordHistoryRepository histories = mock(RecordHistoryRepository.class);
            PublishReadinessService readiness = mock(PublishReadinessService.class);
            RecordBiliPublishService publisher = mock(RecordBiliPublishService.class);
            ReflectionTestUtils.setField(controller, "historyRepository", histories);
            ReflectionTestUtils.setField(controller, "publishReadinessService", readiness);
            ReflectionTestUtils.setField(controller, "publishService", publisher);
            ReflectionTestUtils.setField(controller, "publishAccountScheduler", mock(PublishAccountScheduler.class));
            RecordHistory history = new RecordHistory();
            history.setId(7L);
            history.setUploadRetryCount(4);
            LocalDateTime earliest = reason.equals("MERGE_INTERVAL") ? LocalDateTime.of(2026, 10, 2, 4, 20) : null;
            when(histories.findById(7L)).thenReturn(Optional.of(history));
            when(readiness.check(history)).thenReturn(new PublishReadinessService.Check(false, reason, "请继续等待", earliest));
            var response = controller.touchPublish(7L);
            assertEquals(false, response.get("accepted"));
            assertEquals(reason, response.get("publishWaitReason"));
            assertEquals(earliest, response.get("publishNotBefore"));
            assertEquals(4, history.getUploadRetryCount());
            verify(histories, never()).save(any());
            verifyNoInteractions(publisher);
        }
    }

    @Test
    void mergeWaitingCanBeManuallyAcceptedWithoutChangingRecordingEndTime() {
        HistoryController controller = new HistoryController();
        RecordHistoryRepository histories = mock(RecordHistoryRepository.class);
        PublishReadinessService readiness = mock(PublishReadinessService.class);
        RecordBiliPublishService publisher = mock(RecordBiliPublishService.class);
        ReflectionTestUtils.setField(controller, "historyRepository", histories);
        ReflectionTestUtils.setField(controller, "publishReadinessService", readiness);
        ReflectionTestUtils.setField(controller, "publishService", publisher);
        ReflectionTestUtils.setField(controller, "roomRepository", mock(RecordRoomRepository.class));
        ReflectionTestUtils.setField(controller, "publishAccountScheduler", mock(PublishAccountScheduler.class));
        RecordHistory history = new RecordHistory();
        history.setId(7L);
        LocalDateTime end = LocalDateTime.now().minusMinutes(2);
        history.setEndTime(end);
        when(histories.findById(7L)).thenReturn(Optional.of(history));
        when(histories.save(history)).thenReturn(history);
        when(readiness.check(history)).thenReturn(new PublishReadinessService.Check(false, "MERGE_INTERVAL", "等待合并", end.plusMinutes(10)));
        when(publisher.asyncPublishRecordHistory(history, PublishTaskSource.MANUAL)).thenReturn(true);
        var response = controller.touchPublish(7L);
        assertEquals(true, response.get("accepted"));
        assertEquals(false, response.get("waitingForPublish"));
        assertEquals(null, response.get("publishWaitReason"));
        assertEquals(end, history.getEndTime());
        verify(publisher).asyncPublishRecordHistory(history, PublishTaskSource.MANUAL);
    }
}
