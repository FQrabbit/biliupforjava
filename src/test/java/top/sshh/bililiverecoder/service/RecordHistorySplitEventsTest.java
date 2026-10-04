package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
        BlrecVideoFileCreatedEventService.class, BlrecVideoFileCompletedEventService.class})
class RecordHistorySplitEventsTest {
    @Autowired RecordHistoryRepository histories;
    @Autowired RecordHistoryPartRepository parts;
    @Autowired RecordRoomRepository rooms;
    @Autowired RecordHistorySplitService split;
    @Autowired RecordEventFileOpenService opens;
    @Autowired RecordEventFileClosedService closes;
    @Autowired RecordEventRecordEndService ends;
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
        rooms.save(room);
        history = new RecordHistory();
        history.setRoomId("100"); history.setSessionId("session");
        history.setStartTime(start); history.setEndTime(start);
        history.setRecording(true); history.setStreaming(true); history.setUpload(true);
        history.setEventId(UUID.randomUUID().toString());
        split.initializeNew(history);
        histories.save(history);
        room.setHistoryId(history.getId()); rooms.save(room);
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
        ends.processing(event("missing-p1.flv", 0, 10));
        assertFalse(rooms.findByRoomId("100").isRecording());
        assertEquals(boundary, histories.findById(history.getId()).orElseThrow().getEndTime());
        assertNull(histories.findBySplitParentId(history.getId()));
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
