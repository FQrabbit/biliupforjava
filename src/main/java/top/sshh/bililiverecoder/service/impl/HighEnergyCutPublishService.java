package top.sshh.bililiverecoder.service.impl;

import com.alibaba.fastjson.JSON;
import com.jayway.jsonpath.JsonPath;
import com.zjiecode.wxpusher.client.WxPusher;
import com.zjiecode.wxpusher.client.bean.Message;
import lombok.extern.slf4j.Slf4j;
import net.bramp.ffmpeg.FFmpeg;
import net.bramp.ffmpeg.FFmpegExecutor;
import net.bramp.ffmpeg.builder.FFmpegBuilder;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
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
import top.sshh.bililiverecoder.util.BiliApi;
import top.sshh.bililiverecoder.util.LogKvs;
import top.sshh.bililiverecoder.util.UploadEnums;
import top.sshh.bililiverecoder.util.bili.Cookie;
import top.sshh.bililiverecoder.util.bili.WebCookie;
import top.sshh.bililiverecoder.util.bili.upload.ChunkUploadRequest;
import top.sshh.bililiverecoder.util.bili.upload.CompleteUploadRequest;
import top.sshh.bililiverecoder.util.bili.upload.LineUploadRequest;
import top.sshh.bililiverecoder.util.bili.upload.PreUploadRequest;
import top.sshh.bililiverecoder.util.bili.upload.pojo.CompleteUploadBean;
import top.sshh.bililiverecoder.util.bili.upload.pojo.LineUploadBean;
import top.sshh.bililiverecoder.util.bili.upload.pojo.PreUploadBean;
import top.sshh.bililiverecoder.service.CaptchaService;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Component
public class HighEnergyCutPublishService {

    @Autowired
    private CaptchaService captchaService;

    @Value("${server.port:8080}")
    private String serverPort;

    public static final Map<Long, String> taskRunningMsg = new ConcurrentHashMap<>();
    private static final String WX_MSG_FORMAT = """
            结果: %s
            主播%s高能切片事件
            房间名: %s
            时间: %s
            原因: %s
            """;

    @Value("${record.work-path}")
    private String wxToken;
    @Autowired
    private BiliUserRepository biliUserRepository;
    @Autowired
    private RecordHistoryPartRepository partRepository;
    @Autowired
    private RecordRoomRepository roomRepository;
    @Autowired
    private LiveMsgRepository liveMsgRepository;


    public void process(RecordHistory history) throws IOException {
        List<RecordHistoryPart> partList = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());
        RecordRoom room = roomRepository.findByRoomId(history.getRoomId());
        String wxuid = room.getWxuid();
        // 初始化 FFmpeg
        FFmpeg ffmpeg = new FFmpeg();
        FFmpegExecutor executor = new FFmpegExecutor(ffmpeg);
        Path outputPath = Path.of(history.getFilePath(), "cut");
        if (taskRunningMsg.get(history.getId()) != null) {
            return;
        }
        try {

            taskRunningMsg.put(history.getId(), "创建输出目录");
            if (!Files.exists(outputPath)) {
                Files.createDirectories(outputPath);
            } else {
                Files.walk(outputPath)
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
                Files.createDirectories(outputPath);
            }


            taskRunningMsg.put(history.getId(), "开始分片");
            //循环处理每个分片
            int i = 0;
            int count = 0;
            for (RecordHistoryPart part : partList) {
                Map<Integer, Integer> highEnergyCut = getHighEnergyCut(part, room.getPercentileRank());
                List<Integer> cutList = highEnergyCut.keySet().stream().sorted().toList();
                count += cutList.size();
                for (Integer time : cutList) {
                    i++;
                    String output = Path.of(outputPath.toString(), String.format("%05d", i) + "." + part.getFilePath().substring(part.getFilePath().lastIndexOf(".") + 1)).toString();
                    int startTime = time;
                    int duration = highEnergyCut.get(time);

                    FFmpegBuilder builder = new FFmpegBuilder()
                            .setInput(part.getFilePath())
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
            }
            // 创建 list.txt
            Path listFile = outputPath.resolve("list.txt");
            StringBuilder sb = new StringBuilder();
            for (String file : files) {
                sb.append("file '").append(file).append("'\n");
            }
            Files.writeString(listFile, sb.toString());
            String output = Path.of(outputPath.toString(), "output.mp4").toString();
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
            if (StringUtils.isNotBlank(wxuid)) {
                Message message = new Message();
                message.setAppToken(wxToken);
                message.setContentType(Message.CONTENT_TYPE_TEXT);
                message.setContent(WX_MSG_FORMAT.formatted("直播剪辑生成视频", room.getUname(), room.getTitle(),
                        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")), "耗时" + (System.currentTimeMillis() - currentTimeMillis) / 1000) + "秒");
                message.setUid(wxuid);
                WxPusher.send(message);
            }
            taskRunningMsg.put(history.getId(), "开始上传");
            String upload = upload(room, output);
            taskRunningMsg.put(history.getId(), "开始投稿");
            publish(history, upload);
        } catch (Exception e) {
            log.error("[BLR] {}", LogKvs.event("HighEnergyCut.Process.Failed")
                    .add("historyId", history.getId())
                    .add("roomId", history.getRoomId())
                    .addIfNotBlank("title", history.getTitle()), e);
            if (StringUtils.isNotBlank(wxuid)) {
                Message message = new Message();
                message.setAppToken(wxToken);
                message.setContentType(Message.CONTENT_TYPE_TEXT);
                message.setContent(WX_MSG_FORMAT.formatted("直播剪辑失败", room.getUname(), room.getTitle(),
                        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")), e));
                message.setUid(wxuid);
                WxPusher.send(message);
            }
        } finally {
            taskRunningMsg.remove(history.getId());
            //删除所有生成的文件
            Files.walk(outputPath)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    public void publish(RecordHistory history, String fileName) throws InterruptedException {
        RecordRoom room = roomRepository.findByRoomId(history.getRoomId());
        String wxuid = room.getWxuid();
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

        SingleVideoDto dto = new SingleVideoDto();
        dto.setTitle(room.getUname() + "直播录像剪辑");
        dto.setDesc("");
        dto.setFilename(fileName);

        VideoUploadDto videoUploadDto = new VideoUploadDto();
        videoUploadDto.setTitle(this.template("【直播剪辑】【${uname}】${title} ${yyyy年MM月dd日HH点mm分}", map));
        videoUploadDto.setTid(room.getTid());
        videoUploadDto.setCover(history.getCoverUrl());
        videoUploadDto.setCopyright(1);
        videoUploadDto.setDesc("直播间: https://live.bilibili.com/" + room.getRoomId() + "  稿件直播源\n自动剪辑投稿技术支持https://space.bilibili.com/10043269");
        videoUploadDto.setVideos(Collections.singletonList(dto));
        videoUploadDto.setTag(this.template(room.getTags(), map));

        Optional<BiliBiliUser> userOptional = biliUserRepository.findById(room.getUploadUserId());
        BiliBiliUser biliBiliUser = userOptional.get();

        String uploadRes = null;
        uploadRes = BiliApi.webPublish(biliBiliUser, videoUploadDto);
        log.info("[BLR] {}", LogKvs.event("HighEnergyCut.Publish.WebPublish.Response")
                .add("roomId", room.getRoomId())
                .add("uname", room.getUname())
                .add("historyId", history.getId())
                .add("respLen", uploadRes == null ? 0 : uploadRes.length())
                .add("containsCaptcha", uploadRes != null && uploadRes.contains("验证码")));
        if (uploadRes.contains("验证码")) {
            try {
                String voucher = JsonPath.read(uploadRes, "data.v_voucher");
                Map<String, Object> data = JsonPath.read(uploadRes, "data");
                captchaService.setCaptchaRequired(voucher, history.getTitle(), data);
                log.warn("[BLR] {}", LogKvs.event("Upload.Captcha.Required")
                        .add("roomId", room.getRoomId())
                        .add("uname", room.getUname())
                        .add("historyId", history.getId())
                        .addIfNotBlank("title", history.getTitle())
                        .addUrl("captchaUrl", "http://localhost:" + serverPort + "/html/captcha.html"));
                Map<String, String> captchaResult = captchaService.waitForCaptcha();
                if (captchaResult != null) {
                    if (!captchaResult.containsKey("v_voucher")) {
                        captchaResult.put("v_voucher", voucher);
                    }
                    log.info("[BLR] {}", LogKvs.event("HighEnergyCut.Publish.Captcha.Submit")
                            .add("roomId", room.getRoomId())
                            .add("uname", room.getUname())
                            .add("historyId", history.getId())
                            .add("hasV4", captchaResult.containsKey("captcha_key"))
                            .add("hasVoucher", captchaResult.containsKey("v_voucher")));
                    uploadRes = BiliApi.webPublish(biliBiliUser, videoUploadDto, captchaResult);
                    log.info("[BLR] {}", LogKvs.event("HighEnergyCut.Publish.Captcha.PublishResponse")
                            .add("roomId", room.getRoomId())
                            .add("uname", room.getUname())
                            .add("historyId", history.getId())
                            .add("respLen", uploadRes == null ? 0 : uploadRes.length())
                            .add("containsCaptcha", uploadRes != null && uploadRes.contains("验证码")));

                    if (uploadRes.contains("验证码") || uploadRes.contains("\"code\":601")) {
                        log.error("[BLR] {}", LogKvs.event("HighEnergyCut.Publish.Captcha.VerifyFailedPause")
                                .add("roomId", room.getRoomId())
                                .add("uname", room.getUname())
                                .add("historyId", history.getId())
                                .add("pauseSeconds", 300));
                        Thread.sleep(300 * 1000L);
                        throw new RuntimeException("验证码验证失败: " + uploadRes);
                    }
                } else {
                    log.warn("[BLR] {}", LogKvs.event("Upload.Captcha.Timeout")
                            .add("roomId", room.getRoomId())
                            .add("uname", room.getUname())
                            .add("historyId", history.getId()));
                    Thread.sleep(10 * 1000L);
                    uploadRes = BiliApi.webPublish(biliBiliUser, videoUploadDto);
                }
            } catch (Exception e) {
                log.error("[BLR] {}", LogKvs.event("HighEnergyCut.Publish.Captcha.HandleError")
                        .add("roomId", room.getRoomId())
                        .add("uname", room.getUname())
                        .add("historyId", history.getId()), e);
                Thread.sleep(120 * 1000L);
                uploadRes = BiliApi.webPublish(biliBiliUser, videoUploadDto);
                log.info("[BLR] {}", LogKvs.event("HighEnergyCut.Publish.WebPublish.Response")
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
                log.error("[BLR] {}", LogKvs.event("HighEnergyCut.Publish.TimestampJump.GiveUp")
                        .add("roomId", room.getRoomId())
                        .add("uname", room.getUname())
                        .add("historyId", history.getId())
                        .addIfNotBlank("title", history.getTitle())
                        .add("code", 21588));
                // 不抛出异常，直接返回，避免无意义的重试
                return;
            }
            log.warn("[BLR] {}", LogKvs.event("HighEnergyCut.Publish.MissingIds")
                    .add("roomId", room.getRoomId())
                    .add("uname", room.getUname())
                    .add("historyId", history.getId())
                    .add("respLen", uploadRes == null ? 0 : uploadRes.length()));
            if (StringUtils.isNotBlank(wxuid)) {
                Message message = new Message();
                message.setAppToken(wxToken);
                message.setContentType(Message.CONTENT_TYPE_TEXT);
                message.setContent(WX_MSG_FORMAT.formatted("直播剪辑投稿失败", room.getUname(), room.getTitle(),
                        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")), uploadRes));
                message.setUid(wxuid);
                WxPusher.send(message);
            }
            throw new RuntimeException(uploadRes);
        }
        log.info("[BLR] {}", LogKvs.event("HighEnergyCut.Publish.Success")
                .add("roomId", room.getRoomId())
                .add("uname", room.getUname())
                .add("historyId", history.getId())
                .addIfNotBlank("title", history.getTitle())
                .addIfNotBlank("bvid", bvid)
                .addIfNotBlank("aid", aid));
        if (StringUtils.isNotBlank(wxuid)) {
            Message message = new Message();
            message.setAppToken(wxToken);
            message.setContentType(Message.CONTENT_TYPE_TEXT);
            message.setContent(WX_MSG_FORMAT.formatted("直播剪辑投稿成功", room.getUname(), room.getTitle(),
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")), uploadRes));
            message.setUid(wxuid);
            WxPusher.send(message);
        }
    }

    public String upload(RecordRoom room, String filePath) {

        UploadEnums uploadEnums = UploadEnums.find(room.getLine());
        Optional<BiliBiliUser> userOptional = biliUserRepository.findById(room.getUploadUserId());
        BiliBiliUser biliBiliUser = userOptional.get();
        WebCookie webCookie = Cookie.parse(biliBiliUser.getCookies());

        File uploadFile = new File(filePath);
        Map<String, String> preParams = new HashMap<>();
        preParams.put("r", uploadEnums.getOs());
        preParams.put("profile", uploadEnums.getProfile());
        preParams.put("ssl", "0");
        preParams.put("version", "2.14.0.0");
        preParams.put("build", "2140000");
        preParams.put("webVersion", "2.14.0");
        preParams.put("name", uploadFile.getName());
        preParams.put("size", String.valueOf(uploadFile.length()));
        long fileSize = uploadFile.length();
        long chunkSize = 1024 * 1024 * 5;
        long chunkNum = (long)Math.ceil((double)fileSize / chunkSize);
        PreUploadRequest preuploadRequest = new PreUploadRequest(webCookie, preParams);
        preuploadRequest.setLineQuery(uploadEnums.getLineQuery());
        PreUploadBean preUploadBean;
        LineUploadBean uploadBean = null;
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
                        captchaService.setCaptchaRequired(voucher, uploadFile.getName(), preUploadBean.getDetail());
                        Map<String, String> result = captchaService.waitForCaptcha();
                        if (result != null) {
                            preParams.putAll(result);
                        }
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
                    LineUploadRequest uploadRequest = new LineUploadRequest(webCookie, preUploadBean);
                    uploadBean = uploadRequest.getPojo();
                    log.debug("[BLR] {}", LogKvs.event("HighEnergyCut.Upload.PreUpload.Success")
                            .add("roomId", room.getRoomId())
                            .add("uname", room.getUname())
                            .add("fileName", uploadFile.getName())
                            .add("fileSizeBytes", fileSize)
                            .add("endpoint", preUploadBean.getEndpoint()));
                }
            } while (preUploadBean.getOK() == 0);
        } catch (Exception e) {
            throw new RuntimeException("并发上传失败，存在异常", e);
        }
        // 分段上传
        AtomicInteger upCount = new AtomicInteger(0);
        AtomicInteger tryCount = new AtomicInteger(0);
        List<Runnable> runnableList = new ArrayList<>();
        for (int i = 0; i < chunkNum; i++) {
            long finalI = i;
            LineUploadBean finalUploadBean = uploadBean;
            PreUploadBean finalPreUploadBean = preUploadBean;
            Runnable runnable = () -> {
                try {
                    while (tryCount.get() < 200) {
                        try {
                            // 上传
                            long endSize = (finalI + 1) * chunkSize;
                            long finalChunkSize = chunkSize;
                            Map<String, String> chunkParams = new HashMap<>();
                            chunkParams.put("partNumber", String.valueOf(finalI + 1));
                            chunkParams.put("uploadId", finalUploadBean.getUpload_id());
                            chunkParams.put("chunk", String.valueOf(finalI));
                            chunkParams.put("chunks", String.valueOf(chunkNum));
                            chunkParams.put("size", String.valueOf(finalChunkSize));
                            chunkParams.put("start", String.valueOf(finalI * finalChunkSize));
                            chunkParams.put("end", String.valueOf(endSize));
                            chunkParams.put("total", String.valueOf(fileSize));
                            if (endSize > fileSize) {
                                endSize = fileSize;

                                finalChunkSize = fileSize - (finalI * finalChunkSize);
                                chunkParams.put("size", String.valueOf(finalChunkSize));
                                chunkParams.put("end", String.valueOf(endSize));
                            }
                            try {
                                ChunkUploadRequest chunkUploadRequest = new ChunkUploadRequest(finalPreUploadBean, chunkParams, new RandomAccessFile(filePath, "r"));
                                chunkUploadRequest.getPage();
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
                                    .add("total", chunkNum)
                                    .add("thread", Thread.currentThread().getName()));
                            break;
                        } catch (Exception e) {
                            tryCount.incrementAndGet();
                            log.warn("[BLR] {}", LogKvs.event("HighEnergyCut.Upload.Chunk.Error")
                                    .add("roomId", room.getRoomId())
                                    .add("uname", room.getUname())
                                    .add("filePath", filePath)
                                    .add("partIndex", finalI)
                                    .add("tryCount", tryCount.get())
                                    .addIfNotBlank("err", e.getMessage())
                                    .add("ex", e.getClass().getSimpleName()), e);
                            try {
                                //                                                log.info("上传失败等待十秒==>{}", uploadFile.getName());
                                Thread.sleep(10000L);
                            } catch (InterruptedException ex) {
                                log.warn("[BLR] {}", LogKvs.event("HighEnergyCut.Upload.Chunk.RetryWaitInterrupted")
                                        .add("roomId", room.getRoomId())
                                        .add("uname", room.getUname())
                                        .add("filePath", filePath)
                                        .add("partIndex", finalI), ex);
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

        runnableList.stream().parallel().forEach(Runnable::run);
        //通知服务器上传完成
        Map<String, String> completeParams = new HashMap<>();
        completeParams.put("profile", uploadEnums.getProfile());
        completeParams.put("name", uploadFile.getName());
        completeParams.put("uploadId", uploadBean.getUpload_id());
        completeParams.put("biz_id", String.valueOf(preUploadBean.getBiz_id()));
        Map<String, Object> bodyMap = new LinkedHashMap<>(1);
        List<Map<String, String>> chunkMaps = new ArrayList<>((int)chunkNum);
        for (int i = 1; i <= chunkNum; i++) {
            Map<String, String> partMap = new LinkedHashMap<>(2);
            partMap.put("partNumber", String.valueOf(i));
            partMap.put("eTag", "etag");
            chunkMaps.add(partMap);
        }
        bodyMap.put("parts", chunkMaps);
        CompleteUploadRequest completeUploadRequest = new CompleteUploadRequest(preUploadBean, completeParams, JSON.toJSONString(bodyMap));

        try {
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
                    break;
                }
            }

            if (completeUploadBean != null && completeUploadBean.getOK() != null && completeUploadBean.getOK() == 1) {
                log.info("[BLR] {}", LogKvs.event("HighEnergyCut.Upload.Complete.Success")
                        .add("roomId", room.getRoomId())
                        .add("uname", room.getUname())
                        .add("fileName", uploadBean.getFileName())
                        .add("fileSizeBytes", fileSize));
                return uploadBean.getFileName();
            } else {
                throw new RuntimeException("合并上传文件失败：" + JSON.toJSONString(completeUploadBean));
            }

        } catch (Exception e) {
            throw new RuntimeException("合并上传文件失败：" + e.getMessage());
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
}
