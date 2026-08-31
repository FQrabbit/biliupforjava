package top.sshh.bililiverecoder.controller;


import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.*;
import top.sshh.bililiverecoder.entity.BiliBiliUser;
import top.sshh.bililiverecoder.entity.data.BiliVideoAuditDetailResponse;
import top.sshh.bililiverecoder.entity.data.BiliVideoPartInfoResponse;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.entity.PartFileOperation;
import top.sshh.bililiverecoder.repo.BiliUserRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.lifecycle.ShutdownState;
import top.sshh.bililiverecoder.service.RecordPartUploadService;
import top.sshh.bililiverecoder.service.UploadServiceFactory;
import top.sshh.bililiverecoder.service.UploadPauseService;
import top.sshh.bililiverecoder.service.PartFileLocationService;
import top.sshh.bililiverecoder.service.PartFileOperationService;
import top.sshh.bililiverecoder.service.StorageRootService;
import top.sshh.bililiverecoder.service.impl.RecordBiliPublishService;
import top.sshh.bililiverecoder.util.BiliApi;
import top.sshh.bililiverecoder.util.LogKvs;
import top.sshh.bililiverecoder.util.bili.Cookie;
import top.sshh.bililiverecoder.util.bili.WebCookie;
import top.sshh.bililiverecoder.util.bili.upload.EdtiorSpaceRequest;
import top.sshh.bililiverecoder.util.bili.upload.pojo.EditorSpaceBean;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RestController
@RequestMapping("/part")
public class PartController {
    private static final long REVIEW_INFO_SUCCESS_COOLDOWN_MS = 8000L;
    private static final long REVIEW_INFO_FAIL_COOLDOWN_MS = 1200L;
    private static final long ARCHIVE_PROGRESS_SUCCESS_COOLDOWN_MS = 5000L;
    private static final long ARCHIVE_PROGRESS_FAIL_COOLDOWN_MS = 1500L;
    private static final int ARCHIVE_PROGRESS_XCODE_PART_LIMIT = 50;

    @Autowired
    private BiliUserRepository userRepository;
    @Autowired
    private RecordRoomRepository roomRepository;
    @Autowired
    private RecordHistoryPartRepository partRepository;
    @Autowired
    private RecordHistoryRepository historyRepository;
    @Autowired
    private UploadServiceFactory uploadServiceFactory;
    @Autowired
    private RecordBiliPublishService publishService;
    @Autowired
    private ShutdownState shutdownState;
    @Autowired
    private UploadPauseService uploadPauseService;
    @Autowired
    private PartFileLocationService partFileLocationService;
    @Autowired
    private PartFileOperationService partFileOperationService;
    @Autowired
    private StorageRootService storageRootService;

    @Lazy
    @Autowired
    @Qualifier("editorBilibiliUploadService")
    private RecordPartUploadService editPartUploadService;
    private final Map<Long, ReviewInfoCacheEntry> reviewInfoCache = new ConcurrentHashMap<>();
    private final Map<Long, Object> reviewInfoLocks = new ConcurrentHashMap<>();
    private final Map<Long, ArchiveProgressCacheEntry> archiveProgressCache = new ConcurrentHashMap<>();
    private final Map<Long, Object> archiveProgressLocks = new ConcurrentHashMap<>();

    @PostMapping("/list/{id}")
    public List<RecordHistoryPart> list(@PathVariable("id") Long id) {
        return filterVisibleParts(partRepository.findByHistoryIdOrderByStartTimeAsc(id));
    }

    @PostMapping("/{id}/upload/pause")
    public Map<String, Object> pauseUpload(@PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> request) {
        String reason = request == null ? null : String.valueOf(request.getOrDefault("reason", ""));
        return uploadPauseService.pausePart(id, reason);
    }

    @PostMapping("/{id}/upload/resume")
    public Map<String, Object> resumeUpload(@PathVariable("id") Long id) {
        return uploadPauseService.resumePart(id);
    }

    @PostMapping("/list2/{id}")
    public Map<String, Object> list2(@PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> request) {
        Map<String, Object> resp = new LinkedHashMap<>();
        boolean forceRefreshReview = parseBooleanFlag(request == null ? null : request.get("forceRefreshReview"));
        List<RecordHistoryPart> parts = filterVisibleParts(partRepository.findByHistoryIdOrderByStartTimeAsc(id));
        Optional<RecordHistory> histOpt = historyRepository.findById(id);
        boolean historyPublished = histOpt.isPresent() && histOpt.get().isPublish();
        boolean historyEditableOnline = histOpt.isPresent() && RecordBiliPublishService.hasOnlineIdentity(histOpt.get());
        boolean historyRejected = histOpt.isPresent() && histOpt.get().isPublish() && histOpt.get().getCode() == -2;
        Map<String, Object> reviewDebug = new LinkedHashMap<>();
        reviewDebug.put("historyRejected", historyRejected);
        reviewDebug.put("forceRefreshReview", forceRefreshReview);
        reviewDebug.put("bvid", null);
        reviewDebug.put("hasBvid", false);
        reviewDebug.put("videoPartInfoEndpoint", "https://member.bilibili.com/x/vupre/web/archive/view");
        reviewDebug.put("auditDetailEndpoint", "https://member.bilibili.com/x/web/detail/audit");
        reviewDebug.put("authSource", null);
        reviewDebug.put("authUserId", null);
        reviewDebug.put("authUid", null);
        reviewDebug.put("authUname", null);
        reviewDebug.put("authPolicy", "publish_user_first_fallback_room_when_missing");
        reviewDebug.put("authBlocked", false);
        reviewDebug.put("authBlockedReason", null);
        reviewDebug.put("videoPartInfoCode", null);
        reviewDebug.put("videoPartInfoMessage", null);
        reviewDebug.put("auditDetailCode", null);
        reviewDebug.put("auditDetailMessage", null);
        reviewDebug.put("problemDetailCount", 0);
        reviewDebug.put("videoPartInfoRequestUrl", null);
        reviewDebug.put("auditDetailRequestUrl", null);
        reviewDebug.put("videoPartInfoRaw", null);
        reviewDebug.put("auditDetailRaw", null);
        reviewDebug.put("requestHeaderTemplate", "accept: */*; accept-language: zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6; cache-control: no-cache; pragma: no-cache; origin: https://member.bilibili.com; referer: https://member.bilibili.com/platform/upload-manager/archive-process?bvid=<BVID>; sec-fetch-site: same-origin; cookie: <投稿账号登录态>");
        Map<Integer, BiliVideoPartInfoResponse.Video> reviewByPage = new HashMap<>();
        Map<String, BiliVideoPartInfoResponse.Video> reviewByTitle = new HashMap<>();
        List<BiliVideoAuditDetailResponse.ProblemDetail> reviewProblemDetails = new ArrayList<>();
        if (historyRejected && histOpt.isPresent()) {
            RecordHistory history = histOpt.get();
            reviewDebug.put("bvid", history.getBvId());
            reviewDebug.put("hasBvid", !isBlank(history.getBvId()));
            if (!isBlank(history.getBvId())) {
                try {
                    RecordRoom room = roomRepository.findByRoomId(history.getRoomId());
                    ReviewAuthContext reviewAuth = resolveReviewAuthContext(history, room);
                    reviewDebug.put("authSource", reviewAuth.source);
                    reviewDebug.put("authUserId", reviewAuth.user == null ? null : reviewAuth.user.getId());
                    reviewDebug.put("authUid", reviewAuth.user == null ? null : reviewAuth.user.getUid());
                    reviewDebug.put("authUname", reviewAuth.user == null ? null : reviewAuth.user.getUname());
                    if (reviewAuth.user != null) {
                        ReviewInfoCacheEntry reviewInfo = loadReviewInfo(history.getId(), history.getBvId(), reviewAuth.user, forceRefreshReview);
                        reviewByPage.putAll(reviewInfo.byPage);
                        reviewByTitle.putAll(reviewInfo.byTitle);
                        reviewProblemDetails.addAll(reviewInfo.problemDetails);
                        reviewDebug.put("videoPartInfoCode", reviewInfo.videoPartInfoCode);
                        reviewDebug.put("videoPartInfoMessage", reviewInfo.videoPartInfoMessage);
                        reviewDebug.put("auditDetailCode", reviewInfo.auditDetailCode);
                        reviewDebug.put("auditDetailMessage", reviewInfo.auditDetailMessage);
                        reviewDebug.put("problemDetailCount", reviewInfo.problemDetails == null ? 0 : reviewInfo.problemDetails.size());
                        reviewDebug.put("videoPartInfoRequestUrl", reviewInfo.videoPartInfoRequestUrl);
                        reviewDebug.put("auditDetailRequestUrl", reviewInfo.auditDetailRequestUrl);
                        reviewDebug.put("videoPartInfoRaw", reviewInfo.videoPartInfoRaw);
                        reviewDebug.put("auditDetailRaw", reviewInfo.auditDetailRaw);
                    } else {
                        reviewDebug.put("authBlocked", true);
                        reviewDebug.put("authBlockedReason", reviewAuth.source);
                    }
                } catch (Exception e) {
                    log.debug("[BLR] {}", LogKvs.event("Part.List2.ReviewInfo.FetchFailed")
                            .add("historyId", id)
                            .add("forceRefreshReview", forceRefreshReview)
                            .add("err", e.getMessage())
                            .add("ex", e.getClass().getSimpleName()));
                }
            }
        }
        List<Map<String, Object>> items = new ArrayList<>();
        int blocking = 0;
        long nowMs = System.currentTimeMillis();
        long stableThresholdMs = 10L * 60L * 1000L;

        for (RecordHistoryPart p : parts) {
            PartFileLocationService.LocalFileView localFile = partFileLocationService.describe(p.getId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("roomId", p.getRoomId());
            m.put("historyId", p.getHistoryId());
            m.put("page", p.getPage());
            m.put("partOrder", p.getPartOrder());
            m.put("title", p.getTitle());
            m.put("fileName", p.getFileName());
            m.put("filePath", p.getFilePath());
            m.put("fileSize", p.getFileSize());
            m.put("duration", p.getDuration());
            m.put("recording", p.isRecording());
            m.put("upload", p.isUpload());
            m.put("startTime", p.getStartTime());
            m.put("endTime", p.getEndTime());
            m.put("uploadRetryCount", p.getUploadRetryCount());
            m.put("deleteFailType", p.getDeleteFailType());
            m.put("deleteFailReason", p.getDeleteFailReason());
            m.put("uploadFlow", p.getUploadFlow());
            m.put("uploadPaused", Boolean.TRUE.equals(p.getUploadPaused()));
            m.put("uploadPausedAt", p.getUploadPausedAt());
            m.put("uploadPauseReason", p.getUploadPauseReason());
            m.put("localFileState", localFile.state().name());
            m.put("localFileAvailable", localFile.available());
            m.put("localFileExpected", localFile.expected());
            m.put("storageType", localFile.storageType());
            m.put("rootStatus", localFile.rootStatus());
            m.put("primaryPath", localFile.primaryPath());
            m.put("replicaCount", localFile.replicaCount());
            var latestFileOperation = partFileOperationService.latest(p.getId()).orElse(null);
            m.put("fileOperationKey", latestFileOperation == null ? null : latestFileOperation.getOperationKey());
            m.put("fileOperationStatus", latestFileOperation == null ? null : latestFileOperation.getStatus());
            m.put("fileOperationError", latestFileOperation == null ? null : latestFileOperation.getErrorMessage());
            if (localFile.state() != PartFileLocationService.LocalFileState.ROOT_OFFLINE && latestFileOperation != null) {
                if (latestFileOperation.getStatus() == PartFileOperation.OperationStatus.FAILED) {
                    m.put("localFileState", PartFileLocationService.LocalFileState.PROCESS_FAILED.name());
                } else if (latestFileOperation.getStatus() == PartFileOperation.OperationStatus.PENDING
                        || latestFileOperation.getStatus() == PartFileOperation.OperationStatus.RUNNING) {
                    m.put("localFileState", PartFileLocationService.LocalFileState.PROCESSING.name());
                }
            }

            String issueCode = null;
            String issueMessage = null;
            boolean actionable = false;
            boolean blockingIssue = false;
            List<String> actions = new ArrayList<>();

            BiliVideoPartInfoResponse.Video reviewVideo = null;
            if (!reviewByPage.isEmpty() || !reviewByTitle.isEmpty()) {
                if (p.getPage() > 0) {
                    reviewVideo = reviewByPage.get(p.getPage());
                }
                if (reviewVideo == null && !isBlank(p.getTitle())) {
                    reviewVideo = reviewByTitle.get(p.getTitle());
                }
            }

            if (reviewVideo != null) {
                m.put("reviewFailCode", reviewVideo.getFailCode());
                m.put("reviewXcodeState", reviewVideo.getXcodeState());
                m.put("reviewFailDesc", reviewVideo.getFailDesc());
                m.put("reviewReasonSource", "vupre_fail_desc");
            } else {
                m.put("reviewFailCode", null);
                m.put("reviewXcodeState", null);
                m.put("reviewFailDesc", null);
                m.put("reviewReasonSource", null);
            }

            boolean intentionallyDeleted = localFile.state() == PartFileLocationService.LocalFileState.DELETED_BY_POLICY;
            boolean rootOffline = localFile.state() == PartFileLocationService.LocalFileState.ROOT_OFFLINE;
            boolean processing = localFile.state() == PartFileLocationService.LocalFileState.PROCESSING
                    || (latestFileOperation != null && (latestFileOperation.getStatus() == PartFileOperation.OperationStatus.PENDING
                    || latestFileOperation.getStatus() == PartFileOperation.OperationStatus.RUNNING));
            boolean processFailed = localFile.state() == PartFileLocationService.LocalFileState.PROCESS_FAILED
                    || (latestFileOperation != null && latestFileOperation.getStatus() == PartFileOperation.OperationStatus.FAILED);
            if (intentionallyDeleted) {
                issueCode = "FILE_DELETED_BY_POLICY";
                issueMessage = historyRejected ? "素材已按规则删除，无法自动重新上传" : "本地素材已按配置规则清理";
            } else if (rootOffline) {
                issueCode = "ROOT_OFFLINE";
                issueMessage = "存储目录当前离线，恢复挂载后会自动重新检查";
            } else if (processing) {
                issueCode = "FILE_PROCESSING";
                issueMessage = "本地素材正在执行移动、复制或删除";
            } else if (processFailed) {
                issueCode = "FILE_PROCESS_FAILED";
                issueMessage = latestFileOperation != null && !isBlank(latestFileOperation.getErrorMessage())
                        ? latestFileOperation.getErrorMessage()
                        : isBlank(localFile.message()) ? "文件移动、复制或删除失败" : localFile.message();
                actionable = latestFileOperation != null;
                blockingIssue = !p.isUpload() && !historyPublished;
                if (actionable) actions.add("RETRY_FILE_PROCESS");
            } else if (!isBlank(p.getDeleteFailType()) || p.getUploadRetryCount() >= 9999) {
                issueCode = isBlank(p.getDeleteFailType()) ? "GIVE_UP" : p.getDeleteFailType();
                issueMessage = isBlank(p.getDeleteFailReason()) ? "该分P已被标记为跳过/放弃上传" : p.getDeleteFailReason();
                actionable = "SKIPPED_THRESHOLD".equals(issueCode)
                        || "MANUAL_SKIP".equals(issueCode)
                        || "FILE_MISSING".equals(issueCode)
                        || p.getUploadRetryCount() >= 9999;
                if (actionable && (!historyPublished || historyEditableOnline)) {
                    actions.add("BIND_FILE");
                    if (!historyPublished) {
                        actions.add("MARK_FINISHED");
                    }
                }
            } else if (!historyPublished || historyEditableOnline) {
                String fp = localFile.primaryPath();
                File f = fp == null ? null : new File(fp);
                boolean fileExists = localFile.available() && f != null && f.exists();
                boolean fileStable = fileExists && f.lastModified() > 0 && f.lastModified() < (nowMs - stableThresholdMs);

                if (p.isRecording() || p.getEndTime() == null) {
                    if (fileExists && fileStable) {
                        issueCode = "MISSING_CLOSE";
                        issueMessage = "疑似遗漏文件关闭事件：文件已稳定但仍显示录制中";
                        actionable = true;
                        blockingIssue = true;
                        actions.add("RESCAN");
                        if (!historyPublished) {
                            actions.add("MARK_FINISHED");
                        }
                    }
                } else if (!fileExists) {
                    issueCode = "FILE_MISSING";
                    issueMessage = "分P文件不存在或路径为空";
                    actionable = true;
                    blockingIssue = !p.isUpload() && !historyPublished;
                    actions.add("BIND_FILE");
                    if (!historyPublished) {
                        actions.add("MARK_FINISHED");
                    }
                }
            }

            boolean hasReviewFailSignal = reviewVideo != null
                    && (reviewVideo.getFailCode() != 0 || reviewVideo.getXcodeState() != 0)
                    && !isBlank(reviewVideo.getFailDesc());
            if (historyRejected && isBlank(issueMessage) && hasReviewFailSignal) {
                issueCode = isBlank(issueCode) ? "BILI_REVIEW_FAIL" : issueCode;
                if (reviewVideo.getPage() > 0) {
                    issueMessage = "B站审核提示(P" + reviewVideo.getPage() + "): " + reviewVideo.getFailDesc();
                } else {
                    issueMessage = "B站审核提示: " + reviewVideo.getFailDesc();
                }
            }

            if (blockingIssue) {
                blocking++;
            }

            boolean canControlUpload = !p.isUpload() && !p.isRecording() && p.getEndTime() != null && (!historyPublished || historyEditableOnline);
            if (canControlUpload) {
                if (Boolean.TRUE.equals(p.getUploadPaused())) {
                    actions.add("RESUME_UPLOAD");
                } else if (isBlank(issueCode) || "MISSING_CLOSE".equals(issueCode)) {
                    actions.add("PAUSE_UPLOAD");
                }
            }

            m.put("issueCode", issueCode);
            m.put("issueMessage", issueMessage);
            m.put("actionable", actionable);
            m.put("blocking", blockingIssue);
            m.put("actions", actions);
            items.add(m);
        }

        items.sort(Comparator.comparingInt(o -> {
            Object page = o.get("page");
            if (page instanceof Number) {
                return ((Number) page).intValue();
            }
            return 0;
        }));

        resp.put("items", items);
        resp.put("hasBlockingIssues", blocking > 0);
        resp.put("blockingIssueCount", blocking);
        resp.put("problem_detail", reviewProblemDetails);
        resp.put("problemDetail", reviewProblemDetails);
        resp.put("reviewDebug", reviewDebug);
        return resp;
    }

    @GetMapping("/archiveProgress/{id}")
    public Map<String, Object> archiveProgress(@PathVariable("id") Long id,
                                               @RequestParam(value = "force", defaultValue = "false") boolean force) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("historyId", id);
        resp.put("allowQuery", false);
        resp.put("success", false);
        if (id == null) {
            resp.put("type", "warning");
            resp.put("msg", "稿件不存在");
            return resp;
        }
        Optional<RecordHistory> histOpt = historyRepository.findById(id);
        if (histOpt.isEmpty()) {
            resp.put("type", "warning");
            resp.put("msg", "稿件不存在");
            return resp;
        }
        RecordHistory history = histOpt.get();
        resp.put("publish", history.isPublish());
        resp.put("bvid", history.getBvId());
        resp.put("code", history.getCode());
        if (!history.isPublish() || isBlank(history.getBvId())) {
            resp.put("type", "info");
            resp.put("msg", "稿件提交后才能查看转码进度");
            return resp;
        }
        resp.put("allowQuery", true);
        try {
            RecordRoom room = roomRepository.findByRoomId(history.getRoomId());
            ReviewAuthContext reviewAuth = resolveReviewAuthContext(history, room);
            resp.put("authSource", reviewAuth.source);
            resp.put("authUserId", reviewAuth.user == null ? null : reviewAuth.user.getId());
            resp.put("authUid", reviewAuth.user == null ? null : reviewAuth.user.getUid());
            resp.put("authUname", reviewAuth.user == null ? null : reviewAuth.user.getUname());
            if (reviewAuth.user == null) {
                resp.put("type", "warning");
                resp.put("msg", "无法确定可用的投稿账号登录态，暂时不能查询稿件进度");
                resp.put("authBlocked", true);
                resp.put("authBlockedReason", reviewAuth.source);
                return resp;
            }
            Map<String, Object> progress = loadArchiveProgressInfo(history.getId(), history.getBvId(), reviewAuth.user, force);
            resp.putAll(progress);
            return resp;
        } catch (Exception e) {
            log.debug("[BLR] {}", LogKvs.event("Part.ArchiveProgress.FetchFailed")
                    .add("historyId", id)
                    .add("bvid", history.getBvId())
                    .add("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()));
            resp.put("type", "warning");
            resp.put("msg", "获取稿件进度失败：" + e.getMessage());
            return resp;
        }
    }

    private List<RecordHistoryPart> filterVisibleParts(List<RecordHistoryPart> parts) {
        List<RecordHistoryPart> visible = new ArrayList<>();
        if (parts == null) {
            return visible;
        }
        for (RecordHistoryPart part : parts) {
            if (part == null) {
                continue;
            }
            if ("EDIT_PART".equals(part.getSourceType())) {
                continue;
            }
            visible.add(part);
        }
        return visible;
    }

    @PostMapping("/rescan/{id}")
    public Map<String, Object> rescan(@PathVariable("id") Long id) {
        long totalStartNs = System.nanoTime();
        Map<String, Object> result = new LinkedHashMap<>();
        Optional<RecordHistoryPart> partOptional = partRepository.findById(id);
        if (partOptional.isEmpty()) {
            result.put("type", "warning");
            result.put("msg", "分P不存在");
            return result;
        }
        RecordHistoryPart part = partOptional.get();
        PartFileLocationService.FileResolution resolution = partFileLocationService.resolveReadable(id);
        if (!resolution.available()) {
            result.put("type", "warning");
            result.put("msg", resolution.state() == PartFileLocationService.LocalFileState.ROOT_OFFLINE
                    ? "存储目录离线，请恢复挂载后再试" : "分P文件不存在，请先补全文件");
            return result;
        }
        File file = resolution.path().toFile();
        long stableThresholdMs = 10L * 60L * 1000L;
        long nowMs = System.currentTimeMillis();
        if (file.lastModified() > nowMs - stableThresholdMs) {
            result.put("type", "info");
            result.put("msg", "文件可能仍在写入，稍后再试");
            return result;
        }
        boolean changed = false;
        if (part.isRecording()) {
            part.setRecording(false);
            changed = true;
        }
        if (part.getEndTime() == null) {
            LocalDateTime fileTime = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(file.lastModified()), java.time.ZoneId.systemDefault());
            if (part.getStartTime() != null && fileTime.isBefore(part.getStartTime())) {
                result.put("type", "warning");
                result.put("msg", "文件时间早于分P开始时间，拒绝自动收尾");
                return result;
            }
            part.setEndTime(fileTime);
            changed = true;
        }
        long size = file.length();
        if (size > 0 && part.getFileSize() != size) {
            part.setFileSize(size);
            changed = true;
        }
        if (changed) {
            partRepository.save(part);
        }

        boolean triggered = false;

        log.info("[BLR] {}", LogKvs.event("PartRepair.Rescan.Done")
                .add("partId", part.getId())
                .add("historyId", part.getHistoryId())
                .add("roomId", part.getRoomId())
                .add("triggerUpload", triggered)
                .addRoundCount("stateChanged", changed ? 1 : 0)
                .addStageCostMs("total", totalStartNs));

        result.put("type", "success");
        result.put("msg", "已重试扫描并修正状态，上传将由补偿任务处理");
        return result;
    }

    @PostMapping("/markFinished/{id}")
    public Map<String, Object> markFinished(@PathVariable("id") Long id) {
        long totalStartNs = System.nanoTime();
        Map<String, Object> result = new LinkedHashMap<>();
        Optional<RecordHistoryPart> partOptional = partRepository.findById(id);
        if (partOptional.isEmpty()) {
            result.put("type", "warning");
            result.put("msg", "分P不存在");
            return result;
        }
        RecordHistoryPart part = partOptional.get();
        // 已投稿稿件不允许修改分P状态
        if (part.getHistoryId() != null) {
            if (historyRepository.findById(part.getHistoryId()).map(RecordHistory::isPublish).orElse(false)) {
                result.put("type", "warning");
                result.put("msg", "该稿件已投稿，不允许修改分P状态");
                return result;
            }
        }
        if (part.isRecording()) {
            part.setRecording(false);
        }
        if (part.getEndTime() == null) {
            part.setEndTime(LocalDateTime.now());
        }
        try {
            PartFileLocationService.FileResolution resolution = partFileLocationService.resolveReadable(part.getId());
            if (resolution.available() && part.getFileSize() <= 0) {
                part.setFileSize(Files.size(resolution.path()));
            }
        } catch (Exception ignored) {
        }
        part.setUpload(false);
        part.setUploadRetryCount(9999);
        part.setDeleteFailType("MANUAL_SKIP");
        if (isBlank(part.getDeleteFailReason())) {
            part.setDeleteFailReason("用户已标记该分P结束/跳过，允许稿件继续推进");
        }
        partRepository.save(part);

        log.info("[BLR] {}", LogKvs.event("PartRepair.MarkFinished")
                .add("partId", part.getId())
                .add("historyId", part.getHistoryId())
            .add("roomId", part.getRoomId())
            .addStageCostMs("total", totalStartNs));

        result.put("type", "success");
        result.put("msg", "已标记为结束，稿件可继续推进");
        return result;
    }

    @PostMapping("/bindFile/{id}")
    public Map<String, Object> bindFile(@PathVariable("id") Long id, @RequestBody Map<String, Object> body) {
        long totalStartNs = System.nanoTime();
        Map<String, Object> result = new LinkedHashMap<>();
        Optional<RecordHistoryPart> partOptional = partRepository.findById(id);
        if (partOptional.isEmpty()) {
            result.put("type", "warning");
            result.put("msg", "分P不存在");
            return result;
        }
        RecordHistoryPart part = partOptional.get();
        Optional<RecordHistory> historyOptional = part.getHistoryId() == null ? Optional.empty() : historyRepository.findById(part.getHistoryId());
        // 已投稿稿件不允许补全文件
        if (part.getHistoryId() != null) {
            if (historyOptional.isPresent()
                    && historyOptional.get().isPublish()
                    && !RecordBiliPublishService.hasOnlineIdentity(historyOptional.get())) {
                result.put("type", "warning");
                result.put("msg", "该稿件已投稿但缺少 avId/bvId，无法补全后编辑稿件");
                return result;
            }
        }
        String filePath = body == null ? null : String.valueOf(body.get("filePath"));
        if (filePath != null) {
            filePath = filePath.replace("\\", "/");
        }
        boolean triggerUpload = body != null && Boolean.TRUE.equals(body.get("triggerUpload"));
        if (isBlank(filePath)) {
            result.put("type", "warning");
            result.put("msg", "请选择文件");
            return result;
        }
        StorageRootService.RootMatch trusted = storageRootService.matchTrustedExisting(Path.of(filePath)).orElse(null);
        if (trusted == null) {
            result.put("type", "warning");
            result.put("msg", "文件不在已登记的工作或归档目录下，已拒绝");
            return result;
        }
        String realPath = trusted.resolvedPath().toString().replace("\\", "/");
        File file = new File(realPath);
        if (!file.isFile()) {
            result.put("type", "warning");
            result.put("msg", "文件不存在");
            return result;
        }

        part.setFilePath(realPath);
        part.setFileSize(file.length());
        if (part.isRecording()) {
            part.setRecording(false);
        }
        if (part.getEndTime() == null) {
            part.setEndTime(LocalDateTime.now());
        }
        part.setUpload(false);
        part.setUploadRetryCount(0);
        part.setDeleteFailType(null);
        part.setDeleteFailReason(null);
        part.setFileDelete(false);
        part = partRepository.save(part);
        partFileLocationService.registerPrimary(part);

        boolean triggered = false;
        if (triggerUpload && !shutdownState.isShuttingDown() && historyOptional.isPresent() && historyOptional.get().isUpload()) {
            RecordRoom room = roomRepository.findByRoomId(part.getRoomId());
            if (room != null) {
                try {
                    if (RecordBiliPublishService.hasOnlineIdentity(historyOptional.get())) {
                        publishService.asyncPublishRecordHistory(historyOptional.get());
                    } else {
                        uploadServiceFactory.getUploadService(room.getLine()).asyncUpload(part);
                    }
                    triggered = true;
                } catch (Exception e) {
                    log.error("[BLR] {}", LogKvs.event("PartRepair.BindFile.UploadTriggerFailed")
                            .add("partId", part.getId())
                            .add("historyId", part.getHistoryId())
                            .add("roomId", part.getRoomId())
                            .add("err", e.getMessage())
                            .add("ex", e.getClass().getSimpleName())
                            .addStageCostMs("total", totalStartNs), e);
                }
            }
        }

        log.info("[BLR] {}", LogKvs.event("PartRepair.BindFile.Done")
                .add("partId", part.getId())
                .add("historyId", part.getHistoryId())
                .add("roomId", part.getRoomId())
                .add("triggerUpload", triggered)
                .addStageCostMs("total", totalStartNs));

        result.put("type", "success");
        result.put("msg", triggered ? "已补全文件并触发上传" : "已补全文件");
        return result;
    }

    private Map<String, Object> loadArchiveProgressInfo(Long historyId, String bvId, BiliBiliUser reviewAuthUser, boolean forceRefresh) {
        long now = System.currentTimeMillis();
        ArchiveProgressCacheEntry cached = archiveProgressCache.get(historyId);
        if (!forceRefresh && isArchiveProgressCacheFresh(cached, now)) {
            return copyArchiveProgress(cached);
        }
        Object lock = archiveProgressLocks.computeIfAbsent(historyId, k -> new Object());
        synchronized (lock) {
            now = System.currentTimeMillis();
            cached = archiveProgressCache.get(historyId);
            if (!forceRefresh && isArchiveProgressCacheFresh(cached, now)) {
                return copyArchiveProgress(cached);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            Map<String, Object> debug = new LinkedHashMap<>();
            data.put("success", false);
            data.put("type", "warning");
            data.put("msg", "暂未获取到稿件进度");
            data.put("bvid", bvId);
            data.put("fetchedAtMs", now);
            data.put("cached", false);
            data.put("debug", debug);

            int successCount = 0;
            BiliApi.ApiDebugResponse<JSONObject> videosResp = putArchiveProgressApiResponse(data, debug, "videos",
                    () -> BiliApi.getArchiveProcessVideosDebug(reviewAuthUser, bvId, 1, 500, 1));
            successCount += countArchiveProgressSuccess(videosResp);

            List<Map<String, Object>> videoParts = extractArchiveProgressVideoParts(videosResp == null ? null : videosResp.getParsed());
            data.put("videoParts", videoParts);
            int xcodeSuccessCount = putArchiveProgressXcodeParts(data, debug, reviewAuthUser, bvId, videoParts);
            successCount += xcodeSuccessCount;

            if (videoParts.isEmpty()) {
                successCount += putArchiveProgressApi(data, debug, "xcode", () -> BiliApi.getArchiveProcessXcodeDebug(reviewAuthUser, bvId));
            }
            successCount += putArchiveProgressApi(data, debug, "archive", () -> BiliApi.getArchiveProcessArchiveDebug(reviewAuthUser, bvId));

            boolean success = successCount > 0;
            data.put("success", success);
            data.put("type", success ? "success" : "warning");
            data.put("msg", success ? "已获取稿件进度" : "B站暂未返回可用的稿件进度");
            data.put("apiSuccessCount", successCount);

            ArchiveProgressCacheEntry entry = new ArchiveProgressCacheEntry(now, success, data);
            archiveProgressCache.put(historyId, entry);
            Map<String, Object> result = copyArchiveProgress(entry);
            result.put("cached", false);
            return result;
        }
    }

    private int putArchiveProgressApi(Map<String, Object> data,
                                      Map<String, Object> debug,
                                      String key,
                                      ArchiveProgressApiLoader loader) {
        return countArchiveProgressSuccess(putArchiveProgressApiResponse(data, debug, key, loader));
    }

    private BiliApi.ApiDebugResponse<JSONObject> putArchiveProgressApiResponse(Map<String, Object> data,
                                                                               Map<String, Object> debug,
                                                                               String key,
                                                                               ArchiveProgressApiLoader loader) {
        try {
            BiliApi.ApiDebugResponse<JSONObject> apiResp = loader.load();
            JSONObject parsed = apiResp == null ? null : apiResp.getParsed();
            Integer code = parsed == null ? null : parsed.getInteger("code");
            String message = parsed == null ? null : parsed.getString("message");
            data.put(key, parsed);
            debug.put(key + "Code", code);
            debug.put(key + "Message", message);
            debug.put(key + "RequestUrl", apiResp == null ? null : apiResp.getRequestUrl());
            debug.put(key + "Raw", apiResp == null ? null : apiResp.getRaw());
            return apiResp;
        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            error.put("exception", e.getClass().getSimpleName());
            data.put(key, error);
            debug.put(key + "Error", e.getMessage());
            debug.put(key + "Exception", e.getClass().getSimpleName());
            return null;
        }
    }

    private int countArchiveProgressSuccess(BiliApi.ApiDebugResponse<JSONObject> apiResp) {
        JSONObject parsed = apiResp == null ? null : apiResp.getParsed();
        return parsed != null && Integer.valueOf(0).equals(parsed.getInteger("code")) ? 1 : 0;
    }

    private List<Map<String, Object>> extractArchiveProgressVideoParts(JSONObject videosResp) {
        List<Map<String, Object>> result = new ArrayList<>();
        JSONObject data = videosResp == null ? null : videosResp.getJSONObject("data");
        JSONArray videos = data == null ? null : data.getJSONArray("videos");
        if (videos == null || videos.isEmpty()) {
            return result;
        }
        for (int i = 0; i < videos.size() && result.size() < ARCHIVE_PROGRESS_XCODE_PART_LIMIT; i++) {
            JSONObject video = videos.getJSONObject(i);
            if (video == null) {
                continue;
            }
            String cid = video.getString("cid");
            if (isBlank(cid)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("cid", cid);
            item.put("index", video.getInteger("index"));
            item.put("title", video.getString("title"));
            item.put("xcodeState", video.getInteger("xcode_state"));
            result.add(item);
        }
        return result;
    }

    private int putArchiveProgressXcodeParts(Map<String, Object> data,
                                             Map<String, Object> debug,
                                             BiliBiliUser reviewAuthUser,
                                             String bvId,
                                             List<Map<String, Object>> videoParts) {
        List<Map<String, Object>> xcodeParts = new ArrayList<>();
        List<Map<String, Object>> xcodeDebug = new ArrayList<>();
        int successCount = 0;
        if (videoParts == null || videoParts.isEmpty()) {
            data.put("xcodeParts", xcodeParts);
            debug.put("xcodeParts", xcodeDebug);
            debug.put("xcodePartSuccessCount", successCount);
            return successCount;
        }

        for (Map<String, Object> videoPart : videoParts) {
            String cid = String.valueOf(videoPart.getOrDefault("cid", ""));
            Map<String, Object> xcodePart = new LinkedHashMap<>(videoPart);
            Map<String, Object> debugItem = new LinkedHashMap<>();
            debugItem.put("cid", cid);
            debugItem.put("index", videoPart.get("index"));
            try {
                BiliApi.ApiDebugResponse<JSONObject> apiResp = BiliApi.getArchiveProcessXcodeDebug(reviewAuthUser, bvId, cid);
                JSONObject parsed = apiResp == null ? null : apiResp.getParsed();
                Integer code = parsed == null ? null : parsed.getInteger("code");
                String message = parsed == null ? null : parsed.getString("message");
                xcodePart.put("xcode", parsed);
                debugItem.put("code", code);
                debugItem.put("message", message);
                debugItem.put("requestUrl", apiResp == null ? null : apiResp.getRequestUrl());
                debugItem.put("raw", apiResp == null ? null : apiResp.getRaw());
                if (!data.containsKey("xcode")) {
                    data.put("xcode", parsed);
                }
                if (Integer.valueOf(0).equals(code)) {
                    successCount++;
                }
            } catch (Exception e) {
                xcodePart.put("error", e.getMessage());
                xcodePart.put("exception", e.getClass().getSimpleName());
                debugItem.put("error", e.getMessage());
                debugItem.put("exception", e.getClass().getSimpleName());
            }
            xcodeParts.add(xcodePart);
            xcodeDebug.add(debugItem);
        }
        data.put("xcodeParts", xcodeParts);
        debug.put("xcodeParts", xcodeDebug);
        debug.put("xcodePartSuccessCount", successCount);
        return successCount;
    }

    private boolean isArchiveProgressCacheFresh(ArchiveProgressCacheEntry cached, long now) {
        if (cached == null) {
            return false;
        }
        long cooldown = cached.success ? ARCHIVE_PROGRESS_SUCCESS_COOLDOWN_MS : ARCHIVE_PROGRESS_FAIL_COOLDOWN_MS;
        return (now - cached.fetchAtMs) < cooldown;
    }

    private Map<String, Object> copyArchiveProgress(ArchiveProgressCacheEntry source) {
        Map<String, Object> data = new LinkedHashMap<>(source.data);
        data.put("cached", true);
        return data;
    }

    private ReviewAuthContext resolveReviewAuthContext(RecordHistory history, RecordRoom room) {
        Long publishUserId = history == null ? null : history.getPublishUserId();
        if (publishUserId != null) {
            Optional<BiliBiliUser> publishUserOpt = userRepository.findById(publishUserId);
            if (publishUserOpt.isPresent() && publishUserOpt.get().isLogin()) {
                return new ReviewAuthContext("history.publishUserId", publishUserOpt.get());
            }
            if (publishUserOpt.isEmpty()) {
                return new ReviewAuthContext("publishUser.notFound", null);
            }
            return new ReviewAuthContext("publishUser.notLogin", null);
        }
        if (room == null || room.getUploadUserId() == null) {
            return new ReviewAuthContext("room.uploadUserId.missing", null);
        }
        Optional<BiliBiliUser> roomUserOpt = userRepository.findById(room.getUploadUserId());
        if (roomUserOpt.isEmpty()) {
            return new ReviewAuthContext("roomUser.notFound", null);
        }
        if (!roomUserOpt.get().isLogin()) {
            return new ReviewAuthContext("roomUser.notLogin", null);
        }
        return new ReviewAuthContext("room.uploadUserId.fallback", roomUserOpt.get());
    }

    private ReviewInfoCacheEntry loadReviewInfo(Long historyId, String bvId, BiliBiliUser reviewAuthUser, boolean forceRefresh) {
        long now = System.currentTimeMillis();
        ReviewInfoCacheEntry cached = reviewInfoCache.get(historyId);
        if (!forceRefresh && isReviewCacheFresh(cached, now)) {
            return copyReviewInfo(cached);
        }
        Object lock = reviewInfoLocks.computeIfAbsent(historyId, k -> new Object());
        synchronized (lock) {
            now = System.currentTimeMillis();
            cached = reviewInfoCache.get(historyId);
            if (!forceRefresh && isReviewCacheFresh(cached, now)) {
                return copyReviewInfo(cached);
            }
            try {
                BiliApi.ApiDebugResponse<BiliVideoPartInfoResponse> partInfoDebug = BiliApi.getVideoPartInfoDebug(reviewAuthUser, bvId);
                BiliVideoPartInfoResponse partInfo = partInfoDebug.getParsed();
                Integer videoPartInfoCode = partInfo == null ? null : partInfo.getCode();
                String videoPartInfoMessage = partInfo == null ? "null-response" : partInfo.getMessage();
                String videoPartInfoRequestUrl = partInfoDebug.getRequestUrl();
                String videoPartInfoRaw = partInfoDebug.getRaw();
                Integer auditDetailCode = null;
                String auditDetailMessage = null;
                String auditDetailRequestUrl = null;
                String auditDetailRaw = null;
                if (partInfo != null && partInfo.getCode() == 0 && partInfo.getData() != null && partInfo.getData().getVideos() != null) {
                    Map<Integer, BiliVideoPartInfoResponse.Video> byPage = new HashMap<>();
                    Map<String, BiliVideoPartInfoResponse.Video> byTitle = new HashMap<>();
                    for (BiliVideoPartInfoResponse.Video video : partInfo.getData().getVideos()) {
                        if (video.getPage() > 0) {
                            byPage.put(video.getPage(), video);
                        }
                        if (!isBlank(video.getTitle())) {
                            byTitle.put(video.getTitle(), video);
                        }
                        if (!isBlank(video.getPart())) {
                            byTitle.put(video.getPart(), video);
                        }
                    }
                    List<BiliVideoAuditDetailResponse.ProblemDetail> problemDetails = new ArrayList<>();
                    try {
                        BiliApi.ApiDebugResponse<BiliVideoAuditDetailResponse> auditDetailDebug = BiliApi.getVideoAuditDetailDebug(reviewAuthUser, bvId);
                        BiliVideoAuditDetailResponse auditDetail = auditDetailDebug.getParsed();
                        auditDetailCode = auditDetail == null ? null : auditDetail.getCode();
                        auditDetailMessage = auditDetail == null ? "null-response" : auditDetail.getMessage();
                        auditDetailRequestUrl = auditDetailDebug.getRequestUrl();
                        auditDetailRaw = auditDetailDebug.getRaw();
                        if (auditDetail != null
                                && auditDetail.getCode() == 0
                                && auditDetail.getData() != null
                                && auditDetail.getData().getProblem_detail() != null) {
                            problemDetails.addAll(auditDetail.getData().getProblem_detail());
                        }
                        if (problemDetails.isEmpty()
                                && auditDetail != null
                                && auditDetail.getData() != null
                                && auditDetail.getData().getAppeal() != null
                                && !isBlank(auditDetail.getData().getAppeal().getReject())) {
                            BiliVideoAuditDetailResponse.ProblemDetail fallback = new BiliVideoAuditDetailResponse.ProblemDetail();
                            fallback.setType("appeal");
                            fallback.setReject_reason(auditDetail.getData().getAppeal().getReject());
                            problemDetails.add(fallback);
                        }
                        if (problemDetails.isEmpty()) {
                            boolean auditDetailLoadedWithoutProblems = auditDetail != null
                                    && auditDetail.getCode() == 0
                                    && auditDetail.getData() != null;
                            if (auditDetailLoadedWithoutProblems) {
                                log.info("[BLR] {}", LogKvs.event("Part.ReviewInfo.AuditDetail.Empty")
                                        .add("historyId", historyId)
                                        .add("forceRefresh", forceRefresh)
                                        .add("bvid", bvId)
                                        .add("userId", reviewAuthUser.getId())
                                        .add("respCode", auditDetail.getCode())
                                        .add("respMsg", auditDetail.getMessage())
                                        .add("state", auditDetail.getData().getState())
                                        .add("hasData", true));
                            } else {
                                log.warn("[BLR] {}", LogKvs.event("Part.ReviewInfo.AuditDetail.Unexpected")
                                        .add("historyId", historyId)
                                        .add("forceRefresh", forceRefresh)
                                        .add("bvid", bvId)
                                        .add("userId", reviewAuthUser.getId())
                                        .add("respCode", auditDetail == null ? null : auditDetail.getCode())
                                        .add("respMsg", auditDetail == null ? "null-response" : auditDetail.getMessage())
                                        .add("hasData", auditDetail != null && auditDetail.getData() != null));
                            }
                        }
                    } catch (Exception e) {
                        log.debug("[BLR] {}", LogKvs.event("Part.ReviewInfo.AuditDetail.LoadFailed")
                                .add("historyId", historyId)
                                .add("forceRefresh", forceRefresh)
                                .add("bvid", bvId)
                                .add("userId", reviewAuthUser.getId())
                                .add("err", e.getMessage())
                                .add("ex", e.getClass().getSimpleName()));
                        auditDetailMessage = e.getMessage();
                    }
                    ReviewInfoCacheEntry okEntry = new ReviewInfoCacheEntry(
                            now, true, byPage, byTitle, problemDetails,
                            videoPartInfoCode, videoPartInfoMessage, auditDetailCode, auditDetailMessage,
                            videoPartInfoRequestUrl, auditDetailRequestUrl, videoPartInfoRaw, auditDetailRaw
                    );
                    reviewInfoCache.put(historyId, okEntry);
                    return copyReviewInfo(okEntry);
                }
                log.warn("[BLR] {}", LogKvs.event("Part.ReviewInfo.Vupre.Unexpected")
                        .add("historyId", historyId)
                        .add("forceRefresh", forceRefresh)
                        .add("bvid", bvId)
                        .add("userId", reviewAuthUser.getId())
                        .add("respCode", partInfo == null ? null : partInfo.getCode())
                        .add("respMsg", partInfo == null ? "null-response" : partInfo.getMessage())
                        .add("hasData", partInfo != null && partInfo.getData() != null));
            } catch (Exception e) {
                log.debug("[BLR] {}", LogKvs.event("Part.ReviewInfo.LoadFailed")
                        .add("historyId", historyId)
                        .add("forceRefresh", forceRefresh)
                        .add("bvid", bvId)
                        .add("userId", reviewAuthUser == null ? null : reviewAuthUser.getId())
                        .add("err", e.getMessage())
                        .add("ex", e.getClass().getSimpleName()));
            }
            ReviewInfoCacheEntry fallback = cached == null
                    ? new ReviewInfoCacheEntry(now, false, new HashMap<>(), new HashMap<>(), new ArrayList<>(),
                    null, null, null, null, null, null, null, null)
                    : new ReviewInfoCacheEntry(now, false, cached.byPage, cached.byTitle, cached.problemDetails,
                    cached.videoPartInfoCode, cached.videoPartInfoMessage, cached.auditDetailCode, cached.auditDetailMessage,
                    cached.videoPartInfoRequestUrl, cached.auditDetailRequestUrl, cached.videoPartInfoRaw, cached.auditDetailRaw);
            reviewInfoCache.put(historyId, fallback);
            return copyReviewInfo(fallback);
        }
    }

    private boolean isReviewCacheFresh(ReviewInfoCacheEntry cached, long now) {
        if (cached == null) {
            return false;
        }
        long cooldown = cached.success ? REVIEW_INFO_SUCCESS_COOLDOWN_MS : REVIEW_INFO_FAIL_COOLDOWN_MS;
        return (now - cached.fetchAtMs) < cooldown;
    }

    private ReviewInfoCacheEntry copyReviewInfo(ReviewInfoCacheEntry source) {
        return new ReviewInfoCacheEntry(
                source.fetchAtMs,
                source.success,
                new HashMap<>(source.byPage),
                new HashMap<>(source.byTitle),
                new ArrayList<>(source.problemDetails),
                source.videoPartInfoCode,
                source.videoPartInfoMessage,
                source.auditDetailCode,
                source.auditDetailMessage,
                source.videoPartInfoRequestUrl,
                source.auditDetailRequestUrl,
                source.videoPartInfoRaw,
                source.auditDetailRaw
        );
    }

    private interface ArchiveProgressApiLoader {
        BiliApi.ApiDebugResponse<JSONObject> load();
    }

    private static class ArchiveProgressCacheEntry {
        private final long fetchAtMs;
        private final boolean success;
        private final Map<String, Object> data;

        private ArchiveProgressCacheEntry(long fetchAtMs, boolean success, Map<String, Object> data) {
            this.fetchAtMs = fetchAtMs;
            this.success = success;
            this.data = data;
        }
    }

    private static class ReviewAuthContext {
        private final String source;
        private final BiliBiliUser user;

        private ReviewAuthContext(String source, BiliBiliUser user) {
            this.source = source;
            this.user = user;
        }
    }

    private static class ReviewInfoCacheEntry {
        private final long fetchAtMs;
        private final boolean success;
        private final Map<Integer, BiliVideoPartInfoResponse.Video> byPage;
        private final Map<String, BiliVideoPartInfoResponse.Video> byTitle;
        private final List<BiliVideoAuditDetailResponse.ProblemDetail> problemDetails;
        private final Integer videoPartInfoCode;
        private final String videoPartInfoMessage;
        private final Integer auditDetailCode;
        private final String auditDetailMessage;
        private final String videoPartInfoRequestUrl;
        private final String auditDetailRequestUrl;
        private final String videoPartInfoRaw;
        private final String auditDetailRaw;

        private ReviewInfoCacheEntry(long fetchAtMs,
                                     boolean success,
                                     Map<Integer, BiliVideoPartInfoResponse.Video> byPage,
                                     Map<String, BiliVideoPartInfoResponse.Video> byTitle,
                                     List<BiliVideoAuditDetailResponse.ProblemDetail> problemDetails,
                                     Integer videoPartInfoCode,
                                     String videoPartInfoMessage,
                                     Integer auditDetailCode,
                                     String auditDetailMessage,
                                     String videoPartInfoRequestUrl,
                                     String auditDetailRequestUrl,
                                     String videoPartInfoRaw,
                                     String auditDetailRaw) {
            this.fetchAtMs = fetchAtMs;
            this.success = success;
            this.byPage = byPage;
            this.byTitle = byTitle;
            this.problemDetails = problemDetails;
            this.videoPartInfoCode = videoPartInfoCode;
            this.videoPartInfoMessage = videoPartInfoMessage;
            this.auditDetailCode = auditDetailCode;
            this.auditDetailMessage = auditDetailMessage;
            this.videoPartInfoRequestUrl = videoPartInfoRequestUrl;
            this.auditDetailRequestUrl = auditDetailRequestUrl;
            this.videoPartInfoRaw = videoPartInfoRaw;
            this.auditDetailRaw = auditDetailRaw;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static boolean parseBooleanFlag(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return "true".equalsIgnoreCase(((String) value).trim());
        }
        return false;
    }



    @GetMapping("/uploadEditor/{id}")
    public Map<String, String> delete(@PathVariable("id") Long id) {
        Map<String, String> result = new HashMap<>();
        if (id == null) {
            result.put("type", "info");
            result.put("msg", "请输入id");
            return result;
        }
        Optional<RecordHistoryPart> partOptional = partRepository.findById(id);
        if (partOptional.isPresent()) {
            RecordHistoryPart part = partOptional.get();
            PartFileLocationService.FileResolution resolution = partFileLocationService.resolveReadable(part.getId());
            File file = resolution.available() ? resolution.path().toFile() : null;
            if(file != null && file.exists()){
                RecordRoom room = roomRepository.findByRoomId(part.getRoomId());
                if(room.getUploadUserId() == null){
                    result.put("type", "warning");
                    result.put("msg", "没有配置上传用户");
                    return result;
                }
                Optional<BiliBiliUser> userOptional = userRepository.findById(room.getUploadUserId());
                if(userOptional.isEmpty()){
                    result.put("type", "warning");
                    result.put("msg", "没有配置上传用户");
                    return result;
                }
                BiliBiliUser user = userOptional.get();
                WebCookie cookie = Cookie.parse(user.getCookies());
                EdtiorSpaceRequest edtiorSpaceRequest = new EdtiorSpaceRequest(cookie);
                try {
                    EditorSpaceBean spaceBean = edtiorSpaceRequest.getPojo();
                    EditorSpaceBean.Data data = spaceBean.getData();
                    long freeSize = data.getTotal() - data.getUsed();
                    if(freeSize<file.length()){
                        result.put("type", "warning");
                        result.put("msg", "云剪辑剩余空间不足，剩余"+freeSize/1024/1024+"Mb"+",文件大小为"+file.length()/1024/1024+"Mb");
                        return result;
                    }
                    editPartUploadService.asyncUpload(part);
                    result.put("type", "success");
                    result.put("msg", "云剪辑上传开始，剩余空间"+freeSize/1024/1024+"Mb"+",文件大小为"+file.length()/1024/1024+"Mb");
                    return result;

                } catch (HttpException e) {
                    result.put("type", "warning");
                    result.put("msg", "查询云剪辑剩余空间发生错误");
                    return result;
                }


            }else {
                result.put("type", "warning");
                result.put("msg", resolution.state() == PartFileLocationService.LocalFileState.ROOT_OFFLINE
                        ? "存储目录离线" : "分p文件不存在");
                return result;
            }
        } else {
            result.put("type", "warning");
            result.put("msg", "分p不存在");
            return result;
        }
    }
}
