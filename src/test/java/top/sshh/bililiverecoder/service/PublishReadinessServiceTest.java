package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.Test;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublishReadinessServiceTest {
    private final RecordHistoryPartRepository parts = mock(RecordHistoryPartRepository.class);
    private final SystemConfigService configs = mock(SystemConfigService.class);
    private final PublishReadinessService service = new PublishReadinessService(parts, configs);
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 2, 4, 0);

    private RecordHistory history() {
        RecordHistory history = new RecordHistory();
        history.setId(7L);
        history.setEndTime(now.minusHours(1));
        return history;
    }

    @Test
    void uploadedButStillRecordingPartDoesNotBypassRecordingCheck() {
        RecordHistoryPart part = new RecordHistoryPart();
        part.setUpload(true);
        part.setFileName("already-uploaded");
        part.setRecording(true);
        part.setEndTime(now.minusHours(1));
        when(parts.findByHistoryIdOrderByStartTimeAsc(7L)).thenReturn(List.of(part));
        when(configs.getAllConfigsMap()).thenReturn(Map.of());
        assertEquals("RECORDING", service.check(history()).reason());
    }

    @Test
    void historyAndStreamingFlagsStillBlockSubmissionBetweenParts() {
        RecordHistory history = history();
        history.setStreaming(true);
        assertEquals("RECORDING", service.check(history, 0, now.minusHours(1), Map.of(), now).reason());
        history.setStreaming(false);
        history.setRecording(true);
        assertFalse(service.check(history, 0, now.minusHours(1), Map.of(), now).allowed());
    }

    @Test
    void mergeWaitUsesMostRecentPartEndAndAllowsExactBoundary() {
        RecordHistory history = history();
        LocalDateTime lastPartEnd = now.minusMinutes(2);
        var check = service.check(history, 0, lastPartEnd, Map.of(), now);
        assertEquals("MERGE_INTERVAL", check.reason());
        assertEquals(now.plusMinutes(18), check.earliestAt());
        assertTrue(service.check(history, 0, lastPartEnd, Map.of(), check.earliestAt()).allowed());
    }

    @Test
    void resumedRecordingAndNewPartExtendPreviouslyFinishedWait() {
        RecordHistory history = history();
        assertTrue(service.check(history, 0, null, Map.of(), now).allowed());
        assertFalse(service.check(history, 1, null, Map.of(), now).allowed());
        assertEquals(now.plusMinutes(20), service.check(history, 0, now, Map.of(), now).earliestAt());
    }

    @Test
    void missingEndTimeDoesNotAllowSubmission() {
        RecordHistory history = history();
        history.setEndTime(null);
        assertEquals("RECORDING_END_UNKNOWN", service.check(history, 0, null, Map.of(), now).reason());
    }

    @Test
    void publishedEditsKeepExistingEligibility() {
        RecordHistory history = history();
        history.setPublish(true);
        history.setRecording(true);
        assertTrue(service.check(history).allowed());
        verifyNoInteractions(parts, configs);
    }

    @Test
    void legacyOnlineIdentityDoesNotBecomeNewSubmission() {
        RecordHistory history = history();
        history.setBvId("BV-existing");
        history.setRecording(true);
        assertTrue(service.check(history).allowed());
    }

    @Test
    void configurationKeepsDefaultAndBounds() {
        assertEquals(20, PublishReadinessService.mergeIntervalMinutes(Map.of()));
        assertEquals(20, PublishReadinessService.mergeIntervalMinutes(Map.of(SystemConfigService.KEY_MERGE_INTERVAL_MINUTES, "bad")));
        assertEquals(1, PublishReadinessService.mergeIntervalMinutes(Map.of(SystemConfigService.KEY_MERGE_INTERVAL_MINUTES, "0")));
        assertEquals(1440, PublishReadinessService.mergeIntervalMinutes(Map.of(SystemConfigService.KEY_MERGE_INTERVAL_MINUTES, "9999")));
    }
}
