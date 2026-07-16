package top.sshh.bililiverecoder.job;

import org.junit.jupiter.api.Test;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.notification.NotificationEventType;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoSyncJobTest {

    private final videoSyncJob job = new videoSyncJob();

    @Test
    void keepsPendingReviewWhenRecentEditStillReturnsRejected() {
        RecordHistory history = pendingHistoryUpdatedMinutesAgo(30);

        assertTrue(job.shouldKeepPendingReviewAfterRecentEdit(history, -2));
        assertNull(job.resolveArchiveNotificationType(history.getCode(), history.getCode()));
    }

    @Test
    void allowsRejectedStateAfterRecentEditWindowExpires() {
        RecordHistory history = pendingHistoryUpdatedMinutesAgo(121);

        assertFalse(job.shouldKeepPendingReviewAfterRecentEdit(history, -2));
        assertEquals(NotificationEventType.VIDEO_AUDIT_REJECTED, job.resolveArchiveNotificationType(history.getCode(), -2));
    }

    @Test
    void allowsAuditPassedStatesAfterRecentEdit() {
        RecordHistory history = pendingHistoryUpdatedMinutesAgo(30);

        assertFalse(job.shouldKeepPendingReviewAfterRecentEdit(history, 0));
        assertFalse(job.shouldKeepPendingReviewAfterRecentEdit(history, -50));
        assertEquals(NotificationEventType.VIDEO_PUBLISH, job.resolveArchiveNotificationType(history.getCode(), 0));
    }

    @Test
    void allowsLockedStateAfterRecentEdit() {
        RecordHistory history = pendingHistoryUpdatedMinutesAgo(30);

        assertFalse(job.shouldKeepPendingReviewAfterRecentEdit(history, -4));
        assertEquals(NotificationEventType.VIDEO_AUDIT_LOCKED, job.resolveArchiveNotificationType(history.getCode(), -4));
    }

    private RecordHistory pendingHistoryUpdatedMinutesAgo(long minutes) {
        RecordHistory history = new RecordHistory();
        history.setCode(-1);
        history.setUpdateTime(LocalDateTime.now().minusMinutes(minutes));
        return history;
    }
}
