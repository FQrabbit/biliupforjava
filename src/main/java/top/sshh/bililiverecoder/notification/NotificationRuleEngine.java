package top.sshh.bililiverecoder.notification;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.entity.NotificationChannel;
import top.sshh.bililiverecoder.entity.NotificationRule;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.repo.NotificationChannelRepository;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class NotificationRuleEngine {

    private final NotificationRuleService notificationRuleService;
    private final NotificationChannelRepository notificationChannelRepository;

    public NotificationRuleEngine(NotificationRuleService notificationRuleService,
                                  NotificationChannelRepository notificationChannelRepository) {
        this.notificationRuleService = notificationRuleService;
        this.notificationChannelRepository = notificationChannelRepository;
    }

    public Set<NotificationChannel> resolveChannels(RecordRoom room, NotificationEventType eventType) {
        if (!NotificationEventCatalog.isActive(eventType)) {
            return Set.of();
        }
        List<NotificationRule> candidateRules = notificationRuleService.findCandidateRules(eventType, room);
        if (!candidateRules.isEmpty()) {
            Set<Long> channelIds = notificationRuleService.collectChannelIds(
                    candidateRules.stream()
                            .filter(NotificationRule::isEnabled)
                            .toList()
            );
            if (channelIds.isEmpty()) {
                return Set.of();
            }
            Set<Long> wanted = new HashSet<>(channelIds);
            return notificationChannelRepository.findAllById(wanted).stream()
                    .filter(NotificationChannel::isEnabled)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        return resolveLegacyChannels(room, eventType);
    }

    public boolean canSend(RecordRoom room, NotificationEventType eventType) {
        return !resolveChannels(room, eventType).isEmpty();
    }

    private Set<NotificationChannel> resolveLegacyChannels(RecordRoom room, NotificationEventType eventType) {
        Set<NotificationChannel> channels = new LinkedHashSet<>();
        if (!canSendLegacy(room, eventType)) {
            return channels;
        }
        if (StringUtils.isNotBlank(room.getWxuid())) {
            NotificationChannel channel = new NotificationChannel();
            channel.setName("旧版WxPusher-" + room.getRoomId());
            channel.setType(WxPusherNotificationChannel.TYPE);
            channel.setEnabled(true);
            channel.setConfigJson(NotificationJson.object("uid", room.getWxuid()));
            channel.setSecretJson("{}");
            channels.add(channel);
        }
        if (StringUtils.isNotBlank(room.getServerChanSendKey())) {
            NotificationChannel channel = new NotificationChannel();
            channel.setName("旧版Server酱3-" + room.getRoomId());
            channel.setType(ServerChan3NotificationChannel.TYPE);
            channel.setEnabled(true);
            channel.setConfigJson(NotificationJson.object("tags", StringUtils.defaultString(room.getServerChanChannel())));
            channel.setSecretJson(NotificationJson.object("sendKey", room.getServerChanSendKey()));
            channels.add(channel);
        }
        return channels;
    }

    private boolean canSendLegacy(RecordRoom room, NotificationEventType eventType) {
        if (room == null || eventType == null) {
            return false;
        }
        if (!NotificationLegacyTagParser.isTagEnabled(room.getPushMsgTags(), eventType.label())) {
            return false;
        }
        return StringUtils.isNotBlank(room.getWxuid()) || StringUtils.isNotBlank(room.getServerChanSendKey());
    }
}
