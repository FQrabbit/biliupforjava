package top.sshh.bililiverecoder.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.entity.BiliBiliUser;
import top.sshh.bililiverecoder.entity.LiveMsg;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.job.LiveMsgSendSync;
import top.sshh.bililiverecoder.repo.BiliUserRepository;
import top.sshh.bililiverecoder.repo.LiveMsgRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.service.impl.LiveMsgService;
import top.sshh.bililiverecoder.util.BiliApi;
import top.sshh.bililiverecoder.util.LogKvs;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class DanmakuSendScheduler {

    private static final long RATE_LIMIT_PAUSE_MS = 120_000L;
    private static final long ERROR_PAUSE_MS = 5_000L;
    private static final long BUSY_RETRY_MS = 1_000L;

    private final TaskExecutor replyExecutor;
    private final TaskExecutor highExecutor;
    private final TaskExecutor normalExecutor;
    private final TaskScheduler scheduler;
    private final LiveMsgRepository msgRepository;
    private final RecordHistoryRepository historyRepository;
    private final RecordHistoryPartRepository partRepository;
    private final RecordRoomRepository roomRepository;
    private final BiliUserRepository userRepository;
    private final LiveMsgService liveMsgService;
    private final SystemConfigService systemConfigService;
    private final HistoryMsgQueueCleanupService msgQueueCleanupService;


    private final ArrayDeque<Long> highPartQueue = new ArrayDeque<>();
    private final ArrayDeque<Long> normalPartQueue = new ArrayDeque<>();
    private final Set<Long> pendingHighPartIds = ConcurrentHashMap.newKeySet();
    private final Set<Long> pendingNormalPartIds = ConcurrentHashMap.newKeySet();
    private final Set<Long> pendingReplyHistoryIds = ConcurrentHashMap.newKeySet();
    private final Set<Long> busyUserIds = ConcurrentHashMap.newKeySet();
    private final Object danmakuPermitLock = new Object();
    private final Object commentPermitLock = new Object();
    private volatile long nextGlobalDanmakuSendAtMs = 0L;
    private volatile long nextGlobalCommentSendAtMs = 0L;
    private final Map<Long, Long> nextDanmakuAccountPauseAtByUid = new ConcurrentHashMap<>();
    private final Map<Long, Long> nextCommentAccountPauseAtByUid = new ConcurrentHashMap<>();

    private final AtomicBoolean highDispatchScheduled = new AtomicBoolean(false);
    private final AtomicBoolean normalDispatchScheduled = new AtomicBoolean(false);
    private final AtomicInteger activeNormalWorkers = new AtomicInteger(0);
    private final AtomicInteger normalUserCursor = new AtomicInteger(0);

    public DanmakuSendScheduler(@Qualifier("danmakuExecutor") TaskExecutor replyExecutor,
                                @Qualifier("danmakuHighExecutor") TaskExecutor highExecutor,
                                @Qualifier("danmakuNormalExecutor") TaskExecutor normalExecutor,
                                @Qualifier("danmakuTaskScheduler") TaskScheduler scheduler,
                                LiveMsgRepository msgRepository,
                                RecordHistoryRepository historyRepository,
                                RecordHistoryPartRepository partRepository,
                                RecordRoomRepository roomRepository,
                                BiliUserRepository userRepository,
                                LiveMsgService liveMsgService,
                                SystemConfigService systemConfigService,
                                HistoryMsgQueueCleanupService msgQueueCleanupService) {
        this.replyExecutor = replyExecutor;
        this.highExecutor = highExecutor;
        this.normalExecutor = normalExecutor;
        this.scheduler = scheduler;
        this.msgRepository = msgRepository;
        this.historyRepository = historyRepository;
        this.partRepository = partRepository;
        this.roomRepository = roomRepository;
        this.userRepository = userRepository;
        this.liveMsgService = liveMsgService;
        this.systemConfigService = systemConfigService;
        this.msgQueueCleanupService = msgQueueCleanupService;
    }

    public boolean enqueueReply(Long historyId, Runnable task) {
        if (historyId == null || task == null) {
            return false;
        }
        if (!pendingReplyHistoryIds.add(historyId)) {
            log.debug("[BLR] {}", LogKvs.event("DanmakuDispatch.Reply.Duplicate")
                    .add("historyId", historyId)
                    .add("pendingReplies", pendingReplyHistoryIds.size()));
            return false;
        }
        try {
            replyExecutor.execute(() -> {
                try {
                    task.run();
                } finally {
                    pendingReplyHistoryIds.remove(historyId);
                }
            });
            log.info("[BLR] {}", LogKvs.event("DanmakuDispatch.Reply.Enqueued")
                    .add("historyId", historyId)
                    .add("pendingReplies", pendingReplyHistoryIds.size()));
            return true;
        } catch (RejectedExecutionException e) {
            pendingReplyHistoryIds.remove(historyId);
            log.warn("[BLR] {}", LogKvs.event("DanmakuDispatch.Reply.Rejected")
                    .add("historyId", historyId)
                    .addIfNotBlank("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()), e);
            return false;
        }
    }

    public boolean enqueueHighPart(Long partId) {
        if (partId == null) {
            return false;
        }
        if (!pendingHighPartIds.add(partId)) {
            log.debug("[BLR] {}", LogKvs.event("DanmakuDispatch.High.Duplicate")
                    .add("partId", partId)
                    .add("pendingParts", pendingHighPartIds.size()));
            return false;
        }
        synchronized (highPartQueue) {
            highPartQueue.offer(partId);
        }
        log.info("[BLR] {}", LogKvs.event("DanmakuDispatch.High.Enqueued")
                .add("partId", partId)
                .add("pendingParts", pendingHighPartIds.size()));
        scheduleHighDispatch(0L);
        return true;
    }

    public boolean enqueueNormalPart(Long partId) {
        if (partId == null) {
            return false;
        }
        if (!pendingNormalPartIds.add(partId)) {
            log.debug("[BLR] {}", LogKvs.event("DanmakuDispatch.Normal.Duplicate")
                    .add("partId", partId)
                    .add("pendingParts", pendingNormalPartIds.size()));
            return false;
        }
        synchronized (normalPartQueue) {
            normalPartQueue.offer(partId);
        }
        log.info("[BLR] {}", LogKvs.event("DanmakuDispatch.Normal.Enqueued")
                .add("partId", partId)
                .add("pendingParts", pendingNormalPartIds.size()));
        scheduleNormalDispatch(0L);
        return true;
    }

    public int pendingReplyCount() {
        return pendingReplyHistoryIds.size();
    }

    public int pendingHighPartCount() {
        return pendingHighPartIds.size();
    }

    public int pendingNormalPartCount() {
        return pendingNormalPartIds.size();
    }

    private void scheduleHighDispatch(long delayMs) {
        if (!highDispatchScheduled.compareAndSet(false, true)) {
            return;
        }
        scheduler.schedule(() -> {
            highDispatchScheduled.set(false);
            runHighDispatch();
        }, Instant.now().plusMillis(Math.max(0L, delayMs)));
    }

    private void runHighDispatch() {
        Long partId = poll(highPartQueue);
        if (partId == null) {
            return;
        }
        try {
            highExecutor.execute(() -> processHighPart(partId));
        } catch (RejectedExecutionException e) {
            requeueHighPart(partId, ERROR_PAUSE_MS);
            log.warn("[BLR] {}", LogKvs.event("DanmakuDispatch.High.Rejected")
                    .add("partId", partId)
                    .addIfNotBlank("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()), e);
        }
    }

    private void processHighPart(Long partId) {
        long startNs = System.nanoTime();
        try {
            if (LiveMsgSendSync.skipAdvancedPartIds.contains(partId)) {
                pendingHighPartIds.remove(partId);
                log.info("[BLR] {}", LogKvs.event("LiveMsgSendSync.HighLevel.SkipByManual")
                        .add("partId", partId));
                return;
            }
            LiveMsg msg = firstPendingMessage(partId, 1);
            if (msg == null) {
                pendingHighPartIds.remove(partId);
                return;
            }
            Optional<RecordHistoryPart> partOptional = partRepository.findById(partId);
            if (partOptional.isEmpty()) {
                markMessageDone(msg);
                requeueHighPart(partId, 0L);
                return;
            }
            RecordHistoryPart part = partOptional.get();
            RecordHistory history = null;
            if (part.getHistoryId() != null) {
                history = historyRepository.findById(part.getHistoryId()).orElse(null);
            }
            if (history == null || history.isForceArchived() || history.getCode() == -4) {
                if (history != null && history.getId() != null) {
                    msgQueueCleanupService.cleanupByHistoryId(history.getId(),
                            new HistoryMsgQueueCleanupService.CleanupOptions(true, true, true, false),
                            false,
                            "dispatch-high-archived");
                } else {
                    markMessageFailed(msg, -3);
                }
                pendingHighPartIds.remove(partId);
                log.info("[BLR] {}", LogKvs.event("DanmakuDispatch.High.SkipArchivedOrLocked")
                        .add("partId", partId)
                        .add("historyId", history == null ? null : history.getId())
                        .add("code", history == null ? null : history.getCode())
                        .add("forceArchived", history != null && history.isForceArchived()));
                return;
            }
            RecordRoom room = roomRepository.findByRoomId(part.getRoomId());
            if (room == null || !Boolean.TRUE.equals(room.getSendSc()) || room.getUploadUserId() == null) {
                markMessageDone(msg);
                requeueHighPart(partId, 0L);
                return;
            }
            Optional<BiliBiliUser> userOptional = userRepository.findById(room.getUploadUserId());
            if (userOptional.isEmpty() || userOptional.get().getUid() == null || !userOptional.get().isLogin()) {
                markMessageDone(msg);
                requeueHighPart(partId, 0L);
                return;
            }
            BiliBiliUser user = userOptional.get();
            long accountWaitMs = danmakuAccountCooldownWaitMs(user.getUid());
            if (accountWaitMs > 0L) {
                requeueHighPart(partId, accountWaitMs);
                log.debug("[BLR] {}", LogKvs.event("DanmakuDispatch.High.UserCooldown")
                        .add("uid", user.getUid())
                        .addIfNotBlank("uname", user.getUname())
                        .add("waitMs", accountWaitMs)
                        .add("partId", partId));
                return;
            }
            long globalWaitMs = globalDanmakuWaitMs();
            if (globalWaitMs > 0L) {
                requeueHighPart(partId, globalWaitMs);
                log.debug("[BLR] {}", LogKvs.event("DanmakuDispatch.High.GlobalCooldown")
                        .add("waitMs", globalWaitMs)
                        .add("partId", partId));
                return;
            }
            if (!busyUserIds.add(user.getUid())) {
                requeueHighPart(partId, BUSY_RETRY_MS);
                return;
            }
            long businessDelayMs = systemConfigService.getDanmakuSendIntervalMs();
            long reserveWaitMs = reserveGlobalDanmakuSlot(businessDelayMs);
            if (reserveWaitMs > 0L) {
                busyUserIds.remove(user.getUid());
                requeueHighPart(partId, reserveWaitMs);
                return;
            }
            int code;
            boolean privateFlow = history != null && history.getCode() == -50;
            boolean switchedPublic = false;
            try {
                if (privateFlow) {
                    switchHighVisibility(history, user, 0, "LiveMsgSendSync.Visibility.High.SwitchPublic.Response");
                    switchedPublic = true;
                    sleepQuietly(15_000L, "highPrivatePublicWait");
                }
                code = liveMsgService.sendMsg(user, msg);
            } catch (VisibilitySwitchException e) {
                if (!switchedPublic) {
                    handleHighVisibilitySwitchPublicFailure(room, part, msg, user, e, businessDelayMs);
                    return;
                }
                throw e;
            } finally {
                try {
                    if (switchedPublic) {
                        try {
                            switchHighVisibility(history, user, 1, "LiveMsgSendSync.Visibility.High.SwitchPrivate.Response");
                            sleepQuietly(5_000L, "highPrivateSwitchBackWait");
                        } catch (VisibilitySwitchException e) {
                            if (isVisibilityRateLimitCode(e.code())) {
                                markDanmakuAccountPause(user.getUid(), RATE_LIMIT_PAUSE_MS);
                            }
                            log.warn("[BLR] {}", LogKvs.event("LiveMsgSendSync.Visibility.High.SwitchPrivate.Deferred")
                                    .addIfNotBlank("uname", user.getUname())
                                    .add("code", e.code())
                                    .addIfNotBlank("bvid", msg.getBvid())
                                    .add("partId", msg.getPartId()));
                        }
                    }
                } finally {
                    busyUserIds.remove(user.getUid());
                }
            }
            if (code != 0 && code != 36703) {
                log.error("[BLR] {}", LogKvs.event("LiveMsgSendSync.HighLevel.Send.Failed")
                        .addIfNotBlank("uname", user.getUname())
                        .add("code", code)
                        .addIfNotBlank("bvid", msg.getBvid())
                        .add("partId", msg.getPartId())
                        .add("contextLen", msg.getContext() == null ? 0 : msg.getContext().length()));
                sendHighFailurePush(room, part, msg, user, code);
            }
            long requeueDelayMs;
            if (code == 36703) {
                restoreMessagePending(msg);
                markDanmakuAccountPause(user.getUid(), RATE_LIMIT_PAUSE_MS);
                requeueDelayMs = Math.max(globalDanmakuWaitMs(), danmakuAccountCooldownWaitMs(user.getUid()));
                log.warn("[BLR] {}", LogKvs.event("LiveMsgSendSync.HighLevel.RateLimit.Pause")
                        .addIfNotBlank("uname", user.getUname())
                        .add("code", code)
                        .add("partId", msg.getPartId())
                        .add("businessDelayMs", businessDelayMs)
                        .add("rateLimitPauseMs", RATE_LIMIT_PAUSE_MS)
                        .add("waitMs", requeueDelayMs));
            } else {
                if (code != 0) {
                    extendGlobalDanmakuSlot(Math.max(ERROR_PAUSE_MS, businessDelayMs));
                }
                requeueDelayMs = globalDanmakuWaitMs();
            }
            requeueHighPart(partId, requeueDelayMs);
            log.info("[BLR] {}", LogKvs.event("DanmakuDispatch.High.Sent")
                    .add("partId", partId)
                    .add("code", code)
                    .add("businessDelayMs", businessDelayMs)
                    .add("rateLimitPauseMs", code == 36703 ? RATE_LIMIT_PAUSE_MS : 0L)
                    .add("requeueDelayMs", requeueDelayMs)
                    .add("globalWaitMs", globalDanmakuWaitMs())
                    .addStageCostMs("send", startNs));
        } catch (Exception e) {
            requeueHighPart(partId, ERROR_PAUSE_MS);
            log.error("[BLR] {}", LogKvs.event("DanmakuDispatch.High.Error")
                    .add("partId", partId)
                    .addIfNotBlank("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()), e);
        }
    }

    private void scheduleNormalDispatch(long delayMs) {
        if (!normalDispatchScheduled.compareAndSet(false, true)) {
            return;
        }
        scheduler.schedule(() -> {
            normalDispatchScheduled.set(false);
            runNormalDispatch();
        }, Instant.now().plusMillis(Math.max(0L, delayMs)));
    }

    private void runNormalDispatch() {
        int maxWorkers = systemConfigService.getDanmakuMaxNormalWorkers();
        while (activeNormalWorkers.get() < maxWorkers) {
            Long partId = poll(normalPartQueue);
            if (partId == null) {
                return;
            }
            activeNormalWorkers.incrementAndGet();
            try {
                normalExecutor.execute(() -> {
                    try {
                        processNormalPart(partId);
                    } finally {
                        activeNormalWorkers.decrementAndGet();
                        if (hasQueuedNormalParts()) {
                            scheduleNormalDispatch(0L);
                        }
                    }
                });
            } catch (RejectedExecutionException e) {
                activeNormalWorkers.decrementAndGet();
                requeueNormalPart(partId, ERROR_PAUSE_MS);
                log.warn("[BLR] {}", LogKvs.event("DanmakuDispatch.Normal.Rejected")
                        .add("partId", partId)
                        .addIfNotBlank("err", e.getMessage())
                        .add("ex", e.getClass().getSimpleName()), e);
                return;
            }
        }
    }

    private void processNormalPart(Long partId) {
        long startNs = System.nanoTime();
        try {
            if (LiveMsgSendSync.skipOrdinaryPartIds.contains(partId)) {
                pendingNormalPartIds.remove(partId);
                log.info("[BLR] {}", LogKvs.event("LiveMsgSendSync.Normal.SkipByManual")
                        .add("partId", partId));
                return;
            }
            LiveMsg msg = firstPendingMessage(partId, 0);
            if (msg == null) {
                pendingNormalPartIds.remove(partId);
                return;
            }
            Optional<RecordHistoryPart> partOptional = partRepository.findById(partId);
            if (partOptional.isEmpty()) {
                markMessageFailed(msg, -3);
                pendingNormalPartIds.remove(partId);
                return;
            }
            RecordHistoryPart part = partOptional.get();
            RecordHistory history = part.getHistoryId() == null ? null : historyRepository.findById(part.getHistoryId()).orElse(null);
            if (history == null || history.isForceArchived() || history.getCode() == -4) {
                if (history != null && history.getId() != null) {
                    msgQueueCleanupService.cleanupByHistoryId(history.getId(),
                            new HistoryMsgQueueCleanupService.CleanupOptions(true, true, true, false),
                            false,
                            "dispatch-normal-archived");
                } else {
                    markMessageFailed(msg, -3);
                }
                pendingNormalPartIds.remove(partId);
                log.info("[BLR] {}", LogKvs.event("DanmakuDispatch.Normal.SkipArchivedOrLocked")
                        .add("partId", partId)
                        .add("historyId", history == null ? null : history.getId())
                        .add("code", history == null ? null : history.getCode())
                        .add("forceArchived", history != null && history.isForceArchived()));
                return;
            }
            long globalWaitMs = globalDanmakuWaitMs();
            if (globalWaitMs > 0L) {
                requeueNormalPart(partId, globalWaitMs);
                log.debug("[BLR] {}", LogKvs.event("DanmakuDispatch.Normal.GlobalCooldown")
                        .add("waitMs", globalWaitMs)
                        .add("partId", partId));
                return;
            }
            UserReservation reservation = reserveNormalUser();
            BiliBiliUser user = reservation.user();
            if (user == null) {
                requeueNormalPart(partId, reservation.waitMs());
                return;
            }
            long businessDelayMs = systemConfigService.getDanmakuSendIntervalMs();
            long reserveWaitMs = reserveGlobalDanmakuSlot(businessDelayMs);
            if (reserveWaitMs > 0L) {
                busyUserIds.remove(user.getUid());
                requeueNormalPart(partId, reserveWaitMs);
                return;
            }
            int code;
            try {
                code = liveMsgService.sendMsg(user, msg);
                handleNormalCode(user, msg, code);
            } finally {
                busyUserIds.remove(user.getUid());
            }
            if (code == 36703) {
                restoreMessagePending(msg);
                markDanmakuAccountPause(user.getUid(), RATE_LIMIT_PAUSE_MS);
                long waitMs = danmakuAccountCooldownWaitMs(user.getUid());
                log.warn("[BLR] {}", LogKvs.event("LiveMsgSendSync.Normal.RateLimit.Pause")
                        .addIfNotBlank("uname", user.getUname())
                        .add("code", code)
                        .add("partId", msg.getPartId())
                        .add("businessDelayMs", businessDelayMs)
                        .add("rateLimitPauseMs", RATE_LIMIT_PAUSE_MS)
                        .add("waitMs", waitMs));
            } else {
                if (code != 0) {
                    extendGlobalDanmakuSlot(Math.max(ERROR_PAUSE_MS, businessDelayMs));
                }
            }
            requeueNormalPart(partId, globalDanmakuWaitMs());
            log.info("[BLR] {}", LogKvs.event("DanmakuDispatch.Normal.Sent")
                    .add("partId", partId)
                    .addIfNotBlank("uname", user.getUname())
                    .add("code", code)
                    .add("globalWaitMs", globalDanmakuWaitMs())
                    .add("accountWaitMs", danmakuAccountCooldownWaitMs(user.getUid()))
                    .addStageCostMs("send", startNs));
        } catch (Exception e) {
            requeueNormalPart(partId, ERROR_PAUSE_MS);
            log.error("[BLR] {}", LogKvs.event("DanmakuDispatch.Normal.Error")
                    .add("partId", partId)
                    .addIfNotBlank("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()), e);
        }
    }

    private void handleNormalCode(BiliBiliUser user, LiveMsg msg, int code) {
        if (code == 36714) {
            log.error("[BLR] {}", LogKvs.event("LiveMsgSendSync.Normal.Send.InvalidTime")
                    .addIfNotBlank("uname", user.getUname())
                    .add("code", code));
        } else if (code == 36704) {
            log.error("[BLR] {}", LogKvs.event("LiveMsgSendSync.Normal.Send.VideoNotApproved")
                    .addIfNotBlank("uname", user.getUname())
                    .add("code", code)
                    .addIfNotBlank("bvid", msg.getBvid()));
        } else if (code == -101 || code == -102 || code == -111 || code == -400 || code == -404 || code == -36700) {
            log.error("[BLR] {}", LogKvs.event("LiveMsgSendSync.Normal.Send.UserDisabled")
                    .addIfNotBlank("uname", user.getUname())
                    .add("uid", user.getUid())
                    .add("code", code));
            user.setEnable(false);
            userRepository.save(user);
        } else if (code != 0 && code != 36703) {
            log.error("[BLR] {}", LogKvs.event("LiveMsgSendSync.Normal.Send.Failed")
                    .addIfNotBlank("uname", user.getUname())
                    .add("code", code)
                    .addIfNotBlank("bvid", msg.getBvid()));
        }
    }

    private UserReservation reserveNormalUser() {
        List<BiliBiliUser> users = userRepository.findByLoginIsTrueAndEnableIsTrue();
        if (users.isEmpty()) {
            return new UserReservation(null, ERROR_PAUSE_MS);
        }
        int start = Math.floorMod(normalUserCursor.getAndIncrement(), users.size());
        long waitMs = Long.MAX_VALUE;
        boolean hasUsableUser = false;
        for (int i = 0; i < users.size(); i++) {
            BiliBiliUser user = users.get((start + i) % users.size());
            if (user == null || user.getUid() == null || !user.isLogin() || !user.isEnable()) {
                continue;
            }
            hasUsableUser = true;
            long cooldownWaitMs = danmakuAccountCooldownWaitMs(user.getUid());
            if (cooldownWaitMs > 0L) {
                waitMs = Math.min(waitMs, cooldownWaitMs);
                continue;
            }
            if (busyUserIds.add(user.getUid())) {
                return new UserReservation(user, 0L);
            }
            waitMs = Math.min(waitMs, BUSY_RETRY_MS);
        }
        if (!hasUsableUser) {
            return new UserReservation(null, ERROR_PAUSE_MS);
        }
        return new UserReservation(null, waitMs == Long.MAX_VALUE ? ERROR_PAUSE_MS : Math.max(1L, waitMs));
    }

    private LiveMsg firstPendingMessage(Long partId, int pool) {
        Page<LiveMsg> page = msgRepository.findByPartIdAndPoolAndCodeOrderBySendTimeAsc(partId, pool, -1, PageRequest.of(0, 1));
        if (page == null || page.isEmpty()) {
            return null;
        }
        return page.getContent().get(0);
    }

    private void markMessageDone(LiveMsg msg) {
        msg.setCode(0);
        msgRepository.save(msg);
    }

    private void restoreMessagePending(LiveMsg msg) {
        if (msg == null) {
            return;
        }
        msg.setCode(-1);
        msgRepository.save(msg);
    }

    private void markMessageFailed(LiveMsg msg, int code) {
        if (msg == null) {
            return;
        }
        msg.setCode(code);
        msgRepository.save(msg);
    }

    public void waitForCommentSendPermit(BiliBiliUser user, String bvid, int index) {
        Long uid = user == null ? null : user.getUid();
        if (uid == null) {
            throw new IllegalStateException("comment upload user uid is missing");
        }
        while (true) {
            long accountWaitMs = commentAccountCooldownWaitMs(uid);
            if (accountWaitMs > 0L) {
                log.debug("[BLR] {}", LogKvs.event("LiveMsgSendSync.Reply.AccountCooldown")
                        .add("uid", uid)
                        .addIfNotBlank("uname", user.getUname())
                        .addIfNotBlank("bvid", bvid)
                        .add("index", index)
                        .add("waitMs", accountWaitMs));
                sleepPermit(accountWaitMs);
                continue;
            }
            long globalWaitMs = globalCommentWaitMs();
            if (globalWaitMs > 0L) {
                log.debug("[BLR] {}", LogKvs.event("LiveMsgSendSync.Reply.GlobalCooldown")
                        .add("uid", uid)
                        .addIfNotBlank("uname", user.getUname())
                        .addIfNotBlank("bvid", bvid)
                        .add("index", index)
                        .add("waitMs", globalWaitMs));
                sleepPermit(globalWaitMs);
                continue;
            }
            if (!busyUserIds.add(uid)) {
                sleepPermit(BUSY_RETRY_MS);
                continue;
            }
            long reserveWaitMs = reserveGlobalCommentSlot(systemConfigService.getCommentSendIntervalMs());
            if (reserveWaitMs <= 0L) {
                return;
            }
            busyUserIds.remove(uid);
            sleepPermit(reserveWaitMs);
        }
    }

    public void releaseCommentSendPermit(BiliBiliUser user) {
        if (user != null && user.getUid() != null) {
            busyUserIds.remove(user.getUid());
        }
    }

    public void markCommentFailure(BiliBiliUser user, Integer code) {
        Long uid = user == null ? null : user.getUid();
        if (uid == null) {
            return;
        }
        long delayMs = isRateLimitCode(code)
                ? RATE_LIMIT_PAUSE_MS
                : Math.max(ERROR_PAUSE_MS, systemConfigService.getCommentSendIntervalMs());
        markCooldown(nextCommentAccountPauseAtByUid, uid, delayMs);
        log.warn("[BLR] {}", LogKvs.event("LiveMsgSendSync.Reply.AccountPause")
                .add("uid", uid)
                .addIfNotBlank("uname", user.getUname())
                .add("code", code)
                .add("pauseMs", delayMs));
    }

    private long danmakuAccountCooldownWaitMs(Long uid) {
        return cooldownWaitMs(nextDanmakuAccountPauseAtByUid, uid);
    }

    private long commentAccountCooldownWaitMs(Long uid) {
        return cooldownWaitMs(nextCommentAccountPauseAtByUid, uid);
    }

    private long globalDanmakuWaitMs() {
        return globalWaitMs(nextGlobalDanmakuSendAtMs);
    }

    private long globalCommentWaitMs() {
        return globalWaitMs(nextGlobalCommentSendAtMs);
    }

    private long globalWaitMs(long nextAt) {
        long waitMs = nextAt - System.currentTimeMillis();
        return Math.max(0L, waitMs);
    }

    private long reserveGlobalDanmakuSlot(long delayMs) {
        synchronized (danmakuPermitLock) {
            long waitMs = globalDanmakuWaitMs();
            if (waitMs > 0L) {
                return waitMs;
            }
            nextGlobalDanmakuSendAtMs = System.currentTimeMillis() + Math.max(0L, delayMs);
            return 0L;
        }
    }

    private long reserveGlobalCommentSlot(long delayMs) {
        synchronized (commentPermitLock) {
            long waitMs = globalCommentWaitMs();
            if (waitMs > 0L) {
                return waitMs;
            }
            nextGlobalCommentSendAtMs = System.currentTimeMillis() + Math.max(0L, delayMs);
            return 0L;
        }
    }

    private void extendGlobalDanmakuSlot(long delayMs) {
        synchronized (danmakuPermitLock) {
            nextGlobalDanmakuSendAtMs = Math.max(nextGlobalDanmakuSendAtMs, System.currentTimeMillis() + Math.max(0L, delayMs));
        }
    }

    private long cooldownWaitMs(Map<Long, Long> cooldowns, Long uid) {
        if (uid == null) {
            return ERROR_PAUSE_MS;
        }
        Long nextAt = cooldowns.get(uid);
        if (nextAt == null) {
            return 0L;
        }
        long waitMs = nextAt - System.currentTimeMillis();
        if (waitMs <= 0L) {
            cooldowns.remove(uid, nextAt);
            return 0L;
        }
        return waitMs;
    }

    private void markDanmakuAccountPause(Long uid, long delayMs) {
        markCooldown(nextDanmakuAccountPauseAtByUid, uid, delayMs);
    }

    private void markCooldown(Map<Long, Long> cooldowns, Long uid, long delayMs) {
        if (uid == null) {
            return;
        }
        long nextAt = System.currentTimeMillis() + Math.max(0L, delayMs);
        cooldowns.merge(uid, nextAt, Math::max);
    }

    private boolean isRateLimitCode(Integer code) {
        return code != null && (code == 36703 || code == -352 || code == -412 || code == 12002);
    }

    private boolean isVisibilityRateLimitCode(Integer code) {
        return code != null && (code == 21540 || code == -352 || code == -412 || code == 12002);
    }

    private void sleepPermit(long waitMs) {
        try {
            Thread.sleep(Math.max(1L, waitMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("send permit wait interrupted", e);
        }
    }

    private void requeueHighPart(Long partId, long delayMs) {
        if (!hasPendingMessage(partId, 1)) {
            pendingHighPartIds.remove(partId);
        } else {
            synchronized (highPartQueue) {
                highPartQueue.offer(partId);
            }
            scheduleHighDispatch(delayMs);
            return;
        }
        scheduleHighDispatch(0L);
    }

    private void requeueNormalPart(Long partId, long delayMs) {
        if (!hasPendingMessage(partId, 0)) {
            pendingNormalPartIds.remove(partId);
        } else {
            synchronized (normalPartQueue) {
                normalPartQueue.offer(partId);
            }
            scheduleNormalDispatch(delayMs);
            return;
        }
        scheduleNormalDispatch(0L);
    }

    private boolean hasPendingMessage(Long partId, int pool) {
        return msgRepository.findByPartIdAndPoolAndCodeOrderBySendTimeAsc(partId, pool, -1, PageRequest.of(0, 1)).hasContent();
    }

    private Long poll(ArrayDeque<Long> queue) {
        synchronized (queue) {
            return queue.poll();
        }
    }

    private boolean hasQueuedNormalParts() {
        synchronized (normalPartQueue) {
            return !normalPartQueue.isEmpty();
        }
    }

    private void sendHighFailurePush(RecordRoom room, RecordHistoryPart part, LiveMsg msg, BiliBiliUser user, int code) {
        try {
        } catch (Exception ignored) {
        }
    }

    private void handleHighVisibilitySwitchPublicFailure(RecordRoom room,
                                                         RecordHistoryPart part,
                                                         LiveMsg msg,
                                                         BiliBiliUser user,
                                                         VisibilitySwitchException error,
                                                         long businessDelayMs) {
        int code = error.code();
        if (isVisibilityRateLimitCode(code)) {
            restoreMessagePending(msg);
            markDanmakuAccountPause(user.getUid(), RATE_LIMIT_PAUSE_MS);
            long requeueDelayMs = Math.max(globalDanmakuWaitMs(), danmakuAccountCooldownWaitMs(user.getUid()));
            requeueHighPart(msg.getPartId(), requeueDelayMs);
            log.warn("[BLR] {}", LogKvs.event("LiveMsgSendSync.Visibility.High.SwitchPublic.RateLimit")
                    .addIfNotBlank("uname", user.getUname())
                    .add("code", code)
                    .addIfNotBlank("bvid", msg.getBvid())
                    .add("partId", msg.getPartId())
                    .add("businessDelayMs", businessDelayMs)
                    .add("rateLimitPauseMs", RATE_LIMIT_PAUSE_MS)
                    .add("waitMs", requeueDelayMs));
            return;
        }

        markMessageFailed(msg, code);
        if (part != null && part.getHistoryId() != null) {
            msgQueueCleanupService.cleanupByHistoryId(part.getHistoryId(),
                    new HistoryMsgQueueCleanupService.CleanupOptions(false, true, true, false),
                    false,
                    "dispatch-high-visibility-failed");
            pendingHighPartIds.remove(msg.getPartId());
        } else {
            requeueHighPart(msg.getPartId(), globalDanmakuWaitMs());
        }
        log.warn("[BLR] {}", LogKvs.event("LiveMsgSendSync.Visibility.High.SwitchPublic.Skip")
                .addIfNotBlank("uname", user.getUname())
                .add("code", code)
                .addIfNotBlank("message", error.apiMessage())
                .addIfNotBlank("bvid", msg.getBvid())
                .add("partId", msg.getPartId())
                .add("contextLen", msg.getContext() == null ? 0 : msg.getContext().length()));
        sendHighFailurePush(room, part, msg, user, code);
    }

    private void switchHighVisibility(RecordHistory history, BiliBiliUser user, int visibility, String eventName) {
        if (history == null || history.getAvId() == null || history.getAvId().isBlank()) {
            throw new IllegalStateException("history avId is missing");
        }
        String editRes = BiliApi.updateVideoVisibility(user, Long.parseLong(history.getAvId()), visibility);
        int editCode = -1;
        String editMsg = null;
        try {
            com.alibaba.fastjson.JSONObject jsonObject = com.alibaba.fastjson.JSON.parseObject(editRes);
            Integer c = jsonObject.getInteger("code");
            editCode = c == null ? -1 : c;
            editMsg = jsonObject.getString("message");
            if (editCode != 0) {
                throw new VisibilitySwitchException(editCode, editMsg, editRes);
            }
        } catch (VisibilitySwitchException e) {
            log.warn("[BLR] {}", LogKvs.event(eventName)
                    .addIfNotBlank("bvid", history.getBvId())
                    .addIfNotBlank("avId", history.getAvId())
                    .add("visibility", visibility)
                    .add("code", editCode)
                    .addIfNotBlank("message", editMsg)
                    .add("respLen", editRes == null ? 0 : editRes.length()));
            throw e;
        } catch (RuntimeException e) {
            log.error("[BLR] {}", LogKvs.event(eventName)
                    .addIfNotBlank("bvid", history.getBvId())
                    .addIfNotBlank("avId", history.getAvId())
                    .add("visibility", visibility)
                    .add("code", editCode)
                    .addIfNotBlank("message", editMsg)
                    .add("respLen", editRes == null ? 0 : editRes.length()), e);
            throw e;
        }
        log.info("[BLR] {}", LogKvs.event(eventName)
                .addIfNotBlank("bvid", history.getBvId())
                .addIfNotBlank("avId", history.getAvId())
                .add("visibility", visibility)
                .add("code", editCode)
                .addIfNotBlank("message", editMsg)
                .add("respLen", editRes == null ? 0 : editRes.length()));
    }

    private void sleepQuietly(long waitMs, String phase) {
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[BLR] {}", LogKvs.event("DanmakuDispatch.High.SleepInterrupted")
                    .add("phase", phase)
                    .add("waitMs", waitMs), e);
        }
    }

    private static class VisibilitySwitchException extends RuntimeException {
        private final int code;
        private final String apiMessage;
        private final String response;

        private VisibilitySwitchException(int code, String apiMessage, String response) {
            super("visibility switch failed: " + response);
            this.code = code;
            this.apiMessage = apiMessage;
            this.response = response;
        }

        private int code() {
            return code;
        }

        private String apiMessage() {
            return apiMessage;
        }

        @SuppressWarnings("unused")
        private String response() {
            return response;
        }
    }

    private record UserReservation(BiliBiliUser user, long waitMs) {
    }
}
