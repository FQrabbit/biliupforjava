package top.sshh.bililiverecoder.notification;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class LiveStartedNotificationMessageRenderer implements NotificationMessageRenderer {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒");

    @Override
    public NotificationEventType eventType() {
        return NotificationEventType.LIVE_STREAM_STARTED;
    }

    @Override
    public NotificationMessage render(NotificationEvent event) {
        String content = event.stringAttribute("content");
        if (StringUtils.isBlank(content)) {
            content = """
                    主播%s开播了
                    房间名: %s
                    父分区: %s
                    子分区: %s
                    时间: %s
                    直播间: %s
                    """.formatted(
                    NotificationMessageTemplateSupport.anchorName(event),
                    StringUtils.defaultString(event.stringAttribute("liveTitle")),
                    StringUtils.defaultString(event.stringAttribute("areaNameParent")),
                    StringUtils.defaultString(event.stringAttribute("areaNameChild")),
                    formatOccurredAt(event.getOccurredAt()),
                    NotificationMessageTemplateSupport.liveRoomUrl(event)
            );
        }
        return NotificationMessage.text(event, content);
    }

    private String formatOccurredAt(LocalDateTime occurredAt) {
        return (occurredAt == null ? LocalDateTime.now() : occurredAt).format(TIME_FORMATTER);
    }
}
