package top.sshh.bililiverecoder.notification;

import org.junit.jupiter.api.Test;
import top.sshh.bililiverecoder.entity.NotificationRule;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.repo.NotificationRuleRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationRuleServiceTest {

    private final NotificationRuleRepository repository = mock(NotificationRuleRepository.class);
    private final NotificationRuleService service = new NotificationRuleService(repository);

    @Test
    void roomRuleTakesPrecedenceOverGlobalRule() {
        NotificationRule global = rule("*", true, "1");
        NotificationRule roomRule = rule("1001", true, "2");
        when(repository.findByEventType(NotificationEventType.LIVE_STREAM_STARTED.key())).thenReturn(List.of(global, roomRule));

        List<NotificationRule> rules = service.findCandidateRules(NotificationEventType.LIVE_STREAM_STARTED, room("1001"));

        assertEquals(List.of(roomRule), rules);
    }

    @Test
    void disabledRoomRuleMutesGlobalDefault() {
        NotificationRule global = rule("*", true, "1");
        NotificationRule roomRule = rule("1001", false, "");
        when(repository.findByEventType(NotificationEventType.LIVE_STREAM_STARTED.key())).thenReturn(List.of(global, roomRule));

        List<NotificationRule> rules = service.findEnabledRules(NotificationEventType.LIVE_STREAM_STARTED, room("1001"));

        assertTrue(rules.isEmpty());
    }

    @Test
    void fallsBackToGlobalRuleWhenRoomHasNoOverride() {
        NotificationRule global = rule("*", true, "1");
        NotificationRule otherRoom = rule("1002", true, "2");
        when(repository.findByEventType(NotificationEventType.LIVE_STREAM_STARTED.key())).thenReturn(List.of(global, otherRoom));

        List<NotificationRule> rules = service.findCandidateRules(NotificationEventType.LIVE_STREAM_STARTED, room("1001"));

        assertEquals(List.of(global), rules);
    }

    private NotificationRule rule(String roomId, boolean enabled, String channelIds) {
        NotificationRule rule = new NotificationRule();
        rule.setEventType(NotificationEventType.LIVE_STREAM_STARTED.key());
        rule.setRoomId(roomId);
        rule.setEnabled(enabled);
        rule.setChannelIds(channelIds);
        return rule;
    }

    private RecordRoom room(String roomId) {
        RecordRoom room = new RecordRoom();
        room.setRoomId(roomId);
        return room;
    }
}
