package top.sshh.bililiverecoder.notification;

public interface NotificationMessageRenderer {

    NotificationEventType eventType();

    NotificationMessage render(NotificationEvent event);
}
