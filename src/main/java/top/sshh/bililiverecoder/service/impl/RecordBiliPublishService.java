package top.sshh.bililiverecoder.service.impl;

import com.alibaba.fastjson.JSON;
import com.jayway.jsonpath.JsonPath;
import com.zjiecode.wxpusher.client.WxPusher;
import com.zjiecode.wxpusher.client.bean.Message;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.entity.data.*;
import top.sshh.bililiverecoder.repo.*;
import top.sshh.bililiverecoder.service.CaptchaService;
import top.sshh.bililiverecoder.service.UploadServiceFactory;
import top.sshh.bililiverecoder.util.BiliApi;
import top.sshh.bililiverecoder.util.TaskUtil;
import top.sshh.bililiverecoder.util.LogKvs;
import top.sshh.bililiverecoder.util.bili.Cookie;
import top.sshh.bililiverecoder.util.bili.WebCookie;
import top.sshh.bililiverecoder.util.bili.user.UserMy;
import top.sshh.bililiverecoder.util.bili.user.UserMyRootBean;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
public class RecordBiliPublishService {

    @Value("${record.work-path}")
    private String workPath;

    @Value("${server.port:8080}")
    private String serverPort;

    private static final java.util.concurrent.ConcurrentHashMap<Long, LocalDateTime> suspendMap = new java.util.concurrent.ConcurrentHashMap<>();

    private static final int UPLOAD_RETRY_GIVE_UP = 9999;

    private static final String WX_MSG_FORMAT = """
            投稿结果: %s
            收到主播%s投稿事件
            房间名: %s
            时间: %s
            原因: %s
            """;

    @Value("${record.wx-push-token}")
    private String wxToken;

    @PostConstruct
    public void initWorkPath() {
        workPath = workPath.replaceAll("\\\\\\\\", "\\\\");
        workPath = workPath.replace("\\", "/");
    }

    @Autowired
    private BiliUserRepository biliUserRepository;
    @Autowired
    private RecordHistoryPartRepository partRepository;
    @Autowired
    private RecordHistoryRepository historyRepository;
    @Autowired
    private RecordRoomRepository roomRepository;
    @Autowired
    private UploadServiceFactory uploadServiceFactory;
    @Autowired
    private HighEnergyCutPublishService highEnergyCutPublishService;
    @Autowired
    private LiveMsgService liveMsgService;
    @Autowired
    private LiveMsgRepository msgRepository;
    @Autowired
    private CaptchaService captchaService;

    @Async
    public void asyncPublishRecordHistory(RecordHistory history) {
        this.publishRecordHistory(history);
    }

    // 方法用于按"${@数字}"分割字符串
    public static List<String> splitTemplateByUid(String template) {
        List<String> parts = new ArrayList<>();
        Pattern pattern = Pattern.compile("\\$\\{[@]\\d+\\}");
        Matcher matcher = pattern.matcher(template);
        int lastMatchEnd = 0;

        while (matcher.find()) {
            // 如果从上一个匹配结束位置到当前匹配开始之间有内容，则添加这部分内容
            if (lastMatchEnd < matcher.start()) {
                parts.add(template.substring(lastMatchEnd, matcher.start()));
            }

            // 添加"${@数字}"本身
            parts.add(matcher.group());

            // 更新上一个匹配结束位置
            lastMatchEnd = matcher.end();
        }

        // 如果还有剩余的部分，添加到最后
        if (lastMatchEnd < template.length()) {
            parts.add(template.substring(lastMatchEnd));
        }

        return parts;
    }

    @Async
    public void asyncRepublishRecordHistory(RecordHistory history) {

        RecordRoom room = roomRepository.findByRoomId(history.getRoomId());
        String wxuid = room.getWxuid();
        String pushMsgTags = room.getPushMsgTags();
        Optional<BiliBiliUser> userOptional = biliUserRepository.findById(room.getUploadUserId());
        if (!userOptional.isPresent()) {
            log.error("[BLR] {}", LogKvs.event("Publish.UploadUserMissing")
                    .add("roomId", room.getRoomId())
                    .add("uname", room.getUname())
                    .add("uploadUserId", room.getUploadUserId())
                    .add("historyId", history.getId()));
        }
        BiliBiliUser biliBiliUser = userOptional.get();
        if (!biliBiliUser.isLogin()) {
            log.error("[BLR] {}", LogKvs.event("Publish.LoginInvalid")
                    .add("roomId", room.getRoomId())
                    .add("uname", room.getUname())
                    .add("uploadUserId", room.getUploadUserId())
                    .add("historyId", history.getId()));
        }

        // 发布任务入队列
        TaskUtil.publishTask.put(history.getId(), Thread.currentThread());
        StringBuilder errMsg = new StringBuilder("\n");
        try {
            List<RecordHistoryPart> uploadParts = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());
            BiliVideoPartInfoResponse videoPartInfo = BiliApi.getVideoPartInfo(biliBiliUser, history.getBvId());
            Map<String, BiliVideoPartInfoResponse.Video> videoMap = videoPartInfo.getData().getVideos().stream().collect(Collectors.toMap(BiliVideoPartInfoResponse.Video::getTitle, Function.identity()));
            for (RecordHistoryPart uploadPart : uploadParts) {
                // 正常分p不需要在重复上传
                BiliVideoPartInfoResponse.Video video = videoMap.get(uploadPart.getTitle());
                // video.getFailCode() == 14 && video.getXcodeState() == 1 时间戳跳变
                if (video == null || (video.getFailCode() == 9 && video.getXcodeState() == 3) || (video.getFailCode() == 14 && video.getXcodeState() == 1) || (video.getFailCode() == 0 && video.getXcodeState() == 2)) {
                    boolean isTimestampError = video != null && video.getFailCode() == 14 && video.getXcodeState() == 1;
                    if (video == null) {
                        errMsg.append(uploadPart.getTitle()).append("   视频不存在\n");
                    } else if (video.getXcodeState() == 2) {
                        errMsg.append(uploadPart.getTitle()).append("   转码中\n");
                    } else if (isTimestampError) {
                        // 时间戳跳变错误 (code 21588) - 文件损坏，放弃上传
                        log.error("[BLR] {}", LogKvs.event("Publish.TimestampJump.GiveUpPart")
                                .add("historyId", history.getId())
                                .add("partId", uploadPart.getId())
                                .add("partTitle", uploadPart.getTitle())
                                .add("failCode", 14)
                                .add("xcodeState", 1));
                        errMsg.append(uploadPart.getTitle()).append("   转码失败(时间戳跳变-文件损坏已放弃)\n");
                    } else {
                        errMsg.append(uploadPart.getTitle()).append("   转码失败\n");
                    }
                    uploadPart.setUpload(false);
                    uploadPart.setCid(null);
                    uploadPart.setFileName(null);
                    if (isTimestampError) {
                        uploadPart.setUploadRetryCount(UPLOAD_RETRY_GIVE_UP);
                        uploadPart.setDeleteFailType("TIMESTAMP_JUMP");
                        uploadPart.setDeleteFailReason("分P转码失败(时间戳跳变-文件损坏)，已放弃重新上传");
                    }
                    uploadPart = partRepository.save(uploadPart);
                    // 时间戳跳变错误表示文件损坏，不再重试上传
                    if (isTimestampError) {
                        log.info("[BLR] {}", LogKvs.event("Publish.TimestampJump.SkipReupload")
                                .add("historyId", history.getId())
                                .add("partId", uploadPart.getId())
                                .add("partTitle", uploadPart.getTitle())
                                .add("retry", UPLOAD_RETRY_GIVE_UP));
                        continue;
                    }
                    String filePath = uploadPart.getFilePath().intern();
                    File file = new File(filePath);
                    if (file.exists()) {
                        synchronized (filePath.intern()) {
                            log.info("[BLR] {}", LogKvs.event("Publish.PartUploadLock.Acquired")
                                    .add("historyId", history.getId())
                                    .add("partId", uploadPart.getId()));
                            //再次检查是否上传完成
                            Optional<RecordHistoryPart> partOptional = partRepository.findById(uploadPart.getId());
                            if (partOptional.isPresent()) {
                                RecordHistoryPart part = partOptional.get();
                                if (!part.isUpload()) {
                                    log.info("[BLR] {}", LogKvs.event("Publish.Part.NotUploaded")
                                            .add("historyId", history.getId())
                                            .add("partId", uploadPart.getId()));
                                    uploadServiceFactory.getUploadService(room.getLine()).upload(uploadPart);
                                }
                            }

                        }
                    }
                }
            }
            uploadParts = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());
            userOptional = biliUserRepository.findById(room.getUploadUserId());
            if (!userOptional.isPresent()) {
                log.error("[BLR] {}", LogKvs.event("Publish.UploadUserMissing")
                        .add("roomId", room.getRoomId())
                        .add("uname", room.getUname())
                        .add("uploadUserId", room.getUploadUserId())
                        .add("historyId", history.getId()));
            }
            biliBiliUser = userOptional.get();
            Map<String, Object> map = new HashMap<>();
            LocalDateTime startTime = history.getStartTime();
            map.put("date", startTime);

            String uname = room.getUname();
            if (uname == null) {
                map.put("${uname}", "");
            } else {
                map.put("${uname}", uname);
            }
            String title = StringUtils.isNotBlank(history.getTitle()) ? history.getTitle() : "直播录像";
            map.put("${title}", title);
            map.put("${roomId}", room.getRoomId());
            map.put("${areaName}", "");
            List<SingleVideoDto> dtos = new ArrayList<>();
            for (int i = 0; i < uploadParts.size(); i++) {
                RecordHistoryPart uploadPart = uploadParts.get(i);
                SingleVideoDto dto = new SingleVideoDto();
                title = StringUtils.isNotBlank(uploadPart.getLiveTitle()) ? uploadPart.getLiveTitle() : "直播录像";
                map.put("${title}", title);
                map.put("date", uploadPart.getStartTime());
                map.put("${index}", Integer.valueOf(i + 1));
                map.put("${areaName}", uploadPart.getAreaName());
                String filePath = uploadPart.getFilePath();
                String fileName = filePath.substring(filePath.lastIndexOf("/") + 1, filePath.lastIndexOf("."));
                map.put("${fileName}", fileName);
                dto.setTitle(this.template(room.getPartTitleTemplate(), map).getDesc());
                //同步标题
                uploadPart.setTitle(this.template(room.getPartTitleTemplate(), map).getDesc());
                uploadPart = partRepository.save(uploadPart);
                dto.setDesc("");
                dto.setFilename(uploadPart.getFileName());
                if (uploadPart.getCid() != null && uploadPart.getCid() > 0) {
                    dto.setCid(uploadPart.getCid());
                }
                dtos.add(dto);
            }
            VideoEditUploadDto videoUploadDto = new VideoEditUploadDto();

            map.put("date", startTime);
            videoUploadDto.setTid(room.getTid());
            videoUploadDto.setCover(history.getCoverUrl());
            videoUploadDto.setCopyright(room.getCopyright());
            videoUploadDto.setTitle(this.template(room.getTitleTemplate(), map).getDesc());
            if (videoUploadDto.getCopyright() == 2) {
                videoUploadDto.setSource(this.template(videoUploadDto.getSource(), map).getDesc());
            }
            videoUploadDto.setDesc(this.template(room.getDescTemplate(), map).getDesc());
            videoUploadDto.setDesc_v2(this.template(room.getDescTemplate(), map).getDescV2Dtos());
            videoUploadDto.setDynamic(this.template(room.getDescTemplate(), map).getDesc());
            videoUploadDto.setDynamic_v2(this.template(room.getDescTemplate(), map).getDescV2Dtos());
            videoUploadDto.setVideos(dtos);
            videoUploadDto.setTag(this.template(room.getTags(), map).getDesc());
            videoUploadDto.setIs_only_self(room.getIsOnlySelf());
            videoUploadDto.setAid(Long.parseLong(history.getAvId()));
            String republishRes = BiliApi.editPublish(biliBiliUser, videoUploadDto);
            Integer republishCode = null;
            String republishMsg = null;
            try {
                republishCode = JsonPath.read(republishRes, "code");
                republishMsg = JsonPath.read(republishRes, "message");
            } catch (Exception ignore) {
            }
            log.info("[BLR] {}", LogKvs.event("Publish.Republish.Response")
                    .add("roomId", room.getRoomId())
                    .add("uname", room.getUname())
                    .add("historyId", history.getId())
                    .add("code", republishCode)
                    .addIfNotBlank("message", republishMsg));

            // 发布任务出队列
            TaskUtil.publishTask.remove(history.getId());
            Integer code = JsonPath.read(republishRes, "code");
            if (code == 0) {
                if (StringUtils.isNotBlank(wxuid) && StringUtils.isNotBlank(pushMsgTags) && pushMsgTags.contains("视频投稿")) {
                    Message message = new Message();
                    message.setAppToken(wxToken);
                    message.setContentType(Message.CONTENT_TYPE_TEXT);
                    message.setContent(WX_MSG_FORMAT.formatted("重新投稿成功", room.getUname(), room.getTitle(),
                            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")), errMsg));
                    message.setUid(wxuid);
                    WxPusher.send(message);
                }
            } else {
                if (StringUtils.isNotBlank(wxuid) && StringUtils.isNotBlank(pushMsgTags) && pushMsgTags.contains("视频投稿")) {
                    Message message = new Message();
                    message.setAppToken(wxToken);
                    message.setContentType(Message.CONTENT_TYPE_TEXT);
                    message.setContent(WX_MSG_FORMAT.formatted("重新投稿失败", room.getUname(), room.getTitle(),
                            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")),
                            JsonPath.read(republishRes, "message")));
                    message.setUid(wxuid);
                    WxPusher.send(message);
                }
            }
        } finally {
            TaskUtil.publishTask.remove(history.getId());
        }


    }

    public boolean publishRecordHistory(RecordHistory history) {
        if (suspendMap.containsKey(history.getId())) {
            if (suspendMap.get(history.getId()).isAfter(LocalDateTime.now())) {
                log.info("[BLR] {}", LogKvs.event("Publish.Task.SuspendedSkip")
                        .add("historyId", history.getId())
                        .add("roomId", history.getRoomId())
                        .addIfNotBlank("title", history.getTitle())
                        .add("resumeAt", suspendMap.get(history.getId())));
                return false;
            } else {
                suspendMap.remove(history.getId());
            }
        }
        if (history.isPublish()) {
            log.warn("[BLR] {}", LogKvs.event("Publish.History.AlreadyPublished")
                    .add("historyId", history.getId())
                    .add("roomId", history.getRoomId())
                    .addIfNotBlank("title", history.getTitle())
                    .addIfNotBlank("bvid", history.getBvId())
                    .addIfNotBlank("aid", history.getAvId()));
            return false;
        }
        if (history.getUploadRetryCount() > 10) {
            log.error("[BLR] {}", LogKvs.event("Publish.Retry.GiveUp")
                    .add("historyId", history.getId())
                    .add("roomId", history.getRoomId())
                    .addIfNotBlank("title", history.getTitle())
                    .add("retryCount", history.getUploadRetryCount()));
            return false;
        }
        Thread publishThread = TaskUtil.publishTask.get(history.getId());
        if (publishThread != null) {
            //正在发布，直接退出
            log.warn("[BLR] {}", LogKvs.event("Publish.Task.AlreadyRunning")
                    .add("historyId", history.getId())
                    .add("roomId", history.getRoomId())
                    .addIfNotBlank("title", history.getTitle()));
            return false;
        }
        publishThread = TaskUtil.publishTask.get(history.getId());
        if (publishThread != null) {
            //正在发布，直接退出
            log.warn("[BLR] {}", LogKvs.event("Publish.Task.AlreadyRunning")
                    .add("historyId", history.getId())
                    .add("roomId", history.getRoomId())
                    .addIfNotBlank("title", history.getTitle()));
            return false;
        }
        // 发布任务入队列
        TaskUtil.publishTask.put(history.getId(), Thread.currentThread());
        try {

            RecordRoom room = roomRepository.findByRoomId(history.getRoomId());
            String wxuid = room.getWxuid();
            String pushMsgTags = room.getPushMsgTags();
                log.info("[BLR] {}", LogKvs.event("Publish.Start")
                    .add("roomId", room.getRoomId())
                    .add("uname", room.getUname())
                    .add("historyId", history.getId())
                    .addIfNotBlank("title", history.getTitle()));

            if (room.getTid() == null) {
                //没有设置分区，直接取消上传
                TaskUtil.publishTask.remove(history.getId());
                log.error("[BLR] {}", LogKvs.event("Publish.Room.TidMissing")
                        .add("roomId", room.getRoomId())
                        .add("uname", room.getUname())
                        .add("historyId", history.getId())
                        .addIfNotBlank("title", history.getTitle()));
                return false;
            }
            List<RecordHistoryPart> uploadParts = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());
            if (uploadParts.size() == 0) {
                log.warn("[BLR] {}", LogKvs.event("Publish.Parts.Empty")
                        .add("roomId", room.getRoomId())
                        .add("uname", room.getUname())
                        .add("historyId", history.getId())
                        .addIfNotBlank("title", history.getTitle()));
                historyRepository.delete(history);
                TaskUtil.publishTask.remove(history.getId());
                return false;
            }
            if (uploadParts.size() > 100) {
                log.error("[BLR] {}", LogKvs.event("Publish.Parts.TooMany.Split")
                        .add("roomId", room.getRoomId())
                        .add("uname", room.getUname())
                        .add("historyId", history.getId())
                        .add("partCount", uploadParts.size()));
                //更新唯一键,更新录制状态
                String eventId = history.getEventId();
                String sessionId = history.getSessionId();
                history.setEventId(eventId + 1);
                history.setSessionId(sessionId + 1);
                history = historyRepository.save(history);

                List<RecordHistoryPart> subList = uploadParts.subList(100, uploadParts.size());
                if (!subList.isEmpty()) {
                    //创建新的录制历史
                    history.setId(null);
                    history.setEventId(eventId + 2);
                    history.setSessionId(sessionId + 2);
                    history.setStartTime(subList.get(0).getStartTime());
                    history = historyRepository.save(history);
                    for (RecordHistoryPart part : subList) {
                        part.setHistoryId(history.getId());
                        partRepository.save(part);
                    }
                    if (StringUtils.isNotBlank(wxuid) && StringUtils.isNotBlank(pushMsgTags) && pushMsgTags.contains("视频投稿")) {
                        Message message = new Message();
                        message.setAppToken(wxToken);
                        message.setContentType(Message.CONTENT_TYPE_TEXT);
                        message.setContent(WX_MSG_FORMAT.formatted("投稿失败", room.getUname(), room.getTitle(),
                                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")),
                                "分p数量超过100,将在切割后再次投稿，当前分P数量为：" + uploadParts.size()));
                        message.setUid(wxuid);
                        WxPusher.send(message);
                    }
                    return false;
                }
            }
            LocalDateTime now = LocalDateTime.now();
            try {
            for (RecordHistoryPart uploadPart : uploadParts) {
                Optional<RecordHistoryPart> flsuhPartOptional = partRepository.findById(uploadPart.getId());
                uploadPart = flsuhPartOptional.get();
                String filePath = uploadPart.getFilePath().intern();
                File file = new File(filePath);
                //已经上传完成就跳过
                if (uploadPart.isUpload()) {
                    continue;
                }
                if (file.exists()) {
                    if (uploadPart.isRecording() && file.lastModified() > System.currentTimeMillis() - (10 * 60 * 1000)) {
                        log.error("[BLR] {}", LogKvs.event("Publish.Part.StillRecording")
                                .add("roomId", room.getRoomId())
                                .add("uname", room.getUname())
                                .add("historyId", history.getId())
                                .add("partId", uploadPart.getId())
                                .add("filePath", uploadPart.getFilePath())
                                .add("lastModified", file.lastModified()));
                        TaskUtil.publishTask.remove(history.getId());
                        return false;
                    } else {
                        uploadPart.setRecording(false);
                        if (uploadPart.getFileSize() == 0 || uploadPart.getDuration() == 0) {
                            uploadPart.setFileSize(file.length());
                            if (uploadPart.getDuration() == 0 && uploadPart.getStartTime() != null && uploadPart.getEndTime() != null) {
                                uploadPart.setDuration((int) java.time.Duration.between(uploadPart.getStartTime(), uploadPart.getEndTime()).getSeconds());
                            }
                        }
                        uploadPart = partRepository.save(uploadPart);
                        if (uploadPart.getEndTime().isAfter(now.plusMinutes(11L))) {
                            log.error("[BLR] {}", LogKvs.event("Publish.Part.EndTimeSuspicious")
                                    .add("roomId", room.getRoomId())
                                    .add("uname", room.getUname())
                                    .add("historyId", history.getId())
                                    .add("partId", uploadPart.getId())
                                    .add("startTime", uploadPart.getStartTime())
                                    .add("endTime", uploadPart.getEndTime())
                                    .add("now", now));
                            TaskUtil.publishTask.remove(history.getId());
                            return false;
                        }
                        if (uploadPart.getFileSize() < 1024 * 1024 * room.getFileSizeLimit()) {
                            log.info("[BLR] {}", LogKvs.event("Publish.Part.SkipBelowSizeLimit")
                                    .add("roomId", room.getRoomId())
                                    .add("uname", room.getUname())
                                    .add("historyId", history.getId())
                                    .add("partId", uploadPart.getId())
                                    .add("filePath", uploadPart.getFilePath())
                                    .add("fileSizeBytes", uploadPart.getFileSize())
                                    .add("limitMB", room.getFileSizeLimit()));
                            partRepository.delete(uploadPart);
                            continue;
                        }
                        if (uploadPart.getDuration() < room.getDurationLimit()) {
                            log.info("[BLR] {}", LogKvs.event("Publish.Part.SkipBelowDurationLimit")
                                    .add("roomId", room.getRoomId())
                                    .add("uname", room.getUname())
                                    .add("historyId", history.getId())
                                    .add("partId", uploadPart.getId())
                                    .add("filePath", uploadPart.getFilePath())
                                    .add("durationSec", uploadPart.getDuration())
                                    .add("limitSec", room.getDurationLimit()));
                            partRepository.delete(uploadPart);
                            continue;
                        }
                    }
                }
                Thread thread = TaskUtil.partUploadTask.get(uploadPart.getId());
                if (thread != null && thread != Thread.currentThread()) {
                    //等待线程上传完成
                    log.info("[BLR] {}", LogKvs.event("Publish.PartUploadLock.Wait")
                            .add("roomId", room.getRoomId())
                            .add("uname", room.getUname())
                            .add("historyId", history.getId())
                            .add("partId", uploadPart.getId())
                            .add("filePath", uploadPart.getFilePath()));
                    synchronized (filePath) {
                        TaskUtil.partUploadTask.remove(uploadPart.getId());
                        log.info("[BLR] {}", LogKvs.event("Publish.PartUploadLock.Acquired")
                                .add("roomId", room.getRoomId())
                                .add("uname", room.getUname())
                                .add("historyId", history.getId())
                                .add("partId", uploadPart.getId()));
                        //再次检查是否上传完成
                        Optional<RecordHistoryPart> partOptional = partRepository.findById(uploadPart.getId());
                        if (partOptional.isPresent()) {
                            RecordHistoryPart part = partOptional.get();
                            if (!part.isUpload()) {
                                log.info("[BLR] {}", LogKvs.event("Publish.Part.NotUploaded")
                                        .add("roomId", room.getRoomId())
                                        .add("uname", room.getUname())
                                        .add("historyId", history.getId())
                                        .add("partId", uploadPart.getId()));
                                uploadServiceFactory.getUploadService(room.getLine()).upload(uploadPart);
                                try {
                                    log.info("[BLR] {}", LogKvs.event("Publish.Part.Uploaded.WaitCooldown")
                                            .add("historyId", history.getId())
                                            .add("partId", uploadPart.getId())
                                            .add("waitMs", 20000));
                                    Thread.sleep(20000);
                                } catch (InterruptedException e) {
                                    log.warn("[BLR] {}", LogKvs.event("Publish.Part.Uploaded.WaitCooldownInterrupted")
                                            .add("historyId", history.getId())
                                            .add("partId", uploadPart.getId()), e);
                                }
                            }
                        }

                    }
                } else {
                    log.info("[BLR] {}", LogKvs.event("Publish.Part.NotUploaded")
                            .add("roomId", room.getRoomId())
                            .add("uname", room.getUname())
                            .add("historyId", history.getId())
                            .add("partId", uploadPart.getId()));
                    uploadServiceFactory.getUploadService(room.getLine()).upload(uploadPart);
                    try {
                        log.info("[BLR] {}", LogKvs.event("Publish.Part.Uploaded.WaitCooldown")
                                .add("historyId", history.getId())
                                .add("partId", uploadPart.getId())
                                .add("waitMs", 20000));
                        Thread.sleep(20000);
                    } catch (InterruptedException e) {
                        log.warn("[BLR] {}", LogKvs.event("Publish.Part.Uploaded.WaitCooldownInterrupted")
                                .add("historyId", history.getId())
                                .add("partId", uploadPart.getId()), e);
                    }
                }

            }
            } catch (RuntimeException e) {
                if (e.getMessage() != null && e.getMessage().startsWith("UPLOAD_GATEWAY_ERROR")) {
                    log.error("[BLR] {}", LogKvs.event("Publish.GatewayErrorPause")
                            .add("historyId", history.getId())
                            .add("roomId", history.getRoomId())
                            .addIfNotBlank("title", history.getTitle())
                            .add("pauseMinutes", 30)
                            .addIfNotBlank("err", e.getMessage()));
                    suspendMap.put(history.getId(), LocalDateTime.now().plusMinutes(30));
                    TaskUtil.publishTask.remove(history.getId());
                    return false;
                }
                throw e;
            }
            int preSize = uploadParts.size();
            //重新加载上传列表
            uploadParts = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());
            if (preSize != uploadParts.size()) {
                log.error("[BLR] {}", LogKvs.event("Publish.Parts.Changed")
                        .add("roomId", room.getRoomId())
                        .add("uname", room.getUname())
                        .add("historyId", history.getId())
                        .add("preSize", preSize)
                        .add("nowSize", uploadParts.size()));
                if (StringUtils.isNotBlank(wxuid) && StringUtils.isNotBlank(pushMsgTags) && pushMsgTags.contains("视频投稿")) {
                    Message message = new Message();
                    message.setAppToken(wxToken);
                    message.setContentType(Message.CONTENT_TYPE_TEXT);
                    message.setContent(WX_MSG_FORMAT.formatted("投稿失败", room.getUname(), room.getTitle(),
                            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")),
                            "分p数量发生变动"));
                    message.setUid(wxuid);
                    WxPusher.send(message);
                }
                return false;
            }
            long count = uploadParts.stream().filter(RecordHistoryPart::isUpload).count();
            if (count != uploadParts.size()) {
                //没有全部上传完成返回失败
                log.warn("[BLR] {}", LogKvs.event("Publish.Parts.NotAllUploaded")
                        .add("roomId", room.getRoomId())
                        .add("uname", room.getUname())
                        .add("historyId", history.getId())
                        .add("uploaded", count)
                        .add("total", uploadParts.size()));
                TaskUtil.publishTask.remove(history.getId());
                return false;
            }
            if (room.isUpload()) {
                if (room.getUploadUserId() == null) {
                    log.warn("[BLR] {}", LogKvs.event("Publish.UploadUserIdMissing")
                            .add("roomId", room.getRoomId())
                            .add("uname", room.getUname())
                            .add("historyId", history.getId()));
                    TaskUtil.publishTask.remove(history.getId());
                    return false;
                } else {
                    Optional<BiliBiliUser> userOptional = biliUserRepository.findById(room.getUploadUserId());
                    if (!userOptional.isPresent()) {
                        log.error("[BLR] {}", LogKvs.event("Publish.UploadUserMissing")
                                .add("roomId", room.getRoomId())
                                .add("uname", room.getUname())
                                .add("historyId", history.getId())
                                .add("uploadUserId", room.getUploadUserId()));
                        TaskUtil.publishTask.remove(history.getId());
                        return false;
                    }
                    BiliBiliUser biliBiliUser = userOptional.get();
                    if (!biliBiliUser.isLogin()) {
                        log.error("[BLR] {}", LogKvs.event("Publish.LoginInvalid")
                                .add("roomId", room.getRoomId())
                                .add("uname", room.getUname())
                                .add("historyId", history.getId())
                                .add("uploadUserId", room.getUploadUserId())
                                .addIfNotBlank("uploadUname", biliBiliUser.getUname()));
                        TaskUtil.publishTask.remove(history.getId());
                        return false;
                    }
                    // 检查是否已经过期，调用用户信息接口
                    WebCookie webCookie = Cookie.parse(biliBiliUser.getCookies());
                    UserMy userMy = new UserMy(webCookie);
                    UserMyRootBean myInfo = userMy.getPojo();
                    if (myInfo.getCode() == -101) {
                        biliBiliUser.setLogin(false);
                        biliBiliUser = biliUserRepository.save(biliBiliUser);
                        TaskUtil.publishTask.remove(history.getId());
                        if (StringUtils.isNotBlank(wxuid) && StringUtils.isNotBlank(pushMsgTags) && pushMsgTags.contains("视频投稿")) {
                            Message message = new Message();
                            message.setAppToken(wxToken);
                            message.setContentType(Message.CONTENT_TYPE_TEXT);
                            message.setContent(WX_MSG_FORMAT.formatted("投稿失败", room.getUname(), room.getTitle(),
                                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")),
                                    biliBiliUser.getUname() + "登录已过期，请重新登录"));
                            message.setUid(wxuid);
                            WxPusher.send(message);
                        }
                        throw new RuntimeException("登录已过期，请重新登录: " + biliBiliUser.getUname());
                    }


                    Map<String, Object> map = new HashMap<>();
                    LocalDateTime startTime = history.getStartTime();
                    map.put("date", startTime);

                    String uname = room.getUname();
                    if (uname == null) {
                        map.put("${uname}", "");
                    } else {
                        map.put("${uname}", uname);
                    }
                    String title = StringUtils.isNotBlank(history.getTitle()) ? history.getTitle() : "直播录像";
                    map.put("${title}", title);
                    map.put("${roomId}", room.getRoomId());
                    map.put("${areaName}", "");
                    List<SingleVideoDto> dtos = new ArrayList<>();
                    for (int i = 0; i < uploadParts.size(); i++) {
                        RecordHistoryPart uploadPart = uploadParts.get(i);
                        SingleVideoDto dto = new SingleVideoDto();
                        title = StringUtils.isNotBlank(uploadPart.getLiveTitle()) ? uploadPart.getLiveTitle() : "直播录像";
                        map.put("${title}", title);
                        map.put("date", uploadPart.getStartTime());
                        map.put("${index}", i + 1);
                        map.put("${areaName}", uploadPart.getAreaName());
                        String filePath = uploadPart.getFilePath();
                        String fileName = filePath.substring(filePath.lastIndexOf("/") + 1, filePath.lastIndexOf("."));
                        map.put("${fileName}", fileName);
                        dto.setTitle(this.template(room.getPartTitleTemplate(), map).getDesc());
                        //同步标题
                        uploadPart.setTitle(this.template(room.getPartTitleTemplate(), map).getDesc());
                        uploadPart = partRepository.save(uploadPart);
                        dto.setDesc("");
                        dto.setFilename(uploadPart.getFileName());
                        dtos.add(dto);
                    }
                    String coverUrl = room.getCoverUrl();
                    if ("live".equals(coverUrl)) {
                        try {
                            String filePath = uploadParts.get(uploadParts.size() - 1).getFilePath();
                            filePath = filePath.substring(0, filePath.lastIndexOf("."));
                            filePath += ".cover.jpg";
                            File cover = new File(filePath);
                            if (!cover.exists()) {
                                cover = new File(filePath.replaceAll(".cover.jpg", ".jpg"));
                            }
                            if (!cover.exists()) {
                                cover = new File(filePath.replaceAll(".cover.jpg", ".png"));
                            }
                            if (!cover.exists()) {
                                cover = new File(filePath.replaceAll(".cover.jpg", ".cover.png"));
                            }
                            byte[] bytes = new byte[(int)cover.length()];
                            FileInputStream inputStream = new FileInputStream(cover);
                            inputStream.read(bytes);
                            inputStream.close();
                            String uploadCoverResponse = BiliApi.uploadCover(biliBiliUser, cover.getName(), bytes);
                            log.info("[BLR] {}", LogKvs.event("Publish.Cover.Upload.Response")
                                    .add("roomId", room.getRoomId())
                                    .add("uname", room.getUname())
                                    .add("historyId", history.getId())
                                    .addIfNotBlank("coverFile", cover.getName()));
                            coverUrl = JsonPath.read(uploadCoverResponse, "data.url");
                            history.setCoverUrl(coverUrl);
                            history = historyRepository.save(history);
                        } catch (Exception e) {
                            log.warn("[BLR] {}", LogKvs.event("Publish.Cover.Upload.Failed")
                                    .add("roomId", room.getRoomId())
                                    .add("uname", room.getUname())
                                    .add("historyId", history.getId()), e);
                            coverUrl = "";
                        }
                    }
                    VideoUploadDto videoUploadDto = new VideoUploadDto();

                    map.put("date", startTime);
                    videoUploadDto.setTid(room.getTid());
                    videoUploadDto.setCover(coverUrl);
                    videoUploadDto.setCopyright(room.getCopyright());
                    videoUploadDto.setNo_disturbance(room.getNoDisturbance());
                    videoUploadDto.setIs_only_self(room.getIsOnlySelf());
                    videoUploadDto.setTitle(this.template(room.getTitleTemplate(), map).getDesc());
                    if (videoUploadDto.getCopyright() == 2) {
                        videoUploadDto.setSource(this.template(videoUploadDto.getSource(), map).getDesc());
                    }
                    videoUploadDto.setDesc(this.template(room.getDescTemplate(), map).getDesc());
                    videoUploadDto.setDesc_v2(this.template(room.getDescTemplate(), map).getDescV2Dtos());
                    if (StringUtils.isNotBlank(room.getDynamicTemplate())) {
                        videoUploadDto.setDynamic(this.template(room.getDynamicTemplate(), map).getDesc());
                        videoUploadDto.setDynamic_v2(this.template(room.getDynamicTemplate(), map).getDescV2Dtos());
                    }
                    videoUploadDto.setVideos(dtos);
                    videoUploadDto.setTag(this.template(room.getTags(), map).getDesc());
                    String uploadRes = null;
                    try {
                        uploadRes = BiliApi.webPublish(biliBiliUser, videoUploadDto);
                        log.info("[BLR] {}", LogKvs.event("Publish.WebPublish.Response")
                                .add("roomId", room.getRoomId())
                                .add("uname", room.getUname())
                                .add("historyId", history.getId())
                                .add("respLen", uploadRes == null ? 0 : uploadRes.length())
                                .add("containsCaptcha", uploadRes != null && uploadRes.contains("验证码")));
                        if (uploadRes.contains("验证码")) {
                            try {
                                String voucher = JsonPath.read(uploadRes, "data.v_voucher");
                                Map<String, Object> data = JsonPath.read(uploadRes, "data");
                                log.warn("[BLR] {}", LogKvs.event("Upload.Captcha.Required")
                                        .add("roomId", room.getRoomId())
                                        .add("uname", room.getUname())
                                        .add("historyId", history.getId())
                                        .addIfNotBlank("title", history.getTitle())
                                        .addUrl("captchaUrl", "http://localhost:" + serverPort + "/html/captcha.html"));
                                // 尝试从 data 中获取 geetest 相关信息，如果没有，前端会使用默认的 V4 captchaId
                                captchaService.setCaptchaRequired(voucher, history.getTitle(), data);
                                Map<String, String> captchaResult = captchaService.waitForCaptcha();
                                if (captchaResult != null) {
                                    // 如果前端返回了 V4 的结果，我们需要确保包含 v_voucher
                                    if (!captchaResult.containsKey("v_voucher")) {
                                        captchaResult.put("v_voucher", voucher);
                                    }
                                    log.info("[BLR] {}", LogKvs.event("Publish.Captcha.Submit")
                                            .add("roomId", room.getRoomId())
                                            .add("uname", room.getUname())
                                            .add("historyId", history.getId())
                                            .add("hasV4", captchaResult.containsKey("captcha_key"))
                                            .add("hasVoucher", captchaResult.containsKey("v_voucher")));
                                    uploadRes = BiliApi.webPublish(biliBiliUser, videoUploadDto, captchaResult);
                                    log.info("[BLR] {}", LogKvs.event("Publish.Captcha.PublishResponse")
                                            .add("roomId", room.getRoomId())
                                            .add("uname", room.getUname())
                                            .add("historyId", history.getId())
                                            .add("respLen", uploadRes == null ? 0 : uploadRes.length())
                                            .add("containsCaptcha", uploadRes != null && uploadRes.contains("验证码")));
                                    
                                    if (uploadRes.contains("验证码") || uploadRes.contains("\"code\":601")) {
                                        log.error("[BLR] {}", LogKvs.event("Publish.Captcha.VerifyFailedPause")
                                                .add("roomId", room.getRoomId())
                                                .add("uname", room.getUname())
                                                .add("historyId", history.getId())
                                                .add("pauseSeconds", 300));
                                        Thread.sleep(300 * 1000L);
                                        // 抛出异常以触发重试机制，但有了长休眠，不会频繁刷屏
                                        throw new RuntimeException("验证码验证失败: " + uploadRes);
                                    }
                                } else {
                                    log.warn("[BLR] {}", LogKvs.event("Upload.Captcha.Timeout")
                                            .add("roomId", room.getRoomId())
                                            .add("uname", room.getUname())
                                            .add("historyId", history.getId())
                                            .add("waitSeconds", 0));
                                    Thread.sleep(10 * 1000L);
                                    uploadRes = BiliApi.webPublish(biliBiliUser, videoUploadDto);
                                }
                            } catch (Exception e) {
                                log.error("[BLR] {}", LogKvs.event("Publish.Captcha.HandleError")
                                        .add("roomId", room.getRoomId())
                                        .add("uname", room.getUname())
                                        .add("historyId", history.getId()), e);
                                Thread.sleep(120 * 1000L);
                                uploadRes = BiliApi.webPublish(biliBiliUser, videoUploadDto);
                                log.info("[BLR] {}", LogKvs.event("Publish.WebPublish.Response")
                                        .add("roomId", room.getRoomId())
                                        .add("uname", room.getUname())
                                        .add("historyId", history.getId())
                                        .add("respLen", uploadRes == null ? 0 : uploadRes.length())
                                        .add("containsCaptcha", uploadRes != null && uploadRes.contains("验证码")));
                            }
                        }
                        String bvid = JSON.parseObject(uploadRes).getJSONObject("data").getString("bvid");
                        String aid = JSON.parseObject(uploadRes).getJSONObject("data").getString("aid");
                        if (StringUtils.isBlank(bvid) || StringUtils.isBlank(aid)) {
                            // 检测是否是时间戳跳变错误(code:21588)，如果是则放弃该投稿
                            if (uploadRes.contains("21588") || uploadRes.contains("时间跳跃") || uploadRes.contains("时间戳")) {
                                log.error("[BLR] {}", LogKvs.event("Publish.TimestampJump.GiveUpHistory")
                                        .add("roomId", room.getRoomId())
                                        .add("uname", room.getUname())
                                        .add("historyId", history.getId())
                                        .addIfNotBlank("title", history.getTitle())
                                        .add("code", 21588));
                                // 标记所有未上传的分P为放弃（时间戳跳变）
                                for (RecordHistoryPart part : uploadParts) {
                                    if (!part.isUpload()) {
                                        part.setUpload(false);
                                        part.setUploadRetryCount(UPLOAD_RETRY_GIVE_UP);
                                        part.setDeleteFailType("TIMESTAMP_JUMP");
                                        part.setDeleteFailReason("分P转码失败(时间戳跳变-文件损坏)，已放弃重新上传");
                                        partRepository.save(part);
                                        log.info("[BLR] {}", LogKvs.event("Publish.TimestampJump.MarkPartGiveUp")
                                                .add("historyId", history.getId())
                                                .add("partId", part.getId())
                                                .add("partTitle", part.getTitle()));
                                    }
                                }
                                // 设置为不上传，避免后续任务再次扫描到
                                history.setUpload(false);
                                historyRepository.save(history);
                                return false;
                            }
                            log.warn("[BLR] {}", LogKvs.event("Publish.WebPublish.MissingIds")
                                    .add("roomId", room.getRoomId())
                                    .add("uname", room.getUname())
                                    .add("historyId", history.getId())
                                    .add("respLen", uploadRes == null ? 0 : uploadRes.length()));
                            throw new RuntimeException(uploadRes);
                        }
                        history.setBvId(bvid);
                        history.setAvId(aid);
                        history.setPublish(true);
                        history = historyRepository.save(history);
                        log.info("[BLR] {}", LogKvs.event("Publish.WebPublish.Success")
                            .add("roomId", room.getRoomId())
                            .add("uname", room.getUname())
                            .add("historyId", history.getId())
                            .addIfNotBlank("title", history.getTitle())
                            .addIfNotBlank("bvid", bvid)
                            .addIfNotBlank("aid", aid));

                        // 兜底：部分情况下创建投稿接口可能不稳定地忽略 is_only_self，这里在投稿成功后强制同步一次可见性。
                        try {
                            int desiredVisibility = room.getIsOnlySelf();
                            if (desiredVisibility == 0 || desiredVisibility == 1) {
                                String visRes = BiliApi.updateVideoVisibility(biliBiliUser, Long.parseLong(aid), desiredVisibility);
                                log.info("[BLR] {}", LogKvs.event("Publish.Visibility.Sync.Success")
                                        .add("roomId", room.getRoomId())
                                        .add("historyId", history.getId())
                                        .addIfNotBlank("aid", aid)
                                        .add("is_only_self", desiredVisibility));
                            } else {
                                log.warn("[BLR] {}", LogKvs.event("Publish.Visibility.Sync.SkipInvalid")
                                        .add("roomId", room.getRoomId())
                                        .add("historyId", history.getId())
                                        .add("is_only_self", desiredVisibility));
                            }
                        } catch (Exception e) {
                            log.warn("[BLR] {}", LogKvs.event("Publish.Visibility.Sync.Failed")
                                    .add("roomId", room.getRoomId())
                                    .add("historyId", history.getId())
                                    .addIfNotBlank("aid", aid)
                                    .add("is_only_self", room.getIsOnlySelf()), e);
                        }

                        if (StringUtils.isNotBlank(wxuid) && StringUtils.isNotBlank(pushMsgTags) && pushMsgTags.contains("视频投稿")) {
                            Message message = new Message();
                            message.setAppToken(wxToken);
                            message.setContentType(Message.CONTENT_TYPE_TEXT);
                            message.setContent(WX_MSG_FORMAT.formatted("投稿成功", room.getUname(), room.getTitle(),
                                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")), "bvid=>" + bvid));
                            message.setUid(wxuid);
                            WxPusher.send(message);
                        }
                        for (RecordHistoryPart part : uploadParts) {
                            //解析弹幕入库
                            List<LiveMsg> liveMsgs = msgRepository.queryByPartId(part.getId());
                            msgRepository.deleteAll(liveMsgs);
                            liveMsgService.processing(part);
                        }
                        //处理高能剪辑事件
                        if (room.isHighEnergyCut()) {
                            highEnergyCutPublishService.process(history);
                        }

                        try {
                            if (room.getSeasonId() != null && room.getSeasonId() > 0) {
                                String addSeasons = BiliApi.addSeasons(biliBiliUser, room.getSeasonId(), aid, String.valueOf(uploadParts.get(0).getCid()), videoUploadDto.getTitle());
                                Integer code = JsonPath.read(addSeasons, "code");
                                if (code == 0) {
                                    log.info("[BLR] {}", LogKvs.event("Publish.Season.Add.Success")
                                            .add("roomId", room.getRoomId())
                                            .add("uname", room.getUname())
                                            .add("historyId", history.getId())
                                            .add("seasonId", room.getSeasonId())
                                            .addIfNotBlank("aid", aid));
                                }
                            }
                        } catch (Exception e) {
                            log.error("[BLR] {}", LogKvs.event("Publish.Season.Add.Failed")
                                    .add("roomId", room.getRoomId())
                                    .add("uname", room.getUname())
                                    .add("historyId", history.getId())
                                    .add("seasonId", room.getSeasonId())
                                    .addIfNotBlank("aid", aid), e);
                        }

                        //如果配置成投稿完成后删除则删除文件
                        try {
                            for (RecordHistoryPart part : uploadParts) {
                                String filePath = part.getFilePath();
                                if (room.getDeleteType() == 9) {
                                    File file = new File(filePath);
                                    boolean delete = file.delete();
                                    if (delete) {
                                        log.info("[BLR] {}", LogKvs.event("Publish.File.DeleteSuccess")
                                                .add("historyId", history.getId())
                                                .add("partId", part.getId())
                                                .add("filePath", filePath));
                                    } else {
                                        log.error("[BLR] {}", LogKvs.event("Publish.File.DeleteFailed")
                                                .add("historyId", history.getId())
                                                .add("partId", part.getId())
                                                .add("filePath", filePath));
                                    }
                                } else if (StringUtils.isNotBlank(room.getMoveDir()) && room.getDeleteType() == 10) {

                                    String fileName = filePath.substring(filePath.lastIndexOf("/") + 1, filePath.lastIndexOf("."));
                                    String startDirPath = filePath.substring(0, filePath.lastIndexOf('/') + 1);
                                    String toDirPath = room.getMoveDir() + filePath.substring(0, filePath.lastIndexOf('/') + 1).replace(workPath, "");
                                    File toDir = new File(toDirPath);
                                    if (!toDir.exists()) {
                                        toDir.mkdirs();
                                    }
                                    File startDir = new File(startDirPath);
                                    File[] files = startDir.listFiles((file, s) -> s.startsWith(fileName));
                                    if (files != null) {
                                        for (File file : files) {
                                            if (!filePath.startsWith(workPath)) {
                                                part.setFileDelete(true);
                                                part = partRepository.save(part);
                                                continue;
                                            }
                                            try {
                                                Files.move(Paths.get(file.getPath()), Paths.get(toDirPath + file.getName()),
                                                        StandardCopyOption.REPLACE_EXISTING);
                                                log.info("[BLR] {}", LogKvs.event("Publish.File.MoveSuccess")
                                                    .add("historyId", history.getId())
                                                    .add("partId", part.getId())
                                                    .add("fileName", file.getName())
                                                    .add("toDir", toDirPath));
                                            } catch (Exception e) {
                                                log.error("[BLR] {}", LogKvs.event("Publish.File.MoveFailed")
                                                    .add("historyId", history.getId())
                                                    .add("partId", part.getId())
                                                    .add("fileName", file.getName())
                                                    .add("toDir", toDirPath), e);
                                            }
                                        }
                                    }

                                    part.setFilePath(toDirPath + filePath.substring(filePath.lastIndexOf("/") + 1));
                                    part.setFileDelete(true);
                                    part = partRepository.save(part);
                                }
                            }
                        } catch (Exception de) {
                            log.error("[BLR] {}", LogKvs.event("Publish.File.PostProcess.Error")
                                    .add("roomId", room.getRoomId())
                                    .add("uname", room.getUname())
                                    .add("historyId", history.getId()), de);
                        }

                    } catch (Exception e) {
                        history.setUploadRetryCount(history.getUploadRetryCount() + 1);
                        history = historyRepository.save(history);
                        log.warn("[BLR] {}", LogKvs.event("Publish.WebPublish.Failed")
                                .add("roomId", room.getRoomId())
                                .add("uname", room.getUname())
                                .add("historyId", history.getId())
                                .addIfNotBlank("title", history.getTitle())
                                .add("retryCount", history.getUploadRetryCount())
                                .add("respLen", uploadRes == null ? 0 : uploadRes.length()), e);
                        if (StringUtils.isNotBlank(wxuid) && StringUtils.isNotBlank(pushMsgTags) && pushMsgTags.contains("视频投稿")) {
                            Message message = new Message();
                            message.setAppToken(wxToken);
                            message.setContentType(Message.CONTENT_TYPE_TEXT);
                            message.setContent(WX_MSG_FORMAT.formatted("投稿失败", room.getUname(), room.getTitle(),
                                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")), uploadRes != null ? uploadRes : e.getMessage()));
                            message.setUid(wxuid);
                            WxPusher.send(message);
                        }
                    } finally {
                        TaskUtil.publishTask.remove(history.getId());
                    }
                }
            }
        } catch (Exception e) {
            log.error("[BLR] {}", LogKvs.event("Publish.Error")
                    .add("historyId", history.getId())
                    .add("roomId", history.getRoomId())
                    .addIfNotBlank("title", history.getTitle()), e);
        } finally {
            TaskUtil.publishTask.remove(history.getId());
        }
        return true;
    }

    public DescDto template(String template, Map<String, Object> map) {
        List<DescV2Dto> resultList = new ArrayList<>();
        StringBuilder desc = new StringBuilder();
        List<String> stringList = splitTemplateByUid(template);
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
                    log.error("[BLR] {}", LogKvs.event("Template.UserCard.FetchFailed")
                            .add("uid", uid), e);
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
        return new DescDto(desc.toString(), resultList);
    }

    @Data
    @AllArgsConstructor
    class DescDto {
        public final String desc;
        public final List<DescV2Dto> descV2Dtos;
    }
}
