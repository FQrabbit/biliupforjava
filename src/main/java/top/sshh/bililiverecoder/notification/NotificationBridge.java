package top.sshh.bililiverecoder.notification;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.util.PushNotifyClient;

@Component
public class NotificationBridge {

    private final NotificationService notificationService;

    public NotificationBridge(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @PostConstruct
    public void init() {
        PushNotifyClient.setNotificationService(notificationService);
    }
}
