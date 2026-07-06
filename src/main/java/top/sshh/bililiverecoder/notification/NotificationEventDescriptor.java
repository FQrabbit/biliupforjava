package top.sshh.bililiverecoder.notification;

public record NotificationEventDescriptor(String key,
                                          String label,
                                          String group,
                                          String scope,
                                          String description,
                                          boolean active,
                                          String legacyLabel) {

    public static NotificationEventDescriptor from(NotificationEventType type) {
        return new NotificationEventDescriptor(
                type.key(),
                type.label(),
                type.group(),
                type.scope(),
                type.description(),
                type.active(),
                type.legacyLabels().isEmpty() ? type.label() : type.legacyLabels().get(0)
        );
    }
}
