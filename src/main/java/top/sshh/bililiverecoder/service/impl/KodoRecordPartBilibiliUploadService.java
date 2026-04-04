package top.sshh.bililiverecoder.service.impl;

import com.alibaba.fastjson.JSON;
import com.zjiecode.wxpusher.client.bean.Message;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.entity.BiliBiliUser;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.entity.data.KodoPart;
import top.sshh.bililiverecoder.repo.BiliUserRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.service.LogAnalyzeService;
import top.sshh.bililiverecoder.service.RecordPartUploadService;
import top.sshh.bililiverecoder.service.UploadServiceFactory;
import top.sshh.bililiverecoder.service.UploadFairShareService;
import top.sshh.bililiverecoder.service.UploadUserSerialScheduler;
import top.sshh.bililiverecoder.util.TaskUtil;
import top.sshh.bililiverecoder.util.UploadEnums;
import top.sshh.bililiverecoder.util.LogKvs;
import top.sshh.bililiverecoder.util.PushNotifyClient;
import top.sshh.bililiverecoder.util.UploadProgressTracker;
import top.sshh.bililiverecoder.util.retry.UploadRetryBackoffPolicy;
import top.sshh.bililiverecoder.util.bili.Cookie;
import top.sshh.bililiverecoder.util.bili.WebCookie;
import top.sshh.bililiverecoder.util.bili.upload.KodoChunkUploadRequest;
import top.sshh.bililiverecoder.util.bili.upload.KodoCompleteUploadRequest;
import top.sshh.bililiverecoder.util.bili.upload.KodoFetchUploadRequest;
import top.sshh.bililiverecoder.util.bili.upload.PreUploadRequest;
import top.sshh.bililiverecoder.util.bili.upload.pojo.ChunkUploadBean;
import top.sshh.bililiverecoder.util.bili.upload.pojo.CompleteUploadBean;
import top.sshh.bililiverecoder.util.bili.upload.pojo.PreUploadBean;
import top.sshh.bililiverecoder.util.bili.user.UserMy;
import top.sshh.bililiverecoder.util.bili.user.UserMyRootBean;
import top.sshh.bililiverecoder.service.CaptchaService;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import java.util.concurrent.ForkJoinPool;
import top.sshh.bililiverecoder.service.RateLimiterService;
import top.sshh.bililiverecoder.util.NettyUploadClient;

@Slf4j
@Service("kodoRecordPartBilibiliUploadService")
public class KodoRecordPartBilibiliUploadService implements RecordPartUploadService {

    @Autowired
    private CaptchaService captchaService;

    @Value("${server.port:8080}")
    private String serverPort;

    public static final String OS = "kodo";

    @Value("${record.work-path}")
    private String workPath;

    @PostConstruct
    public void initWorkPath() {
        workPath = workPath.replaceAll("\\\\\\\\", "\\\\");
        workPath = workPath.replace("\\", "/");
    }

    private static final String WX_MSG_FORMAT = """
            上传结果: %s
            收到主播%s分P上传%s事件
            房间名: %s
            时间: %s
            文件路径: %s
            文件录制开始时间: %s
            文件录制时长: %s 分钟
            文件录制大小: %.3f GB
            原因: %s
            """;
    @Value("${record.wx-push-token}")
    private String wxToken;
    @Autowired
    private BiliUserRepository biliUserRepository;
    @Autowired
    private RecordHistoryPartRepository partRepository;
    @Autowired
    private RecordHistoryRepository historyRepository;
    @Autowired
    private RecordRoomRepository roomRepository;

    @Lazy
    @Autowired
    private UploadServiceFactory uploadServiceFactory;

    @Autowired
    private UploadProgressTracker uploadProgressTracker;

    @Autowired
    private UploadFairShareService uploadFairShareService;

    @Autowired
    private UploadUserSerialScheduler uploadUserSerialScheduler;

    private final UploadRetryBackoffPolicy uploadRetryBackoffPolicy = new UploadRetryBackoffPolicy();

    private static final int UPLOAD_RETRY_GIVE_UP = 9999;
    private static final int CHUNK_MAX_RETRY = 200;
    private static final int GLOBAL_CHUNK_FAILURE_FUSE_THRESHOLD = 400;

    @Override
    public void asyncUpload(RecordHistoryPart part) {
        asyncUploadIfNeeded(part);
        }

        @Override
        public boolean asyncUploadIfNeeded(RecordHistoryPart part) {
        RecordHistoryPart loadedPart = partRepository.findById(part.getId()).get();
        log.info("[BLR] {}", LogKvs.event("Upload.Part.AsyncStart")
                .add("os", OS)
                .add("partId", loadedPart.getId())
                .add("historyId", loadedPart.getHistoryId())
                .add("roomId", loadedPart.getRoomId())
                .add("filePath", loadedPart.getFilePath()));
        RecordRoom room = roomRepository.findByRoomId(loadedPart.getRoomId());
        if (room == null || room.getUploadUserId() == null) {
            this.upload(loadedPart);
            return true;
        }
        boolean enqueued = uploadUserSerialScheduler.submitIfPartNotPending(
                room.getUploadUserId(),
                room.getRoomId(),
                loadedPart.getHistoryId(),
                loadedPart.getId(),
                OS,
                () -> this.upload(loadedPart)
        );
        if (!enqueued) {
            log.debug("[BLR] {}", LogKvs.event("Upload.Part.AlreadyQueued")
                .add("os", OS)
                .add("partId", loadedPart.getId())
                .add("historyId", loadedPart.getHistoryId())
                .add("roomId", loadedPart.getRoomId()));
        }
        return enqueued;
    }

    @Override
    public void upload(RecordHistoryPart part) {
        part = partRepository.findById(part.getId()).get();
        long uploadStartNs = System.nanoTime();
        if (part.isUpload()) {
            log.info("[BLR] {}", LogKvs.event("Upload.Part.SkipAlreadyUploaded")
                    .add("os", OS)
                    .add("partId", part.getId())
                    .add("historyId", part.getHistoryId())
                    .add("roomId", part.getRoomId()));
            return;
        }
        synchronized (TaskUtil.partUploadTask) {
            Thread thread = TaskUtil.partUploadTask.get(part.getId());
            if (thread != null && thread != Thread.currentThread()) {
                log.info("[BLR] {}", LogKvs.event("Upload.Part.AlreadyUploading")
                        .add("os", OS)
                        .add("partId", part.getId())
                        .add("historyId", part.getHistoryId())
                        .add("roomId", part.getRoomId())
                        .add("ownerThread", thread.getName())
                        .add("currentThread", Thread.currentThread().getName()));
                return;
            }
            TaskUtil.partUploadTask.put(part.getId(), Thread.currentThread());
        }
        try {
            RecordRoom room = roomRepository.findByRoomId(part.getRoomId());

            if (room != null) {
                UploadEnums uploadEnums = UploadEnums.find(room.getLine());
                String wxuid = room.getWxuid();
                String pushMsgTags = room.getPushMsgTags();
                if (room.getTid() == null) {
                    //没有设置分区，直接取消上传
                    return;
                }
                // 上传任务入队列
                String filePath = part.getFilePath().intern();
                synchronized (filePath) {
                    Optional<RecordHistory> historyOptional = historyRepository.findById(part.getHistoryId());
                    if (!historyOptional.isPresent()) {
                        log.error("[BLR] {}", LogKvs.event("Upload.Part.MissingHistory")
                                .add("os", OS)
                                .add("partId", part.getId())
                                .add("historyId", part.getHistoryId())
                                .add("roomId", part.getRoomId())
                                .add("filePath", part.getFilePath()));
                        TaskUtil.partUploadTask.remove(part.getId());
                        return;
                    }
                    RecordHistory history = historyOptional.get();
                    File uploadFile = new File(filePath);
                    if (!uploadFile.exists()) {
                        log.error("[BLR] {}", LogKvs.event("Upload.Part.FileMissing")
                                .add("os", OS)
                                .add("partId", part.getId())
                                .add("historyId", part.getHistoryId())
                                .add("roomId", part.getRoomId())
                                .add("filePath", filePath));
                        if (part.getUploadRetryCount() < 2) {
                            part.setUploadRetryCount(part.getUploadRetryCount() + 1);
                            partRepository.save(part);
                            uploadServiceFactory.getUploadService(room.getLine()).asyncUpload(part);
                        } else {
                            part.setUploadRetryCount(UPLOAD_RETRY_GIVE_UP);
                            String errorMsg = "稿件分P文件不存在，已放弃上传: " + filePath;
                            part.setDeleteFailReason(errorMsg);
                            part.setDeleteFailType("FILE_MISSING");
                            partRepository.save(part);
                            LogAnalyzeService.getInstance().processLog(errorMsg, "ERROR");
                            TaskUtil.partUploadTask.remove(part.getId());
                        }
                        return;
                    }
                    if (history.isUpload()) {
                        if (room.getUploadUserId() == null) {
                            log.warn("[BLR] {}", LogKvs.event("Upload.Part.NoUploadUser")
                                    .add("os", OS)
                                    .add("roomId", room.getRoomId())
                                    .add("uname", room.getUname())
                                    .add("partId", part.getId())
                                    .add("historyId", part.getHistoryId()));
                            TaskUtil.partUploadTask.remove(part.getId());
                            return;
                        } else {
                            Optional<BiliBiliUser> userOptional = biliUserRepository.findById(room.getUploadUserId());
                            if (!userOptional.isPresent()) {
                                log.error("[BLR] {}", LogKvs.event("Upload.Part.UploadUserMissing")
                                        .add("os", OS)
                                        .add("roomId", room.getRoomId())
                                        .add("uname", room.getUname())
                                        .add("uploadUserId", room.getUploadUserId())
                                        .add("partId", part.getId())
                                        .add("historyId", part.getHistoryId()));
                                TaskUtil.partUploadTask.remove(part.getId());
                                return;
                            }
                            BiliBiliUser biliBiliUser = userOptional.get();
                            if (!biliBiliUser.isLogin()) {
                                log.error("[BLR] {}", LogKvs.event("Upload.Part.LoginInvalid")
                                        .add("os", OS)
                                        .add("roomId", room.getRoomId())
                                        .add("uname", room.getUname())
                                        .add("uploadUserId", room.getUploadUserId())
                                        .add("partId", part.getId())
                                        .add("historyId", part.getHistoryId()));
                                TaskUtil.partUploadTask.remove(part.getId());
                                return;
                            }
                            // 检查是否已经过期，调用用户信息接口
                            // 登录验证结束
                            WebCookie webCookie = Cookie.parse(biliBiliUser.getCookies());
                            UserMy userMy = new UserMy(webCookie);
                            UserMyRootBean myInfo = userMy.getPojo();
                            if (myInfo.getCode() == -101) {
                                biliBiliUser.setLogin(false);
                                biliBiliUser = biliUserRepository.save(biliBiliUser);
                                TaskUtil.partUploadTask.remove(part.getId());
                                if (PushNotifyClient.canSend(room, wxuid, pushMsgTags, "分P上传")) {
                                    Message message = new Message();
                                    message.setAppToken(wxToken);
                                    message.setContentType(Message.CONTENT_TYPE_TEXT);
                                    message.setContent(WX_MSG_FORMAT.formatted("上传失败", room.getUname(), "开始", room.getTitle(),
                                            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")),
                                            part.getFilePath(), part.getStartTime().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")), (int)part.getDuration() / 60, ((float)part.getFileSize() / 1024 / 1024 / 1024), biliBiliUser.getUname() + "登录已过期，请重新登录"));
                                    message.setUid(wxuid);
                                    PushNotifyClient.sendParallel(room, message);
                                }
                                throw new RuntimeException("{}登录已过期，请重新登录! " + biliBiliUser.getUname());
                            }
                            // 登录验证结束
                            // 按 uploadUserId 串行由调度器保证，这里不再阻塞线程等待锁
                            {
                            uploadFairShareService.registerUploadUser(biliBiliUser.getId(), room.getRoomId(), part.getId(), "KODO_PART");
                            try {
                            Map<String, String> preParams = new HashMap<>();
                            preParams.put("r", uploadEnums.getOs());
                            preParams.put("os", uploadEnums.getOs());
                            if (StringUtils.isNotBlank(uploadEnums.getCdn())) {
                                preParams.put("upcdn", uploadEnums.getCdn());
                            }
                            if (StringUtils.isNotBlank(uploadEnums.getZone())) {
                                preParams.put("zone", uploadEnums.getZone());
                            }
                            preParams.put("profile", uploadEnums.getProfile());
                            preParams.put("ssl", "0");
                            preParams.put("version", "2.14.0.0");
                            preParams.put("build", "2140000");
                            preParams.put("webVersion", "2.14.0");
                            preParams.put("name", uploadFile.getName());
                            preParams.put("size", String.valueOf(uploadFile.length()));
                            long fileSize = uploadFile.length();
                            long chunkSize = 1024 * 1024 * 4;
                            long chunkNum = (long)Math.ceil((double)fileSize / chunkSize);
                            PreUploadRequest preuploadRequest = new PreUploadRequest(webCookie, preParams);
                            PreUploadBean preUploadBean;
                            try {
                                do {
                                    preUploadBean = preuploadRequest.getPojo();
                                    if (preUploadBean == null || preUploadBean.getOK() == 0) {
                                            log.warn("[BLR] {}", LogKvs.event("Upload.PreUpload.Failed")
                                                    .add("os", OS)
                                                    .add("roomId", room.getRoomId())
                                                    .add("uname", room.getUname())
                                                    .add("partId", part.getId())
                                                    .add("historyId", part.getHistoryId())
                                                    .add("fileName", uploadFile.getName())
                                                    .add("code", preUploadBean != null ? preUploadBean.getCode() : null)
                                                    .add("ok", preUploadBean != null ? preUploadBean.getOK() : null));
                                            if (preUploadBean != null && ((preUploadBean.getCode() == 601 && preUploadBean.getDetail() != null && preUploadBean.getDetail().containsKey("v_voucher")) || preUploadBean.getCode() == 406)) {
                                                String voucher = (preUploadBean.getDetail() != null) ? (String) preUploadBean.getDetail().get("v_voucher") : "MANUAL_INTERVENTION";
                                                log.warn("[BLR] {}", LogKvs.event("Upload.Captcha.Required")
                                                        .add("os", OS)
                                                        .add("roomId", room.getRoomId())
                                                        .add("uname", room.getUname())
                                                        .add("partId", part.getId())
                                                        .add("historyId", part.getHistoryId())
                                                        .add("code", preUploadBean.getCode())
                                                        .add("fileName", uploadFile.getName())
                                                        .add("url", "http://localhost:" + serverPort + "/html/captcha.html"));
                                                captchaService.setCaptchaRequired(voucher, uploadFile.getName(), preUploadBean.getDetail());
                                                Map<String, String> result = captchaService.waitForCaptcha();
                                                if (result != null) {
                                                    preParams.putAll(result);
                                                }
                                            } else {
                                            log.warn("[BLR] {}", LogKvs.event("Upload.RateLimit.Wait")
                                                    .add("os", OS)
                                                    .add("roomId", room.getRoomId())
                                                    .add("uname", room.getUname())
                                                    .add("partId", part.getId())
                                                    .add("historyId", part.getHistoryId())
                                                    .add("fileName", uploadFile.getName())
                                                    .add("sleepMs", 10000));
                                            try {
                                                Thread.sleep(10000L);
                                            } catch (InterruptedException e) {
                                                log.warn("[BLR] {}", LogKvs.event("Upload.RateLimit.WaitInterrupted")
                                                        .add("os", OS)
                                                        .add("roomId", room.getRoomId())
                                                        .add("uname", room.getUname())
                                                        .add("partId", part.getId())
                                                        .add("historyId", part.getHistoryId())
                                                        .add("fileName", uploadFile.getName())
                                                        .add("sleepMs", 10000)
                                                        .addIfNotBlank("err", e.getMessage())
                                                        .add("ex", e.getClass().getSimpleName()), e);
                                                Thread.currentThread().interrupt();
                                            }
                                        }
                                    }
                                } while (Objects.requireNonNull(preUploadBean).getOK() == 0);
                            } catch (Exception e) {
                                //存在异常
                                TaskUtil.partUploadTask.remove(part.getId());
                                if (PushNotifyClient.canSend(room, wxuid, pushMsgTags, "分P上传")) {
                                    Message message = new Message();
                                    message.setAppToken(wxToken);
                                    message.setContentType(Message.CONTENT_TYPE_TEXT);
                                    message.setContent(WX_MSG_FORMAT.formatted("上传失败", room.getUname(), "开始", room.getTitle(),
                                            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")),
                                            part.getFilePath(), part.getStartTime().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")), (int)part.getDuration() / 60, ((float)part.getFileSize() / 1024 / 1024 / 1024), biliBiliUser.getUname() + "并发上传失败，存在异常"));
                                    message.setUid(wxuid);
                                    PushNotifyClient.sendParallel(room, message);
                                }
                                throw new RuntimeException("并发上传失败，存在异常", e);
                            }
                                log.info("[BLR] {}", LogKvs.event("Upload.PreUpload.Success")
                                    .add("os", OS)
                                    .add("roomId", room.getRoomId())
                                    .add("uname", room.getUname())
                                    .add("partId", part.getId())
                                    .add("historyId", part.getHistoryId())
                                    .add("fileName", uploadFile.getName())
                                    .add("bizId", preUploadBean != null ? preUploadBean.getBiz_id() : null)
                                    .add("endpoint", preUploadBean != null ? preUploadBean.getEndpoint() : null));
                            // 分段上传
                            AtomicInteger upCount = new AtomicInteger(0);
                            AtomicInteger globalFailCount = new AtomicInteger(0);
                            java.util.concurrent.atomic.AtomicBoolean globalFuseOpen = new java.util.concurrent.atomic.AtomicBoolean(false);
                            java.util.concurrent.atomic.AtomicReference<String> globalFuseReason = new java.util.concurrent.atomic.AtomicReference<>(null);
                            final Long partId = part.getId();
                            final Long historyId = part.getHistoryId();
                            final Integer partPage = resolveProgressPage(part);
                            uploadProgressTracker.start(partId, historyId, partPage, (int) chunkNum);
                            List<KodoPart> parts = new CopyOnWriteArrayList<>();
                            List<Runnable> runnableList = new ArrayList<>();
                            for (int i = 0; i < chunkNum; i++) {
                                int finalI = i;
                                PreUploadBean finalPreUploadBean = preUploadBean;
                                Runnable runnable = () -> {
                                    try {
                                        int chunkRetryCount = 0;
                                        while (!globalFuseOpen.get() && chunkRetryCount < CHUNK_MAX_RETRY) {
                                            try {
                                                // 上传
                                                long endSize = (finalI + 1) * chunkSize;
                                                long finalChunkSize = chunkSize;
                                                Map<String, String> chunkParams = new HashMap<>();
                                                chunkParams.put("partNumber", String.valueOf(finalI + 1));
                                                chunkParams.put("size", String.valueOf(finalChunkSize));
                                                chunkParams.put("start", String.valueOf(finalI * finalChunkSize));
                                                chunkParams.put("end", String.valueOf(endSize));
                                                if (endSize > fileSize) {
                                                    endSize = fileSize;
                                                    finalChunkSize = fileSize - (finalI * finalChunkSize);
                                                    chunkParams.put("size", String.valueOf(finalChunkSize));
                                                    chunkParams.put("end", String.valueOf(endSize));
                                                }
                                                try (RandomAccessFile randomAccessFile = new RandomAccessFile(filePath, "r")) {
                                                    KodoChunkUploadRequest chunkUploadRequest = new KodoChunkUploadRequest(finalPreUploadBean, chunkParams, randomAccessFile);
                                                    ChunkUploadBean chunkUploadBean = chunkUploadRequest.getPojo();
                                                    parts.add(new KodoPart(finalI, chunkUploadBean.getCtx()));
                                                } catch (FileNotFoundException fileNotFoundException) {
                                                    globalFuseReason.compareAndSet(null, "UPLOAD_FILE_NOT_FOUND");
                                                    globalFuseOpen.set(true);
                                                    log.error("[BLR] {}", LogKvs.event("Upload.Chunk.FileMissing")
                                                        .add("os", OS)
                                                        .add("roomId", room.getRoomId())
                                                        .add("uname", room.getUname())
                                                        .add("partId", partId)
                                                        .add("historyId", historyId)
                                                        .add("filePath", filePath));
                                                    break;
                                                }
                                                int count = upCount.incrementAndGet();
                                                uploadProgressTracker.updateChunkDone(partId, historyId, partPage, count, (int) chunkNum);
                                                log.debug("[BLR] {}", LogKvs.event("Upload.Chunk.Progress")
                                                    .add("os", OS)
                                                    .add("roomId", room.getRoomId())
                                                    .add("title", room.getTitle())
                                                    .add("partId", partId)
                                                    .add("historyId", historyId)
                                                    .add("chunkIndex", finalI)
                                                    .add("done", count)
                                                    .add("total", chunkNum)
                                                    .add("thread", Thread.currentThread().getName()));
                                                break;
                                            } catch (Exception e) {
                                                chunkRetryCount++;
                                                int globalRetryCount = globalFailCount.incrementAndGet();
                                                UploadRetryBackoffPolicy.BackoffDecision backoffDecision =
                                                        uploadRetryBackoffPolicy.nextDecision(chunkRetryCount, e, e.getMessage());
                                                String uploadHost = resolveUploadHost(finalPreUploadBean);
                                                long backoffMs = backoffDecision.delayMs();
                                                uploadProgressTracker.markRetryWait(partId, e.getMessage(), chunkRetryCount, backoffMs);
                                                log.warn("[BLR] {}", LogKvs.event("Upload.Chunk.Error")
                                                    .add("os", OS)
                                                    .add("roomId", room.getRoomId())
                                                    .add("title", room.getTitle())
                                                    .add("partId", partId)
                                                    .add("historyId", historyId)
                                                    .add("chunkIndex", finalI)
                                                    .add("chunkSize", chunkSize)
                                                    .add("start", (long) finalI * chunkSize)
                                                    .add("end", (long) (finalI + 1) * chunkSize)
                                                    .add("chunkRetryCount", chunkRetryCount)
                                                    .add("chunkRetryMax", CHUNK_MAX_RETRY)
                                                    .add("globalFailCount", globalRetryCount)
                                                    .add("globalFuseThreshold", GLOBAL_CHUNK_FAILURE_FUSE_THRESHOLD)
                                                    .add("retryCategory", backoffDecision.retryCategory())
                                                    .add("host", uploadHost)
                                                    .add("backoffMs", backoffMs)
                                                    .add("err", e.getMessage())
                                                    .add("ex", e.getClass().getSimpleName()));
                                                if (globalRetryCount >= GLOBAL_CHUNK_FAILURE_FUSE_THRESHOLD) {
                                                    globalFuseReason.compareAndSet(null, "GLOBAL_CHUNK_FAILURE_THRESHOLD_REACHED");
                                                    globalFuseOpen.set(true);
                                                    log.error("[BLR] {}", LogKvs.event("Upload.Chunk.GlobalFuseOpen")
                                                            .add("os", OS)
                                                            .add("roomId", room.getRoomId())
                                                            .add("uname", room.getUname())
                                                            .add("partId", partId)
                                                            .add("historyId", historyId)
                                                            .add("chunkIndex", finalI)
                                                            .add("globalFailCount", globalRetryCount)
                                                            .add("globalFuseThreshold", GLOBAL_CHUNK_FAILURE_FUSE_THRESHOLD)
                                                            .add("reason", globalFuseReason.get())
                                                            .addIfNotBlank("err", e.getMessage())
                                                            .add("ex", e.getClass().getSimpleName()), e);
                                                    break;
                                                }
                                                try {
                                                    Thread.sleep(backoffMs);
                                                } catch (InterruptedException ex) {
                                                    log.warn("[BLR] {}", LogKvs.event("Upload.Chunk.RetryWaitInterrupted")
                                                            .add("os", OS)
                                                            .add("roomId", room.getRoomId())
                                                            .add("uname", room.getUname())
                                                            .add("partId", partId)
                                                            .add("historyId", historyId)
                                                            .add("chunkIndex", finalI)
                                                            .add("retryCategory", backoffDecision.retryCategory())
                                                            .add("host", uploadHost)
                                                            .add("sleepMs", backoffMs)
                                                            .addIfNotBlank("err", ex.getMessage())
                                                            .add("ex", ex.getClass().getSimpleName()), ex);
                                                    globalFuseReason.compareAndSet(null, "RETRY_WAIT_INTERRUPTED");
                                                    globalFuseOpen.set(true);
                                                    Thread.currentThread().interrupt();
                                                    return;
                                                }
                                            }
                                        }
                                        if (chunkRetryCount >= CHUNK_MAX_RETRY && !globalFuseOpen.get()) {
                                            globalFuseReason.compareAndSet(null, "CHUNK_RETRY_LIMIT_REACHED");
                                            globalFuseOpen.set(true);
                                            log.error("[BLR] {}", LogKvs.event("Upload.Chunk.GlobalFuseOpen")
                                                    .add("os", OS)
                                                    .add("roomId", room.getRoomId())
                                                    .add("uname", room.getUname())
                                                    .add("partId", partId)
                                                    .add("historyId", historyId)
                                                    .add("chunkIndex", finalI)
                                                    .add("chunkRetryCount", chunkRetryCount)
                                                    .add("chunkRetryMax", CHUNK_MAX_RETRY)
                                                    .add("reason", globalFuseReason.get()));
                                        }
                                    } catch (Exception e) {
                                        uploadProgressTracker.markFailed(partId, e.getMessage());
                                        log.error("[BLR] {}", LogKvs.event("Upload.Chunk.ThreadFailed")
                                                .add("os", OS)
                                                .add("roomId", room.getRoomId())
                                                .add("uname", room.getUname())
                                                .add("partId", partId)
                                                .add("historyId", historyId)
                                                .add("chunkIndex", finalI)
                                                .addIfNotBlank("err", e.getMessage())
                                                .add("ex", e.getClass().getSimpleName()), e);
                                    }
                                };

                                runnableList.add(runnable);

                            }

                            //并发上传
                            Message message = new Message();

                            if (PushNotifyClient.canSend(room, wxuid, pushMsgTags, "分P上传")) {
                                message.setAppToken(wxToken);
                                message.setContentType(Message.CONTENT_TYPE_TEXT);
                                message.setContent(WX_MSG_FORMAT.formatted("开始上传", room.getUname(), "开始", room.getTitle(),
                                        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")),
                                        part.getFilePath(), part.getStartTime().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")), (int) part.getDuration() / 60, ((float) part.getFileSize() / 1024 / 1024 / 1024), biliBiliUser.getUname()));
                                message.setUid(wxuid);
                                PushNotifyClient.sendParallel(room, message);
                            }

                            // 动态并发计算
                            int concurrency = 3; // 默认
                            if (RateLimiterService.getInstance() != null) {
                                long limitRate = RateLimiterService.getInstance().getUploadSpeedLimitBytesPerSecond();
                                long realRate = NettyUploadClient.getGlobalWriteThroughput();
                                UploadFairShareService.FairShareDecision fairShareDecision = uploadFairShareService.fairShareLimitWithDecision(limitRate);
                                long fairShareLimit = fairShareDecision.effectiveLimitBps();
                                concurrency = uploadFairShareService.recommendConcurrency(fairShareLimit, realRate);
                                log.info("[BLR] {}", LogKvs.event("Upload.FairShare")
                                        .add("os", OS)
                                        .add("roomId", room.getRoomId())
                                        .add("partId", part.getId())
                                        .add("uploadUserId", biliBiliUser.getId())
                                        .add("globalLimit", limitRate)
                                        .add("effectiveLimit", fairShareLimit)
                                        .add("splitApplied", fairShareDecision.splitApplied())
                                        .add("splitReason", fairShareDecision.reason())
                                        .add("activeUploadUsers", fairShareDecision.activeUploadUsers())
                                        .add("activeTasks", uploadFairShareService.getActiveUploadTasks())
                                        .add("realRate", realRate));
                            }
                            concurrency = Math.min(concurrency, 8);
                            concurrency = Math.max(concurrency, 1);
                            
                            log.info("[BLR] {}", LogKvs.event("Upload.Concurrency")
                                    .add("concurrency", concurrency));

                            ForkJoinPool customThreadPool = new ForkJoinPool(concurrency);
                            try {
                                customThreadPool.submit(() ->
                                        runnableList.stream().parallel().forEach(Runnable::run)
                                ).get();
                            } catch (Exception e) {
                                throw new RuntimeException("Concurrency Upload Failed", e);
                            } finally {
                                customThreadPool.shutdown();
                            }

                            if (globalFuseOpen.get() || upCount.get() < chunkNum) {
                                part = partRepository.findById(part.getId()).get();
                                part.setUpload(false);
                                part.setUploadRetryCount(part.getUploadRetryCount() + 1);
                                part = partRepository.save(part);
                                String chunkFailReason = StringUtils.defaultIfBlank(globalFuseReason.get(), "chunk upload failed");
                                uploadProgressTracker.markFailed(partId, chunkFailReason);
                                log.error("[BLR] {}", LogKvs.event("Upload.Chunk.AllFailed")
                                        .add("os", OS)
                                        .add("roomId", room.getRoomId())
                                        .add("uname", room.getUname())
                                        .add("partId", partId)
                                        .add("historyId", historyId)
                                        .add("doneChunkCount", upCount.get())
                                        .add("totalChunkCount", chunkNum)
                                        .add("globalFailCount", globalFailCount.get())
                                        .add("globalFuseOpen", globalFuseOpen.get())
                                        .add("reason", chunkFailReason));
                                if (part.getUploadRetryCount() < 2) {
                                    Thread.sleep(5000);
                                    uploadServiceFactory.getUploadService(room.getLine()).asyncUpload(part);
                                    log.info("[BLR] {}", LogKvs.event("Upload.Part.RetryScheduled")
                                            .add("os", OS)
                                            .add("roomId", room.getRoomId())
                                            .add("uname", room.getUname())
                                            .add("partId", part.getId())
                                            .add("historyId", part.getHistoryId())
                                            .add("retry", part.getUploadRetryCount())
                                            .add("maxRetry", 2)
                                            .add("filePath", filePath));
                                } else {
                                    part.setUploadRetryCount(UPLOAD_RETRY_GIVE_UP);
                                    String errorMsg = "稿件分P上传失败次数过多，已放弃: " + filePath;
                                    part.setDeleteFailReason(errorMsg);
                                    part.setDeleteFailType("UPLOAD_FAILED");
                                    partRepository.save(part);
                                    LogAnalyzeService.getInstance().processLog(errorMsg, "ERROR");
                                }
                                //存在异常
                                TaskUtil.partUploadTask.remove(part.getId());
                                if (PushNotifyClient.canSend(room, wxuid, pushMsgTags, "分P上传")) {
                                    message.setAppToken(wxToken);
                                    message.setContentType(Message.CONTENT_TYPE_TEXT);
                                    message.setContent(WX_MSG_FORMAT.formatted("上传失败", room.getUname(), "开始", room.getTitle(),
                                            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")),
                                            part.getFilePath(), part.getStartTime().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")), (int)part.getDuration() / 60, ((float)part.getFileSize() / 1024 / 1024 / 1024), biliBiliUser.getUname() + "并发上传失败，存在异常"));
                                    message.setUid(wxuid);
                                    PushNotifyClient.sendParallel(room, message);
                                }
                                throw new RuntimeException(part.getFilePath() + "===并发上传失败，存在异常");
                            }
                            //通知服务器上传完成
                            Map<String, String> completeParams = new HashMap<>();
                            completeParams.put("total", String.valueOf(fileSize));
                            String ctxs = parts.stream().sorted(Comparator.comparingInt(KodoPart::getIndex)).map(KodoPart::getCtx).collect(Collectors.joining(","));
                            KodoCompleteUploadRequest completeUploadRequest = new KodoCompleteUploadRequest(preUploadBean, completeParams, ctxs);
                            KodoFetchUploadRequest checkUploadRequest = new KodoFetchUploadRequest(preUploadBean);
                            try {
                                CompleteUploadBean completeUploadBean = null;
                                for (int i = 0; i < 5; i++) {
                                    try {
                                        completeUploadBean = completeUploadRequest.getPojo();
                                    } catch (Exception e) {
                                        if (completeUploadBean == null) {
                                            completeUploadBean = new CompleteUploadBean();
                                        }
                                        log.error("[BLR] {}", LogKvs.event("Upload.Complete.Retry")
                                                .add("os", OS)
                                                .add("roomId", room.getRoomId())
                                                .add("uname", room.getUname())
                                                .add("partId", part.getId())
                                                .add("historyId", part.getHistoryId())
                                                .add("attempt", i + 1)
                                                .add("maxAttempt", 5)
                                                .add("err", e.getMessage())
                                                .add("ex", e.getClass().getSimpleName()), e);
                                    }
                                }
                                CompleteUploadBean checkUploadBean = null;
                                for (int i = 0; i < 5; i++) {
                                    try {
                                        checkUploadBean = checkUploadRequest.getPojo();
                                        if (checkUploadBean != null && checkUploadBean.getOK() != null && checkUploadBean.getOK() == 1) {
                                            break;
                                        }
                                    } catch (Exception e) {
                                        if (checkUploadBean == null) {
                                            checkUploadBean = new CompleteUploadBean();
                                        }
                                        log.error("[BLR] {}", LogKvs.event("Upload.Complete.CheckRetry")
                                                .add("os", OS)
                                                .add("roomId", room.getRoomId())
                                                .add("uname", room.getUname())
                                                .add("partId", part.getId())
                                                .add("historyId", part.getHistoryId())
                                                .add("attempt", i + 1)
                                                .add("maxAttempt", 5)
                                                .add("err", e.getMessage())
                                                .add("ex", e.getClass().getSimpleName()), e);
                                    }
                                }

                                if (completeUploadBean != null && checkUploadBean != null
                                        && checkUploadBean.getOK() != null && checkUploadBean.getOK() == 1) {
                                    part = partRepository.findById(part.getId()).get();
                                    part.setUpload(true);
                                    part.setCid(preUploadBean.getBiz_id());
                                    part.setFileName(preUploadBean.getBili_filename());
                                    part.setUpdateTime(LocalDateTime.now());
                                    part = partRepository.save(part);
                                    //如果配置上传完成删除，则删除文件
                                    if (room.getDeleteType() == 1) {
                                        boolean delete = uploadFile.delete();
                                        if (delete) {
                                            log.info("[BLR] {}", LogKvs.event("Upload.Post.DeleteSuccess")
                                                    .add("os", OS)
                                                    .add("roomId", room.getRoomId())
                                                    .add("uname", room.getUname())
                                                    .add("partId", part.getId())
                                                    .add("historyId", part.getHistoryId())
                                                    .add("filePath", filePath));
                                        } else {
                                            log.error("[BLR] {}", LogKvs.event("Upload.Post.DeleteFailed")
                                                    .add("os", OS)
                                                    .add("roomId", room.getRoomId())
                                                    .add("uname", room.getUname())
                                                    .add("partId", part.getId())
                                                    .add("historyId", part.getHistoryId())
                                                    .add("filePath", filePath));
                                        }
                                    } else if (StringUtils.isNotBlank(room.getMoveDir()) && room.getDeleteType() == 4) {
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
                                                    part = partRepository.findById(part.getId()).get();
                                                    part.setFileDelete(true);
                                                    part = partRepository.save(part);
                                                    continue;
                                                }
                                                try {
                                                    Files.move(Paths.get(file.getPath()), Paths.get(toDirPath + file.getName()),
                                                            StandardCopyOption.REPLACE_EXISTING);
                                                    log.info("[BLR] {}", LogKvs.event("Upload.Post.MoveSuccess")
                                                            .add("os", OS)
                                                            .add("roomId", room.getRoomId())
                                                            .add("uname", room.getUname())
                                                            .add("partId", part.getId())
                                                            .add("historyId", part.getHistoryId())
                                                            .add("fileName", file.getName())
                                                            .add("toDir", toDirPath));
                                                } catch (Exception e) {
                                                    log.error("[BLR] {}", LogKvs.event("Upload.Post.MoveFailed")
                                                            .add("os", OS)
                                                            .add("roomId", room.getRoomId())
                                                            .add("uname", room.getUname())
                                                            .add("partId", part.getId())
                                                            .add("historyId", part.getHistoryId())
                                                            .add("fileName", file.getName())
                                                            .add("toDir", toDirPath)
                                                            .add("err", e.getMessage())
                                                            .add("ex", e.getClass().getSimpleName()), e);
                                                }
                                            }
                                        }
                                        part = partRepository.findById(part.getId()).get();
                                        part.setFilePath(toDirPath + filePath.substring(filePath.lastIndexOf("/") + 1));
                                        part.setFileDelete(true);
                                        part = partRepository.save(part);
                                    }
                                    TaskUtil.partUploadTask.remove(part.getId());
                                    uploadProgressTracker.markSuccessAndRemove(part.getId());
                                        log.info("[BLR] {}", LogKvs.event("Upload.Part.Success")
                                            .add("os", OS)
                                            .add("roomId", room.getRoomId())
                                            .add("uname", room.getUname())
                                            .add("partId", part.getId())
                                            .add("historyId", part.getHistoryId())
                                            .add("filePath", filePath)
                                            .add("serverFileName", part.getFileName())
                                            .add("cid", part.getCid())
                                            .addStageCostMs("total", uploadStartNs));

                                    if (PushNotifyClient.canSend(room, wxuid, pushMsgTags, "分P上传")) {
                                        message.setAppToken(wxToken);
                                        message.setContentType(Message.CONTENT_TYPE_TEXT);
                                        message.setContent(WX_MSG_FORMAT.formatted("上传成功", room.getUname(), "结束", room.getTitle(),
                                                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")),
                                                part.getFilePath(), part.getStartTime().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")), (int)part.getDuration() / 60, ((float)part.getFileSize() / 1024 / 1024 / 1024), "服务器文件名称\n" + part.getFileName()));
                                        message.setUid(wxuid);
                                        PushNotifyClient.sendParallel(room, message);
                                    }
                                } else {
                                    throw new RuntimeException("合并上传文件失败：" + JSON.toJSONString(completeUploadBean));
                                }

                            } catch (Exception e) {
                                //存在异常
                                TaskUtil.partUploadTask.remove(part.getId());
                                uploadProgressTracker.markFailed(part.getId(), e.getMessage());
                                log.error("[BLR] {}", LogKvs.event("Upload.Part.Failed")
                                        .add("os", OS)
                                        .add("roomId", room.getRoomId())
                                        .add("uname", room.getUname())
                                        .add("partId", part.getId())
                                        .add("historyId", part.getHistoryId())
                                        .add("filePath", filePath)
                                        .add("err", e.getMessage())
                                    .add("ex", e.getClass().getSimpleName())
                                    .addStageCostMs("total", uploadStartNs), e);
                                if (PushNotifyClient.canSend(room, wxuid, pushMsgTags, "分P上传")) {
                                    message.setAppToken(wxToken);
                                    message.setContentType(Message.CONTENT_TYPE_TEXT);
                                    message.setContent(WX_MSG_FORMAT.formatted("上传失败", room.getUname(), "结束", room.getTitle(),
                                            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")),
                                            part.getFilePath(), part.getStartTime().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")), (int)part.getDuration() / 60, ((float)part.getFileSize() / 1024 / 1024 / 1024), e.getMessage()));
                                    message.setUid(wxuid);
                                    PushNotifyClient.sendParallel(room, message);
                                }
                            }
                            } finally {
                                uploadFairShareService.unregisterUploadUser(biliBiliUser.getId(), room.getRoomId(), part.getId(), "KODO_PART");
                            }
                            }
                        }
                    } else {
                        log.info("[BLR] {}", LogKvs.event("Upload.SkipNotNeeded")
                                .add("os", OS)
                                .add("roomId", part.getRoomId())
                                .add("partId", part.getId())
                                .add("historyId", part.getHistoryId()));
                        TaskUtil.partUploadTask.remove(part.getId());
                        return;
                    }
                }

            }
        } catch (Exception e) {
            log.error("[BLR] {}", LogKvs.event("Upload.ServiceError")
                    .add("os", OS)
                    .add("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName())
                    .addStageCostMs("total", uploadStartNs), e);
        } finally {
            TaskUtil.partUploadTask.remove(part.getId());
            uploadProgressTracker.remove(part.getId());
        }

    }

    private Integer resolveProgressPage(RecordHistoryPart part) {
        if (part == null) return null;
        int page = part.getPage();
        if (page > 0) return page;
        try {
            List<RecordHistoryPart> parts = partRepository.findByHistoryIdOrderByStartTimeAsc(part.getHistoryId());
            if (parts != null) {
                for (int i = 0; i < parts.size(); i++) {
                    RecordHistoryPart p = parts.get(i);
                    if (p != null && p.getId() != null && p.getId().equals(part.getId())) {
                        return i + 1;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String resolveUploadHost(PreUploadBean preUploadBean) {
        if (preUploadBean == null || StringUtils.isBlank(preUploadBean.getEndpoint())) {
            return "unknown";
        }
        String endpoint = preUploadBean.getEndpoint().trim();
        String url = endpoint.startsWith("http") ? endpoint : "https:" + endpoint;
        try {
            URI uri = URI.create(url);
            if (StringUtils.isNotBlank(uri.getHost())) {
                return uri.getHost();
            }
        } catch (Exception ignored) {
        }
        return endpoint;
    }
}


