package top.sshh.bililiverecoder.notification;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationMessageRendererTest {

    @Test
    void liveStartedRendererBuildsMessageFromStructuredEvent() {
        NotificationEvent event = new NotificationEvent();
        event.setEventType(NotificationEventType.LIVE_STREAM_STARTED);
        event.setRoomId("1001");
        event.setRoomName("主播A");
        event.setOccurredAt(LocalDateTime.of(2026, 7, 5, 1, 2, 3));
        event.add("liveTitle", "测试直播")
                .add("areaNameParent", "网游")
                .add("areaNameChild", "综合");

        NotificationMessage message = new LiveStartedNotificationMessageRenderer().render(event);

        assertEquals(NotificationEventType.LIVE_STREAM_STARTED, message.getEventType());
        assertTrue(message.getContent().contains("主播【主播A】开播了"));
        assertTrue(message.getContent().contains("房间名: 测试直播"));
        assertTrue(message.getContent().contains("父分区: 网游"));
        assertTrue(message.getContent().contains("子分区: 综合"));
        assertTrue(message.getContent().contains("时间: 2026年07月05日01点02分03秒"));
        assertTrue(message.getContent().contains("直播间: https://live.bilibili.com/1001"));
    }

    @Test
    void videoPublishRendererBuildsMessageFromStructuredEvent() {
        NotificationEvent event = new NotificationEvent();
        event.setEventType(NotificationEventType.VIDEO_PUBLISH);
        event.setRoomId("1002");
        event.setRoomName("主播B");
        event.setOccurredAt(LocalDateTime.of(2026, 7, 5, 4, 5, 6));
        event.add("videoTitle", "直播标题")
                .add("bvId", "BV123")
                .add("status", "公开浏览")
                .add("danmakuCount", 1234)
                .add("revenueText", "¥56.78")
                .add("durationText", "1小时2分3秒")
                .add("partCount", 4);

        NotificationMessage message = new VideoPublishNotificationMessageRenderer().render(event);

        assertEquals(NotificationEventType.VIDEO_PUBLISH, message.getEventType());
        assertTrue(message.getContent().contains("稿件审核通过"));
        assertTrue(message.getContent().contains("主播: 【主播B】"));
        assertTrue(message.getContent().contains("标题: 直播标题"));
        assertTrue(message.getContent().contains("BV号: BV123"));
        assertTrue(message.getContent().contains("状态: 公开浏览"));
        assertTrue(message.getContent().contains("时间: 2026年07月05日04点05分06秒"));
        assertTrue(message.getContent().contains("弹幕总数: 1234"));
        assertTrue(message.getContent().contains("流水: ¥56.78"));
        assertTrue(message.getContent().contains("时长: 1小时2分3秒"));
        assertTrue(message.getContent().contains("分P数: 4"));
        assertTrue(message.getContent().contains("视频: https://www.bilibili.com/video/BV123"));
        assertTrue(message.getContent().contains("直播间: https://live.bilibili.com/1002"));
    }

    @Test
    void liveEndedRendererBuildsMessageFromStructuredEvent() {
        NotificationEvent event = new NotificationEvent();
        event.setEventType(NotificationEventType.LIVE_STREAM_ENDED);
        event.setRoomId("1003");
        event.setRoomName("主播C");
        event.setOccurredAt(LocalDateTime.of(2026, 7, 5, 7, 8, 9));
        event.add("liveTitle", "下播测试")
                .add("durationText", "2小时3分4秒");

        NotificationMessage message = new LiveEndedNotificationMessageRenderer().render(event);

        assertEquals(NotificationEventType.LIVE_STREAM_ENDED, message.getEventType());
        assertTrue(message.getContent().contains("主播【主播C】下播了"));
        assertTrue(message.getContent().contains("房间名: 下播测试"));
        assertTrue(message.getContent().contains("时间: 2026年07月05日07点08分09秒"));
        assertTrue(message.getContent().contains("持续时间: 2小时3分4秒"));
    }

    @Test
    void rejectedAndLockedRenderersBuildReasonMessages() {
        NotificationEvent rejected = new NotificationEvent();
        rejected.setEventType(NotificationEventType.VIDEO_AUDIT_REJECTED);
        rejected.setRoomId("1004");
        rejected.setRoomName("主播D");
        rejected.add("videoTitle", "退回标题")
                .add("bvId", "BVREJECT")
                .add("reason", "建议修改游戏玩家昵称、画面（左上部）位置内容")
                .add("violationPosition", "P13内容")
                .add("violationTime", "P13(00:26:18-00:27:02)");

        NotificationEvent locked = new NotificationEvent();
        locked.setEventType(NotificationEventType.VIDEO_AUDIT_LOCKED);
        locked.setRoomId("1005");
        locked.setRoomName("主播E");
        locked.add("videoTitle", "锁定标题")
                .add("bvId", "BVLOCK")
                .add("reason", "建议修改违规画面")
                .add("violationPosition", "P2内容")
                .add("violationTime", "P2(00:01:00-00:01:10)");

        NotificationMessage rejectedMessage = new VideoAuditRejectedNotificationMessageRenderer().render(rejected);
        NotificationMessage lockedMessage = new VideoAuditLockedNotificationMessageRenderer().render(locked);

        assertTrue(rejectedMessage.getContent().contains("稿件审核退回"));
        assertTrue(rejectedMessage.getContent().contains("主播: 【主播D】"));
        assertTrue(rejectedMessage.getContent().contains("原因: 建议修改游戏玩家昵称、画面（左上部）位置内容"));
        assertTrue(rejectedMessage.getContent().contains("违规位置: P13内容"));
        assertTrue(rejectedMessage.getContent().contains("违规时段: P13(00:26:18-00:27:02)"));
        assertTrue(rejectedMessage.getContent().contains("视频: https://www.bilibili.com/video/BVREJECT"));
        assertTrue(rejectedMessage.getContent().contains("直播间: https://live.bilibili.com/1004"));
        assertTrue(lockedMessage.getContent().contains("稿件已被锁定"));
        assertTrue(lockedMessage.getContent().contains("主播: 【主播E】"));
        assertTrue(lockedMessage.getContent().contains("原因: 建议修改违规画面"));
        assertTrue(lockedMessage.getContent().contains("违规位置: P2内容"));
        assertTrue(lockedMessage.getContent().contains("违规时段: P2(00:01:00-00:01:10)"));
        assertTrue(lockedMessage.getContent().contains("视频: https://www.bilibili.com/video/BVLOCK"));
        assertTrue(lockedMessage.getContent().contains("直播间: https://live.bilibili.com/1005"));
    }

    @Test
    void auditRenderersUseUnknownWhenDetailsAreUnavailable() {
        NotificationEvent event = new NotificationEvent();
        event.setEventType(NotificationEventType.VIDEO_AUDIT_REJECTED);

        NotificationMessage message = new VideoAuditRejectedNotificationMessageRenderer().render(event);

        assertTrue(message.getContent().contains("原因: 未知"));
        assertTrue(message.getContent().contains("违规位置: 未知"));
        assertTrue(message.getContent().contains("违规时段: 未知"));
    }

    @Test
    void workspaceUsageRendererBuildsThresholdMessage() {
        NotificationEvent event = new NotificationEvent();
        event.setEventType(NotificationEventType.WORKSPACE_USAGE_ALERT);
        event.add("workPath", "D:/record")
                .add("probePath", "D:/")
                .add("usedPercent", 90.5)
                .add("alertThresholdPercent", 90)
                .add("totalSize", "100.00 GB")
                .add("freeSize", "9.50 GB")
                .add("pendingUploadCount", 2)
                .add("queuedUploadCount", 1)
                .add("activeUploadCount", 1);

        NotificationMessage message = new WorkspaceUsageAlertNotificationMessageRenderer().render(event);

        assertEquals(NotificationEventType.WORKSPACE_USAGE_ALERT, message.getEventType());
        assertTrue(message.getContent().contains("工作目录空间已达到预警阈值"));
        assertTrue(message.getContent().contains("已用空间: 90.5%"));
        assertTrue(message.getContent().contains("预警阈值: 90%"));
    }
}
