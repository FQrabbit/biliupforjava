package top.sshh.bililiverecoder.job;

import org.junit.jupiter.api.Test;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.data.BiliVideoAuditDetailResponse;
import top.sshh.bililiverecoder.notification.NotificationEventType;

import java.time.LocalDateTime;
import java.util.List;

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

    @Test
    void auditNotificationUsesModifyAdviceAndViolationDetails() {
        BiliVideoAuditDetailResponse.ProblemDetail detail = new BiliVideoAuditDetailResponse.ProblemDetail();
        detail.setReject_reason("您的视频不予审核通过");
        detail.setModify_advise("建议修改游戏玩家昵称、画面（左上部）位置内容");
        detail.setProblem_description("很长的规则说明");
        detail.setViolation_position("P13内容");
        detail.setViolation_time("P13(00:26:18-00:27:02)");

        videoSyncJob.AuditNotificationDetails details = job.summarizeAuditNotificationDetails(
                auditResponse(detail), null);

        assertEquals("建议修改游戏玩家昵称、画面（左上部）位置内容", details.resolveReason(null));
        assertEquals("P13内容", details.violationPosition());
        assertEquals("P13(00:26:18-00:27:02)", details.violationTime());
    }

    @Test
    void auditNotificationFallsBackOnlyWhenStructuredDetailsAreUnavailable() {
        BiliVideoAuditDetailResponse.ProblemDetail fallbackOnly = new BiliVideoAuditDetailResponse.ProblemDetail();
        fallbackOnly.setReject_reason("现有退回原因");
        fallbackOnly.setProblem_description("现有规则说明");
        videoSyncJob.AuditNotificationDetails fallbackDetails = job.summarizeAuditNotificationDetails(
                auditResponse(fallbackOnly), null);

        assertEquals("现有退回原因；现有规则说明", fallbackDetails.resolveReason(null));

        BiliVideoAuditDetailResponse.ProblemDetail positionOnly = new BiliVideoAuditDetailResponse.ProblemDetail();
        positionOnly.setReject_reason("不应使用的现有原因");
        positionOnly.setViolation_position("P3内容");
        videoSyncJob.AuditNotificationDetails positionDetails = job.summarizeAuditNotificationDetails(
                auditResponse(positionOnly), null);

        assertNull(positionDetails.resolveReason("不应使用的现有原因"));
        assertEquals("P3内容", positionDetails.violationPosition());
    }

    private RecordHistory pendingHistoryUpdatedMinutesAgo(long minutes) {
        RecordHistory history = new RecordHistory();
        history.setCode(-1);
        history.setUpdateTime(LocalDateTime.now().minusMinutes(minutes));
        return history;
    }

    private BiliVideoAuditDetailResponse auditResponse(BiliVideoAuditDetailResponse.ProblemDetail... details) {
        BiliVideoAuditDetailResponse.AuditData data = new BiliVideoAuditDetailResponse.AuditData();
        data.setState(-2);
        data.setProblem_detail(List.of(details));
        BiliVideoAuditDetailResponse response = new BiliVideoAuditDetailResponse();
        response.setCode(0);
        response.setData(data);
        return response;
    }
}
