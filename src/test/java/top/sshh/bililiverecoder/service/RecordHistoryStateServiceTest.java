package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.Test;
import top.sshh.bililiverecoder.entity.RecordEventData;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecordHistoryStateServiceTest {

    @Test
    void staleSessionEndResolvesItsOwnHistoryInsteadOfCurrentRoomPointer() {
        RecordHistoryRepository histories = mock(RecordHistoryRepository.class);
        RecordHistoryPartRepository parts = mock(RecordHistoryPartRepository.class);
        RecordRoomRepository rooms = mock(RecordRoomRepository.class);
        RecordHistoryStateService service = new RecordHistoryStateService(histories, parts, rooms);

        RecordRoom room = new RecordRoom();
        room.setRoomId("100");
        room.setSessionId("new-session");
        room.setHistoryId(2L);
        RecordHistory oldHistory = new RecordHistory();
        oldHistory.setId(1L);
        oldHistory.setRoomId("100");
        RecordHistoryPart oldPart = new RecordHistoryPart();
        oldPart.setHistoryId(1L);

        RecordEventData event = new RecordEventData();
        event.setRoomId("100");
        event.setSessionId("old-session");
        when(parts.findByRoomIdAndSessionIdOrderByIdDesc("100", "old-session")).thenReturn(List.of(oldPart));
        when(histories.findById(1L)).thenReturn(Optional.of(oldHistory));

        assertSame(oldHistory, service.resolveHistory(room, event, null));
        verify(histories, never()).findById(2L);
    }

    @Test
    void staleSessionMustNotClearCurrentRoomState() {
        RecordHistoryRepository histories = mock(RecordHistoryRepository.class);
        RecordHistoryPartRepository parts = mock(RecordHistoryPartRepository.class);
        RecordRoomRepository rooms = mock(RecordRoomRepository.class);
        RecordHistoryStateService service = new RecordHistoryStateService(histories, parts, rooms);

        RecordRoom room = new RecordRoom();
        room.setSessionId("new-session");
        room.setHistoryId(2L);
        RecordHistory oldHistory = new RecordHistory();
        oldHistory.setId(1L);
        RecordEventData oldEvent = new RecordEventData();
        oldEvent.setSessionId("old-session");

        service.markCurrentRoomStopped(room, oldEvent, oldHistory);

        verify(rooms, never()).save(any());
    }
}
