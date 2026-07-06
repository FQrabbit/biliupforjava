package top.sshh.bililiverecoder.notification;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
public class WorkspaceUsageAlertNotificationMessageRenderer implements NotificationMessageRenderer {

    @Override
    public NotificationEventType eventType() {
        return NotificationEventType.WORKSPACE_USAGE_ALERT;
    }

    @Override
    public NotificationMessage render(NotificationEvent event) {
        String content = event.stringAttribute("content");
        if (StringUtils.isBlank(content)) {
            content = """
                    工作目录空间已达到预警阈值
                    工作目录: %s
                    所在路径: %s
                    已用空间: %s%%
                    预警阈值: %s%%
                    总空间: %s
                    可用空间: %s
                    待上传分P: %s
                    队列中分P: %s
                    上传中分P: %s
                    """.formatted(
                    StringUtils.defaultString(event.stringAttribute("workPath")),
                    StringUtils.defaultString(event.stringAttribute("probePath")),
                    StringUtils.defaultString(event.stringAttribute("usedPercent")),
                    StringUtils.defaultString(event.stringAttribute("alertThresholdPercent")),
                    StringUtils.defaultString(event.stringAttribute("totalSize")),
                    StringUtils.defaultString(event.stringAttribute("freeSize")),
                    StringUtils.defaultString(event.stringAttribute("pendingUploadCount")),
                    StringUtils.defaultString(event.stringAttribute("queuedUploadCount")),
                    StringUtils.defaultString(event.stringAttribute("activeUploadCount"))
            );
        }
        return NotificationMessage.text(event, content);
    }
}
