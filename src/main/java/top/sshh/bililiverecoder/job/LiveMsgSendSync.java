package top.sshh.bililiverecoder.job;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.entity.data.*;
import top.sshh.bililiverecoder.repo.*;
import top.sshh.bililiverecoder.service.DanmakuSendScheduler;
import top.sshh.bililiverecoder.service.GiftReplyCandidateService;
import top.sshh.bililiverecoder.service.RoomLiveEventParseService;
import top.sshh.bililiverecoder.service.HistoryMsgQueueCleanupService;
import top.sshh.bililiverecoder.service.SystemConfigService;
import top.sshh.bililiverecoder.service.impl.LiveMsgService;
import top.sshh.bililiverecoder.util.BiliApi;
import top.sshh.bililiverecoder.util.LogKvs;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Component
public class LiveMsgSendSync {

    @Autowired
    private BiliUserRepository userRepository;

    @Autowired
    private LiveMsgRepository msgRepository;

    @Autowired
    private RecordHistoryRepository historyRepository;

    @Autowired
    private RecordHistoryPartRepository partRepository;

    @Autowired
    private RecordRoomRepository roomRepository;

    @Autowired
    private LiveMsgService liveMsgService;

    @Autowired
    private RoomLiveEventParseService roomLiveEventParseService;

    @Autowired
    private RoomLiveEventRepository roomLiveEventRepository;

    @Autowired
    private GiftReplyCandidateService giftReplyCandidateService;

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private DanmakuSendScheduler danmakuSendScheduler;

    @Autowired
    private HistoryMsgQueueCleanupService msgQueueCleanupService;

    private static final Lock lock = new ReentrantLock();
    private volatile long lastReconcileAtMs = 0L;

    public static Set<Long> skipOrdinaryPartIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    public static Set<Long> skipAdvancedPartIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Scheduled(fixedDelay = 60000, initialDelay = 5000)
    public void sndMsgProcess() {
        long roundStartNs = System.nanoTime();
        int historyCount = 0;
        int candidatePartCount = 0;
        int pendingNormalCount = 0;
        int pendingHighCount = 0;
        log.debug("[BLR] {}", LogKvs.event("LiveMsgSendSync.Start"));
        try {
            if (isQueuedDispatchMode()) {
                ReconcileStats stats = reconcileQueuedDispatch(false);
                historyCount = stats.historyCount();
                candidatePartCount = stats.candidatePartCount();
                pendingNormalCount = stats.pendingNormalCount();
                pendingHighCount = stats.pendingHighCount();
                return;
            }
            skipOrdinaryPartIds.clear();
            skipAdvancedPartIds.clear();
            long startTime = System.currentTimeMillis();
            
            // 获取需要发送评论的稿件
            List<RecordHistory> historyList = new ArrayList<>(historyRepository.findByPublishIsTrueAndSendReplyIsFalseAndCodeIn(Arrays.asList(0, -50)));
            
            // 获取有待发送弹幕的分P，找到其对应的稿件，如果没在 historyList 中则加进去
            List<Long> pendingPartIds = msgRepository.findDistinctPartIdByCode(-1);
            Set<Long> pendingPartIdSet = pendingPartIds == null ? Collections.emptySet() : new HashSet<>(pendingPartIds);
            if (pendingPartIds != null && !pendingPartIds.isEmpty()) {
                Set<Long> pendingHistoryIds = new HashSet<>();
                // 分批查询，防止 IN 语句过长报错
                int batchSize = 500;
                for (int i = 0; i < pendingPartIds.size(); i += batchSize) {
                    List<Long> subList = pendingPartIds.subList(i, Math.min(i + batchSize, pendingPartIds.size()));
                    List<RecordHistoryPart> pendingParts = partRepository.findByIdIn(subList);
                    for (RecordHistoryPart p : pendingParts) {
                        if (p.getHistoryId() != null) {
                            pendingHistoryIds.add(p.getHistoryId());
                        }
                    }
                }
                
                for (Long hid : pendingHistoryIds) {
                    boolean exists = false;
                    for (RecordHistory h : historyList) {
                        if (h.getId().equals(hid)) {
                            exists = true;
                            break;
                        }
                    }
                    if (!exists) {
                        Optional<RecordHistory> opt = historyRepository.findById(hid);
                        if (opt.isPresent()) {
                            RecordHistory h = opt.get();
                            if (h.isPublish() && (h.getCode() == 0 || h.getCode() == -50)) {
                                historyList.add(h);
                            }
                        }
                    }
                }
            }

            historyCount = historyList.size();
            if (CollectionUtils.isEmpty(historyList)) {
                return;
            }
            List<BiliBiliUser> allUser = userRepository.findByLoginIsTrueAndEnableIsTrue();
        DateFormat format = new SimpleDateFormat("HH:mm:ss");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        List<RecordHistoryPart> partList = new ArrayList<>();
        for (RecordHistory history : historyList) {
            List<RecordHistoryPart> allParts = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());
            List<RecordHistoryPart> parts = new ArrayList<>();
            for (RecordHistoryPart p : allParts) {
                // 跳过已标记为异常的分P
                if (p.getUploadRetryCount() >= 9999) {
                    continue;
                }

                if (p.getCid() == null || p.getCid() == 0) {
                    // 标记为分P异常
                    p.setUpload(false);
                    p.setUploadRetryCount(9999);
                    p.setDeleteFailReason("分P缺失CID");
                    p.setDeleteFailType("CID_MISSING");
                    partRepository.save(p);
                    
                    log.warn("[BLR] {}", LogKvs.event("LiveMsgSendSync.Part.SkipMissingCid")
                            .add("historyId", history.getId())
                            .add("partId", p.getId())
                            .addIfNotBlank("title", p.getTitle())
                            .addIfNotBlank("bvid", history.getBvId())
                            .add("msg", "分P缺失CID，标记为异常并跳过后续处理"));
                } else {
                    parts.add(p);
                }
            }
            // 房间弹幕发送开关（普通/SC）
            RecordRoom room = roomRepository.findByRoomId(history.getRoomId());
            boolean sendDmEnabled = room != null && Boolean.TRUE.equals(room.getSendDm());
            boolean sendScEnabled = room != null && Boolean.TRUE.equals(room.getSendSc());
            boolean sendGiftReplyEnabled = room != null && Boolean.TRUE.equals(room.getSendGiftReply());

            //如果没有发送评论
            if (!history.isSendReply()) {
                BiliBiliUser user = null;
                if (room != null) {
                    Long uploadUserId = room.getUploadUserId();
                    Optional<BiliBiliUser> userOptional = userRepository.findById(uploadUserId);
                    if (userOptional.isPresent()) {
                        user = userOptional.get();
                        if (!(user.getUid() != null && user.isLogin())) {
                            log.warn("[BLR] {}", LogKvs.event("LiveMsgSendSync.UploadUser.InvalidState")
                                    .add("uid", user.getUid())
                                    .addIfNotBlank("uname", user.getUname())
                                    .add("login", user.isLogin())
                                    .add("enable", user.isEnable())
                                    .add("historyId", history.getId())
                                    .addIfNotBlank("bvid", history.getBvId()));
                            user = null; // 后续按“无可用上传账号”处理
                        }
                    }
                }

                // 普通弹幕/高级弹幕/SC/礼物评论全关闭：直接标记“评论已处理”，避免状态卡住
                if (!sendDmEnabled && !sendScEnabled && !sendGiftReplyEnabled) {
                    history.setSendReply(true);
                    historyRepository.save(history);
                    log.info("[BLR] {}", LogKvs.event("LiveMsgSendSync.AllDmDisabled.Archive")
                            .add("roomId", history.getRoomId())
                            .add("historyId", history.getId())
                            .addIfNotBlank("bvid", history.getBvId())
                            .addIfNotBlank("title", history.getTitle()));
                    continue;
                }

                // 未开启 SC/上舰发送且未开启礼物评论时，直接认为评论阶段已完成
                if (!sendScEnabled && !sendGiftReplyEnabled) {
                    history.setSendReply(true);
                    historyRepository.save(history);
                    log.info("[BLR] {}", LogKvs.event("LiveMsgSendSync.Reply.SkipByRoomConfig")
                            .add("roomId", history.getRoomId())
                            .add("historyId", history.getId())
                            .addIfNotBlank("bvid", history.getBvId())
                            .addIfNotBlank("title", history.getTitle()));
                } else {
                boolean isPrivateFlow = false;
                try {
                    if (user != null) {
                        BiliVideoInfoResponse videoInfo = BiliApi.getVideoInfo(user, history.getBvId());
                        if (videoInfo != null && videoInfo.getData() != null && videoInfo.getData().getState() == -50) {
                            isPrivateFlow = true;
                            log.info("[BLR] {}", LogKvs.event("LiveMsgSendSync.PrivateFlow.Detected")
                                    .addIfNotBlank("bvid", history.getBvId())
                                    .addIfNotBlank("avId", history.getAvId())
                                    .addIfNotBlank("title", history.getTitle())
                                    .add("state", -50));

                            // 使用轻量级接口切换为公开状态
                            String editRes = BiliApi.updateVideoVisibility(user, Long.parseLong(history.getAvId()), 0);
                            int editCode = -1;
                            String editMsg = null;
                            try {
                                com.alibaba.fastjson.JSONObject jsonObject = com.alibaba.fastjson.JSON.parseObject(editRes);
                                Integer c = jsonObject.getInteger("code");
                                editCode = c == null ? -1 : c;
                                editMsg = jsonObject.getString("message");
                            } catch (Exception ignored) {
                                // ignore
                            }
                            log.info("[BLR] {}", LogKvs.event("LiveMsgSendSync.Visibility.SwitchPublic.Response")
                                    .addIfNotBlank("bvid", history.getBvId())
                                    .add("code", editCode)
                                    .addIfNotBlank("message", editMsg)
                                    .add("respLen", editRes == null ? 0 : editRes.length()));
                            
                            // 检查响应结果
                            try {
                                com.alibaba.fastjson.JSONObject jsonObject = com.alibaba.fastjson.JSON.parseObject(editRes);
                                if (jsonObject.getInteger("code") != 0) {
                                    log.error("[BLR] {}", LogKvs.event("LiveMsgSendSync.Visibility.SwitchPublic.Failed")
                                            .addIfNotBlank("bvid", history.getBvId())
                                            .addIfNotBlank("avId", history.getAvId())
                                            .addIfNotBlank("title", history.getTitle())
                                            .add("code", jsonObject.getInteger("code"))
                                            .addIfNotBlank("message", jsonObject.getString("message")));
                                    // 抛出异常以中断当前视频的处理流程，避免继续发送评论或弹幕
                                    throw new RuntimeException("切换公开状态失败: " + editRes);
                                }
                            } catch (Exception e) {
                                if (e instanceof RuntimeException) {
                                    throw e;
                                }
                                log.error("[BLR] {}", LogKvs.event("LiveMsgSendSync.Visibility.SwitchPublic.ResponseParseFailed")
                                        .addIfNotBlank("bvid", history.getBvId())
                                        .addIfNotBlank("avId", history.getAvId())
                                        .addIfNotBlank("title", history.getTitle())
                                        .add("respLen", editRes == null ? 0 : editRes.length())
                                        .addIfNotBlank("err", e.getMessage())
                                        .add("ex", e.getClass().getSimpleName()), e);
                                throw new RuntimeException("解析响应失败", e);
                            }

                            // 等待B站状态同步
                            Thread.sleep(15000);
                        }
                    }
                } catch (Exception e) {
                    log.error("[BLR] {}", LogKvs.event("LiveMsgSendSync.PrivateFlow.SkipByError")
                            .addIfNotBlank("title", history.getTitle())
                            .addIfNotBlank("bvid", history.getBvId())
                            .addIfNotBlank("avId", history.getAvId())
                            .addIfNotBlank("err", e.getMessage())
                            .add("ex", e.getClass().getSimpleName()), e);
                    msgQueueCleanupService.cleanupByHistoryId(history.getId(),
                            new HistoryMsgQueueCleanupService.CleanupOptions(false, true, true, false),
                            false,
                            "reply-private-visibility-failed");
                    // 如果切换公开失败，直接跳过当前视频的后续所有操作（评论、弹幕、切回私有）
                    continue;
                }

                ReplyPageResolver pageResolver = buildReplyPageResolver(history, user, parts);
                List<String> replyLines = new ArrayList<>();
                boolean hasScReply = false;
                boolean hasGuardReply = false;
                boolean hasOtherHighLevelReply = false;
                if (sendScEnabled) {
                    for (RecordHistoryPart part : parts) {
                        List<LiveMsg> msgList = msgRepository.findByPartIdAndPoolAndCidNotNullOrderBySendTimeAsc(part.getId(), 1);
                        for (LiveMsg liveMsg : msgList) {
                            String contextText = liveMsg.getContext();
                            if (contextText != null && contextText.startsWith("SC [")) {
                                hasScReply = true;
                            } else if (contextText != null && contextText.startsWith("⚓")) {
                                hasGuardReply = true;
                            } else {
                                hasOtherHighLevelReply = true;
                            }
                            replyLines.add(pageResolver.resolve(part) + "#" + format.format(new Date(liveMsg.getSendTime()))
                                    + "  " + contextText + "\n");
                        }
                    }
                }
                int giftReplyCount = 0;
                if (sendGiftReplyEnabled) {
                    giftReplyCount = appendGiftReplyLines(replyLines, history, parts, room, format, pageResolver);
                }
                List<BiliReply> replies = buildVideoReplies(history,
                        buildReplyHeader(hasScReply, hasGuardReply, hasOtherHighLevelReply, giftReplyCount),
                        replyLines);
                // 需要发送评论但没有可用的上传账号：跳过本次评论发送，避免空指针
                if (user == null && !replies.isEmpty()) {
                    log.warn("[BLR] {}", LogKvs.event("LiveMsgSendSync.Reply.SkipNoUploadUser")
                            .add("roomId", history.getRoomId())
                            .add("historyId", history.getId())
                            .addIfNotBlank("bvid", history.getBvId())
                            .addIfNotBlank("title", history.getTitle())
                            .add("replyCount", replies.size()));
                } else if (!replies.isEmpty()) {
                    try {
                        String replId = null;
                    for (int i = 0; i < replies.size(); i++) {
                        BiliReply reply = replies.get(i);
                        reply.setRoot(replId);
                        reply.setParent(replId);
                        boolean permitAcquired = false;
                        BiliReplyResponse replyResponse;
                        try {
                            danmakuSendScheduler.waitForCommentSendPermit(user, history.getBvId(), i);
                            permitAcquired = true;
                            try {
                                replyResponse = BiliApi.sendVideoReply(user, reply);
                            } catch (RuntimeException e) {
                                danmakuSendScheduler.markCommentFailure(user, null);
                                throw e;
                            }
                        if (replyResponse.getCode() == 0) {
                            log.info("[BLR] {}", LogKvs.event("LiveMsgSendSync.Reply.Send.Success")
                                    .addIfNotBlank("bvid", history.getBvId())
                                    .addIfNotBlank("avId", reply.getOid())
                                    .add("index", i)
                                    .add("messageLen", reply.getMessage() == null ? 0 : reply.getMessage().length()));
                            //第一个评论进行置顶操作
                            if (i == 0) {
                                replId = replyResponse.getData().getRpid();
                                //等待一段时间，否则无法置顶
                                Thread.sleep(2000L);
                                reply.setRpid(replyResponse.getData().getRpid());
                                reply.setAction("1");
                                BiliReplyResponse response = BiliApi.topVideoReply(user, reply);
                                if (response.getCode() != 0) {
                                    log.error("[BLR] {}", LogKvs.event("LiveMsgSendSync.Reply.Top.Failed")
                                            .addIfNotBlank("bvid", history.getBvId())
                                            .addIfNotBlank("avId", reply.getOid())
                                            .addIfNotBlank("title", history.getTitle())
                                            .addIfNotBlank("rpid", reply.getRpid())
                                            .add("code", response.getCode())
                                            .addIfNotBlank("message", response.getMessage()));
                                }
                                if (response.getCode() == 404) {
                                    //等待一段时间，否则无法置顶
                                    Thread.sleep(2000L);
                                    BiliApi.topVideoReply(user, reply);
                                }
                            }

                            try {
                            } catch (Exception ignored) {

                            }
                        } else {
                            danmakuSendScheduler.markCommentFailure(user, replyResponse.getCode());
                            log.error("[BLR] {}", LogKvs.event("LiveMsgSendSync.Reply.Send.Failed")
                                    .addIfNotBlank("bvid", history.getBvId())
                                    .addIfNotBlank("avId", reply.getOid())
                                    .add("code", replyResponse.getCode())
                                    .addIfNotBlank("message", replyResponse.getMessage()));
                            throw new RuntimeException("发送评论失败: " + replyResponse.getMessage());
                        }
                        //等待一段时间在发送
                        } finally {
                            if (permitAcquired) {
                                danmakuSendScheduler.releaseCommentSendPermit(user);
                            }
                        }
                    }
                    // 全部发送成功后，标记为已发送
                    history.setSendReply(true);
                    history = historyRepository.save(history);
                } catch (Exception e) {
                    log.error("[BLR] {}", LogKvs.event("LiveMsgSendSync.Reply.BatchFailed")
                            .addIfNotBlank("title", history.getTitle())
                            .addIfNotBlank("bvid", history.getBvId())
                            .addIfNotBlank("avId", history.getAvId())
                            .add("replyCount", replies.size())
                            .addIfNotBlank("err", e.getMessage())
                            .add("ex", e.getClass().getSimpleName()), e);
                    try {
                    } catch (Exception ignored) {

                    }
                }
                } else {
                    log.info("[BLR] {}", LogKvs.event("LiveMsgSendSync.Reply.None")
                            .addIfNotBlank("title", history.getTitle())
                            .addIfNotBlank("bvid", history.getBvId())
                            .addIfNotBlank("avId", history.getAvId()));
                    // 即使没有评论，也稍微等待一下，避免操作过快
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        log.warn("[BLR] {}", LogKvs.event("LiveMsgSendSync.SleepInterrupted")
                                .add("waitMs", 2000), e);
                    }
                    history.setSendReply(true);
                    historyRepository.save(history);
                }

                if (isPrivateFlow) {
                    try {
                        // 使用轻量级接口切换回仅自己可见状态
                        String editRes = BiliApi.updateVideoVisibility(user, Long.parseLong(history.getAvId()), 1);
                        int editCode = -1;
                        String editMsg = null;
                        try {
                            com.alibaba.fastjson.JSONObject jsonObject = com.alibaba.fastjson.JSON.parseObject(editRes);
                            Integer c = jsonObject.getInteger("code");
                            editCode = c == null ? -1 : c;
                            editMsg = jsonObject.getString("message");
                        } catch (Exception ignored) {
                            // ignore
                        }
                        log.info("[BLR] {}", LogKvs.event("LiveMsgSendSync.Visibility.SwitchPrivate.Response")
                                .addIfNotBlank("bvid", history.getBvId())
                                .add("code", editCode)
                                .addIfNotBlank("message", editMsg)
                                .add("respLen", editRes == null ? 0 : editRes.length()));
                        
                        // 检查响应结果
                        com.alibaba.fastjson.JSONObject jsonObject = com.alibaba.fastjson.JSON.parseObject(editRes);
                        if (jsonObject.getInteger("code") != 0) {
                            log.error("[BLR] {}", LogKvs.event("LiveMsgSendSync.Visibility.SwitchPrivate.Failed")
                                    .addIfNotBlank("bvid", history.getBvId())
                                    .addIfNotBlank("avId", history.getAvId())
                                    .addIfNotBlank("title", history.getTitle())
                                    .add("code", jsonObject.getInteger("code"))
                                    .addIfNotBlank("message", jsonObject.getString("message")));
                        }
                    } catch (Exception e) {
                        log.error("[BLR] {}", LogKvs.event("LiveMsgSendSync.Visibility.SwitchPrivate.Error")
                                .addIfNotBlank("title", history.getTitle())
                                .addIfNotBlank("bvid", history.getBvId())
                                .addIfNotBlank("avId", history.getAvId())
                                .addIfNotBlank("err", e.getMessage())
                                .add("ex", e.getClass().getSimpleName()), e);
                    }
                    // 操作完成后等待一段时间，避免频繁请求
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        log.warn("[BLR] {}", LogKvs.event("LiveMsgSendSync.SleepInterrupted")
                                .add("waitMs", 5000), e);
                    }
                    continue;
                }
                }
            }
            // 只有非仅自己可见(-50)的稿件，才加入弹幕发送队列
            // 并且至少开启了普通弹幕或SC/上舰发送开关
            if (history.getCode() != -50 && (sendDmEnabled || sendScEnabled)) {
                partList.addAll(parts);
            }
        }
        if (CollectionUtils.isEmpty(partList)) {
            return;
        }
        candidatePartCount = partList.size();
        //普通弹幕
        List<LiveMsg> msgAllList = new ArrayList<>();
        List<RecordRoom> roomList = roomRepository.findBySendDmIsTrue();
        List<String> roomIds = roomList.stream().map(RecordRoom::getRoomId).toList();
        List<Long> partIds = partList.stream().map(RecordHistoryPart::getId).toList();
        List<Long> pendingCandidatePartIds = partIds.stream()
                .filter(pendingPartIdSet::contains)
                .toList();
        if (pendingCandidatePartIds.isEmpty()) {
            log.info("[BLR] {}", LogKvs.event("LiveMsgSendSync.PendingPart.Empty")
                    .add("candidatePart", partIds.size()));
            return;
        }
        
        // 高级弹幕 (分批查询避免 IN 语句过长)
        List<LiveMsg> allHighLevelMsg = new ArrayList<>();
        int pBatchSize = 500;
        for (int i = 0; i < pendingCandidatePartIds.size(); i += pBatchSize) {
            List<Long> subList = pendingCandidatePartIds.subList(i, Math.min(i + pBatchSize, pendingCandidatePartIds.size()));
            int remain = 500 - allHighLevelMsg.size();
            if (remain <= 0) break;
            Page<LiveMsg> msgPage = msgRepository.findByPoolAndCodeAndPartIdInOrderBySendTimeAsc(1, -1, subList, PageRequest.of(0, remain));
            if (!msgPage.isEmpty()) {
                allHighLevelMsg.addAll(msgPage.get().toList());
            }
        }
        pendingHighCount = allHighLevelMsg.size();

        // 房间设置是否发送弹幕
        List<Long> normalPartIds = partList.stream()
                .filter(p -> roomIds.contains(p.getRoomId()))
                .map(RecordHistoryPart::getId)
                .filter(pendingPartIdSet::contains)
                .toList();
        //一个用户一小时发送150条弹幕
        for (int i = 0; i < normalPartIds.size(); i += pBatchSize) {
            List<Long> subList = normalPartIds.subList(i, Math.min(i + pBatchSize, normalPartIds.size()));
            int remain = 500 - msgAllList.size();
            if (remain <= 0) break;
            Pageable pageable = Pageable.ofSize(remain);
            Page<LiveMsg> msgPage = msgRepository.findByPoolAndCodeAndPartIdInOrderBySendTimeAsc(0, -1, subList, pageable);
            if (!msgPage.isEmpty()) {
                msgAllList.addAll(msgPage.get().toList());
            }
        }
        pendingNormalCount = msgAllList.size();
        if (msgAllList.isEmpty() && allHighLevelMsg.isEmpty()) {
            return;
        }

        final long danmakuSendIntervalMs = systemConfigService.getDanmakuSendIntervalMs();

        try {
            boolean tryLock = lock.tryLock();
            if (!tryLock) {
                log.error("[BLR] {}", LogKvs.event("LiveMsgSendSync.Lock.Failed"));
                return;
            }
            //高优先级弹幕，如sc,舰长，只能由视频发布账号发送
            log.info("[BLR] {}", LogKvs.event("LiveMsgSendSync.HighLevel.Start")
                    .add("pending", allHighLevelMsg.size()));
            for (LiveMsg msg : allHighLevelMsg) {
                Long partId = msg.getPartId();
                if (skipAdvancedPartIds.contains(partId)) {
                    log.info("[BLR] {}", LogKvs.event("LiveMsgSendSync.HighLevel.SkipByManual")
                            .add("partId", partId)
                            .addIfNotBlank("bvid", msg.getBvid()));
                    continue;
                }
                Optional<RecordHistoryPart> partOptional = partRepository.findById(partId);
                if (partOptional.isPresent()) {
                    RecordHistoryPart part = partOptional.get();
                    String roomId = part.getRoomId();
                    RecordRoom room = roomRepository.findByRoomId(roomId);
                    if (room != null) {
                        if (!Boolean.TRUE.equals(room.getSendSc())) {
                            continue;
                        }
                        Long uploadUserId = room.getUploadUserId();
                        Optional<BiliBiliUser> userOptional = userRepository.findById(uploadUserId);
                        if (userOptional.isPresent()) {
                            BiliBiliUser user = userOptional.get();
                            if (!user.isLogin()) {
                                continue;
                            }
                            int code = liveMsgService.sendMsg(user, msg);
                            if (code != 0) {
                                log.error("[BLR] {}", LogKvs.event("LiveMsgSendSync.HighLevel.Send.Failed")
                                        .addIfNotBlank("uname", user.getUname())
                                        .add("code", code)
                                        .addIfNotBlank("bvid", msg.getBvid())
                                        .add("partId", msg.getPartId())
                                        .add("contextLen", msg.getContext() == null ? 0 : msg.getContext().length()));
                                try {
                                } catch (Exception ignored) {

                                }
                            }
                            try {
                                if (code == 36703) {
                                    log.warn("[BLR] {}", LogKvs.event("LiveMsgSendSync.HighLevel.RateLimit.Pause")
                                            .addIfNotBlank("uname", user.getUname())
                                            .add("code", code)
                                            .add("waitSec", 120));
                                    Thread.sleep(120 * 1000L);
                                } else if (code == 0) {
                                    Thread.sleep(danmakuSendIntervalMs);
                                } else {
                                    // 其他失败情况，默认暂停5秒，防止死循环风控
                                    Thread.sleep(5000L);
                                }
                            } catch (InterruptedException e) {
                                log.warn("[BLR] {}", LogKvs.event("LiveMsgSendSync.SleepInterrupted")
                                        .add("phase", "highLevelThrottle"), e);
                            }
                            continue;
                        }
                    }
                }
                msg.setCode(0);
                msgRepository.save(msg);
            }

            //普通弹幕发送
            if (msgAllList.size() == 0) {
                log.info("[BLR] {}", LogKvs.event("LiveMsgSendSync.Normal.EmptyExit"));
                return;
            }
            BlockingQueue<LiveMsg> msgQueue = new ArrayBlockingQueue<>(msgAllList.size());
            msgQueue.addAll(msgAllList);
            AtomicInteger count = new AtomicInteger(0);
            log.info("[BLR] {}", LogKvs.event("LiveMsgSendSync.Normal.Start")
                    .add("pending", msgQueue.size()));
            allUser.stream().parallel().forEach(user -> {
                while (msgQueue.size() > 0) {
                    if (System.currentTimeMillis() - startTime > 2 * 3600 * 1000) {
                        LiveMsg peekMsg = msgQueue.peek();
                        if (peekMsg != null) {
                            String title = "";
                            try {
                                Optional<RecordHistoryPart> partOpt = partRepository.findById(peekMsg.getPartId());
                                if (partOpt.isPresent()) {
                                    title = partOpt.get().getTitle();
                                }
                            } catch (Exception e) {
                                // ignore
                            }
                            log.info("[BLR] {}", LogKvs.event("LiveMsgSendSync.Normal.TimeLimitStop")
                                    .add("limitSec", 7200)
                                    .addIfNotBlank("bvid", peekMsg.getBvid())
                                    .addIfNotBlank("title", title));
                        } else {
                            log.info("[BLR] {}", LogKvs.event("LiveMsgSendSync.Normal.TimeLimitStop")
                                    .add("limitSec", 7200));
                        }
                        return;
                    }
                    LiveMsg msg = null;
                    try {
                        msg = msgQueue.poll(10, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        log.warn("[BLR] {}", LogKvs.event("LiveMsgSendSync.Normal.QueuePollInterrupted"), e);
                    }
                    if (msg == null) {
                        return;
                    }
                    if (skipOrdinaryPartIds.contains(msg.getPartId())) {
                        log.info("[BLR] {}", LogKvs.event("LiveMsgSendSync.Normal.SkipByManual")
                                .add("partId", msg.getPartId())
                                .addIfNotBlank("bvid", msg.getBvid()));
                        continue;
                    }
                    count.incrementAndGet();
                    user = userRepository.findByUid(user.getUid());
                    if (!(user.isLogin() && user.isEnable())) {
                        log.error("[BLR] {}", LogKvs.event("LiveMsgSendSync.User.InvalidState")
                                .add("uid", user.getUid())
                                .addIfNotBlank("uname", user.getUname())
                                .add("login", user.isLogin())
                                .add("enable", user.isEnable()));
                        return;
                    }
                    int code = liveMsgService.sendMsg(user, msg);
                    if (code == 36703) {
                        log.warn("[BLR] {}", LogKvs.event("LiveMsgSendSync.Normal.RateLimit.RetryOnce")
                                .addIfNotBlank("uname", user.getUname())
                                .add("code", code)
                                .add("waitMs", 5000)
                                .add("sent", count.get()));
                        try {
                            Thread.sleep(5000L);
                        } catch (InterruptedException e) {
                            log.warn("[BLR] {}", LogKvs.event("LiveMsgSendSync.SleepInterrupted")
                                    .add("phase", "normalRateLimitRetry"), e);
                        }
                        user = userRepository.findByUid(user.getUid());
                        code = liveMsgService.sendMsg(user, msg);
                        if (code == 36703) {
                            log.error("[BLR] {}", LogKvs.event("LiveMsgSendSync.Normal.RateLimit.Pause")
                                    .addIfNotBlank("uname", user.getUname())
                                    .add("code", code)
                                    .add("sent", count.get())
                                    .add("waitSec", 120));

                        }
                    } else if (code == 36714) {
                        log.error("[BLR] {}", LogKvs.event("LiveMsgSendSync.Normal.Send.InvalidTime")
                                .addIfNotBlank("uname", user.getUname())
                                .add("code", code)
                                .add("sent", count.get()));
                    } else if (code == 36704) {
                        log.error("[BLR] {}", LogKvs.event("LiveMsgSendSync.Normal.Send.VideoNotApproved")
                                .addIfNotBlank("uname", user.getUname())
                                .add("code", code)
                                .add("sent", count.get())
                                .addIfNotBlank("bvid", msg.getBvid()));
                        return;
                    }else if(code == -101 || code == -102 || code == -111 || code == -400 || code == -404 || code == -36700){
                        log.error("[BLR] {}", LogKvs.event("LiveMsgSendSync.Normal.Send.UserDisabled")
                                .addIfNotBlank("uname", user.getUname())
                                .add("uid", user.getUid())
                                .add("code", code)
                                .add("sent", count.get()));
                        user.setEnable(false);
                        user = userRepository.save(user);
                    }else if(code != 0){
                        log.error("[BLR] {}", LogKvs.event("LiveMsgSendSync.Normal.Send.Failed")
                                .addIfNotBlank("uname", user.getUname())
                                .add("code", code)
                                .add("sent", count.get())
                                .addIfNotBlank("bvid", msg.getBvid()));
                        return;
                    }
                    try {
                        if (code == 36703) {
                            Thread.sleep(120 * 1000L);
                        } else if (code == 0) {
                            Thread.sleep(danmakuSendIntervalMs);
                        } else {
                            // 其他未中断的错误（如36714），默认暂停5秒
                            Thread.sleep(5000L);
                        }
                    } catch (InterruptedException e) {
                        log.warn("[BLR] {}", LogKvs.event("LiveMsgSendSync.SleepInterrupted")
                                .add("phase", "normalThrottle"), e);
                    }
                }
            });
        } finally {
            lock.unlock();
        }
        } finally {
            log.info("[BLR] {}", LogKvs.event("LiveMsgSendSync.Done")
                    .addRoundCount("history", historyCount)
                    .addRoundCount("candidatePart", candidatePartCount)
                    .addRoundCount("pendingNormal", pendingNormalCount)
                    .addRoundCount("pendingHigh", pendingHighCount)
                    .addStageCostMs("total", roundStartNs));
        }

    }

    public void enqueueHistoryDispatch(Long historyId) {
        if (historyId == null) {
            return;
        }
        enqueueHistoryDispatchInternal(historyId);
    }

    private boolean isQueuedDispatchMode() {
        return true;
    }

    private ReconcileStats reconcileQueuedDispatch(boolean force) {
        long now = System.currentTimeMillis();
        long intervalMs = systemConfigService.getDanmakuReconcileIntervalMs();
        if (!force && now - lastReconcileAtMs < intervalMs) {
            return new ReconcileStats(0, 0, danmakuSendScheduler.pendingNormalPartCount(), danmakuSendScheduler.pendingHighPartCount());
        }
        if (!lock.tryLock()) {
            log.warn("[BLR] {}", LogKvs.event("LiveMsgSendSync.Lock.Failed"));
            return new ReconcileStats(0, 0, danmakuSendScheduler.pendingNormalPartCount(), danmakuSendScheduler.pendingHighPartCount());
        }
        long startNs = System.nanoTime();
        int replyEnqueued = 0;
        int normalEnqueued = 0;
        int highEnqueued = 0;
        try {
            lastReconcileAtMs = now;
            int batchSize = systemConfigService.getDanmakuDispatchBatchSize();
            Pageable page = PageRequest.of(0, batchSize);

            List<RecordHistory> replyHistories = historyRepository.findPendingReplyHistories(page);
            for (RecordHistory history : replyHistories) {
                if (history != null && history.getId() != null) {
                    if (danmakuSendScheduler.enqueueReply(history.getId(), () -> processReplyThenDispatch(history.getId()))) {
                        replyEnqueued++;
                    }
                }
            }

            List<Long> highPartIds = msgRepository.findPendingHighDispatchPartIds(page);
            for (Long partId : highPartIds) {
                if (danmakuSendScheduler.enqueueHighPart(partId)) {
                    highEnqueued++;
                }
            }
            List<Long> normalPartIds = msgRepository.findPendingNormalDispatchPartIds(page);
            for (Long partId : normalPartIds) {
                if (danmakuSendScheduler.enqueueNormalPart(partId)) {
                    normalEnqueued++;
                }
            }

            int duplicateSkipped = replyHistories.size() + normalPartIds.size() + highPartIds.size()
                    - replyEnqueued - normalEnqueued - highEnqueued;
            log.info("[BLR] {}", LogKvs.event("LiveMsgSendSync.Reconcile.Done")
                    .add("replyDiscovered", replyHistories.size())
                    .add("normalDiscovered", normalPartIds.size())
                    .add("highDiscovered", highPartIds.size())
                    .add("replyEnqueued", replyEnqueued)
                    .add("normalEnqueued", normalEnqueued)
                    .add("highEnqueued", highEnqueued)
                    .add("duplicateSkipped", duplicateSkipped)
                    .add("pendingReplyQueue", danmakuSendScheduler.pendingReplyCount())
                    .add("pendingNormalQueue", danmakuSendScheduler.pendingNormalPartCount())
                    .add("pendingHighQueue", danmakuSendScheduler.pendingHighPartCount())
                    .addStageCostMs("reconcile", startNs));
            return new ReconcileStats(replyHistories.size(), normalPartIds.size() + highPartIds.size(),
                    normalPartIds.size(), highPartIds.size());
        } finally {
            lock.unlock();
        }
    }

    private void processReplyThenDispatch(Long historyId) {
        try {
            processReply(historyId);
        } finally {
            enqueuePartDispatchByHistory(historyId);
        }
    }

    private void enqueueHistoryDispatchInternal(Long historyId) {
        Optional<RecordHistory> historyOptional = historyRepository.findById(historyId);
        if (historyOptional.isEmpty()) {
            return;
        }
        RecordHistory history = historyOptional.get();
        if (!history.isPublish() || (history.getCode() != 0 && history.getCode() != -50)) {
            return;
        }
        if (!history.isSendReply()) {
            danmakuSendScheduler.enqueueReply(historyId, () -> processReplyThenDispatch(historyId));
        } else {
            enqueuePartDispatchByHistory(historyId);
        }
    }

    private void enqueuePartDispatchByHistory(Long historyId) {
        if (historyId == null) {
            return;
        }
        int batchSize = systemConfigService.getDanmakuDispatchBatchSize();
        Pageable page = PageRequest.of(0, batchSize);
        for (Long partId : msgRepository.findPendingHighDispatchPartIdsByHistoryId(historyId, page)) {
            danmakuSendScheduler.enqueueHighPart(partId);
        }
        for (Long partId : msgRepository.findPendingNormalDispatchPartIdsByHistoryId(historyId, page)) {
            danmakuSendScheduler.enqueueNormalPart(partId);
        }
    }

    private void processReply(Long historyId) {
        long startNs = System.nanoTime();
        Optional<RecordHistory> historyOptional = historyRepository.findById(historyId);
        if (historyOptional.isEmpty()) {
            return;
        }
        RecordHistory history = historyOptional.get();
        if (!history.isPublish() || history.isSendReply() || (history.getCode() != 0 && history.getCode() != -50)) {
            return;
        }
        List<RecordHistoryPart> parts = partRepository.findDispatchablePartsByHistoryId(history.getId());
        RecordRoom room = roomRepository.findByRoomId(history.getRoomId());
        boolean sendDmEnabled = room != null && Boolean.TRUE.equals(room.getSendDm());
        boolean sendScEnabled = room != null && Boolean.TRUE.equals(room.getSendSc());
        boolean sendGiftReplyEnabled = room != null && Boolean.TRUE.equals(room.getSendGiftReply());
        if (!sendDmEnabled && !sendScEnabled && !sendGiftReplyEnabled) {
            history.setSendReply(true);
            historyRepository.save(history);
            log.info("[BLR] {}", LogKvs.event("LiveMsgSendSync.AllDmDisabled.Archive")
                    .add("roomId", history.getRoomId())
                    .add("historyId", history.getId())
                    .addIfNotBlank("bvid", history.getBvId())
                    .addIfNotBlank("title", history.getTitle()));
            return;
        }
        if (!sendScEnabled && !sendGiftReplyEnabled) {
            history.setSendReply(true);
            historyRepository.save(history);
            log.info("[BLR] {}", LogKvs.event("LiveMsgSendSync.Reply.SkipByRoomConfig")
                    .add("roomId", history.getRoomId())
                    .add("historyId", history.getId())
                    .addIfNotBlank("bvid", history.getBvId())
                    .addIfNotBlank("title", history.getTitle()));
            return;
        }

        BiliBiliUser user = resolveUploadUser(room, history);
        boolean isPrivateFlow = false;
        try {
            if (user != null) {
                BiliVideoInfoResponse videoInfo = BiliApi.getVideoInfo(user, history.getBvId());
                if (videoInfo != null && videoInfo.getData() != null && videoInfo.getData().getState() == -50) {
                    isPrivateFlow = true;
                    switchVisibility(history, user, 0, "LiveMsgSendSync.Visibility.SwitchPublic.Response");
                    sleepQuietly(15_000L, "privatePublicWait");
                }
            }
        } catch (Exception e) {
            log.error("[BLR] {}", LogKvs.event("LiveMsgSendSync.PrivateFlow.SkipByError")
                    .addIfNotBlank("title", history.getTitle())
                    .addIfNotBlank("bvid", history.getBvId())
                    .addIfNotBlank("avId", history.getAvId())
                    .addIfNotBlank("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()), e);
            msgQueueCleanupService.cleanupByHistoryId(history.getId(),
                    new HistoryMsgQueueCleanupService.CleanupOptions(false, true, true, false),
                    false,
                    "reply-private-visibility-failed");
            return;
        }

        try {
            DateFormat format = new SimpleDateFormat("HH:mm:ss");
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            ReplyPageResolver pageResolver = buildReplyPageResolver(history, user, parts);
            List<String> replyLines = new ArrayList<>();
            List<Long> partIds = parts.stream()
                    .map(RecordHistoryPart::getId)
                    .filter(Objects::nonNull)
                    .toList();
            boolean hasScReply = false;
            boolean hasGuardReply = false;
            boolean hasOtherHighLevelReply = false;
            if (sendScEnabled && !partIds.isEmpty()) {
                Map<Long, List<LiveMsg>> messagesByPart = new HashMap<>();
                for (LiveMsg liveMsg : msgRepository.findByPartIdInAndPoolAndCidNotNullOrderByPartIdAscSendTimeAsc(partIds, 1)) {
                    messagesByPart.computeIfAbsent(liveMsg.getPartId(), ignored -> new ArrayList<>()).add(liveMsg);
                }
                for (RecordHistoryPart part : parts) {
                    List<LiveMsg> msgList = messagesByPart.getOrDefault(part.getId(), Collections.emptyList());
                    for (LiveMsg liveMsg : msgList) {
                        String contextText = liveMsg.getContext();
                        if (contextText != null && contextText.startsWith("SC [")) {
                            hasScReply = true;
                        } else if (contextText != null && contextText.startsWith("⚓")) {
                            hasGuardReply = true;
                        } else {
                            hasOtherHighLevelReply = true;
                        }
                        replyLines.add(pageResolver.resolve(part) + "#" + format.format(new Date(liveMsg.getSendTime()))
                                + "  " + contextText + "\n");
                    }
                }
            }
            int giftReplyCount = 0;
            if (sendGiftReplyEnabled) {
                giftReplyCount = appendGiftReplyLines(replyLines, history, parts, room, format, pageResolver);
            }
            List<BiliReply> replies = buildVideoReplies(history,
                    buildReplyHeader(hasScReply, hasGuardReply, hasOtherHighLevelReply, giftReplyCount),
                    replyLines);
            if (user == null && !replies.isEmpty()) {
                log.warn("[BLR] {}", LogKvs.event("LiveMsgSendSync.Reply.SkipNoUploadUser")
                        .add("roomId", history.getRoomId())
                        .add("historyId", history.getId())
                        .addIfNotBlank("bvid", history.getBvId())
                        .addIfNotBlank("title", history.getTitle())
                        .add("replyCount", replies.size()));
                return;
            }
            if (replies.isEmpty()) {
                log.info("[BLR] {}", LogKvs.event("LiveMsgSendSync.Reply.None")
                        .addIfNotBlank("title", history.getTitle())
                        .addIfNotBlank("bvid", history.getBvId())
                        .addIfNotBlank("avId", history.getAvId()));
                history.setSendReply(true);
                historyRepository.save(history);
                return;
            }
            sendReplies(history, room, user, replies);
            history.setSendReply(true);
            historyRepository.save(history);
            log.info("[BLR] {}", LogKvs.event("LiveMsgSendSync.Reply.Dispatch.Done")
                    .add("historyId", history.getId())
                    .add("replyCount", replies.size())
                    .addStageCostMs("reply", startNs));
        } finally {
            if (isPrivateFlow && user != null) {
                try {
                    switchVisibility(history, user, 1, "LiveMsgSendSync.Visibility.SwitchPrivate.Response");
                    sleepQuietly(5_000L, "privateSwitchBackWait");
                } catch (Exception e) {
                    log.error("[BLR] {}", LogKvs.event("LiveMsgSendSync.Visibility.SwitchPrivate.Error")
                            .addIfNotBlank("title", history.getTitle())
                            .addIfNotBlank("bvid", history.getBvId())
                            .addIfNotBlank("avId", history.getAvId())
                            .addIfNotBlank("err", e.getMessage())
                            .add("ex", e.getClass().getSimpleName()), e);
                }
            }
        }
    }

    private BiliBiliUser resolveUploadUser(RecordRoom room, RecordHistory history) {
        if (room == null || room.getUploadUserId() == null) {
            return null;
        }
        Optional<BiliBiliUser> userOptional = userRepository.findById(room.getUploadUserId());
        if (userOptional.isEmpty()) {
            return null;
        }
        BiliBiliUser user = userOptional.get();
        if (!(user.getUid() != null && user.isLogin())) {
            log.warn("[BLR] {}", LogKvs.event("LiveMsgSendSync.UploadUser.InvalidState")
                    .add("uid", user.getUid())
                    .addIfNotBlank("uname", user.getUname())
                    .add("login", user.isLogin())
                    .add("enable", user.isEnable())
                    .add("historyId", history.getId())
                    .addIfNotBlank("bvid", history.getBvId()));
            return null;
        }
        return user;
    }

    private void switchVisibility(RecordHistory history, BiliBiliUser user, int visibility, String eventName) {
        String editRes = BiliApi.updateVideoVisibility(user, Long.parseLong(history.getAvId()), visibility);
        int editCode = -1;
        String editMsg = null;
        try {
            com.alibaba.fastjson.JSONObject jsonObject = com.alibaba.fastjson.JSON.parseObject(editRes);
            Integer c = jsonObject.getInteger("code");
            editCode = c == null ? -1 : c;
            editMsg = jsonObject.getString("message");
            if (editCode != 0) {
                throw new RuntimeException("visibility switch failed: " + editRes);
            }
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
                .add("visibility", visibility)
                .add("code", editCode)
                .addIfNotBlank("message", editMsg)
                .add("respLen", editRes == null ? 0 : editRes.length()));
    }

    private void sendReplies(RecordHistory history, RecordRoom room, BiliBiliUser user, List<BiliReply> replies) {
        try {
            String replId = null;
            for (int i = 0; i < replies.size(); i++) {
                BiliReply reply = replies.get(i);
                reply.setRoot(replId);
                reply.setParent(replId);
                boolean permitAcquired = false;
                try {
                    danmakuSendScheduler.waitForCommentSendPermit(user, history.getBvId(), i);
                    permitAcquired = true;
                    BiliReplyResponse replyResponse;
                    try {
                        replyResponse = BiliApi.sendVideoReply(user, reply);
                    } catch (RuntimeException e) {
                        danmakuSendScheduler.markCommentFailure(user, null);
                        throw e;
                    }
                    if (replyResponse.getCode() != 0) {
                        danmakuSendScheduler.markCommentFailure(user, replyResponse.getCode());
                        log.error("[BLR] {}", LogKvs.event("LiveMsgSendSync.Reply.Send.Failed")
                                .addIfNotBlank("bvid", history.getBvId())
                                .addIfNotBlank("avId", reply.getOid())
                                .add("code", replyResponse.getCode())
                                .addIfNotBlank("message", replyResponse.getMessage()));
                        throw new RuntimeException("send reply failed: " + replyResponse.getMessage());
                    }
                    log.info("[BLR] {}", LogKvs.event("LiveMsgSendSync.Reply.Send.Success")
                            .addIfNotBlank("bvid", history.getBvId())
                            .addIfNotBlank("avId", reply.getOid())
                            .add("index", i)
                            .add("messageLen", reply.getMessage() == null ? 0 : reply.getMessage().length()));
                    if (i == 0 && replyResponse.getData() != null) {
                        replId = replyResponse.getData().getRpid();
                        sleepQuietly(2_000L, "replyTopWait");
                        reply.setRpid(replyResponse.getData().getRpid());
                        reply.setAction("1");
                        BiliReplyResponse response = BiliApi.topVideoReply(user, reply);
                        if (response.getCode() == 404) {
                            sleepQuietly(2_000L, "replyTopRetryWait");
                            BiliApi.topVideoReply(user, reply);
                        } else if (response.getCode() != 0) {
                            log.error("[BLR] {}", LogKvs.event("LiveMsgSendSync.Reply.Top.Failed")
                                    .addIfNotBlank("bvid", history.getBvId())
                                    .addIfNotBlank("avId", reply.getOid())
                                    .addIfNotBlank("rpid", reply.getRpid())
                                    .add("code", response.getCode())
                                    .addIfNotBlank("message", response.getMessage()));
                        }
                    }
                } finally {
                    if (permitAcquired) {
                        danmakuSendScheduler.releaseCommentSendPermit(user);
                    }
                }
                sendReplyPush(room, history, user, reply);
            }
        } catch (Exception e) {
            log.error("[BLR] {}", LogKvs.event("LiveMsgSendSync.Reply.BatchFailed")
                    .addIfNotBlank("title", history.getTitle())
                    .addIfNotBlank("bvid", history.getBvId())
                    .addIfNotBlank("avId", history.getAvId())
                    .add("replyCount", replies.size())
                    .addIfNotBlank("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()), e);
            sendReplyFailurePush(room, history, replies, e);
            throw e;
        }
    }

    private void sendReplyPush(RecordRoom room, RecordHistory history, BiliBiliUser user, BiliReply reply) {
        try {
        } catch (Exception ignored) {
        }
    }

    private void sendReplyFailurePush(RecordRoom room, RecordHistory history, List<BiliReply> replies, Exception error) {
        try {
        } catch (Exception ignored) {
        }
    }

    private void sleepQuietly(long waitMs, String phase) {
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[BLR] {}", LogKvs.event("LiveMsgSendSync.SleepInterrupted")
                    .add("phase", phase)
                    .add("waitMs", waitMs), e);
        }
    }

    private record ReconcileStats(int historyCount, int candidatePartCount, int pendingNormalCount, int pendingHighCount) {
    }

    private String buildReplyHeader(boolean hasScReply, boolean hasGuardReply, boolean hasOtherHighLevelReply, int giftReplyCount) {
        List<String> parts = new ArrayList<>();
        if (hasScReply) {
            parts.add("SC");
        }
        if (hasGuardReply) {
            parts.add("上舰");
        }
        if (hasOtherHighLevelReply) {
            parts.add("高级弹幕");
        }
        if (giftReplyCount > 0) {
            parts.add("高价值礼物");
        }
        if (parts.isEmpty()) {
            return "高价值互动列表\n";
        }
        return String.join("/", parts) + "列表\n";
    }

    private List<BiliReply> buildVideoReplies(RecordHistory history, String header, List<String> lines) {
        List<BiliReply> replies = new ArrayList<>();
        if (lines == null || lines.isEmpty()) {
            return replies;
        }
        StringBuilder context = new StringBuilder(header);
        for (String line : lines) {
            if (context.length() + line.length() > 1000 && context.length() > header.length()) {
                replies.add(buildVideoReply(history, context.toString()));
                context = new StringBuilder(header);
            }
            context.append(line);
        }
        if (context.length() > header.length()) {
            replies.add(buildVideoReply(history, context.toString()));
        }
        return replies;
    }

    private BiliReply buildVideoReply(RecordHistory history, String message) {
        BiliReply reply = new BiliReply();
        reply.setType("1");
        reply.setOid(history.getAvId());
        reply.setAction("1");
        reply.setMessage(message);
        return reply;
    }

    private int appendGiftReplyLines(List<String> replyLines,
                                     RecordHistory history,
                                     List<RecordHistoryPart> parts,
                                     RecordRoom room,
                                     DateFormat format,
                                     ReplyPageResolver pageResolver) {
        Map<Long, RecordHistoryPart> partById = new HashMap<>();
        for (RecordHistoryPart part : parts) {
            if (part == null || part.getId() == null) {
                continue;
            }
            partById.put(part.getId(), part);
            roomLiveEventParseService.parsePart(part, false);
        }

        List<RoomLiveEvent> giftEvents = roomLiveEventRepository.findByHistoryIdAndTypeOrderByPartIdAscSendTimeAsc(
                history.getId(), RoomLiveEvent.TYPE_GIFT);
        if (giftEvents.isEmpty()) {
            log.info("[BLR] {}", LogKvs.event("LiveMsgSendSync.GiftReply.Scan")
                    .add("roomId", history.getRoomId())
                    .add("historyId", history.getId())
                    .addIfNotBlank("bvid", history.getBvId())
                    .add("giftEvent", 0)
                    .add("appended", 0)
                    .add("minPriceCny", giftReplyCandidateService.formatMoney(room == null ? null : room.getGiftReplyMinPriceCny()))
                    .add("skippedNoPart", 0)
                    .add("skippedNoPrice", 0)
                    .add("skippedBelowThreshold", 0));
            return 0;
        }

        GiftReplyCandidateService.GiftReplyScan scan = giftReplyCandidateService.scan(history, room, parts, giftEvents);
        for (GiftReplyCandidateService.GiftReplyCandidate candidate : scan.candidates()) {
            RoomLiveEvent event = candidate.event();
            RecordHistoryPart part = partById.get(event.getPartId());
            String uname = hasText(event.getUname()) ? event.getUname() : "未知用户";
            String giftName = hasText(event.getGiftName())
                    ? event.getGiftName()
                    : (hasText(candidate.giftName()) ? candidate.giftName() : "未知礼物");
            long sendTime = event.getSendTime() == null ? 0L : event.getSendTime();
            replyLines.add(pageResolver.resolve(part) + "#" + format.format(new Date(sendTime))
                    + "  礼物 [💎" + giftReplyCandidateService.formatMoney(candidate.unitPrice()) + "] "
                    + uname + ": " + giftName + " x" + candidate.count()
                    + "，总计💎" + giftReplyCandidateService.formatMoney(candidate.totalPrice()) + "\n");
        }
        Map<String, Integer> appendedByPriceSource = scan.matchedByPriceSource();
        log.info("[BLR] {}", LogKvs.event("LiveMsgSendSync.GiftReply.Scan")
                .add("roomId", history.getRoomId())
                .add("historyId", history.getId())
                .addIfNotBlank("bvid", history.getBvId())
                .add("giftEvent", giftEvents.size())
                .add("appended", scan.candidates().size())
                .add("minPriceCny", giftReplyCandidateService.formatMoney(scan.minPrice()))
                .add("skippedNoPart", scan.skippedNoPart())
                .add("skippedNoPrice", scan.skippedNoPrice())
                .add("skippedBelowThreshold", scan.skippedBelowThreshold())
                .add("rawPriceHit", appendedByPriceSource.getOrDefault("raw", 0))
                .add("roomCatalogHit", appendedByPriceSource.getOrDefault("room_catalog", 0))
                .add("localGiftIdFallback", appendedByPriceSource.getOrDefault("local_gift_id", 0))
                .add("localGiftNameFallback", appendedByPriceSource.getOrDefault("local_gift_name", 0)));
        return scan.candidates().size();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private ReplyPageResolver buildReplyPageResolver(RecordHistory history, BiliBiliUser user, List<RecordHistoryPart> orderedParts) {
        ReplyPageResolver resolver = ReplyPageResolver.localFallback(orderedParts);
        if (history == null || user == null || !hasText(history.getBvId())) {
            return resolver;
        }
        try {
            BiliVideoPartInfoResponse partInfo = BiliApi.getVideoPartInfo(user, history.getBvId());
            ReplyPageResolver withOnlineSnapshot = ReplyPageResolver.fromOnlineSnapshot(orderedParts, partInfo);
            log.info("[BLR] {}", LogKvs.event("LiveMsgSendSync.Reply.PageSnapshot")
                    .add("historyId", history.getId())
                    .addIfNotBlank("bvid", history.getBvId())
                    .add("partInfoCode", partInfo == null ? null : partInfo.getCode())
                    .add("onlinePageCount", withOnlineSnapshot.onlinePageCount())
                    .add("cidMapCount", withOnlineSnapshot.cidMapCount())
                    .add("fileNameMapCount", withOnlineSnapshot.fileNameMapCount())
                    .add("titleMapCount", withOnlineSnapshot.titleMapCount()));
            return withOnlineSnapshot;
        } catch (Exception e) {
            log.warn("[BLR] {}", LogKvs.event("LiveMsgSendSync.Reply.PageSnapshot.Failed")
                    .add("historyId", history.getId())
                    .addIfNotBlank("bvid", history.getBvId())
                    .addIfNotBlank("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()));
            return resolver;
        }
    }

    static int resolveReplyPage(RecordHistoryPart part, List<RecordHistoryPart> orderedParts) {
        if (part == null) {
            return 1;
        }
        if (part.getPage() > 0) {
            return part.getPage();
        }
        if (orderedParts != null) {
            for (int i = 0; i < orderedParts.size(); i++) {
                if (isSamePart(part, orderedParts.get(i))) {
                    return i + 1;
                }
            }
        }
        Integer partOrder = part.getPartOrder();
        if (partOrder != null && partOrder > 0) {
            return partOrder;
        }
        return 1;
    }

    private static boolean isSamePart(RecordHistoryPart left, RecordHistoryPart right) {
        if (left == right) {
            return true;
        }
        return left != null
                && right != null
                && left.getId() != null
                && left.getId().equals(right.getId());
    }

    static final class ReplyPageResolver {
        private final List<RecordHistoryPart> orderedParts;
        private final Map<Long, Integer> pageByCid;
        private final Map<String, Integer> pageByFileName;
        private final Map<String, Integer> pageByTitle;
        private final int onlinePageCount;

        private ReplyPageResolver(List<RecordHistoryPart> orderedParts,
                                  Map<Long, Integer> pageByCid,
                                  Map<String, Integer> pageByFileName,
                                  Map<String, Integer> pageByTitle,
                                  int onlinePageCount) {
            this.orderedParts = orderedParts == null ? Collections.emptyList() : List.copyOf(orderedParts);
            this.pageByCid = pageByCid == null ? Collections.emptyMap() : Map.copyOf(pageByCid);
            this.pageByFileName = pageByFileName == null ? Collections.emptyMap() : Map.copyOf(pageByFileName);
            this.pageByTitle = pageByTitle == null ? Collections.emptyMap() : Map.copyOf(pageByTitle);
            this.onlinePageCount = onlinePageCount;
        }

        static ReplyPageResolver localFallback(List<RecordHistoryPart> orderedParts) {
            return new ReplyPageResolver(orderedParts, Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(), 0);
        }

        static ReplyPageResolver fromOnlineSnapshot(List<RecordHistoryPart> orderedParts, BiliVideoPartInfoResponse partInfo) {
            if (partInfo == null || partInfo.getCode() != 0 || partInfo.getData() == null
                    || partInfo.getData().getVideos() == null || partInfo.getData().getVideos().isEmpty()) {
                return localFallback(orderedParts);
            }

            Map<Long, Integer> pageByCid = new HashMap<>();
            Map<String, Integer> pageByFileName = new HashMap<>();
            Map<String, Integer> pageByTitle = new HashMap<>();
            Set<String> duplicateFileNames = new HashSet<>();
            Set<String> duplicateTitles = new HashSet<>();
            int fallbackPage = 1;
            int onlinePageCount = 0;
            for (BiliVideoPartInfoResponse.Video video : partInfo.getData().getVideos()) {
                if (video == null) {
                    continue;
                }
                int page = video.getPage() > 0 ? video.getPage() : fallbackPage;
                fallbackPage++;
                if (page <= 0) {
                    continue;
                }
                onlinePageCount++;
                if (video.getCid() > 0) {
                    pageByCid.put(video.getCid(), page);
                }
                putUniquePage(pageByFileName, duplicateFileNames, normalizeReplyPageKey(video.getFilename()), page);
                putUniquePage(pageByTitle, duplicateTitles, normalizeReplyPageKey(video.getTitle()), page);
                putUniquePage(pageByTitle, duplicateTitles, normalizeReplyPageKey(video.getPart()), page);
            }
            removeDuplicateKeys(pageByFileName, duplicateFileNames);
            removeDuplicateKeys(pageByTitle, duplicateTitles);
            return new ReplyPageResolver(orderedParts, pageByCid, pageByFileName, pageByTitle, onlinePageCount);
        }

        int resolve(RecordHistoryPart part) {
            Integer onlinePage = resolveOnlinePage(part);
            if (onlinePage != null && onlinePage > 0) {
                return onlinePage;
            }
            return resolveReplyPage(part, orderedParts);
        }

        private Integer resolveOnlinePage(RecordHistoryPart part) {
            if (part == null) {
                return null;
            }
            Long cid = part.getCid();
            if (cid != null && cid > 0 && pageByCid.containsKey(cid)) {
                return pageByCid.get(cid);
            }
            String fileNameKey = normalizeReplyPageKey(part.getFileName());
            if (fileNameKey != null && pageByFileName.containsKey(fileNameKey)) {
                return pageByFileName.get(fileNameKey);
            }
            String titleKey = normalizeReplyPageKey(part.getTitle());
            if (titleKey != null && pageByTitle.containsKey(titleKey)) {
                return pageByTitle.get(titleKey);
            }
            return null;
        }

        int onlinePageCount() {
            return onlinePageCount;
        }

        int cidMapCount() {
            return pageByCid.size();
        }

        int fileNameMapCount() {
            return pageByFileName.size();
        }

        int titleMapCount() {
            return pageByTitle.size();
        }

        private static void putUniquePage(Map<String, Integer> target, Set<String> duplicates, String key, int page) {
            if (key == null || duplicates.contains(key)) {
                return;
            }
            Integer previous = target.putIfAbsent(key, page);
            if (previous != null && previous != page) {
                duplicates.add(key);
                target.remove(key);
            }
        }

        private static void removeDuplicateKeys(Map<String, Integer> target, Set<String> duplicates) {
            for (String key : duplicates) {
                target.remove(key);
            }
        }
    }

    private static String normalizeReplyPageKey(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

}


