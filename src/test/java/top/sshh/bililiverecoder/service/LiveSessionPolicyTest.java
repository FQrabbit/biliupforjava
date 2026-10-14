package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.Test;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordRoom;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class LiveSessionPolicyTest {
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 14, 20, 0);

    private RecordRoom room() {
        RecordRoom room = new RecordRoom(); room.setRoomId("100"); room.setLiveGroup("local-group");
        room.setLiveEventAt(now.minusHours(2)); room.setLiveEndedAt(now.minusMinutes(20));
        return room;
    }

    @Test
    void exactConfiguredBoundaryStillBelongsToSameLive() {
        RecordRoom room = room();
        assertTrue(LiveSessionPolicy.begin(room, null, null, now, 20));
        assertEquals("local-group", room.getLiveGroup());
        assertNull(room.getLiveEndedAt());
    }

    @Test
    void oneMillisecondBeyondBoundaryStartsNewGroup() {
        RecordRoom room = room();
        LiveSessionPolicy.begin(room, null, null, now.plusNanos(1000000), 20);
        assertNotEquals("local-group", room.getLiveGroup());
    }

    @Test
    void customWindowChangesClassification() {
        RecordRoom room = room();
        LiveSessionPolicy.begin(room, null, null, now, 30);
        assertEquals("local-group", room.getLiveGroup());
        room = room(); LiveSessionPolicy.begin(room, null, null, now, 10);
        assertNotEquals("local-group", room.getLiveGroup());
    }

    @Test
    void duplicateEndDoesNotExtendMergeWindow() {
        RecordRoom room = room();
        LiveSessionPolicy.end(room, now);
        assertEquals(now.minusMinutes(20), room.getLiveEndedAt());
    }

    @Test
    void oldStartCannotReopenCurrentPhysicalRecording() {
        RecordRoom room = room(); room.setLiveEventAt(now.minusMinutes(1));
        Date old = Date.from(now.minusHours(1).atZone(ZoneId.systemDefault()).toInstant());
        assertFalse(LiveSessionPolicy.begin(room, null, old, now, 20));
        assertEquals("local-group", room.getLiveGroup());
        assertNotNull(room.getLiveEndedAt());
    }

    @Test
    void legacySplitGroupUsesBoundaryOnlyForInitialRecovery() {
        RecordRoom room = new RecordRoom(); room.setRoomId("100"); room.setRecording(true);
        RecordHistory history = new RecordHistory(); history.setRoomId("100"); history.setSplitGroup("legacy");
        history.setSplitClosedAt(now.minusHours(1)); history.setEndTime(now.minusHours(1));
        LiveSessionPolicy.begin(room, history, null, now, 20);
        assertNotEquals("legacy", room.getLiveGroup());
        room.setLiveGroup("legacy"); room.setLiveEndedAt(null);
        LiveSessionPolicy.begin(room, history, null, now, 20);
        assertEquals("legacy", room.getLiveGroup());
    }

    @Test
    void lateStartFromBeforeEndCannotReopenEndedGroup() {
        RecordRoom room = room(); room.setLiveEndedAt(null);
        Date end = Date.from(now.minusMinutes(20).atZone(ZoneId.systemDefault()).toInstant());
        LiveSessionPolicy.end(room, end, now.minusMinutes(20));
        Date lateStart = Date.from(now.minusMinutes(30).atZone(ZoneId.systemDefault()).toInstant());
        assertFalse(LiveSessionPolicy.begin(room, null, lateStart, now, 20));
        assertEquals(now.minusMinutes(20), room.getLiveEndedAt());
    }

    @Test
    void archivedGroupIsNotRecoveredAsActiveLive() {
        RecordRoom room = new RecordRoom(); room.setRoomId("100"); room.setRecording(true);
        RecordHistory archived = new RecordHistory(); archived.setSplitGroup("archive"); archived.setImportArchived(true);
        LiveSessionPolicy.begin(room, archived, null, now, 20);
        assertNotEquals("archive", room.getLiveGroup());
    }
}
