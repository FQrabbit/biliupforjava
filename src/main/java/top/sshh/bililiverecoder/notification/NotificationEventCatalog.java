package top.sshh.bililiverecoder.notification;

import java.util.List;
import java.util.Optional;

public final class NotificationEventCatalog {

    private NotificationEventCatalog() {
    }

    public static List<NotificationEventDescriptor> activeDescriptors() {
        return NotificationEventType.activeValues().stream()
                .map(NotificationEventDescriptor::from)
                .toList();
    }

    public static List<NotificationEventDescriptor> allDescriptors() {
        return NotificationEventType.orderedValues().stream()
                .map(NotificationEventDescriptor::from)
                .toList();
    }

    public static boolean isActive(NotificationEventType type) {
        return type != null && type.active();
    }

    public static boolean isActiveKey(String key) {
        return NotificationEventType.fromKey(key)
                .map(NotificationEventType::active)
                .orElse(false);
    }

    public static Optional<NotificationEventDescriptor> activeDescriptor(String key) {
        return NotificationEventType.fromKey(key)
                .filter(NotificationEventType::active)
                .map(NotificationEventDescriptor::from);
    }
}
