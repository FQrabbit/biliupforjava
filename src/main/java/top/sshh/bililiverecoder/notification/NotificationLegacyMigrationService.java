package top.sshh.bililiverecoder.notification;

import com.alibaba.fastjson.JSON;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.NotificationChannel;
import top.sshh.bililiverecoder.entity.NotificationRule;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.repo.NotificationChannelRepository;
import top.sshh.bililiverecoder.repo.NotificationRuleRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.util.PushNotifyClient;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class NotificationLegacyMigrationService {

    private static final String DEFAULT_LEGACY_PUSH_TAGS = NotificationEventType.orderedValues().stream()
            .map(NotificationEventType::label)
            .collect(Collectors.joining(","));

    private final RecordRoomRepository recordRoomRepository;
    private final NotificationChannelRepository notificationChannelRepository;
    private final NotificationRuleRepository notificationRuleRepository;

    public NotificationLegacyMigrationService(RecordRoomRepository recordRoomRepository,
                                              NotificationChannelRepository notificationChannelRepository,
                                              NotificationRuleRepository notificationRuleRepository) {
        this.recordRoomRepository = recordRoomRepository;
        this.notificationChannelRepository = notificationChannelRepository;
        this.notificationRuleRepository = notificationRuleRepository;
    }

    public Map<String, Object> status(boolean revealSecrets) {
        List<RecordRoom> candidates = legacyRooms();
        List<Map<String, Object>> rooms = candidates.stream()
                .map(room -> describeRoom(room, revealSecrets))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("needsMigration", !rooms.isEmpty());
        result.put("count", rooms.size());
        result.put("rooms", rooms);
        result.put("backupJson", JSON.toJSONString(rooms));
        return result;
    }

    @Transactional
    public Map<String, Object> apply() {
        List<RecordRoom> candidates = legacyRooms();
        int channelCreated = 0;
        int ruleCreatedOrUpdated = 0;
        for (RecordRoom room : candidates) {
            Set<Long> channelIds = new LinkedHashSet<>();
            if (StringUtils.isNotBlank(room.getWxuid())) {
                NotificationChannel channel = findOrCreateChannel(
                        WxPusherNotificationChannel.TYPE,
                        "WxPusher UID " + mask(room.getWxuid()),
                        NotificationJson.object("uid", room.getWxuid()),
                        "{}"
                );
                if (channel.getCreateTime() != null && channel.getCreateTime().equals(channel.getUpdateTime())) {
                    channelCreated++;
                }
                channelIds.add(channel.getId());
            }
            if (StringUtils.isNotBlank(room.getServerChanSendKey())) {
                NotificationChannel channel = findOrCreateChannel(
                        ServerChan3NotificationChannel.TYPE,
                        "Server酱3 " + mask(room.getServerChanSendKey()),
                        NotificationJson.object("tags", StringUtils.defaultString(room.getServerChanChannel())),
                        NotificationJson.object("sendKey", room.getServerChanSendKey())
                );
                if (channel.getCreateTime() != null && channel.getCreateTime().equals(channel.getUpdateTime())) {
                    channelCreated++;
                }
                channelIds.add(channel.getId());
            }
            Set<NotificationEventType> enabledEvents = new LinkedHashSet<>(enabledEvents(room));
            if (!channelIds.isEmpty()) {
                for (NotificationEventType eventType : enabledEvents) {
                    upsertRule(room, eventType, true, channelIds);
                    ruleCreatedOrUpdated++;
                }
            }
            for (NotificationEventType eventType : disabledEvents(enabledEvents)) {
                upsertRule(room, eventType, false, Set.of());
                ruleCreatedOrUpdated++;
            }
            clearLegacyFields(room);
        }
        recordRoomRepository.saveAll(candidates);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("rooms", candidates.size());
        result.put("channels", channelCreated);
        result.put("rules", ruleCreatedOrUpdated);
        return result;
    }

    @Transactional
    public Map<String, Object> discard() {
        List<RecordRoom> candidates = legacyRooms();
        for (RecordRoom room : candidates) {
            clearLegacyFields(room);
        }
        recordRoomRepository.saveAll(candidates);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("rooms", candidates.size());
        return result;
    }

    private List<RecordRoom> legacyRooms() {
        return recordRoomRepository.findAllOrderBySortOrder().stream()
                .filter(this::hasLegacyConfig)
                .toList();
    }

    private boolean hasLegacyConfig(RecordRoom room) {
        return hasLegacyChannelConfig(room) || hasCustomLegacyEventConfig(room);
    }

    private boolean hasLegacyChannelConfig(RecordRoom room) {
        return StringUtils.isNotBlank(room.getWxuid())
                || StringUtils.isNotBlank(room.getServerChanSendKey())
                || StringUtils.isNotBlank(room.getServerChanChannel());
    }

    private boolean hasCustomLegacyEventConfig(RecordRoom room) {
        String rawTags = room.getPushMsgTags();
        if (StringUtils.isBlank(rawTags)) {
            return false;
        }
        String normalized = PushNotifyClient.normalizePushMsgTags(rawTags);
        if (StringUtils.isBlank(normalized)) {
            return true;
        }
        return !DEFAULT_LEGACY_PUSH_TAGS.equals(normalized);
    }

    private Map<String, Object> describeRoom(RecordRoom room, boolean revealSecrets) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", room.getId());
        map.put("roomId", room.getRoomId());
        map.put("uname", room.getUname());
        map.put("wxuid", revealSecrets ? room.getWxuid() : mask(room.getWxuid()));
        map.put("serverChanSendKey", revealSecrets ? room.getServerChanSendKey() : mask(room.getServerChanSendKey()));
        map.put("serverChanChannel", room.getServerChanChannel());
        map.put("pushMsgTags", room.getPushMsgTags());
        map.put("events", enabledEvents(room).stream().map(NotificationEventType::label).toList());
        List<String> channels = new ArrayList<>();
        if (StringUtils.isNotBlank(room.getWxuid())) {
            channels.add("WxPusher");
        }
        if (StringUtils.isNotBlank(room.getServerChanSendKey()) || StringUtils.isNotBlank(room.getServerChanChannel())) {
            channels.add("Server酱3");
        }
        map.put("channels", channels);
        return map;
    }

    private List<NotificationEventType> enabledEvents(RecordRoom room) {
        String normalized = PushNotifyClient.normalizePushMsgTags(room.getPushMsgTags());
        if (StringUtils.isBlank(normalized)) {
            return List.of();
        }
        return Arrays.stream(normalized.split(","))
                .map(StringUtils::trimToNull)
                .filter(Objects::nonNull)
                .map(label -> NotificationEventType.fromLegacyLabel(label).orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }

    private List<NotificationEventType> disabledEvents(Set<NotificationEventType> enabledEvents) {
        return NotificationEventType.orderedValues().stream()
                .filter(eventType -> !enabledEvents.contains(eventType))
                .toList();
    }

    private NotificationChannel findOrCreateChannel(String type, String name, String configJson, String secretJson) {
        for (NotificationChannel channel : notificationChannelRepository.findAll()) {
            if (type.equals(channel.getType())
                    && StringUtils.defaultString(configJson).equals(StringUtils.defaultString(channel.getConfigJson()))
                    && StringUtils.defaultString(secretJson).equals(StringUtils.defaultString(channel.getSecretJson()))) {
                return channel;
            }
        }
        NotificationChannel channel = new NotificationChannel();
        channel.setName(name);
        channel.setType(type);
        channel.setEnabled(true);
        channel.setConfigJson(configJson);
        channel.setSecretJson(secretJson);
        LocalDateTime now = LocalDateTime.now();
        channel.setCreateTime(now);
        channel.setUpdateTime(now);
        return notificationChannelRepository.save(channel);
    }

    private void upsertRule(RecordRoom room, NotificationEventType eventType, boolean enabled, Set<Long> channelIds) {
        String roomId = room.getRoomId();
        NotificationRule rule = notificationRuleRepository.findByEventType(eventType.key()).stream()
                .filter(item -> StringUtils.defaultString(item.getRoomId()).equals(StringUtils.defaultString(roomId)))
                .findFirst()
                .orElseGet(NotificationRule::new);
        Set<Long> mergedChannelIds = new LinkedHashSet<>(NotificationRuleService.parseChannelIds(rule.getChannelIds()));
        if (enabled) {
            mergedChannelIds.addAll(channelIds);
        } else {
            mergedChannelIds.clear();
        }
        LocalDateTime now = LocalDateTime.now();
        if (rule.getCreateTime() == null) {
            rule.setCreateTime(now);
        }
        rule.setUpdateTime(now);
        rule.setEventType(eventType.key());
        rule.setEventLabel(eventType.label());
        rule.setRoomId(roomId);
        rule.setRoomName(room.getUname());
        rule.setEnabled(enabled);
        rule.setChannelIds(NotificationRuleService.formatChannelIds(mergedChannelIds));
        notificationRuleRepository.save(rule);
    }

    private void clearLegacyFields(RecordRoom room) {
        room.setWxuid(null);
        room.setServerChanSendKey(null);
        room.setServerChanChannel(null);
        room.setPushMsgTags("");
    }

    private String mask(String value) {
        if (StringUtils.isBlank(value)) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 6) {
            return "***";
        }
        return trimmed.substring(0, Math.min(4, trimmed.length())) + "***" + trimmed.substring(trimmed.length() - 3);
    }
}
