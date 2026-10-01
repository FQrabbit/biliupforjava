package top.sshh.bililiverecoder.service;

import com.alibaba.fastjson.JSON;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.RoomLiveDanmuUserStats;
import top.sshh.bililiverecoder.entity.RoomLiveEvent;
import top.sshh.bililiverecoder.entity.RoomLiveEventParseState;
import top.sshh.bililiverecoder.repo.RoomLiveDanmuUserStatsRepository;
import top.sshh.bililiverecoder.repo.RoomLiveEventParseStateRepository;
import top.sshh.bililiverecoder.repo.RoomLiveEventRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = {
        "spring.datasource.hikari.jdbc-url=jdbc:h2:mem:room-event-spool;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;DATABASE_TO_UPPER=false",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(RoomLiveEventParseCommitService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RoomLiveEventParseCommitServiceTest {
    @TempDir
    Path tempDir;

    @Autowired
    private RoomLiveEventParseCommitService commitService;
    @Autowired
    private RoomLiveEventRepository eventRepository;
    @Autowired
    private RoomLiveDanmuUserStatsRepository danmuStatsRepository;
    @Autowired
    private RoomLiveEventParseStateRepository stateRepository;

    @Test
    void replacesOldRowsUsingBoundedPersistenceBatches() throws Exception {
        long partId = 501L;
        Path spool = tempDir.resolve("events.jsonl");
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < 75; i++) {
            RoomLiveEvent event = event(partId, i + 1L, "new-" + i);
            lines.append(JSON.toJSONString(event)).append('\n');
        }
        Files.writeString(spool, lines);
        seedOldRows(partId);

        RoomLiveEventParseState replacementState = stateRepository.findByPartId(partId);
        replacementState.setErrorMessage(null);
        commitService.replacePartDataFromSpool(partId, spool, List.of(stats(partId, 20L, "viewer-new")),
                replacementState, 50);

        List<RoomLiveEvent> savedEvents = eventsFor(partId);
        assertEquals(75, savedEvents.size());
        assertTrue(savedEvents.stream().anyMatch(event -> "new-0".equals(event.getContent())));
        List<RoomLiveDanmuUserStats> savedStats = statsFor(partId);
        assertEquals(1, savedStats.size());
        assertEquals("viewer-new", savedStats.get(0).getUname());
        assertTrue(stateRepository.findByPartId(partId).isSuccess());
    }

    @Test
    void malformedSpoolRestoresPreviouslyCommittedRows() throws Exception {
        long partId = 502L;
        seedOldRows(partId);
        Path spool = tempDir.resolve("broken.jsonl");
        Files.writeString(spool, "{not-json\n");

        assertThrows(RuntimeException.class, () -> commitService.replacePartDataFromSpool(
                partId, spool, List.of(stats(partId, 20L, "viewer-new")), stateRepository.findByPartId(partId), 50));

        assertEquals(1, eventsFor(partId).size());
        assertEquals("old-event", eventsFor(partId).get(0).getContent());
        assertEquals(1, statsFor(partId).size());
        assertEquals("viewer-old", statsFor(partId).get(0).getUname());
        assertEquals("old-state", stateRepository.findByPartId(partId).getErrorMessage());
    }

    private void seedOldRows(long partId) {
        eventRepository.save(event(partId, 1L, "old-event"));
        danmuStatsRepository.save(stats(partId, 10L, "viewer-old"));
        RoomLiveEventParseState state = successfulState(partId);
        state.setErrorMessage("old-state");
        stateRepository.save(state);
    }

    private List<RoomLiveEvent> eventsFor(long partId) {
        return StreamSupport.stream(eventRepository.findAll().spliterator(), false)
                .filter(event -> partId == event.getPartId()).toList();
    }

    private List<RoomLiveDanmuUserStats> statsFor(long partId) {
        return StreamSupport.stream(danmuStatsRepository.findAll().spliterator(), false)
                .filter(stats -> partId == stats.getPartId()).toList();
    }

    private static RoomLiveEvent event(long partId, long uid, String content) {
        RoomLiveEvent event = new RoomLiveEvent();
        event.setPartId(partId);
        event.setHistoryId(42L);
        event.setRoomId("room");
        event.setLiveDate(LocalDate.of(2026, 9, 28));
        event.setType(RoomLiveEvent.TYPE_DANMU);
        event.setUid(uid);
        event.setUname("viewer-" + uid);
        event.setSendTime(uid);
        event.setContent(content);
        return event;
    }

    private static RoomLiveDanmuUserStats stats(long partId, long uid, String uname) {
        RoomLiveDanmuUserStats stats = new RoomLiveDanmuUserStats();
        stats.setPartId(partId);
        stats.setHistoryId(42L);
        stats.setRoomId("room");
        stats.setLiveDate(LocalDate.of(2026, 9, 28));
        stats.setUid(uid);
        stats.setUname(uname);
        stats.setDanmuCount(3L);
        stats.setParserVersion(2);
        return stats;
    }

    private static RoomLiveEventParseState successfulState(long partId) {
        RoomLiveEventParseState state = new RoomLiveEventParseState();
        state.setPartId(partId);
        state.setHistoryId(42L);
        state.setRoomId("room");
        state.setSuccess(true);
        state.setParserVersion(2);
        return state;
    }
}
