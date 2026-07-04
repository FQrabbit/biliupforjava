package top.sshh.bililiverecoder.notification;

import top.sshh.bililiverecoder.entity.NotificationChannel;

public interface NotificationChannelAdapter {

    String type();

    NotificationSendResult send(NotificationChannel channel, NotificationMessage message);
}
