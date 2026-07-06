package top.sshh.bililiverecoder.notification;

import org.junit.jupiter.api.Test;
import top.sshh.bililiverecoder.entity.NotificationChannel;
import top.sshh.bililiverecoder.entity.NotificationRule;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.repo.NotificationChannelRepository;
import top.sshh.bililiverecoder.repo.NotificationRuleRepository;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationRuleEngineTest {

    private final NotificationRuleRepository ruleRepository = mock(NotificationRuleRepository.class);
    private final NotificationChannelRepository channelRepository = mock(NotificationChannelRepository.class);
    private final NotificationRuleService ruleService = new NotificationRuleService(ruleRepository);
    private final NotificationRuleEngine engine = new NotificationRuleEngine(ruleService, channelRepository);

    @Test
    void deprecatedEventDoesNotResolveRulesOrLegacyChannels() {
        RecordRoom room = room("1001");
        room.setPushMsgTags("分P上传");
        room.setWxuid("UID");

        Set<NotificationChannel> channels = engine.resolveChannels(room, NotificationEventType.PART_UPLOAD);

        assertTrue(channels.isEmpty());
    }

    @Test
    void roomDisabledRuleMutesGlobalChannel() {
        NotificationRule global = rule("*", true, "1");
        NotificationRule roomMute = rule("1001", false, "");
        when(ruleRepository.findByEventType(NotificationEventType.LIVE_STREAM_STARTED.key()))
                .thenReturn(List.of(global, roomMute));
        when(channelRepository.findAllById(any())).thenReturn(List.of(channel(1L)));

        Set<NotificationChannel> channels = engine.resolveChannels(room("1001"), NotificationEventType.LIVE_STREAM_STARTED);

        assertTrue(channels.isEmpty());
    }

    @Test
    void activeEventFallsBackToLegacyChannelWhenNoNewRuleExists() {
        RecordRoom room = room("1001");
        room.setPushMsgTags("开始直播");
        room.setWxuid("UID");
        when(ruleRepository.findByEventType(NotificationEventType.LIVE_STREAM_STARTED.key())).thenReturn(List.of());

        Set<NotificationChannel> channels = engine.resolveChannels(room, NotificationEventType.LIVE_STREAM_STARTED);

        assertEquals(1, channels.size());
        assertEquals(WxPusherNotificationChannel.TYPE, channels.iterator().next().getType());
    }

    private NotificationRule rule(String roomId, boolean enabled, String channelIds) {
        NotificationRule rule = new NotificationRule();
        rule.setEventType(NotificationEventType.LIVE_STREAM_STARTED.key());
        rule.setRoomId(roomId);
        rule.setEnabled(enabled);
        rule.setChannelIds(channelIds);
        return rule;
    }

    private NotificationChannel channel(Long id) {
        NotificationChannel channel = new NotificationChannel();
        channel.setId(id);
        channel.setType(WxPusherNotificationChannel.TYPE);
        channel.setEnabled(true);
        return channel;
    }

    private RecordRoom room(String roomId) {
        RecordRoom room = new RecordRoom();
        room.setRoomId(roomId);
        return room;
    }
}
