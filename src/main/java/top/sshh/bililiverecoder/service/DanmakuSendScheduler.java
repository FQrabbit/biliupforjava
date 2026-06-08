package top.sshh.bililiverecoder.service;

import com.zjiecode.wxpusher.client.bean.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.entity.BiliBiliUser;
import top.sshh.bililiverecoder.entity.LiveMsg;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.job.LiveMsgSendSync;
import top.sshh.bililiverecoder.repo.BiliUserRepository;
import top.sshh.bililiverecoder.repo.LiveMsgRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.service.impl.LiveMsgService;
import top.sshh.bililiverecoder.util.LogKvs;
import top.sshh.bililiverecoder.util.PushNotifyClient;

import java.time.Instant;
import java.time.LocalDateTime;
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
    private final RecordHistoryPartRepository partRepository;
    private final RecordRoomRepository roomRepository;
    private final BiliUserRepository userRepository;
    private final LiveMsgService liveMsgService;
    private final SystemConfigService systemConfigService;

    @Value("${record.wx-push-token}")
    private String wxToken;

    private final ArrayDeque<Long> highPartQueue = new ArrayDeque<>();
    private final ArrayDeque<Long> normalPartQueue = new ArrayDeque<>();
    private final Set<Long> pendingHighPartIds = ConcurrentHashMap.newKeySet();
    private final Set<Long> pendingNormalPartIds = ConcurrentHashMap.newKeySet();
    private final Set<Long> pendingReplyHistoryIds = ConcurrentHashMap.newKeySet();
    private final Set<Long> busyUserIds = ConcurrentHashMap.newKeySet();
    private final Map<Long, Long> nextNormalSendAtByUid = new ConcurrentHashMap<>();
    private final Map<Long, Long> nextHighSendAtByUid = new ConcurrentHashMap<>();
    private final Map<Long, Long> nextRateLimitPauseAtByUid = new ConcurrentHashMap<>();

    private final AtomicBoolean highDispatchScheduled = new AtomicBoolean(false);
    private final AtomicBoolean normalDispatchScheduled = new AtomicBoolean(false);
    private final AtomicInteger activeNormalWorkers = new AtomicInteger(0);
    private final AtomicInteger normalUserCursor = new AtomicInteger(0);

    public DanmakuSendScheduler(@Qualifier("danmakuExecutor") TaskExecutor replyExecutor,
                                @Qualifier("danmakuHighExecutor") TaskExecutor highExecutor,
                                @Qualifier("danmakuNormalExecutor") TaskExecutor normalExecutor,
                                @Qualifier("danmakuTaskScheduler") TaskScheduler scheduler,
                                LiveMsgRepository msgRepository,
                                RecordHistoryPartRepository partRepository,
                                RecordRoomRepository roomRepository,
                                BiliUserRepository userRepository,
                                LiveMsgService liveMsgService,
                                SystemConfigService systemConfigService) {
        this.replyExecutor = replyExecutor;
        this.highExecutor = highExecutor;
        this.normalExecutor = normalExecutor;
        this.scheduler = scheduler;
        this.msgRepository = msgRepository;
        this.partRepository = partRepository;
        this.roomRepository = roomRepository;
        this.userRepository = userRepository;
        this.liveMsgService = liveMsgService;
        this.systemConfigService = systemConfigService;
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
            RecordRoom room = roomRepository.findByRoomId(part.getRoomId());
            if (room == null || !Boolean.TRUE.equals(room.getSendSc()) || room.getUploadUserId() == null) {
                markMessageDone(msg);
                requeueHighPart(partId, 0L);
                return;
            }
            Optional<BiliBiliUser> userOptional = userRepository.findById(room.getUploadUserId());
            if (userOptional.isEmpty() || userOptional.get().getUid() == null || !userOptional.get().isLogin() || !userOptional.get().isEnable()) {
                markMessageDone(msg);
                requeueHighPart(partId, 0L);
                return;
            }
            BiliBiliUser user = userOptional.get();
            long cooldownWaitMs = highCooldownWaitMs(user.getUid());
            if (cooldownWaitMs > 0L) {
                requeueHighPart(partId, cooldownWaitMs);
                log.debug("[BLR] {}", LogKvs.event("DanmakuDispatch.High.UserCooldown")
                        .add("uid", user.getUid())
                        .addIfNotBlank("uname", user.getUname())
                        .add("waitMs", cooldownWaitMs)
                        .add("partId", partId));
                return;
            }
            if (!busyUserIds.add(user.getUid())) {
                requeueHighPart(partId, BUSY_RETRY_MS);
                return;
            }
            int code;
            try {
                code = liveMsgService.sendMsg(user, msg);
            } finally {
                busyUserIds.remove(user.getUid());
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
            long businessDelayMs = code == 0 || code == 36703
                    ? systemConfigService.getHighLevelDanmakuIntervalMs()
                    : Math.max(ERROR_PAUSE_MS, systemConfigService.getHighLevelDanmakuIntervalMs());
            long requeueDelayMs;
            if (code == 36703) {
                restoreMessagePending(msg);
                markHighCooldown(user.getUid(), businessDelayMs);
                markRateLimitPause(user.getUid(), RATE_LIMIT_PAUSE_MS);
                requeueDelayMs = highCooldownWaitMs(user.getUid());
                log.warn("[BLR] {}", LogKvs.event("LiveMsgSendSync.HighLevel.RateLimit.Pause")
                        .addIfNotBlank("uname", user.getUname())
                        .add("code", code)
                        .add("partId", msg.getPartId())
                        .add("businessDelayMs", businessDelayMs)
                        .add("rateLimitPauseMs", RATE_LIMIT_PAUSE_MS)
                        .add("waitMs", requeueDelayMs));
            } else {
                markHighCooldown(user.getUid(), businessDelayMs);
                requeueDelayMs = highCooldownWaitMs(user.getUid());
            }
            requeueHighPart(partId, requeueDelayMs);
            log.info("[BLR] {}", LogKvs.event("DanmakuDispatch.High.Sent")
                    .add("partId", partId)
                    .add("code", code)
                    .add("businessDelayMs", businessDelayMs)
                    .add("rateLimitPauseMs", code == 36703 ? RATE_LIMIT_PAUSE_MS : 0L)
                    .add("requeueDelayMs", requeueDelayMs)
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
            UserReservation reservation = reserveNormalUser();
            BiliBiliUser user = reservation.user();
            if (user == null) {
                requeueNormalPart(partId, reservation.waitMs());
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
                long businessDelayMs = systemConfigService.getNormalDanmakuIntervalMs();
                restoreMessagePending(msg);
                markNormalCooldown(user.getUid(), businessDelayMs);
                markRateLimitPause(user.getUid(), RATE_LIMIT_PAUSE_MS);
                long waitMs = normalCooldownWaitMs(user.getUid());
                log.warn("[BLR] {}", LogKvs.event("LiveMsgSendSync.Normal.RateLimit.Pause")
                        .addIfNotBlank("uname", user.getUname())
                        .add("code", code)
                        .add("partId", msg.getPartId())
                        .add("businessDelayMs", businessDelayMs)
                        .add("rateLimitPauseMs", RATE_LIMIT_PAUSE_MS)
                        .add("waitMs", waitMs));
            } else {
                long businessDelayMs = code == 0
                        ? systemConfigService.getNormalDanmakuIntervalMs()
                        : Math.max(ERROR_PAUSE_MS, systemConfigService.getNormalDanmakuIntervalMs());
                markNormalCooldown(user.getUid(), businessDelayMs);
            }
            requeueNormalPart(partId, 0L);
            log.info("[BLR] {}", LogKvs.event("DanmakuDispatch.Normal.Sent")
                    .add("partId", partId)
                    .addIfNotBlank("uname", user.getUname())
                    .add("code", code)
                    .add("normalWaitMs", normalCooldownWaitMs(user.getUid()))
                    .add("rateLimitPauseMs", rateLimitPauseWaitMs(user.getUid()))
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
            long cooldownWaitMs = normalCooldownWaitMs(user.getUid());
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

    private long normalCooldownWaitMs(Long uid) {
        return Math.max(cooldownWaitMs(nextNormalSendAtByUid, uid), rateLimitPauseWaitMs(uid));
    }

    private long highCooldownWaitMs(Long uid) {
        return Math.max(cooldownWaitMs(nextHighSendAtByUid, uid), rateLimitPauseWaitMs(uid));
    }

    private long rateLimitPauseWaitMs(Long uid) {
        return cooldownWaitMs(nextRateLimitPauseAtByUid, uid);
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

    private void markNormalCooldown(Long uid, long delayMs) {
        markCooldown(nextNormalSendAtByUid, uid, delayMs);
    }

    private void markHighCooldown(Long uid, long delayMs) {
        markCooldown(nextHighSendAtByUid, uid, delayMs);
    }

    private void markRateLimitPause(Long uid, long delayMs) {
        markCooldown(nextRateLimitPauseAtByUid, uid, delayMs);
    }

    private void markCooldown(Map<Long, Long> cooldowns, Long uid, long delayMs) {
        if (uid == null) {
            return;
        }
        long nextAt = System.currentTimeMillis() + Math.max(0L, delayMs);
        cooldowns.merge(uid, nextAt, Math::max);
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
            if (PushNotifyClient.canSend(room, room.getWxuid(), room.getPushMsgTags(), "高级弹幕")) {
                Message message = new Message();
                message.setAppToken(wxToken);
                message.setContentType(Message.CONTENT_TYPE_TEXT);
                message.setContent("High level danmaku send failed\n"
                        + "room=" + room.getUname() + "\n"
                        + "part=" + part.getTitle() + "\n"
                        + "bvid=" + msg.getBvid() + "\n"
                        + "time=" + LocalDateTime.now() + "\n"
                        + "content=" + msg.getContext() + "\n"
                        + "user=" + user.getUname() + "\n"
                        + "code=" + code);
                message.setUid(room.getWxuid());
                PushNotifyClient.sendParallel(room, message);
            }
        } catch (Exception ignored) {
        }
    }

    private record UserReservation(BiliBiliUser user, long waitMs) {
    }
}
