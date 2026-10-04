package top.sshh.bililiverecoder.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.repo.*;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = {
        "spring.datasource.hikari.jdbc-url=jdbc:h2:mem:split-history;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({RecordHistorySplitService.class, RoomLiveEventParseCommitService.class})
class RecordHistorySplitServiceTest {
    @Autowired RecordHistorySplitService service;
    @Autowired org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager entities;
    @Autowired RecordHistoryRepository histories;
    @Autowired RecordHistoryPartRepository parts;
    @Autowired RecordRoomRepository rooms;
    @Autowired RoomLiveEventRepository events;
    @Autowired RoomLiveDanmuUserStatsRepository danmuStats;
    @Autowired RoomLiveEventParseStateRepository parseStates;
    @Autowired RoomLiveEventXmlIssueRepository issues;
    @Autowired RoomLiveEventParseCommitService parseCommit;
    @MockBean SystemConfigService configs;
    @MockBean PartPreviewService preview;
    private final Map<String, String> settings = new HashMap<>();
    private final LocalDateTime start = LocalDateTime.of(2026, 10, 4, 8, 0);
    private RecordRoom room;

    @BeforeEach
    void setup() {
        settings.clear();
        settings.put(SystemConfigService.KEY_SPLIT_SIZE_GB, "10");
        settings.put(SystemConfigService.KEY_SPLIT_DURATION_MINUTES, "0");
        when(configs.getAllConfigsMap()).thenAnswer(invocation -> new HashMap<>(settings));
        room = new RecordRoom();
        room.setRoomId("100");
        room.setRecording(true);
        room.setStreaming(true);
        rooms.save(room);
    }

    private RecordHistory history() {
        RecordHistory history = new RecordHistory();
        history.setRoomId(room.getRoomId());
        history.setSessionId("session-1");
        history.setStartTime(start);
        history.setEndTime(start);
        history.setRecording(true);
        history.setStreaming(true);
        history.setUpload(true);
        history.setTitle("原直播标题");
        history.setEventId(UUID.randomUUID().toString());
        service.initializeNew(history);
        histories.save(history);
        room.setHistoryId(history.getId());
        rooms.save(room);
        return history;
    }

    private RecordHistoryPart part(RecordHistory history, int index, double gb, float seconds, boolean recording) {
        RecordHistoryPart part = new RecordHistoryPart();
        part.setRoomId(history.getRoomId());
        part.setHistoryId(history.getId());
        part.setSessionId(history.getSessionId());
        part.setStartTime(start.plusHours(index));
        part.setPartOrder(index + 1);
        part.setFilePath("/split/" + UUID.randomUUID() + ".flv");
        part.setEventId(UUID.randomUUID().toString());
        part.setFileSize((long) (gb * 1073741824L));
        part.setDuration(seconds);
        part.setRecording(recording);
        if (!recording) part.setEndTime(part.getStartTime().plusSeconds((long) seconds));
        return parts.save(part);
    }

    @Test
    void exactSizeSealsWithoutEmptySuccessorAndKeepsRoomRecording() {
        RecordHistory history = history();
        RecordHistoryPart boundary = part(history, 0, 10, 3600, false);
        service.reconcile(history.getId());
        assertTrue(history.isSplitClosed());
        assertEquals(boundary.getId(), history.getSplitBoundaryPartId());
        assertFalse(history.isRecording());
        assertTrue(room.isRecording());
        assertTrue(room.isStreaming());
        assertNull(histories.findBySplitParentId(history.getId()));
    }

    @Test
    void oversizedLaterPartStaysWithPrecedingPartAndOpenTailMoves() {
        RecordHistory history = history();
        RecordHistoryPart first = part(history, 0, 3, 3600, false);
        RecordHistoryPart boundary = part(history, 1, 12, 3600, false);
        RecordHistoryPart tail = part(history, 2, 0, 0, true);
        service.reconcile(history.getId());
        RecordHistory next = histories.findBySplitParentId(history.getId());
        assertEquals(history.getId(), first.getHistoryId());
        assertEquals(history.getId(), boundary.getHistoryId());
        assertEquals(next.getId(), tail.getHistoryId());
        assertEquals(1, tail.getPartOrder());
        assertEquals(next.getId(), room.getHistoryId());
        assertEquals("原直播标题", next.getTitle());
        assertEquals(history.getSessionId(), next.getSessionId());
        assertNotEquals(history.getEventId(), next.getEventId());
        assertEquals(2, next.getSplitSequence());
        assertFalse(service.canUpload(tail));
    }

    @Test
    void outOfOrderCompletionWaitsForPrecedingDataBeforeUploadingTail() {
        RecordHistory history = history();
        RecordHistoryPart first = part(history, 0, 0, 0, true);
        RecordHistoryPart later = part(history, 1, 12, 3600, false);
        assertFalse(service.canUpload(later));
        assertFalse(history.isSplitClosed());
        first.setRecording(false);
        first.setEndTime(start.plusMinutes(30));
        first.setDuration(1800);
        first.setFileSize(10L * 1073741824L);
        parts.save(first);
        service.reconcile(history.getId());
        RecordHistory next = histories.findBySplitParentId(history.getId());
        assertTrue(history.isSplitClosed());
        assertTrue(next.isSplitClosed());
        assertEquals(next.getId(), later.getHistoryId());
        assertTrue(service.canUpload(later));
        assertNull(histories.findBySplitParentId(next.getId()));
    }

    @Test
    void mediaDurationIsSummedWithoutDisconnectedGapAndSizeCanBeDisabled() {
        settings.put(SystemConfigService.KEY_SPLIT_DURATION_MINUTES, "60");
        settings.put(SystemConfigService.KEY_SPLIT_SIZE_GB, "0");
        RecordHistory history = history();
        part(history, 0, 12, 1800, false);
        service.reconcile(history.getId());
        assertFalse(history.isSplitClosed());
        part(history, 5, 12, 1800, false);
        service.reconcile(history.getId());
        assertTrue(history.isSplitClosed());
        assertEquals("DURATION", history.getSplitReason());
    }

    @Test
    void eitherEnabledConditionCanClose() {
        settings.put(SystemConfigService.KEY_SPLIT_DURATION_MINUTES, "60");
        RecordHistory history = history();
        part(history, 0, 1, 3600, false);
        service.reconcile(history.getId());
        assertEquals("DURATION", history.getSplitReason());
    }

    @Test
    void configurationSnapshotChangesOnlyForSuccessor() {
        RecordHistory history = history();
        settings.put(SystemConfigService.KEY_SPLIT_SIZE_GB, "20");
        part(history, 0, 10, 3600, false);
        service.reconcile(history.getId());
        assertTrue(history.isSplitClosed());
        RecordHistory next = service.historyForNewPart(room, history, start.plusHours(1));
        assertEquals(20L * 1073741824L, next.getSplitSizeBytes());
        assertSame(next, service.historyForNewPart(room, history, start.plusHours(1)));
        assertEquals(10L * 1073741824L, history.getSplitSizeBytes());
    }

    @Test
    void disabledSuccessorDoesNotBlockMovedTail() {
        RecordHistory history = history();
        part(history, 0, 10, 3600, false);
        RecordHistoryPart tail = part(history, 1, 1, 3600, false);
        settings.put(SystemConfigService.KEY_SPLIT_SIZE_GB, "0");
        assertTrue(service.canUpload(tail));
        assertFalse(histories.findById(tail.getHistoryId()).orElseThrow().isSplitEnabled());
    }

    @Test
    void legacyHistoryAndDefaultDisabledHistoryAreNeverSplit() {
        RecordHistory legacy = new RecordHistory();
        legacy.setRoomId(room.getRoomId());
        histories.save(legacy);
        RecordHistoryPart oldPart = part(legacy, 0, 100, 3600, false);
        service.reconcile(legacy.getId());
        assertFalse(legacy.isSplitClosed());
        assertTrue(service.canUpload(oldPart));
        settings.clear();
        RecordHistory fresh = history();
        part(fresh, 0, 100, 3600, false);
        service.reconcile(fresh.getId());
        assertFalse(fresh.isSplitClosed());
    }

    @Test
    void repeatedCloseAndFileDeletionDoNotChangeFrozenTotalsOrDuplicateSuccessor() {
        RecordHistory history = history();
        RecordHistoryPart first = part(history, 0, 5, 3600, false);
        service.reconcile(history.getId());
        first.setFileSize(0);
        first.setFileDelete(true);
        parts.save(first);
        RecordHistoryPart boundary = part(history, 1, 5, 3600, false);
        part(history, 2, 1, 3600, false);
        service.reconcile(history.getId());
        Long nextId = histories.findBySplitParentId(history.getId()).getId();
        service.reconcile(history.getId());
        assertEquals(boundary.getId(), history.getSplitBoundaryPartId());
        assertEquals(10L * 1073741824L, history.getFileSize());
        assertEquals(nextId, histories.findBySplitParentId(history.getId()).getId());
    }

    @Test
    void skippedAndFailedPartsStillCountAndKeepUploadResults() {
        RecordHistory history = history();
        RecordHistoryPart skipped = part(history, 0, 5, 3600, false);
        skipped.setUploadRetryCount(9999);
        skipped.setDeleteFailType("MANUAL_SKIP");
        RecordHistoryPart uploaded = part(history, 1, 5, 3600, false);
        uploaded.setUpload(true);
        uploaded.setCid(42L);
        uploaded.setFileName("platform-name");
        service.reconcile(history.getId());
        assertTrue(history.isSplitClosed());
        assertEquals(9999, skipped.getUploadRetryCount());
        assertTrue(uploaded.isUpload());
        assertEquals(42L, uploaded.getCid());
        assertEquals("platform-name", uploaded.getFileName());
    }

    @Test
    void unknownDurationWaitsForProbeAndNeverUsesHistoryElapsedTime() {
        settings.put(SystemConfigService.KEY_SPLIT_DURATION_MINUTES, "60");
        settings.put(SystemConfigService.KEY_SPLIT_SIZE_GB, "0");
        RecordHistory history = history();
        RecordHistoryPart part = part(history, 5, 1, 0, false);
        assertFalse(service.canUpload(part));
        assertFalse(history.isSplitClosed());
        when(preview.probeDuration(part.getId())).thenReturn(3600d);
        assertTrue(service.canUpload(part));
        assertTrue(history.isSplitClosed());
    }

    @Test
    void reopenedFileBlocksUploadWithoutRevokingBoundary() {
        RecordHistory history = history();
        RecordHistoryPart part = part(history, 0, 10, 3600, false);
        service.reconcile(history.getId());
        part.setRecording(true);
        part.setEndTime(null);
        parts.save(part);
        assertFalse(service.canUpload(part));
        assertTrue(history.isSplitClosed());
        assertEquals(part.getId(), history.getSplitBoundaryPartId());
    }

    @Test
    void storedSnapshotsAndBoundariesSurviveEntityReload() {
        RecordHistory history = history();
        part(history, 0, 10, 3600, false);
        service.reconcile(history.getId());
        entities.flush();
        entities.clear();
        RecordHistory stored = histories.findById(history.getId()).orElseThrow();
        assertTrue(stored.isSplitClosed());
        settings.put(SystemConfigService.KEY_SPLIT_SIZE_GB, "1");
        service.reconcile(stored.getId());
        assertEquals(10L * 1073741824L, stored.getSplitSizeBytes());
        assertNull(histories.findBySplitParentId(stored.getId()));
    }

    @Test
    void automaticCandidatesBypassMergeOnlyForSealedHistory() {
        RecordHistory sealed = history();
        part(sealed, 0, 10, 3600, false);
        service.reconcile(sealed.getId());
        sealed.setEndTime(start.plusHours(1));
        RecordHistory tail = history();
        tail.setRecording(false);
        tail.setStreaming(false);
        tail.setEndTime(start.plusHours(1));
        histories.saveAll(List.of(sealed, tail));
        List<RecordHistory> found = histories.findAutomaticPublishCandidates("100", 5, start.minusDays(1), start.plusHours(1).plusMinutes(1), start);
        assertEquals(List.of(sealed.getId()), found.stream().map(RecordHistory::getId).toList());
    }

    @Test
    void sameSessionCanHaveSeveralHistoriesWithoutCrossRoomResolution() {
        RecordHistory first = history();
        RecordHistory second = history();
        RecordHistory other = history();
        other.setRoomId("200");
        histories.save(other);
        assertEquals(second.getId(), histories.findFirstByRoomIdAndSessionIdOrderByIdDesc("100", first.getSessionId()).getId());
    }

    @Test
    void movingTailAlsoMovesParsedEventsStatsAndIssuesAndLateParseUsesCurrentOwner() {
        RecordHistory history = history();
        part(history, 0, 10, 3600, false);
        RecordHistoryPart tail = part(history, 1, 1, 3600, false);
        RoomLiveEvent event = new RoomLiveEvent();
        event.setPartId(tail.getId());
        event.setHistoryId(history.getId());
        event.setContent(UUID.randomUUID().toString());
        events.save(event);
        RoomLiveDanmuUserStats user = new RoomLiveDanmuUserStats();
        user.setPartId(tail.getId());
        user.setHistoryId(history.getId());
        danmuStats.save(user);
        RoomLiveEventParseState state = new RoomLiveEventParseState();
        state.setPartId(tail.getId());
        state.setHistoryId(history.getId());
        parseStates.save(state);
        RoomLiveEventXmlIssue issue = new RoomLiveEventXmlIssue();
        issue.setPartId(tail.getId());
        issue.setHistoryId(history.getId());
        issue.setXmlPath("split-test.xml");
        issue.setIssueType(RoomLiveEventXmlIssue.IssueType.values()[0]);
        issues.save(issue);
        service.reconcile(history.getId());
        Long nextId = tail.getHistoryId();
        entities.flush();
        entities.clear();
        assertEquals(nextId, events.findById(event.getId()).orElseThrow().getHistoryId());
        assertEquals(nextId, danmuStats.findById(user.getId()).orElseThrow().getHistoryId());
        assertEquals(nextId, parseStates.findById(state.getId()).orElseThrow().getHistoryId());
        assertEquals(nextId, issues.findById(issue.getPartId()).orElseThrow().getHistoryId());
        RoomLiveEvent late = new RoomLiveEvent();
        late.setPartId(tail.getId());
        late.setHistoryId(history.getId());
        late.setContent(UUID.randomUUID().toString());
        state.setHistoryId(history.getId());
        parseCommit.replacePartData(tail.getId(), List.of(late), List.of(), state);
        assertEquals(nextId, late.getHistoryId());
        assertEquals(nextId, state.getHistoryId());
    }

    @Test
    void thresholdValidationRejectsInvalidAndOverflowingInput() {
        for (String invalid : List.of("-1", "NaN", "Infinity", "1.234", "999999999999999999999")) {
            assertThrows(IllegalArgumentException.class, () -> RecordHistorySplitService.normalizeThreshold(SystemConfigService.KEY_SPLIT_SIZE_GB, invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> RecordHistorySplitService.normalizeThreshold(SystemConfigService.KEY_SPLIT_DURATION_MINUTES, "1.5"));
        assertEquals("0", RecordHistorySplitService.normalizeThreshold(SystemConfigService.KEY_SPLIT_SIZE_GB, "0"));
        assertEquals("0.25", RecordHistorySplitService.normalizeThreshold(SystemConfigService.KEY_SPLIT_SIZE_GB, "0.25"));
    }

    @Test
    void splitApiFieldsSerializeWithoutAdditionalTypes() throws Exception {
        RecordHistory history = history();
        part(history, 0, 10, 3600, false);
        service.reconcile(history.getId());
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var json = mapper.readTree(mapper.writeValueAsString(history));
        assertEquals("SIZE", json.get("splitReason").asText());
        assertTrue(json.hasNonNull("splitClosedAt"));
        assertEquals(1, json.get("splitSequence").asInt());
    }
}
