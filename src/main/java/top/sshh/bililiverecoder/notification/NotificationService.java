package top.sshh.bililiverecoder.notification;

import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.entity.NotificationChannel;
import top.sshh.bililiverecoder.entity.NotificationRule;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.repo.NotificationChannelRepository;
import top.sshh.bililiverecoder.repo.NotificationDeliveryRepository;
import top.sshh.bililiverecoder.repo.NotificationRuleRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.service.SystemConfigService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class NotificationService {

    private final NotificationChannelRepository notificationChannelRepository;
    private final NotificationDeliveryRepository notificationDeliveryRepository;
    private final NotificationRuleRepository notificationRuleRepository;
    private final RecordRoomRepository recordRoomRepository;
    private final SystemConfigService systemConfigService;
    private final NotificationDispatchService dispatchService;

    public NotificationService(NotificationChannelRepository notificationChannelRepository,
                               NotificationDeliveryRepository notificationDeliveryRepository,
                               NotificationRuleRepository notificationRuleRepository,
                               RecordRoomRepository recordRoomRepository,
                               SystemConfigService systemConfigService,
                               NotificationDispatchService dispatchService) {
        this.notificationChannelRepository = notificationChannelRepository;
        this.notificationDeliveryRepository = notificationDeliveryRepository;
        this.notificationRuleRepository = notificationRuleRepository;
        this.recordRoomRepository = recordRoomRepository;
        this.systemConfigService = systemConfigService;
        this.dispatchService = dispatchService;
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
        return dispatchService.sendToChannel(optional.get(), message);
    }

    public boolean isEnabled() {
        return systemConfigService.getAllConfigsMap()
                .getOrDefault(SystemConfigService.KEY_NOTIFICATION_ENABLED, "true")
                .equalsIgnoreCase("true");
    }

    public Map<String, Object> configOverview() {
        Map<String, Object> result = new HashMap<>();
        result.put("enabled", isEnabled());
        result.put("eventTypes", NotificationEventCatalog.activeDescriptors());
        result.put("deprecatedEventTypes", NotificationEventCatalog.allDescriptors().stream()
                .filter(descriptor -> !descriptor.active())
                .toList());
        result.put("channels", sanitizeChannels(notificationChannelRepository.findAll()));
        result.put("rules", activeRules(notificationRuleRepository.findAll()));
        result.put("rooms", sanitizeRooms(recordRoomRepository.findAllOrderBySortOrder()));
        result.put("deliveries", notificationDeliveryRepository.findTop50ByOrderByCreateTimeDesc());
        result.put("workspaceUsageAlertThresholdPercent", systemConfigService.getWorkspaceUsageAlertThresholdPercent());
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
            channel.setSecretJson(mergeSecretJson(channel.getSecretJson(), incoming.getSecretJson()));
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
        Optional<NotificationEventType> eventType = NotificationEventType.fromKey(incoming.getEventType());
        boolean systemEvent = eventType.map(NotificationEventType::systemScope).orElse(false);
        String normalizedRoomId = systemEvent ? "*" : normalizeRuleRoomId(incoming.getRoomId());
        if (systemEvent) {
            deleteRoomScopedRules(incoming.getEventType());
        }
        NotificationRule rule = incoming.getId() == null
                ? findExistingRule(incoming.getEventType(), normalizedRoomId).orElse(new NotificationRule())
                : notificationRuleRepository.findById(incoming.getId()).orElseGet(() -> findExistingRule(incoming.getEventType(), normalizedRoomId).orElse(new NotificationRule()));
        if (systemEvent && !NotificationRuleService.isGlobalRoomId(rule.getRoomId())) {
            rule = findExistingRule(incoming.getEventType(), "*").orElse(new NotificationRule());
        }
        rule.setEventType(incoming.getEventType());
        rule.setEventLabel(NotificationEventCatalog.activeDescriptor(incoming.getEventType())
                .map(NotificationEventDescriptor::label)
                .orElse(incoming.getEventLabel()));
        rule.setRoomId(normalizedRoomId);
        rule.setRoomName(systemEvent ? "系统级事件" : StringUtils.trimToNull(incoming.getRoomName()));
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

    private List<NotificationRule> activeRules(List<NotificationRule> rules) {
        return rules.stream()
                .filter(rule -> NotificationEventCatalog.isActiveKey(rule.getEventType()))
                .toList();
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

    private void deleteRoomScopedRules(String eventType) {
        notificationRuleRepository.findByEventType(eventType).stream()
                .filter(rule -> !NotificationRuleService.isGlobalRoomId(rule.getRoomId()))
                .forEach(notificationRuleRepository::delete);
    }

    private String normalizeRuleRoomId(String roomId) {
        String normalized = StringUtils.trimToNull(roomId);
        return normalized == null ? "*" : normalized;
    }

    private String mergeSecretJson(String currentSecretJson, String incomingSecretJson) {
        JSONObject merged = NotificationJson.parse(currentSecretJson);
        JSONObject incoming = NotificationJson.parse(incomingSecretJson);
        for (Map.Entry<String, Object> entry : incoming.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String str && StringUtils.isBlank(str)) {
                continue;
            }
            if (value != null) {
                merged.put(entry.getKey(), value);
            }
        }
        return merged.toJSONString();
    }

    private String defaultChannelName(String type) {
        if (WxPusherNotificationChannel.TYPE.equals(type)) {
            return "WxPusher";
        }
        if (WeComNotificationChannel.TYPE.equals(type)) {
            return "企业微信应用消息";
        }
        if (WeComWebhookNotificationChannel.TYPE.equals(type)) {
            return "企业微信群机器人";
        }
        if (DingTalkWebhookNotificationChannel.TYPE.equals(type)) {
            return "钉钉群机器人";
        }
        if (NtfyNotificationChannel.TYPE.equals(type)) {
            return "ntfy";
        }
        if (BarkNotificationChannel.TYPE.equals(type)) {
            return "Bark";
        }
        if (ServerChan3NotificationChannel.TYPE.equals(type)) {
            return "Server酱3";
        }
        return StringUtils.defaultIfBlank(type, "推送渠道");
    }
}
