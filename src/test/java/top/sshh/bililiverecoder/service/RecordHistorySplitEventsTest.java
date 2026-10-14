package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.entity.blrec.*;
import top.sshh.bililiverecoder.lifecycle.ShutdownState;
import top.sshh.bililiverecoder.notification.LiveNotificationDurationService;
import top.sshh.bililiverecoder.notification.NotificationEventPublisher;
import top.sshh.bililiverecoder.repo.*;
import top.sshh.bililiverecoder.service.impl.*;
import top.sshh.bililiverecoder.service.blrec.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = {
        "spring.datasource.hikari.jdbc-url=jdbc:h2:mem:split-events;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
        "record.work-path=${java.io.tmpdir}/split-events"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({RecordHistorySplitService.class, RecordHistoryMergeService.class, RecordHistoryStateService.class,
        RecordEventFileOpenService.class, RecordEventFileClosedService.class, RecordEventRecordEndService.class,
        RecordEventRecordStartedService.class, RecordEventStreamStartService.class, RecordEventStreamEndService.class,
        BlrecRecordingStartedEventService.class, BlrecVideoFileCreatedEventService.class, BlrecVideoFileCompletedEventService.class})
class RecordHistorySplitEventsTest {
    @Autowired RecordHistoryRepository histories;
    @Autowired RecordHistoryPartRepository parts;
    @Autowired RecordRoomRepository rooms;
    @Autowired RecordHistorySplitService split;
    @Autowired RecordEventFileOpenService opens;
    @Autowired RecordEventFileClosedService closes;
    @Autowired RecordEventRecordEndService ends;
    @Autowired RecordEventRecordStartedService starts;
    @Autowired RecordEventStreamStartService streamStarts;
    @Autowired RecordEventStreamEndService streamEnds;
    @Autowired BlrecRecordingStartedEventService nativeStarts;
    @Autowired BlrecVideoFileCreatedEventService nativeOpens;
    @Autowired BlrecVideoFileCompletedEventService nativeCloses;
    @MockBean SystemConfigService configs;
    @MockBean PartPreviewService preview;
    @MockBean PartFileLocationService locations;
    @MockBean RecordPartPathService paths;
    @MockBean StatsAggregationService stats;
    @MockBean UploadServiceFactory uploads;
    @MockBean PartFileOperationService operations;
    @MockBean PartFileCleanupPolicy cleanup;
    @MockBean ShutdownState shutdown;
    @MockBean RecordPartRecordingStateService recordingState;
    @MockBean LiveNotificationDurationService liveDuration;
    @MockBean NotificationEventPublisher notifications;
    @MockBean(name = "taskExecutor") TaskExecutor executor;
    private LocalDateTime start;
    private RecordRoom room;
    private RecordHistory history;

    @BeforeEach
    void setup() {
        parts.deleteAll(); histories.deleteAll(); rooms.deleteAll();
        start = LocalDateTime.now().minusHours(3);
        when(configs.getAllConfigsMap()).thenReturn(Map.of(SystemConfigService.KEY_SPLIT_SIZE_GB, "10"));
        when(paths.resolveWebhookPath(anyString())).thenAnswer(call -> call.getArgument(0));
        when(paths.sameFile(anyString(), anyString())).thenAnswer(call -> Objects.equals(call.getArgument(0), call.getArgument(1)));
        room = new RecordRoom();
        room.setRoomId("100"); room.setSessionId("session");
        room.setRecording(true); room.setStreaming(true); room.setUpload(true);
        rooms.saveLiveState(room);
        history = new RecordHistory();
        history.setRoomId("100"); history.setSessionId("session");
        history.setStartTime(start); history.setEndTime(start);
        history.setRecording(true); history.setStreaming(true); history.setUpload(true);
        history.setEventId(UUID.randomUUID().toString());
        split.initializeNew(history);
        histories.save(history);
        room.setHistoryId(history.getId()); room.setLiveGroup(history.getSplitGroup()); room.setLiveEventAt(start); rooms.saveLiveState(room);
    }

    private Date date(LocalDateTime time) { return Date.from(time.atZone(ZoneId.systemDefault()).toInstant()); }

    private RecordEventDTO event(String file, int index, double size) {
        RecordEventDTO event = new RecordEventDTO();
        event.setEventId(UUID.randomUUID().toString());
        RecordEventData data = new RecordEventData();
        data.setRoomId("100"); data.setSessionId("session"); data.setRelativePath(file);
        data.setTitle("直播"); data.setName("主播"); data.setRecording(true); data.setStreaming(true);
        data.setFileOpenTime(date(start.plusHours(index)));
        data.setFileCloseTime(date(start.plusHours(index).plusMinutes(30)));
        data.setDuration(1800); data.setFileSize((long) (size * 1073741824L));
        event.setEventData(data);
        return event;
    }

    private RecordEventDTO endedEvent(String file, int index, double size) {
        RecordEventDTO event = event(file, index, size);
        event.getEventData().setRecording(false); event.getEventData().setStreaming(false);
        return event;
    }

    @Test
    void openingNextBeforeClosingPreviousMovesTailAndKeepsRecording() {
        opens.processing(event("missing-p1.flv", 0, 0));
        opens.processing(event("missing-p2.flv", 1, 0));
        closes.processing(event("missing-p2.flv", 1, 1));
        RecordHistoryPart tail = parts.findByFilePath("missing-p2.flv");
        assertFalse(split.canUpload(tail));
        closes.processing(event("missing-p1.flv", 0, 10));
        RecordHistory sealed = histories.findById(history.getId()).orElseThrow();
        RecordHistory next = histories.findBySplitParentId(history.getId());
        assertTrue(sealed.isSplitClosed());
        assertEquals(next.getId(), parts.findByFilePath("missing-p2.flv").getHistoryId());
        assertTrue(split.canUpload(parts.findByFilePath("missing-p2.flv")));
        assertTrue(rooms.findByRoomId("100").isRecording());
        assertEquals(next.getId(), rooms.findByRoomId("100").getHistoryId());
        verifyNoInteractions(uploads);
    }

    @Test
    void missingOpeningForNextPartStillCreatesSuccessorAndDuplicateCloseDoesNotCreateAnother() {
        closes.processing(event("missing-p1.flv", 0, 10));
        assertNull(histories.findBySplitParentId(history.getId()));
        closes.processing(event("missing-p2.flv", 1, 1));
        RecordHistory next = histories.findBySplitParentId(history.getId());
        assertNotNull(next);
        closes.processing(event("missing-p2.flv", 1, 1));
        assertEquals(2, histories.count());
        assertEquals(next.getId(), parts.findByFilePath("missing-p2.flv").getHistoryId());
    }

    @Test
    void simultaneousRepeatedCloseProducesOneBoundaryAndSuccessor() {
        opens.processing(event("missing-p1.flv", 0, 0));
        opens.processing(event("missing-p2.flv", 1, 0));
        CompletableFuture<?>[] tasks = new CompletableFuture<?>[4];
        for (int i = 0; i < tasks.length; i++) tasks[i] = CompletableFuture.runAsync(() -> closes.processing(event("missing-p1.flv", 0, 10)));
        CompletableFuture.allOf(tasks).join();
        assertEquals(2, histories.count());
        assertNotNull(histories.findBySplitParentId(history.getId()));
        assertEquals(2, parts.count());
    }

    @Test
    void sessionEndStopsRoomWithoutOverwritingThresholdBoundaryTime() {
        closes.processing(event("missing-p1.flv", 0, 10));
        LocalDateTime boundary = histories.findById(history.getId()).orElseThrow().getEndTime();
        ends.processing(endedEvent("missing-p1.flv", 0, 10));
        assertFalse(rooms.findByRoomId("100").isRecording());
        assertEquals(boundary, histories.findById(history.getId()).orElseThrow().getEndTime());
        assertNull(histories.findBySplitParentId(history.getId()));
    }

    @Test
    void changedSessionWithinMergeWindowContinuesSplitGroupEvenWithoutSessionEnd() {
        closes.processing(event("boundary.flv", 0, 10));
        streamEnds.processing(event("boundary.flv", 0, 10));
        assertTrue(rooms.findByRoomId("100").isRecording());
        RecordEventDTO next = event("next-live.flv", 3, 0);
        next.getEventData().setSessionId("next-session");
        starts.processing(next);
        opens.processing(next);
        assertSplitSuccessor("next-live.flv");
    }

    @Test
    void streamStartBeforeSessionStartUsesSameTimeBasedSplitGroup() {
        closes.processing(event("boundary.flv", 0, 10));
        ends.processing(endedEvent("boundary.flv", 0, 10));
        assertFalse(rooms.findByRoomId("100").isRecording());
        RecordEventDTO next = event("next-live.flv", 3, 0);
        next.getEventData().setSessionId("next-session");
        streamStarts.processing(next);
        assertTrue(rooms.findByRoomId("100").isRecording());
        assertEquals("next-session", rooms.findByRoomId("100").getSessionId());
        starts.processing(next);
        opens.processing(next);
        assertSplitSuccessor("next-live.flv");
    }

    @Test
    void repeatedStartInSameSessionStillCreatesSecondSplitSegment() {
        closes.processing(event("boundary.flv", 0, 10));
        RecordEventDTO next = event("same-live.flv", 1, 0);
        starts.processing(next);
        assertEquals(history.getId(), rooms.findByRoomId("100").getHistoryId());
        assertEquals(1, histories.count());
        opens.processing(next);
        assertSplitSuccessor("same-live.flv");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void missingSessionStillUsesLocalMergeWindow(String sessionId) {
        closes.processing(event("boundary.flv", 0, 10));
        RecordEventDTO next = event("unknown-session.flv", 3, 0);
        next.getEventData().setSessionId(sessionId);
        starts.processing(next);
        opens.processing(next);
        assertSplitSuccessor("unknown-session.flv");
    }

    @Test
    void blrecChangedLiveStartWithinLocalWindowContinuesSplitGroup() {
        long previousLiveStart = start.atZone(ZoneId.systemDefault()).toEpochSecond();
        setBlrecSession(previousLiveStart);
        closes.processing(blrecBoundaryEvent());
        BlrecEventDTO next = nativeStartEvent(previousLiveStart + 10800);
        nativeStarts.processing(next);
        nativeOpens.processing(nativeEvent("native-next-live.flv", 3));
        assertSplitSuccessor("native-next-live.flv");
    }

    @Test
    void blrecRepeatedStartInSameLiveStillCreatesSecondSplitSegment() {
        long liveStart = start.atZone(ZoneId.systemDefault()).toEpochSecond();
        setBlrecSession(liveStart);
        closes.processing(blrecBoundaryEvent());
        nativeStarts.processing(nativeStartEvent(liveStart));
        assertEquals(history.getId(), rooms.findByRoomId("100").getHistoryId());
        assertEquals(1, histories.count());
        nativeOpens.processing(nativeEvent("native-same-live.flv", 1));
        assertSplitSuccessor("native-same-live.flv");
    }

    @Test
    void nonSplitHistoryStillMergesShortSessionResume() {
        closes.processing(event("unfinished.flv", 0, 1));
        ends.processing(endedEvent("unfinished.flv", 0, 1));
        RecordEventDTO next = event("resumed.flv", 3, 0);
        next.getEventData().setSessionId("resumed-session");
        starts.processing(next);
        opens.processing(next);
        assertEquals(history.getId(), parts.findByFilePath("resumed.flv").getHistoryId());
        assertEquals(1, histories.count());
        assertEquals(history.getSplitGroup(), histories.findById(history.getId()).orElseThrow().getSplitGroup());
    }

    @ParameterizedTest
    @ValueSource(strings = {"session", "changed-session"})
    void outsideLocalMergeWindowStartsIndependentHistoryRegardlessOfSessionId(String sessionId) {
        closes.processing(event("boundary.flv", 0, 10));
        room = rooms.findByRoomId("100");
        room.setLiveEndedAt(LocalDateTime.now().minusMinutes(21));
        rooms.saveLiveState(room);
        RecordEventDTO next = event("later-live.flv", 3, 0);
        next.getEventData().setSessionId(sessionId);
        streamStarts.processing(next);
        starts.processing(next);
        opens.processing(next);
        assertIndependentHistory("later-live.flv", sessionId);
    }

    @Test
    void oldThresholdBoundaryDoesNotEndAnOngoingLiveGroup() {
        closes.processing(event("boundary.flv", 0, 10));
        RecordEventDTO next = event("ongoing.flv", 3, 0);
        next.getEventData().setSessionId("recorder-restarted");
        starts.processing(next);
        opens.processing(next);
        assertSplitSuccessor("ongoing.flv");
    }

    @Test
    void publishedThresholdHistoryCanContinueItsGroupWithoutBeingReopened() {
        closes.processing(event("boundary.flv", 0, 10));
        RecordHistory sealed = histories.findById(history.getId()).orElseThrow();
        sealed.setPublish(true); sealed.setBvId("BVpublished"); histories.save(sealed);
        ends.processing(endedEvent("boundary.flv", 0, 10));
        RecordEventDTO next = event("continued.flv", 3, 0);
        next.getEventData().setSessionId("resumed-session");
        starts.processing(next); opens.processing(next);
        assertSplitSuccessor("continued.flv");
        sealed = histories.findById(history.getId()).orElseThrow();
        assertTrue(sealed.isPublish()); assertFalse(sealed.isRecording());
        assertEquals("BVpublished", sealed.getBvId());
    }

    @Test
    void fileOpeningWithoutSessionStartedUsesLocalMergeWindow() {
        closes.processing(event("boundary.flv", 0, 10));
        ends.processing(endedEvent("boundary.flv", 0, 10));
        RecordEventDTO next = event("opening-only.flv", 3, 0);
        next.getEventData().setSessionId("new-recorder-id");
        opens.processing(next);
        assertSplitSuccessor("opening-only.flv");
    }

    @Test
    void blrecOutsideLocalMergeWindowStartsIndependentHistory() {
        long liveStart = start.atZone(ZoneId.systemDefault()).toEpochSecond();
        setBlrecSession(liveStart); closes.processing(blrecBoundaryEvent());
        room = rooms.findByRoomId("100"); room.setLiveEndedAt(LocalDateTime.now().minusMinutes(21)); rooms.saveLiveState(room);
        nativeStarts.processing(nativeStartEvent(liveStart));
        nativeOpens.processing(nativeEvent("native-later-live.flv", 3));
        assertIndependentHistory("native-later-live.flv", "blrec:100:" + liveStart);
    }

    @Test
    void lateOldSessionEndDoesNotStopResumedRecordingInSameHistory() {
        closes.processing(event("unfinished.flv", 0, 1));
        ends.processing(endedEvent("unfinished.flv", 0, 1));
        RecordEventDTO next = event("resumed.flv", 3, 0);
        next.getEventData().setSessionId("new-session");
        starts.processing(next); opens.processing(next);
        ends.processing(endedEvent("unfinished.flv", 0, 1));
        assertTrue(rooms.findByRoomId("100").isRecording());
        assertTrue(histories.findById(history.getId()).orElseThrow().isRecording());
        assertNull(rooms.findByRoomId("100").getLiveEndedAt());
    }

    @Test
    void lateUnregisteredFileCloseStillCompletesOriginalPartAfterShortResume() {
        closes.processing(event("unfinished.flv", 0, 1));
        ends.processing(endedEvent("unfinished.flv", 0, 1));
        RecordEventDTO next = event("resumed.flv", 3, 0);
        next.setEventTimestamp(date(LocalDateTime.now().minusMinutes(5)));
        next.getEventData().setSessionId("new-session");
        starts.processing(next); opens.processing(next);
        RecordEventDTO late = event("old-unregistered.flv", 1, 1);
        late.setEventType(RecordEventType.FileClosed);
        late.setEventTimestamp(date(LocalDateTime.now().minusMinutes(6)));
        closes.processing(late);
        assertNotNull(parts.findByFilePath("old-unregistered.flv"));
        assertEquals(history.getId(), parts.findByFilePath("old-unregistered.flv").getHistoryId());
        assertTrue(histories.findById(history.getId()).orElseThrow().isRecording());
        assertTrue(rooms.findByRoomId("100").isRecording());
    }

    @Test
    void changedEndIdStillClosesCurrentRoomAndStartsMergeWindow() {
        RecordEventDTO ended = endedEvent("end.flv", 0, 0);
        ended.getEventData().setSessionId("changed-end-id");
        ends.processing(ended);
        assertFalse(rooms.findByRoomId("100").isRecording());
        assertNotNull(rooms.findByRoomId("100").getLiveEndedAt());
    }

    @Test
    void noFileLiveDoesNotReusePublishedNonSplitHistoryPointer() {
        history.setPublish(true); history.setRecording(false); history.setStreaming(false);
        histories.save(history);
        LocalDateTime oldEnd = histories.findById(history.getId()).orElseThrow().getEndTime();
        room.setRecording(false); room.setStreaming(false); room.setLiveEndedAt(LocalDateTime.now().minusMinutes(1)); rooms.saveLiveState(room);
        RecordEventDTO next = event("encrypted.flv", 3, 0);
        next.getEventData().setRecording(false); next.getEventData().setSessionId("encrypted-session");
        streamStarts.processing(next);
        assertEquals(-1L, rooms.findByRoomId("100").getHistoryId());
        streamEnds.processing(next);
        assertEquals(oldEnd, histories.findById(history.getId()).orElseThrow().getEndTime());
        assertFalse(rooms.findByRoomId("100").isStreaming());
    }

    @Test
    void missingRoomPointerRecoversLatestHistoryOnlyWithinLocalGroup() {
        room.setHistoryId(-1L); rooms.saveLiveState(room);
        RecordEventDTO next = event("recovered.flv", 3, 0);
        next.getEventData().setSessionId("restarted-recorder");
        starts.processing(next); opens.processing(next);
        assertEquals(history.getId(), rooms.findByRoomId("100").getHistoryId());
        assertEquals(history.getId(), parts.findByFilePath("recovered.flv").getHistoryId());
        assertEquals(1, histories.count());
    }

    @Test
    void savingOldSettingsSnapshotCannotOverwriteLiveClock() {
        RecordRoom stale = rooms.findByRoomId("100");
        RecordRoom updated = rooms.findByRoomId("100");
        LocalDateTime endedAt = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        updated.setLiveGroup("fresh-group"); updated.setLiveEndedAt(endedAt); rooms.saveLiveState(updated);
        stale.setTitle("updated-settings"); rooms.save(stale);
        RecordRoom saved = rooms.findByRoomId("100");
        assertEquals("updated-settings", saved.getTitle());
        assertEquals("fresh-group", saved.getLiveGroup());
        assertEquals(endedAt, saved.getLiveEndedAt());
    }

    @Test
    void liveClockWriteFailureRollsBackRoomStateWrite() {
        RecordRoom current = rooms.findByRoomId("100");
        String oldTitle = current.getTitle();
        current.setTitle("must-rollback"); current.setLiveGroup("x".repeat(256));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> rooms.saveLiveState(current));
        assertEquals(oldTitle, rooms.findByRoomId("100").getTitle());
        assertEquals(history.getSplitGroup(), rooms.findByRoomId("100").getLiveGroup());
    }

    @Test
    void recorderRestartWhileLiveDoesNotStartOfflineMergeWindow() {
        closes.processing(event("boundary.flv", 0, 10));
        RecordEventDTO recordingStopped = event("boundary.flv", 0, 10);
        recordingStopped.getEventData().setRecording(false);
        ends.processing(recordingStopped);
        assertFalse(rooms.findByRoomId("100").isRecording());
        assertTrue(rooms.findByRoomId("100").isStreaming());
        assertNull(rooms.findByRoomId("100").getLiveEndedAt());
        RecordEventDTO next = event("recorder-restarted.flv", 3, 0);
        next.getEventData().setSessionId("restarted-id"); starts.processing(next); opens.processing(next);
        assertSplitSuccessor("recorder-restarted.flv");
    }

    @Test
    void stoppedRecorderKeepsOrdinaryHistoryStreamingUntilActualStreamEnd() {
        RecordEventDTO recordingStopped = event("recording-stopped.flv", 0, 0);
        recordingStopped.getEventData().setRecording(false);
        ends.processing(recordingStopped);
        assertFalse(histories.findById(history.getId()).orElseThrow().isRecording());
        assertTrue(histories.findById(history.getId()).orElseThrow().isStreaming());
        streamEnds.processing(recordingStopped);
        assertFalse(histories.findById(history.getId()).orElseThrow().isStreaming());
        assertNotNull(rooms.findByRoomId("100").getLiveEndedAt());
    }

    private void assertIndependentHistory(String file, String sessionId) {
        RecordHistory next = histories.findById(rooms.findByRoomId("100").getHistoryId()).orElseThrow();
        RecordHistory previous = histories.findById(history.getId()).orElseThrow();
        assertNotEquals(previous.getId(), next.getId());
        assertEquals(1, next.getSplitSequence());
        assertNotEquals(previous.getSplitGroup(), next.getSplitGroup());
        assertNull(next.getSplitParentId());
        assertNull(histories.findBySplitParentId(previous.getId()));
        assertTrue(previous.isSplitClosed());
        assertTrue(next.isRecording());
        assertEquals(sessionId, next.getSessionId());
        assertEquals(next.getId(), parts.findByFilePath(file).getHistoryId());
        assertEquals(previous.getId(), parts.findByFilePath("boundary.flv").getHistoryId());
        assertEquals(2, histories.count());
    }

    private void assertSplitSuccessor(String file) {
        RecordHistory next = histories.findBySplitParentId(history.getId());
        assertNotNull(next);
        assertEquals(2, next.getSplitSequence());
        assertEquals(history.getSplitGroup(), next.getSplitGroup());
        String currentSession = rooms.findByRoomId("100").getSessionId();
        assertEquals(currentSession == null ? history.getSessionId() : currentSession, next.getSessionId());
        assertEquals(next.getId(), parts.findByFilePath(file).getHistoryId());
        assertEquals(next.getId(), rooms.findByRoomId("100").getHistoryId());
    }

    private void setBlrecSession(long liveStart) {
        String session = "blrec:100:" + liveStart;
        history.setSessionId(session); histories.save(history);
        room.setSessionId(session); rooms.saveLiveState(room);
    }

    private RecordEventDTO blrecBoundaryEvent() {
        RecordEventDTO boundary = event("boundary.flv", 0, 10);
        boundary.getEventData().setSessionId(history.getSessionId());
        return boundary;
    }

    private BlrecEventDTO nativeStartEvent(long liveStart) {
        BlrecEventDTO next = nativeEvent(null, 3);
        next.getData().getRoomInfo().setLiveStartTime(liveStart);
        next.getData().getRoomInfo().setLiveStatus(1);
        next.getData().getRoomInfo().setTitle("直播");
        return next;
    }

    @Test
    void nativeBlrecUsesIndividualMediaDurationAndPreservesNewPartOwnership() {
        when(configs.getAllConfigsMap()).thenReturn(Map.of(SystemConfigService.KEY_SPLIT_DURATION_MINUTES, "1"));
        history.setSplitSizeBytes(0L); history.setSplitDurationSeconds(60L); histories.save(history);
        when(preview.probeDuration(anyLong())).thenReturn(120d);
        nativeOpens.processing(nativeEvent("native-p1.flv", 0));
        nativeCloses.processing(nativeEvent("native-p1.flv", 0));
        assertTrue(histories.findById(history.getId()).orElseThrow().isSplitClosed());
        assertEquals(120f, parts.findByFilePath("native-p1.flv").getDuration());
        nativeOpens.processing(nativeEvent("native-p2.flv", 1));
        assertEquals(histories.findBySplitParentId(history.getId()).getId(), parts.findByFilePath("native-p2.flv").getHistoryId());
        assertEquals("BLREC", rooms.findByRoomId("100").getWebhookSource());
    }

    private BlrecEventDTO nativeEvent(String file, int index) {
        BlrecEventDTO event = new BlrecEventDTO();
        event.setId(UUID.randomUUID().toString()); event.setDate(date(start.plusHours(index).plusMinutes(2)));
        BlrecDataDTO data = new BlrecDataDTO(); data.setRoomId("100"); data.setPath(file);
        BlrecRoomInfoDTO info = new BlrecRoomInfoDTO(); info.setRoomId("100"); data.setRoomInfo(info);
        event.setData(data);
        return event;
    }

    @Test
    void missingDurationUsesMediaProbeInsteadOfDelayedNotificationElapsedTime() {
        history.setSplitSizeBytes(0L);
        history.setSplitDurationSeconds(60L);
        histories.save(history);
        when(preview.probeDuration(anyLong())).thenReturn(30d);
        RecordEventDTO first = event("duration-p1.flv", 0, 1);
        first.getEventData().setDuration(0);
        first.getEventData().setFileCloseTime(date(start.plusHours(5)));
        closes.processing(first);
        assertFalse(histories.findById(history.getId()).orElseThrow().isSplitClosed());
        assertEquals(30f, parts.findByFilePath("duration-p1.flv").getDuration());
        RecordEventDTO second = event("duration-p2.flv", 1, 1);
        second.getEventData().setDuration(0);
        closes.processing(second);
        assertTrue(histories.findById(history.getId()).orElseThrow().isSplitClosed());
    }

    @Test
    void sizeBoundarySurvivesMissingDurationAndUploadWaitsUntilProbeSucceeds() {
        RecordEventDTO first = event("duration-missing.flv", 0, 10);
        first.getEventData().setDuration(0);
        closes.processing(first);
        RecordHistoryPart part = parts.findByFilePath("duration-missing.flv");
        assertTrue(histories.findById(history.getId()).orElseThrow().isSplitClosed());
        assertFalse(split.canUpload(part));
        assertEquals(0, part.getUploadRetryCount());
        when(preview.probeDuration(part.getId())).thenReturn(1800d);
        assertTrue(split.canUpload(part));
        assertEquals(part.getId(), histories.findById(history.getId()).orElseThrow().getSplitBoundaryPartId());
    }
}
