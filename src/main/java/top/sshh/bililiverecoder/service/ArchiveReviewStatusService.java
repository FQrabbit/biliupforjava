package top.sshh.bililiverecoder.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.entity.BiliBiliUser;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.entity.data.BiliVideoAuditDetailResponse;
import top.sshh.bililiverecoder.entity.data.BiliVideoInfoResponse;
import top.sshh.bililiverecoder.entity.data.BiliVideoPartInfoResponse;
import top.sshh.bililiverecoder.repo.BiliUserRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.util.BiliApi;
import top.sshh.bililiverecoder.util.LogKvs;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
public class ArchiveReviewStatusService {

    private static final long RETRY_DELAY_MS = 3000L;
    private static final int PLATFORM_FAILURE_THRESHOLD = 3;

    public enum ReviewState {
        PASSED,
        PRIVATE_PASSED,
        PENDING,
        REJECTED,
        LOCKED,
        MISSING,
        UNKNOWN,
        AUTH_FAILED,
        REQUEST_FAILED
    }

    public record ReviewCheckResult(Long historyId,
                                    Long accountId,
                                    ReviewState state,
                                    Integer observedCode,
                                    String reason,
                                    LocalDateTime checkedAt) {
        public boolean permitsCleanup() {
            return state == ReviewState.PASSED || state == ReviewState.PRIVATE_PASSED;
        }
    }

    public static final class ReviewRound {
        private final Map<Long, ReviewCheckResult> results = new HashMap<>();
        private final Set<Long> blockedAccounts = new HashSet<>();
        private int consecutiveRequestFailures;
        private boolean platformUnavailable;

        public boolean isPlatformUnavailable() {
            return platformUnavailable;
        }
    }

    @FunctionalInterface
    interface RetrySleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final RecordHistoryRepository historyRepository;
    private final BiliUserRepository userRepository;
    private final BiliArchiveReviewApiClient apiClient;
    private final RetrySleeper retrySleeper;

    @Autowired
    public ArchiveReviewStatusService(RecordHistoryRepository historyRepository,
                                      BiliUserRepository userRepository,
                                      BiliArchiveReviewApiClient apiClient) {
        this(historyRepository, userRepository, apiClient, Thread::sleep);
    }

    ArchiveReviewStatusService(RecordHistoryRepository historyRepository,
                               BiliUserRepository userRepository,
                               BiliArchiveReviewApiClient apiClient,
                               RetrySleeper retrySleeper) {
        this.historyRepository = historyRepository;
        this.userRepository = userRepository;
        this.apiClient = apiClient;
        this.retrySleeper = retrySleeper;
    }

    public ReviewRound newRound() {
        return new ReviewRound();
    }

    public ReviewCheckResult checkForCleanup(RecordHistory history, RecordRoom room, ReviewRound round) {
        ReviewRound activeRound = round == null ? newRound() : round;
        if (history != null && history.getId() != null) {
            ReviewCheckResult cached = activeRound.results.get(history.getId());
            if (cached != null) return cached;
        }

        AuthContext auth = resolveAuth(history, room);
        ReviewCheckResult result;
        if (history == null || history.getId() == null) {
            result = result(null, auth.accountId(), ReviewState.UNKNOWN, null, "稿件记录不存在");
        } else if (StringUtils.isBlank(history.getBvId())) {
            result = result(history.getId(), auth.accountId(), ReviewState.UNKNOWN, null, "稿件缺少 BV 号");
        } else if (auth.user() == null) {
            result = result(history.getId(), auth.accountId(), ReviewState.AUTH_FAILED, null, auth.reason());
        } else if (activeRound.blockedAccounts.contains(auth.accountId())) {
            result = result(history.getId(), auth.accountId(), ReviewState.AUTH_FAILED, null, "本轮已停止使用失效账号");
        } else if (activeRound.platformUnavailable) {
            result = result(history.getId(), auth.accountId(), ReviewState.REQUEST_FAILED, null, "本轮平台请求已熔断");
        } else {
            result = inspect(history, auth);
        }

        recordRoundResult(activeRound, result);
        if (history != null && history.getId() != null) activeRound.results.put(history.getId(), result);
        logResult(history, room, result);
        return result;
    }

    private ReviewCheckResult inspect(RecordHistory history, AuthContext auth) {
        ApiCall<BiliVideoInfoResponse> publicInfo = callWithRetry(
                () -> apiClient.getVideoInfo(auth.user(), history.getBvId()));
        if (!publicInfo.success()) {
            return requestFailure(history, auth, publicInfo.reason());
        }
        BiliVideoInfoResponse response = publicInfo.value();
        if (response == null) return requestFailure(history, auth, "view API 返回空响应");

        if (response.getCode() == 0) {
            if (response.getData() == null) return requestFailure(history, auth, "view API 缺少 data");
            int state = response.getData().getState();
            if (state == -50) return inspectPrivate(history, auth, response.getData());
            persistVideoState(history, response.getData(), state, state == -4);
            return result(history.getId(), auth.accountId(), mapArchiveState(state), state,
                    stateReason(state));
        }

        if (isAuthFailure(response.getCode(), response.getMessage())) {
            return result(history.getId(), auth.accountId(), ReviewState.AUTH_FAILED,
                    response.getCode(), StringUtils.defaultIfBlank(response.getMessage(), "账号认证失败"));
        }
        if (response.getCode() == -404) return inspectPublicMissing(history, auth);
        if (response.getCode() == 62002) {
            return result(history.getId(), auth.accountId(), ReviewState.MISSING, response.getCode(),
                    StringUtils.defaultIfBlank(response.getMessage(), "稿件不可见或已删除"));
        }
        if (isRequestFailureCode(response.getCode(), response.getMessage())) {
            return requestFailure(history, auth,
                    "view API: " + response.getCode() + " " + StringUtils.defaultString(response.getMessage()));
        }
        return result(history.getId(), auth.accountId(), ReviewState.UNKNOWN, response.getCode(),
                StringUtils.defaultIfBlank(response.getMessage(), "无法识别的稿件状态"));
    }

    private ReviewCheckResult inspectPublicMissing(RecordHistory history, AuthContext auth) {
        ApiCall<BiliApi.ApiDebugResponse<BiliVideoPartInfoResponse>> memberCall = callWithRetry(
                () -> apiClient.getVideoPartInfo(auth.user(), history.getBvId()));
        if (!memberCall.success()) return requestFailure(history, auth, memberCall.reason());
        BiliVideoPartInfoResponse member = parsed(memberCall.value());
        if (member == null) return requestFailure(history, auth, "member API 返回空响应");
        if (isAuthFailure(member.getCode(), member.getMessage())) {
            return result(history.getId(), auth.accountId(), ReviewState.AUTH_FAILED, member.getCode(), member.getMessage());
        }
        if (isRequestFailureCode(member.getCode(), member.getMessage())) {
            return requestFailure(history, auth,
                    "member API: " + member.getCode() + " " + StringUtils.defaultString(member.getMessage()));
        }
        if (member.getCode() == -404) {
            return result(history.getId(), auth.accountId(), ReviewState.MISSING, -404, "公开和会员接口均确认稿件不存在");
        }
        if (member.getCode() != 0 || member.getData() == null) {
            return result(history.getId(), auth.accountId(), ReviewState.UNKNOWN, member.getCode(),
                    "member API 未确认稿件状态: " + StringUtils.defaultString(member.getMessage()));
        }
        return inspectPrivateDetails(history, auth, memberCall.value(), null);
    }

    private ReviewCheckResult inspectPrivate(RecordHistory history,
                                             AuthContext auth,
                                             BiliVideoInfoResponse.BiliVideoInfo videoInfo) {
        ApiCall<BiliApi.ApiDebugResponse<BiliVideoPartInfoResponse>> memberCall = callWithRetry(
                () -> apiClient.getVideoPartInfo(auth.user(), history.getBvId()));
        if (!memberCall.success()) return requestFailure(history, auth, memberCall.reason());
        return inspectPrivateDetails(history, auth, memberCall.value(), videoInfo);
    }

    private ReviewCheckResult inspectPrivateDetails(RecordHistory history,
                                                    AuthContext auth,
                                                    BiliApi.ApiDebugResponse<BiliVideoPartInfoResponse> memberDebug,
                                                    BiliVideoInfoResponse.BiliVideoInfo videoInfo) {
        BiliVideoPartInfoResponse member = parsed(memberDebug);
        if (member == null) return requestFailure(history, auth, "member API 返回空响应");
        if (isAuthFailure(member.getCode(), member.getMessage())) {
            return result(history.getId(), auth.accountId(), ReviewState.AUTH_FAILED, member.getCode(), member.getMessage());
        }
        if (isRequestFailureCode(member.getCode(), member.getMessage())) {
            return requestFailure(history, auth,
                    "member API: " + member.getCode() + " " + StringUtils.defaultString(member.getMessage()));
        }
        if (member.getCode() != 0 || member.getData() == null) {
            return result(history.getId(), auth.accountId(), ReviewState.UNKNOWN, member.getCode(),
                    "member API 未确认私密稿件: " + StringUtils.defaultString(member.getMessage()));
        }

        ApiCall<BiliApi.ApiDebugResponse<BiliVideoAuditDetailResponse>> auditCall = callWithRetry(
                () -> apiClient.getAuditDetail(auth.user(), history.getBvId()));
        if (!auditCall.success()) return requestFailure(history, auth, auditCall.reason());
        BiliVideoAuditDetailResponse audit = parsed(auditCall.value());
        if (audit == null) return requestFailure(history, auth, "审核详情 API 返回空响应");
        if (isAuthFailure(audit.getCode(), audit.getMessage())) {
            return result(history.getId(), auth.accountId(), ReviewState.AUTH_FAILED, audit.getCode(), audit.getMessage());
        }
        if (isRequestFailureCode(audit.getCode(), audit.getMessage())) {
            return requestFailure(history, auth,
                    "审核详情 API: " + audit.getCode() + " " + StringUtils.defaultString(audit.getMessage()));
        }
        if (audit.getCode() != 0 || audit.getData() == null) {
            return result(history.getId(), auth.accountId(), ReviewState.UNKNOWN, audit.getCode(),
                    "审核详情未确认私密稿件: " + StringUtils.defaultString(audit.getMessage()));
        }

        ReviewState detailState = privateDetailState(memberDebug, auditCall.value());
        if (detailState == ReviewState.LOCKED) {
            persistCode(history, -4, true);
            return result(history.getId(), auth.accountId(), ReviewState.LOCKED, -4, "会员或审核接口检测到稿件锁定");
        }
        if (detailState == ReviewState.REJECTED) {
            persistCode(history, -2, false);
            return result(history.getId(), auth.accountId(), ReviewState.REJECTED, -2, "会员或审核接口检测到稿件退回");
        }
        if (detailState == ReviewState.PENDING) {
            persistCode(history, audit.getData().getState(), false);
            return result(history.getId(), auth.accountId(), ReviewState.PENDING,
                    audit.getData().getState(), "私密稿件仍在审核中");
        }

        if (videoInfo != null) persistVideoState(history, videoInfo, -50, false);
        else persistCode(history, -50, false);
        return result(history.getId(), auth.accountId(), ReviewState.PRIVATE_PASSED, -50,
                "私密稿件已由会员及审核接口确认未锁定");
    }

    private ReviewState privateDetailState(BiliApi.ApiDebugResponse<BiliVideoPartInfoResponse> memberDebug,
                                           BiliApi.ApiDebugResponse<BiliVideoAuditDetailResponse> auditDebug) {
        BiliVideoPartInfoResponse member = parsed(memberDebug);
        BiliVideoAuditDetailResponse audit = parsed(auditDebug);
        if (containsLockedRaw(memberDebug == null ? null : memberDebug.getRaw())
                || containsLockedRaw(auditDebug == null ? null : auditDebug.getRaw())
                || member.getData().getState() == -4
                || audit.getData().getState() == -4) return ReviewState.LOCKED;
        if (member.getData().getState() == -2) return ReviewState.REJECTED;
        if (member.getData().getVideos() != null) {
            for (BiliVideoPartInfoResponse.Video video : member.getData().getVideos()) {
                if (video == null) continue;
                if (video.getFailCode() == -4 || containsLockedText(video.getFailDesc())) return ReviewState.LOCKED;
                if (video.getFailCode() == -2) return ReviewState.REJECTED;
            }
        }
        if (audit.getData().getState() == -2) return ReviewState.REJECTED;
        int auditState = audit.getData().getState();
        int memberState = member.getData().getState();
        if (auditState != 0 && auditState != -50 || memberState != 0 && memberState != -50) {
            return ReviewState.PENDING;
        }
        return ReviewState.PRIVATE_PASSED;
    }

    private void persistVideoState(RecordHistory history,
                                   BiliVideoInfoResponse.BiliVideoInfo data,
                                   int state,
                                   boolean locked) {
        history.setCode(state);
        history.setAvId(data.getAid());
        if (StringUtils.isNotBlank(data.getBvid())) history.setBvId(data.getBvid());
        history.setCoverUrl(data.getPic());
        if (locked) markLocked(history);
        history.setUpdateTime(LocalDateTime.now());
        historyRepository.save(history);
    }

    private void persistCode(RecordHistory history, int state, boolean locked) {
        history.setCode(state);
        if (locked) markLocked(history);
        history.setUpdateTime(LocalDateTime.now());
        historyRepository.save(history);
    }

    private void markLocked(RecordHistory history) {
        history.setForceArchived(true);
        history.setUpload(false);
        history.setSendReply(true);
        history.setStreaming(false);
        history.setRecording(false);
    }

    private AuthContext resolveAuth(RecordHistory history, RecordRoom room) {
        Long accountId = history == null ? null : history.getPublishUserId();
        if (accountId == null && room != null) accountId = room.getUploadUserId();
        if (accountId == null) return new AuthContext(null, null, "未配置投稿账号");
        Optional<BiliBiliUser> user = userRepository.findById(accountId);
        if (user.isEmpty()) return new AuthContext(accountId, null, "投稿账号不存在");
        if (!user.get().isLogin()) return new AuthContext(accountId, null, "投稿账号登录已失效");
        return new AuthContext(accountId, user.get(), null);
    }

    private <T> ApiCall<T> callWithRetry(ApiSupplier<T> supplier) {
        try {
            return ApiCall.success(supplier.get());
        } catch (Exception first) {
            if (!isRetryableException(first)) return ApiCall.failure(errorMessage(first));
            try {
                retrySleeper.sleep(RETRY_DELAY_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return ApiCall.failure("请求重试等待被中断");
            }
            try {
                return ApiCall.success(supplier.get());
            } catch (Exception second) {
                return ApiCall.failure(errorMessage(second));
            }
        }
    }

    private boolean isRetryableException(Exception exception) {
        String name = exception.getClass().getName().toLowerCase();
        String message = StringUtils.defaultString(exception.getMessage()).toLowerCase();
        return !name.contains("json")
                && !message.contains("parse")
                && !message.contains("解析")
                && !message.contains("parameter")
                && !message.contains("参数")
                && !message.contains("cookie")
                && !message.contains("未登录")
                && !message.contains("登录失效");
    }

    private void recordRoundResult(ReviewRound round, ReviewCheckResult result) {
        if (result.state() == ReviewState.AUTH_FAILED && result.accountId() != null) {
            round.blockedAccounts.add(result.accountId());
        }
        if (result.state() == ReviewState.REQUEST_FAILED) {
            round.consecutiveRequestFailures++;
            if (round.consecutiveRequestFailures >= PLATFORM_FAILURE_THRESHOLD) round.platformUnavailable = true;
        } else {
            round.consecutiveRequestFailures = 0;
        }
    }

    private void logResult(RecordHistory history, RecordRoom room, ReviewCheckResult result) {
        LogKvs details = LogKvs.event("ArchiveReview.CleanupCheck")
                .add("roomId", room == null ? null : room.getRoomId())
                .add("historyId", result.historyId())
                .addIfNotBlank("bvid", history == null ? null : history.getBvId())
                .add("accountId", result.accountId())
                .add("state", result.state())
                .add("observedCode", result.observedCode())
                .add("permitted", result.permitsCleanup())
                .addIfNotBlank("reason", result.reason());
        boolean firstFailure = !StringUtils.contains(result.reason(), "本轮已停止")
                && !StringUtils.contains(result.reason(), "本轮平台请求已熔断");
        if (firstFailure && (result.state() == ReviewState.AUTH_FAILED
                || result.state() == ReviewState.REQUEST_FAILED)) {
            log.warn("[BLR] {}", details);
        } else {
            log.info("[BLR] {}", details);
        }
    }

    private ReviewCheckResult requestFailure(RecordHistory history, AuthContext auth, String reason) {
        return result(history.getId(), auth.accountId(), ReviewState.REQUEST_FAILED, null, reason);
    }

    private ReviewCheckResult result(Long historyId, Long accountId, ReviewState state, Integer code, String reason) {
        return new ReviewCheckResult(historyId, accountId, state, code, reason, LocalDateTime.now());
    }

    private ReviewState mapArchiveState(int state) {
        return switch (state) {
            case 0 -> ReviewState.PASSED;
            case -2 -> ReviewState.REJECTED;
            case -4 -> ReviewState.LOCKED;
            case -1, -9, -30, -40 -> ReviewState.PENDING;
            default -> ReviewState.UNKNOWN;
        };
    }

    private String stateReason(int state) {
        return switch (state) {
            case 0 -> "稿件审核通过";
            case -2 -> "稿件已被退回";
            case -4 -> "稿件已被锁定";
            case -1, -9, -30, -40 -> "稿件仍在审核中";
            default -> "未识别的稿件状态: " + state;
        };
    }

    private boolean isAuthFailure(int code, String message) {
        return code == -101 || code == -111
                || StringUtils.contains(message, "未登录")
                || StringUtils.containsIgnoreCase(message, "cookie")
                || StringUtils.contains(message, "登录失效");
    }

    private boolean isRequestFailureCode(int code, String message) {
        return code == -400 || code == -412 || code == -429 || code == -509
                || StringUtils.contains(message, "频繁")
                || StringUtils.contains(message, "风控")
                || StringUtils.contains(message, "限流");
    }

    private boolean containsLockedText(String value) {
        return StringUtils.contains(value, "锁定") || StringUtils.containsIgnoreCase(value, "lock");
    }

    private boolean containsLockedRaw(String value) {
        if (StringUtils.isBlank(value)) return false;
        String normalized = value.replaceAll("\\s+", "").toLowerCase();
        return normalized.contains("锁定")
                || normalized.contains("\"locked\":true")
                || normalized.contains("\"is_locked\":true")
                || normalized.contains("\"lock\":true");
    }

    private String errorMessage(Exception exception) {
        return exception.getClass().getSimpleName() + ":" + StringUtils.defaultString(exception.getMessage());
    }

    private static <T> T parsed(BiliApi.ApiDebugResponse<T> response) {
        return response == null ? null : response.getParsed();
    }

    private record AuthContext(Long accountId, BiliBiliUser user, String reason) {}

    private record ApiCall<T>(T value, String reason, boolean success) {
        static <T> ApiCall<T> success(T value) {
            return new ApiCall<>(value, null, true);
        }

        static <T> ApiCall<T> failure(String reason) {
            return new ApiCall<>(null, reason, false);
        }
    }

    @FunctionalInterface
    private interface ApiSupplier<T> {
        T get() throws Exception;
    }
}
