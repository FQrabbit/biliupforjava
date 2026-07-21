package top.sshh.bililiverecoder.notification;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
public class VideoAuditRejectedNotificationMessageRenderer implements NotificationMessageRenderer {

    @Override
    public NotificationEventType eventType() {
        return NotificationEventType.VIDEO_AUDIT_REJECTED;
    }

    @Override
    public NotificationMessage render(NotificationEvent event) {
        String content = event.stringAttribute("content");
        if (StringUtils.isBlank(content)) {
            String bvId = event.stringAttribute("bvId");
            content = """
                    稿件审核退回
                    主播: %s
                    标题: %s
                    BV号: %s
                    原因: %s
                    违规位置: %s
                    违规时段: %s
                    视频: %s
                    直播间: %s
                    """.formatted(
                    NotificationMessageTemplateSupport.anchorName(event),
                    StringUtils.defaultString(event.stringAttribute("videoTitle")),
                    StringUtils.defaultString(bvId),
                    StringUtils.defaultIfBlank(event.stringAttribute("reason"), "未知"),
                    StringUtils.defaultIfBlank(event.stringAttribute("violationPosition"), "未知"),
                    StringUtils.defaultIfBlank(event.stringAttribute("violationTime"), "未知"),
                    NotificationMessageTemplateSupport.videoUrl(bvId),
                    NotificationMessageTemplateSupport.liveRoomUrl(event)
            );
        }
        return NotificationMessage.text(event, content);
    }
}
