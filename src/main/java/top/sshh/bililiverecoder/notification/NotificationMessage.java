package top.sshh.bililiverecoder.notification;

import lombok.Data;
import top.sshh.bililiverecoder.entity.RecordRoom;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class NotificationMessage {

    private NotificationEventType eventType;

    private String title;

    private String content;

    private String roomId;

    private String roomName;

    private LocalDateTime occurredAt = LocalDateTime.now();

    private Map<String, Object> metadata = new LinkedHashMap<>();

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

    public static NotificationMessage text(NotificationEvent event, String content) {
        NotificationMessage message = new NotificationMessage();
        message.setEventType(event.getEventType());
        message.setContent(content);
        message.setTitle(extractTitle(content));
        message.setRoomId(event.getRoomId());
        message.setRoomName(event.getRoomName());
        message.setOccurredAt(event.getOccurredAt());
        if (event.getAttributes() != null) {
            message.setMetadata(new LinkedHashMap<>(event.getAttributes()));
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
