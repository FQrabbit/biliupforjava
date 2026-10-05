package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.task.TaskExecutor;
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.repo.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StatsUpdateServiceTest {
    @TempDir Path temporary;

    @Test
    void fatalWorkerErrorEntersLoggingPipelineAndReleasesWorkerGate() {
        var fixture = new Fixture();
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(StatsUpdateService.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        var failure = new LinkageError("native reflection unavailable");
        var time = new java.util.concurrent.atomic.AtomicLong();
        fixture.service.nanoTime = time::get;
        when(fixture.aggregation.manualTaskRunning()).thenThrow(failure);
        try {
            fixture.service.wake();
            assertSame(failure, assertThrows(LinkageError.class, fixture::execute));
            assertTrue(appender.list.stream().anyMatch(event ->
                    event.getLevel() == ch.qos.logback.classic.Level.ERROR
                            && event.getThrowableProxy() != null
                            && event.getThrowableProxy().getMessage().equals(failure.getMessage())));
            fixture.service.wake();
            assertNull(fixture.next.get());
            time.addAndGet(30_000_000_000L);
            fixture.service.wake();
            assertNotNull(fixture.next.get());
            verify(fixture.store, never()).complete(any());
            verify(fixture.store, never()).deleteHistory(anyLong());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void signatureIgnoresPollTimesAndDetectsExternalXmlAndMetadataSeparately() throws Exception {
        var fixture = new Fixture();
        var history = history(1L);
        var part = new RecordHistoryPart(); part.setId(2L); part.setHistoryId(1L);
        part.setStartTime(history.getStartTime()); part.setEndTime(history.getEndTime());
        Path xml = temporary.resolve("live.xml"); Files.writeString(xml, "<i/>");
        when(fixture.parts.findByHistoryIdOrderByStartTimeAsc(1L)).thenReturn(List.of(part));
        when(fixture.locations.inspectCompanionState(eq(2L), eq(".xml"), anyMap())).thenAnswer(invocation ->
                new PartFileLocationService.CompanionResolution(PartFileLocationService.CompanionState.AVAILABLE, xml, xml, null, null));
        var before = fixture.service.snapshot(history, null, new HashMap<>());
        history.setUpdateTime(LocalDateTime.now());
        var unchanged = fixture.service.snapshot(history, null, new HashMap<>());
        assertEquals(before.content(), unchanged.content()); assertEquals(before.metadata(), unchanged.metadata());
        history.setPublish(true);
        var metadata = fixture.service.snapshot(history, null, new HashMap<>());
        assertEquals(before.content(), metadata.content()); assertNotEquals(before.metadata(), metadata.metadata());
        Files.writeString(xml, "<i><d>changed</d></i>");
        var changed = fixture.service.snapshot(history, null, new HashMap<>());
        assertNotEquals(before.content(), changed.content());
        assertEquals(metadata.metadata(), changed.metadata());
    }

    @Test
    void scanUsesCursorBeyondTwoHundredAndDoesNotRestartUnfinishedCycle() {
        var fixture = new Fixture();
        var first = new ArrayList<RecordHistory>();
        for (long id = 1; id <= 200; id++) first.add(history(id));
        when(fixture.histories.findByIdGreaterThanOrderByIdAsc(eq(0L), any())).thenReturn(first);
        when(fixture.histories.findByIdGreaterThanOrderByIdAsc(eq(200L), any())).thenReturn(List.of(history(201L)));
        when(fixture.parts.findByHistoryIdOrderByStartTimeAsc(anyLong())).thenReturn(List.of());
        fixture.service.startScan(); fixture.execute();
        fixture.service.startScan(); fixture.execute();
        verify(fixture.store).observe(eq(201L), eq("r"), anyString(), anyString(), eq(false), eq(false));
        verify(fixture.histories, times(1)).findByIdGreaterThanOrderByIdAsc(eq(0L), any());
        verify(fixture.store).pruneDeleted();
    }

    @Test
    void maintenanceRejectionAndBusyAggregationKeepDurableTask() {
        var fixture = new Fixture();
        when(fixture.maintenance.isMaintenanceActive()).thenReturn(true);
        fixture.service.wake(); assertNull(fixture.next.get());
        when(fixture.maintenance.isMaintenanceActive()).thenReturn(false);
        var state = new StatsUpdateStore.State(1L,"r",1,0,1,1,false,null,null,null,null,0,null,false,0);
        when(fixture.store.due(200)).thenReturn(List.of(state));
        when(fixture.histories.findById(1L)).thenReturn(Optional.of(history(1L)));
        when(fixture.parts.findByHistoryIdOrderByStartTimeAsc(1L)).thenReturn(List.of());
        fixture.service.wake(); fixture.execute();
        verify(fixture.store).retry(eq(state), anyString());
        verify(fixture.store, never()).complete(any());
        TaskExecutor rejected = task -> { throw new org.springframework.core.task.TaskRejectedException("full"); };
        var service = fixture.withExecutor(rejected);
        assertDoesNotThrow(service::wake); assertDoesNotThrow(service::wake);
        verify(fixture.store, never()).deleteHistory(anyLong());
    }

    @Test
    void failedXmlAndImportedSnapshotsCannotOverwriteGoodStats() {
        var fixture = new Fixture();
        var state = new StatsUpdateStore.State(1L,"r",1,0,0,1,false,null,null,null,null,0,null,false,0);
        when(fixture.store.due(200)).thenReturn(List.of(state));
        when(fixture.histories.findById(1L)).thenReturn(Optional.of(history(1L)));
        var imported = new RoomLiveSessionStats(); imported.setImportedSnapshot(true);
        when(fixture.sessions.findByHistoryId(1L)).thenReturn(imported);
        fixture.service.wake(); fixture.execute();
        verify(fixture.store).observe(eq(1L), eq("r"), anyString(), anyString(), eq(true), eq(true));
        verify(fixture.aggregation, never()).updateQueuedHistory(any(), any(), anyBoolean(), anyBoolean(), anyBoolean());
    }

    @Test
    void scanLockFailureBacksOffAndResumesFromLastCompletedHistory() {
        var fixture = new Fixture();
        var time = new java.util.concurrent.atomic.AtomicLong();
        fixture.service.nanoTime = time::get;
        when(fixture.histories.findByIdGreaterThanOrderByIdAsc(eq(0L), any()))
                .thenReturn(List.of(history(1L), history(2L)));
        when(fixture.histories.findByIdGreaterThanOrderByIdAsc(eq(1L), any()))
                .thenReturn(List.of(history(2L)));
        doThrow(new org.springframework.dao.QueryTimeoutException("locked"))
                .doNothing().when(fixture.store).observe(eq(2L), anyString(), anyString(), anyString(), anyBoolean(), anyBoolean());
        fixture.service.startScan(); fixture.execute();
        for (int i = 0; i < 5; i++) { time.addAndGet(5_000_000_000L); fixture.service.wake(); }
        assertNull(fixture.next.get());
        verify(fixture.store, times(1)).observe(eq(1L), anyString(), anyString(), anyString(), anyBoolean(), anyBoolean());
        time.addAndGet(5_000_000_000L);
        fixture.service.wake(); fixture.execute();
        verify(fixture.store, times(2)).observe(eq(2L), anyString(), anyString(), anyString(), anyBoolean(), anyBoolean());
        verify(fixture.store).pruneDeleted();
    }

    @Test
    void priceChangesBatchQueriesAndDeduplicateAffectedHistories() {
        var fixture = new Fixture();
        var catalogs = new ArrayList<RoomLiveGiftCatalog>();
        for (int id = 1; id <= 131; id++) {
            var gift = new RoomLiveGiftCatalog(); gift.setGiftId(id); gift.setGiftName("gift" + id);
            catalogs.add(gift);
        }
        when(fixture.jdbc.queryForList(anyString(), eq(Long.class), (Object[]) any()))
                .thenReturn(List.of(1L, 2L));
        when(fixture.histories.findById(anyLong())).thenAnswer(i -> Optional.of(history(i.getArgument(0))));
        fixture.service.priceChanged(catalogs);
        verify(fixture.jdbc, times(2)).queryForList(anyString(), eq(Long.class), (Object[]) any());
        verify(fixture.store, times(1)).requestPrices(new TreeSet<>(List.of(1L, 2L)));
    }

    static RecordHistory history(Long id) {
        var history = new RecordHistory(); history.setId(id); history.setRoomId("r");
        history.setStartTime(LocalDateTime.of(2026,10,1,0,0)); history.setEndTime(history.getStartTime().plusHours(1));
        return history;
    }
    static class Fixture {
        final StatsUpdateStore store = mock(StatsUpdateStore.class);
        final RecordHistoryRepository histories = mock(RecordHistoryRepository.class);
        final RecordHistoryPartRepository parts = mock(RecordHistoryPartRepository.class);
        final RecordRoomRepository rooms = mock(RecordRoomRepository.class);
        final RoomLiveSessionStatsRepository sessions = mock(RoomLiveSessionStatsRepository.class);
        final PartFileLocationService locations = mock(PartFileLocationService.class);
        final StatsAggregationService aggregation = mock(StatsAggregationService.class);
        final DatabaseMaintenanceState maintenance = mock(DatabaseMaintenanceState.class);
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final AtomicReference<Runnable> next = new AtomicReference<>();
        final StatsUpdateService service = withExecutor(next::set);
        Fixture() {
            var room = new RecordRoom(); room.setRoomId("r");
            when(rooms.findByRoomId("r")).thenReturn(room);
        }
        StatsUpdateService withExecutor(TaskExecutor executor) {
            var factory = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
            factory.registerSingleton("aggregation", aggregation);
            return new StatsUpdateService(store,histories,parts,rooms,sessions,locations,
                    factory.getBeanProvider(StatsAggregationService.class),maintenance,executor,jdbc);
        }
        void execute() { next.getAndSet(null).run(); }
    }
}
