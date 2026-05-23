package top.sshh.bililiverecoder.job;

import com.alibaba.fastjson.JSON;
import com.zjiecode.wxpusher.client.bean.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.entity.data.*;
import top.sshh.bililiverecoder.repo.*;
import top.sshh.bililiverecoder.service.RoomLiveEventParseService;
import top.sshh.bililiverecoder.service.RoomLiveGiftCatalogService;
import top.sshh.bililiverecoder.service.SystemConfigService;
import top.sshh.bililiverecoder.service.impl.LiveMsgService;
import top.sshh.bililiverecoder.util.BiliApi;
import top.sshh.bililiverecoder.util.LogKvs;
import top.sshh.bililiverecoder.util.PushNotifyClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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

    @Value("${record.wx-push-token}")
    private String wxToken;
    private static final String WX_MSG_FORMAT = """
            收到弹幕评论发送事件
            主播名: %s
            房间名: %s
            BV号: %s
            时间: %s
            发送内容: %s
            发送结果: %s
            原因: %s
            """;
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
    private RoomLiveGiftCatalogRepository roomLiveGiftCatalogRepository;

    @Autowired
    private RoomLiveGiftCatalogService roomLiveGiftCatalogService;

    @Autowired
    private SystemConfigService systemConfigService;

    private static final Lock lock = new ReentrantLock();

    public static Set<Long> skipOrdinaryPartIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    public static Set<Long> skipAdvancedPartIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Scheduled(fixedDelay = 60000, initialDelay = 5000)
    public void sndMsgProcess() {
        skipOrdinaryPartIds.clear();
        skipAdvancedPartIds.clear();
        long roundStartNs = System.nanoTime();
        int historyCount = 0;
        int candidatePartCount = 0;
        int pendingNormalCount = 0;
        int pendingHighCount = 0;
        log.debug("[BLR] {}", LogKvs.event("LiveMsgSendSync.Start"));
        try {
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

            //如果没有发送评论
            if (!history.isSendReply()) {
                String wxuid = null;
                String pushMsgTags = null;
                BiliBiliUser user = null;
                if (room != null) {
                    wxuid = room.getWxuid();
                    pushMsgTags = room.getPushMsgTags();
                    Long uploadUserId = room.getUploadUserId();
                    Optional<BiliBiliUser> userOptional = userRepository.findById(uploadUserId);
                    if (userOptional.isPresent()) {
                        user = userOptional.get();
                        if (!(user.isLogin() && user.isEnable())) {
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

                // 普通弹幕/高级弹幕/SC 全关闭：直接标记“评论已处理”，避免状态卡住
                if (!sendDmEnabled && !sendScEnabled) {
                    history.setSendReply(true);
                    historyRepository.save(history);
                    log.info("[BLR] {}", LogKvs.event("LiveMsgSendSync.AllDmDisabled.Archive")
                            .add("roomId", history.getRoomId())
                            .add("historyId", history.getId())
                            .addIfNotBlank("bvid", history.getBvId())
                            .addIfNotBlank("title", history.getTitle()));
                    continue;
                }

                // 未开启 SC/上舰发送时，不发送“SC/上舰列表评论”，直接认为评论阶段已完成
                if (!sendScEnabled) {
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
                    // 如果切换公开失败，直接跳过当前视频的后续所有操作（评论、弹幕、切回私有）
                    continue;
                }

                List<String> replyLines = new ArrayList<>();
                boolean hasScReply = false;
                boolean hasGuardReply = false;
                boolean hasOtherHighLevelReply = false;
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
                        replyLines.add(part.getPage() + "#" + format.format(new Date(liveMsg.getSendTime()))
                                + "  " + contextText + "\n");
                    }
                }
                int giftReplyCount = 0;
                if (room != null && Boolean.TRUE.equals(room.getSendGiftReply())) {
                    giftReplyCount = appendGiftReplyLines(replyLines, history, parts, room, format);
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
                        BiliReplyResponse replyResponse = BiliApi.sendVideoReply(user, reply);
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
                                if (PushNotifyClient.canSend(room, wxuid, pushMsgTags, "视频评论")) {
                                    Message message = new Message();
                                    message.setAppToken(wxToken);
                                    message.setContentType(Message.CONTENT_TYPE_TEXT);
                                    message.setContent(WX_MSG_FORMAT.formatted(room.getUname(), history.getTitle(), history.getBvId(),
                                            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")),
                                            reply.getMessage(), "发送成功", user.getUname() + ""));
                                    message.setUid(wxuid);
                                    PushNotifyClient.sendParallel(room, message);
                                }
                            } catch (Exception ignored) {

                            }
                        } else {
                            log.error("[BLR] {}", LogKvs.event("LiveMsgSendSync.Reply.Send.Failed")
                                    .addIfNotBlank("bvid", history.getBvId())
                                    .addIfNotBlank("avId", reply.getOid())
                                    .add("code", replyResponse.getCode())
                                    .addIfNotBlank("message", replyResponse.getMessage()));
                            throw new RuntimeException("发送评论失败: " + replyResponse.getMessage());
                        }
                        //等待一段时间在发送
                        Thread.sleep(5000L);
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
                        if (PushNotifyClient.canSend(room, wxuid, pushMsgTags, "视频评论")) {
                            Message message = new Message();
                            message.setAppToken(wxToken);
                            message.setContentType(Message.CONTENT_TYPE_TEXT);
                            message.setContent(WX_MSG_FORMAT.formatted(room.getUname(), history.getTitle(), history.getBvId(),
                                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")),
                                    JSON.toJSONString(replies), "发送失败", e.getMessage()));
                            message.setUid(wxuid);
                            PushNotifyClient.sendParallel(room, message);
                        }
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

        final long normalDanmakuIntervalMs = systemConfigService.getNormalDanmakuIntervalMs();
        final long highLevelDanmakuIntervalMs = systemConfigService.getHighLevelDanmakuIntervalMs();

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
                        String wxuid = room.getWxuid();
                        String pushMsgTags = room.getPushMsgTags();
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
                                    if (PushNotifyClient.canSend(room, wxuid, pushMsgTags, "高级弹幕")) {
                                        Message message = new Message();
                                        message.setAppToken(wxToken);
                                        message.setContentType(Message.CONTENT_TYPE_TEXT);
                                        message.setContent(WX_MSG_FORMAT.formatted(room.getUname(), part.getTitle(), msg.getBvid(),
                                                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")),
                                                msg.getContext(), "发送失败", user.getUname() + "-->code: " + code));
                                        message.setUid(wxuid);
                                        PushNotifyClient.sendParallel(room, message);
                                    }
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
                                    Thread.sleep(highLevelDanmakuIntervalMs);
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
                            Thread.sleep(normalDanmakuIntervalMs);
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

    private int appendGiftReplyLines(List<String> replyLines, RecordHistory history, List<RecordHistoryPart> parts, RecordRoom room, DateFormat format) {
        BigDecimal minPrice = room.getGiftReplyMinPriceCny() == null ? BigDecimal.ZERO : room.getGiftReplyMinPriceCny();
        if (minPrice.compareTo(BigDecimal.ZERO) < 0) {
            minPrice = BigDecimal.ZERO;
        }
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
            return 0;
        }

        List<Integer> giftIds = giftEvents.stream()
                .map(RoomLiveEvent::getGiftId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Integer, RoomLiveGiftCatalog> catalogByGiftId = new HashMap<>();
        if (!giftIds.isEmpty()) {
            String roomId = hasText(history.getRoomId()) ? history.getRoomId() : room.getRoomId();
            for (RoomLiveGiftCatalog catalog : roomLiveGiftCatalogRepository.findByRoomIdAndGiftIdIn(roomId, giftIds)) {
                if (catalog.getGiftId() != null) {
                    catalogByGiftId.put(catalog.getGiftId(), catalog);
                }
            }
        }
        int appended = 0;
        for (RoomLiveEvent event : giftEvents) {
            RecordHistoryPart part = partById.get(event.getPartId());
            if (part == null) {
                continue;
            }
            RoomLiveGiftCatalog catalog = event.getGiftId() == null ? null : catalogByGiftId.get(event.getGiftId());
            BigDecimal unitPrice = resolveGiftUnitPriceCny(event, catalog);
            if (unitPrice == null || unitPrice.compareTo(minPrice) < 0) {
                continue;
            }
            long count = event.getGiftCount() == null || event.getGiftCount() <= 0 ? 1L : event.getGiftCount();
            BigDecimal totalPrice = resolveGiftTotalPriceCny(event, unitPrice, count);
            String uname = hasText(event.getUname()) ? event.getUname() : "未知用户";
            String giftName = hasText(event.getGiftName())
                    ? event.getGiftName()
                    : (catalog != null && hasText(catalog.getGiftName()) ? catalog.getGiftName() : "未知礼物");
            long sendTime = event.getSendTime() == null ? 0L : event.getSendTime();
            replyLines.add(part.getPage() + "#" + format.format(new Date(sendTime))
                    + "  礼物 [💎" + formatMoney(unitPrice) + "] "
                    + uname + ": " + giftName + " x" + count
                    + "，总计💎" + formatMoney(totalPrice) + "\n");
            appended++;
        }
        return appended;
    }

    private BigDecimal resolveGiftUnitPriceCny(RoomLiveEvent event, RoomLiveGiftCatalog catalog) {
        Long unitCoin = event.getGiftPriceCoin();
        long count = event.getGiftCount() == null || event.getGiftCount() <= 0 ? 1L : event.getGiftCount();
        if ((unitCoin == null || unitCoin <= 0) && event.getGiftTotalCoin() != null && event.getGiftTotalCoin() > 0) {
            unitCoin = event.getGiftTotalCoin() / count;
        }
        if (unitCoin != null && unitCoin > 0) {
            return roomLiveGiftCatalogService.toCny(unitCoin);
        }
        if (catalog != null && catalog.getPriceCny() != null && catalog.getPriceCny().compareTo(BigDecimal.ZERO) > 0) {
            return catalog.getPriceCny();
        }
        if (catalog != null && catalog.getPriceCoin() != null && catalog.getPriceCoin() > 0) {
            return roomLiveGiftCatalogService.toCny(catalog.getPriceCoin());
        }
        return null;
    }

    private BigDecimal resolveGiftTotalPriceCny(RoomLiveEvent event, BigDecimal unitPrice, long count) {
        if (event.getGiftTotalCoin() != null && event.getGiftTotalCoin() > 0) {
            return roomLiveGiftCatalogService.toCny(event.getGiftTotalCoin());
        }
        return unitPrice.multiply(BigDecimal.valueOf(count));
    }

    private String formatMoney(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

}


