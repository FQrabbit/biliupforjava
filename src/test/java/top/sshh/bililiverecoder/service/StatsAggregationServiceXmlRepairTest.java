package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StatsAggregationServiceXmlRepairTest {
    StatsAggregationService service = new StatsAggregationService();
    RoomLiveEventXmlRepairService repair = mock(RoomLiveEventXmlRepairService.class);
    RoomLiveEventParseService parser = mock(RoomLiveEventParseService.class);
    RoomLiveEventXmlIssueService issues = mock(RoomLiveEventXmlIssueService.class);
    RecordHistoryPartRepository parts = mock(RecordHistoryPartRepository.class);
    RecordHistoryPart part = new RecordHistoryPart();

    @BeforeEach
    void setup() {
        part.setId(17L);
        when(parts.findById(17L)).thenReturn(Optional.of(part));
        ReflectionTestUtils.setField(service, "partRepository", parts);
        ReflectionTestUtils.setField(service, "xmlRepairService", repair);
        ReflectionTestUtils.setField(service, "roomLiveEventParseService", parser);
        ReflectionTestUtils.setField(service, "xmlIssueService", issues);
        ReflectionTestUtils.setField(service, "databaseMaintenanceState", mock(DatabaseMaintenanceState.class));
        ReflectionTestUtils.setField(service, "taskExecutor", (TaskExecutor) Runnable::run);
    }

    @Test
    void successfulRepairIsFollowedByForcedParsing() throws Exception {
        when(repair.repairWithReport(part)).thenReturn(java.util.Map.of("backupPath", "original.bak"));
        when(parser.parsePart(part, true)).thenReturn(new RoomLiveEventParseService.ParseResult(true, 1, null, null));
        service.startXmlIssueRepair(17L);
        var order = inOrder(repair, parser);
        order.verify(repair).repairWithReport(part);
        order.verify(parser).parsePart(part, true);
        assertEquals(true, service.getStatsTaskStatus().get("success"));
        assertEquals("DONE", service.getStatsTaskStatus().get("phase"));
    }

    @Test
    void failedRepairDoesNotParseAndReportsCause() throws Exception {
        when(repair.repairWithReport(part)).thenThrow(new IOException("broken XML"));
        service.startXmlIssueRepair(17L);
        verifyNoInteractions(parser);
        assertEquals(false, service.getStatsTaskStatus().get("success"));
        assertTrue(service.getStatsTaskStatus().get("message").toString().contains("broken XML"));
    }

    @Test
    void failedParsingDoesNotReportRepairComplete() throws Exception {
        when(repair.repairWithReport(part)).thenReturn(java.util.Map.of("backupPath", "original.bak"));
        when(parser.parsePart(part, true)).thenReturn(new RoomLiveEventParseService.ParseResult(false, 0, "database failure", null));
        service.startXmlIssueRepair(17L);
        assertEquals(false, service.getStatsTaskStatus().get("success"));
        assertTrue(service.getStatsTaskStatus().get("message").toString().contains("database failure"));
    }

    @Test
    void failureReleasesLockBeforePublishingTerminalStatusAndAllowsNextRepair() throws Exception {
        AtomicBoolean runningAtUnlock = new AtomicBoolean();
        ReentrantLock lock = new ReentrantLock() {
            @Override public void unlock() {
                runningAtUnlock.set(Boolean.TRUE.equals(service.getStatsTaskStatus().get("running")));
                super.unlock();
            }
        };
        ReflectionTestUtils.setField(service, "statsWriteLock", lock);
        when(repair.repairWithReport(part)).thenThrow(new IOException("broken XML"))
                .thenReturn(java.util.Map.of("summary", "文件未修改"));
        when(parser.parsePart(part, true)).thenReturn(new RoomLiveEventParseService.ParseResult(true, 1, null, null));
        service.startXmlIssueRepair(17L);
        assertTrue(runningAtUnlock.get());
        assertFalse(lock.isLocked());
        assertEquals(false, service.getStatsTaskStatus().get("running"));
        service.startXmlIssueRepair(17L);
        assertEquals("DONE", service.getStatsTaskStatus().get("phase"));
        verify(repair, times(2)).repairWithReport(part);
    }

    @Test
    void repairWaitsForBriefStatisticsReadInsteadOfReportingBusy() throws Exception {
        ReentrantLock lock = (ReentrantLock) ReflectionTestUtils.getField(service, "statsWriteLock");
        var executor = Executors.newSingleThreadExecutor();
        AtomicReference<Future<?>> task = new AtomicReference<>();
        ReflectionTestUtils.setField(service, "taskExecutor", (TaskExecutor) runnable -> task.set(executor.submit(runnable)));
        when(repair.repairWithReport(part)).thenReturn(java.util.Map.of("summary", "文件未修改"));
        when(parser.parsePart(part, true)).thenReturn(new RoomLiveEventParseService.ParseResult(true, 1, null, null));
        try {
            lock.lock();
            try {
                service.startXmlIssueRepair(17L);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!lock.hasQueuedThreads() && !task.get().isDone() && System.nanoTime() < deadline) Thread.yield();
                assertTrue(lock.hasQueuedThreads(), "Repair should wait for the read lock");
                assertEquals(true, service.getStatsTaskStatus().get("running"));
            } finally { lock.unlock(); }
            task.get().get(5, TimeUnit.SECONDS);
            assertEquals("DONE", service.getStatsTaskStatus().get("phase"));
        } finally { executor.shutdownNow(); }
    }
}
