package top.sshh.bililiverecoder.job;

import com.alibaba.fastjson.JSON;
import com.zjiecode.wxpusher.client.WxPusher;
import com.zjiecode.wxpusher.client.bean.Message;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.entity.data.*;
import top.sshh.bililiverecoder.repo.*;
import top.sshh.bililiverecoder.service.impl.LiveMsgService;
import top.sshh.bililiverecoder.service.impl.RecordBiliPublishService;
import top.sshh.bililiverecoder.util.BiliApi;
import top.sshh.bililiverecoder.util.LogKvs;

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

    private static final Lock lock = new ReentrantLock();

    @Scheduled(fixedDelay = 60000, initialDelay = 5000)
    public void sndMsgProcess() {
        log.debug("[BLR] {}", LogKvs.event("LiveMsgSendSync.Start"));
        long startTime = System.currentTimeMillis();
        List<RecordHistory> historyList = historyRepository.findByPublishIsTrueAndCodeIn(Arrays.asList(0, -50));
        if (CollectionUtils.isEmpty(historyList)) {
            return;
        }
        List<BiliBiliUser> allUser = userRepository.findByLoginIsTrueAndEnableIsTrue();
        if (CollectionUtils.isEmpty(allUser)) {
            return;
        }
        DateFormat format = new SimpleDateFormat("HH:mm:ss");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        List<RecordHistoryPart> partList = new ArrayList<>();
        for (RecordHistory history : historyList) {
            List<RecordHistoryPart> parts = partRepository.findByHistoryIdAndCidIsNotNullOrderByPageAsc(history.getId());
            //如果没有发送评论
            if (!history.isSendReply()) {
                RecordRoom room = roomRepository.findByRoomId(history.getRoomId());
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
                            continue;
                        }
                    }
                }

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

                List<BiliReply> replies = new ArrayList<>();
                StringBuilder context = new StringBuilder();
                context.append("SC和上舰列表\n");
                for (RecordHistoryPart part : parts) {
                    List<LiveMsg> msgList = msgRepository.findByPartIdAndPoolAndCidNotNullOrderBySendTimeAsc(part.getId(), 1);
                    for (LiveMsg liveMsg : msgList) {
                        StringBuilder builder = new StringBuilder();
                        builder.append(part.getPage()).append('#').append(format.format(new Date(liveMsg.getSendTime()))).append("  ").append(liveMsg.getContext()).append('\n');
                        //发送限制为1000
                        if (context.length() + builder.length() > 1000) {
                            BiliReply reply = new BiliReply();
                            reply.setType("1");
                            reply.setOid(history.getAvId());
                            reply.setAction("1");
                            reply.setMessage(context.toString());
                            replies.add(reply);
                            //重置
                            context = new StringBuilder();
                            context.append("SC和上舰列表\n");
                        }
                        context.append(builder);
                    }
                }
                if (context.length() > 20) {
                    BiliReply reply = new BiliReply();
                    reply.setType("1");
                    reply.setOid(history.getAvId());
                    reply.setAction("1");
                    reply.setMessage(context.toString());
                    replies.add(reply);
                }
                if (!replies.isEmpty()) {
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
                                if (StringUtils.isNotBlank(wxuid) && StringUtils.isNotBlank(pushMsgTags) && pushMsgTags.contains("视频评论")) {
                                    Message message = new Message();
                                    message.setAppToken(wxToken);
                                    message.setContentType(Message.CONTENT_TYPE_TEXT);
                                    message.setContent(WX_MSG_FORMAT.formatted(room.getUname(), history.getTitle(), history.getBvId(),
                                            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")),
                                            reply.getMessage(), "发送成功", user.getUname() + ""));
                                    message.setUid(wxuid);
                                    WxPusher.send(message);
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
                        if (StringUtils.isNotBlank(wxuid) && StringUtils.isNotBlank(pushMsgTags) && pushMsgTags.contains("视频评论")) {
                            Message message = new Message();
                            message.setAppToken(wxToken);
                            message.setContentType(Message.CONTENT_TYPE_TEXT);
                            message.setContent(WX_MSG_FORMAT.formatted(room.getUname(), history.getTitle(), history.getBvId(),
                                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")),
                                    JSON.toJSONString(replies), "发送失败", e.getMessage()));
                            message.setUid(wxuid);
                            WxPusher.send(message);
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
            // 只有非仅自己可见(-50)的稿件，才加入弹幕发送队列
            if (history.getCode() != -50) {
                partList.addAll(parts);
            }
        }
        if (CollectionUtils.isEmpty(partList)) {
            return;
        }
        //普通弹幕
        List<LiveMsg> msgAllList = new ArrayList<>();
        List<RecordRoom> roomList = roomRepository.findBySendDmIsTrue();
        List<String> roomIds = roomList.stream().map(RecordRoom::getRoomId).toList();
        List<Long> partIds = partList.stream().map(RecordHistoryPart::getId).toList();
        // 高级弹幕
        List<LiveMsg> allHighLevelMsg = msgRepository.findByPoolAndCodeAndPartIdInOrderBySendTimeAsc(1, -1, partIds);

        // 房间设置是否发送弹幕
        partIds = partList.stream().filter(p -> roomIds.contains(p.getRoomId())).map(RecordHistoryPart::getId).toList();
        //一个用户一小时发送150条弹幕
        Pageable pageable = Pageable.ofSize(500);
        Page<LiveMsg> msgPage = msgRepository.findByPoolAndCodeAndPartIdInOrderBySendTimeAsc(0, -1, partIds, pageable);
        if (!msgPage.isEmpty()) {
            msgAllList.addAll(msgPage.get().toList());
        }
        if (msgAllList.isEmpty() && allHighLevelMsg.isEmpty()) {
            return;
        }


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
                Optional<RecordHistoryPart> partOptional = partRepository.findById(partId);
                if (partOptional.isPresent()) {
                    RecordHistoryPart part = partOptional.get();
                    String roomId = part.getRoomId();
                    RecordRoom room = roomRepository.findByRoomId(roomId);
                    if (room != null) {
                        String wxuid = room.getWxuid();
                        String pushMsgTags = room.getPushMsgTags();
                        Long uploadUserId = room.getUploadUserId();
                        Optional<BiliBiliUser> userOptional = userRepository.findById(uploadUserId);
                        if (userOptional.isPresent()) {
                            BiliBiliUser user = userOptional.get();
                            if (!(user.isLogin() && user.isEnable())) {
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
                                    if (StringUtils.isNotBlank(wxuid) && StringUtils.isNotBlank(pushMsgTags) && pushMsgTags.contains("高级弹幕")) {
                                        Message message = new Message();
                                        message.setAppToken(wxToken);
                                        message.setContentType(Message.CONTENT_TYPE_TEXT);
                                        message.setContent(WX_MSG_FORMAT.formatted(room.getUname(), part.getTitle(), msg.getBvid(),
                                                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")),
                                                msg.getContext(), "发送失败", user.getUname() + "-->code: " + code));
                                        message.setUid(wxuid);
                                        WxPusher.send(message);
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
                                    Thread.sleep(25 * 1000L);
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
                            Thread.sleep(25 * 1000L);
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

    }

    private VideoEditUploadDto.DescDto generateDesc(String template, Map<String, Object> map) {
        List<DescV2Dto> resultList = new ArrayList<>();
        StringBuilder desc = new StringBuilder();
        List<String> stringList = RecordBiliPublishService.splitTemplateByUid(template);
        for (String s : stringList) {
            if (s.startsWith("${@")) {
                long uid = Long.parseLong(s.substring(3, s.length() - 1));
                try {
                    BiliApi.BiliUserCardResponseDto userCard = BiliApi.getUserCard(uid);
                    if (userCard != null && userCard.getCode() == 0) {
                        //必须带个空格，否则报错简介过长
                        desc.append("@").append(userCard.getCard().getName() + " ");
                        DescV2Dto descV2Dto = new DescV2Dto();
                        descV2Dto.setBiz_id(String.valueOf(uid));
                        descV2Dto.setRaw_text(userCard.getCard().getName());
                        descV2Dto.setType(2);
                        resultList.add(descV2Dto);
                    }

                } catch (Exception e) {
                    log.error("[BLR] {}", LogKvs.event("Template.AtUser.Failed")
                            .add("uid", uid)
                            .addIfNotBlank("err", e.getMessage())
                            .add("ex", e.getClass().getSimpleName()), e);
                }
            } else {
                s = s.replace("${uname}", map.get("${uname}") != null ? map.get("${uname}").toString() : "")
                        .replace("${title}", map.get("${title}") != null ? map.get("${title}").toString() : "")
                        .replace("${index}", map.get("${index}") != null ? map.get("${index}").toString() : "")
                        .replace("${areaName}", map.get("${areaName}") != null ? map.get("${areaName}").toString() : "")
                        .replace("${roomId}", map.get("${roomId}") != null ? map.get("${roomId}").toString() : "");
                if (s.contains("${")) {
                    try {
                        LocalDateTime localDateTime = (LocalDateTime)map.get("date");
                        String substring = s.substring(s.indexOf("${"));
                        String date = substring.substring(0, substring.indexOf("}") + 1);
                        String format = localDateTime.format(DateTimeFormatter.ofPattern(date.substring(2, date.length() - 1)));
                        s = s.replace(date, format);
                    } catch (Exception e) {
                        log.error("[BLR] {}", LogKvs.event("Template.DateFormat.Failed")
                                .addIfNotBlank("template", template));
                    }
                }
                s = s.replace(",,", ",");

                DescV2Dto descV2Dto = new DescV2Dto();
                descV2Dto.setRaw_text(s);
                descV2Dto.setType(1);
                resultList.add(descV2Dto);
                desc.append(s);
            }

        }
        return new VideoEditUploadDto.DescDto(desc.toString(), resultList);
    }
}
