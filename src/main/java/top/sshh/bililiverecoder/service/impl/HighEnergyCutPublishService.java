package top.sshh.bililiverecoder.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import net.bramp.ffmpeg.FFmpeg;
import net.bramp.ffmpeg.FFmpegExecutor;
import net.bramp.ffmpeg.builder.FFmpegBuilder;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.entity.data.SingleVideoDto;
import top.sshh.bililiverecoder.entity.data.VideoUploadDto;
import top.sshh.bililiverecoder.repo.BiliUserRepository;
import top.sshh.bililiverecoder.repo.LiveMsgRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.service.SystemConfigService;
import top.sshh.bililiverecoder.service.UploadFairShareService;
import top.sshh.bililiverecoder.service.PartFileLocationService;
import top.sshh.bililiverecoder.service.StorageRootService;
import top.sshh.bililiverecoder.util.BiliApi;
import top.sshh.bililiverecoder.util.LogKvs;
import top.sshh.bililiverecoder.util.UploadEnums;
import top.sshh.bililiverecoder.util.UploadRetryLogPolicy;
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
import top.sshh.bililiverecoder.service.CaptchaService;
import top.sshh.bililiverecoder.service.PublishAccountScheduler;
import top.sshh.bililiverecoder.service.PublishTaskService;
import top.sshh.bililiverecoder.service.PublishAccountCooldownService;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import java.util.concurrent.ForkJoinPool;
import top.sshh.bililiverecoder.service.RateLimiterService;
import top.sshh.bililiverecoder.util.NettyUploadClient;

@Slf4j
@Component
public class HighEnergyCutPublishService {

    @Autowired
    private CaptchaService captchaService;

    @Autowired
    private PublishAccountScheduler publishAccountScheduler;

    private final Map<Long, Map<String, String>> pendingCaptchaResults = new ConcurrentHashMap<>();

    @Value("${server.port:8080}")
    private String serverPort;

    public static final Map<Long, String> taskRunningMsg = new ConcurrentHashMap<>();

    @Value("${record.upload.multipart-enabled:true}")
    private boolean multipartEnabled;

    @Autowired
    private SystemConfigService systemConfigService;

    @Value("${record.upload.probe-version:20250923}")
    private String probeVersion;
    @Autowired
    private BiliUserRepository biliUserRepository;
    @Autowired
    private RecordHistoryPartRepository partRepository;
    @Autowired
    private RecordRoomRepository roomRepository;

    @Autowired
    private RecordHistoryRepository historyRepository;

    @Autowired
    private PublishTaskService publishTaskService;

    @Autowired
    private PublishAccountCooldownService publishAccountCooldownService;
    @Autowired
    private LiveMsgRepository liveMsgRepository;

    @Autowired
    private UploadFairShareService uploadFairShareService;

    @Autowired
    private PartFileLocationService partFileLocationService;

    @Autowired
    private StorageRootService storageRootService;

    private final UploadRetryBackoffPolicy uploadRetryBackoffPolicy = new UploadRetryBackoffPolicy();
    private static final long LEGACY_CHUNK_SIZE = 1024L * 1024L * 5L;
    private static final String BROWSER_MULTIPART_PROFILE = "ugcfx/bup";

    private boolean isBrowserMultipartEnabled() {
        try {
            return systemConfigService != null && systemConfigService.isNewUploadFlowEnabled();
        } catch (Exception e) {
            log.warn("[BLR] {}", LogKvs.event("HighEnergyCut.Upload.Multipart.ConfigReadFailed")
                    .add("fallbackProperty", multipartEnabled)
                    .addIfNotBlank("err", e.getMessage()));
            return multipartEnabled;
        }
    }


    public void process(RecordHistory history) throws IOException {
        if (history == null || history.getId() == null) throw new IOException("history unavailable");
        RecordRoom room = roomRepository.findByRoomId(history.getRoomId());
        Long accountId = history.getPublishUserId() != null
                ? history.getPublishUserId() : room == null ? null : room.getUploadUserId();
        top.sshh.bililiverecoder.service.PublishTaskService.Admission admission = publishAccountScheduler.accept(
                accountId, history.getId(), PublishTaskOperation.HIGH_ENERGY, PublishTaskSource.SYSTEM, null);
        if (!admission.isAccepted()) throw new IOException(admission.getMessage());
    }

    public Map<String, Object> prepareQueuedTask(PublishTask task, RecordHistory history) throws IOException {
        if (task == null || history == null || task.getAccountId() == null) throw new IOException("task or history unavailable");
        String fileName = prepareAndUpload(history, task.getAccountId(), task.getId());
        Path outputFile = storageRootService.activeWorkRoot()
                .map(root -> Path.of(root.getPath(), "_high_energy_cut", String.valueOf(history.getId()), "output.mp4"))
                .orElseThrow(() -> new IOException("active work storage root missing"));
        Map<String, Object> metadata = readArtifactManifest(outputFile.getParent().resolve("prepared-artifact.json"));
        metadata.put("uploadedFileName", fileName);
        return metadata;
    }

    private String prepareAndUpload(RecordHistory history, Long accountId, Long taskId) throws IOException {
        List<RecordHistoryPart> partList = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());
        RecordRoom room = roomRepository.findByRoomId(history.getRoomId());
        if (room == null) throw new IOException("room missing");
        if (accountId == null) throw new IOException("publish account missing");
        // 初始化 FFmpeg
        FFmpeg ffmpeg = new FFmpeg();
        FFmpegExecutor executor = new FFmpegExecutor(ffmpeg);
        Path outputPath = storageRootService.activeWorkRoot()
                .map(root -> Path.of(root.getPath(), "_high_energy_cut", String.valueOf(history.getId())))
                .orElseThrow(() -> new IOException("active work storage root missing"));
        Path outputFile = outputPath.resolve("output.mp4");
        Path manifestFile = outputPath.resolve("prepared-artifact.json");
        if (taskRunningMsg.get(history.getId()) != null) {
            throw new IOException("high-energy preparation already running");
        }
        try {
            taskRunningMsg.put(history.getId(), "创建输出目录");
            Files.createDirectories(outputPath);
            Map<String, Object> manifest = readArtifactManifest(manifestFile);
            String existingHash = Files.isRegularFile(outputFile) ? sha256(outputFile) : null;
            boolean reusableOutput = existingHash != null && existingHash.equals(manifest.get("outputSha256"))
                    && accountId.toString().equals(String.valueOf(manifest.get("accountId")));
            String uploadedFileName = reusableOutput ? stringValue(manifest.get("uploadedFileName")) : null;
            if (StringUtils.isNotBlank(uploadedFileName)) return uploadedFileName;
            if (!reusableOutput) {
                Files.walk(outputPath).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
                Files.createDirectories(outputPath);
                taskRunningMsg.put(history.getId(), "开始分片");
            //循环处理每个分片
            int i = 0;
            int count = 0;
            for (RecordHistoryPart part : partList) {
                PartFileLocationService.FileResolution fileResolution = partFileLocationService.resolveReadable(part.getId());
                if (!fileResolution.available()) {
                    throw new IOException("part local file unavailable: " + part.getId() + " state=" + fileResolution.state());
                }
                String sourcePath = fileResolution.path().toString();
                Map<Integer, Integer> highEnergyCut = getHighEnergyCut(part, room.getPercentileRank());
                List<Integer> cutList = highEnergyCut.keySet().stream().sorted().toList();
                count += cutList.size();
                for (Integer time : cutList) {
                    ensurePreparationActive(taskId);
                    i++;
                    String output = Path.of(outputPath.toString(), String.format("%05d", i) + "." + sourcePath.substring(sourcePath.lastIndexOf(".") + 1)).toString();
                    int startTime = time;
                    int duration = highEnergyCut.get(time);

                    FFmpegBuilder builder = new FFmpegBuilder()
                            .setInput(sourcePath)
                            .setStartOffset(startTime, TimeUnit.SECONDS)
                            .addExtraArgs("-t", String.valueOf(duration))
                            .overrideOutputFiles(true)
                            .addOutput(output)
                            .setVideoCodec("copy")
                            .setAudioCodec("copy")
                            .done();
                    long currentTimeMillis = System.currentTimeMillis();
                    taskRunningMsg.put(history.getId(), "开始处理分片 " + i);
                    executor.createJob(builder).run();
                    log.info("[BLR] {}", LogKvs.event("HighEnergyCut.Segment.Generated")
                            .add("historyId", history.getId())
                            .add("roomId", history.getRoomId())
                            .addIfNotBlank("title", history.getTitle())
                            .add("index", i)
                            .add("total", count)
                            .add("costSec", (System.currentTimeMillis() - currentTimeMillis) / 1000)
                            .add("output", output));
                }
            }
            ensurePreparationActive(taskId);
            // 获取所有文件，按名字排序
            List<String> files;
            try (Stream<Path> stream = Files.list(outputPath)) {
                files = stream
                        .sorted() // 确保顺序：part001, part002...
                        .map(p -> p.toAbsolutePath().toString())
                        .toList();
            }
            if (files.isEmpty()) {
                log.error("[BLR] {}", LogKvs.event("HighEnergyCut.Segment.Empty")
                        .add("historyId", history.getId())
                        .add("roomId", history.getRoomId())
                        .add("outputPath", outputPath.toString()));
                throw new IOException("no high-energy segments were generated");
            }
            // 创建 list.txt
            Path listFile = outputPath.resolve("list.txt");
            StringBuilder sb = new StringBuilder();
            for (String file : files) {
                sb.append("file '").append(file).append("'\n");
            }
            Files.writeString(listFile, sb.toString());
            String output = outputFile.toString();
            taskRunningMsg.put(history.getId(), "开始合并分片");
            long currentTimeMillis = System.currentTimeMillis();
            try {
                FFmpegBuilder copyBuilder = new FFmpegBuilder()
                        .setInput(listFile.toString())
                        .addExtraArgs("-f", "concat")
                        .addExtraArgs("-safe", "0")
                        .overrideOutputFiles(true)
                        .addOutput(output)
                        .addExtraArgs("-c", "copy")
                        .done();
                executor.createJob(copyBuilder).run();
            } catch (Exception e) {
                FFmpegBuilder encodeBuilder = new FFmpegBuilder()
                        .setInput(listFile.toString())
                        .addExtraArgs("-f", "concat")
                        .overrideOutputFiles(true)
                        .addOutput(output)
                        .addExtraArgs("-safe", "0")
                        .setAudioChannels(2)         // 立体声
                        .setAudioCodec("aac")        // AAC 编码
                        .setAudioSampleRate(48_000)  // 48kHz
                        .setAudioBitRate(128_000)    // 128 kbit/s（标准高质量）
                        .addExtraArgs(
                                "-c:v", "libx264", "-crf", "23", "-preset", "fast",
                                "-c:a", "aac", "-b:a", "128k"
                        )
                        .setVideoCodec("libx264")            // H.264 编码
                        .setVideoFrameRate(60, 1)            // 30 fps（更流畅，也可用 24/25）
                        .setVideoResolution(1920, 1080)      // 1080p 分辨率
                        .setVideoBitRate(5_000_000)          // 5 Mbps 码率（关键！）
                        .done();
                executor.createJob(encodeBuilder).run();
            }
            log.info("[BLR] {}", LogKvs.event("HighEnergyCut.Output.Generated")
                    .add("historyId", history.getId())
                    .add("roomId", history.getRoomId())
                    .addIfNotBlank("title", history.getTitle())
                    .add("costSec", (System.currentTimeMillis() - currentTimeMillis) / 1000)
                    .add("output", output));
            }
            String outputSha256 = sha256(outputFile);
            manifest = new HashMap<>();
            manifest.put("historyId", history.getId());
            manifest.put("accountId", accountId);
            manifest.put("outputPath", outputFile.toString());
            manifest.put("outputSha256", outputSha256);
            manifest.put("generatedAt", LocalDateTime.now().toString());
            Files.writeString(manifestFile, JSON.toJSONString(manifest));
            ensurePreparationActive(taskId);
            taskRunningMsg.put(history.getId(), "开始上传");
            String upload = upload(room, outputFile.toString(), history.getId(), accountId, taskId);
            if (StringUtils.isBlank(upload)) throw new IOException("high-energy upload returned empty filename");
            manifest.put("uploadedFileName", upload);
            manifest.put("uploadedAt", LocalDateTime.now().toString());
            Files.writeString(manifestFile, JSON.toJSONString(manifest));
            return upload;
        } catch (Exception e) {
            log.error("[BLR] {}", LogKvs.event("HighEnergyCut.Process.Failed")
                    .add("historyId", history.getId())
                    .add("roomId", history.getRoomId())
                    .addIfNotBlank("title", history.getTitle()), e);
            if (e instanceof IOException io) throw io;
            throw new IOException("high-energy preparation failed: " + e.getMessage(), e);
        } finally {
            taskRunningMsg.remove(history.getId());
        }
    }

    public boolean executeQueuedTask(PublishTask task) throws InterruptedException {
        if (task == null || StringUtils.isBlank(task.getRequestSnapshot()))
            throw new IllegalStateException("高能剪辑任务尚未准备完成");
        Map<String, Object> snapshot = JSON.parseObject(task.getRequestSnapshot(), new TypeReference<Map<String, Object>>() {});
        String uploadedFileName = stringValue(snapshot.get("uploadedFileName"));
        if (StringUtils.isBlank(uploadedFileName)) throw new IllegalStateException("高能剪辑上传产物缺失");
        RecordHistory history = historyRepository.findById(task.getHistoryId())
                .orElseThrow(() -> new IllegalStateException("稿件不存在"));
        RecordRoom room = roomRepository.findByRoomId(history.getRoomId());
        if (room == null) throw new IllegalStateException("稿件所属房间不存在");
        BiliBiliUser user = biliUserRepository.findById(task.getAccountId())
                .orElseThrow(() -> new IllegalStateException("投稿账号不存在"));
        if (!user.isLogin()) throw new IllegalStateException("投稿账号登录已失效");

        VideoUploadDto dto = buildHighEnergyVideoUploadDto(history, room, uploadedFileName);
        Map<String, String> captchaResult = pendingCaptchaResults.remove(task.getId());
        String response = captchaResult == null
                ? BiliApi.webPublish(user, dto) : BiliApi.webPublish(user, dto, captchaResult);
        JSONObject root = parseJsonObject(response);
        JSONObject data = root == null ? null : root.getJSONObject("data");
        Integer code = root == null ? null : root.getInteger("code");
        String message = root == null ? null : root.getString("message");
        if (isPublishCaptchaResponse(response, code)) {
            String voucher = data == null ? null : data.getString("v_voucher");
            captchaService.deferPublish(voucher, dto.getTitle(), data, task.getAccountId(), history.getId(),
                    task.getId(), "HIGH_ENERGY_SUBMIT", answer -> {
                        pendingCaptchaResults.put(task.getId(), new HashMap<>(answer));
                        publishAccountScheduler.enqueueAfterCaptcha(task.getId(), task.getAccountId(), history.getId());
                    });
            return false;
        }
        if (code != null && isAccountPublishRateLimit(code, message)) {
            publishAccountCooldownService.recordRisk(task.getAccountId());
            return false;
        }
        if (code == null || code != 0 || data == null) {
            throw new IllegalStateException("高能剪辑投稿结果未确认：" + abbreviatePublishResponse(response, 320));
        }
        String bvid = data.getString("bvid");
        String aid = data.getString("aid");
        if (StringUtils.isBlank(bvid) || StringUtils.isBlank(aid)) {
            throw new IllegalStateException("平台接受响应缺少 BVID/AID，结果需要核对");
        }
        snapshot.put("publishedBvid", bvid);
        snapshot.put("publishedAid", aid);
        snapshot.put("publishedAt", LocalDateTime.now().toString());
        publishTaskService.updateSnapshot(task.getId(), JSON.toJSONString(snapshot),
                "高能剪辑已由平台接受：" + bvid);
        log.info("[BLR] {}", LogKvs.event("HighEnergyCut.Publish.Success")
                .add("taskId", task.getId()).add("historyId", history.getId())
                .add("accountId", task.getAccountId()).addIfNotBlank("bvid", bvid).addIfNotBlank("aid", aid));
        return true;
    }

    private VideoUploadDto buildHighEnergyVideoUploadDto(RecordHistory history, RecordRoom room, String fileName) {
        Map<String, Object> map = new HashMap<>();
        map.put("date", history.getStartTime());
        map.put("${uname}", StringUtils.defaultString(room.getUname()));
        map.put("${title}", StringUtils.defaultIfBlank(history.getTitle(), "直播录像"));
        map.put("${roomId}", room.getRoomId());
        map.put("${areaName}", "");
        SingleVideoDto video = new SingleVideoDto();
        video.setTitle(room.getUname() + "直播录像剪辑");
        video.setDesc("");
        video.setFilename(fileName);
        VideoUploadDto dto = new VideoUploadDto();
        dto.setTitle(template("【直播剪辑】【${uname}】${title} ${yyyy年MM月dd日HH点mm分}", map));
        dto.setTid(room.getTid());
        dto.setCover(history.getCoverUrl());
        dto.setCopyright(1);
        dto.setDesc("直播间: https://live.bilibili.com/" + room.getRoomId()
                + "  稿件直播源\n自动剪辑投稿技术支持https://space.bilibili.com/10043269");
        dto.setVideos(Collections.singletonList(video));
        dto.setTag(template(room.getTags(), map));
        return dto;
    }

    public void completeQueuedTask(PublishTask task) {
        if (task == null || task.getOperation() != PublishTaskOperation.HIGH_ENERGY
                || task.getHistoryId() == null) return;
        try {
            Path activeRoot = storageRootService.activeWorkRoot()
                    .map(root -> Path.of(root.getPath()).toAbsolutePath().normalize()).orElse(null);
            if (activeRoot == null) return;
            Path allowedRoot = activeRoot.resolve("_high_energy_cut").normalize();
            Path directory = allowedRoot.resolve(String.valueOf(task.getHistoryId())).normalize();
            if (!directory.startsWith(allowedRoot)) return;
            if (StringUtils.isNotBlank(task.getRequestSnapshot())) {
                Map<String, Object> snapshot = JSON.parseObject(task.getRequestSnapshot(), new TypeReference<Map<String, Object>>() {});
                String rawOutput = snapshot == null ? null : stringValue(snapshot.get("outputPath"));
                Path expected = directory.resolve("output.mp4").normalize();
                if (StringUtils.isNotBlank(rawOutput)
                        && !Path.of(rawOutput).toAbsolutePath().normalize().equals(expected)) return;
            }
            try (Stream<Path> paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (IOException ignored) { }
                });
            }
        } catch (Exception e) {
            log.warn("Unable to clean accepted high-energy artifact taskId={}", task.getId(), e);
        }
    }

    private Map<String, Object> readArtifactManifest(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return new HashMap<>();
        try {
            JSONObject parsed = JSON.parseObject(Files.readString(path));
            return parsed == null ? new HashMap<>() : new HashMap<>(parsed);
        } catch (RuntimeException e) {
            return new HashMap<>();
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[128 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String stringValue(Object value) { return value == null ? null : String.valueOf(value); }

    private void ensurePreparationActive(Long taskId) throws IOException {
        PublishTask task = publishTaskService.get(taskId);
        if (task == null || task.getState() != PublishTaskState.PREPARING) {
            throw new IOException("高能剪辑准备任务已取消或不再有效");
        }
    }

    private boolean isPublishCaptchaResponse(String response, Integer code) {
        return StringUtils.contains(response, "验证码") || Objects.equals(code, 601) || Objects.equals(code, 406);
    }

    private static boolean isAccountPublishRateLimit(Integer code, String message) {
        if (code != null && (code == -412 || code == -352 || code == -429 || code == 12002)) return true;
        return StringUtils.contains(message, "风控")
                || StringUtils.contains(message, "频繁")
                || StringUtils.contains(message, "限流");
    }

    /**
     * 保留旧的调用入口，高能剪辑投稿统一先保存为任务
     * 调用方不能再绕过队列直接投稿
     */
    @Deprecated
    public void publish(RecordHistory history, String fileName) throws InterruptedException {
        try {
            process(history);
        } catch (IOException e) {
            throw new IllegalStateException("高能剪辑任务未能加入投稿队列：" + e.getMessage(), e);
        }
    }
    public String upload(RecordRoom room, String filePath, Long historyId, Long accountId) {
        return upload(room, filePath, historyId, accountId, null);
    }

    private String upload(RecordRoom room, String filePath, Long historyId, Long accountId, Long taskId) {

        UploadEnums uploadEnums = UploadEnums.find(room.getLine());
        Optional<BiliBiliUser> userOptional = accountId == null
                ? Optional.empty() : biliUserRepository.findById(accountId);
        BiliBiliUser biliBiliUser = userOptional.orElseThrow(() -> new IllegalStateException("投稿账号不存在"));
        WebCookie webCookie = Cookie.parse(biliBiliUser.getCookies());
        uploadFairShareService.registerUploadUser(biliBiliUser.getId(), room.getRoomId(), null, "HIGH_ENERGY_CUT");
        try {

        File uploadFile = new File(filePath);
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
        boolean configuredMultipartEnabled = isBrowserMultipartEnabled();
        boolean useMultipartFlow = configuredMultipartEnabled;
        String multipartUploadId = null;
        String multipartUri = null;
        String multipartToken = null;
        long multipartBizId = 0L;
        String multipartProfile = BROWSER_MULTIPART_PROFILE;
        String multipartMetaUposUri = null;
        String multipartSessionDigest = null;
        Map<Integer, String> multipartEtags = new ConcurrentHashMap<>();
        Map<Integer, String> multipartSignedUploadIds = new ConcurrentHashMap<>();
        Map<Integer, Integer> multipartSignedPartNumbers = new ConcurrentHashMap<>();
        try {
            do {
                preUploadBean = preuploadRequest.getPojo();
                if (preUploadBean == null || preUploadBean.getOK() == 0) {
                    Integer preCode = null;
                    String preMsg = null;
                    try {
                        if (preUploadBean != null) {
                            preCode = preUploadBean.getCode();
                            preMsg = preUploadBean.getMessage();
                        }
                    } catch (Exception ignore) {
                    }
                    log.warn("[BLR] {}", LogKvs.event("HighEnergyCut.Upload.PreUpload.Failed")
                            .add("roomId", room.getRoomId())
                            .add("uname", room.getUname())
                            .add("filePath", filePath)
                            .add("fileSizeBytes", fileSize)
                            .add("line", room.getLine())
                            .add("code", preCode)
                            .addIfNotBlank("message", preMsg));
                    if (preUploadBean != null && ((preUploadBean.getCode() == 601 && preUploadBean.getDetail() != null && preUploadBean.getDetail().containsKey("v_voucher")) || preUploadBean.getCode() == 406)) {
                        String voucher = (preUploadBean.getDetail() != null) ? (String) preUploadBean.getDetail().get("v_voucher") : "MANUAL_INTERVENTION";
                        log.warn("[BLR] {}", LogKvs.event("Upload.Captcha.Required")
                                .add("roomId", room.getRoomId())
                                .add("uname", room.getUname())
                                .add("fileName", uploadFile.getName())
                                .add("code", preUploadBean.getCode())
                                .addUrl("captchaUrl", "http://localhost:" + serverPort + "/html/captcha.html"));
                        captchaService.setCaptchaRequired(voucher, uploadFile.getName(), preUploadBean.getDetail(),
                                accountId, historyId, taskId, "HIGH_ENERGY_UPLOAD");
                        Map<String, String> result = captchaService.waitForCaptcha();
                        if (result == null) throw new IllegalStateException("高能剪辑上传验证码已过期或取消，请重新验证");
                        preParams.putAll(result);
                    } else {
                        log.warn("[BLR] {}", LogKvs.event("Upload.RateLimit.Wait")
                                .add("roomId", room.getRoomId())
                                .add("uname", room.getUname())
                                .add("fileName", uploadFile.getName())
                                .add("waitMs", 10000));
                        try {
                            Thread.sleep(10000L);
                        } catch (InterruptedException e) {
                            log.warn("[BLR] {}", LogKvs.event("HighEnergyCut.Upload.RateLimitWaitInterrupted")
                                    .add("roomId", room.getRoomId())
                                    .add("uname", room.getUname())
                                    .add("fileName", uploadFile.getName()), e);
                        }
                    }
                } else {
                    // 同步更新
                    //                                    chunkSize = preUploadBean.getChunk_size();
                    //                                    chunkNum = (long) Math.ceil((double) fileSize / chunkSize);
                    // 如果返回的线路不是指定的线路，则从备用线路选择
                    if (!preUploadBean.getEndpoint().contains(("upcdn" + uploadEnums.getCdn()))) {
                        String[] endpoints = preUploadBean.getEndpoints();
                        for (String endpoint : endpoints) {
                            if (endpoint.contains("upcdn" + uploadEnums.getCdn())) {
                                preUploadBean.setEndpoint(endpoint);
                            }
                        }
                    }
                    if (useMultipartFlow) {
                        multipartUploadId = null;
                        multipartUri = preUploadBean.getUpos_uri();
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
                            log.info("[BLR] {}", LogKvs.event("HighEnergyCut.Upload.Multipart.SessionLocked")
                                    .add("roomId", room.getRoomId())
                                    .add("uname", room.getUname())
                                    .add("bizId", multipartBizId)
                                    .addIfNotBlank("profile", multipartProfile)
                                    .addIfNotBlank("sessionDigest", multipartSessionDigest));
                            MultipartSessionValidator.MetaBucketCheck metaCheck =
                                    MultipartSessionValidator.checkMetaBucket(multipartUri, multipartMetaUposUri);
                            if (metaCheck.isComparable() && !metaCheck.isConsistent()) {
                                useMultipartFlow = false;
                                log.warn("[BLR] {}", LogKvs.event("HighEnergyCut.Upload.Multipart.InitFallback")
                                        .add("roomId", room.getRoomId())
                                        .add("uname", room.getUname())
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
                            }
                        } catch (Exception initEx) {
                            useMultipartFlow = false;
                            LogKvs kvs = LogKvs.event("HighEnergyCut.Upload.Multipart.InitFallback")
                                .add("roomId", room.getRoomId())
                                .add("uname", room.getUname())
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
                        if (StringUtils.isBlank(multipartUri) || StringUtils.isBlank(multipartToken)) {
                            useMultipartFlow = false;
                            log.warn("[BLR] {}", LogKvs.event("HighEnergyCut.Upload.Multipart.PrepareFallback")
                                    .add("roomId", room.getRoomId())
                                    .add("uname", room.getUname())
                                    .add("reason", "missing uri or upload_token"));
                        }
                    }
                    if (!useMultipartFlow) {
                        LineUploadRequest uploadRequest = new LineUploadRequest(webCookie, preUploadBean);
                        uploadBean = uploadRequest.getPojo();
                    }
                    log.debug("[BLR] {}", LogKvs.event("HighEnergyCut.Upload.PreUpload.Success")
                            .add("roomId", room.getRoomId())
                            .add("uname", room.getUname())
                            .add("fileName", uploadFile.getName())
                            .add("fileSizeBytes", fileSize)
                            .add("multipartEnabled", configuredMultipartEnabled)
                            .add("multipartActive", useMultipartFlow)
                            .add("chunkSize", chunkSize)
                            .add("chunkNum", chunkNum)
                            .add("endpoint", preUploadBean.getEndpoint()));
                }
            } while (preUploadBean.getOK() == 0);
        } catch (Exception e) {
            throw new RuntimeException("并发上传失败，存在异常", e);
        }
        // 分段上传
        final long effectiveChunkSize = chunkSize;
        final long effectiveChunkNum = chunkNum;
        AtomicInteger upCount = new AtomicInteger(0);
        AtomicInteger tryCount = new AtomicInteger(0);
        List<Runnable> runnableList = new ArrayList<>();
        for (int i = 0; i < effectiveChunkNum; i++) {
            long finalI = i;
            LineUploadBean finalUploadBean = uploadBean;
            PreUploadBean finalPreUploadBean = preUploadBean;
            boolean finalUseMultipartFlow = useMultipartFlow;
            String finalMultipartUploadId = multipartUploadId;
            String finalMultipartUri = multipartUri;
            String finalMultipartToken = multipartToken;
            Map<Integer, String> finalMultipartEtags = multipartEtags;
            Map<Integer, String> finalMultipartSignedUploadIds = multipartSignedUploadIds;
            Map<Integer, Integer> finalMultipartSignedPartNumbers = multipartSignedPartNumbers;
            Runnable runnable = () -> {
                try {
                    while (tryCount.get() < 200) {
                        try {
                            // 上传
                            long endSize = (finalI + 1) * effectiveChunkSize;
                            long finalChunkSize = effectiveChunkSize;
                            long startSize = finalI * finalChunkSize;
                            if (endSize > fileSize) {
                                endSize = fileSize;
                                finalChunkSize = fileSize - startSize;
                            }
                            try {
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
                                                timeoutSeconds
                                        );
                                        int partNumber = (int) (finalI + 1);
                                        finalMultipartEtags.put(partNumber, etag);
                                        finalMultipartSignedUploadIds.put(partNumber, MultipartDebugSupport.uploadIdFromUrl(signedReq.getUrl()));
                                        finalMultipartSignedPartNumbers.put(partNumber, MultipartDebugSupport.partNumberFromUrl(signedReq.getUrl()));
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
                                        ChunkUploadRequest chunkUploadRequest = new ChunkUploadRequest(finalPreUploadBean, chunkParams, randomAccessFile);
                                        chunkUploadRequest.getPage();
                                    }
                                }
                            } catch (FileNotFoundException fileNotFoundException) {
                                tryCount.set(200);
                                log.error("[BLR] {}", LogKvs.event("HighEnergyCut.Upload.Chunk.FileMissing")
                                        .add("roomId", room.getRoomId())
                                        .add("uname", room.getUname())
                                        .add("filePath", filePath)
                                        .add("partIndex", finalI));
                                break;
                            }
                            int count = upCount.incrementAndGet();
                            log.debug("[BLR] {}", LogKvs.event("HighEnergyCut.Upload.Chunk.Progress")
                                    .add("roomId", room.getRoomId())
                                    .add("uname", room.getUname())
                                    .add("filePath", filePath)
                                    .add("uploaded", count)
                                    .add("total", effectiveChunkNum)
                                    .add("thread", Thread.currentThread().getName()));
                            break;
                        } catch (Exception e) {
                            tryCount.incrementAndGet();
                            long backoffMs = uploadRetryBackoffPolicy.nextDelayMs(tryCount.get(), e.getMessage());
                            LogKvs chunkErrorLog = LogKvs.event("HighEnergyCut.Upload.Chunk.Error")
                                    .add("roomId", room.getRoomId())
                                    .add("uname", room.getUname())
                                    .add("filePath", filePath)
                                    .add("partIndex", finalI)
                                    .add("tryCount", tryCount.get())
                                    .add("backoffMs", backoffMs)
                                    .addIfNotBlank("err", e.getMessage())
                                    .add("ex", e.getClass().getSimpleName());
                            if (UploadRetryLogPolicy.shouldWarn(tryCount.get())) {
                                log.warn("[BLR] {}", chunkErrorLog, e);
                            } else {
                                log.debug("[BLR] {}", chunkErrorLog);
                            }
                            try {
                                Thread.sleep(backoffMs);
                    } catch (InterruptedException ex) {
                                log.warn("[BLR] {}", LogKvs.event("HighEnergyCut.Upload.Chunk.RetryWaitInterrupted")
                                        .add("roomId", room.getRoomId())
                                        .add("uname", room.getUname())
                                        .add("filePath", filePath)
                                        .add("partIndex", finalI)
                                        .add("sleepMs", backoffMs), ex);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("[BLR] {}", LogKvs.event("HighEnergyCut.Upload.Chunk.ThreadFailed")
                            .add("roomId", room.getRoomId())
                            .add("uname", room.getUname())
                            .add("filePath", filePath)
                            .add("partIndex", finalI)
                            .addIfNotBlank("err", e.getMessage())
                            .add("ex", e.getClass().getSimpleName()), e);
                }
            };

            runnableList.add(runnable);

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

        //通知服务器上传完成
        try {
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
                log.info("[BLR] {}", LogKvs.event("HighEnergyCut.Upload.MultipartComplete.PayloadSummary")
                        .add("roomId", room.getRoomId())
                        .add("uname", room.getUname())
                        .add("profile", completeProfile)
                        .add("bizId", multipartBizId)
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
                    log.error("[BLR] {}", LogKvs.event("HighEnergyCut.Upload.MultipartComplete.ValidationFailed")
                            .add("roomId", room.getRoomId())
                            .add("uname", room.getUname())
                            .addIfNotBlank("initUploadId", multipartUploadId)
                            .add("parts", partPayload.size())
                            .add("signedUploadIdCount", validation.getSignedUploadIdCount())
                            .addIfNotBlank("signedUploadIds", validation.getSignedUploadIds())
                            .addIfNotBlank("reason", validation.getReason())
                            .addIfNotBlank("sessionDigest", completeSessionDigest));
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
                            if (resp != null) {
                                int code = resp.getIntValue("code");
                                if (code == -403 || code == 403) {
                                    log.error("[BLR] {}", LogKvs.event("HighEnergyCut.Upload.MultipartComplete.FatalError")
                                            .add("roomId", room.getRoomId())
                                            .add("uname", room.getUname())
                                            .add("code", code)
                                            .add("message", resp.getString("message"))
                                            .addIfNotBlank("sessionDigest", completeSessionDigest)
                                            .add("willFallback", true));
                                    break;
                                }
                                if (code == -409 || code == 409) {
                                    log.warn("[BLR] {}", LogKvs.event("HighEnergyCut.Upload.MultipartComplete.ConflictRetry")
                                            .add("roomId", room.getRoomId())
                                            .add("uname", room.getUname())
                                            .add("filePath", filePath)
                                            .add("attempt", i + 1)
                                            .add("maxAttempt", 5)
                                            .add("code", code)
                                            .add("message", resp.getString("message"))
                                            .addIfNotBlank("sessionDigest", completeSessionDigest)
                                            .add("willFallback", i >= 4));
                                }
                            }
                            if (i < 4) {
                                Thread.sleep(1000);
                            }
                        } catch (Exception e) {
                            log.error("[BLR] {}", LogKvs.event("HighEnergyCut.Upload.MultipartComplete.Retry")
                                    .add("roomId", room.getRoomId())
                                    .add("uname", room.getUname())
                                    .add("filePath", filePath)
                                    .add("attempt", i + 1), e);
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
                        log.error("[BLR] {}", LogKvs.event("HighEnergyCut.Upload.Complete.Retry")
                                .add("roomId", room.getRoomId())
                                .add("uname", room.getUname())
                                .add("filePath", filePath)
                                .add("attempt", i + 1), e);
                    }
                    if (completeUploadBean != null && completeUploadBean.getOK() != null && completeUploadBean.getOK() == 1) {
                        completeSuccess = true;
                        completeResponse = JSON.toJSONString(completeUploadBean);
                        break;
                    }
                }
            }

            if (completeSuccess) {
                log.info("[BLR] {}", LogKvs.event("HighEnergyCut.Upload.Complete.Success")
                        .add("roomId", room.getRoomId())
                        .add("uname", room.getUname())
                        .add("fileName", serverFileName)
                        .add("fileSizeBytes", fileSize));
                return serverFileName;
            }
            throw new RuntimeException("合并上传文件失败：" + completeResponse);
        } catch (Exception e) {
            throw new RuntimeException("合并上传文件失败：" + e.getMessage(), e);
        }
        } finally {
            uploadFairShareService.unregisterUploadUser(biliBiliUser.getId(), room.getRoomId(), null, "HIGH_ENERGY_CUT");
        }
    }

    public Map<Integer, Integer> getHighEnergyCut(RecordHistoryPart part, double percentileRank) {
        // 1. 获取每秒的弹幕数
        List<Object[]> highEnergyCuts = liveMsgRepository.getMsgCountBySecond(part.getId());
        List<HighEnergyCut> msgCountBySecond = new ArrayList<>();
        TreeMap<Integer, Integer> timeToMsgCount = new TreeMap<>();
        for (Object[] row : highEnergyCuts) {
            int time = ((Number)row[0]).intValue();  // 时间戳（秒）
            int num = ((Number)row[1]).intValue();   // 该秒的弹幕数
            msgCountBySecond.add(new HighEnergyCut(time, num));
            timeToMsgCount.put(time, num);
        }

        if (msgCountBySecond.isEmpty()) {
            return Collections.emptyMap();
        }

        // 2. 按时间排序并构建前缀和数组
        List<Integer> sortedTimes = msgCountBySecond.stream()
                .map(HighEnergyCut::getTime)
                .sorted()
                .collect(Collectors.toList());

        int n = sortedTimes.size();
        int[] prefixSum = new int[n + 1];  // prefixSum[0] = 0

        for (int i = 0; i < n; i++) {
            prefixSum[i + 1] = prefixSum[i] + msgCountBySecond.get(i).getNum();
        }
        int maxTime = timeToMsgCount.lastKey();

        // 生成连续时间序列（从 minTime 到 maxTime）
        List<Integer> allTimes = new ArrayList<>();
        for (int t = 0; t <= maxTime; t++) {
            allTimes.add(t);
        }
        //初始化0秒时的数据
        Map<Integer, Integer> smoothedValues = new HashMap<>();
        int windowSum = 0;
        // 滑动窗口左边界
        int left = -10;
        // 滑动窗口右边界
        int right = 10;

        // 初始窗口填充
        for (int t = left; t <= right; t++) {
            windowSum += timeToMsgCount.getOrDefault(t, 0);
        }
        smoothedValues.put(0, windowSum);

        // 滑动窗口增量更新
        for (int i = 1; i < allTimes.size(); i++) {
            int prevTime = allTimes.get(i - 1);
            int currTime = allTimes.get(i);

            // 移除滑出窗口的旧元素
            int outTime = prevTime - 10;
            windowSum -= timeToMsgCount.getOrDefault(outTime, 0);

            // 添加滑入窗口的新元素
            int inTime = currTime + 10;
            windowSum += timeToMsgCount.getOrDefault(inTime, 0);

            smoothedValues.put(currTime, windowSum);
        }
        // 4. 计算分位数
        List<Integer> smoothedNums = new ArrayList<>(smoothedValues.values());
        Collections.sort(smoothedNums);
        double percentile = calculatePercentile(smoothedNums, percentileRank);
        if (percentile <= 1) {
            percentile = 1;
        }
        // 5. 筛选高能点
        List<Integer> highEnergySeconds = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : smoothedValues.entrySet()) {
            if (entry.getValue() >= percentile) {
                highEnergySeconds.add(entry.getKey());
            }
        }
        Collections.sort(highEnergySeconds);

        if (highEnergySeconds.isEmpty()) {
            return Collections.emptyMap();
        }


        // 6. 原始合并（基于连续时间点）
        List<int[]> originalIntervals = new ArrayList<>();
        int start = highEnergySeconds.get(0);
        int end = start;

        for (int i = 1; i < highEnergySeconds.size(); i++) {
            int current = highEnergySeconds.get(i);
            int prev = highEnergySeconds.get(i - 1);

            if (current - prev == 1) {  // 仅合并连续时间点
                end = current;
            } else {
                originalIntervals.add(new int[]{start, end});
                start = current;
                end = current;
            }
        }
        originalIntervals.add(new int[]{start, end});

        // 7. 时间调整（前推30秒，延长10秒）
        List<int[]> adjustedIntervals = new ArrayList<>();
        for (int[] interval : originalIntervals) {
            int originalStart = interval[0];
            int originalEnd = interval[1];
            int adjustedStart = originalStart - 30;  // 前推30秒
            int adjustedEnd = originalEnd + 40;      // 延长10秒
            adjustedIntervals.add(new int[]{adjustedStart, adjustedEnd});
        }

        // 8. 二次合并（基于调整后的区间，间隔 ≤ 60 秒）
        List<int[]> mergedIntervals = new ArrayList<>();
        if (!adjustedIntervals.isEmpty()) {
            int[] currentInterval = adjustedIntervals.get(0);
            for (int i = 1; i < adjustedIntervals.size(); i++) {
                int[] nextInterval = adjustedIntervals.get(i);
                int prevEnd = currentInterval[1];
                int nextStart = nextInterval[0];

                if (nextStart - prevEnd <= 60) {  // 间隔 ≤ 60 秒，合并
                    currentInterval[1] = Math.max(currentInterval[1], nextInterval[1]);
                } else {
                    mergedIntervals.add(currentInterval);
                    currentInterval = nextInterval;
                }
            }
            mergedIntervals.add(currentInterval);
        }

        // 9. 转换为 Map<起始秒, 持续时间>
        Map<Integer, Integer> result = new LinkedHashMap<>();
        for (int[] interval : mergedIntervals) {
            int startTime = interval[0];
            int duration = interval[1] - interval[0] + 1;
            if (startTime < 0) {
                startTime = 0;
            }
            result.put(startTime, duration);
        }
        return result;
    }

    // 分位数计算（与原代码相同）
    private double calculatePercentile(List<Integer> sortedValues, double percentileRank) {
        int n = sortedValues.size();
        if (n == 0) {
            return 0;
        }
        if (n <= 60 * 5) {
            // 小于五分钟的视频无需合并
            return 999999;
        }

        // 确保 percentileRank 在 [0, 1] 范围内
        percentileRank = Math.max(0.0, Math.min(1.0, percentileRank));

        double index = (n - 1) * percentileRank + 1;
        int floor = (int)Math.floor(index); // 下取整
        int ceil = floor + 1;

        // 修正：确保 floor 和 ceil 在有效范围内
        floor = Math.max(0, Math.min(n - 1, floor));
        ceil = Math.max(0, Math.min(n - 1, ceil));

        double fraction = index - floor;
        return sortedValues.get(floor) + fraction * (sortedValues.get(ceil) - sortedValues.get(floor));
    }

    private String template(String template, Map<String, Object> map) {
        template = template.replace("${uname}", map.get("${uname}") != null ? map.get("${uname}").toString() : "")
                .replace("${title}", map.get("${title}") != null ? map.get("${title}").toString() : "")
                .replace("${index}", map.get("${index}") != null ? map.get("${index}").toString() : "")
                .replace("${areaName}", map.get("${areaName}") != null ? map.get("${areaName}").toString() : "")
                .replace("${roomId}", map.get("${roomId}") != null ? map.get("${roomId}").toString() : "");
        if (template.contains("${")) {
            try {
                LocalDateTime localDateTime = (LocalDateTime)map.get("date");
                String substring = template.substring(template.indexOf("${"));
                String date = substring.substring(0, substring.indexOf("}") + 1);
                String format = localDateTime.format(DateTimeFormatter.ofPattern(date.substring(2, date.length() - 1)));
                template = template.replace(date, format);
            } catch (Exception e) {
                log.error("[BLR] {}", LogKvs.event("Template.DateFormat.Failed")
                        .addIfNotBlank("template", template));
            }
        }
        template = template.replace(",,", ",");
        return template;
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
        // 优先锁定 multipart/new 返回的 profile，保证同一会话 init/part/complete 一致
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

    private JSONObject parseJsonObject(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            return JSON.parseObject(raw);
        } catch (Exception e) {
            log.warn("[BLR] {}", LogKvs.event("HighEnergyCut.Publish.WebPublish.ParseFailed")
                    .add("respLen", raw.length())
                    .add("err", e.getMessage())
                    .addIfNotBlank("respSnippet", abbreviatePublishResponse(raw, 320)));
            return null;
        }
    }

    private String abbreviatePublishResponse(String raw, int maxLen) {
        if (StringUtils.isBlank(raw)) {
            return "";
        }
        String normalized = raw.replace("\r", " ").replace("\n", " ");
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, maxLen) + "...";
    }
}


