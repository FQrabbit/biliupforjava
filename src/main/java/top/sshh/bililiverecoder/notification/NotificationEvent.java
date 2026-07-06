package top.sshh.bililiverecoder.notification;

import lombok.Data;
import top.sshh.bililiverecoder.entity.RecordRoom;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class NotificationEvent {

    private NotificationEventType eventType;

    private String roomId;

    private String roomName;

    private LocalDateTime occurredAt = LocalDateTime.now();

    private Map<String, Object> attributes = new LinkedHashMap<>();

    private String source;

    private String dedupeKey;

    public static NotificationEvent of(RecordRoom room, NotificationEventType eventType) {
        NotificationEvent event = new NotificationEvent();
        event.setEventType(eventType);
        if (room != null) {
            event.setRoomId(room.getRoomId());
            event.setRoomName(room.getUname());
        }
        return event;
    }

    public NotificationEvent add(String key, Object value) {
        if (key != null && value != null) {
            attributes.put(key, value);
        }
        return this;
    }

    public String stringAttribute(String key) {
        Object value = attributes == null ? null : attributes.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
