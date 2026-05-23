package top.sshh.bililiverecoder.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.zjiecode.wxpusher.client.bean.Message;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.entity.BiliBiliUser;
import top.sshh.bililiverecoder.entity.MultipartUploadPart;
import top.sshh.bililiverecoder.entity.MultipartUploadSession;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.repo.BiliUserRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.service.LogAnalyzeService;
import top.sshh.bililiverecoder.service.MultipartUploadSessionService;
import top.sshh.bililiverecoder.service.RecordPartUploadService;
import top.sshh.bililiverecoder.service.SystemConfigService;
import top.sshh.bililiverecoder.service.UploadPauseService;
import top.sshh.bililiverecoder.service.UploadServiceFactory;
import top.sshh.bililiverecoder.service.UploadFairShareService;
import top.sshh.bililiverecoder.service.UploadUserSerialScheduler;
import top.sshh.bililiverecoder.util.TaskUtil;
import top.sshh.bililiverecoder.util.UploadEnums;
import top.sshh.bililiverecoder.util.LogKvs;
import top.sshh.bililiverecoder.util.PushNotifyClient;
import top.sshh.bililiverecoder.util.UploadRetryLogPolicy;
import top.sshh.bililiverecoder.util.UploadProgressTracker;
import top.sshh.bililiverecoder.util.retry.UploadRetryBackoffPolicy;
import top.sshh.bililiverecoder.util.bili.Cookie;
import top.sshh.bililiverecoder.util.bili.WebCookie;
import top.sshh.bililiverecoder.util.bili.upload.ChunkUploadRequest;
import top.sshh.bililiverecoder.util.bili.upload.CompleteUploadRequest;
import top.sshh.bililiverecoder.util.bili.upload.LineUploadRequest;
import top.sshh.bililiverecoder.util.bili.upload.MultipartCompleteRequest;
import top.sshh.bililiverecoder.util.bili.upload.MultipartDebugSupport;
import top.sshh.bililiverecoder.util.bili.upload.MultipartInitRequest;
import top.sshh.bililiverecoder.util.bili.upload.MultipartPartRequest;
import top.sshh.bililiverecoder.util.bili.upload.MultipartSessionValidator;
import top.sshh.bililiverecoder.util.bili.upload.PreUploadRequest;
import top.sshh.bililiverecoder.util.bili.upload.SignedUrlChunkUploadRequest;
import top.sshh.bililiverecoder.util.bili.upload.pojo.CompleteUploadBean;
import top.sshh.bililiverecoder.util.bili.upload.pojo.LineUploadBean;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import top.sshh.bililiverecoder.service.RateLimiterService;
import top.sshh.bililiverecoder.util.NettyUploadClient;

@Slf4j
@Service("uposRecordPartBilibiliUploadService")
public class UposRecordPartBilibiliUploadService implements RecordPartUploadService {

    @Autowired
    private CaptchaService captchaService;

    @Value("${server.port:8080}")
    private String serverPort;

    public static final String OS = "upos";

    @Value("${record.work-path}")
    private String workPath;

    @PostConstruct
    public void initWorkPath() {
        workPath = workPath.replaceAll("\\\\\\\\", "\\\\");
        workPath = workPath.replace("\\", "/");
    }

    @Value("${record.wx-push-token}")
    private String wxToken;

    @Value("${record.upload.multipart-enabled:true}")
    private boolean multipartEnabled;

    @Autowired
    private SystemConfigService systemConfigService;

    @Value("${record.upload.probe-version:20250923}")
    private String probeVersion;
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

    @Autowired
    private UploadPauseService uploadPauseService;

    @Autowired
    private MultipartUploadSessionService multipartUploadSessionService;

    private final UploadRetryBackoffPolicy uploadRetryBackoffPolicy = new UploadRetryBackoffPolicy();

    private static final int UPLOAD_RETRY_GIVE_UP = 9999;
    private static final int CHUNK_MAX_RETRY = 200;
    private static final int GLOBAL_CHUNK_FAILURE_FUSE_THRESHOLD = 400;
    private static final int MULTIPART_RETRY_LIMIT = 2;
    private static final long LEGACY_CHUNK_SIZE = 1024L * 1024L * 5L;
    private static final String BROWSER_MULTIPART_PROFILE = "ugcfx/bup";

    private boolean isBrowserMultipartEnabled() {
        try {
            return systemConfigService != null && systemConfigService.isNewUploadFlowEnabled();
        } catch (Exception e) {
            log.warn("[BLR] {}", LogKvs.event("Upload.Multipart.ConfigReadFailed")
                    .add("fallbackProperty", multipartEnabled)
                    .addIfNotBlank("err", e.getMessage()));
            return multipartEnabled;
        }
    }

    @Override
    public void asyncUpload(RecordHistoryPart part) {
        asyncUploadIfNeeded(part);
        }

        @Override
        public boolean asyncUploadIfNeeded(RecordHistoryPart part) {
        RecordHistoryPart loadedPart = partRepository.findById(part.getId()).get();
        if (uploadPauseService.isUploadPaused(loadedPart.getHistoryId(), loadedPart.getId())) {
            log.info("[BLR] {}", LogKvs.event("Upload.Part.SkipPaused")
                    .add("partId", loadedPart.getId())
                    .add("historyId", loadedPart.getHistoryId())
                    .add("roomId", loadedPart.getRoomId()));
            return false;
        }
        log.info("[BLR] {}", LogKvs.event("Upload.Part.AsyncStart")
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
        RecordHistory pauseHistory = part.getHistoryId() == null ? null : historyRepository.findById(part.getHistoryId()).orElse(null);
        if (uploadPauseService.isUploadPaused(pauseHistory, part)) {
            uploadProgressTracker.markPaused(part.getId(), uploadPauseService.pauseMessage(pauseHistory, part));
            log.info("[BLR] {}", LogKvs.event("Upload.Part.SkipPaused")
                    .add("partId", part.getId())
                    .add("historyId", part.getHistoryId())
                    .add("roomId", part.getRoomId()));
            return;
        }
        synchronized (TaskUtil.partUploadTask) {
            Thread thread = TaskUtil.partUploadTask.get(part.getId());
            if (thread != null && thread != Thread.currentThread()) {
                log.info("[BLR] {}", LogKvs.event("Upload.Part.AlreadyUploading")
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
                                .add("partId", part.getId())
                                .add("historyId", part.getHistoryId())
                                .add("roomId", part.getRoomId())
                                .add("filePath", filePath));
                        if (part.getUploadRetryCount() < MULTIPART_RETRY_LIMIT) {
                            part.setUploadRetryCount(part.getUploadRetryCount() + 1);
                            partRepository.save(part);
                            scheduleRetryEnqueue(room, part, filePath, 5000L, "FILE_MISSING");
                            log.info("[BLR] {}", LogKvs.event("Upload.Part.RetryScheduled")
                                    .add("partId", part.getId())
                                    .add("historyId", part.getHistoryId())
                                    .add("roomId", part.getRoomId())
                                    .add("retry", part.getUploadRetryCount())
                                    .add("nextAttemptMultipart", part.getUploadRetryCount() < MULTIPART_RETRY_LIMIT)
                                    .add("maxRetry", MULTIPART_RETRY_LIMIT)
                                    .add("filePath", filePath));
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
                                            part.getFilePath(), part.getStartTime().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")), (int)part.getDuration() / 60, ((float)part.getFileSize() / 1024 / 1024 / 1024), biliBiliUser.getUname() + "登录已过期，请重新登录\n" + "线路：" + uploadEnums.getLine()));
                                    message.setUid(wxuid);
                                    PushNotifyClient.sendParallel(room, message);
                                }
                                throw new RuntimeException("{}登录已过期，请重新登录! " + biliBiliUser.getUname());
                            }
                            // 登录验证结束
                            // 按 uploadUserId 串行由调度器保证，这里不再阻塞线程等待锁
                            {
                            uploadFairShareService.registerUploadUser(biliBiliUser.getId(), room.getRoomId(), part.getId(), "UPOS_PART");
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
                            if (StringUtils.isNotBlank(probeVersion)) {
                                preParams.put("probe_version", probeVersion);
                            }
                            preParams.put("name", uploadFile.getName());
                            preParams.put("size", String.valueOf(uploadFile.length()));
                            long fileSize = uploadFile.length();
                            long chunkSize = LEGACY_CHUNK_SIZE;
                            long chunkNum = (long)Math.ceil((double)fileSize / chunkSize);
                            PreUploadRequest preuploadRequest = new PreUploadRequest(webCookie, preParams);
                            preuploadRequest.setLineQuery(uploadEnums.getLineQuery());
                            PreUploadBean preUploadBean;
                            LineUploadBean uploadBean = null;
                            int multipartRetryCount = part.getUploadRetryCount();
                            final int finalMultipartRetryCount = multipartRetryCount;
                            boolean configuredMultipartEnabled = isBrowserMultipartEnabled();
                            boolean useMultipartFlow = configuredMultipartEnabled && multipartRetryCount < MULTIPART_RETRY_LIMIT;
                            String uploadFlowFallbackReason = configuredMultipartEnabled && multipartRetryCount >= MULTIPART_RETRY_LIMIT
                                    ? "multipart retry limit reached, using legacy"
                                    : null;
                            String multipartUploadId = null;
                            String multipartUri = null;
                            String multipartToken = null;
                            long multipartBizId = 0L;
                            String multipartProfile = BROWSER_MULTIPART_PROFILE;
                            String multipartMetaUposUri = null;
                            String multipartSessionDigest = null;
                            MultipartUploadSession multipartSession = null;
                            Map<Integer, String> multipartEtags = new ConcurrentHashMap<>();
                            Map<Integer, String> multipartSignedUploadIds = new ConcurrentHashMap<>();
                            Map<Integer, Integer> multipartSignedPartNumbers = new ConcurrentHashMap<>();
                            try {
                                do {
                                    preUploadBean = preuploadRequest.getPojo();
                                    if (preUploadBean == null || preUploadBean.getOK() == 0) {
                                            log.warn("[BLR] {}", LogKvs.event("Upload.PreUpload.Failed")
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
                                                } else {
                                                    log.warn("[BLR] {}", LogKvs.event("Upload.Captcha.Timeout")
                                                            .add("roomId", room.getRoomId())
                                                            .add("uname", room.getUname())
                                                            .add("partId", part.getId())
                                                            .add("historyId", part.getHistoryId())
                                                            .add("fileName", uploadFile.getName())
                                                            .add("sleepMs", 600000));
                                                    try {
                                                        Thread.sleep(600000L);
                                                    } catch (InterruptedException e) {
                                                        log.warn("[BLR] {}", LogKvs.event("Upload.Captcha.TimeoutSleepInterrupted")
                                                                .add("roomId", room.getRoomId())
                                                                .add("uname", room.getUname())
                                                                .add("partId", part.getId())
                                                                .add("historyId", part.getHistoryId())
                                                                .add("fileName", uploadFile.getName())
                                                                .add("sleepMs", 600000)
                                                                .addIfNotBlank("err", e.getMessage())
                                                                .add("ex", e.getClass().getSimpleName()), e);
                                                        Thread.currentThread().interrupt();
                                                    }
                                                }
                                            } else {
                                            log.warn("[BLR] {}", LogKvs.event("Upload.RateLimit.Wait")
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
                                    } else {
                                        // 同步更新
                                        //                                    chunkSize = preUploadBean.getChunk_size();
                                        //                                    chunkNum = (long) Math.ceil((double) fileSize / chunkSize);
                                        // 如果返回的线路不是指定线路，优先选择 upcdn 系列节点（速度通常更稳定）
                                        String expectedToken = "upcdn" + uploadEnums.getCdn();
                                        String[] endpoints = preUploadBean.getEndpoints() == null ? new String[0] : preUploadBean.getEndpoints();
                                        String assignedEndpoint = StringUtils.defaultString(preUploadBean.getEndpoint());
                                        String selectedEndpoint = assignedEndpoint;
                                        boolean isFallback = !assignedEndpoint.contains(expectedToken);
                                        if (isFallback) {
                                            // 先尝试找用户指定的 CDN
                                            for (String endpoint : endpoints) {
                                                if (StringUtils.isNotBlank(endpoint) && endpoint.contains(expectedToken)) {
                                                    selectedEndpoint = endpoint;
                                                    isFallback = false;
                                                    break;
                                                }
                                            }
                                            // 指定 CDN 不可用时，优先使用任意 upcdn 的正规节点（不是 esheep）
                                            if (isFallback) {
                                                for (String endpoint : endpoints) {
                                                    if (StringUtils.isNotBlank(endpoint)
                                                            && endpoint.contains("upcdn")
                                                            && !endpoint.contains("esheep.com")) {
                                                        selectedEndpoint = endpoint;
                                                        log.info("[BLR] {}", LogKvs.event("Upload.PreUpload.PreferUpcdn")
                                                                .add("roomId", room.getRoomId())
                                                                .add("historyId", part.getHistoryId())
                                                                .add("expectedCdn", uploadEnums.getCdn())
                                                                .add("assignedEndpoint", assignedEndpoint)
                                                                .add("newEndpoint", endpoint));
                                                        break;
                                                    }
                                                }
                                            }
                                            // 如果仍是 fallback 且原始分配为 esheep，记录规避尝试结果（可能无可用替换）
                                            if (assignedEndpoint.contains("esheep.com") && !assignedEndpoint.equals(selectedEndpoint)) {
                                                log.info("[BLR] {}", LogKvs.event("Upload.PreUpload.AvoidEsheep")
                                                        .add("roomId", room.getRoomId())
                                                        .add("historyId", part.getHistoryId())
                                                        .add("avoidedEndpoint", assignedEndpoint)
                                                        .add("newEndpoint", selectedEndpoint));
                                            }
                                            preUploadBean.setEndpoint(selectedEndpoint);
                                        }
                                        if (isFallback) {
                                            log.warn("[BLR] {}", LogKvs.event("Upload.PreUpload.Fallback")
                                                    .add("roomId", room.getRoomId())
                                                    .add("uname", room.getUname())
                                                    .add("partId", part.getId())
                                                    .add("historyId", part.getHistoryId())
                                                    .add("expectedCdn", uploadEnums.getCdn())
                                                    .add("assignedEndpoint", preUploadBean.getEndpoint())
                                                    .add("endpointsList", Arrays.toString(endpoints)));
                                        }
                                        String selectedHost = resolveUploadHost(preUploadBean);
                                        if (isLikelyEdgeOrProxyHost(selectedHost)) {
                                            log.warn("[BLR] {}", LogKvs.event("Upload.PreUpload.EdgeProxy")
                                                    .add("roomId", room.getRoomId())
                                                    .add("uname", room.getUname())
                                                    .add("partId", part.getId())
                                                    .add("historyId", part.getHistoryId())
                                                    .add("expectedCdn", uploadEnums.getCdn())
                                                    .add("endpoint", preUploadBean.getEndpoint())
                                                    .add("host", selectedHost)
                                                    .add("endpointsList", Arrays.toString(endpoints)));
                                        }
                                        
                                        if (useMultipartFlow) {
                                            multipartUploadId = null;
                                            multipartUri = preUploadBean.getUpos_uri();
                                            Optional<MultipartUploadSession> reusableSession =
                                                    multipartUploadSessionService.findReusableSession(part.getId(), fileSize);
                                            if (reusableSession.isPresent()) {
                                                multipartSession = multipartUploadSessionService.activate(reusableSession.get());
                                                multipartUploadId = multipartSession.getUploadId();
                                                multipartToken = multipartSession.getUploadToken();
                                                multipartUri = multipartSession.getUri();
                                                multipartProfile = multipartSession.getProfile();
                                                multipartBizId = multipartSession.getBizId() == null ? preUploadBean.getBiz_id() : multipartSession.getBizId();
                                                preUploadBean.setBiz_id(multipartBizId);
                                                if (multipartSession.getChunkSize() != null && multipartSession.getChunkSize() > 0) {
                                                    chunkSize = multipartSession.getChunkSize();
                                                    preUploadBean.setChunk_size(chunkSize);
                                                }
                                                chunkNum = multipartSession.getChunkTotal() == null || multipartSession.getChunkTotal() <= 0
                                                        ? (long) Math.ceil((double) fileSize / chunkSize)
                                                        : multipartSession.getChunkTotal();
                                                multipartSessionDigest = buildMultipartSessionDigest(
                                                        multipartUploadId,
                                                        multipartUri,
                                                        multipartToken,
                                                        multipartBizId,
                                                        multipartProfile
                                                );
                                                for (MultipartUploadPart savedPart : multipartUploadSessionService.listCompletedParts(multipartSession)) {
                                                    if (savedPart.getPartNumber() != null && StringUtils.isNotBlank(savedPart.getEtag())) {
                                                        multipartEtags.put(savedPart.getPartNumber(), savedPart.getEtag());
                                                    }
                                                }
                                                log.info("[BLR] {}", LogKvs.event("Upload.Multipart.ResumeSession")
                                                        .add("roomId", room.getRoomId())
                                                        .add("uname", room.getUname())
                                                        .add("partId", part.getId())
                                                        .add("historyId", part.getHistoryId())
                                                        .add("doneParts", multipartEtags.size())
                                                        .add("chunkNum", chunkNum)
                                                        .addIfNotBlank("sessionDigest", multipartSessionDigest));
                                            } else {
                                            try {
                                                MultipartInitRequest multipartInitRequest = new MultipartInitRequest(webCookie);
                                                MultipartInitRequest.MultipartInitInfo initInfo = multipartInitRequest.init(
                                                        multipartUploadId,
                                                        uploadFile.getName(),
                                                        multipartUri,
                                                        multipartProfile,
                                                    preUploadBean.getBiz_id(),
                                                    fileSize
                                                );
                                                multipartUploadId = initInfo.getUploadId();
                                                multipartToken = initInfo.getUploadToken();
                                                multipartUri = initInfo.getUri();
                                                multipartProfile = initInfo.getProfile();
                                                multipartBizId = initInfo.getBizId();
                                                multipartMetaUposUri = initInfo.getMetaUposUri();
                                                preUploadBean.setBiz_id(multipartBizId);
                                                multipartSessionDigest = buildMultipartSessionDigest(
                                                        multipartUploadId,
                                                        multipartUri,
                                                        multipartToken,
                                                        multipartBizId,
                                                        multipartProfile
                                                );
                                                log.info("[BLR] {}", LogKvs.event("Upload.Multipart.FlowDecision")
                                                    .add("roomId", room.getRoomId())
                                                    .add("uname", room.getUname())
                                                    .add("partId", part.getId())
                                                    .add("historyId", part.getHistoryId())
                                                    .add("retryCount", multipartRetryCount)
                                                    .add("retryLimit", MULTIPART_RETRY_LIMIT)
                                                    .add("multipartEnabled", configuredMultipartEnabled)
                                                    .add("useMultipartFlow", useMultipartFlow)
                                                    .add("nextRetryMultipart", multipartRetryCount + 1 < MULTIPART_RETRY_LIMIT)
                                                    .addIfNotBlank("sessionDigest", multipartSessionDigest));
                                                log.info("[BLR] {}", LogKvs.event("Upload.Multipart.SessionLocked")
                                                        .add("roomId", room.getRoomId())
                                                        .add("uname", room.getUname())
                                                        .add("partId", part.getId())
                                                        .add("historyId", part.getHistoryId())
                                                        .add("bizId", multipartBizId)
                                                        .addIfNotBlank("profile", multipartProfile)
                                                    .add("retryCount", multipartRetryCount)
                                                    .add("retryLimit", MULTIPART_RETRY_LIMIT)
                                                    .add("useMultipartFlow", useMultipartFlow)
                                                        .addIfNotBlank("sessionDigest", multipartSessionDigest));
                                                MultipartSessionValidator.MetaBucketCheck metaCheck =
                                                        MultipartSessionValidator.checkMetaBucket(multipartUri, multipartMetaUposUri);
                                                if (metaCheck.isComparable() && !metaCheck.isConsistent()) {
                                                    uploadFlowFallbackReason = "multipart init meta bucket mismatch";
                                                    useMultipartFlow = false;
                                                    log.warn("[BLR] {}", LogKvs.event("Upload.Multipart.InitFallback")
                                                            .add("roomId", room.getRoomId())
                                                            .add("uname", room.getUname())
                                                            .add("partId", part.getId())
                                                            .add("historyId", part.getHistoryId())
                                                            .add("reason", "meta bucket mismatch")
                                                            .add("uposBucket", metaCheck.getUposBucket())
                                                            .add("expectedMetaBucket", metaCheck.getExpectedMetaBucket())
                                                            .add("actualMetaBucket", metaCheck.getActualMetaBucket())
                                                            .addIfNotBlank("metaUposUri", multipartMetaUposUri)
                                                            .addIfNotBlank("zipUrl", preUploadBean.getUpos_uri()));
                                                }
                                                if (useMultipartFlow) {
                                                    long multipartChunkSize = initInfo.getChunkSize();
                                                    if (multipartChunkSize > 0) {
                                                        chunkSize = multipartChunkSize;
                                                        chunkNum = (long) Math.ceil((double) fileSize / chunkSize);
                                                        preUploadBean.setChunk_size(chunkSize);
                                                    }
                                                    if (initInfo.getThreads() > 0) {
                                                        preUploadBean.setThreads(String.valueOf(initInfo.getThreads()));
                                                    }
                                                    if (initInfo.getTimeout() > 0) {
                                                        preUploadBean.setTimeout(String.valueOf(initInfo.getTimeout()));
                                                    }
                                                    multipartSession = multipartUploadSessionService.createSession(
                                                            part,
                                                            initInfo,
                                                            chunkSize,
                                                            (int) chunkNum,
                                                            fileSize
                                                    );
                                                }
                                            } catch (Exception initEx) {
                                                uploadFlowFallbackReason = "multipart init failed";
                                                useMultipartFlow = false;
                                                LogKvs kvs = LogKvs.event("Upload.Multipart.InitFallback")
                                                    .add("roomId", room.getRoomId())
                                                    .add("uname", room.getUname())
                                                    .add("partId", part.getId())
                                                    .add("historyId", part.getHistoryId())
                                                    .add("reason", "multipart init failed")
                                                    .addIfNotBlank("err", initEx.getMessage());
                                                if (initEx instanceof MultipartInitRequest.MultipartInitDiagnosticException) {
                                                    MultipartInitRequest.MultipartInitDiagnosticException dx =
                                                        (MultipartInitRequest.MultipartInitDiagnosticException) initEx;
                                                    kvs.add("httpCode", dx.getHttpCode())
                                                        .add("bizCode", dx.getBizCode())
                                                        .addIfNotBlank("bizMessage", dx.getBizMessage())
                                                        .addIfNotBlank("uploadId", dx.getUploadId())
                                                        .addIfNotBlank("filename", dx.getFilename())
                                                        .addIfNotBlank("zipUrl", dx.getZipUrl())
                                                        .addIfNotBlank("rootKeys", dx.getRootKeys())
                                                        .addIfNotBlank("dataKeys", dx.getDataKeys())
                                                        .add("hasUploadToken", dx.isHasUploadToken())
                                                        .add("hasUptoken", dx.isHasUptoken())
                                                        .add("hasUri", dx.isHasUri())
                                                        .add("hasUposUri", dx.isHasUposUri())
                                                        .addIfNotBlank("missingFields", dx.getMissingFields())
                                                        .addIfNotBlank("responseSnippet", dx.getResponseSnippet());
                                                }
                                                kvs.add("preHasAuth", StringUtils.isNotBlank(preUploadBean.getAuth()))
                                                        .add("preHasUptoken", StringUtils.isNotBlank(preUploadBean.getRawUptoken()))
                                                        .add("preHasUposUri", StringUtils.isNotBlank(preUploadBean.getUpos_uri()));
                                                log.warn("[BLR] {}", kvs);
                                            }
                                            }
                                            if (StringUtils.isBlank(multipartUri) || StringUtils.isBlank(multipartToken)) {
                                                uploadFlowFallbackReason = "multipart missing uri or upload_token";
                                                useMultipartFlow = false;
                                                log.warn("[BLR] {}", LogKvs.event("Upload.Multipart.PrepareFallback")
                                                        .add("roomId", room.getRoomId())
                                                        .add("uname", room.getUname())
                                                        .add("partId", part.getId())
                                                        .add("historyId", part.getHistoryId())
                                                        .add("reason", "missing uri or upload_token"));
                                            }
                                        }

                                        if (!useMultipartFlow) {
                                            LineUploadRequest uploadRequest = new LineUploadRequest(webCookie, preUploadBean);
                                            uploadBean = uploadRequest.getPojo();
                                        }
                                        log.debug("[BLR] {}", LogKvs.event("Upload.PreUpload.Success")
                                                .add("roomId", room.getRoomId())
                                                .add("uname", room.getUname())
                                                .add("partId", part.getId())
                                                .add("historyId", part.getHistoryId())
                                                .add("fileName", uploadFile.getName())
                                                .add("uploadId", uploadBean != null ? uploadBean.getUpload_id() : null)
                                                .add("multipartEnabled", configuredMultipartEnabled)
                                                .add("multipartActive", useMultipartFlow)
                                                .add("chunkSize", chunkSize)
                                                .add("chunkNum", chunkNum)
                                                .add("endpoint", preUploadBean != null ? preUploadBean.getEndpoint() : null));
                                    }
                                } while (preUploadBean.getOK() == 0);
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
                            // 分段上传
                            final long effectiveChunkSize = chunkSize;
                            final long effectiveChunkNum = chunkNum;
                            AtomicInteger upCount = new AtomicInteger(0);
                            AtomicInteger globalFailCount = new AtomicInteger(0);
                            java.util.concurrent.atomic.AtomicBoolean globalFuseOpen = new java.util.concurrent.atomic.AtomicBoolean(false);
                            final Long partId = part.getId();
                            final Long historyId = part.getHistoryId();
                            final Integer partPage = resolveProgressPage(part);
                            uploadProgressTracker.start(
                                    partId,
                                    historyId,
                                    partPage,
                                    (int) effectiveChunkNum,
                                    effectiveChunkSize,
                                    fileSize,
                                    useMultipartFlow ? "MULTIPART" : "LEGACY"
                            );
                            if (useMultipartFlow && multipartEtags.size() > 0) {
                                upCount.set(multipartEtags.size());
                                uploadProgressTracker.updateChunkDone(partId, historyId, partPage, upCount.get(), (int) effectiveChunkNum);
                            }
                            java.util.concurrent.atomic.AtomicReference<String> gatewayError = new java.util.concurrent.atomic.AtomicReference<>(null);
                            java.util.concurrent.atomic.AtomicReference<String> globalFuseReason = new java.util.concurrent.atomic.AtomicReference<>(null);
                            List<Runnable> runnableList = new ArrayList<>();
                            for (int i = 0; i < effectiveChunkNum; i++) {
                                long finalI = i;
                                LineUploadBean finalUploadBean = uploadBean;
                                PreUploadBean finalPreUploadBean = preUploadBean;
                                boolean finalUseMultipartFlow = useMultipartFlow;
                                String finalMultipartUploadId = multipartUploadId;
                                String finalMultipartUri = multipartUri;
                                String finalMultipartToken = multipartToken;
                                String finalMultipartSessionDigest = multipartSessionDigest;
                                MultipartUploadSession finalMultipartSession = multipartSession;
                                Map<Integer, String> finalMultipartEtags = multipartEtags;
                                Map<Integer, String> finalMultipartSignedUploadIds = multipartSignedUploadIds;
                                Map<Integer, Integer> finalMultipartSignedPartNumbers = multipartSignedPartNumbers;
                                Runnable runnable = () -> {
                                    try {
                                        int partNumber = (int) (finalI + 1);
                                        if (finalUseMultipartFlow && finalMultipartEtags.containsKey(partNumber)) {
                                            log.debug("[BLR] {}", LogKvs.event("Upload.Multipart.Part.SkipDone")
                                                    .add("partId", partId)
                                                    .add("historyId", historyId)
                                                    .add("partNumber", partNumber));
                                            return;
                                        }
                                        if (shouldPauseUpload(historyId, partId)) {
                                            globalFuseReason.compareAndSet(null, "UPLOAD_PAUSED");
                                            globalFuseOpen.set(true);
                                            return;
                                        }
                                        int chunkRetryCount = 0;
                                        while (!globalFuseOpen.get() && chunkRetryCount < CHUNK_MAX_RETRY) {
                                            if (shouldPauseUpload(historyId, partId)) {
                                                globalFuseReason.compareAndSet(null, "UPLOAD_PAUSED");
                                                globalFuseOpen.set(true);
                                                break;
                                            }
                                            if (gatewayError.get() != null) {
                                                globalFuseReason.compareAndSet(null, "UPLOAD_GATEWAY_ERROR");
                                                globalFuseOpen.set(true);
                                                break;
                                            }
                                            try {
                                                // 上传
                                                long endSize = (finalI + 1) * effectiveChunkSize;
                                                long finalChunkSize = effectiveChunkSize;
                                                long startSize = finalI * finalChunkSize;
                                                if (endSize > fileSize) {
                                                    endSize = fileSize;
                                                    finalChunkSize = fileSize - startSize;
                                                }

                                                try (RandomAccessFile randomAccessFile = new RandomAccessFile(filePath, "r")) {
                                                    if (finalUseMultipartFlow) {
                                                        MultipartPartRequest multipartPartRequest = new MultipartPartRequest(webCookie);
                                                        MultipartPartRequest.MultipartSignedReq signedReq = multipartPartRequest.getSignedReq(
                                                                finalMultipartUploadId,
                                                                finalMultipartUri,
                                                                finalMultipartToken,
                                                                (int) (finalI + 1),
                                                                uploadEnums.getCdn()
                                                        );
                                                        int timeoutSeconds = 1200;
                                                        if (StringUtils.isNotBlank(finalPreUploadBean.getTimeout())) {
                                                            timeoutSeconds = Integer.parseInt(finalPreUploadBean.getTimeout());
                                                        }
                                                        SignedUrlChunkUploadRequest signedUrlChunkUploadRequest = new SignedUrlChunkUploadRequest();
                                                        String etag = signedUrlChunkUploadRequest.upload(
                                                                signedReq.getUrl(),
                                                                randomAccessFile,
                                                                startSize,
                                                                endSize,
                                                                timeoutSeconds,
                                                                () -> shouldPauseUpload(historyId, partId)
                                                        );
                                                        finalMultipartEtags.put(partNumber, etag);
                                                        if (finalMultipartSession != null) {
                                                            multipartUploadSessionService.saveCompletedPart(
                                                                    finalMultipartSession,
                                                                    partNumber,
                                                                    etag,
                                                                    startSize,
                                                                    endSize
                                                            );
                                                        }
                                                        String signedUploadId = MultipartDebugSupport.uploadIdFromUrl(signedReq.getUrl());
                                                        Integer signedPartNumber = MultipartDebugSupport.partNumberFromUrl(signedReq.getUrl());
                                                        finalMultipartSignedUploadIds.put(partNumber, signedUploadId);
                                                        finalMultipartSignedPartNumbers.put(partNumber, signedPartNumber);
                                                        if (StringUtils.isNotBlank(finalMultipartUploadId)
                                                                && StringUtils.isNotBlank(signedUploadId)
                                                                && !StringUtils.equals(finalMultipartUploadId, signedUploadId)) {
                                                            log.warn("[BLR] {}", LogKvs.event("Upload.Multipart.Part.DiagnosticUploadIdMismatch")
                                                                    .add("roomId", room.getRoomId())
                                                                    .add("uname", room.getUname())
                                                                    .add("partId", partId)
                                                                    .add("historyId", historyId)
                                                                    .add("chunkIndex", finalI)
                                                                    .add("partNumber", partNumber)
                                                                    .add("diagnosticUploadId", finalMultipartUploadId)
                                                                    .add("signedUrlUploadId", signedUploadId)
                                                                    .addIfNotBlank("signedPartNumber", signedPartNumber == null ? null : String.valueOf(signedPartNumber))
                                                                    .addIfNotBlank("sessionDigest", finalMultipartSessionDigest)
                                                                    .add("retryCount", finalMultipartRetryCount)
                                                                    .add("retryLimit", MULTIPART_RETRY_LIMIT)
                                                                    .add("useMultipartFlow", finalUseMultipartFlow));
                                                        }
                                                    } else {
                                                        Map<String, String> chunkParams = new HashMap<>();
                                                        chunkParams.put("partNumber", String.valueOf(finalI + 1));
                                                        chunkParams.put("uploadId", finalUploadBean.getUpload_id());
                                                        chunkParams.put("chunk", String.valueOf(finalI));
                                                        chunkParams.put("chunks", String.valueOf(effectiveChunkNum));
                                                        chunkParams.put("size", String.valueOf(finalChunkSize));
                                                        chunkParams.put("start", String.valueOf(startSize));
                                                        chunkParams.put("end", String.valueOf(endSize));
                                                        chunkParams.put("total", String.valueOf(fileSize));
                                                        ChunkUploadRequest chunkUploadRequest = new ChunkUploadRequest(
                                                                finalPreUploadBean,
                                                                chunkParams,
                                                                randomAccessFile,
                                                                () -> shouldPauseUpload(historyId, partId)
                                                        );
                                                        chunkUploadRequest.getPage();
                                                    }
                                                } catch (FileNotFoundException fileNotFoundException) {
                                                    globalFuseReason.compareAndSet(null, "UPLOAD_FILE_NOT_FOUND");
                                                    globalFuseOpen.set(true);
                                                    log.error("[BLR] {}", LogKvs.event("Upload.Chunk.FileMissing")
                                                            .add("roomId", room.getRoomId())
                                                            .add("uname", room.getUname())
                                                            .add("partId", partId)
                                                            .add("historyId", historyId)
                                                            .add("filePath", filePath));
                                                    break;
                                                }
                                                int count = upCount.incrementAndGet();
                                                uploadProgressTracker.updateChunkDone(partId, historyId, partPage, count, (int) effectiveChunkNum);
                                                log.debug("[BLR] {}", LogKvs.event("Upload.Chunk.Progress")
                                                        .add("roomId", room.getRoomId())
                                                        .add("title", room.getTitle())
                                                        .add("partId", partId)
                                                        .add("historyId", historyId)
                                                        .add("chunkIndex", finalI)
                                                        .add("done", count)
                                                        .add("total", effectiveChunkNum)
                                                        .add("thread", Thread.currentThread().getName()));
                                                break;
                                            } catch (Exception e) {
                                                if (shouldPauseUpload(historyId, partId) || isUploadChunkCancelled(e)) {
                                                    globalFuseReason.compareAndSet(null, "UPLOAD_PAUSED");
                                                    globalFuseOpen.set(true);
                                                    break;
                                                }
                                                if (finalUseMultipartFlow && finalMultipartSession != null && isMultipartSessionInvalid(e)) {
                                                    String invalidReason = StringUtils.defaultIfBlank(e.getMessage(), "multipart session invalid");
                                                    multipartUploadSessionService.markExpired(finalMultipartSession, invalidReason);
                                                    globalFuseReason.compareAndSet(null, "MULTIPART_SESSION_EXPIRED");
                                                    globalFuseOpen.set(true);
                                                    log.warn("[BLR] {}", LogKvs.event("Upload.Multipart.SessionExpired")
                                                            .add("roomId", room.getRoomId())
                                                            .add("uname", room.getUname())
                                                            .add("partId", partId)
                                                            .add("historyId", historyId)
                                                            .add("chunkIndex", finalI)
                                                            .add("partNumber", partNumber)
                                                            .addIfNotBlank("reason", invalidReason)
                                                            .addIfNotBlank("sessionDigest", finalMultipartSessionDigest));
                                                    break;
                                                }
                                                chunkRetryCount++;
                                                int globalRetryCount = globalFailCount.incrementAndGet();
                                                UploadRetryBackoffPolicy.BackoffDecision backoffDecision =
                                                        uploadRetryBackoffPolicy.nextDecision(chunkRetryCount, e, e.getMessage());
                                                String uploadHost = resolveUploadHost(finalPreUploadBean);
                                                if ("GATEWAY_5XX".equals(backoffDecision.retryCategory())) {
                                                    UploadRetryLogPolicy.LogDecision logDecision = UploadRetryLogPolicy.recoverable(
                                                            "Upload.GatewayErrorPause:" + historyId + ":" + partId + ":" + uploadHost);
                                                    LogKvs gatewayLog = LogKvs.event("Upload.GatewayErrorPause")
                                                            .add("roomId", room.getRoomId())
                                                            .add("uname", room.getUname())
                                                            .add("partId", partId)
                                                            .add("historyId", historyId)
                                                            .add("retryCategory", backoffDecision.retryCategory())
                                                            .add("host", uploadHost)
                                                            .add("recoverableCount", logDecision.count())
                                                            .add("warnThreshold", logDecision.warnThreshold())
                                                            .add("err", e.getMessage())
                                                            .add("ex", e.getClass().getSimpleName());
                                                    if (logDecision.warn()) {
                                                        log.warn("[BLR] {}", gatewayLog);
                                                    } else {
                                                        log.info("[BLR] {}", gatewayLog);
                                                    }
                                                    gatewayError.set(e.getMessage());
                                                    globalFuseReason.compareAndSet(null, "UPLOAD_GATEWAY_ERROR");
                                                    globalFuseOpen.set(true);
                                                    break;
                                                }
                                                long backoffMs = backoffDecision.delayMs();
                                                uploadProgressTracker.markRetryWait(partId, e.getMessage(), chunkRetryCount, backoffMs);
                                                LogKvs chunkErrorLog = LogKvs.event("Upload.Chunk.Error")
                                                        .add("roomId", room.getRoomId())
                                                        .add("title", room.getTitle())
                                                    .add("partId", partId)
                                                    .add("historyId", historyId)
                                                        .add("chunkIndex", finalI)
                                                        .add("chunkSize", effectiveChunkSize)
                                                        .add("start", finalI * effectiveChunkSize)
                                                        .add("end", (finalI + 1) * effectiveChunkSize)
                                                        .add("chunkRetryCount", chunkRetryCount)
                                                        .add("chunkRetryMax", CHUNK_MAX_RETRY)
                                                        .add("globalFailCount", globalRetryCount)
                                                        .add("globalFuseThreshold", GLOBAL_CHUNK_FAILURE_FUSE_THRESHOLD)
                                                        .add("retryCategory", backoffDecision.retryCategory())
                                                        .add("host", uploadHost)
                                                        .add("backoffMs", backoffMs)
                                                        .add("err", e.getMessage())
                                                        .add("ex", e.getClass().getSimpleName());
                                                if (UploadRetryLogPolicy.shouldWarn(chunkRetryCount, globalRetryCount)) {
                                                    log.warn("[BLR] {}", chunkErrorLog);
                                                } else {
                                                    log.debug("[BLR] {}", chunkErrorLog);
                                                }
                                                if (globalRetryCount >= GLOBAL_CHUNK_FAILURE_FUSE_THRESHOLD) {
                                                    globalFuseReason.compareAndSet(null, "GLOBAL_CHUNK_FAILURE_THRESHOLD_REACHED");
                                                    globalFuseOpen.set(true);
                                                    log.error("[BLR] {}", LogKvs.event("Upload.Chunk.GlobalFuseOpen")
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
                                        part.getFilePath(), part.getStartTime().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")), (int) part.getDuration() / 60, ((float) part.getFileSize() / 1024 / 1024 / 1024), biliBiliUser.getUname() + "\n线路：" + uploadEnums.getLine()));
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

                            if (gatewayError.get() != null) {
                                throw new RuntimeException("UPLOAD_GATEWAY_ERROR:" + gatewayError.get());
                            }

                            if ("UPLOAD_PAUSED".equals(globalFuseReason.get()) || shouldPauseUpload(historyId, partId)) {
                                pauseUpload(historyId, partId, multipartSession);
                                return;
                            }

                            if (globalFuseOpen.get() || upCount.get() < effectiveChunkNum) {
                                part = partRepository.findById(part.getId()).get();
                                part.setUpload(false);
                                part.setUploadRetryCount(part.getUploadRetryCount() + 1);
                                part = partRepository.save(part);
                                String chunkFailReason = StringUtils.defaultIfBlank(globalFuseReason.get(), "chunk upload failed");
                                uploadProgressTracker.markFailed(partId, chunkFailReason);
                                log.error("[BLR] {}", LogKvs.event("Upload.Chunk.AllFailed")
                                        .add("roomId", room.getRoomId())
                                        .add("uname", room.getUname())
                                        .add("partId", partId)
                                        .add("historyId", historyId)
                                        .add("doneChunkCount", upCount.get())
                                        .add("totalChunkCount", effectiveChunkNum)
                                        .add("globalFailCount", globalFailCount.get())
                                        .add("globalFuseOpen", globalFuseOpen.get())
                                        .add("reason", chunkFailReason));
                                if (part.getUploadRetryCount() < MULTIPART_RETRY_LIMIT) {
                                    scheduleRetryEnqueue(room, part, filePath, 5000L, "CHUNK_UPLOAD_FAILED");
                                    log.info("[BLR] {}", LogKvs.event("Upload.Part.RetryScheduled")
                                            .add("roomId", room.getRoomId())
                                            .add("uname", room.getUname())
                                            .add("partId", part.getId())
                                            .add("historyId", part.getHistoryId())
                                            .add("retry", part.getUploadRetryCount())
                                            .add("nextAttemptMultipart", part.getUploadRetryCount() < MULTIPART_RETRY_LIMIT)
                                            .add("maxRetry", MULTIPART_RETRY_LIMIT)
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
                                            part.getFilePath(), part.getStartTime().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")), (int)part.getDuration() / 60, ((float)part.getFileSize() / 1024 / 1024 / 1024), biliBiliUser.getUname() + "并发上传失败，存在异常\n" + "线路：" + uploadEnums.getLine()));
                                    message.setUid(wxuid);
                                    PushNotifyClient.sendParallel(room, message);
                                }
                                throw new RuntimeException(part.getFilePath() + "===并发上传失败，存在异常");
                            }
                            //通知服务器上传完成
                            try {
                                if (shouldPauseUpload(historyId, partId)) {
                                    pauseUpload(historyId, partId, multipartSession);
                                    return;
                                }
                                boolean completeSuccess = false;
                                String completeResponse = null;
                                String serverFileName = resolveServerFileName(preUploadBean, uploadBean, uploadFile.getName());
                                if (useMultipartFlow) {
                                    serverFileName = resolveFileNameFromUposUri(multipartUri, serverFileName);
                                }

                                if (useMultipartFlow) {
                                    if (multipartEtags.size() < effectiveChunkNum) {
                                        throw new RuntimeException("multipart etag missing, expected=" + effectiveChunkNum + ", actual=" + multipartEtags.size());
                                    }

                                    List<Map<String, Object>> partPayload = new ArrayList<>((int) effectiveChunkNum);
                                    for (int i = 1; i <= effectiveChunkNum; i++) {
                                        String etag = multipartEtags.get(i);
                                        if (StringUtils.isBlank(etag)) {
                                            throw new RuntimeException("multipart etag missing for part " + i);
                                        }
                                        Map<String, Object> partMap = new LinkedHashMap<>(2);
                                        partMap.put("part_number", i);
                                        partMap.put("etag", etag);
                                        partPayload.add(partMap);
                                    }

                                    String completeProfile = resolveMultipartProfile(preUploadBean, multipartProfile, multipartUri);
                                    String completeSessionDigest = buildMultipartSessionDigest(
                                            multipartUploadId,
                                            multipartUri,
                                            multipartToken,
                                            multipartBizId,
                                            completeProfile
                                    );
                                    String firstEtag = partPayload.isEmpty() ? "" : String.valueOf(partPayload.get(0).get("etag"));
                                    String lastEtag = partPayload.isEmpty() ? "" : String.valueOf(partPayload.get(partPayload.size() - 1).get("etag"));
                                    MultipartSessionValidator.CompleteValidation validation =
                                            MultipartSessionValidator.validateCompleteContext(
                                                    effectiveChunkNum,
                                                    multipartUri,
                                                    multipartToken,
                                                    multipartBizId,
                                                    completeProfile,
                                                    multipartUploadId,
                                                    multipartEtags,
                                                    multipartSignedUploadIds,
                                                    multipartSignedPartNumbers
                                            );

                                    log.info("[BLR] {}", LogKvs.event("Upload.MultipartComplete.PayloadSummary")
                                            .add("roomId", room.getRoomId())
                                            .add("uname", room.getUname())
                                            .add("partId", part.getId())
                                            .add("historyId", part.getHistoryId())
                                            .add("retryCount", multipartRetryCount)
                                            .add("retryLimit", MULTIPART_RETRY_LIMIT)
                                            .add("nextRetryMultipart", multipartRetryCount + 1 < MULTIPART_RETRY_LIMIT)
                                            .add("profile", completeProfile)
                                            .add("bizId", multipartBizId)
                                            .addIfNotBlank("initUploadId", multipartUploadId)
                                            .add("parts", partPayload.size())
                                            .add("chunkSize", effectiveChunkSize)
                                            .add("expectedParts", effectiveChunkNum)
                                            .add("uriPrefix", StringUtils.left(multipartUri, 32))
                                            .addIfNotBlank("metaUposUri", multipartMetaUposUri)
                                            .add("tokenLen", multipartToken == null ? 0 : multipartToken.length())
                                            .add("firstEtagQuoted", StringUtils.startsWith(firstEtag, "\"") && StringUtils.endsWith(firstEtag, "\""))
                                            .add("lastEtagQuoted", StringUtils.startsWith(lastEtag, "\"") && StringUtils.endsWith(lastEtag, "\""))
                                            .add("signedUploadIdCount", validation.getSignedUploadIdCount())
                                            .addIfNotBlank("signedUploadId", validation.getSignedUploadId())
                                            .addIfNotBlank("inferredProfile", validation.getInferredProfile())
                                            .addIfNotBlank("sessionDigest", completeSessionDigest));

                                    if (!validation.isValid()) {
                                        completeResponse = "multipart complete validation failed: " + validation.getReason();
                                        log.error("[BLR] {}", LogKvs.event("Upload.MultipartComplete.ValidationFailed")
                                                .add("roomId", room.getRoomId())
                                                .add("uname", room.getUname())
                                                .add("partId", part.getId())
                                                .add("historyId", part.getHistoryId())
                                                .add("retryCount", multipartRetryCount)
                                                .add("retryLimit", MULTIPART_RETRY_LIMIT)
                                                .add("nextRetryMultipart", multipartRetryCount + 1 < MULTIPART_RETRY_LIMIT)
                                                .add("parts", partPayload.size())
                                                .add("signedUploadIdCount", validation.getSignedUploadIdCount())
                                                .addIfNotBlank("signedUploadIds", validation.getSignedUploadIds())
                                                .addIfNotBlank("initUploadId", multipartUploadId)
                                                .addIfNotBlank("sessionDigest", completeSessionDigest)
                                                .addIfNotBlank("reason", validation.getReason())
                                                .add("willFallback", true));
                                    } else {
                                        MultipartCompleteRequest multipartCompleteRequest = new MultipartCompleteRequest(webCookie);
                                        for (int i = 0; i < 5; i++) {
                                            try {
                                                JSONObject resp = multipartCompleteRequest.complete(
                                                        multipartUri,
                                                        multipartUploadId,
                                                        multipartToken,
                                                        multipartBizId,
                                                        completeProfile,
                                                        partPayload
                                                );
                                                completeResponse = resp != null ? resp.toJSONString() : null;
                                                if (MultipartCompleteRequest.isSuccess(resp)) {
                                                    completeSuccess = true;
                                                    break;
                                                }
                                                // 403 是权限问题，继续重试通常只会消耗额度；409 先按对象存储合并延迟重试。
                                                if (resp != null) {
                                                    int code = resp.getIntValue("code");
                                                    if (code == -403 || code == 403) {
                                                        log.error("[BLR] {}", LogKvs.event("Upload.MultipartComplete.FatalError")
                                                                .add("roomId", room.getRoomId())
                                                                .add("uname", room.getUname())
                                                                .add("partId", part.getId())
                                                                .add("historyId", part.getHistoryId())
                                                            .add("retryCount", multipartRetryCount)
                                                            .add("retryLimit", MULTIPART_RETRY_LIMIT)
                                                            .add("nextRetryMultipart", multipartRetryCount + 1 < MULTIPART_RETRY_LIMIT)
                                                                .add("code", code)
                                                                .add("message", resp.getString("message"))
                                                                .addIfNotBlank("sessionDigest", completeSessionDigest)
                                                                .add("willFallback", true));
                                                        break;
                                                    }
                                                    if (code == -409 || code == 409) {
                                                        log.warn("[BLR] {}", LogKvs.event("Upload.MultipartComplete.ConflictRetry")
                                                                .add("roomId", room.getRoomId())
                                                                .add("uname", room.getUname())
                                                                .add("partId", part.getId())
                                                                .add("historyId", part.getHistoryId())
                                                                .add("attempt", i + 1)
                                                                .add("maxAttempt", 5)
                                                                .add("code", code)
                                                                .add("message", resp.getString("message"))
                                                                .addIfNotBlank("sessionDigest", completeSessionDigest)
                                                                .add("willFallback", i >= 4));
                                                    }
                                                }
                                                // 重试间隔，避免疯狂重试
                                                if (i < 4) {
                                                    Thread.sleep(1000);
                                                }
                                            } catch (Exception e) {
                                                log.error("[BLR] {}", LogKvs.event("Upload.MultipartComplete.Retry")
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
                                    }
                                } else {
                                    Map<String, String> completeParams = new HashMap<>();
                                    completeParams.put("profile", uploadEnums.getProfile());
                                    completeParams.put("name", uploadFile.getName());
                                    completeParams.put("uploadId", uploadBean.getUpload_id());
                                    completeParams.put("biz_id", String.valueOf(preUploadBean.getBiz_id()));
                                    Map<String, Object> bodyMap = new LinkedHashMap<>(1);
                                    List<Map<String, String>> chunkMaps = new ArrayList<>((int) effectiveChunkNum);
                                    for (int i = 1; i <= effectiveChunkNum; i++) {
                                        Map<String, String> partMap = new LinkedHashMap<>(2);
                                        partMap.put("partNumber", String.valueOf(i));
                                        partMap.put("eTag", "etag");
                                        chunkMaps.add(partMap);
                                    }
                                    bodyMap.put("parts", chunkMaps);
                                    CompleteUploadRequest completeUploadRequest = new CompleteUploadRequest(preUploadBean, completeParams, JSON.toJSONString(bodyMap));
                                    CompleteUploadBean completeUploadBean = null;
                                    for (int i = 0; i < 5; i++) {
                                        try {
                                            completeUploadBean = completeUploadRequest.getPojo();
                                        } catch (Exception e) {
                                            if (completeUploadBean == null) {
                                                completeUploadBean = new CompleteUploadBean();
                                            }
                                            log.error("[BLR] {}", LogKvs.event("Upload.Complete.Retry")
                                                    .add("roomId", room.getRoomId())
                                                    .add("uname", room.getUname())
                                                    .add("partId", part.getId())
                                                    .add("historyId", part.getHistoryId())
                                                    .add("attempt", i + 1)
                                                    .add("maxAttempt", 5)
                                                    .add("err", e.getMessage())
                                                    .add("ex", e.getClass().getSimpleName()), e);
                                        }
                                        if (completeUploadBean != null && completeUploadBean.getOK() != null && completeUploadBean.getOK() == 1) {
                                            completeSuccess = true;
                                            completeResponse = JSON.toJSONString(completeUploadBean);
                                            break;
                                        }
                                    }
                                }

                                if (completeSuccess) {
                                    part = partRepository.findById(part.getId()).get();
                                    part.setUpload(true);
                                    part.setFileName(serverFileName);
                                    part.setCid(preUploadBean.getBiz_id());
                                    part.setUpdateTime(LocalDateTime.now());
                                    part.setUploadFlow(useMultipartFlow ? "MULTIPART" : "LEGACY");
                                    if (configuredMultipartEnabled && !useMultipartFlow) {
                                        part.setUploadFlowFallback(true);
                                        part.setUploadFlowFallbackReason(StringUtils.defaultIfBlank(uploadFlowFallbackReason, "multipart fallback to legacy"));
                                    } else {
                                        part.setUploadFlowFallback(false);
                                        part.setUploadFlowFallbackReason(null);
                                    }
                                    part = partRepository.save(part);
                                    //如果配置上传完成删除，则删除文件
                                    if (room.getDeleteType() == 1) {
                                        boolean delete = uploadFile.delete();
                                        if (delete) {
                                            log.info("[BLR] {}", LogKvs.event("Upload.Post.DeleteSuccess")
                                                    .add("roomId", room.getRoomId())
                                                    .add("uname", room.getUname())
                                                    .add("partId", part.getId())
                                                    .add("historyId", part.getHistoryId())
                                                    .add("filePath", filePath));
                                        } else {
                                            log.error("[BLR] {}", LogKvs.event("Upload.Post.DeleteFailed")
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
                                                            .add("roomId", room.getRoomId())
                                                            .add("uname", room.getUname())
                                                            .add("partId", part.getId())
                                                            .add("historyId", part.getHistoryId())
                                                            .add("fileName", file.getName())
                                                            .add("toDir", toDirPath));
                                                } catch (Exception e) {
                                                    log.error("[BLR] {}", LogKvs.event("Upload.Post.MoveFailed")
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
                                    if (useMultipartFlow && multipartSession != null) {
                                        multipartUploadSessionService.markCompletedAndClear(multipartSession);
                                    }
                                    uploadProgressTracker.markSuccessAndRemove(part.getId());
                                        log.info("[BLR] {}", LogKvs.event("Upload.Part.Success")
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
                                    // 合并失败，检查是否可以重试
                                    if (useMultipartFlow && multipartSession != null) {
                                        multipartUploadSessionService.markExpired(multipartSession,
                                                StringUtils.defaultIfBlank(completeResponse, "multipart complete failed"));
                                    }
                                    int currentRetry = part.getUploadRetryCount();
                                    int nextRetryCount = currentRetry + 1;
                                    boolean nextAttemptMultipart = nextRetryCount < MULTIPART_RETRY_LIMIT;
                                    if (currentRetry < MULTIPART_RETRY_LIMIT) {
                                        // 还有重试次数，先再跑一次 multipart；再失败才回退 legacy
                                        part.setUploadRetryCount(nextRetryCount);
                                        if (!nextAttemptMultipart) {
                                            part.setUploadFlowFallback(true);
                                            part.setUploadFlowFallbackReason("multipart complete failed, will fallback to legacy");
                                        }
                                        part = partRepository.save(part);
                                        TaskUtil.partUploadTask.remove(part.getId());
                                        uploadProgressTracker.markFailed(part.getId(), nextAttemptMultipart
                                            ? "multipart complete failed, will retry multipart session"
                                            : "multipart complete failed, will fallback to legacy");
                                        log.warn("[BLR] {}", LogKvs.event("Upload.MultipartComplete.WillRetry")
                                                .add("roomId", room.getRoomId())
                                                .add("uname", room.getUname())
                                                .add("partId", part.getId())
                                                .add("historyId", part.getHistoryId())
                                                .add("filePath", filePath)
                                                .add("currentRetry", currentRetry)
                                                .add("nextRetry", nextRetryCount)
                                                .add("retryLimit", MULTIPART_RETRY_LIMIT)
                                                .add("nextAttemptMultipart", nextAttemptMultipart)
                                                .add("response", completeResponse)
                                                .addIfNotBlank("sessionDigest", multipartSessionDigest)
                                                .add("willUseLegacyFlow", !nextAttemptMultipart)
                                                .addStageCostMs("total", uploadStartNs));
                                        scheduleRetryEnqueue(room, part, filePath, 3000L, "MULTIPART_COMPLETE_FAILED");
                                    } else {
                                        // 超过最大重试次数，标记为真正失败
                                        part.setUploadRetryCount(UPLOAD_RETRY_GIVE_UP);
                                        String errorMsg = "multipart complete 已重试多次仍失败，放弃上传: " + filePath;
                                        part.setDeleteFailReason(errorMsg);
                                        part.setDeleteFailType("MULTIPART_COMPLETE_FAILED");
                                        partRepository.save(part);
                                        uploadProgressTracker.markFailed(part.getId(), errorMsg);
                                        LogAnalyzeService.getInstance().processLog(errorMsg, "ERROR");
                                        throw new RuntimeException("合并上传文件失败（已重试）：" + completeResponse);
                                    }
                                }

                            } catch (Exception e) {
                                //存在异常
                                TaskUtil.partUploadTask.remove(part.getId());
                                uploadProgressTracker.markFailed(part.getId(), e.getMessage());
                                log.error("[BLR] {}", LogKvs.event("Upload.Part.Failed")
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
                                uploadFairShareService.unregisterUploadUser(biliBiliUser.getId(), room.getRoomId(), part.getId(), "UPOS_PART");
                            }
                        }
                        }
                    } else {
                        log.info("[BLR] {}", LogKvs.event("Upload.SkipNotNeeded")
                                .add("roomId", part.getRoomId())
                                .add("partId", part.getId())
                                .add("historyId", part.getHistoryId()));
                        TaskUtil.partUploadTask.remove(part.getId());
                        return;
                    }
                }

            }
        } catch (Exception e) {
            UploadRetryLogPolicy.LogDecision logDecision = UploadRetryLogPolicy.recoverable(
                    "Upload.ServiceError:" + OS + ":" + (part == null ? "unknown" : part.getId()));
            LogKvs serviceErrorLog = LogKvs.event("Upload.ServiceError")
                    .add("os", OS)
                    .add("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName())
                    .add("recoverableCount", logDecision.count())
                    .add("warnThreshold", logDecision.warnThreshold())
                    .addStageCostMs("total", uploadStartNs);
            if (logDecision.warn()) {
                log.warn("[BLR] {}", serviceErrorLog, e);
            } else {
                log.info("[BLR] {}", serviceErrorLog);
            }
        } finally {
            TaskUtil.partUploadTask.remove(part.getId());
            uploadProgressTracker.remove(part.getId());
        }

    }



    private boolean shouldPauseUpload(Long historyId, Long partId) {
        return uploadPauseService.isUploadPaused(historyId, partId);
    }

    private boolean isUploadChunkCancelled(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (StringUtils.containsIgnoreCase(message, "Upload chunk cancelled")
                    || current instanceof java.io.InterruptedIOException
                    || current instanceof InterruptedException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String pauseUpload(Long historyId, Long partId, MultipartUploadSession session) {
        RecordHistoryPart currentPart = partId == null ? null : partRepository.findById(partId).orElse(null);
        RecordHistory currentHistory = historyId == null ? null : historyRepository.findById(historyId).orElse(null);
        String msg = uploadPauseService.pauseMessage(currentHistory, currentPart);
        if (partId != null) {
            uploadProgressTracker.markPaused(partId, msg);
        }
        multipartUploadSessionService.markPaused(session, msg);
        TaskUtil.partUploadTask.remove(partId);
        log.info("[BLR] {}", LogKvs.event("Upload.Part.Paused")
                .add("partId", partId)
                .add("historyId", historyId)
                .addIfNotBlank("reason", msg));
        return msg;
    }

    private boolean isMultipartSessionInvalid(Exception e) {
        String msg = e == null ? "" : StringUtils.defaultString(e.getMessage()).toLowerCase(Locale.ROOT);
        return msg.contains("multipart part request failed")
                || msg.contains("multipart part response missing")
                || msg.contains("multipart part response invalid")
                || msg.contains("uploadid")
                || msg.contains("upload id")
                || msg.contains("upload_token")
                || msg.contains("upload token")
                || msg.contains("session")
                || msg.contains("expired")
                || msg.contains("invalid");
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

    private boolean isLikelyEdgeOrProxyHost(String host) {
        if (StringUtils.isBlank(host)) {
            return false;
        }
        String h = host.toLowerCase();
        if (h.contains("bilivideo.com") || h.contains("bilivideo.cn") || h.contains("bilivideo.net")) {
            return false;
        }
        if (h.contains("esheep.com") || h.contains("aikobo.cn")) {
            return true;
        }
        int firstDot = h.indexOf('.');
        String firstLabel = firstDot > 0 ? h.substring(0, firstDot) : h;
        boolean mixedAlphaNum = firstLabel.matches(".*[a-z].*") && firstLabel.matches(".*\\d.*");
        if (mixedAlphaNum && firstLabel.length() >= 8) {
            return true;
        }
        return h.contains("cdn");
    }

    private String buildMultipartUploadId(Long uid, Long partId) {
        long now = System.currentTimeMillis();
        String uidPart = uid == null ? "0" : String.valueOf(uid);
        String partSuffix = partId == null ? "0" : String.valueOf(Math.abs(partId % 10000));
        return uidPart + "_" + now + "_" + partSuffix;
    }

    private String extractMultipartUploadToken(PreUploadBean preUploadBean) {
        if (preUploadBean == null) {
            return null;
        }
        String raw = preUploadBean.getRawUptoken();
        if (StringUtils.isNotBlank(raw)) {
            return raw;
        }
        String withPrefix = preUploadBean.getUptoken();
        if (StringUtils.isBlank(withPrefix)) {
            return null;
        }
        return StringUtils.removeStart(withPrefix, "UpToken ").trim();
    }

    private void scheduleRetryEnqueue(RecordRoom room,
                                      RecordHistoryPart part,
                                      String filePath,
                                      long initialDelayMs,
                                      String reason) {
        if (room == null || part == null) {
            return;
        }
        CompletableFuture.runAsync(
                () -> tryRetryEnqueue(room, part.getId(), filePath, reason, 1),
                CompletableFuture.delayedExecutor(Math.max(initialDelayMs, 0L), TimeUnit.MILLISECONDS)
        );
    }

    private void tryRetryEnqueue(RecordRoom room,
                                 Long partId,
                                 String filePath,
                                 String reason,
                                 int attempt) {
        if (room == null || partId == null) {
            return;
        }
        try {
            Optional<RecordHistoryPart> latestOpt = partRepository.findById(partId);
            if (latestOpt.isEmpty()) {
                return;
            }
            RecordHistoryPart latestPart = latestOpt.get();
            boolean accepted = uploadServiceFactory
                    .getUploadService(room.getLine())
                    .asyncUploadIfNeeded(latestPart);
            log.info("[BLR] {}", LogKvs.event("Upload.Part.RetryEnqueueAttempt")
                    .add("roomId", room.getRoomId())
                    .add("uname", room.getUname())
                    .add("partId", latestPart.getId())
                    .add("historyId", latestPart.getHistoryId())
                    .add("attempt", attempt)
                    .add("accepted", accepted)
                    .addIfNotBlank("reason", reason)
                    .addIfNotBlank("filePath", filePath));
            if (!accepted && attempt < 4) {
                CompletableFuture.runAsync(
                        () -> tryRetryEnqueue(room, partId, filePath, reason, attempt + 1),
                        CompletableFuture.delayedExecutor(1500L, TimeUnit.MILLISECONDS)
                );
            }
        } catch (Exception enqueueEx) {
            log.warn("[BLR] {}", LogKvs.event("Upload.Part.RetryEnqueueError")
                    .add("roomId", room.getRoomId())
                    .add("uname", room.getUname())
                    .add("partId", partId)
                    .add("attempt", attempt)
                    .addIfNotBlank("reason", reason)
                    .addIfNotBlank("filePath", filePath)
                    .addIfNotBlank("err", enqueueEx.getMessage())
                    .add("ex", enqueueEx.getClass().getSimpleName()), enqueueEx);
            if (attempt < 4) {
                CompletableFuture.runAsync(
                        () -> tryRetryEnqueue(room, partId, filePath, reason, attempt + 1),
                        CompletableFuture.delayedExecutor(1500L, TimeUnit.MILLISECONDS)
                );
            }
        }
    }

    private String buildMultipartSessionDigest(String uploadId,
                                               String multipartUri,
                                               String uploadToken,
                                               long bizId,
                                               String profile) {
        if (StringUtils.isBlank(uploadId)
                && StringUtils.isBlank(multipartUri)
                && StringUtils.isBlank(uploadToken)
                && bizId <= 0
                && StringUtils.isBlank(profile)) {
            return "";
        }
        String normalizedProfile = MultipartSessionValidator.preferProfileByUri(profile, multipartUri);
        String raw = shortHash(uploadId)
                + ":"
                + shortHash(multipartUri)
                + ":"
                + shortHash(uploadToken)
                + ":"
                + bizId
                + ":"
                + shortHash(normalizedProfile);
        return Integer.toHexString(raw.hashCode());
    }

    private String shortHash(String value) {
        if (StringUtils.isBlank(value)) {
            return "";
        }
        return Integer.toHexString(StringUtils.trim(value).hashCode());
    }

    private String resolveMultipartProfile(PreUploadBean preUploadBean, String fallbackProfile, String multipartUri) {
        // 优先锁定 multipart/new 返回的 profile，保证同一会话 init/part/complete 一致。
        String candidateProfile = StringUtils.trimToNull(fallbackProfile);
        if (StringUtils.isBlank(candidateProfile) && preUploadBean != null && StringUtils.isNotBlank(preUploadBean.getPut_query())) {
            String putQuery = preUploadBean.getPut_query();
            for (String kv : putQuery.split("&")) {
                String[] pair = kv.split("=", 2);
                if (pair.length == 2 && "profile".equals(pair[0]) && StringUtils.isNotBlank(pair[1])) {
                    String profile = java.net.URLDecoder.decode(pair[1], java.nio.charset.StandardCharsets.UTF_8);
                    if (StringUtils.isNotBlank(profile)) {
                        candidateProfile = profile;
                        break;
                    }
                }
            }
        }
        return MultipartSessionValidator.preferProfileByUri(candidateProfile, multipartUri);
    }

    private String resolveServerFileName(PreUploadBean preUploadBean, LineUploadBean uploadBean, String fallback) {
        if (uploadBean != null && StringUtils.isNotBlank(uploadBean.getKey())) {
            return uploadBean.getFileName();
        }
        if (preUploadBean != null && StringUtils.isNotBlank(preUploadBean.getUpos_uri())) {
            return resolveFileNameFromUposUri(preUploadBean.getUpos_uri(), fallback);
        }
        return fallback;
    }

    private String resolveFileNameFromUposUri(String uposUri, String fallback) {
        if (StringUtils.isBlank(uposUri)) {
            return fallback;
        }
        int slash = uposUri.lastIndexOf('/');
        String filename = slash >= 0 ? uposUri.substring(slash + 1) : uposUri;
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
