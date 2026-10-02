package top.sshh.bililiverecoder.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.repo.LiveMsgRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.service.HistoryMsgQueueCleanupService;
import top.sshh.bililiverecoder.service.HistoryMsgRetryService;
import top.sshh.bililiverecoder.service.PublishAccountScheduler;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class HistoryControllerOperationPolicyTest {
    @Test
    void unsubmittedAndUnreviewedRequestsCannotReadOrMutateDanmaku() {
        for (boolean submitted : List.of(false, true)) {
            HistoryController controller = new HistoryController();
            RecordHistoryRepository histories = mock(RecordHistoryRepository.class);
            LiveMsgRepository messages = mock(LiveMsgRepository.class);
            HistoryMsgQueueCleanupService cleanup = mock(HistoryMsgQueueCleanupService.class);
            HistoryMsgRetryService retry = mock(HistoryMsgRetryService.class);
            ReflectionTestUtils.setField(controller, "historyRepository", histories);
            ReflectionTestUtils.setField(controller, "msgRepository", messages);
            ReflectionTestUtils.setField(controller, "msgQueueCleanupService", cleanup);
            ReflectionTestUtils.setField(controller, "msgRetryService", retry);
            RecordHistory history = new RecordHistory();
            history.setId(7L);
            history.setPublish(submitted);
            history.setBvId(submitted ? "BV1TEST" : null);
            history.setCode(-10);
            when(histories.findById(7L)).thenReturn(Optional.of(history));
            assertEquals("warning", controller.deleteMsg(7L).get("type"));
            assertEquals("warning", controller.reloadMsg(7L, false, false).get("type"));
            assertEquals("warning", controller.abandonMsgQueue(7L, Map.of()).get("type"));
            assertEquals("warning", controller.retryFailedDanmaku(7L, Map.of()).get("type"));
            assertEquals("warning", controller.forceRetryFailedDanmaku(7L, Map.of()).get("type"));
            assertEquals(false, controller.retryUnknownDanmaku(7L, 9L, Map.of("confirmedNotSent", true)).get("success"));
            assertEquals("warning", controller.abandonMsgQueueBatch(Map.of("ids", List.of(7L))).get("type"));
            verifyNoInteractions(messages, cleanup, retry);
            verify(histories, never()).save(any());
            assertTrue(history.getCode() == -10);
        }
    }

    @Test
    void acceptedPublishedHistoryCanDeleteItsOwnRecordsWithoutChangingHistory() {
        HistoryController controller = new HistoryController();
        RecordHistoryRepository histories = mock(RecordHistoryRepository.class);
        LiveMsgRepository messages = mock(LiveMsgRepository.class);
        RecordHistoryPartRepository parts = mock(RecordHistoryPartRepository.class);
        RecordRoomRepository rooms = mock(RecordRoomRepository.class);
        PublishAccountScheduler scheduler = mock(PublishAccountScheduler.class);
        ReflectionTestUtils.setField(controller, "historyRepository", histories);
        ReflectionTestUtils.setField(controller, "msgRepository", messages);
        ReflectionTestUtils.setField(controller, "partRepository", parts);
        ReflectionTestUtils.setField(controller, "roomRepository", rooms);
        ReflectionTestUtils.setField(controller, "publishAccountScheduler", scheduler);
        RecordHistory history = new RecordHistory();
        history.setId(7L);
        history.setPublish(true);
        history.setBvId("BV1TEST");
        history.setCode(0);
        when(histories.findById(7L)).thenReturn(Optional.of(history));
        when(messages.countByBvid("BV1TEST")).thenReturn(2);
        assertEquals("success", controller.deleteMsg(7L).get("type"));
        verify(messages).queryByBvid("BV1TEST");
        verify(messages).deleteAll(anyList());
        verify(histories, never()).save(any());
        assertEquals(0, history.getMsgCount());
    }
}
