package top.sshh.bililiverecoder.notification;

import org.junit.jupiter.api.Test;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.repo.NotificationChannelRepository;
import top.sshh.bililiverecoder.repo.NotificationRuleRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationLegacyMigrationServiceTest {

    private final RecordRoomRepository recordRoomRepository = mock(RecordRoomRepository.class);
    private final NotificationChannelRepository notificationChannelRepository = mock(NotificationChannelRepository.class);
    private final NotificationRuleRepository notificationRuleRepository = mock(NotificationRuleRepository.class);
    private final NotificationLegacyMigrationService service = new NotificationLegacyMigrationService(
            recordRoomRepository,
            notificationChannelRepository,
            notificationRuleRepository
    );

    @Test
    void statusIgnoresDefaultTagsWithoutLegacyChannel() {
        mockRooms(room("1000"));

        Map<String, Object> status = service.status(false);

        assertEquals(false, status.get("needsMigration"));
        assertEquals(0, status.get("count"));
    }

    @Test
    void statusDetectsCustomTagsWithoutLegacyChannel() {
        RecordRoom room = room("1001");
        room.setPushMsgTags(NotificationEventType.LIVE_STREAM_STARTED.label());
        mockRooms(room);

        Map<String, Object> status = service.status(false);

        assertEquals(true, status.get("needsMigration"));
        assertEquals(1, status.get("count"));
    }

    @Test
    void statusDetectsServerChanChannelWithoutSendKey() {
        RecordRoom room = room("1002");
        room.setServerChanChannel("ops");
        mockRooms(room);

        Map<String, Object> status = service.status(false);

        assertEquals(true, status.get("needsMigration"));
        assertEquals(1, status.get("count"));
    }

    @Test
    void applyCreatesRoomMuteRulesForLegacyDisabledEvents() {
        RecordRoom room = room("1003");
        room.setPushMsgTags(NotificationEventType.LIVE_STREAM_STARTED.label());
        mockRooms(room);
        when(notificationRuleRepository.findByEventType(any())).thenReturn(List.of());

        Map<String, Object> result = service.apply();

        assertEquals(true, result.get("success"));
        assertEquals(1, result.get("rooms"));
        assertEquals(NotificationEventType.orderedValues().size() - 1, result.get("rules"));
        verify(notificationRuleRepository, atLeastOnce()).save(any());
    }

    private void mockRooms(RecordRoom... rooms) {
        when(recordRoomRepository.findAllOrderBySortOrder()).thenReturn(List.of(rooms));
        when(recordRoomRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private RecordRoom room(String roomId) {
        RecordRoom room = new RecordRoom();
        room.setRoomId(roomId);
        room.setUname("room-" + roomId);
        return room;
    }
}
