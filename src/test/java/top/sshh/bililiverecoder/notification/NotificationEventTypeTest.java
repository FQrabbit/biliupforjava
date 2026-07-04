package top.sshh.bililiverecoder.notification;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationEventTypeTest {

    @Test
    void fromKeyShouldResolveStableEventKey() {
        assertEquals(NotificationEventType.LIVE_STREAM_STARTED,
                NotificationEventType.fromKey("live.stream.started").orElseThrow());
    }

    @Test
    void fromLegacyLabelShouldIgnoreSpaces() {
        assertEquals(NotificationEventType.HIGH_LEVEL_DANMAKU,
                NotificationEventType.fromLegacyLabel(" 高 级 弹 幕 ").orElseThrow());
    }

    @Test
    void unknownLegacyLabelShouldReturnEmpty() {
        assertTrue(NotificationEventType.fromLegacyLabel("不存在的事件").isEmpty());
    }
}
