package top.sshh.bililiverecoder.notification;

public record NotificationSendResult(boolean success, String errorMessage) {

    public static NotificationSendResult ok() {
        return new NotificationSendResult(true, null);
    }

    public static NotificationSendResult failed(String errorMessage) {
        return new NotificationSendResult(false, errorMessage);
    }
}
