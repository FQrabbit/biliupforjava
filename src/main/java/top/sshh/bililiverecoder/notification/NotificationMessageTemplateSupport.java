package top.sshh.bililiverecoder.notification;

import org.apache.commons.lang3.StringUtils;

final class NotificationMessageTemplateSupport {

    private NotificationMessageTemplateSupport() {
    }

    static String anchorName(NotificationEvent event) {
        String name = event == null ? "" : StringUtils.defaultIfBlank(event.getRoomName(), event.getRoomId());
        return StringUtils.isBlank(name) ? "【未知主播】" : "【" + name + "】";
    }

    static String liveRoomUrl(NotificationEvent event) {
        String roomId = event == null ? "" : event.getRoomId();
        if (StringUtils.isBlank(roomId)) {
            return "";
        }
        return "https://live.bilibili.com/" + roomId.trim();
    }

    static String videoUrl(String bvId) {
        if (StringUtils.isBlank(bvId)) {
            return "";
        }
        return "https://www.bilibili.com/video/" + bvId.trim();
    }

}
