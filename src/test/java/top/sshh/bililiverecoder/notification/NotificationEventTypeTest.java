package top.sshh.bililiverecoder.notification;

import org.junit.jupiter.api.Test;

import java.util.List;

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
    void fromLegacyLabelShouldResolveOldVideoPublishLabel() {
        assertEquals(NotificationEventType.VIDEO_PUBLISH,
                NotificationEventType.fromLegacyLabel("视频投稿").orElseThrow());
    }

    @Test
    void activeDescriptorsShouldOnlyExposeCurrentEvents() {
        assertEquals(
                List.of(
                        "live.stream.started",
                        "live.stream.ended",
                        "publish.video",
                        "publish.audit.rejected",
                        "publish.audit.locked",
                        "workspace.usage.alert"
                ),
                NotificationEventCatalog.activeDescriptors().stream()
                        .map(NotificationEventDescriptor::key)
                        .toList()
        );
    }

    @Test
    void workspaceUsageDescriptorShouldBeSystemScoped() {
        NotificationEventDescriptor descriptor = NotificationEventCatalog.activeDescriptor("workspace.usage.alert").orElseThrow();

        assertEquals("system", descriptor.scope());
    }

    @Test
    void unknownLegacyLabelShouldReturnEmpty() {
        assertTrue(NotificationEventType.fromLegacyLabel("不存在的事件").isEmpty());
    }
}
