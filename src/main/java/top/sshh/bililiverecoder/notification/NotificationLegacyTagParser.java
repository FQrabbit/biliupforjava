package top.sshh.bililiverecoder.notification;

import org.apache.commons.lang3.StringUtils;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

public final class NotificationLegacyTagParser {

    private NotificationLegacyTagParser() {
    }

    public static String normalizeActivePushMsgTags(String pushMsgTags) {
        Set<NotificationEventType> enabled = parseEnabledEvents(pushMsgTags);
        if (enabled.isEmpty()) {
            return "";
        }
        return NotificationEventType.activeValues().stream()
                .filter(enabled::contains)
                .filter(eventType -> !eventType.systemScope())
                .map(NotificationEventType::label)
                .collect(Collectors.joining(","));
    }

    public static Set<NotificationEventType> parseEnabledEvents(String pushMsgTags) {
        return parseEnabledTypes(pushMsgTags);
    }

    public static boolean isTagEnabled(String pushMsgTags, String tag) {
        if (StringUtils.isBlank(pushMsgTags) || StringUtils.isBlank(tag)) {
            return false;
        }
        return NotificationEventType.fromLegacyLabel(tag)
                .map(type -> parseEnabledTypes(pushMsgTags).contains(type))
                .orElse(false);
    }

    private static Set<NotificationEventType> parseEnabledTypes(String rawTags) {
        if (StringUtils.isBlank(rawTags)) {
            return Collections.emptySet();
        }
        String normalizedRaw = normalizeSeparators(rawTags);
        String[] pieces = normalizedRaw.split(",");
        Set<NotificationEventType> enabled = new LinkedHashSet<>();
        for (String piece : pieces) {
            String token = normalizeLabel(piece);
            if (StringUtils.isNotBlank(token)) {
                NotificationEventType.fromLegacyLabel(token).ifPresent(enabled::add);
            }
        }
        if (!enabled.isEmpty()) {
            return ordered(enabled);
        }

        String compact = normalizeLabel(normalizedRaw);
        for (NotificationEventType type : NotificationEventType.orderedValues()) {
            if (type.containsLegacyLabel(compact)) {
                enabled.add(type);
            }
        }
        return ordered(enabled);
    }

    private static Set<NotificationEventType> ordered(Set<NotificationEventType> enabled) {
        if (enabled.isEmpty()) {
            return Collections.emptySet();
        }
        return NotificationEventType.orderedValues().stream()
                .filter(enabled::contains)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String normalizeSeparators(String raw) {
        return StringUtils.defaultString(raw)
                .replace("，", ",")
                .replace("、", ",")
                .replace("|", ",")
                .replace("；", ",")
                .replace(";", ",")
                .replace("\n", ",")
                .replace("\r", ",")
                .replace("\t", ",")
                .trim();
    }

    private static String normalizeLabel(String raw) {
        String value = StringUtils.defaultString(raw).trim();
        while (value.startsWith("[") || value.startsWith("\"") || value.startsWith("'")) {
            value = value.substring(1).trim();
        }
        while (value.endsWith("]") || value.endsWith("\"") || value.endsWith("'")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        return value.replace(" ", "").replace("　", "");
    }
}
