package top.sshh.bililiverecoder.entity;

import java.time.LocalDateTime;

public record RecordWebhookInboxStatusDto(Long eventId, String source, String eventType, String state,
                                          int attemptCount, LocalDateTime receivedAt,
                                          LocalDateTime updatedAt, LocalDateTime nextAttemptAt,
                                          String errorMessage) {
    public static RecordWebhookInboxStatusDto from(RecordWebhookInboxEvent event) {
        return new RecordWebhookInboxStatusDto(event.getId(), event.getSource(), event.getEventType(),
                event.getState(), event.getAttemptCount(), event.getReceivedAt(), event.getUpdatedAt(),
                event.getNextAttemptAt(), event.getErrorMessage());
    }
}
