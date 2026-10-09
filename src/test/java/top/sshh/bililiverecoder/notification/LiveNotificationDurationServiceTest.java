package top.sshh.bililiverecoder.notification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.bililiverecoder.entity.RecordEventDTO;
import top.sshh.bililiverecoder.entity.RecordEventData;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.service.RecordHistoryStateService;
import top.sshh.bililiverecoder.service.impl.RecordEventStreamEndService;
import top.sshh.bililiverecoder.service.impl.RecordEventStreamStartService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LiveNotificationDurationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");
    private static final Instant START = Instant.parse("2026-10-08T12:00:00Z");
    private final LiveNotificationDurationService service = tracker();

    private LiveNotificationDurationService tracker() {
        return new LiveNotificationDurationService(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private RecordEventDTO event(String sessionId, Instant at, String id) {
        RecordEventDTO event = new RecordEventDTO();
        event.setEventId(id);
        event.setEventTimestamp(Date.from(at));
        RecordEventData data = new RecordEventData();
        data.setRoomId("100");
        data.setSessionId(sessionId);
        event.setEventData(data);
        return event;
    }

    @Test
    void webhookOnlyLiveWithoutFilesFreezesAtEndAndNextLiveStartsFresh() {
        service.streamStarted(event("encrypted", START, "start-1"));
        service.streamEnded(event("encrypted", START.plusSeconds(3600), "end-1"));

        assertEquals("1小时0分0秒", service.observeRoom("100", false, null, NOW, true));
        service.notificationConsumed("100", NOW);
        service.streamStarted(event("normal", NOW.minusSeconds(1800), "start-2"));
        service.streamEnded(event("normal", NOW, "end-2"));

        assertEquals("30分0秒", service.observeRoom("100", false, null, NOW, true));
    }

    @Test
    void noHistoryStillClosesTimingBeforeStreamEndReturns() {
        RecordRoomRepository rooms = mock(RecordRoomRepository.class);
        RecordHistoryStateService historyState = mock(RecordHistoryStateService.class);
        NotificationEventPublisher publisher = mock(NotificationEventPublisher.class);
        RecordRoom room = new RecordRoom();
        room.setRoomId("100");
        when(rooms.findByRoomId("100")).thenReturn(room);
        when(rooms.save(any())).thenAnswer(call -> call.getArgument(0));

        RecordEventStreamStartService startHandler = new RecordEventStreamStartService();
        ReflectionTestUtils.setField(startHandler, "roomRepository", rooms);
        ReflectionTestUtils.setField(startHandler, "notificationEventPublisher", publisher);
        ReflectionTestUtils.setField(startHandler, "liveNotificationDurationService", service);
        startHandler.processing(event("encrypted", START, "start"));

        RecordEventStreamEndService endHandler = new RecordEventStreamEndService();
        ReflectionTestUtils.setField(endHandler, "roomRepository", rooms);
        ReflectionTestUtils.setField(endHandler, "historyStateService", historyState);
        ReflectionTestUtils.setField(endHandler, "liveNotificationDurationService", service);
        endHandler.processing(event("encrypted", START.plusSeconds(3600), "end"));

        assertEquals("1小时0分0秒", service.observeRoom("100", false, null, NOW, true));
        verify(publisher).publish(any(), same(room));
        verify(historyState, never()).markStreamEnded(any());
    }

    @Test
    void missedWebhookStartUsesPlatformStartAndMarksPollingEndAsApproximate() {
        service.observeRoom("100", true, "2026-10-08 20:00:00", START.plusSeconds(7200), null);

        assertEquals("约3小时0分0秒", service.observeRoom("100", false, null, START.plusSeconds(10800), true));
    }

    @Test
    void restartDuringLiveRecoversOriginalStartFromPlatform() {
        service.streamStarted(event("live", START, "start"));
        LiveNotificationDurationService restarted = tracker();
        restarted.observeRoom("100", true, "2026-10-08 20:00:00", START.plusSeconds(7200), null);
        restarted.streamEnded(event("live", START.plusSeconds(10800), "end"));

        assertEquals("3小时0分0秒", restarted.observeRoom("100", false, null, NOW, true));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "0000-00-00 00:00:00", "invalid", "2026-02-30 12:00:00", "2026-10-10 20:00:00"})
    void invalidPlatformStartWithoutWebhookRemainsUnknown(String liveTime) {
        service.observeRoom("100", true, liveTime, START, null);

        assertNull(service.observeRoom("100", false, null, NOW, true));
    }

    @Test
    void validPlatformTimeCorrectsWebhookStartAndSurvivesMissingLaterSamples() {
        service.streamStarted(event("live", START.plusSeconds(600), "start"));
        service.observeRoom("100", true, "2026-10-08 20:00:00", START.plusSeconds(1200), null);
        service.observeRoom("100", true, null, START.plusSeconds(1800), true);
        service.streamEnded(event("live", START.plusSeconds(3600), "end"));

        assertEquals("1小时0分0秒", service.observeRoom("100", false, null, NOW, true));
    }

    @Test
    void duplicateStartDoesNotResetTiming() {
        service.streamStarted(event("live", START, "start"));
        service.streamStarted(event("live", START.plusSeconds(1800), "duplicate"));
        service.streamEnded(event("live", START.plusSeconds(3600), "end"));

        assertEquals("1小时0分0秒", service.observeRoom("100", false, null, NOW, true));
    }

    @Test
    void delayedStartAfterLiveWasAlreadyObservedPreservesPlatformStart() {
        service.observeRoom("100", true, "2026-10-08 20:00:00", START.plusSeconds(1800), null);
        service.streamStarted(event("live", START.plusSeconds(10), "delayed-start"));
        service.streamEnded(event("live", START.plusSeconds(3600), "end"));

        assertEquals("1小时0分0秒", service.observeRoom("100", false, null, NOW, true));
    }

    @Test
    void lateStartCanSupplyMissingStartAfterObservationWithInvalidPlatformTime() {
        service.observeRoom("100", true, null, START.plusSeconds(1800), null);
        service.streamStarted(event("live", START, "delayed-start"));
        service.streamEnded(event("live", START.plusSeconds(3600), "end"));

        assertEquals("1小时0分0秒", service.observeRoom("100", false, null, NOW, true));
    }

    @Test
    void lateOldStartAndEndCannotChangeNextLive() {
        service.streamStarted(event("old", START, "old-start"));
        service.streamEnded(event("old", START.plusSeconds(3600), "old-end"));
        service.streamStarted(event("new", START.plusSeconds(7200), "new-start"));
        service.streamStarted(event("old", START, "late-old-start"));
        service.streamEnded(event("old", START.plusSeconds(10800), "late-old-end"));
        service.streamEnded(event("new", START.plusSeconds(14400), "new-end"));

        assertEquals("2小时0分0秒", service.observeRoom("100", false, null, NOW, true));
    }

    @Test
    void staleLiveSampleCannotReopenWebhookEndedLive() {
        service.streamStarted(event("live", START, "start"));
        service.streamEnded(event("live", START.plusSeconds(3600), "end"));
        service.observeRoom("100", true, "2026-10-08 20:00:00", START.plusSeconds(7200), true);
        service.observeRoom("100", true, null, START.plusSeconds(10800), true);

        assertEquals("1小时0分0秒", service.observeRoom("100", false, null, NOW, true));
    }

    @Test
    void consumedTimingAndLateStartCannotLeakIntoUnknownNextLive() {
        service.streamStarted(event("old", START, "start"));
        service.streamEnded(event("old", START.plusSeconds(3600), "end"));
        service.notificationConsumed("100", START.plusSeconds(7200));
        service.streamStarted(event("old", START, "late-start"));
        service.observeRoom("100", true, null, START.plusSeconds(10800), false);

        assertNull(service.observeRoom("100", false, null, NOW, true));
    }

    @Test
    void platformStartChangeWithoutObservedOfflineBeginsNewLive() {
        service.observeRoom("100", true, "2026-10-08 20:00:00", START.plusSeconds(1800), null);
        service.observeRoom("100", true, "2026-10-08 23:00:00", START.plusSeconds(12600), true);
        service.streamEnded(event("new", START.plusSeconds(14400), "end"));

        assertEquals("1小时0分0秒", service.observeRoom("100", false, null, NOW, true));
    }

    @Test
    void oldPlatformStartCannotReplaceNextWebhooksStart() {
        service.observeRoom("100", true, "2026-10-08 20:00:00", START.plusSeconds(1800), null);
        service.streamEnded(event("old", START.plusSeconds(3600), "old-end"));
        service.streamStarted(event("new", START.plusSeconds(7200), "new-start"));
        service.observeRoom("100", true, "2026-10-08 20:00:00", START.plusSeconds(9000), true);
        service.streamEnded(event("new", START.plusSeconds(10800), "new-end"));

        assertEquals("1小时0分0秒", service.observeRoom("100", false, null, NOW, true));
    }

    @Test
    void preciseLateEndCanCorrectPollingEstimateBeforeNotification() {
        service.streamStarted(event("live", START, "start"));
        assertEquals("约1小时5分0秒", service.observeRoom("100", false, null, START.plusSeconds(3900), true));
        service.streamEnded(event("live", START.plusSeconds(3600), "end"));

        assertEquals("1小时0分0秒", service.observeRoom("100", false, null, NOW, false));
    }

    @Test
    void missingBrecTimestampUsesBlrecEventDate() {
        RecordEventDTO start = event("blrec", START, "start");
        start.setDate(start.getEventTimestamp());
        start.setEventTimestamp(null);
        service.streamStarted(start);
        service.streamEnded(event("blrec", START.plusSeconds(75), "end"));

        assertEquals("1分15秒", service.observeRoom("100", false, null, NOW, true));
    }

    @Test
    void futureWebhookTimestampUsesReceiptTimeAsEstimate() {
        RecordEventDTO start = event("live", NOW.plusSeconds(3600), "start");
        service.streamStarted(start);

        assertEquals("约1分0秒", service.observeRoom("100", false, null, NOW.plusSeconds(60), true));
    }

    @Test
    void roomsDoNotShareTiming() {
        service.streamStarted(event("live", START, "start"));
        service.observeRoom("200", true, "2026-10-08 23:00:00", START.plusSeconds(12600), null);

        assertEquals("约4小时0分0秒", service.observeRoom("100", false, null, START.plusSeconds(14400), true));
        assertEquals("约1小时0分0秒", service.observeRoom("200", false, null, START.plusSeconds(14400), true));
    }

    @Test
    void slowOfflineQueryStartedBeforeNewWebhookCannotEndNewLive() {
        service.streamStarted(event("live", START.plusSeconds(60), "start"));
        assertNull(service.observeRoom("100", false, null, START, true));
        service.notificationConsumed("100", START);
        service.streamEnded(event("live", START.plusSeconds(3660), "end"));

        assertEquals("1小时0分0秒", service.observeRoom("100", false, null, NOW, true));
    }

    @Test
    void missingTimestampStartAfterRecoveryCannotReplaceKnownPlatformStart() {
        service.observeRoom("100", true, "2026-10-08 20:00:00", START.plusSeconds(1800), null);
        RecordEventDTO delayed = event("live", START, "start");
        delayed.setEventTimestamp(null);
        service.streamStarted(delayed);
        service.streamEnded(event("live", NOW, "end"));

        assertEquals("24小时0分0秒", service.observeRoom("100", false, null, NOW, true));
    }

    @Test
    void newWebhookDuringMissingOfflineSampleRejectsOldPlatformStart() {
        service.streamStarted(event("old", START, "old-start"));
        service.observeRoom("100", true, "2026-10-08 20:00:00", START.plusSeconds(1800), null);
        service.streamStarted(event("new", START.plusSeconds(7200), "new-start"));
        service.observeRoom("100", true, "2026-10-08 20:00:00", START.plusSeconds(9000), true);
        service.streamEnded(event("new", START.plusSeconds(10800), "end"));

        assertEquals("1小时0分0秒", service.observeRoom("100", false, null, NOW, true));
    }
}
