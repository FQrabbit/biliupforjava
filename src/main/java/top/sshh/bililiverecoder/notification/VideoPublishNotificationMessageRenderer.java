package top.sshh.bililiverecoder.notification;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class VideoPublishNotificationMessageRenderer implements NotificationMessageRenderer {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒");

    @Override
    public NotificationEventType eventType() {
        return NotificationEventType.VIDEO_PUBLISH;
    }

    @Override
    public NotificationMessage render(NotificationEvent event) {
        String content = event.stringAttribute("content");
        if (StringUtils.isBlank(content)) {
            String bvId = event.stringAttribute("bvId");
            content = """
                    稿件审核通过
                    主播: %s
                    标题: %s
                    BV号: %s
                    状态: %s
                    时间: %s
                    弹幕总数: %s
                    流水: %s
                    时长: %s
                    分P数: %s
                    视频: %s
                    直播间: %s
                    """.formatted(
                    NotificationMessageTemplateSupport.anchorName(event),
                    StringUtils.defaultString(event.stringAttribute("videoTitle")),
                    StringUtils.defaultString(bvId),
                    StringUtils.defaultIfBlank(event.stringAttribute("status"), "审核通过"),
                    formatOccurredAt(event.getOccurredAt()),
                    StringUtils.defaultIfBlank(event.stringAttribute("danmakuCount"), "0"),
                    StringUtils.defaultIfBlank(event.stringAttribute("revenueText"), "¥0.00"),
                    StringUtils.defaultIfBlank(event.stringAttribute("durationText"), "0秒"),
                    StringUtils.defaultIfBlank(event.stringAttribute("partCount"), "0"),
                    NotificationMessageTemplateSupport.videoUrl(bvId),
                    NotificationMessageTemplateSupport.liveRoomUrl(event)
            );
        }
        return NotificationMessage.text(event, content);
    }

    private String formatOccurredAt(LocalDateTime occurredAt) {
        return (occurredAt == null ? LocalDateTime.now() : occurredAt).format(TIME_FORMATTER);
    }
}
