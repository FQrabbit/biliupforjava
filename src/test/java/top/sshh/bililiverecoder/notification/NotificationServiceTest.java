package top.sshh.bililiverecoder.notification;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import top.sshh.bililiverecoder.entity.NotificationChannel;
import top.sshh.bililiverecoder.entity.NotificationRule;
import top.sshh.bililiverecoder.repo.NotificationChannelRepository;
import top.sshh.bililiverecoder.repo.NotificationDeliveryRepository;
import top.sshh.bililiverecoder.repo.NotificationRuleRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.service.SystemConfigService;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationServiceTest {

    private final NotificationChannelRepository channelRepository = mock(NotificationChannelRepository.class);
    private final NotificationDeliveryRepository deliveryRepository = mock(NotificationDeliveryRepository.class);
    private final NotificationRuleRepository ruleRepository = mock(NotificationRuleRepository.class);
    private final RecordRoomRepository roomRepository = mock(RecordRoomRepository.class);
    private final SystemConfigService systemConfigService = mock(SystemConfigService.class);
    private final NotificationDispatchService dispatchService = mock(NotificationDispatchService.class);
    private final NotificationService service = new NotificationService(
            channelRepository,
            deliveryRepository,
            ruleRepository,
            roomRepository,
            systemConfigService,
            dispatchService
    );

    @Test
    void saveSystemScopedRuleForcesGlobalRuleAndDeletesRoomRules() {
        NotificationRule staleRoomRule = new NotificationRule();
        staleRoomRule.setId(12L);
        staleRoomRule.setEventType(NotificationEventType.WORKSPACE_USAGE_ALERT.key());
        staleRoomRule.setRoomId("1001");
        when(ruleRepository.findByEventType(NotificationEventType.WORKSPACE_USAGE_ALERT.key()))
                .thenReturn(List.of(staleRoomRule));
        when(ruleRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        NotificationRule incoming = new NotificationRule();
        incoming.setEventType(NotificationEventType.WORKSPACE_USAGE_ALERT.key());
        incoming.setRoomId("1001");
        incoming.setRoomName("主播A");
        incoming.setEnabled(true);
        incoming.setChannelIds("1,2");

        service.saveRule(incoming);

        ArgumentCaptor<NotificationRule> captor = ArgumentCaptor.forClass(NotificationRule.class);
        verify(ruleRepository).delete(staleRoomRule);
        verify(ruleRepository).save(captor.capture());
        NotificationRule saved = captor.getValue();
        assertEquals("*", saved.getRoomId());
        assertEquals("系统级事件", saved.getRoomName());
        assertEquals("1,2", saved.getChannelIds());
    }

    @Test
    void saveChannelMergesNonBlankSecretFields() {
        NotificationChannel existing = new NotificationChannel();
        existing.setId(5L);
        existing.setType(DingTalkWebhookNotificationChannel.TYPE);
        existing.setName("钉钉");
        existing.setSecretJson("{\"webhookUrl\":\"https://old.example\",\"signSecret\":\"old-secret\"}");
        when(channelRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(channelRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        NotificationChannel incoming = new NotificationChannel();
        incoming.setId(5L);
        incoming.setType(DingTalkWebhookNotificationChannel.TYPE);
        incoming.setName("钉钉");
        incoming.setEnabled(true);
        incoming.setConfigJson("{}");
        incoming.setSecretJson("{\"signSecret\":\"new-secret\"}");

        service.saveChannel(incoming);

        ArgumentCaptor<NotificationChannel> captor = ArgumentCaptor.forClass(NotificationChannel.class);
        verify(channelRepository).save(captor.capture());
        NotificationChannel saved = captor.getValue();
        assertEquals("https://old.example", NotificationJson.parse(saved.getSecretJson()).getString("webhookUrl"));
        assertEquals("new-secret", NotificationJson.parse(saved.getSecretJson()).getString("signSecret"));
    }
}
