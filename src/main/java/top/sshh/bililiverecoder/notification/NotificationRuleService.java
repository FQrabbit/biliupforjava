package top.sshh.bililiverecoder.notification;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.entity.NotificationRule;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.repo.NotificationRuleRepository;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class NotificationRuleService {

    private final NotificationRuleRepository notificationRuleRepository;

    public NotificationRuleService(NotificationRuleRepository notificationRuleRepository) {
        this.notificationRuleRepository = notificationRuleRepository;
    }

    public List<NotificationRule> findCandidateRules(NotificationEventType eventType, RecordRoom room) {
        if (eventType == null) {
            return Collections.emptyList();
        }
        List<NotificationRule> rules = notificationRuleRepository.findByEventType(eventType.key());
        List<NotificationRule> roomRules = rules.stream()
                .filter(rule -> matchesExactRoom(rule, room))
                .toList();
        if (!roomRules.isEmpty()) {
            return roomRules;
        }
        return rules.stream()
                .filter(this::isGlobalRule)
                .toList();
    }

    public List<NotificationRule> findEnabledRules(NotificationEventType eventType, RecordRoom room) {
        return findCandidateRules(eventType, room).stream()
                .filter(NotificationRule::isEnabled)
                .filter(rule -> !parseChannelIds(rule.getChannelIds()).isEmpty())
                .toList();
    }

    public Set<Long> collectChannelIds(List<NotificationRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (NotificationRule rule : rules) {
            ids.addAll(parseChannelIds(rule.getChannelIds()));
        }
        return ids;
    }

    public static String formatChannelIds(Set<Long> channelIds) {
        if (channelIds == null || channelIds.isEmpty()) {
            return "";
        }
        return channelIds.stream()
                .filter(id -> id != null)
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }

    public static Set<Long> parseChannelIds(String raw) {
        if (StringUtils.isBlank(raw)) {
            return Collections.emptySet();
        }
        return Arrays.stream(raw.split(","))
                .map(StringUtils::trimToNull)
                .filter(token -> token != null)
                .map(token -> {
                    try {
                        return Long.parseLong(token);
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                })
                .filter(id -> id != null)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public static boolean isGlobalRoomId(String roomId) {
        String normalized = StringUtils.trimToNull(roomId);
        return normalized == null || "*".equals(normalized);
    }

    private boolean isGlobalRule(NotificationRule rule) {
        return rule != null && isGlobalRoomId(rule.getRoomId());
    }

    private boolean matchesExactRoom(NotificationRule rule, RecordRoom room) {
        if (rule == null || room == null || StringUtils.isBlank(room.getRoomId())) {
            return false;
        }
        String ruleRoomId = StringUtils.trimToNull(rule.getRoomId());
        return ruleRoomId != null && !"*".equals(ruleRoomId) && ruleRoomId.equals(room.getRoomId());
    }
}
