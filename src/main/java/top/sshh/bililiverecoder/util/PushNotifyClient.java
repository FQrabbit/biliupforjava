package top.sshh.bililiverecoder.util;

import top.sshh.bililiverecoder.notification.NotificationLegacyTagParser;

/**
 * Deprecated compatibility facade for old room push-tag fields.
 * New notification code should use the notification package directly.
 */
public final class PushNotifyClient {

    private PushNotifyClient() {
    }

    public static String normalizePushMsgTags(String pushMsgTags) {
        return NotificationLegacyTagParser.normalizeActivePushMsgTags(pushMsgTags);
    }

    public static boolean isTagEnabled(String pushMsgTags, String tag) {
        return NotificationLegacyTagParser.isTagEnabled(pushMsgTags, tag);
    }
}
