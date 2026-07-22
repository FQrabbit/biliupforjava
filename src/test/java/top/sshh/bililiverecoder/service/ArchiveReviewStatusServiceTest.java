package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sshh.bililiverecoder.entity.BiliBiliUser;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.entity.data.BiliVideoAuditDetailResponse;
import top.sshh.bililiverecoder.entity.data.BiliVideoInfoResponse;
import top.sshh.bililiverecoder.entity.data.BiliVideoPartInfoResponse;
import top.sshh.bililiverecoder.repo.BiliUserRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.util.BiliApi;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ArchiveReviewStatusServiceTest {

    @Mock
    private RecordHistoryRepository historyRepository;
    @Mock
    private BiliUserRepository userRepository;
    @Mock
    private BiliArchiveReviewApiClient apiClient;

    private ArchiveReviewStatusService service;
    private RecordHistory history;
    private RecordRoom room;
    private BiliBiliUser user;

    @BeforeEach
    void setUp() {
        service = new ArchiveReviewStatusService(historyRepository, userRepository, apiClient, millis -> {});
        user = new BiliBiliUser();
        user.setId(7L);
        user.setLogin(true);
        history = new RecordHistory();
        history.setId(11L);
        history.setRoomId("room-1");
        history.setBvId("BV1test");
        history.setPublishUserId(7L);
        history.setCode(0);
        room = new RecordRoom();
        room.setRoomId("room-1");
        room.setUploadUserId(7L);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
    }

    @Test
    void refreshesRejectedStatusAndBlocksCleanup() {
        when(apiClient.getVideoInfo(user, "BV1test")).thenReturn(videoInfo(-2));

        ArchiveReviewStatusService.ReviewCheckResult result =
                service.checkForCleanup(history, room, service.newRound());

        assertEquals(ArchiveReviewStatusService.ReviewState.REJECTED, result.state());
        assertFalse(result.permitsCleanup());
        assertEquals(-2, history.getCode());
        verify(historyRepository).save(history);
    }

    @Test
    void retriesTransientFailureButDoesNotOverwriteLocalApproval() {
        when(apiClient.getVideoInfo(user, "BV1test"))
                .thenThrow(new RuntimeException("timeout"))
                .thenThrow(new RuntimeException("timeout again"));

        ArchiveReviewStatusService.ReviewCheckResult result =
                service.checkForCleanup(history, room, service.newRound());

        assertEquals(ArchiveReviewStatusService.ReviewState.REQUEST_FAILED, result.state());
        assertFalse(result.permitsCleanup());
        assertEquals(0, history.getCode());
        verify(apiClient, times(2)).getVideoInfo(user, "BV1test");
        verify(historyRepository, never()).save(any());
    }

    @Test
    void proceedsWhenTransientRetrySucceeds() {
        when(apiClient.getVideoInfo(user, "BV1test"))
                .thenThrow(new RuntimeException("timeout"))
                .thenReturn(videoInfo(0));

        ArchiveReviewStatusService.ReviewCheckResult result =
                service.checkForCleanup(history, room, service.newRound());

        assertEquals(ArchiveReviewStatusService.ReviewState.PASSED, result.state());
        assertTrue(result.permitsCleanup());
        verify(apiClient, times(2)).getVideoInfo(user, "BV1test");
        verify(historyRepository).save(history);
    }

    @Test
    void rateLimitDoesNotRetryOrOverwriteLocalApproval() {
        BiliVideoInfoResponse rateLimited = new BiliVideoInfoResponse();
        rateLimited.setCode(-412);
        rateLimited.setMessage("请求过于频繁");
        when(apiClient.getVideoInfo(user, "BV1test")).thenReturn(rateLimited);

        ArchiveReviewStatusService.ReviewCheckResult result =
                service.checkForCleanup(history, room, service.newRound());

        assertEquals(ArchiveReviewStatusService.ReviewState.REQUEST_FAILED, result.state());
        assertEquals(0, history.getCode());
        verify(apiClient).getVideoInfo(user, "BV1test");
        verify(historyRepository, never()).save(any());
    }

    @Test
    void roundCachesOneCheckPerHistory() {
        when(apiClient.getVideoInfo(user, "BV1test")).thenReturn(videoInfo(0));
        ArchiveReviewStatusService.ReviewRound round = service.newRound();

        ArchiveReviewStatusService.ReviewCheckResult first = service.checkForCleanup(history, room, round);
        ArchiveReviewStatusService.ReviewCheckResult second = service.checkForCleanup(history, room, round);

        assertSame(first, second);
        assertTrue(first.permitsCleanup());
        verify(apiClient).getVideoInfo(user, "BV1test");
    }

    @Test
    void privateArchiveRequiresMemberAndAuditConfirmation() {
        when(apiClient.getVideoInfo(user, "BV1test")).thenReturn(videoInfo(-50));
        when(apiClient.getVideoPartInfo(user, "BV1test"))
                .thenReturn(memberInfo(0, "{\"locked\":false}"));
        when(apiClient.getAuditDetail(user, "BV1test")).thenReturn(auditInfo(0, "{}"));

        ArchiveReviewStatusService.ReviewCheckResult result =
                service.checkForCleanup(history, room, service.newRound());

        assertEquals(ArchiveReviewStatusService.ReviewState.PRIVATE_PASSED, result.state());
        assertTrue(result.permitsCleanup());
        assertEquals(-50, history.getCode());
    }

    @Test
    void privateArchiveLockSignalForcesProtection() {
        when(apiClient.getVideoInfo(user, "BV1test")).thenReturn(videoInfo(-50));
        when(apiClient.getVideoPartInfo(user, "BV1test"))
                .thenReturn(memberInfo(0, "{\"message\":\"稿件已锁定\"}"));
        when(apiClient.getAuditDetail(user, "BV1test")).thenReturn(auditInfo(0, "{}"));

        ArchiveReviewStatusService.ReviewCheckResult result =
                service.checkForCleanup(history, room, service.newRound());

        assertEquals(ArchiveReviewStatusService.ReviewState.LOCKED, result.state());
        assertEquals(-4, history.getCode());
        assertTrue(history.isForceArchived());
        assertFalse(result.permitsCleanup());
    }

    private BiliVideoInfoResponse videoInfo(int state) {
        BiliVideoInfoResponse response = new BiliVideoInfoResponse();
        response.setCode(0);
        BiliVideoInfoResponse.BiliVideoInfo data = new BiliVideoInfoResponse.BiliVideoInfo();
        data.setState(state);
        data.setAid("123");
        data.setBvid("BV1test");
        response.setData(data);
        return response;
    }

    private BiliApi.ApiDebugResponse<BiliVideoPartInfoResponse> memberInfo(int state, String raw) {
        BiliVideoPartInfoResponse response = new BiliVideoPartInfoResponse();
        response.setCode(0);
        BiliVideoPartInfoResponse.BiliVideoInfo data = new BiliVideoPartInfoResponse.BiliVideoInfo();
        data.setState(state);
        response.setData(data);
        return new BiliApi.ApiDebugResponse<>(response, raw, "member-url");
    }

    private BiliApi.ApiDebugResponse<BiliVideoAuditDetailResponse> auditInfo(int state, String raw) {
        BiliVideoAuditDetailResponse response = new BiliVideoAuditDetailResponse();
        response.setCode(0);
        BiliVideoAuditDetailResponse.AuditData data = new BiliVideoAuditDetailResponse.AuditData();
        data.setState(state);
        response.setData(data);
        return new BiliApi.ApiDebugResponse<>(response, raw, "audit-url");
    }
}
