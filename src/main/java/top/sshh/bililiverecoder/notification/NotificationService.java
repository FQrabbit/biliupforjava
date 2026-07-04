package top.sshh.bililiverecoder.notification;

import com.zjiecode.wxpusher.client.bean.Message;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.entity.NotificationChannel;
import top.sshh.bililiverecoder.entity.NotificationDelivery;
import top.sshh.bililiverecoder.entity.NotificationRule;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.repo.NotificationChannelRepository;
import top.sshh.bililiverecoder.repo.NotificationDeliveryRepository;
import top.sshh.bililiverecoder.repo.NotificationRuleRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.service.SystemConfigService;
import top.sshh.bililiverecoder.util.LogKvs;
import top.sshh.bililiverecoder.util.PushNotifyClient;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class NotificationService {

    private final NotificationChannelRepository notificationChannelRepository;
    private final NotificationDeliveryRepository notificationDeliveryRepository;
    private final NotificationRuleRepository notificationRuleRepository;
    private final RecordRoomRepository recordRoomRepository;
    private final NotificationRuleService notificationRuleService;
    private final SystemConfigService systemConfigService;
    private final Map<String, NotificationChannelAdapter> adapters;

    public NotificationService(NotificationChannelRepository notificationChannelRepository,
                               NotificationDeliveryRepository notificationDeliveryRepository,
                               NotificationRuleRepository notificationRuleRepository,
                               RecordRoomRepository recordRoomRepository,
                               NotificationRuleService notificationRuleService,
                               SystemConfigService systemConfigService,
                               List<NotificationChannelAdapter> adapters) {
        this.notificationChannelRepository = notificationChannelRepository;
        this.notificationDeliveryRepository = notificationDeliveryRepository;
        this.notificationRuleRepository = notificationRuleRepository;
        this.recordRoomRepository = recordRoomRepository;
        this.notificationRuleService = notificationRuleService;
        this.systemConfigService = systemConfigService;
        this.adapters = adapters.stream().collect(Collectors.toMap(NotificationChannelAdapter::type, Function.identity()));
    }

    public boolean canSend(RecordRoom room, NotificationEventType eventType) {
        if (!isEnabled() || eventType == null) {
            return false;
        }

        List<NotificationRule> candidateRules = notificationRuleService.findCandidateRules(eventType, room);
        if (!candidateRules.isEmpty()) {
            return !notificationRuleService.findEnabledRules(eventType, room).isEmpty();
        }

        return canSendLegacy(room, eventType);
    }

    public void sendText(RecordRoom room, NotificationEventType eventType, String content) {
        NotificationMessage message = NotificationMessage.text(room, eventType, content);
        sendAsync(room, message);
    }

    public void sendWxPusherMessage(RecordRoom room, NotificationEventType eventType, Message message) {
        if (message == null) {
            return;
        }
        NotificationMessage notificationMessage = NotificationMessage.text(room, eventType, message.getContent());
        sendAsync(room, notificationMessage);
    }

    public NotificationSendResult sendTest(Long channelId) {
        Optional<NotificationChannel> optional = notificationChannelRepository.findById(channelId);
        if (optional.isEmpty()) {
            return NotificationSendResult.failed("channel not found");
        }
        NotificationMessage message = new NotificationMessage();
        message.setEventType(NotificationEventType.LIVE_STREAM_STARTED);
        message.setTitle("biliupforjava测试通知");
        message.setContent("biliupforjava测试通知\n时间: " + LocalDateTime.now());
        return sendToChannel(optional.get(), message);
    }

    public boolean isEnabled() {
        return systemConfigService.getAllConfigsMap().getOrDefault(SystemConfigService.KEY_NOTIFICATION_ENABLED, "true").equalsIgnoreCase("true");
    }

    public Map<String, Object> configOverview() {
        Map<String, Object> result = new HashMap<>();
        result.put("enabled", isEnabled());
        result.put("eventTypes", NotificationEventType.orderedValues().stream()
                .map(type -> Map.of("key", type.key(), "label", type.label()))
                .toList());
        result.put("channels", sanitizeChannels(notificationChannelRepository.findAll()));
        result.put("rules", notificationRuleRepository.findAll());
        result.put("rooms", sanitizeRooms(recordRoomRepository.findAllOrderBySortOrder()));
        result.put("deliveries", notificationDeliveryRepository.findTop50ByOrderByCreateTimeDesc());
        return result;
    }

    public NotificationChannel saveChannel(NotificationChannel incoming) {
        NotificationChannel channel = incoming.getId() == null
                ? new NotificationChannel()
                : notificationChannelRepository.findById(incoming.getId()).orElse(new NotificationChannel());
        channel.setName(StringUtils.defaultIfBlank(incoming.getName(), defaultChannelName(incoming.getType())));
        channel.setType(incoming.getType());
        channel.setEnabled(incoming.isEnabled());
        channel.setConfigJson(StringUtils.defaultString(incoming.getConfigJson(), "{}"));
        if (StringUtils.isNotBlank(incoming.getSecretJson())) {
            channel.setSecretJson(incoming.getSecretJson());
        } else if (channel.getId() == null) {
            channel.setSecretJson("{}");
        }
        LocalDateTime now = LocalDateTime.now();
        if (channel.getCreateTime() == null) {
            channel.setCreateTime(now);
        }
        channel.setUpdateTime(now);
        return sanitizeChannel(notificationChannelRepository.save(channel));
    }

    public NotificationRule saveRule(NotificationRule incoming) {
        String normalizedRoomId = normalizeRuleRoomId(incoming.getRoomId());
        NotificationRule rule = incoming.getId() == null
                ? findExistingRule(incoming.getEventType(), normalizedRoomId).orElse(new NotificationRule())
                : notificationRuleRepository.findById(incoming.getId()).orElseGet(() -> findExistingRule(incoming.getEventType(), normalizedRoomId).orElse(new NotificationRule()));
        rule.setEventType(incoming.getEventType());
        rule.setEventLabel(NotificationEventType.fromKey(incoming.getEventType()).map(NotificationEventType::label).orElse(incoming.getEventLabel()));
        rule.setRoomId(normalizedRoomId);
        rule.setRoomName(StringUtils.trimToNull(incoming.getRoomName()));
        rule.setEnabled(incoming.isEnabled());
        rule.setChannelIds(StringUtils.defaultString(incoming.getChannelIds()));
        LocalDateTime now = LocalDateTime.now();
        if (rule.getCreateTime() == null) {
            rule.setCreateTime(now);
        }
        rule.setUpdateTime(now);
        return notificationRuleRepository.save(rule);
    }

    public void deleteRule(Long id) {
        if (id != null && notificationRuleRepository.existsById(id)) {
            notificationRuleRepository.deleteById(id);
        }
    }

    public List<NotificationChannel> sanitizeChannels(List<NotificationChannel> channels) {
        List<NotificationChannel> result = new ArrayList<>();
        for (NotificationChannel channel : channels) {
            result.add(sanitizeChannel(channel));
        }
        return result;
    }

    public NotificationChannel sanitizeChannel(NotificationChannel source) {
        NotificationChannel copy = new NotificationChannel();
        copy.setId(source.getId());
        copy.setName(source.getName());
        copy.setType(source.getType());
        copy.setEnabled(source.isEnabled());
        copy.setConfigJson(source.getConfigJson());
        copy.setSecretJson("");
        copy.setCreateTime(source.getCreateTime());
        copy.setUpdateTime(source.getUpdateTime());
        return copy;
    }

    private List<Map<String, Object>> sanitizeRooms(List<RecordRoom> rooms) {
        return rooms.stream().map(room -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", room.getId());
            item.put("roomId", room.getRoomId());
            item.put("roomName", StringUtils.defaultIfBlank(room.getUname(), room.getRoomId()));
            item.put("title", room.getTitle());
            item.put("streaming", room.isStreaming());
            item.put("recording", room.isRecording());
            item.put("sortOrder", room.getSortOrder());
            return item;
        }).toList();
    }

    private Optional<NotificationRule> findExistingRule(String eventType, String normalizedRoomId) {
        return notificationRuleRepository.findByEventType(eventType).stream()
                .filter(rule -> StringUtils.defaultString(normalizeRuleRoomId(rule.getRoomId()), "*")
                        .equals(StringUtils.defaultString(normalizedRoomId, "*")))
                .findFirst();
    }

    private String normalizeRuleRoomId(String roomId) {
        String normalized = StringUtils.trimToNull(roomId);
        return normalized == null ? "*" : normalized;
    }

    private void sendAsync(RecordRoom room, NotificationMessage message) {
        CompletableFuture.runAsync(() -> dispatch(room, message));
    }

    private void dispatch(RecordRoom room, NotificationMessage message) {
        try {
            if (message == null || message.getEventType() == null || !isEnabled()) {
                return;
            }
            Set<NotificationChannel> channels = resolveChannels(room, message.getEventType());
            for (NotificationChannel channel : channels) {
                sendToChannel(channel, message);
            }
        } catch (Exception e) {
            log.warn("[BLR] {}", LogKvs.event("Notify.Dispatch.Failed")
                    .addIfNotBlank("eventType", message == null || message.getEventType() == null ? null : message.getEventType().key())
                    .addIfNotBlank("roomId", room == null ? null : room.getRoomId())
                    .addIfNotBlank("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()), e);
        }
    }

    private Set<NotificationChannel> resolveChannels(RecordRoom room, NotificationEventType eventType) {
        List<NotificationRule> candidateRules = notificationRuleService.findCandidateRules(eventType, room);
        if (!candidateRules.isEmpty()) {
            Set<Long> channelIds = notificationRuleService.collectChannelIds(
                    candidateRules.stream()
                            .filter(NotificationRule::isEnabled)
                            .toList()
            );
            Set<Long> wanted = new HashSet<>(channelIds);
            return notificationChannelRepository.findAllById(wanted).stream()
                    .filter(NotificationChannel::isEnabled)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        return resolveLegacyChannels(room, eventType);
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
        if (!PushNotifyClient.isTagEnabled(room.getPushMsgTags(), eventType.label())) {
            return false;
        }
        return StringUtils.isNotBlank(room.getWxuid()) || StringUtils.isNotBlank(room.getServerChanSendKey());
    }

    private NotificationSendResult sendToChannel(NotificationChannel channel, NotificationMessage message) {
        NotificationDelivery delivery = new NotificationDelivery();
        delivery.setEventType(message.getEventType() == null ? null : message.getEventType().key());
        delivery.setEventLabel(message.getEventType() == null ? null : message.getEventType().label());
        delivery.setRoomId(message.getRoomId());
        delivery.setRoomName(message.getRoomName());
        delivery.setChannelId(channel.getId());
        delivery.setChannelType(channel.getType());
        delivery.setChannelName(channel.getName());
        delivery.setTitle(message.getTitle());
        delivery.setContent(message.getContent());
        delivery.setStatus("PENDING");
        delivery.setCreateTime(LocalDateTime.now());
        delivery = notificationDeliveryRepository.save(delivery);

        NotificationChannelAdapter adapter = adapters.get(channel.getType());
        NotificationSendResult result;
        if (adapter == null) {
            result = NotificationSendResult.failed("unsupported channel type: " + channel.getType());
        } else {
            result = adapter.send(channel, message);
        }

        delivery.setSentTime(LocalDateTime.now());
        delivery.setStatus(result.success() ? "SUCCESS" : "FAILED");
        delivery.setErrorMessage(result.errorMessage());
        notificationDeliveryRepository.save(delivery);
        return result;
    }

    private String defaultChannelName(String type) {
        if (WxPusherNotificationChannel.TYPE.equals(type)) {
            return "WxPusher";
        }
        if (WeComNotificationChannel.TYPE.equals(type)) {
            return "企业微信应用消息";
        }
        if (ServerChan3NotificationChannel.TYPE.equals(type)) {
            return "Server酱3";
        }
        return StringUtils.defaultIfBlank(type, "推送渠道");
    }
}
