package top.sshh.bililiverecoder.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.jayway.jsonpath.JsonPath;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import top.sshh.bililiverecoder.lifecycle.ShutdownState;
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.entity.data.*;
import top.sshh.bililiverecoder.job.LiveMsgSendSync;
import top.sshh.bililiverecoder.job.videoSyncJob;
import top.sshh.bililiverecoder.repo.*;
import top.sshh.bililiverecoder.service.CaptchaService;
import top.sshh.bililiverecoder.service.PartFileCleanupPolicy;
import top.sshh.bililiverecoder.service.PartFileOperationService;
import top.sshh.bililiverecoder.service.PartFileLocationService;
import top.sshh.bililiverecoder.service.RecordPartPathService;
import top.sshh.bililiverecoder.service.RoomLiveEventXmlIssueService;
import top.sshh.bililiverecoder.service.StorageRootService;
import top.sshh.bililiverecoder.service.UploadServiceFactory;
import top.sshh.bililiverecoder.service.UploadUserSerialScheduler;
import top.sshh.bililiverecoder.util.BiliApi;
import top.sshh.bililiverecoder.util.TaskUtil;
import top.sshh.bililiverecoder.util.LogKvs;
import top.sshh.bililiverecoder.util.bili.Cookie;
import top.sshh.bililiverecoder.util.bili.WebCookie;
import top.sshh.bililiverecoder.util.bili.user.UserMy;
import top.sshh.bililiverecoder.util.bili.user.UserMyRootBean;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public class RecordBiliPublishService {

    @Value("${record.work-path}")
    private String workPath;

    @Value("${server.port:8080}")
    private String serverPort;

    private static final java.util.concurrent.ConcurrentHashMap<Long, LocalDateTime> suspendMap = new java.util.concurrent.ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, EditPartsTaskStatus> editPartsTaskMap = new ConcurrentHashMap<>();

    private static final int UPLOAD_RETRY_GIVE_UP = 9999;
    private static final long EDIT_TEMP_TTL_MS = 24L * 60L * 60L * 1000L;

    @PostConstruct
    public void initWorkPath() {
        workPath = workPath.replaceAll("\\\\\\\\", "\\\\");
        workPath = workPath.replace("\\", "/");
        editPartsTaskMap.clear();
        cleanupExpiredEditPartTempFiles();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedEditPartsUploads() {
        List<RecordHistory> interrupted = historyRepository.findByEditPartsUploadingTrue();
        if (interrupted.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (RecordHistory history : interrupted) {
            history.setEditPartsUploading(false);
            history.setUpdateTime(now);
        }
        historyRepository.saveAll(interrupted);
        log.warn("[BLR] {}", LogKvs.event("Publish.EditParts.InterruptedRecovered")
                .addRoundCount("history", interrupted.size()));
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
    private UploadUserSerialScheduler uploadUserSerialScheduler;
    @Autowired
    private HighEnergyCutPublishService highEnergyCutPublishService;
    @Autowired
    private LiveMsgService liveMsgService;
    @Autowired
    private LiveMsgRepository msgRepository;
    @Autowired
    private CaptchaService captchaService;
    @Autowired
    private ShutdownState shutdownState;
    @Autowired
    private videoSyncJob videoSyncJob;
    @Autowired
    private LiveMsgSendSync liveMsgSendSync;
    @Autowired
    private PartFileCleanupPolicy partFileCleanupPolicy;
    @Autowired
    private PartFileOperationService partFileOperationService;
    @Autowired
    private PartFileLocationService partFileLocationService;
    @Autowired
    private StorageRootService storageRootService;
    @Autowired
    private RecordPartPathService partPathService;
    @Autowired
    private RoomLiveEventXmlIssueService xmlIssueService;

    @Async
    public void asyncPublishRecordHistory(RecordHistory history) {
        if (shutdownState.isShuttingDown() || Thread.currentThread().isInterrupted()) {
            return;
        }
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

    Map<String, Object> buildHistoryTemplateMap(RecordHistory history, RecordRoom room) {
        Map<String, Object> map = new HashMap<>();
        map.put("date", history == null ? null : history.getStartTime());
        map.put("${uname}", room == null ? "" : StringUtils.defaultString(room.getUname()));
        map.put("${title}", history == null ? "直播录像" : StringUtils.defaultIfBlank(history.getTitle(), "直播录像"));
        map.put("${roomId}", room == null ? null : room.getRoomId());
        map.put("${areaName}", "");
        return map;
    }

    Map<String, Object> buildPartTemplateMap(Map<String, Object> historyTemplateMap, RecordHistoryPart uploadPart, int index) {
        Map<String, Object> map = new HashMap<>(historyTemplateMap);
        map.put("${title}", uploadPart == null ? "直播录像" : StringUtils.defaultIfBlank(uploadPart.getLiveTitle(), "直播录像"));
        map.put("date", uploadPart == null ? null : uploadPart.getStartTime());
        map.put("${index}", index);
        map.put("${areaName}", uploadPart == null ? "" : uploadPart.getAreaName());
        String filePath = uploadPart == null ? null : normalizeFilePath(uploadPart.getFilePath());
        map.put("${fileName}", extractFileNameNoExt(filePath));
        return map;
    }

    @Async
    public void asyncRepublishRecordHistory(RecordHistory history) {
        if (shutdownState.isShuttingDown() || Thread.currentThread().isInterrupted()) {
            return;
        }
        if (history == null) {
            return;
        }
        editPublishedHistory(history, "republish");
    }

    public Map<String, Object> buildEditPartsDraft(Long historyId) {
        Map<String, Object> result = new LinkedHashMap<>();
        Optional<RecordHistory> historyOptional = historyRepository.findById(historyId);
        if (historyOptional.isEmpty()) {
            result.put("canEdit", false);
            result.put("message", "稿件不存在");
            result.put("items", List.of());
            return result;
        }
        RecordHistory history = historyOptional.get();
        RecordRoom room = roomRepository.findByRoomId(history.getRoomId());
        EditAuthContext auth = resolveEditAuth(history, room);
        result.put("canEdit", auth.canEdit);
        result.put("message", auth.message);
        result.put("historyId", history.getId());
        result.put("avId", history.getAvId());
        result.put("bvId", history.getBvId());
        if (!auth.canEdit || auth.user == null) {
            result.put("items", List.of());
            return result;
        }
        BiliVideoPartInfoResponse partInfo = loadOnlinePartInfo(auth.user, history);
        List<BiliVideoPartInfoResponse.Video> onlineVideos = new ArrayList<>();
        if (partInfo != null && partInfo.getData() != null && partInfo.getData().getVideos() != null) {
            onlineVideos.addAll(partInfo.getData().getVideos());
        }
        onlineVideos.sort(Comparator.comparingInt(BiliVideoPartInfoResponse.Video::getPage));
        List<RecordHistoryPart> localParts = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());
        Map<Integer, RecordHistoryPart> localByPage = localParts.stream()
                .filter(p -> p.getPage() > 0)
                .collect(Collectors.toMap(RecordHistoryPart::getPage, Function.identity(), (a, b) -> a));
        Map<String, RecordHistoryPart> localByTitle = new HashMap<>();
        for (RecordHistoryPart part : localParts) {
            if (StringUtils.isNotBlank(part.getTitle())) {
                localByTitle.putIfAbsent(part.getTitle(), part);
            }
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (BiliVideoPartInfoResponse.Video video : onlineVideos) {
            if (video == null) {
                continue;
            }
            RecordHistoryPart local = localByPage.get(video.getPage());
            if (local == null && StringUtils.isNotBlank(video.getTitle())) {
                local = localByTitle.get(video.getTitle());
            }
            if (local == null && StringUtils.isNotBlank(video.getPart())) {
                local = localByTitle.get(video.getPart());
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("onlinePage", video.getPage());
            item.put("page", video.getPage());
            item.put("title", StringUtils.defaultIfBlank(video.getTitle(), StringUtils.defaultIfBlank(video.getPart(), local == null ? "" : local.getTitle())));
            item.put("part", video.getPart());
            item.put("filename", video.getFilename());
            item.put("cid", video.getCid());
            item.put("duration", video.getDuration());
            item.put("aid", video.getAid());
            item.put("bvid", video.getBvid());
            item.put("partId", local == null ? null : local.getId());
            item.put("filePath", local == null ? null : local.getFilePath());
            item.put("fileSize", local == null ? 0 : local.getFileSize());
            item.put("source", "online");
            items.add(item);
        }
        result.put("items", items);
        result.put("onlineCount", items.size());
        result.put("code", partInfo == null ? null : partInfo.getCode());
        result.put("apiMessage", partInfo == null ? null : partInfo.getMessage());
        return result;
    }

    public Map<String, Object> saveEditPartTempFile(Long historyId, String sessionId, MultipartFile file) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (file == null || file.isEmpty()) {
            result.put("success", false);
            result.put("message", "文件为空");
            return result;
        }
        Optional<RecordHistory> historyOptional = historyRepository.findById(historyId);
        if (historyOptional.isEmpty()) {
            result.put("success", false);
            result.put("message", "稿件不存在");
            return result;
        }
        String safeSession = safePathSegment(StringUtils.defaultIfBlank(sessionId, UUID.randomUUID().toString()));
        String safeName = safeFileName(StringUtils.defaultIfBlank(file.getOriginalFilename(), "upload.mp4"));
        Path dir = Paths.get(workPath, "_edit_uploads", String.valueOf(historyId), safeSession).normalize();
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(System.currentTimeMillis() + "-" + safeName).normalize();
            if (!target.startsWith(Paths.get(workPath).normalize())) {
                result.put("success", false);
                result.put("message", "文件路径非法");
                return result;
            }
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
            result.put("success", true);
            result.put("sessionId", safeSession);
            result.put("fileRef", target.toString());
            result.put("filePath", target.toString());
            result.put("name", safeName);
            result.put("size", Files.size(target));
            return result;
        } catch (IOException e) {
            log.warn("[BLR] {}", LogKvs.event("Publish.EditParts.TempUploadFailed")
                    .add("historyId", historyId)
                    .addIfNotBlank("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()), e);
            result.put("success", false);
            result.put("message", e.getMessage());
            return result;
        }
    }

    public Map<String, Object> saveEditPartTempFileChunk(Long historyId,
                                                         String sessionId,
                                                         String uploadId,
                                                         String fileName,
                                                         int chunkIndex,
                                                         int totalChunks,
                                                         long totalSize,
                                                         MultipartFile chunk) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (chunk == null || chunk.isEmpty()) {
            result.put("success", false);
            result.put("message", "分片文件为空");
            return result;
        }
        if (chunkIndex < 0 || totalChunks <= 0 || chunkIndex >= totalChunks) {
            result.put("success", false);
            result.put("message", "分片序号非法");
            return result;
        }
        Optional<RecordHistory> historyOptional = historyRepository.findById(historyId);
        if (historyOptional.isEmpty()) {
            result.put("success", false);
            result.put("message", "稿件不存在");
            return result;
        }
        String safeSession = safePathSegment(StringUtils.defaultIfBlank(sessionId, UUID.randomUUID().toString()));
        String safeUploadId = safePathSegment(StringUtils.defaultIfBlank(uploadId, UUID.randomUUID().toString()));
        String safeName = safeFileName(StringUtils.defaultIfBlank(fileName, "upload.mp4"));
        Path workRoot = Paths.get(workPath).normalize();
        Path dir = Paths.get(workPath, "_edit_uploads", String.valueOf(historyId), safeSession).normalize();
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(safeUploadId + "-" + safeName).normalize();
            if (!target.startsWith(workRoot)) {
                result.put("success", false);
                result.put("message", "文件路径非法");
                return result;
            }
            if (chunkIndex == 0) {
                Files.deleteIfExists(target);
                Files.createFile(target);
            } else if (!Files.exists(target)) {
                result.put("success", false);
                result.put("message", "上传会话已失效，请重新选择文件");
                return result;
            }
            try (var in = chunk.getInputStream();
                 var out = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                in.transferTo(out);
            }
            long currentSize = Files.size(target);
            boolean complete = chunkIndex + 1 >= totalChunks;
            if (complete && totalSize > 0 && currentSize != totalSize) {
                Files.deleteIfExists(target);
                result.put("success", false);
                result.put("message", "上传文件大小校验失败，请重新上传");
                return result;
            }
            result.put("success", true);
            result.put("complete", complete);
            result.put("sessionId", safeSession);
            result.put("uploadId", safeUploadId);
            result.put("chunkIndex", chunkIndex);
            result.put("totalChunks", totalChunks);
            result.put("uploadedSize", currentSize);
            result.put("name", safeName);
            result.put("size", complete ? currentSize : totalSize);
            if (complete) {
                result.put("fileRef", target.toString());
                result.put("filePath", target.toString());
            }
            return result;
        } catch (IOException e) {
            if (e instanceof java.nio.file.NoSuchFileException) {
                result.put("success", false);
                result.put("message", "上传已取消");
                result.put("cancelled", true);
                return result;
            }
            log.warn("[BLR] {}", LogKvs.event("Publish.EditParts.TempUploadFailed")
                    .add("historyId", historyId)
                    .add("chunkIndex", chunkIndex)
                    .add("totalChunks", totalChunks)
                    .addIfNotBlank("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()), e);
            result.put("success", false);
            result.put("message", e.getMessage());
            return result;
        }
    }

    public Map<String, Object> cancelEditPartTempUpload(Long historyId, String sessionId, String uploadId, String fileName) {
        Map<String, Object> result = new LinkedHashMap<>();
        String safeSession = safePathSegment(StringUtils.defaultIfBlank(sessionId, ""));
        String safeUploadId = safePathSegment(StringUtils.defaultIfBlank(uploadId, ""));
        String safeName = safeFileName(StringUtils.defaultIfBlank(fileName, "upload.mp4"));
        if (StringUtils.isBlank(safeSession) || StringUtils.isBlank(safeUploadId)) {
            result.put("success", false);
            result.put("message", "上传会话无效");
            return result;
        }
        try {
            Path workRoot = Paths.get(workPath).normalize();
            Path target = Paths.get(workPath, "_edit_uploads", String.valueOf(historyId), safeSession, safeUploadId + "-" + safeName).normalize();
            if (!target.startsWith(workRoot)) {
                result.put("success", false);
                result.put("message", "文件路径非法");
                return result;
            }
            boolean deleted = Files.deleteIfExists(target);
            result.put("success", true);
            result.put("deleted", deleted);
            return result;
        } catch (IOException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return result;
        }
    }

    public Map<String, Object> submitEditParts(Long historyId, Map<String, Object> request) {
        Map<String, Object> result = new LinkedHashMap<>();
        EditPartsTaskStatus running = editPartsTaskMap.get(historyId);
        if (running != null && ("RUNNING".equals(running.status) || "QUEUED".equals(running.status))) {
            result.put("accepted", false);
            result.put("message", "已有分P编辑任务正在执行");
            return result;
        }
        Optional<RecordHistory> historyOptional = historyRepository.findById(historyId);
        if (historyOptional.isEmpty()) {
            result.put("accepted", false);
            result.put("message", "稿件不存在");
            return result;
        }
        RecordHistory history = historyOptional.get();
        RecordRoom room = roomRepository.findByRoomId(history.getRoomId());
        EditAuthContext auth = resolveEditAuth(history, room);
        if (!auth.canEdit || auth.user == null) {
            result.put("accepted", false);
            result.put("message", auth.message);
            return result;
        }
        EditPartsTaskStatus status = new EditPartsTaskStatus();
        status.status = "QUEUED";
        status.message = "等待处理";
        status.historyId = historyId;
        status.startTime = LocalDateTime.now();
        status.sessionId = request == null ? null : stringValue(request.get("sessionId"));
        markHistoryWorkingForEdit(history, status);
        editPartsTaskMap.put(historyId, status);
        Thread worker = new Thread(() -> runEditPartsSubmit(historyId, request, status), "edit-parts-" + historyId);
        worker.setDaemon(true);
        worker.start();
        result.put("accepted", true);
        result.put("status", status.status);
        result.put("historyCode", status.historyCode);
        result.put("historyEditPartsUploading", status.historyEditPartsUploading);
        result.put("historyStatus", status.historyStatus);
        return result;
    }

    public Map<String, Object> getEditPartsTask(Long historyId) {
        EditPartsTaskStatus status = editPartsTaskMap.get(historyId);
        if (status == null) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("status", "NONE");
            return empty;
        }
        return status.toMap();
    }

    public Map<String, Object> cleanupEditPartTempFiles(Long historyId, String sessionId) {
        Map<String, Object> result = new LinkedHashMap<>();
        EditPartsTaskStatus running = editPartsTaskMap.get(historyId);
        if (running != null
                && StringUtils.isNotBlank(sessionId)
                && sessionId.equals(running.sessionId)
                && ("RUNNING".equals(running.status) || "QUEUED".equals(running.status))) {
            result.put("deleted", false);
            result.put("skipped", true);
            result.put("message", "edit task is using this temp session");
            return result;
        }
        Path path = StringUtils.isBlank(sessionId)
                ? Paths.get(workPath, "_edit_uploads", String.valueOf(historyId)).normalize()
                : Paths.get(workPath, "_edit_uploads", String.valueOf(historyId), safePathSegment(sessionId)).normalize();
        result.put("deleted", deleteDirectoryQuietly(path));
        return result;
    }

    public Map<String, Object> restoreEditPartsOnlineState(Long historyId) {
        Map<String, Object> result = new LinkedHashMap<>();
        EditPartsTaskStatus running = editPartsTaskMap.get(historyId);
        if (running != null && ("RUNNING".equals(running.status) || "QUEUED".equals(running.status))) {
            result.put("success", false);
            result.put("message", "分P编辑任务仍在执行，请完成后再恢复线上状态");
            return result;
        }
        Optional<RecordHistory> historyOptional = historyRepository.findById(historyId);
        if (historyOptional.isEmpty()) {
            result.put("success", false);
            result.put("message", "稿件不存在");
            return result;
        }
        RecordHistory history = historyOptional.get();
        RecordRoom room = roomRepository.findByRoomId(history.getRoomId());
        EditAuthContext auth = resolveEditAuth(history, room);
        if (!auth.canEdit || auth.user == null) {
            result.put("success", false);
            result.put("message", auth.message);
            return result;
        }
        BiliVideoPartInfoResponse partInfo = loadOnlinePartInfo(auth.user, history);
        if (partInfo == null || partInfo.getData() == null || partInfo.getData().getVideos() == null
                || partInfo.getData().getVideos().isEmpty()) {
            result.put("success", false);
            result.put("message", "未读取到线上分P列表");
            return result;
        }

        List<BiliVideoPartInfoResponse.Video> onlineVideos = new ArrayList<>(partInfo.getData().getVideos());
        onlineVideos.sort(Comparator.comparingInt(BiliVideoPartInfoResponse.Video::getPage));
        List<RecordHistoryPart> localParts = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());
        Set<Long> touchedPartIds = new HashSet<>();
        int restored = 0;
        int created = 0;
        int deletedTemp = 0;
        int deletedPolluted = 0;

        for (BiliVideoPartInfoResponse.Video video : onlineVideos) {
            if (video == null || StringUtils.isBlank(video.getFilename())) {
                continue;
            }
            RecordHistoryPart part = findLocalPartForOnlineRestore(localParts, video, touchedPartIds);
            if (part == null) {
                part = new RecordHistoryPart();
                part.setHistoryId(history.getId());
                part.setRoomId(history.getRoomId());
                part.setStartTime(history.getStartTime());
                part.setEndTime(history.getEndTime());
                part.setRecording(false);
                part.setSourceType("ONLINE_PART");
                created++;
            }
            part.setPage(video.getPage());
            part.setPartOrder(video.getPage());
            part.setTitle(StringUtils.defaultIfBlank(video.getTitle(), StringUtils.defaultIfBlank(video.getPart(), part.getTitle())));
            part.setFileName(video.getFilename());
            if (video.getCid() > 0) {
                part.setCid(video.getCid());
            }
            part.setUpload(true);
            part.setUploadRetryCount(0);
            part.setDeleteFailType(null);
            part.setDeleteFailReason(null);
            part.setUploadFlowFallback(false);
            part.setUploadFlowFallbackReason(null);
            part.setRecording(false);
            if (isEditTempPath(part.getFilePath()) && !new File(part.getFilePath()).exists()) {
                part.setFilePath(null);
            }
            part = partRepository.save(part);
            touchedPartIds.add(part.getId());
            restored++;
        }

        for (RecordHistoryPart part : localParts) {
            if (part == null || part.getId() == null || touchedPartIds.contains(part.getId())) {
                continue;
            }
            if ("EDIT_PART".equals(part.getSourceType())) {
                deletePartAndXmlIssue(part);
                deletedTemp++;
            } else if ("ONLINE_PART".equals(part.getSourceType()) && part.getPage() > 0
                    && onlineVideos.stream().anyMatch(v -> v != null && v.getPage() == part.getPage())) {
                deletePartAndXmlIssue(part);
                deletedTemp++;
            } else if (shouldDeleteUnmatchedLocalPartAfterRestore(part, onlineVideos)) {
                deletePartAndXmlIssue(part);
                deletedPolluted++;
            }
        }

        result.put("success", true);
        result.put("message", "已按线上分P恢复本地状态");
        result.put("restored", restored);
        result.put("created", created);
        result.put("deletedTemp", deletedTemp);
        result.put("deletedPolluted", deletedPolluted);
        return result;
    }

    @Scheduled(fixedDelay = 3600000, initialDelay = 300000)
    public void cleanupExpiredEditPartTempFiles() {
        Path root = Paths.get(workPath, "_edit_uploads").normalize();
        if (!Files.exists(root)) {
            return;
        }
        long cutoff = System.currentTimeMillis() - EDIT_TEMP_TTL_MS;
        try {
            List<Path> paths = Files.walk(root)
                    .sorted(Comparator.reverseOrder())
                    .collect(Collectors.toList());
            for (Path path : paths) {
                try {
                    if (Files.isRegularFile(path) && Files.getLastModifiedTime(path).toMillis() < cutoff) {
                        Files.deleteIfExists(path);
                    } else if (Files.isDirectory(path) && !path.equals(root)) {
                        Files.deleteIfExists(path);
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            log.debug("[BLR] {}", LogKvs.event("Publish.EditParts.TempCleanupFailed")
                    .addIfNotBlank("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()));
        }
    }

    private void runEditPartsSubmit(Long historyId, Map<String, Object> request, EditPartsTaskStatus status) {
        status.status = "RUNNING";
        status.message = "正在提交编辑";
        RecordHistory history = historyRepository.findById(historyId).orElse(null);
        if (history == null) {
            markEditPartsTaskFailed(status, "稿件不存在");
            return;
        }
        RecordRoom room = roomRepository.findByRoomId(history.getRoomId());
        EditAuthContext auth = resolveEditAuth(history, room, status);
        if (!auth.canEdit || auth.user == null) {
            markEditPartsTaskFailed(status, auth.message);
            return;
        }
        Thread existed = TaskUtil.publishTask.putIfAbsent(history.getId(), Thread.currentThread());
        if (existed != null && existed != Thread.currentThread()) {
            markEditPartsTaskFailed(status, "稿件正在发布或编辑中");
            return;
        }
        try {
            BiliVideoPartInfoResponse partInfo = loadOnlinePartInfo(auth.user, history);
            Long aid = resolveOnlineAid(history, partInfo);
            if (aid == null || aid <= 0) {
                markEditPartsTaskFailed(status, "缺少 aid，无法编辑稿件");
                return;
            }
            Map<Integer, BiliVideoPartInfoResponse.Video> onlineByPage = buildOnlineVideoPageMap(partInfo);
            Map<String, BiliVideoPartInfoResponse.Video> onlineByTitle = new HashMap<>();
            Map<String, BiliVideoPartInfoResponse.Video> onlineByFilename = new HashMap<>();
            Map<Long, BiliVideoPartInfoResponse.Video> onlineByCid = new HashMap<>();
            if (partInfo != null && partInfo.getData() != null && partInfo.getData().getVideos() != null) {
                for (BiliVideoPartInfoResponse.Video video : partInfo.getData().getVideos()) {
                    if (video == null) {
                        continue;
                    }
                    if (StringUtils.isNotBlank(video.getFilename())) {
                        onlineByFilename.putIfAbsent(video.getFilename(), video);
                    }
                    if (video.getCid() > 0) {
                        onlineByCid.putIfAbsent(video.getCid(), video);
                    }
                    if (StringUtils.isNotBlank(video.getTitle())) {
                        onlineByTitle.putIfAbsent(video.getTitle(), video);
                    }
                    if (StringUtils.isNotBlank(video.getPart())) {
                        onlineByTitle.putIfAbsent(video.getPart(), video);
                    }
                }
            }
            List<EditPartSubmitItem> items = parseEditPartSubmitItems(request == null ? null : request.get("items"));
            List<SingleVideoDto> videos = new ArrayList<>();
            Set<Long> submittedPartIds = new HashSet<>();
            int page = 1;
            for (EditPartSubmitItem item : items) {
                if (item.deleted) {
                    continue;
                }
                SingleVideoDto dto = new SingleVideoDto();
                dto.setDesc("");
                dto.setTitle(StringUtils.defaultIfBlank(item.title, "P" + page));
                boolean hasReplacementFile = StringUtils.isNotBlank(item.filePath) || StringUtils.isNotBlank(item.fileRef);
                boolean expectsLocalFile = "local".equalsIgnoreCase(item.source) || "workdir".equalsIgnoreCase(item.source);
                if (!hasReplacementFile && expectsLocalFile) {
                    markEditPartsTaskFailed(status, "本地分P文件尚未上传完成: P" + page);
                    return;
                }
                if (hasReplacementFile) {
                    status.message = "正在上传分P P" + page;
                    RecordHistoryPart part = prepareEditUploadPart(history, room, item, page);
                    if (part == null) {
                        markEditPartsTaskFailed(status, "文件不在工作目录下或文件不存在: P" + page);
                        return;
                    }
                    uploadPartWithUserSerialBlocking(room, part);
                    part = partRepository.findById(part.getId()).orElse(part);
                    if (!part.isUpload() || StringUtils.isBlank(part.getFileName())) {
                        markEditPartsTaskFailed(status, "分P上传未完成: P" + page);
                        return;
                    }
                    dto.setFilename(part.getFileName());
                    if (part.getCid() != null && part.getCid() > 0) {
                        dto.setCid(part.getCid());
                    }
                    String persistedFilePath = "local".equalsIgnoreCase(item.source) ? null : part.getFilePath();
                    RecordHistoryPart synced = syncEditUploadResult(history, item, part, page, dto.getTitle(),
                            persistedFilePath, dto.getFilename(), dto.getCid(), part.getFileSize());
                    if (synced != null && synced.getId() != null) {
                        submittedPartIds.add(synced.getId());
                    }
                } else {
                    BiliVideoPartInfoResponse.Video online = findOnlineVideoForSubmitItem(item, onlineByPage, onlineByTitle, onlineByFilename, onlineByCid);
                    if (online == null) {
                        markEditPartsTaskFailed(status, "分P缺少线上页码或本地文件: P" + page);
                        return;
                    }
                    if (StringUtils.isBlank(online.getFilename())) {
                        markEditPartsTaskFailed(status, "线上分P不存在或缺少 filename: P" + (online.getPage() > 0 ? online.getPage() : page));
                        return;
                    }
                    dto.setFilename(online.getFilename());
                    if (online.getCid() > 0) {
                        dto.setCid(online.getCid());
                    }
                    RecordHistoryPart synced = syncExistingOnlinePart(history, item, page, dto);
                    if (synced != null && synced.getId() != null) {
                        submittedPartIds.add(synced.getId());
                    }
                }
                videos.add(dto);
                page++;
            }
            if (videos.isEmpty()) {
                markEditPartsTaskFailed(status, "至少需要保留一个分P");
                return;
            }
            status.message = "正在提交编辑";
            VideoEditUploadDto dto = buildVideoEditUploadDto(history, room, aid, videos);
            String editRes = BiliApi.editPublish(auth.user, dto);
            JSONObject root = parseJsonObject(editRes);
            Integer code = root == null ? null : root.getInteger("code");
            String message = root == null ? null : root.getString("message");
            status.code = code;
            status.responseMessage = message;
            status.responseSnippet = abbreviatePublishResponse(editRes, 320);
            if (code != null && code == 0) {
                history.setPublish(true);
                if (StringUtils.isBlank(history.getAvId())) {
                    history.setAvId(String.valueOf(aid));
                }
                markHistoryPendingReviewAfterEdit(history);
                historyRepository.save(history);
                cleanupStaleEditPartLocalState(history, submittedPartIds);
                syncEditHistoryStatusImmediately(history.getId());
                cleanupEditPartTempFiles(historyId, status.sessionId);
                captureEditPartsHistoryState(status);
                status.status = "SUCCESS";
                status.message = "编辑成功";
                status.endTime = LocalDateTime.now();
            } else {
                markEditPartsTaskFailed(status, StringUtils.defaultIfBlank(message, "编辑接口失败"));
            }
        } catch (Exception e) {
            log.error("[BLR] {}", LogKvs.event("Publish.EditParts.SubmitFailed")
                    .add("historyId", historyId)
                    .addIfNotBlank("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()), e);
            markEditPartsTaskFailed(status, e.getMessage());
        } finally {
            TaskUtil.publishTask.remove(history.getId());
        }
    }

    public boolean editPublishedHistory(RecordHistory history, String reason) {
        if (history == null) {
            return false;
        }
        RecordRoom room = roomRepository.findByRoomId(history.getRoomId());
        if (room == null) {
            log.warn("[BLR] {}", LogKvs.event("Publish.Edit.RoomMissing")
                    .add("historyId", history.getId())
                    .add("roomId", history.getRoomId())
                    .addIfNotBlank("reason", reason));
            return false;
        }
        Optional<BiliBiliUser> userOptional = room.getUploadUserId() == null
                ? Optional.empty()
                : biliUserRepository.findById(room.getUploadUserId());
        if (!userOptional.isPresent()) {
            log.error("[BLR] {}", LogKvs.event("Publish.UploadUserMissing")
                    .add("roomId", room.getRoomId())
                    .add("uname", room.getUname())
                    .add("uploadUserId", room.getUploadUserId())
                    .add("historyId", history.getId())
                    .addIfNotBlank("reason", reason));
            return false;
        }
        BiliBiliUser biliBiliUser = userOptional.get();
        if (!biliBiliUser.isLogin()) {
            log.error("[BLR] {}", LogKvs.event("Publish.LoginInvalid")
                    .add("roomId", room.getRoomId())
                    .add("uname", room.getUname())
                    .add("uploadUserId", room.getUploadUserId())
                    .add("historyId", history.getId())
                    .addIfNotBlank("reason", reason));
            return false;
        }
        BiliVideoPartInfoResponse videoPartInfo = loadOnlinePartInfo(biliBiliUser, history);
        Long aid = resolveOnlineAid(history, videoPartInfo);
        if (aid == null || aid <= 0) {
            log.warn("[BLR] {}", LogKvs.event("Publish.Edit.SkipNoAid")
                    .add("historyId", history.getId())
                    .add("roomId", history.getRoomId())
                    .addIfNotBlank("bvid", history.getBvId())
                    .addIfNotBlank("aid", history.getAvId())
                    .addIfNotBlank("reason", reason));
            return false;
        }

        Thread existed = TaskUtil.publishTask.putIfAbsent(history.getId(), Thread.currentThread());
        if (existed != null && existed != Thread.currentThread()) {
            log.warn("[BLR] {}", LogKvs.event("Publish.Task.AlreadyRunning")
                    .add("historyId", history.getId())
                    .add("roomId", history.getRoomId())
                    .addIfNotBlank("title", history.getTitle())
                    .add("ownerThread", existed.getName())
                    .add("currentThread", Thread.currentThread().getName()));
            return false;
        }

        long startNs = System.nanoTime();
        try {
            log.info("[BLR] {}", LogKvs.event("Publish.Edit.Start")
                    .add("roomId", room.getRoomId())
                    .add("uname", room.getUname())
                    .add("historyId", history.getId())
                    .add("aid", aid)
                    .addIfNotBlank("bvid", history.getBvId())
                    .addIfNotBlank("reason", reason));

            Map<String, BiliVideoPartInfoResponse.Video> onlineByTitle = buildOnlineVideoTitleMap(videoPartInfo);
            Map<Integer, BiliVideoPartInfoResponse.Video> onlineByPage = buildOnlineVideoPageMap(videoPartInfo);
            List<RecordHistoryPart> uploadParts = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());
            boolean editBlocked = false;
            for (RecordHistoryPart uploadPart : uploadParts) {
                if (isSkippedPart(uploadPart)) {
                    continue;
                }
                BiliVideoPartInfoResponse.Video onlineVideo = resolveOnlineVideo(uploadPart, onlineByTitle, onlineByPage);
                boolean onlineFailed = isOnlineVideoFailedForEdit(onlineVideo);
                boolean onlineUsable = isOnlineVideoUsable(onlineVideo);
                if (!uploadPart.isUpload() && onlineUsable) {
                    syncPartFromOnlineVideo(uploadPart, onlineVideo);
                    continue;
                }
                boolean needsUpload = !uploadPart.isUpload() || onlineFailed;
                if (!needsUpload) {
                    continue;
                }
                boolean timestampError = onlineVideo != null && onlineVideo.getFailCode() == 14 && onlineVideo.getXcodeState() == 1;
                if (timestampError) {
                    uploadPart.setUpload(false);
                    uploadPart.setCid(null);
                    uploadPart.setFileName(null);
                    uploadPart.setUploadRetryCount(UPLOAD_RETRY_GIVE_UP);
                    uploadPart.setDeleteFailType("TIMESTAMP_JUMP");
                    uploadPart.setDeleteFailReason("part transcode failed by timestamp jump, give up reupload");
                    partRepository.save(uploadPart);
                    log.info("[BLR] {}", LogKvs.event("Publish.Edit.PartGiveUpTimestampJump")
                            .add("historyId", history.getId())
                            .add("partId", uploadPart.getId())
                            .add("failCode", onlineVideo.getFailCode())
                            .add("xcodeState", onlineVideo.getXcodeState()));
                    continue;
                }
                if (onlineFailed) {
                    uploadPart.setUpload(false);
                    uploadPart.setCid(null);
                    uploadPart.setFileName(null);
                    uploadPart = partRepository.save(uploadPart);
                }
                PartFileLocationService.FileResolution fileResolution = partFileLocationService.resolveReadable(uploadPart.getId());
                String filePath = fileResolution.available() ? normalizeFilePath(fileResolution.path().toString()) : null;
                if (StringUtils.isBlank(filePath)) {
                    log.warn("[BLR] {}", LogKvs.event("Publish.Edit.PartUpload.SkipNoFilePath")
                            .add("historyId", history.getId())
                            .add("partId", uploadPart.getId())
                            .add("localFileState", fileResolution.state()));
                    editBlocked = true;
                    continue;
                }
                uploadPart.setFilePath(filePath);
                File file = new File(filePath);
                if (!file.exists()) {
                    log.warn("[BLR] {}", LogKvs.event("Publish.Edit.PartUpload.SkipFileMissing")
                            .add("historyId", history.getId())
                            .add("partId", uploadPart.getId())
                            .add("filePath", filePath));
                    editBlocked = true;
                    continue;
                }
                log.info("[BLR] {}", LogKvs.event("Publish.Edit.PartUpload")
                        .add("historyId", history.getId())
                        .add("partId", uploadPart.getId())
                        .addIfNotBlank("reason", reason));
                uploadPartWithUserSerialBlocking(room, uploadPart);
            }
            if (editBlocked) {
                log.warn("[BLR] {}", LogKvs.event("Publish.Edit.Deferred")
                        .add("historyId", history.getId())
                        .add("roomId", history.getRoomId())
                        .addIfNotBlank("reason", reason)
                        .addIfNotBlank("err", "part_missing_local_file"));
                return false;
            }

            uploadParts = filterPublishableParts(partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId()));
            if (uploadParts.isEmpty()) {
                log.warn("[BLR] {}", LogKvs.event("Publish.Edit.Parts.Empty")
                        .add("roomId", room.getRoomId())
                        .add("historyId", history.getId())
                        .addIfNotBlank("reason", reason));
                return false;
            }
            videoPartInfo = loadOnlinePartInfo(biliBiliUser, history);
            onlineByTitle = buildOnlineVideoTitleMap(videoPartInfo);
            onlineByPage = buildOnlineVideoPageMap(videoPartInfo);
            EditVideosBuildResult videosBuild = buildEditVideos(history, room, uploadParts, videoPartInfo, onlineByTitle, onlineByPage);
            if (videosBuild.isBlocked()) {
                log.warn("[BLR] {}", LogKvs.event("Publish.Edit.Deferred")
                        .add("historyId", history.getId())
                        .add("roomId", history.getRoomId())
                        .add("blockedPartCount", videosBuild.getBlockedPartCount())
                        .addIfNotBlank("reason", reason)
                        .addIfNotBlank("err", "unsafe_video_list"));
                return false;
            }
            if (videosBuild.getVideos().isEmpty()) {
                log.warn("[BLR] {}", LogKvs.event("Publish.Edit.Parts.Empty")
                        .add("roomId", room.getRoomId())
                        .add("historyId", history.getId())
                        .addIfNotBlank("reason", reason));
                return false;
            }
            VideoEditUploadDto videoUploadDto = buildVideoEditUploadDto(history, room, aid, videosBuild.getVideos());
            String editRes = BiliApi.editPublish(biliBiliUser, videoUploadDto);
            JSONObject editRoot = parseJsonObject(editRes);
            Integer code = editRoot == null ? null : editRoot.getInteger("code");
            String message = editRoot == null ? null : editRoot.getString("message");
            log.info("[BLR] {}", LogKvs.event("Publish.Edit.Response")
                    .add("roomId", room.getRoomId())
                    .add("uname", room.getUname())
                    .add("historyId", history.getId())
                    .add("aid", aid)
                    .add("code", code)
                    .addIfNotBlank("message", message)
                    .add("videoCount", videoUploadDto.getVideos() == null ? 0 : videoUploadDto.getVideos().size())
                    .add("respLen", editRes == null ? 0 : editRes.length())
                    .addIfNotBlank("reason", reason)
                    .addIfNotBlank("respSnippet", abbreviatePublishResponse(editRes, 320))
                    .addStageCostMs("total", startNs));
            if (code != null && code == 0) {
                history.setPublish(true);
                history.setPublishUserId(biliBiliUser.getId());
                if (StringUtils.isBlank(history.getAvId())) {
                    history.setAvId(String.valueOf(aid));
                }
                markHistoryPendingReviewAfterEdit(history);
                historyRepository.save(history);
                syncEditHistoryStatusImmediately(history.getId());
                return true;
            }
            return false;
        } catch (PartUploadWaitTimeoutException e) {
            log.info("[BLR] {}", LogKvs.event("Publish.Edit.Deferred")
                    .add("historyId", history.getId())
                    .add("roomId", history.getRoomId())
                    .addIfNotBlank("reason", reason)
                    .addIfNotBlank("err", e.getMessage())
                    .addStageCostMs("total", startNs));
            return false;
        } catch (Exception e) {
            log.error("[BLR] {}", LogKvs.event("Publish.Edit.Error")
                    .add("historyId", history.getId())
                    .add("roomId", history.getRoomId())
                    .addIfNotBlank("reason", reason)
                    .addIfNotBlank("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName())
                    .addStageCostMs("total", startNs), e);
            return false;
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
        if (hasOnlineIdentity(history)) {
            return editPublishedHistory(history, "publish-entry");
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
        Thread publishThread = TaskUtil.publishTask.putIfAbsent(history.getId(), Thread.currentThread());
        if (publishThread != null && publishThread != Thread.currentThread()) {
            // 正在发布，直接退出
            log.warn("[BLR] {}", LogKvs.event("Publish.Task.AlreadyRunning")
                .add("historyId", history.getId())
                .add("roomId", history.getRoomId())
                .addIfNotBlank("title", history.getTitle())
                .add("ownerThread", publishThread.getName())
                .add("currentThread", Thread.currentThread().getName()));
            return false;
        }
        long publishStartNs = System.nanoTime();
        long loadPartsStartNs = System.nanoTime();
        long loadPartsCostMs = -1L;
        long ensureUploadStartNs = 0L;
        long ensureUploadCostMs = -1L;
        long webPublishStartNs = 0L;
        long webPublishCostMs = -1L;
        long postProcessStartNs = 0L;
        long postProcessCostMs = -1L;
        try {

            RecordRoom room = roomRepository.findByRoomId(history.getRoomId());
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
            uploadParts = filterPublishableParts(uploadParts);
            loadPartsCostMs = (System.nanoTime() - loadPartsStartNs) / 1_000_000L;
            if (uploadParts.size() == 0) {
                log.warn("[BLR] {}", LogKvs.event("Publish.Parts.Empty")
                        .add("roomId", room.getRoomId())
                        .add("uname", room.getUname())
                        .add("historyId", history.getId())
                        .addIfNotBlank("title", history.getTitle()));
                history.setUpload(false);
                history.setRecording(false);
                history.setStreaming(false);
                history.setUpdateTime(LocalDateTime.now());
                history = historyRepository.save(history);
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
                    return false;
                }
            }
            LocalDateTime now = LocalDateTime.now();
            try {
            ensureUploadStartNs = System.nanoTime();
            for (RecordHistoryPart uploadPart : uploadParts) {
                Optional<RecordHistoryPart> flsuhPartOptional = partRepository.findById(uploadPart.getId());
                uploadPart = flsuhPartOptional.get();
                if (isSkippedPart(uploadPart)) {
                    continue;
                }
                if (uploadPart.isUpload()) {
                    if (StringUtils.isNotBlank(uploadPart.getFileName())) {
                        // 已有服务器文件标识时可以直接复用，不能再要求本地视频存在
                        continue;
                    }
                    log.error("[BLR] {}", LogKvs.event("Publish.Part.UploadIdentityInvalid")
                            .add("roomId", room.getRoomId())
                            .add("uname", room.getUname())
                            .add("historyId", history.getId())
                            .add("partId", uploadPart.getId())
                            .add("upload", true)
                            .add("fileDelete", uploadPart.isFileDelete())
                            .addIfNotBlank("fileName", uploadPart.getFileName())
                            .add("cid", uploadPart.getCid()));
                    TaskUtil.publishTask.remove(history.getId());
                    return false;
                }
                PartFileLocationService.FileResolution fileResolution = partFileLocationService.resolveReadable(uploadPart.getId());
                String filePath = fileResolution.available() ? normalizeFilePath(fileResolution.path().toString()) : null;
                if (StringUtils.isBlank(filePath)) {
                    log.error("[BLR] {}", LogKvs.event("Publish.Part.FilePathInvalid")
                            .add("roomId", room.getRoomId())
                            .add("uname", room.getUname())
                            .add("historyId", history.getId())
                            .add("partId", uploadPart.getId())
                            .add("localFileState", fileResolution.state()));
                    TaskUtil.publishTask.remove(history.getId());
                    return false;
                }
                uploadPart.setFilePath(filePath);
                filePath = filePath.intern();
                File file = new File(filePath);
                if (file.exists()) {
                    if (uploadPart.getEndTime() == null) {
                        log.warn("[BLR] {}", LogKvs.event("Publish.Part.FileStillWriting")
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
                        if (uploadPart.getEndTime() != null && uploadPart.getEndTime().isAfter(now.plusMinutes(11L))) {
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
                            uploadPart.setUpload(false);
                            uploadPart.setUploadRetryCount(UPLOAD_RETRY_GIVE_UP);
                            uploadPart.setDeleteFailType("SKIPPED_THRESHOLD");
                            uploadPart.setDeleteFailReason("文件低于阈值(大小/时长)已跳过上传，可在前端手动补救");
                            partRepository.save(uploadPart);
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
                            uploadPart.setUpload(false);
                            uploadPart.setUploadRetryCount(UPLOAD_RETRY_GIVE_UP);
                            uploadPart.setDeleteFailType("SKIPPED_THRESHOLD");
                            uploadPart.setDeleteFailReason("文件低于阈值(大小/时长)已跳过上传，可在前端手动补救");
                            partRepository.save(uploadPart);
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
                            if (!part.isUpload() && !isSkippedPart(part)) {
                                log.info("[BLR] {}", LogKvs.event("Publish.Part.NotUploaded")
                                        .add("roomId", room.getRoomId())
                                        .add("uname", room.getUname())
                                        .add("historyId", history.getId())
                                        .add("partId", uploadPart.getId()));
                                uploadPartWithUserSerialBlocking(room, uploadPart);
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
                    if (isSkippedPart(uploadPart)) {
                        continue;
                    }
                    log.info("[BLR] {}", LogKvs.event("Publish.Part.NotUploaded")
                            .add("roomId", room.getRoomId())
                            .add("uname", room.getUname())
                            .add("historyId", history.getId())
                            .add("partId", uploadPart.getId()));
                    uploadPartWithUserSerialBlocking(room, uploadPart);
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
            ensureUploadCostMs = (System.nanoTime() - ensureUploadStartNs) / 1_000_000L;
            } catch (RuntimeException e) {
                if (e instanceof PartUploadWaitTimeoutException || (e.getMessage() != null && e.getMessage().startsWith("UPLOAD_WAIT_TIMEOUT"))) {
                    if (ensureUploadStartNs > 0L && ensureUploadCostMs < 0L) {
                        ensureUploadCostMs = (System.nanoTime() - ensureUploadStartNs) / 1_000_000L;
                    }
                    log.info("[BLR] {}", LogKvs.event("Publish.PartUpload.Deferred")
                            .add("historyId", history.getId())
                            .add("roomId", history.getRoomId())
                            .addIfNotBlank("title", history.getTitle())
                            .addIfNotBlank("err", e.getMessage())
                            .addStageField("ensureUpload", "costMs", ensureUploadCostMs));
                    TaskUtil.publishTask.remove(history.getId());
                    return false;
                }
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
            uploadParts = filterPublishableParts(partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId()));
            if (preSize != uploadParts.size()) {
                log.error("[BLR] {}", LogKvs.event("Publish.Parts.Changed")
                        .add("roomId", room.getRoomId())
                        .add("uname", room.getUname())
                        .add("historyId", history.getId())
                        .add("preSize", preSize)
                        .add("nowSize", uploadParts.size()));
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
                        throw new RuntimeException("登录已过期，请重新登录: " + biliBiliUser.getUname());
                    }


                    Map<String, Object> historyTemplateMap = buildHistoryTemplateMap(history, room);
                    List<SingleVideoDto> dtos = new ArrayList<>();
                    for (int i = 0; i < uploadParts.size(); i++) {
                        RecordHistoryPart uploadPart = uploadParts.get(i);
                        SingleVideoDto dto = new SingleVideoDto();
                        Map<String, Object> partTemplateMap = buildPartTemplateMap(historyTemplateMap, uploadPart, i + 1);
                        String partTitle = this.template(room.getPartTitleTemplate(), partTemplateMap).getDesc();
                        dto.setTitle(partTitle);
                        //同步标题
                        uploadPart.setTitle(partTitle);
                        uploadPart = partRepository.save(uploadPart);
                        dto.setDesc("");
                        dto.setFilename(uploadPart.getFileName());
                        if (uploadPart.getCid() != null && uploadPart.getCid() > 0) {
                            dto.setCid(uploadPart.getCid());
                        }
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
                            
                            // 明确检查文件是否存在
                            if (!cover.exists()) {
                                log.warn("[BLR] {}", LogKvs.event("Publish.Cover.NotFound")
                                        .add("roomId", room.getRoomId())
                                        .add("uname", room.getUname())
                                        .add("historyId", history.getId())
                                        .add("expectedPath", filePath));
                                // 尝试使用直播间封面兜底
                                try {
                                    BiliLiveRoomInfoResponse roomInfo = BiliApi.getLiveRoomInfo(String.valueOf(room.getRoomId()));
                                    if (roomInfo != null && roomInfo.getCode() == 0 && roomInfo.getData() != null) {
                                        String liveCoverUrl = roomInfo.getData().getUser_cover();
                                        if (StringUtils.isBlank(liveCoverUrl)) {
                                            liveCoverUrl = roomInfo.getData().getKeyframe();
                                        }
                                        if (StringUtils.isNotBlank(liveCoverUrl)) {
                                            coverUrl = liveCoverUrl;
                                            log.info("[BLR] {}", LogKvs.event("Publish.Cover.Fallback.Success")
                                                    .add("roomId", room.getRoomId())
                                                    .add("historyId", history.getId())
                                                    .add("url", coverUrl));
                                        } else {
                                            log.warn("[BLR] {}", LogKvs.event("Publish.Cover.Fallback.Failed")
                                                    .add("roomId", room.getRoomId())
                                                    .add("reason", "empty_cover_url"));
                                            coverUrl = "";
                                        }
                                    } else {
                                        log.warn("[BLR] {}", LogKvs.event("Publish.Cover.Fallback.Failed")
                                                .add("roomId", room.getRoomId())
                                                .add("reason", "api_error"));
                                        coverUrl = "";
                                    }
                                } catch (Exception e) {
                                    log.warn("[BLR] {}", LogKvs.event("Publish.Cover.Fallback.Failed")
                                            .add("roomId", room.getRoomId())
                                            .add("error", e.getMessage()));
                                    coverUrl = "";
                                }
                            } else {
                                // 读取文件
                                byte[] bytes = new byte[(int)cover.length()];
                                try (FileInputStream inputStream = new FileInputStream(cover)) {
                                    inputStream.read(bytes);
                                }

                                // 带重试的上传机制
                                String uploadCoverResponse = null;
                                Exception lastException = null;
                                int maxRetries = 3;
                                
                                for (int i = 0; i < maxRetries; i++) {
                                    try {
                                        uploadCoverResponse = BiliApi.uploadCover(biliBiliUser, cover.getName(), bytes);
                                        // 简单检查响应是否有效
                                        if (uploadCoverResponse != null && uploadCoverResponse.contains("\"code\":0")) {
                                            break; // 成功，跳出循环
                                        } else {
                                            // 如果是业务错误（如图片格式不对），重试可能没用，但在不解析详细code的情况下，暂且统一处理
                                            throw new RuntimeException("Cover upload response invalid: " + uploadCoverResponse);
                                        }
                                    } catch (Exception e) {
                                        lastException = e;
                                        if (i < maxRetries - 1) {
                                            log.warn("[BLR] {}", LogKvs.event("Publish.Cover.Upload.Retry")
                                                    .add("roomId", room.getRoomId())
                                                    .add("historyId", history.getId())
                                                    .add("retry", i + 1)
                                                    .add("error", e.getMessage()));
                                            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                                        }
                                    }
                                }

                                if (uploadCoverResponse != null && uploadCoverResponse.contains("\"code\":0")) {
                                    log.info("[BLR] {}", LogKvs.event("Publish.Cover.Upload.Response")
                                            .add("roomId", room.getRoomId())
                                            .add("uname", room.getUname())
                                            .add("historyId", history.getId())
                                            .addIfNotBlank("coverFile", cover.getName()));
                                    coverUrl = JsonPath.read(uploadCoverResponse, "data.url");
                                    history.setCoverUrl(coverUrl);
                                    history = historyRepository.save(history);
                                } else {
                                    // 抛出最后一次异常或通用异常
                                    throw lastException != null ? lastException : new RuntimeException("Upload failed: " + uploadCoverResponse);
                                }
                            }
                        } catch (Exception e) {
                            log.warn("[BLR] {}", LogKvs.event("Publish.Cover.Upload.Failed")
                                    .add("roomId", room.getRoomId())
                                    .add("uname", room.getUname())
                                    .add("historyId", history.getId())
                                    .add("reason", e.getMessage()), e);
                            coverUrl = "";
                        }
                    }
                    VideoUploadDto videoUploadDto = new VideoUploadDto();

                    videoUploadDto.setTid(room.getTid());
                    videoUploadDto.setCover(coverUrl);
                    videoUploadDto.setCopyright(room.getCopyright());
                    videoUploadDto.setNo_disturbance(room.getNoDisturbance());
                    videoUploadDto.setIs_only_self(room.getIsOnlySelf());
                    videoUploadDto.setTitle(this.template(room.getTitleTemplate(), historyTemplateMap).getDesc());
                    if (videoUploadDto.getCopyright() == 2) {
                        videoUploadDto.setSource(this.template(videoUploadDto.getSource(), historyTemplateMap).getDesc());
                    }
                    videoUploadDto.setDesc(this.template(room.getDescTemplate(), historyTemplateMap).getDesc());
                    videoUploadDto.setDesc_v2(this.template(room.getDescTemplate(), historyTemplateMap).getDescV2Dtos());
                    if (StringUtils.isNotBlank(room.getDynamicTemplate())) {
                        videoUploadDto.setDynamic(this.template(room.getDynamicTemplate(), historyTemplateMap).getDesc());
                        videoUploadDto.setDynamic_v2(this.template(room.getDynamicTemplate(), historyTemplateMap).getDescV2Dtos());
                    }
                    videoUploadDto.setVideos(dtos);
                    videoUploadDto.setTag(this.template(room.getTags(), historyTemplateMap).getDesc());
                    log.info("[BLR] {}", LogKvs.event("Publish.WebPublish.UploadPartsReady")
                            .add("roomId", room.getRoomId())
                            .add("uname", room.getUname())
                            .add("historyId", history.getId())
                            .add("partCount", uploadParts.size())
                            .add("videoCount", dtos.size())
                            .add("multipartPartCount", countUploadFlow(uploadParts, "MULTIPART"))
                            .add("legacyPartCount", countUploadFlow(uploadParts, "LEGACY"))
                            .add("unknownFlowPartCount", countUnknownUploadFlow(uploadParts))
                            .add("fallbackPartCount", countUploadFlowFallback(uploadParts))
                            .addIfNotBlank("historyPartSummary", summarizePublishParts(uploadParts)));
                    if (log.isDebugEnabled()) {
                        log.debug("[BLR] {}", LogKvs.event("Publish.WebPublish.PayloadDebug")
                                .add("roomId", room.getRoomId())
                                .add("uname", room.getUname())
                                .add("historyId", history.getId())
                                .add("partCount", uploadParts.size())
                                .add("videoCount", dtos.size())
                                .add("tid", videoUploadDto.getTid())
                                .add("copyright", videoUploadDto.getCopyright())
                                .add("isOnlySelf", videoUploadDto.getIs_only_self())
                                .add("noDisturbance", videoUploadDto.getNo_disturbance())
                                .addIfNotBlank("historyPartSummary", summarizePublishParts(uploadParts))
                                .addIfNotBlank("videoSummary", summarizePublishVideos(dtos))
                                .addIfNotBlank("publishTitle", abbreviateForLog(videoUploadDto.getTitle(), 80))
                                .addIfNotBlank("publishTag", abbreviateForLog(videoUploadDto.getTag(), 80))
                                .addIfNotBlank("cover", abbreviateForLog(videoUploadDto.getCover(), 120))
                                .addIfNotBlank("source", abbreviateForLog(videoUploadDto.getSource(), 120)));
                    }
                    String uploadRes = null;
                    try {
                        webPublishStartNs = System.nanoTime();
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
                        JSONObject publishRoot = parseJsonObject(uploadRes);
                        JSONObject publishData = publishRoot == null ? null : publishRoot.getJSONObject("data");
                        log.info("[BLR] {}", LogKvs.event("Publish.WebPublish.Parsed")
                                .add("roomId", room.getRoomId())
                                .add("uname", room.getUname())
                                .add("historyId", history.getId())
                                .add("respLen", uploadRes == null ? 0 : uploadRes.length())
                                .add("code", publishRoot == null ? null : publishRoot.getInteger("code"))
                                .addIfNotBlank("message", publishRoot == null ? null : publishRoot.getString("message"))
                                .add("hasData", publishData != null)
                                .addIfNotBlank("rootKeys", publishRoot == null ? "" : String.join(",", publishRoot.keySet()))
                                .addIfNotBlank("dataKeys", publishData == null ? "" : String.join(",", publishData.keySet()))
                                .addIfNotBlank("respSnippet", abbreviatePublishResponse(uploadRes, 320)));
                        if (publishData == null) {
                            Integer publishCode = publishRoot == null ? null : publishRoot.getInteger("code");
                            String publishMessage = publishRoot == null ? null : publishRoot.getString("message");
                            log.warn("[BLR] {}", LogKvs.event("Publish.WebPublish.MissingData")
                                    .add("roomId", room.getRoomId())
                                    .add("uname", room.getUname())
                                    .add("historyId", history.getId())
                                    .add("respLen", uploadRes == null ? 0 : uploadRes.length())
                                    .add("code", publishCode)
                                    .addIfNotBlank("message", publishMessage)
                                    .addIfNotBlank("respSnippet", abbreviatePublishResponse(uploadRes, 320)));
                            throw new RuntimeException("webPublish failed: code=" + publishCode
                                    + ", message=" + publishMessage
                                    + ", resp=" + abbreviatePublishResponse(uploadRes, 320));
                        }
                        String bvid = publishData == null ? null : publishData.getString("bvid");
                        String aid = publishData == null ? null : publishData.getString("aid");
                        if (StringUtils.isBlank(bvid) || StringUtils.isBlank(aid)) {
                            // 检测是否是时间戳跳变错误(code:21588)，如果是则放弃该投稿
                            if (StringUtils.contains(uploadRes, "21588") || StringUtils.contains(uploadRes, "时间跳跃") || StringUtils.contains(uploadRes, "时间戳")) {
                                log.error("[BLR] {}", LogKvs.event("Publish.TimestampJump.GiveUpHistory")
                                        .add("roomId", room.getRoomId())
                                        .add("uname", room.getUname())
                                        .add("historyId", history.getId())
                                        .addIfNotBlank("title", history.getTitle())
                                        .add("code", 21588));
                                // 标记所有未上传的分P为放弃（时间戳跳变）
                                for (RecordHistoryPart part : uploadParts) {
                                    if (!part.isUpload() && !isSkippedPart(part)) {
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
                        history.setPublishUserId(biliBiliUser.getId());
                        history = historyRepository.save(history);
                        webPublishCostMs = webPublishStartNs > 0L ? (System.nanoTime() - webPublishStartNs) / 1_000_000L : -1L;
                        log.info("[BLR] {}", LogKvs.event("Publish.WebPublish.Success")
                            .add("roomId", room.getRoomId())
                            .add("uname", room.getUname())
                            .add("historyId", history.getId())
                            .addIfNotBlank("title", history.getTitle())
                            .addIfNotBlank("bvid", bvid)
                            .addIfNotBlank("aid", aid)
                            .addStageCostMs("total", publishStartNs)
                            .addStageField("loadParts", "costMs", loadPartsCostMs)
                            .addStageField("ensureUpload", "costMs", ensureUploadCostMs)
                            .addStageField("webPublish", "costMs", webPublishCostMs)
                            .addStageField("postProcess", "costMs", postProcessCostMs));

                        // 兜底：部分情况下创建投稿接口可能不稳定地忽略 is_only_self，这里在投稿成功后强制同步一次可见性
                        try {
                            int desiredVisibility = room.getIsOnlySelf();
                            if (desiredVisibility == 0 || desiredVisibility == 1) {
                                BiliApi.updateVideoVisibility(biliBiliUser, Long.parseLong(aid), desiredVisibility);
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

                        postProcessStartNs = System.nanoTime();
                        for (RecordHistoryPart part : uploadParts) {
                            //解析弹幕入库
                            List<LiveMsg> liveMsgs = msgRepository.queryByPartId(part.getId());
                            msgRepository.deleteAll(liveMsgs);
                            liveMsgService.processing(part);
                        }
                        liveMsgSendSync.enqueueHistoryDispatch(history.getId());
                        //处理高能剪辑事件
                        if (room.isHighEnergyCut()) {
                            highEnergyCutPublishService.process(history);
                        }

                        try {
                            Long sectionId = resolveSectionId(room, biliBiliUser);
                            if (sectionId != null && sectionId > 0) {
                                String addSeasons = BiliApi.addSeasons(biliBiliUser, sectionId, aid, String.valueOf(uploadParts.get(0).getCid()), videoUploadDto.getTitle());
                                Integer code = JsonPath.read(addSeasons, "code");
                                if (code == 0) {
                                    log.info("[BLR] {}", LogKvs.event("Publish.Season.Add.Success")
                                            .add("roomId", room.getRoomId())
                                            .add("uname", room.getUname())
                                            .add("historyId", history.getId())
                                            .add("sectionId", sectionId)
                                            .addIfNotBlank("aid", aid));
                                }
                            }
                        } catch (Exception e) {
                            log.error("[BLR] {}", LogKvs.event("Publish.Season.Add.Failed")
                                    .add("roomId", room.getRoomId())
                                    .add("uname", room.getUname())
                                    .add("historyId", history.getId())
                                    .add("sectionId", room.getSectionId())
                                    .add("seasonId", room.getSeasonId())
                                    .addIfNotBlank("aid", aid), e);
                        }

                        // 投稿完成后的文件处理统一交给可恢复的生命周期服务
                        try {
                            for (RecordHistoryPart part : uploadParts) {
                                String filePath = part.getFilePath();
                                if (partFileCleanupPolicy.isPostPublishCleanupType(room.getDeleteType())
                                        && partFileCleanupPolicy.shouldSkipProtectedArchive(room, history, part, filePath, "Publish", "postPublishCleanup")) {
                                    continue;
                                }
                                if (room.getDeleteType() == 9) {
                                    partFileOperationService.delete(part.getId());
                                } else if (StringUtils.isNotBlank(room.getMoveDir()) && room.getDeleteType() == 10) {
                                    partFileOperationService.move(part.getId(), room.getMoveDir());
                                }
                            }
                        } catch (Exception de) {
                            log.error("[BLR] {}", LogKvs.event("Publish.File.PostProcess.Error")
                                    .add("roomId", room.getRoomId())
                                    .add("uname", room.getUname())
                                    .add("historyId", history.getId()), de);
                        }
                        postProcessCostMs = postProcessStartNs > 0L ? (System.nanoTime() - postProcessStartNs) / 1_000_000L : -1L;

                    } catch (Exception e) {
                        webPublishCostMs = webPublishStartNs > 0L ? (System.nanoTime() - webPublishStartNs) / 1_000_000L : -1L;
                        history.setUploadRetryCount(history.getUploadRetryCount() + 1);
                        history = historyRepository.save(history);
                        log.warn("[BLR] {}", LogKvs.event("Publish.WebPublish.Failed")
                                .add("roomId", room.getRoomId())
                                .add("uname", room.getUname())
                                .add("historyId", history.getId())
                                .addIfNotBlank("title", history.getTitle())
                                .add("retryCount", history.getUploadRetryCount())
                                .add("respLen", uploadRes == null ? 0 : uploadRes.length())
                                .addStageCostMs("total", publishStartNs)
                                .addStageField("loadParts", "costMs", loadPartsCostMs)
                                .addStageField("ensureUpload", "costMs", ensureUploadCostMs)
                                .addStageField("webPublish", "costMs", webPublishCostMs)
                                .addStageField("postProcess", "costMs", postProcessCostMs), e);
                    } finally {
                        TaskUtil.publishTask.remove(history.getId());
                    }
                }
            }
        } catch (Exception e) {
            log.error("[BLR] {}", LogKvs.event("Publish.Error")
                    .add("historyId", history.getId())
                    .add("roomId", history.getRoomId())
                    .addIfNotBlank("title", history.getTitle())
                    .addStageCostMs("total", publishStartNs)
                    .addStageField("loadParts", "costMs", loadPartsCostMs)
                    .addStageField("ensureUpload", "costMs", ensureUploadCostMs)
                    .addStageField("webPublish", "costMs", webPublishCostMs)
                    .addStageField("postProcess", "costMs", postProcessCostMs), e);
        } finally {
            TaskUtil.publishTask.remove(history.getId());
        }
        return true;
    }

    public static boolean hasOnlineIdentity(RecordHistory history) {
        return history != null
                && (StringUtils.isNotBlank(history.getAvId()) || StringUtils.isNotBlank(history.getBvId()));
    }

    private Long resolveOnlineAid(RecordHistory history, BiliVideoPartInfoResponse videoPartInfo) {
        if (history != null && StringUtils.isNotBlank(history.getAvId())) {
            try {
                return Long.parseLong(history.getAvId().trim());
            } catch (Exception ignore) {
            }
        }
        if (videoPartInfo != null && videoPartInfo.getData() != null && videoPartInfo.getData().getVideos() != null) {
            for (BiliVideoPartInfoResponse.Video video : videoPartInfo.getData().getVideos()) {
                if (video != null && video.getAid() > 0) {
                    return video.getAid();
                }
            }
        }
        return null;
    }

    private BiliVideoPartInfoResponse loadOnlinePartInfo(BiliBiliUser user, RecordHistory history) {
        if (user == null || history == null || StringUtils.isBlank(history.getBvId())) {
            return null;
        }
        try {
            BiliVideoPartInfoResponse partInfo = BiliApi.getVideoPartInfo(user, history.getBvId());
            if (partInfo == null || partInfo.getData() == null || partInfo.getData().getVideos() == null) {
                log.warn("[BLR] {}", LogKvs.event("Publish.Edit.OnlinePartInfo.Empty")
                        .add("historyId", history.getId())
                        .addIfNotBlank("bvid", history.getBvId())
                        .add("code", partInfo == null ? null : partInfo.getCode())
                        .addIfNotBlank("message", partInfo == null ? null : partInfo.getMessage()));
            }
            return partInfo;
        } catch (Exception e) {
            log.warn("[BLR] {}", LogKvs.event("Publish.Edit.OnlinePartInfo.Failed")
                    .add("historyId", history.getId())
                    .addIfNotBlank("bvid", history.getBvId())
                    .addIfNotBlank("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()), e);
            return null;
        }
    }

    private Map<String, BiliVideoPartInfoResponse.Video> buildOnlineVideoTitleMap(BiliVideoPartInfoResponse partInfo) {
        Map<String, BiliVideoPartInfoResponse.Video> result = new HashMap<>();
        if (partInfo == null || partInfo.getData() == null || partInfo.getData().getVideos() == null) {
            return result;
        }
        for (BiliVideoPartInfoResponse.Video video : partInfo.getData().getVideos()) {
            if (video == null) {
                continue;
            }
            if (StringUtils.isNotBlank(video.getTitle())) {
                result.putIfAbsent(video.getTitle(), video);
            }
            if (StringUtils.isNotBlank(video.getPart())) {
                result.putIfAbsent(video.getPart(), video);
            }
        }
        return result;
    }

    private Map<Integer, BiliVideoPartInfoResponse.Video> buildOnlineVideoPageMap(BiliVideoPartInfoResponse partInfo) {
        Map<Integer, BiliVideoPartInfoResponse.Video> result = new HashMap<>();
        if (partInfo == null || partInfo.getData() == null || partInfo.getData().getVideos() == null) {
            return result;
        }
        for (BiliVideoPartInfoResponse.Video video : partInfo.getData().getVideos()) {
            if (video != null && video.getPage() > 0) {
                result.putIfAbsent(video.getPage(), video);
            }
        }
        return result;
    }

    private RecordHistoryPart findLocalPartForOnlineRestore(List<RecordHistoryPart> localParts,
                                                            BiliVideoPartInfoResponse.Video video,
                                                            Set<Long> usedPartIds) {
        if (localParts == null || video == null) {
            return null;
        }
        RecordHistoryPart fallbackOnlinePart = null;
        for (RecordHistoryPart part : localParts) {
            if (!canUseLocalPartForRestore(part, usedPartIds)) {
                continue;
            }
            if (part.getPage() == video.getPage() && !"ONLINE_PART".equals(part.getSourceType())) {
                return part;
            }
            if (part.getPage() == video.getPage() && fallbackOnlinePart == null) {
                fallbackOnlinePart = part;
            }
        }
        for (RecordHistoryPart part : localParts) {
            if (!canUseLocalPartForRestore(part, usedPartIds)) {
                continue;
            }
            if (video.getCid() > 0 && part.getCid() != null && part.getCid() == video.getCid()) {
                return part;
            }
            if (StringUtils.isNotBlank(video.getFilename()) && video.getFilename().equals(part.getFileName())) {
                return part;
            }
        }
        for (RecordHistoryPart part : localParts) {
            if (!canUseLocalPartForRestore(part, usedPartIds)) {
                continue;
            }
            if (StringUtils.isNotBlank(video.getTitle()) && video.getTitle().equals(part.getTitle())) {
                return part;
            }
            if (StringUtils.isNotBlank(video.getPart()) && video.getPart().equals(part.getTitle())) {
                return part;
            }
        }
        return fallbackOnlinePart;
    }

    private boolean canUseLocalPartForRestore(RecordHistoryPart part, Set<Long> usedPartIds) {
        return part != null
                && part.getId() != null
                && !usedPartIds.contains(part.getId())
                && !"EDIT_PART".equals(part.getSourceType());
    }

    private boolean isEditTempPath(String filePath) {
        if (StringUtils.isBlank(filePath)) {
            return false;
        }
        String normalized = filePath.replace('\\', '/');
        return normalized.contains("/_edit_uploads/");
    }

    private boolean shouldDeleteUnmatchedLocalPartAfterRestore(RecordHistoryPart part,
                                                               List<BiliVideoPartInfoResponse.Video> onlineVideos) {
        if (part == null || part.isRecording()) {
            return false;
        }
        boolean onlineHasSamePage = onlineVideos != null && part.getPage() > 0
                && onlineVideos.stream().anyMatch(v -> v != null && v.getPage() == part.getPage());
        boolean missingOnlineIdentity = StringUtils.isBlank(part.getFileName())
                && (part.getCid() == null || part.getCid() <= 0);
        boolean abnormal = !part.isUpload()
                && (part.getUploadRetryCount() >= UPLOAD_RETRY_GIVE_UP
                || StringUtils.isNotBlank(part.getDeleteFailType())
                || StringUtils.isBlank(part.getFilePath())
                || isEditTempPath(part.getFilePath()));
        return onlineHasSamePage || missingOnlineIdentity || abnormal;
    }

    void markHistoryPendingReviewAfterEdit(RecordHistory history) {
        if (history == null) {
            return;
        }
        history.setEditPartsUploading(false);
        history.setForceArchived(false);
        history.setCode(-1);
        history.setUpdateTime(LocalDateTime.now());
    }

    private void markHistoryWorkingForEdit(RecordHistory history, EditPartsTaskStatus status) {
        if (history == null || status == null) {
            return;
        }
        status.previousCode = history.getCode();
        status.previousForceArchived = history.isForceArchived();
        status.historyMarkedWorking = true;
        status.historyCode = history.getCode();
        status.historyEditPartsUploading = true;
        status.historyStatus = "分P上传中";
        history.setEditPartsUploading(true);
        history.setUpdateTime(LocalDateTime.now());
        historyRepository.save(history);
    }

    private void restoreHistoryStateAfterEditFailure(EditPartsTaskStatus status) {
        if (status == null || !status.historyMarkedWorking || status.historyId == null || status.previousCode == null) {
            return;
        }
        RecordHistory history = historyRepository.findById(status.historyId).orElse(null);
        if (history == null || !history.isEditPartsUploading()) {
            return;
        }
        history.setCode(status.previousCode);
        history.setEditPartsUploading(false);
        history.setForceArchived(Boolean.TRUE.equals(status.previousForceArchived));
        history.setUpdateTime(LocalDateTime.now());
        historyRepository.save(history);
        status.historyCode = history.getCode();
        status.historyEditPartsUploading = false;
        status.historyStatus = history.getStatus();
    }

    private void syncEditHistoryStatusImmediately(Long historyId) {
        if (historyId == null) {
            return;
        }
        RecordHistory latest = historyRepository.findById(historyId).orElse(null);
        if (latest == null || StringUtils.isBlank(latest.getBvId())) {
            return;
        }
        try {
            videoSyncJob.syncStatusOnly(latest);
        } catch (Exception e) {
            log.debug("[BLR] {}", LogKvs.event("Publish.EditParts.SyncStatusFailed")
                    .add("historyId", historyId)
                    .addIfNotBlank("bvid", latest.getBvId())
                    .addIfNotBlank("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()));
        }
    }

    private BiliVideoPartInfoResponse.Video resolveOnlineVideo(
            RecordHistoryPart part,
            Map<String, BiliVideoPartInfoResponse.Video> onlineByTitle,
            Map<Integer, BiliVideoPartInfoResponse.Video> onlineByPage) {
        if (part == null) {
            return null;
        }
        BiliVideoPartInfoResponse.Video video = null;
        if (part.getPage() > 0 && onlineByPage != null) {
            video = onlineByPage.get(part.getPage());
        }
        if (video == null && StringUtils.isNotBlank(part.getTitle()) && onlineByTitle != null) {
            video = onlineByTitle.get(part.getTitle());
        }
        return video;
    }

    private boolean isOnlineVideoFailedForEdit(BiliVideoPartInfoResponse.Video video) {
        if (video == null) {
            return false;
        }
        return (video.getFailCode() == 9 && video.getXcodeState() == 3)
                || (video.getFailCode() == 14 && video.getXcodeState() == 1)
                || (video.getFailCode() == 0 && video.getXcodeState() == 2);
    }

    private boolean isOnlineVideoUsable(BiliVideoPartInfoResponse.Video video) {
        return video != null
                && !isOnlineVideoFailedForEdit(video)
                && StringUtils.isNotBlank(video.getFilename());
    }

    private void syncPartFromOnlineVideo(RecordHistoryPart part, BiliVideoPartInfoResponse.Video onlineVideo) {
        if (part == null || onlineVideo == null) {
            return;
        }
        boolean changed = false;
        if (StringUtils.isNotBlank(onlineVideo.getFilename()) && !Objects.equals(part.getFileName(), onlineVideo.getFilename())) {
            part.setFileName(onlineVideo.getFilename());
            changed = true;
        }
        if (onlineVideo.getCid() > 0 && (part.getCid() == null || part.getCid() <= 0 || !Objects.equals(part.getCid(), onlineVideo.getCid()))) {
            part.setCid(onlineVideo.getCid());
            changed = true;
        }
        if (!part.isUpload()) {
            part.setUpload(true);
            changed = true;
        }
        if (changed) {
            partRepository.save(part);
        }
    }

    private EditVideosBuildResult buildEditVideos(
            RecordHistory history,
            RecordRoom room,
            List<RecordHistoryPart> uploadParts,
            BiliVideoPartInfoResponse videoPartInfo,
            Map<String, BiliVideoPartInfoResponse.Video> onlineByTitle,
            Map<Integer, BiliVideoPartInfoResponse.Video> onlineByPage) {
        Map<String, Object> historyTemplateMap = buildHistoryTemplateMap(history, room);

        List<SingleVideoDto> dtos = new ArrayList<>();
        Map<String, SingleVideoDto> dtoByOnlineKey = new HashMap<>();
        if (videoPartInfo != null && videoPartInfo.getData() != null && videoPartInfo.getData().getVideos() != null) {
            List<BiliVideoPartInfoResponse.Video> onlineVideos = new ArrayList<>(videoPartInfo.getData().getVideos());
            onlineVideos.sort(Comparator.comparingInt(BiliVideoPartInfoResponse.Video::getPage));
            for (BiliVideoPartInfoResponse.Video onlineVideo : onlineVideos) {
                if (!isOnlineVideoUsable(onlineVideo)) {
                    continue;
                }
                SingleVideoDto dto = new SingleVideoDto();
                dto.setTitle(StringUtils.defaultIfBlank(onlineVideo.getTitle(), onlineVideo.getPart()));
                dto.setDesc("");
                dto.setFilename(onlineVideo.getFilename());
                if (onlineVideo.getCid() > 0) {
                    dto.setCid(onlineVideo.getCid());
                }
                dtos.add(dto);
                String key = onlineVideoKey(onlineVideo);
                if (StringUtils.isNotBlank(key)) {
                    dtoByOnlineKey.put(key, dto);
                }
            }
        }
        int blockedPartCount = 0;
        for (int i = 0; i < uploadParts.size(); i++) {
            RecordHistoryPart uploadPart = uploadParts.get(i);
            SingleVideoDto dto = null;
            Map<String, Object> partTemplateMap = buildPartTemplateMap(historyTemplateMap, uploadPart, i + 1);
            String partTitle = this.template(room.getPartTitleTemplate(), partTemplateMap).getDesc();
            uploadPart.setTitle(partTitle);
            BiliVideoPartInfoResponse.Video onlineVideo = resolveOnlineVideo(uploadPart, onlineByTitle, onlineByPage);
            if (StringUtils.isBlank(uploadPart.getFileName()) && onlineVideo != null && StringUtils.isNotBlank(onlineVideo.getFilename())) {
                uploadPart.setFileName(onlineVideo.getFilename());
            }
            if ((uploadPart.getCid() == null || uploadPart.getCid() <= 0) && onlineVideo != null && onlineVideo.getCid() > 0) {
                uploadPart.setCid(onlineVideo.getCid());
            }
            uploadPart = partRepository.save(uploadPart);
            String onlineKey = onlineVideoKey(onlineVideo);
            if (StringUtils.isNotBlank(onlineKey)) {
                dto = dtoByOnlineKey.get(onlineKey);
            }
            boolean hasUploadedLocalFile = uploadPart.isUpload() && StringUtils.isNotBlank(uploadPart.getFileName());
            if (dto == null) {
                if (!hasUploadedLocalFile) {
                    blockedPartCount++;
                    log.warn("[BLR] {}", LogKvs.event("Publish.Edit.VideoList.BlockedPart")
                            .add("historyId", history.getId())
                            .add("partId", uploadPart.getId())
                            .add("upload", uploadPart.isUpload())
                            .addIfNotBlank("fileName", uploadPart.getFileName())
                            .addIfNotBlank("title", uploadPart.getTitle()));
                    continue;
                }
                dto = new SingleVideoDto();
                dtos.add(dto);
            }
            dto.setDesc("");
            dto.setTitle(partTitle);
            if (hasUploadedLocalFile) {
                dto.setFilename(uploadPart.getFileName());
            }
            if (uploadPart.getCid() != null && uploadPart.getCid() > 0) {
                dto.setCid(uploadPart.getCid());
            }
            if (StringUtils.isBlank(dto.getFilename())) {
                blockedPartCount++;
                log.warn("[BLR] {}", LogKvs.event("Publish.Edit.VideoList.BlockedPart")
                        .add("historyId", history.getId())
                        .add("partId", uploadPart.getId())
                        .add("reason", "filename_missing_after_merge"));
            }
        }

        return new EditVideosBuildResult(dtos, blockedPartCount);
    }

    private String onlineVideoKey(BiliVideoPartInfoResponse.Video video) {
        if (video == null) {
            return null;
        }
        if (video.getPage() > 0) {
            return "page:" + video.getPage();
        }
        if (StringUtils.isNotBlank(video.getFilename())) {
            return "filename:" + video.getFilename();
        }
        if (StringUtils.isNotBlank(video.getTitle())) {
            return "title:" + video.getTitle();
        }
        if (StringUtils.isNotBlank(video.getPart())) {
            return "title:" + video.getPart();
        }
        return null;
    }

    private VideoEditUploadDto buildVideoEditUploadDto(RecordHistory history, RecordRoom room, long aid, List<SingleVideoDto> videos) {
        Map<String, Object> map = buildHistoryTemplateMap(history, room);
        VideoEditUploadDto videoUploadDto = new VideoEditUploadDto();
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
        videoUploadDto.setVideos(videos);
        videoUploadDto.setTag(this.template(room.getTags(), map).getDesc());
        videoUploadDto.setIs_only_self(room.getIsOnlySelf());
        videoUploadDto.setAid(aid);
        return videoUploadDto;
    }

    private EditAuthContext resolveEditAuth(RecordHistory history, RecordRoom room) {
        return resolveEditAuth(history, room, null);
    }

    private EditAuthContext resolveEditAuth(RecordHistory history, RecordRoom room, EditPartsTaskStatus status) {
        EditAuthContext ctx = new EditAuthContext();
        if (history == null) {
            ctx.message = "稿件不存在";
            return ctx;
        }
        if (!history.isPublish() || !hasOnlineIdentity(history)) {
            ctx.message = "稿件未投稿或缺少 avId/bvId";
            return ctx;
        }
        if (history.isEditPartsUploading() && status == null) {
            ctx.message = "分P编辑任务正在上传，请等待当前任务完成";
            return ctx;
        }
        if (!isEditablePublishedCode(effectiveEditAuthCode(history, status))) {
            ctx.message = "仅审核通过或被退回的稿件支持编辑分P";
            return ctx;
        }
        if (room == null || room.getUploadUserId() == null) {
            ctx.message = "投稿账号不存在";
            return ctx;
        }
        Optional<BiliBiliUser> userOptional = biliUserRepository.findById(room.getUploadUserId());
        if (userOptional.isEmpty() || !userOptional.get().isLogin()) {
            ctx.message = "投稿账号未登录";
            return ctx;
        }
        ctx.canEdit = true;
        ctx.message = "ok";
        ctx.user = userOptional.get();
        return ctx;
    }

    private boolean isEditablePublishedCode(int code) {
        return code == 0 || code == -50 || code == -2;
    }

    private int effectiveEditAuthCode(RecordHistory history, EditPartsTaskStatus status) {
        if (history != null
                && status != null
                && status.historyMarkedWorking
                && history.isEditPartsUploading()
                && status.previousCode != null) {
            return status.previousCode;
        }
        return history == null ? -1 : history.getCode();
    }

    private List<EditPartSubmitItem> parseEditPartSubmitItems(Object raw) {
        List<EditPartSubmitItem> result = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return result;
        }
        for (Object obj : list) {
            if (!(obj instanceof Map<?, ?> map)) {
                continue;
            }
            EditPartSubmitItem item = new EditPartSubmitItem();
            item.partId = longValue(map.get("partId"));
            item.onlinePage = intValue(map.get("onlinePage"));
            item.originalOnlinePage = intValue(map.get("originalOnlinePage"));
            item.title = stringValue(map.get("title"));
            item.originalTitle = stringValue(map.get("originalTitle"));
            item.filename = stringValue(map.get("filename"));
            item.originalFilename = stringValue(map.get("originalFilename"));
            item.cid = longPrimitiveValue(map.get("cid"));
            item.originalCid = longPrimitiveValue(map.get("originalCid"));
            item.deleted = booleanValue(map.get("deleted"));
            item.fileRef = stringValue(map.get("fileRef"));
            item.filePath = StringUtils.defaultIfBlank(stringValue(map.get("filePath")), item.fileRef);
            item.source = stringValue(map.get("source"));
            result.add(item);
        }
        return result;
    }

    private BiliVideoPartInfoResponse.Video findOnlineVideoForSubmitItem(EditPartSubmitItem item,
                                                                          Map<Integer, BiliVideoPartInfoResponse.Video> onlineByPage,
                                                                          Map<String, BiliVideoPartInfoResponse.Video> onlineByTitle,
                                                                          Map<String, BiliVideoPartInfoResponse.Video> onlineByFilename,
                                                                          Map<Long, BiliVideoPartInfoResponse.Video> onlineByCid) {
        if (item == null) {
            return null;
        }
        BiliVideoPartInfoResponse.Video video = onlineByPage.get(item.onlinePage);
        if (video != null) {
            return video;
        }
        video = onlineByPage.get(item.originalOnlinePage);
        if (video != null) {
            return video;
        }
        if (StringUtils.isNotBlank(item.originalFilename)) {
            video = onlineByFilename.get(item.originalFilename);
            if (video != null) {
                return video;
            }
        }
        if (StringUtils.isNotBlank(item.filename)) {
            video = onlineByFilename.get(item.filename);
            if (video != null) {
                return video;
            }
        }
        if (item.originalCid > 0) {
            video = onlineByCid.get(item.originalCid);
            if (video != null) {
                return video;
            }
        }
        if (item.cid > 0) {
            video = onlineByCid.get(item.cid);
            if (video != null) {
                return video;
            }
        }
        if (StringUtils.isNotBlank(item.originalTitle)) {
            video = onlineByTitle.get(item.originalTitle);
            if (video != null) {
                return video;
            }
        }
        if (StringUtils.isNotBlank(item.title)) {
            return onlineByTitle.get(item.title);
        }
        return null;
    }

    private RecordHistoryPart prepareEditUploadPart(RecordHistory history, RecordRoom room, EditPartSubmitItem item, int page) {
        RecordHistoryPart part = new RecordHistoryPart();
        part.setHistoryId(history.getId());
        part.setRoomId(history.getRoomId());
        part.setStartTime(history.getStartTime());
        part.setEndTime(history.getEndTime());
        part.setRecording(false);
        part.setSourceType("EDIT_PART");
        String path = normalizeFilePath(StringUtils.defaultIfBlank(item.filePath, item.fileRef));
        if (StringUtils.isNotBlank(path)) {
            String realPath = resolveTrustedLocalFile(path);
            if (realPath == null) {
                log.warn("[BLR] {}", LogKvs.event("Publish.EditParts.FileNotUnderWorkPath")
                        .add("historyId", history.getId())
                        .add("path", path));
                return null;
            }
            path = realPath;
        }
        File file = new File(path);
        part.setRoomId(history.getRoomId());
        part.setHistoryId(history.getId());
        part.setPage(page);
        part.setPartOrder(page);
        part.setTitle(StringUtils.defaultIfBlank(item.title, "P" + page));
        part.setLiveTitle(StringUtils.defaultIfBlank(history.getTitle(), part.getTitle()));
        part.setFilePath(path);
        part.setFileSize(file.exists() ? file.length() : part.getFileSize());
        part.setUpload(false);
        part.setFileName(null);
        part.setCid(null);
        part.setUploadRetryCount(0);
        part.setDeleteFailType(null);
        part.setDeleteFailReason(null);
        part.setRecording(false);
        RecordHistoryPart saved = partRepository.save(part);
        partFileLocationService.registerPrimary(saved);
        return saved;
    }

    private RecordHistoryPart syncEditUploadResult(RecordHistory history, EditPartSubmitItem item, RecordHistoryPart uploadPart,
                                                   int page, String title, String filePath, String fileName, Long cid, Long fileSize) {
        RecordHistoryPart target = null;
        if (item.partId != null) {
            target = partRepository.findById(item.partId).orElse(null);
            if (target != null && "EDIT_PART".equals(target.getSourceType())) {
                target = null;
            }
        }
        if (target == null) {
            target = uploadPart;
            target.setSourceType(null);
        }
        if (StringUtils.isBlank(filePath) && uploadPart != null && uploadPart.getId() != null && uploadPart.getId().equals(target.getId())) {
            target.setFilePath(null);
        }
        target = syncEditPartLocalState(target, page, title, filePath, fileName, cid, fileSize);
        if (uploadPart != null && uploadPart.getId() != null && !uploadPart.getId().equals(target.getId())) {
            try {
                deletePartAndXmlIssue(uploadPart);
            } catch (Exception e) {
                log.debug("[BLR] {}", LogKvs.event("Publish.EditParts.TempPartDeleteFailed")
                        .add("historyId", history.getId())
                        .add("partId", uploadPart.getId())
                        .addIfNotBlank("err", e.getMessage()));
            }
        }
        return target;
    }

    private RecordHistoryPart syncEditPartLocalState(RecordHistoryPart part, int page, String title, String filePath, String fileName, Long cid) {
        return syncEditPartLocalState(part, page, title, filePath, fileName, cid, null);
    }

    private RecordHistoryPart syncEditPartLocalState(RecordHistoryPart part, int page, String title, String filePath, String fileName, Long cid, Long fileSize) {
        part.setPage(page);
        part.setPartOrder(page);
        part.setTitle(title);
        if (StringUtils.isNotBlank(filePath)) {
            part.setFilePath(filePath);
            File file = new File(filePath);
            if (file.exists() && file.isFile()) {
                part.setFileSize(file.length());
            }
        } else if (fileSize != null && fileSize >= 0) {
            part.setFileSize(fileSize);
        }
        part.setFileName(fileName);
        if (cid != null && cid > 0) {
            part.setCid(cid);
        }
        part.setUpload(true);
        part.setUploadRetryCount(0);
        part.setDeleteFailType(null);
        part.setDeleteFailReason(null);
        RecordHistoryPart saved = partRepository.save(part);
        if (StringUtils.isNotBlank(filePath)) {
            partFileLocationService.registerPrimary(saved);
        }
        return saved;
    }

    private RecordHistoryPart syncExistingOnlinePart(RecordHistory history, EditPartSubmitItem item, int page, SingleVideoDto dto) {
        RecordHistoryPart part = item.partId == null ? null : partRepository.findById(item.partId).orElse(null);
        if (part == null && item.onlinePage > 0) {
            List<RecordHistoryPart> parts = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());
            for (RecordHistoryPart candidate : parts) {
                if (candidate.getPage() == item.onlinePage) {
                    part = candidate;
                    break;
                }
            }
        }
        if (part == null) {
            part = new RecordHistoryPart();
            part.setHistoryId(history.getId());
            part.setRoomId(history.getRoomId());
            part.setStartTime(history.getStartTime());
            part.setEndTime(history.getEndTime());
            part.setRecording(false);
            part.setSourceType("ONLINE_PART");
        }
        return syncEditPartLocalState(part, page, dto.getTitle(), part.getFilePath(), dto.getFilename(), dto.getCid());
    }

    private void cleanupStaleEditPartLocalState(RecordHistory history, Set<Long> submittedPartIds) {
        if (history == null || history.getId() == null || submittedPartIds == null || submittedPartIds.isEmpty()) {
            return;
        }
        List<RecordHistoryPart> parts = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());
        for (RecordHistoryPart part : parts) {
            if (part == null || part.getId() == null || submittedPartIds.contains(part.getId())) {
                continue;
            }
            try {
                deletePartAndXmlIssue(part);
            } catch (Exception e) {
                log.debug("[BLR] {}", LogKvs.event("Publish.EditParts.StalePartDeleteFailed")
                        .add("historyId", history.getId())
                        .add("partId", part.getId())
                        .addIfNotBlank("err", e.getMessage()));
            }
        }
    }

    private void deletePartAndXmlIssue(RecordHistoryPart part) {
        if (part == null) {
            return;
        }
        partRepository.delete(part);
        if (part.getId() != null) {
            xmlIssueService.clear(part.getId());
        }
    }

    private void markEditPartsTaskFailed(EditPartsTaskStatus status, String message) {
        restoreHistoryStateAfterEditFailure(status);
        captureEditPartsHistoryState(status);
        status.status = "FAILED";
        status.message = StringUtils.defaultIfBlank(message, "编辑失败");
        status.endTime = LocalDateTime.now();
    }

    private void captureEditPartsHistoryState(EditPartsTaskStatus status) {
        if (status == null || status.historyId == null) {
            return;
        }
        RecordHistory history = historyRepository.findById(status.historyId).orElse(null);
        if (history == null) {
            status.historyEditPartsUploading = false;
            status.historyStatus = null;
            return;
        }
        status.historyCode = history.getCode();
        status.historyEditPartsUploading = history.isEditPartsUploading();
        status.historyStatus = history.getStatus();
    }

    private boolean deleteDirectoryQuietly(Path path) {
        if (path == null || !Files.exists(path)) {
            return false;
        }
        try {
            Path workRoot = Paths.get(workPath).normalize();
            if (!path.normalize().startsWith(workRoot)) {
                return false;
            }
            List<Path> paths = Files.walk(path)
                    .sorted(Comparator.reverseOrder())
                    .collect(Collectors.toList());
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String safePathSegment(String raw) {
        return StringUtils.defaultIfBlank(raw, "default").replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String safeFileName(String raw) {
        String name = StringUtils.defaultIfBlank(raw, "upload.mp4").replace("\\", "/");
        int idx = name.lastIndexOf('/');
        if (idx >= 0) {
            name = name.substring(idx + 1);
        }
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return StringUtils.defaultIfBlank(name, "upload.mp4");
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private Long longValue(Object value) {
        if (value == null || StringUtils.isBlank(String.valueOf(value))) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private long longPrimitiveValue(Object value) {
        Long parsed = longValue(value);
        return parsed == null ? 0L : parsed;
    }

    private int intValue(Object value) {
        if (value == null || StringUtils.isBlank(String.valueOf(value))) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    @Data
    @AllArgsConstructor
    private static class EditVideosBuildResult {
        private List<SingleVideoDto> videos;
        private int blockedPartCount;

        boolean isBlocked() {
            return blockedPartCount > 0;
        }
    }

    private static class EditAuthContext {
        private boolean canEdit;
        private String message;
        private BiliBiliUser user;
    }

    private static class EditPartSubmitItem {
        private Long partId;
        private int onlinePage;
        private int originalOnlinePage;
        private String title;
        private String originalTitle;
        private String filename;
        private String originalFilename;
        private long cid;
        private long originalCid;
        private boolean deleted;
        private String fileRef;
        private String filePath;
        private String source;
    }

    private static class EditPartsTaskStatus {
        private Long historyId;
        private String status;
        private String message;
        private String sessionId;
        private Integer code;
        private String responseMessage;
        private String responseSnippet;
        private Integer historyCode;
        private Boolean historyEditPartsUploading;
        private String historyStatus;
        private Integer previousCode;
        private Boolean previousForceArchived;
        private boolean historyMarkedWorking;
        private LocalDateTime startTime;
        private LocalDateTime endTime;

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("historyId", historyId);
            map.put("status", status);
            map.put("message", message);
            map.put("sessionId", sessionId);
            map.put("code", code);
            map.put("responseMessage", responseMessage);
            map.put("responseSnippet", responseSnippet);
            map.put("historyCode", historyCode);
            map.put("historyEditPartsUploading", historyEditPartsUploading);
            map.put("historyStatus", historyStatus);
            map.put("startTime", startTime);
            map.put("endTime", endTime);
            return map;
        }
    }

    private void uploadPartWithUserSerialBlocking(RecordRoom room, RecordHistoryPart part) {
        if (room == null || room.getUploadUserId() == null) {
            uploadServiceFactory.getUploadService(room != null ? room.getLine() : null).upload(part);
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<RuntimeException> runtimeRef = new AtomicReference<>();
        boolean enqueued = uploadUserSerialScheduler.submitIfPartNotPending(
                room.getUploadUserId(),
                room.getRoomId(),
                part.getHistoryId(),
                part.getId(),
                "publish-sync",
                () -> {
                    try {
                        uploadServiceFactory.getUploadService(room.getLine()).upload(part);
                    } catch (RuntimeException e) {
                        runtimeRef.set(e);
                        throw e;
                    } finally {
                        done.countDown();
                    }
                }
        );
        if (!enqueued) {
            log.info("[BLR] {}", LogKvs.event("Publish.PartUpload.WaitQueued")
                    .add("roomId", room.getRoomId())
                    .add("historyId", part.getHistoryId())
                    .add("partId", part.getId()));
            waitExistingPartUpload(part);
            return;
        }
        try {
            long timeoutMinutes = 30;
            long startTime = System.currentTimeMillis();
            long timeoutMs = timeoutMinutes * 60 * 1000;
            
            while (!done.await(1, TimeUnit.SECONDS)) {
                if (shutdownState.isShuttingDown()) {
                    throw new RuntimeException("UPLOAD_INTERRUPTED_BY_SHUTDOWN");
                }
                
                long elapsedMs = System.currentTimeMillis() - startTime;
                if (elapsedMs > timeoutMs) {
                    log.info("[BLR] {}", LogKvs.event("Publish.PartUpload.Deferred")
                            .add("historyId", part.getHistoryId())
                            .add("partId", part.getId())
                            .add("roomId", part.getRoomId())
                            .add("timeoutMinutes", timeoutMinutes)
                            .add("elapsedMs", elapsedMs));
                    throw new PartUploadWaitTimeoutException(part.getHistoryId(), part.getId(),
                            "UPLOAD_WAIT_TIMEOUT: 分P仍在上传，等待超过" + timeoutMinutes + "分钟，本轮投稿延后");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[BLR] {}", LogKvs.event("Publish.PartUpload.Interrupted")
                    .add("historyId", part.getHistoryId())
                    .add("partId", part.getId()), e);
            throw new RuntimeException("UPLOAD_INTERRUPTED", e);
        }
        RuntimeException ex = runtimeRef.get();
        if (ex != null) {
            throw ex;
        }
    }

    private void waitExistingPartUpload(RecordHistoryPart part) {
        long timeoutMs = 30L * 60L * 1000L;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (shutdownState.isShuttingDown()) {
                throw new RuntimeException("UPLOAD_INTERRUPTED_BY_SHUTDOWN");
            }
            Optional<RecordHistoryPart> latestOpt = partRepository.findById(part.getId());
            if (!latestOpt.isPresent()) {
                return;
            }
            RecordHistoryPart latest = latestOpt.get();
            if (latest.isUpload() || isSkippedPart(latest)) {
                return;
            }
            if (!uploadUserSerialScheduler.hasPendingPart(latest.getId())) {
                throw new RuntimeException("UPLOAD_QUEUE_DRAINED_BUT_NOT_UPLOADED");
            }
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("UPLOAD_INTERRUPTED", e);
            }
        }
        log.info("[BLR] {}", LogKvs.event("Publish.PartUpload.Deferred")
                .add("historyId", part.getHistoryId())
                .add("partId", part.getId())
                .add("timeoutMs", timeoutMs));
        throw new PartUploadWaitTimeoutException(part.getHistoryId(), part.getId(),
                "UPLOAD_WAIT_TIMEOUT: 等待队列中分P上传超过" + (timeoutMs / 60000L) + "分钟，本轮投稿延后");
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

    private Long resolveSectionId(RecordRoom room, BiliBiliUser biliBiliUser) {
        Long seasonId = normalizePositive(room.getSeasonId());
        Long sectionId = normalizePositive(room.getSectionId());
        if (seasonId == null) {
            if (sectionId != null) {
                room.setSectionId(null);
                roomRepository.save(room);
                log.info("[BLR] {}", LogKvs.event("Publish.Season.Section.Corrected")
                        .add("roomId", room.getRoomId())
                        .add("seasonId", null)
                        .add("oldSectionId", sectionId)
                        .add("newSectionId", null)
                        .add("action", "disable_no_season"));
            }
            return null;
        }
        String raw = BiliApi.getSeasons(biliBiliUser);
        if (StringUtils.isBlank(raw)) {
            log.warn("[BLR] {}", LogKvs.event("Publish.Season.ResolveSectionId.Empty")
                    .add("roomId", room.getRoomId())
                    .add("seasonId", seasonId));
            return null;
        }
        try {
            List<Map<String, Object>> seasons = JsonPath.read(raw, "$.data.seasons");
            for (Map<String, Object> item : seasons) {
                Object seasonObj = item.get("season");
                if (!(seasonObj instanceof Map<?, ?> seasonMap)) {
                    continue;
                }
                Long currentSeasonId = normalizePositive(asLong(seasonMap.get("id")));
                if (!Objects.equals(currentSeasonId, seasonId)) {
                    continue;
                }
                List<Long> sectionIds = extractSectionIds(item);
                Long firstSectionId = sectionIds.isEmpty() ? null : sectionIds.get(0);
                if (sectionId != null && sectionIds.contains(sectionId)) {
                    return sectionId;
                }
                if (firstSectionId != null) {
                    room.setSectionId(firstSectionId);
                    roomRepository.save(room);
                    log.info("[BLR] {}", LogKvs.event("Publish.Season.Section.Corrected")
                            .add("roomId", room.getRoomId())
                            .add("seasonId", seasonId)
                            .add("oldSectionId", sectionId)
                            .add("newSectionId", firstSectionId)
                            .add("action", "use_first_section"));
                    return firstSectionId;
                }
                room.setSeasonId(null);
                room.setSectionId(null);
                roomRepository.save(room);
                log.warn("[BLR] {}", LogKvs.event("Publish.Season.Section.Disabled")
                        .add("roomId", room.getRoomId())
                        .add("seasonId", seasonId)
                        .add("oldSectionId", sectionId)
                        .add("reason", "season_without_section"));
                return null;
            }
            room.setSeasonId(null);
            room.setSectionId(null);
            roomRepository.save(room);
            log.warn("[BLR] {}", LogKvs.event("Publish.Season.Section.Disabled")
                    .add("roomId", room.getRoomId())
                    .add("seasonId", seasonId)
                    .add("oldSectionId", sectionId)
                    .add("reason", "season_not_found"));
        } catch (Exception e) {
            log.warn("[BLR] {}", LogKvs.event("Publish.Season.ResolveSectionId.Failed")
                    .add("roomId", room.getRoomId())
                    .add("seasonId", seasonId)
                    .add("respLen", raw.length()), e);
        }
        return null;
    }

    private List<Long> extractSectionIds(Map<String, Object> seasonItem) {
        Object sectionsObj = seasonItem.get("sections");
        if (!(sectionsObj instanceof Map<?, ?> sectionsMap)) {
            return new ArrayList<>();
        }
        Object sectionsListObj = sectionsMap.get("sections");
        if (!(sectionsListObj instanceof List<?> sectionsList)) {
            return new ArrayList<>();
        }
        List<Long> sectionIds = new ArrayList<>();
        for (Object sectionObj : sectionsList) {
            if (!(sectionObj instanceof Map<?, ?> sectionMap)) {
                continue;
            }
            Long id = normalizePositive(asLong(sectionMap.get("id")));
            if (id != null) {
                sectionIds.add(id);
            }
        }
        return sectionIds;
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ignore) {
            return null;
        }
    }

    private Long normalizePositive(Long value) {
        if (value == null || value <= 0) {
            return null;
        }
        return value;
    }

    private static boolean isSkippedPart(RecordHistoryPart part) {
        if (part == null) {
            return false;
        }
        String type = part.getDeleteFailType();
        if (StringUtils.isBlank(type)) {
            return false;
        }
        return "SKIPPED_THRESHOLD".equals(type) || "MANUAL_SKIP".equals(type);
    }

    private List<RecordHistoryPart> filterPublishableParts(List<RecordHistoryPart> parts) {
        if (parts == null || parts.isEmpty()) {
            return new ArrayList<>();
        }
        List<RecordHistoryPart> candidates = parts.stream().filter(p -> !isSkippedPart(p)).collect(Collectors.toList());
        RecordPartPathService.PartSelection selection = partPathService.selectPreferredParts(candidates);
        if (!selection.suppressed().isEmpty()) {
            Long historyId = selection.selected().isEmpty() ? null : selection.selected().get(0).getHistoryId();
            log.warn("[BLR] {}", LogKvs.event("Publish.Parts.DuplicatePhysicalFileFiltered")
                    .add("historyId", historyId)
                    .add("keptPartIds", joinPartIds(selection.selected()))
                    .add("filteredPartIds", joinPartIds(selection.suppressed())));
        }
        return selection.selected();
    }

    private static String joinPartIds(List<RecordHistoryPart> parts) {
        return parts.stream().map(RecordHistoryPart::getId).filter(Objects::nonNull)
                .map(String::valueOf).collect(Collectors.joining(","));
    }

    private static String normalizeFilePath(String filePath) {
        if (filePath == null) {
            return null;
        }
        return filePath.replace("\\", "/");
    }

    private String resolveTrustedLocalFile(String filePath) {
        try {
            return storageRootService.matchTrustedExisting(Paths.get(filePath))
                    .filter(match -> Files.isRegularFile(match.resolvedPath()))
                    .map(match -> match.resolvedPath().toString().replace("\\", "/"))
                    .orElse(null);
        } catch (Exception e) {
            // 任意一个分辨率获取失败就拒绝
        }
        return null;
    }

    private static String extractFileNameNoExt(String filePath) {
        if (StringUtils.isBlank(filePath)) {
            return "unknown";
        }
        String fp = normalizeFilePath(filePath);
        int slash = fp.lastIndexOf("/");
        String name = slash >= 0 ? fp.substring(slash + 1) : fp;
        int dot = name.lastIndexOf(".");
        if (dot > 0) {
            return name.substring(0, dot);
        }
        return name;
    }

    private JSONObject parseJsonObject(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            return JSON.parseObject(raw);
        } catch (Exception e) {
            log.warn("[BLR] {}", LogKvs.event("Publish.WebPublish.ParseFailed")
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

    private String summarizePublishParts(List<RecordHistoryPart> uploadParts) {
        if (uploadParts == null || uploadParts.isEmpty()) {
            return "";
        }
        return uploadParts.stream()
                .limit(12)
                .map(part -> "partId=" + part.getId()
                        + ",upload=" + part.isUpload()
                        + ",flow=" + StringUtils.defaultIfBlank(part.getUploadFlow(), "UNKNOWN")
                        + ",fallback=" + part.isUploadFlowFallback()
                        + ",fallbackReason=" + abbreviateForLog(part.getUploadFlowFallbackReason(), 40)
                        + ",fileName=" + abbreviateForLog(part.getFileName(), 36)
                        + ",cid=" + (part.getCid() == null ? "null" : part.getCid())
                        + ",title=" + abbreviateForLog(part.getTitle(), 32))
                .collect(Collectors.joining(" | "));
    }

    private long countUploadFlow(List<RecordHistoryPart> uploadParts, String uploadFlow) {
        if (uploadParts == null || uploadParts.isEmpty()) {
            return 0L;
        }
        return uploadParts.stream()
                .filter(part -> StringUtils.equalsIgnoreCase(uploadFlow, part.getUploadFlow()))
                .count();
    }

    private long countUnknownUploadFlow(List<RecordHistoryPart> uploadParts) {
        if (uploadParts == null || uploadParts.isEmpty()) {
            return 0L;
        }
        return uploadParts.stream()
                .filter(part -> StringUtils.isBlank(part.getUploadFlow()))
                .count();
    }

    private long countUploadFlowFallback(List<RecordHistoryPart> uploadParts) {
        if (uploadParts == null || uploadParts.isEmpty()) {
            return 0L;
        }
        return uploadParts.stream()
                .filter(RecordHistoryPart::isUploadFlowFallback)
                .count();
    }

    private String summarizePublishVideos(List<SingleVideoDto> videos) {
        if (videos == null || videos.isEmpty()) {
            return "";
        }
        return videos.stream()
                .limit(12)
                .map(video -> "fileName=" + abbreviateForLog(video.getFilename(), 36)
                        + ",cid=" + (video.getCid() == null ? "null" : video.getCid())
                        + ",title=" + abbreviateForLog(video.getTitle(), 32))
                .collect(Collectors.joining(" | "));
    }

    private String abbreviateForLog(String value, int maxLen) {
        if (StringUtils.isBlank(value)) {
            return "";
        }
        String normalized = value.replace("\r", " ").replace("\n", " ");
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, maxLen) + "...";
    }

    private static final class PartUploadWaitTimeoutException extends RuntimeException {
        private PartUploadWaitTimeoutException(Long historyId, Long partId, String message) {
            super(message + " | historyId=" + historyId + " | partId=" + partId);
        }
    }

    @Data
    @AllArgsConstructor
    class DescDto {
        public final String desc;
        public final List<DescV2Dto> descV2Dtos;
    }
}


