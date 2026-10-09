package top.sshh.bililiverecoder.job;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.notification.NotificationEvent;
import top.sshh.bililiverecoder.notification.NotificationEventPublisher;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

class RoomStatusSyncJobTest {

    @Test
    void shouldOnlyPublishLiveEndedAfterObservedLiveState() {
        RoomStatusSyncJob job = new RoomStatusSyncJob();

        assertFalse(job.shouldPublishLiveEnded(null, false));
        assertFalse(job.shouldPublishLiveEnded(null, true));
        assertFalse(job.shouldPublishLiveEnded(false, false));
        assertFalse(job.shouldPublishLiveEnded(false, true));
        assertFalse(job.shouldPublishLiveEnded(true, true));
        assertTrue(job.shouldPublishLiveEnded(true, false));
    }

    @Test
    void notificationUsesLiveTimingWithoutReadingSplitOrPreviousHistory() {
        RoomStatusSyncJob job = new RoomStatusSyncJob();
        RecordHistoryRepository histories = mock(RecordHistoryRepository.class);
        NotificationEventPublisher publisher = mock(NotificationEventPublisher.class);
        ReflectionTestUtils.setField(job, "historyRepository", histories);
        ReflectionTestUtils.setField(job, "notificationEventPublisher", publisher);
        RecordRoom room = new RecordRoom();
        room.setRoomId("100");
        room.setHistoryId(999L);

        ReflectionTestUtils.invokeMethod(job, "publishLiveEnded", room, "6小时0分0秒");

        ArgumentCaptor<NotificationEvent> event = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(publisher).publish(event.capture(), same(room));
        assertEquals("6小时0分0秒", event.getValue().stringAttribute("durationText"));
        verifyNoInteractions(histories);
    }

    @Test
    void unknownLiveTimingDoesNotFallBackToPreviousHistory() {
        RoomStatusSyncJob job = new RoomStatusSyncJob();
        RecordHistoryRepository histories = mock(RecordHistoryRepository.class);
        NotificationEventPublisher publisher = mock(NotificationEventPublisher.class);
        ReflectionTestUtils.setField(job, "historyRepository", histories);
        ReflectionTestUtils.setField(job, "notificationEventPublisher", publisher);
        RecordRoom room = new RecordRoom();
        room.setRoomId("100");
        room.setHistoryId(999L);

        ReflectionTestUtils.invokeMethod(job, "publishLiveEnded", room, null);

        ArgumentCaptor<NotificationEvent> event = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(publisher).publish(event.capture(), same(room));
        assertNull(event.getValue().stringAttribute("durationText"));
        verifyNoInteractions(histories);
    }
}
