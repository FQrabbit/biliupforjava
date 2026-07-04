package top.sshh.bililiverecoder.notification;

import lombok.Data;
import top.sshh.bililiverecoder.entity.RecordRoom;

import java.time.LocalDateTime;

@Data
public class NotificationMessage {

    private NotificationEventType eventType;

    private String title;

    private String content;

    private String roomId;

    private String roomName;

    private LocalDateTime occurredAt = LocalDateTime.now();

    public static NotificationMessage text(RecordRoom room, NotificationEventType eventType, String content) {
        NotificationMessage message = new NotificationMessage();
        message.setEventType(eventType);
        message.setContent(content);
        message.setTitle(extractTitle(content));
        if (room != null) {
            message.setRoomId(room.getRoomId());
            message.setRoomName(room.getUname());
        }
        return message;
    }

    private static String extractTitle(String content) {
        String safe = content == null ? "" : content.trim();
        if (safe.isEmpty()) {
            return "biliupforjava通知";
        }
        int idx = safe.indexOf('\n');
        String title = idx > 0 ? safe.substring(0, idx).trim() : safe;
        return title.length() > 64 ? title.substring(0, 64) : title;
    }
}
