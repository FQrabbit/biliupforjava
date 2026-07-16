package top.sshh.bililiverecoder.job;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.entity.data.BiliVideoInfoResponse;
import top.sshh.bililiverecoder.entity.data.BiliVideoPartInfoResponse;
import top.sshh.bililiverecoder.notification.NotificationEvent;
import top.sshh.bililiverecoder.notification.NotificationEventPublisher;
import top.sshh.bililiverecoder.notification.NotificationEventType;
import top.sshh.bililiverecoder.repo.*;
import top.sshh.bililiverecoder.service.PartFileCleanupPolicy;
import top.sshh.bililiverecoder.service.StatsAggregationService;
import top.sshh.bililiverecoder.service.impl.LiveMsgService;
import top.sshh.bililiverecoder.util.BiliApi;
import top.sshh.bililiverecoder.util.LogKvs;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Component
public class videoSyncJob {

    @Value("${record.work-path}")
    private String workPath;

    @Autowired
    private RecordRoomRepository roomRepository;

    @Autowired
    private RecordHistoryRepository historyRepository;

    @Autowired
    private RecordHistoryPartRepository partRepository;

    @Autowired
    private BiliUserRepository userRepository;

    @Autowired
    private LiveMsgService liveMsgService;
    @Autowired
    private LiveMsgRepository msgRepository;


    // 定时查询录制历史，每五分钟验证一下是否发布成功
    @Autowired
    private LiveMsgSendSync liveMsgSendSync;

    @Autowired
    private PartFileCleanupPolicy partFileCleanupPolicy;
    @Autowired
    private NotificationEventPublisher notificationEventPublisher;
    @Autowired
    private StatsAggregationService statsAggregationService;
    @Autowired
    private RoomLiveSessionStatsRepository sessionStatsRepository;

    @Scheduled(fixedDelay = 300000, initialDelay = 5000)
    public void syncVideo() {
        //查询出所有需要同步的录播记录
        long roundStartNs = System.nanoTime();
        int syncCount = 0;
        List<RecordHistory> syncList = historyRepository.findSyncList();
        try {
        for (RecordHistory next : syncList) {
            try {
                // 避免请求过快，每次请求间隔3秒
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                log.warn("[BLR] {}", LogKvs.event("VideoSync.SleepInterrupted")
                        .add("waitMs", 3000), e);
            }
            syncOne(next);
            syncCount++;
        }
        } finally {
            log.info("[BLR] {}", LogKvs.event("VideoSync.Round.Done")
                    .addRoundCount("candidate", syncList.size())
                    .addRoundCount("synced", syncCount)
                    .addStageCostMs("total", roundStartNs));
        }

    }

    public void syncOne(RecordHistory next) {
        syncOneInternal(next, true);
    }

    /**
     * 仅同步稿件状态/基础信息，不触发弹幕重解析与文件处理。
     * 用于前端“刷新状态”按钮，避免误删已有弹幕数据。
     */
    public SyncStatusResult syncStatusOnly(RecordHistory next) {
        return syncStatusOnlyForced(next);
    }

    private SyncStatusResult syncStatusOnlyForced(RecordHistory history) {
        SyncStatusResult result = new SyncStatusResult(history == null ? null : history.getId());
        if (history == null) {
            result.success = false;
            result.type = "warning";
            result.msg = "稿件不存在";
            return result;
        }
        RecordRoom room = roomRepository.findByRoomId(history.getRoomId());
        if (room == null) {
            result.success = false;
            result.type = "warning";
            result.msg = "房间不存在";
            return result;
        }
        PublishAuthContext auth = resolvePublishAuthContext(history, room);
        BiliBiliUser user = auth.user();
        result.authSource = auth.source();
        if (StringUtils.isBlank(history.getBvId())) {
            result.success = false;
            result.type = "warning";
            result.msg = "稿件缺少 BV 号，无法刷新线上状态";
            return result;
        }

        BiliVideoInfoResponse videoInfoResponse = null;
        try {
            videoInfoResponse = BiliApi.getVideoInfo(user, history.getBvId());
        } catch (Exception e) {
            result.success = false;
            result.type = "error";
            result.msg = "刷新稿件状态失败: " + e.getMessage();
            log.warn("[BLR] {}", LogKvs.event("VideoSync.ManualRefresh.VideoInfoFailed")
                    .add("historyId", history.getId())
                    .addIfNotBlank("bvid", history.getBvId())
                    .addIfNotBlank("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()), e);
            return result;
        }

        result.videoInfoCode = videoInfoResponse == null ? null : videoInfoResponse.getCode();
        result.videoInfoMessage = videoInfoResponse == null ? "null-response" : videoInfoResponse.getMessage();
        LockedArchiveSnapshot lockedSnapshot = detectLockedArchive(user, history, result);
        boolean keepPendingReview = false;
        boolean publicViewMissingButMemberVisible = videoInfoResponse != null
                && videoInfoResponse.getCode() == -404
                && result.memberPartInfoCode != null
                && result.memberPartInfoCode == 0;
        if (videoInfoResponse != null && videoInfoResponse.getCode() == 0 && videoInfoResponse.getData() != null) {
            BiliVideoInfoResponse.BiliVideoInfo data = videoInfoResponse.getData();
            result.oldArchiveCode = history.getCode();
            int state = data.getState();
            if (lockedSnapshot.locked()) {
                state = -4;
                result.locked = true;
                result.lockReason = lockedSnapshot.reason();
                result.forceArchived = true;
            }
            if (shouldKeepPendingReviewAfterRecentEdit(history, state)) {
                keepPendingReview = true;
                result.archiveCode = history.getCode();
                log.info("[BLR] {}", LogKvs.event("VideoSync.ManualRefresh.KeepPendingAfterRecentEdit")
                        .add("historyId", history.getId())
                        .addIfNotBlank("bvid", history.getBvId())
                        .add("apiState", data.getState())
                        .add("localCode", history.getCode()));
            } else {
                result.archiveCode = state;
                history.setCode(state);
                history.setAvId(data.getAid());
                history.setBvId(data.getBvid());
                history.setCoverUrl(data.getPic());
                if (state == -4) {
                    markHistoryLockedAndArchived(history);
                }
                history.setUpdateTime(LocalDateTime.now());
                history = historyRepository.save(history);
                result.statusSynced = true;
            }
        } else if (videoInfoResponse != null && videoInfoResponse.getCode() == 62002) {
            result.oldArchiveCode = history.getCode();
            result.archiveCode = videoInfoResponse.getCode();
            history.setCode(videoInfoResponse.getCode());
            history.setUpdateTime(LocalDateTime.now());
            historyRepository.save(history);
            result.statusSynced = true;
        } else if (lockedSnapshot.locked()
                || publicViewMissingButMemberVisible
                && (Integer.valueOf(-4).equals(result.auditDetailState) || StringUtils.isNotBlank(result.auditReason))) {
            result.oldArchiveCode = history.getCode();
            result.archiveCode = -4;
            result.locked = true;
            result.lockReason = StringUtils.defaultIfBlank(lockedSnapshot.reason(), result.auditReason);
            result.forceArchived = true;
            history.setCode(-4);
            markHistoryLockedAndArchived(history);
            history.setUpdateTime(LocalDateTime.now());
            historyRepository.save(history);
            result.statusSynced = true;
        }

        List<OnlinePartSnapshot> onlineParts = loadOnlinePartSnapshotsForManualRefresh(user, history, videoInfoResponse, result);
        SyncStatusResult orderResult = syncOnlinePartOrder(history, onlineParts);
        result.mergePartOrder(orderResult);
        result.success = keepPendingReview || result.statusSynced || result.partOrderSynced;
        if (keepPendingReview) {
            result.type = result.partOrderAnomaly ? "warning" : "info";
            result.msg = result.partOrderAnomaly
                    ? "稿件仍处于二次提交审核中，已忽略平台短时间返回的旧退回状态；线上分P顺序存在异常"
                    : result.partOrderChanged
                    ? "稿件仍处于二次提交审核中，已忽略平台短时间返回的旧退回状态；线上分P顺序已同步"
                    : "稿件仍处于二次提交审核中，已忽略平台短时间返回的旧退回状态";
        } else if (result.locked) {
            result.type = "warning";
            result.msg = StringUtils.isBlank(result.lockReason)
                    ? "稿件已被平台锁定，已按强制归档处理，后续任务不会再触发"
                    : "稿件已被平台锁定，已按强制归档处理：" + result.lockReason;
        } else if (result.partOrderAnomaly) {
            result.type = "warning";
            result.msg = "状态已刷新，但部分线上分P无法与本地记录完全匹配，分P顺序可能存在异常";
        } else if (result.partOrderChanged) {
            result.type = "success";
            result.msg = "状态已刷新，分P顺序已同步";
        } else if (result.statusSynced) {
            result.type = "success";
            result.msg = "状态已刷新";
        } else {
            result.type = "warning";
            result.msg = "未获取到可更新的稿件状态";
        }
        if (result.statusSynced) {
            publishArchiveStatusNotificationIfChanged(
                    history,
                    room,
                    user,
                    result.oldArchiveCode == null ? history.getCode() : result.oldArchiveCode,
                    result.archiveCode == null ? history.getCode() : result.archiveCode,
                    StringUtils.defaultIfBlank(result.lockReason, result.auditReason),
                    true
            );
        }
        return result;
    }

    private LockedArchiveSnapshot detectLockedArchive(BiliBiliUser user, RecordHistory history) {
        return detectLockedArchive(user, history, null);
    }

    private LockedArchiveSnapshot detectLockedArchive(BiliBiliUser user, RecordHistory history, SyncStatusResult result) {
        if (user == null || history == null || StringUtils.isBlank(history.getBvId())) {
            return LockedArchiveSnapshot.unlocked();
        }
        try {
            BiliApi.ApiDebugResponse<BiliVideoPartInfoResponse> partInfoDebug = BiliApi.getVideoPartInfoDebug(user, history.getBvId());
            BiliVideoPartInfoResponse partInfo = partInfoDebug.getParsed();
            if (result != null) {
                result.memberPartInfoCode = partInfo == null ? null : partInfo.getCode();
                result.memberPartInfoMessage = partInfo == null ? "null-response" : partInfo.getMessage();
                result.memberPartInfoLockSignal = findLockedSignal(partInfoDebug.getRaw());
            }
            String auditReason = loadAuditReason(user, history, result);
            String memberLockSignal = result == null ? findLockedSignal(partInfoDebug.getRaw()) : result.memberPartInfoLockSignal;
            String auditLockSignal = result == null ? null : result.auditDetailLockSignal;
            if (Integer.valueOf(-4).equals(result == null ? null : result.auditDetailState) || containsLockedText(auditReason)) {
                return LockedArchiveSnapshot.locked(auditReason);
            }
            if (containsLockedText(auditLockSignal)) {
                return LockedArchiveSnapshot.locked(StringUtils.defaultIfBlank(auditReason, auditLockSignal));
            }
            if (containsLockedText(memberLockSignal)) {
                return LockedArchiveSnapshot.locked(StringUtils.defaultIfBlank(auditReason, memberLockSignal));
            }
            if (partInfo == null || partInfo.getCode() != 0 || partInfo.getData() == null) {
                return LockedArchiveSnapshot.unlocked();
            }
            if (partInfo.getData().getState() == -4) {
                return LockedArchiveSnapshot.locked(StringUtils.defaultIfBlank(auditReason, firstPartFailDesc(partInfo)));
            }
            if (partInfo.getData().getVideos() != null) {
                for (BiliVideoPartInfoResponse.Video video : partInfo.getData().getVideos()) {
                    if (video == null) {
                        continue;
                    }
                    if (video.getFailCode() == -4 || containsLockedText(video.getFailDesc())) {
                        return LockedArchiveSnapshot.locked(StringUtils.defaultIfBlank(auditReason, video.getFailDesc()));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[BLR] {}", LogKvs.event("VideoSync.LockedDetect.Failed")
                    .add("historyId", history.getId())
                    .addIfNotBlank("bvid", history.getBvId())
                    .addIfNotBlank("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()));
        }
        return LockedArchiveSnapshot.unlocked();
    }

    private String loadAuditReason(BiliBiliUser user, RecordHistory history) {
        return loadAuditReason(user, history, null);
    }

    private String loadAuditReason(BiliBiliUser user, RecordHistory history, SyncStatusResult result) {
        if (user == null || history == null || StringUtils.isBlank(history.getBvId())) {
            return null;
        }
        try {
            BiliApi.ApiDebugResponse<top.sshh.bililiverecoder.entity.data.BiliVideoAuditDetailResponse> auditDebug =
                    BiliApi.getVideoAuditDetailDebug(user, history.getBvId());
            var audit = auditDebug.getParsed();
            if (result != null) {
                result.auditDetailCode = audit == null ? null : audit.getCode();
                result.auditDetailMessage = audit == null ? "null-response" : audit.getMessage();
                result.auditDetailState = audit == null || audit.getData() == null ? null : audit.getData().getState();
                result.auditDetailLockSignal = findLockedSignal(auditDebug.getRaw());
            }
            if (audit == null || audit.getCode() != 0 || audit.getData() == null) {
                return null;
            }
            if (audit.getData().getProblem_detail() != null) {
                for (var detail : audit.getData().getProblem_detail()) {
                    String text = joinNonBlank(
                            detail == null ? null : detail.getReject_reason(),
                            detail == null ? null : detail.getModify_advise(),
                            detail == null ? null : detail.getProblem_description()
                    );
                    if (StringUtils.isNotBlank(text)) {
                        if (result != null) {
                            result.auditReason = text;
                        }
                        return text;
                    }
                }
            }
            if (audit.getData().getAppeal() != null) {
                String text = audit.getData().getAppeal().getReject();
                if (result != null) {
                    result.auditReason = text;
                }
                return text;
            }
        } catch (Exception e) {
            log.debug("[BLR] {}", LogKvs.event("VideoSync.LockedAuditReason.Failed")
                    .add("historyId", history.getId())
                    .addIfNotBlank("bvid", history.getBvId())
                    .addIfNotBlank("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()));
        }
        return null;
    }

    private String joinNonBlank(String... values) {
        List<String> parts = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                if (StringUtils.isNotBlank(value)) {
                    parts.add(value.trim());
                }
            }
        }
        return String.join("；", parts);
    }

    private String firstPartFailDesc(BiliVideoPartInfoResponse partInfo) {
        if (partInfo == null || partInfo.getData() == null || partInfo.getData().getVideos() == null) {
            return null;
        }
        for (BiliVideoPartInfoResponse.Video video : partInfo.getData().getVideos()) {
            if (video != null && StringUtils.isNotBlank(video.getFailDesc())) {
                return video.getFailDesc();
            }
        }
        return null;
    }

    private String findLockedSignal(String raw) {
        if (!containsLockedText(raw)) {
            return null;
        }
        try {
            Object parsed = JSONObject.parse(raw);
            String signal = findLockedSignal(parsed);
            return StringUtils.abbreviate(StringUtils.defaultIfBlank(signal, "locked"), 240);
        } catch (Exception e) {
            return "locked";
        }
    }

    private String findLockedSignal(Object node) {
        if (node == null) {
            return null;
        }
        if (node instanceof JSONObject json) {
            for (Map.Entry<String, Object> entry : json.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (containsLockedText(key) || containsLockedText(String.valueOf(value))) {
                    String valueText = summarizeJsonValue(value);
                    return StringUtils.isBlank(valueText) || valueText.length() > 180 ? key : key + "=" + valueText;
                }
                String nested = findLockedSignal(value);
                if (StringUtils.isNotBlank(nested)) {
                    return nested;
                }
            }
            return null;
        }
        if (node instanceof JSONArray array) {
            for (Object item : array) {
                String nested = findLockedSignal(item);
                if (StringUtils.isNotBlank(nested)) {
                    return nested;
                }
            }
            return null;
        }
        String text = String.valueOf(node);
        return containsLockedText(text) ? text : null;
    }

    private String summarizeJsonValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof JSONObject || value instanceof JSONArray) {
            return null;
        }
        return StringUtils.abbreviate(String.valueOf(value), 180);
    }

    private boolean containsLockedText(String text) {
        return StringUtils.contains(text, "锁定") || StringUtils.containsIgnoreCase(text, "lock");
    }

    private void markHistoryLockedAndArchived(RecordHistory history) {
        if (history == null) {
            return;
        }
        history.setForceArchived(true);
        history.setUpload(false);
        history.setSendReply(true);
        history.setStreaming(false);
        history.setRecording(false);
        if (StringUtils.isNotBlank(history.getBvId())) {
            msgRepository.markPendingByBvidAndPool(history.getBvId(), 0, -4);
            msgRepository.markPendingByBvidAndPool(history.getBvId(), 1, -4);
        }
        List<RecordHistoryPart> parts = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());
        for (RecordHistoryPart part : parts) {
            if (part == null) {
                continue;
            }
            LiveMsgSendSync.skipOrdinaryPartIds.add(part.getId());
            LiveMsgSendSync.skipAdvancedPartIds.add(part.getId());
            if (part.isRecording()) {
                part.setRecording(false);
                partRepository.save(part);
            }
        }
        RecordRoom room = roomRepository.findByRoomId(history.getRoomId());
        if (room != null && history.getId() != null && history.getId().equals(room.getHistoryId())) {
            room.setHistoryId(null);
            roomRepository.save(room);
        }
    }

    private List<OnlinePartSnapshot> loadOnlinePartSnapshotsForManualRefresh(BiliBiliUser user,
                                                                             RecordHistory history,
                                                                             BiliVideoInfoResponse videoInfoResponse,
                                                                             SyncStatusResult result) {
        List<OnlinePartSnapshot> onlineParts = new ArrayList<>();
        if (user != null && StringUtils.isNotBlank(history.getBvId())) {
            try {
                BiliVideoPartInfoResponse partInfo = BiliApi.getVideoPartInfo(user, history.getBvId());
                result.memberPartInfoCode = partInfo == null ? null : partInfo.getCode();
                result.memberPartInfoMessage = partInfo == null ? "null-response" : partInfo.getMessage();
                if (partInfo != null && partInfo.getCode() == 0 && partInfo.getData() != null && partInfo.getData().getVideos() != null) {
                    int fallbackPage = 1;
                    for (BiliVideoPartInfoResponse.Video video : partInfo.getData().getVideos()) {
                        if (video == null) {
                            continue;
                        }
                        int page = video.getPage() > 0 ? video.getPage() : fallbackPage;
                        onlineParts.add(new OnlinePartSnapshot(page,
                                StringUtils.defaultIfBlank(video.getTitle(), video.getPart()),
                                video.getFilename(),
                                video.getCid(),
                                video.getDuration()));
                        fallbackPage++;
                    }
                }
            } catch (Exception e) {
                result.memberPartInfoMessage = e.getMessage();
                log.debug("[BLR] {}", LogKvs.event("VideoSync.ManualRefresh.PartInfoFailed")
                        .add("historyId", history.getId())
                        .addIfNotBlank("bvid", history.getBvId())
                        .addIfNotBlank("err", e.getMessage())
                        .add("ex", e.getClass().getSimpleName()));
            }
        }
        if (!onlineParts.isEmpty()) {
            onlineParts.sort((a, b) -> Integer.compare(a.page, b.page));
            return onlineParts;
        }
        if (videoInfoResponse != null && videoInfoResponse.getCode() == 0
                && videoInfoResponse.getData() != null && videoInfoResponse.getData().getPages() != null) {
            int fallbackPage = 1;
            for (BiliVideoInfoResponse.BiliVideoInfoPart page : videoInfoResponse.getData().getPages()) {
                if (page == null) {
                    continue;
                }
                int pageNo = page.getPage() > 0 ? page.getPage() : fallbackPage;
                onlineParts.add(new OnlinePartSnapshot(pageNo, page.getPart(), null, page.getCid(), page.getDuration()));
                fallbackPage++;
            }
            onlineParts.sort((a, b) -> Integer.compare(a.page, b.page));
        }
        return onlineParts;
    }

    private SyncStatusResult syncOnlinePartOrder(RecordHistory history, List<OnlinePartSnapshot> onlineParts) {
        SyncStatusResult result = new SyncStatusResult(history == null ? null : history.getId());
        if (history == null || onlineParts == null || onlineParts.isEmpty()) {
            result.partOrderSynced = false;
            return result;
        }
        List<RecordHistoryPart> localParts = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());
        result.onlinePartCount = onlineParts.size();
        if (localParts == null || localParts.isEmpty()) {
            result.partOrderAnomaly = true;
            result.unmatchedOnlineCount = onlineParts.size();
            for (OnlinePartSnapshot online : onlineParts) {
                result.unmatchedOnlineParts.add(online.label());
            }
            return result;
        }

        List<RecordHistoryPart> matchableLocalParts = new ArrayList<>();
        for (RecordHistoryPart part : localParts) {
            if (part != null && !isSkippedPart(part)) {
                matchableLocalParts.add(part);
            }
        }
        if (matchableLocalParts.isEmpty()) {
            result.partOrderAnomaly = true;
            result.unmatchedOnlineCount = onlineParts.size();
            for (OnlinePartSnapshot online : onlineParts) {
                result.unmatchedOnlineParts.add(online.label());
            }
            moveSkippedPartsAfterOnlineOrder(localParts, onlineParts.size(), result);
            return result;
        }

        Map<String, List<RecordHistoryPart>> byCid = new HashMap<>();
        Map<String, List<RecordHistoryPart>> byFileName = new HashMap<>();
        Map<String, List<RecordHistoryPart>> byTitle = new HashMap<>();
        for (RecordHistoryPart part : matchableLocalParts) {
            if (part.getCid() != null && part.getCid() > 0) {
                addLocalPart(byCid, String.valueOf(part.getCid()), part);
            }
            if (StringUtils.isNotBlank(part.getFileName())) {
                addLocalPart(byFileName, normalizeKey(part.getFileName()), part);
            }
            if (StringUtils.isNotBlank(part.getTitle())) {
                addLocalPart(byTitle, normalizeKey(part.getTitle()), part);
            }
        }

        Set<Long> usedPartIds = new HashSet<>();
        for (OnlinePartSnapshot online : onlineParts) {
            RecordHistoryPart part = matchLocalPart(online, byCid, byFileName, byTitle, usedPartIds);
            if (part == null && onlineParts.size() == 1 && matchableLocalParts.size() == 1) {
                part = matchableLocalParts.get(0);
            }
            if (part == null) {
                result.partOrderAnomaly = true;
                result.unmatchedOnlineCount++;
                result.unmatchedOnlineParts.add(online.label());
                continue;
            }
            usedPartIds.add(part.getId());
            result.matchedPartCount++;
            if (isOnlineSourceMismatch(part, online)) {
                result.partOrderAnomaly = true;
                result.sourceMismatchCount++;
                result.sourceMismatchParts.add(online.label());
            }
            boolean changed = !Integer.valueOf(online.page).equals(part.getPartOrder()) || part.getPage() != online.page;
            part.setPage(online.page);
            part.setPartOrder(online.page);
            if (StringUtils.isNotBlank(online.title)) {
                part.setTitle(online.title);
            }
            if (StringUtils.isNotBlank(online.fileName)) {
                part.setFileName(online.fileName);
            }
            if (online.cid > 0) {
                part.setCid(online.cid);
            }
            if (online.duration > 0) {
                part.setDuration(online.duration);
            }
            if (part.getCid() != null && part.getCid() > 0) {
                part.setUpload(true);
                if (part.getUploadRetryCount() >= 9999) {
                    part.setUploadRetryCount(0);
                }
                part.setDeleteFailType(null);
                part.setDeleteFailReason(null);
            }
            partRepository.save(part);
            if (changed) {
                result.partOrderChanged = true;
            }
        }

        for (RecordHistoryPart part : localParts) {
            if (part == null || part.getId() == null || usedPartIds.contains(part.getId()) || isSkippedPart(part)) {
                continue;
            }
            if (part.isUpload() || part.getCid() != null && part.getCid() > 0 || StringUtils.isNotBlank(part.getFileName())) {
                result.unmatchedLocalCount++;
            }
        }
        result.partOrderSynced = result.matchedPartCount > 0 || result.onlinePartCount == 0;
        if (result.unmatchedLocalCount > 0 && result.onlinePartCount > 0) {
            result.partOrderAnomaly = true;
        }
        moveSkippedPartsAfterOnlineOrder(localParts, onlineParts.size(), result);
        return result;
    }

    private void moveSkippedPartsAfterOnlineOrder(List<RecordHistoryPart> localParts, int onlinePartCount, SyncStatusResult result) {
        if (localParts == null || localParts.isEmpty()) {
            return;
        }
        int nextOrder = Math.max(onlinePartCount, 0) + 1;
        for (RecordHistoryPart part : localParts) {
            if (!isSkippedPart(part)) {
                continue;
            }
            boolean changed = part.getPartOrder() == null || part.getPartOrder() != nextOrder || part.getPage() != 0;
            part.setPartOrder(nextOrder);
            part.setPage(0);
            partRepository.save(part);
            if (changed && result != null) {
                result.partOrderChanged = true;
            }
            nextOrder++;
        }
    }

    private RecordHistoryPart matchLocalPart(OnlinePartSnapshot online,
                                             Map<String, List<RecordHistoryPart>> byCid,
                                             Map<String, List<RecordHistoryPart>> byFileName,
                                             Map<String, List<RecordHistoryPart>> byTitle,
                                             Set<Long> usedPartIds) {
        if (online.cid > 0) {
            RecordHistoryPart part = uniqueUnused(byCid.get(String.valueOf(online.cid)), usedPartIds);
            if (part != null) {
                return part;
            }
        }
        if (StringUtils.isNotBlank(online.fileName)) {
            RecordHistoryPart part = uniqueUnused(byFileName.get(normalizeKey(online.fileName)), usedPartIds);
            if (part != null) {
                return part;
            }
        }
        if (StringUtils.isNotBlank(online.title)) {
            return uniqueUnused(byTitle.get(normalizeKey(online.title)), usedPartIds);
        }
        return null;
    }

    private boolean isOnlineSourceMismatch(RecordHistoryPart part, OnlinePartSnapshot online) {
        if (part == null || online == null) {
            return false;
        }
        if (part.getCid() != null && part.getCid() > 0 && online.cid > 0 && !part.getCid().equals(online.cid)) {
            return true;
        }
        return StringUtils.isNotBlank(part.getFileName())
                && StringUtils.isNotBlank(online.fileName)
                && !normalizeKey(part.getFileName()).equals(normalizeKey(online.fileName));
    }

    private RecordHistoryPart uniqueUnused(List<RecordHistoryPart> parts, Set<Long> usedPartIds) {
        if (parts == null || parts.isEmpty()) {
            return null;
        }
        RecordHistoryPart match = null;
        for (RecordHistoryPart part : parts) {
            if (part == null || part.getId() == null || usedPartIds.contains(part.getId())) {
                continue;
            }
            if (match != null) {
                return null;
            }
            match = part;
        }
        return match;
    }

    private void addLocalPart(Map<String, List<RecordHistoryPart>> map, String key, RecordHistoryPart part) {
        if (StringUtils.isBlank(key)) {
            return;
        }
        map.computeIfAbsent(key, k -> new ArrayList<>()).add(part);
    }

    private String normalizeKey(String value) {
        return StringUtils.defaultString(value).trim().toLowerCase(Locale.ROOT);
    }

    private PublishAuthContext resolvePublishAuthContext(RecordHistory history, RecordRoom room) {
        Long publishUserId = history == null ? null : history.getPublishUserId();
        if (publishUserId != null) {
            Optional<BiliBiliUser> publishUser = userRepository.findById(publishUserId);
            if (publishUser.isPresent() && publishUser.get().isLogin()) {
                return new PublishAuthContext("history.publishUserId", publishUser.get());
            }
            return new PublishAuthContext(publishUser.isPresent() ? "publishUser.notLogin" : "publishUser.notFound", null);
        }
        if (room == null || room.getUploadUserId() == null) {
            return new PublishAuthContext("room.uploadUserId.missing", null);
        }
        Optional<BiliBiliUser> roomUser = userRepository.findById(room.getUploadUserId());
        if (roomUser.isPresent() && roomUser.get().isLogin()) {
            return new PublishAuthContext("room.uploadUserId.fallback", roomUser.get());
        }
        return new PublishAuthContext(roomUser.isPresent() ? "roomUser.notLogin" : "roomUser.notFound", null);
    }

    private void syncOneInternal(RecordHistory next, boolean doPostPublishProcessing) {
        RecordRoom room = roomRepository.findByRoomId(next.getRoomId());
        if (room == null) {
            log.error("[BLR] {}", LogKvs.event("VideoSync.RoomMissing")
                    .add("roomId", next.getRoomId())
                    .add("historyId", next.getId())
                    .addIfNotBlank("bvid", next.getBvId())
                    .addIfNotBlank("title", next.getTitle()));
            return;
        }
        BiliBiliUser user = resolvePublishAuthContext(next, room).user();
        int oldCode = next.getCode();

        BiliVideoInfoResponse videoInfoResponse = BiliApi.getVideoInfo(user,next.getBvId());
        int code = videoInfoResponse.getCode();
        if(code != 0){
            log.debug("[BLR] {}", LogKvs.event("VideoSync.VideoInfo.Failed")
                    .add("roomId", room.getRoomId())
                    .add("uname", room.getUname())
                    .add("historyId", next.getId())
                    .addIfNotBlank("title", next.getTitle())
                    .addIfNotBlank("bvid", next.getBvId())
                    .add("code", code)
                    .addIfNotBlank("msg", videoInfoResponse.getMessage()));
            
            // 处理 62002 (稿件不可见)
            if (code == 62002) {
                next.setCode(code);
                historyRepository.save(next);
                log.info("[BLR] {}", LogKvs.event("VideoSync.NotVisibleStop")
                        .add("roomId", room.getRoomId())
                        .add("uname", room.getUname())
                        .add("historyId", next.getId())
                        .addIfNotBlank("bvid", next.getBvId())
                        .addIfNotBlank("title", next.getTitle())
                        .add("code", code));
                return;
            }

            if (code == -404) {
                if (user != null) {
                    // 尝试使用 Member API 二次确认
                    var partInfo = BiliApi.getVideoPartInfo(user, next.getBvId());
                    if (partInfo.getCode() == -404) {
                        // Member API 也返回 404，确认删除
                        next.setCode(code);
                        historyRepository.save(next);
                        log.warn("[BLR] {}", LogKvs.event("VideoSync.DeletedConfirmed")
                                .add("roomId", room.getRoomId())
                                .add("uname", room.getUname())
                                .add("historyId", next.getId())
                                .addIfNotBlank("bvid", next.getBvId())
                                .addIfNotBlank("title", next.getTitle())
                                .add("code", code));
                    } else if (partInfo.getCode() == 0) {
                        // Member API 返回 0：注意其 state 字段语义不稳定，不能直接当作“可见性/审核状态”。
                        // 这里再用带 Cookie 的 view API 二次确认真实 state（0:公开, -50:仅自己可见）。
                        if (partInfo.getData() != null && partInfo.getData().getVideos() != null && !partInfo.getData().getVideos().isEmpty()) {
                            next.setAvId(String.valueOf(partInfo.getData().getVideos().get(0).getAid()));
                        }

                        try {
                            Thread.sleep(800);
                        } catch (InterruptedException ignored) {
                        }

                        BiliVideoInfoResponse confirm = BiliApi.getVideoInfo(user, next.getBvId());
                        if (confirm != null && confirm.getCode() == 0 && confirm.getData() != null) {
                            int state = confirm.getData().getState();
                            if (shouldKeepPendingReviewAfterRecentEdit(next, state)) {
                                log.info("[BLR] {}", LogKvs.event("VideoSync.KeepPendingAfterRecentEdit")
                                        .add("roomId", room.getRoomId())
                                        .add("uname", room.getUname())
                                        .add("historyId", next.getId())
                                        .addIfNotBlank("bvid", next.getBvId())
                                        .add("apiState", state)
                                        .add("localCode", next.getCode())
                                        .add("source", "member-confirm"));
                                return;
                            }
                            next.setCode(state);
                            next.setAvId(confirm.getData().getAid());
                            next.setBvId(confirm.getData().getBvid());
                            next.setCoverUrl(confirm.getData().getPic());
                            historyRepository.save(next);
                            publishArchiveStatusNotificationIfChanged(next, room, user, oldCode, state, null, true);
                            log.info("[BLR] {}", LogKvs.event("VideoSync.Confirm.Success")
                                    .add("roomId", room.getRoomId())
                                    .add("uname", room.getUname())
                                    .add("historyId", next.getId())
                                    .addIfNotBlank("bvid", next.getBvId())
                                    .addIfNotBlank("title", next.getTitle())
                                    .add("state", state));
                            return;
                        }

                        // 二次确认失败：补充 debug 信息，方便排查(例如 cookie 失效/风控/接口波动等)
                        if (confirm == null) {
                            log.debug("[BLR] {}", LogKvs.event("VideoSync.Confirm.Failed")
                                    .add("roomId", room.getRoomId())
                                    .add("uname", room.getUname())
                                    .add("historyId", next.getId())
                                    .addIfNotBlank("bvid", next.getBvId())
                                    .add("reason", "confirm=null"));
                        } else {
                            log.debug("[BLR] {}", LogKvs.event("VideoSync.Confirm.Failed")
                                    .add("roomId", room.getRoomId())
                                    .add("uname", room.getUname())
                                    .add("historyId", next.getId())
                                    .addIfNotBlank("bvid", next.getBvId())
                                    .add("code", confirm.getCode())
                                    .addIfNotBlank("msg", confirm.getMessage()));
                        }

                        int fallbackOldCode = next.getCode();
                        if (room.getIsOnlySelf() == 1) {
                            // 保守策略：房间配置要求仅自己可见，但当前无法可靠读取状态时，避免误发普通弹幕。
                            next.setCode(-50);
                            historyRepository.save(next);
                            log.info("[BLR] {}", LogKvs.event("VideoSync.StateFallback.OnlySelfByRoomConfig")
                                    .add("roomId", room.getRoomId())
                                    .add("uname", room.getUname())
                                    .add("historyId", next.getId())
                                    .addIfNotBlank("bvid", next.getBvId())
                                    .add("oldCode", fallbackOldCode)
                                    .add("newCode", -50));
                            return;
                        }

                        historyRepository.save(next);
                        log.info("[BLR] {}", LogKvs.event("VideoSync.StateFallback.KeepOld")
                                .add("roomId", room.getRoomId())
                                .add("uname", room.getUname())
                                .add("historyId", next.getId())
                                .addIfNotBlank("bvid", next.getBvId())
                                .add("oldCode", fallbackOldCode));
                        return;
                    } else {
                        log.warn("[BLR] {}", LogKvs.event("VideoSync.MemberApi.Unexpected")
                                .add("roomId", room.getRoomId())
                                .add("uname", room.getUname())
                                .add("historyId", next.getId())
                                .addIfNotBlank("bvid", next.getBvId())
                                .add("code", partInfo.getCode()));
                    }
                } else {
                    log.warn("[BLR] {}", LogKvs.event("VideoSync.Confirm.SkipNoUser")
                            .add("roomId", room.getRoomId())
                            .add("uname", room.getUname())
                            .add("historyId", next.getId())
                            .addIfNotBlank("bvid", next.getBvId()));
                }
            }
            return;
        }
        BiliVideoInfoResponse.BiliVideoInfo videoInfoResponseData = videoInfoResponse.getData();
        // 更新状态
        if (shouldKeepPendingReviewAfterRecentEdit(next, videoInfoResponseData.getState())) {
            log.info("[BLR] {}", LogKvs.event("VideoSync.KeepPendingAfterRecentEdit")
                    .add("roomId", room.getRoomId())
                    .add("uname", room.getUname())
                    .add("historyId", next.getId())
                    .addIfNotBlank("bvid", next.getBvId())
                    .add("apiState", videoInfoResponseData.getState())
                    .add("localCode", next.getCode()));
            return;
        }
        int state = videoInfoResponseData.getState();
        String statusReason = null;
        if (state == -50) {
            LockedArchiveSnapshot locked = detectLockedArchive(user, next);
            if (locked.locked()) {
                state = -4;
                statusReason = locked.reason();
                markHistoryLockedAndArchived(next);
                log.warn("[BLR] {}", LogKvs.event("VideoSync.LockedArchive.AutoForceArchived")
                        .add("roomId", room.getRoomId())
                        .add("uname", room.getUname())
                        .add("historyId", next.getId())
                        .addIfNotBlank("bvid", next.getBvId())
                        .addIfNotBlank("title", next.getTitle())
                        .addIfNotBlank("reason", locked.reason()));
            }
        } else if (state == -2) {
            statusReason = loadAuditReason(user, next);
        }
        next.setCode(state);
        next.setAvId(videoInfoResponseData.getAid());
        next.setBvId(videoInfoResponseData.getBvid());
        next.setCoverUrl(videoInfoResponseData.getPic());
        next = historyRepository.save(next);

        // 前端“刷新状态”只需要更新状态，但如果发现分P缺失CID，我们还是得同步一下CID，否则弹幕发不出去
        List<RecordHistoryPart> dbParts = partRepository.findByHistoryId(next.getId());
        boolean hasMissingCid = dbParts.stream().anyMatch(p -> p.getCid() == null || p.getCid() == 0);
        if (!doPostPublishProcessing && !hasMissingCid) {
            return;
        }

        // 0: 开放浏览, -50: 仅自己可见
        // 这两种状态都视为"发布成功"，可以进行后续的弹幕解析
        if(state != 0 && state != -50){
            publishArchiveStatusNotificationIfChanged(next, room, user, oldCode, state, statusReason, false);
            if (doPostPublishProcessing
                    && room != null
                    && partFileCleanupPolicy.isPostAuditCleanupType(room.getDeleteType())
                    && partFileCleanupPolicy.isProtectedFromPartFileCleanup(next)) {
                for (RecordHistoryPart part : dbParts) {
                    partFileCleanupPolicy.shouldSkipProtectedArchive(room, next, part, part.getFilePath(), "VideoSync", "postAuditCleanup");
                }
            }
            return;
        }
        
        RecordRoom recordRoom = room;
        List<BiliVideoInfoResponse.BiliVideoInfoPart> pages = videoInfoResponseData.getPages();
        if (pages == null) {
            publishArchiveStatusNotificationIfChanged(next, room, user, oldCode, state, statusReason, true);
            return;
        }
        List<OnlinePartSnapshot> onlineParts = new ArrayList<>();
        int fallbackPage = 1;
        for (BiliVideoInfoResponse.BiliVideoInfoPart page : pages) {
            if (page == null) {
                continue;
            }
            int pageNo = page.getPage() > 0 ? page.getPage() : fallbackPage;
            onlineParts.add(new OnlinePartSnapshot(pageNo, page.getPart(), null, page.getCid(), page.getDuration()));
            fallbackPage++;
        }
        SyncStatusResult orderResult = syncOnlinePartOrder(next, onlineParts);
        if (orderResult.partOrderAnomaly) {
            log.warn("[BLR] {}", LogKvs.event("VideoSync.PartOrder.Anomaly")
                    .add("historyId", next.getId())
                    .add("roomId", room.getRoomId())
                    .addIfNotBlank("bvid", next.getBvId())
                    .add("onlinePartCount", orderResult.onlinePartCount)
                    .add("matchedPartCount", orderResult.matchedPartCount)
                    .add("unmatchedOnlineCount", orderResult.unmatchedOnlineCount)
                    .add("unmatchedLocalCount", orderResult.unmatchedLocalCount));
        }
        for (BiliVideoInfoResponse.BiliVideoInfoPart page : pages) {
            RecordHistoryPart part = partRepository.findByHistoryIdAndTitle(next.getId(), page.getPart());
            if (part != null) {
                // 如果是手动刷新状态，仅针对缺失 CID 的分P进行解析
                boolean needReparse = part.getCid() == null || part.getCid() == 0;

                part.setCid(page.getCid());
                part.setPage(page.getPage());
                part.setPartOrder(page.getPage());
                part.setDuration(page.getDuration());

                // 如果CID已恢复，且之前标记为异常，则清除异常状态
                if (part.getCid() != null && part.getCid() != 0 && part.getUploadRetryCount() >= 9999) {
                    part.setUploadRetryCount(0);
                    part.setUpload(true);
                    part.setDeleteFailReason("");
                    log.info("[BLR] {}", LogKvs.event("VideoSync.Part.ExceptionCleared")
                            .add("historyId", next.getId())
                            .add("partId", part.getId())
                            .add("msg", "CID已获取，清除异常状态"));
                }

                part = partRepository.save(part);

                if (doPostPublishProcessing || needReparse) {
                    //解析弹幕入库
                    List<LiveMsg> liveMsgs = msgRepository.queryByPartId(part.getId());
                    msgRepository.deleteAll(liveMsgs);
                    liveMsgService.processing(part);
                    log.info("[BLR] {}", LogKvs.event("VideoSync.PartSynced")
                            .add("roomId", room.getRoomId())
                            .add("uname", room.getUname())
                            .add("historyId", next.getId())
                            .addIfNotBlank("bvid", next.getBvId())
                            .add("partId", part.getId())
                            .add("page", part.getPage())
                            .add("cid", part.getCid())
                            .addIfNotBlank("partTitle", part.getTitle())
                            .add("durationSec", part.getDuration()));
                }
            }
        }
        // 只有在自动同步（发布后处理）流程中才考虑删除文件
        publishArchiveStatusNotificationIfChanged(next, room, user, oldCode, state, statusReason, true);
        liveMsgSendSync.enqueueHistoryDispatch(next.getId());
        if (doPostPublishProcessing) {
            for (BiliVideoInfoResponse.BiliVideoInfoPart page : pages) {
                RecordHistoryPart part = partRepository.findByHistoryIdAndTitle(next.getId(), page.getPart());
                if (part != null) {
                    //如果配置成发布完成后删除则删除文件
                    String filePath = part.getFilePath();
                    if (recordRoom != null
                            && partFileCleanupPolicy.isPostAuditCleanupType(recordRoom.getDeleteType())
                            && partFileCleanupPolicy.shouldSkipProtectedArchive(recordRoom, next, part, filePath, "VideoSync", "postAuditCleanup")) {
                        continue;
                    }
                    if (recordRoom != null && recordRoom.getDeleteType() == 2) {
                        File file = new File(filePath);
                        boolean delete = file.delete();
                        if (delete) {
                            log.info("[BLR] {}", LogKvs.event("VideoSync.File.DeleteSuccess")
                                    .add("roomId", room.getRoomId())
                                    .add("uname", room.getUname())
                                    .add("historyId", next.getId())
                                    .addIfNotBlank("bvid", next.getBvId())
                                    .add("partId", part.getId())
                                    .addIfNotBlank("filePath", filePath));
                        } else {
                            log.warn("[BLR] {}", LogKvs.event("VideoSync.File.DeleteFailed")
                                    .add("roomId", room.getRoomId())
                                    .add("uname", room.getUname())
                                    .add("historyId", next.getId())
                                    .addIfNotBlank("bvid", next.getBvId())
                                    .add("partId", part.getId())
                                    .addIfNotBlank("filePath", filePath));
                        }
                    } else if (recordRoom != null && StringUtils.isNotBlank(recordRoom.getMoveDir()) && recordRoom.getDeleteType() == 5) {
                        String fileName = filePath.substring(filePath.lastIndexOf("/") + 1, filePath.lastIndexOf("."));
                        String startDirPath = filePath.substring(0, filePath.lastIndexOf('/') + 1);
                        String toDirPath = recordRoom.getMoveDir() + filePath.substring(0, filePath.lastIndexOf('/') + 1).replace(workPath, "");
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
                                    log.info("[BLR] {}", LogKvs.event("VideoSync.File.MoveSuccess")
                                            .add("roomId", room.getRoomId())
                                            .add("uname", room.getUname())
                                            .add("historyId", next.getId())
                                            .add("partId", part.getId())
                                            .add("from", file.getPath())
                                            .add("to", toDirPath + file.getName()));
                                } catch (Exception e) {
                                    log.error("[BLR] {}", LogKvs.event("VideoSync.File.MoveFailed")
                                            .add("roomId", room.getRoomId())
                                            .add("uname", room.getUname())
                                            .add("historyId", next.getId())
                                            .add("partId", part.getId())
                                            .add("from", file.getPath())
                                            .add("to", toDirPath + file.getName())
                                            .addIfNotBlank("err", e.getMessage())
                                            .add("ex", e.getClass().getSimpleName()), e);
                                }
                            }
                        }

                        part.setFilePath(toDirPath + filePath.substring(filePath.lastIndexOf("/") + 1));
                        part.setFileDelete(true);
                        part = partRepository.save(part);
                    } else if (recordRoom != null && StringUtils.isNotBlank(recordRoom.getMoveDir()) && recordRoom.getDeleteType() == 11) {
                        String fileName = filePath.substring(filePath.lastIndexOf("/") + 1, filePath.lastIndexOf("."));
                        String startDirPath = filePath.substring(0, filePath.lastIndexOf('/') + 1);
                        String toDirPath = recordRoom.getMoveDir() + filePath.substring(0, filePath.lastIndexOf('/') + 1).replace(workPath, "");
                        File toDir = new File(toDirPath);
                        if (!toDir.exists()) {
                            toDir.mkdirs();
                        }
                        File startDir = new File(startDirPath);
                        File[] files = startDir.listFiles((file, s) -> s.startsWith(fileName));
                        if (files != null) {
                            for (File file : files) {
                                try {
                                    Files.copy(Paths.get(file.getPath()), Paths.get(toDirPath + file.getName()),
                                            StandardCopyOption.REPLACE_EXISTING);
                                    log.info("[BLR] {}", LogKvs.event("VideoSync.File.CopySuccess")
                                            .add("roomId", room.getRoomId())
                                            .add("uname", room.getUname())
                                            .add("historyId", next.getId())
                                            .add("partId", part.getId())
                                            .add("from", file.getPath())
                                            .add("to", toDirPath + file.getName()));
                                } catch (Exception e) {
                                    log.error("[BLR] {}", LogKvs.event("VideoSync.File.CopyFailed")
                                            .add("roomId", room.getRoomId())
                                            .add("uname", room.getUname())
                                            .add("historyId", next.getId())
                                            .add("partId", part.getId())
                                            .add("from", file.getPath())
                                            .add("to", toDirPath + file.getName())
                                            .addIfNotBlank("err", e.getMessage())
                                            .add("ex", e.getClass().getSimpleName()), e);
                                }
                            }
                        }
                        part = partRepository.save(part);
                    }
                }
            }
        }
    }

    private void publishArchiveStatusNotificationIfChanged(RecordHistory history,
                                                           RecordRoom room,
                                                           BiliBiliUser user,
                                                           int oldCode,
                                                           int newCode,
                                                           String reason,
                                                           boolean includeSummary) {
        NotificationEventType eventType = resolveArchiveNotificationType(oldCode, newCode);
        if (eventType == null) {
            return;
        }
        String resolvedReason = reason;
        if (StringUtils.isBlank(resolvedReason)
                && (eventType == NotificationEventType.VIDEO_AUDIT_REJECTED || eventType == NotificationEventType.VIDEO_AUDIT_LOCKED)) {
            resolvedReason = loadAuditReason(user, history);
        }
        NotificationEvent event = NotificationEvent.of(room, eventType)
                .add("historyId", history == null ? null : history.getId())
                .add("videoTitle", history == null ? null : history.getTitle())
                .add("bvId", history == null ? null : history.getBvId())
                .add("oldArchiveCode", oldCode)
                .add("archiveCode", newCode)
                .add("status", archiveStatusLabel(newCode))
                .add("reason", resolvedReason);

        if (eventType == NotificationEventType.VIDEO_PUBLISH && includeSummary) {
            ArchiveNotificationSummary summary = buildArchiveNotificationSummary(history);
            event.add("danmakuCount", summary.danmakuCount())
                    .add("revenueText", formatMoney(summary.totalRevenueCny()))
                    .add("giftAmountText", formatMoney(summary.giftAmountCny()))
                    .add("scAmountText", formatMoney(summary.scAmount()))
                    .add("durationSeconds", summary.durationSeconds())
                    .add("durationText", formatDuration(summary.durationSeconds()))
                    .add("partCount", summary.partCount());
        }
        notificationEventPublisher.publish(event, room);
    }

    NotificationEventType resolveArchiveNotificationType(int oldCode, int newCode) {
        if (isAuditPassedCode(newCode) && !isAuditPassedCode(oldCode)) {
            return NotificationEventType.VIDEO_PUBLISH;
        }
        if (newCode == -2 && oldCode != -2) {
            return NotificationEventType.VIDEO_AUDIT_REJECTED;
        }
        if (newCode == -4 && oldCode != -4) {
            return NotificationEventType.VIDEO_AUDIT_LOCKED;
        }
        return null;
    }

    private boolean isAuditPassedCode(int code) {
        return code == 0 || code == -50;
    }

    private ArchiveNotificationSummary buildArchiveNotificationSummary(RecordHistory history) {
        if (history == null || history.getId() == null) {
            return ArchiveNotificationSummary.empty();
        }
        try {
            statsAggregationService.refreshHistoryStats(history.getId());
        } catch (Exception e) {
            log.debug("[BLR] {}", LogKvs.event("Notify.Publish.SummaryStatsRefreshFailed")
                    .add("historyId", history.getId())
                    .addIfNotBlank("bvid", history.getBvId())
                    .addIfNotBlank("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()));
        }
        RoomLiveSessionStats stats = sessionStatsRepository.findByHistoryId(history.getId());
        if (stats != null) {
            BigDecimal giftAmount = safeMoney(stats.getGiftAmountCny());
            BigDecimal scAmount = safeMoney(stats.getScAmount());
            return new ArchiveNotificationSummary(
                    stats.getMsgCount(),
                    giftAmount,
                    scAmount,
                    giftAmount.add(scAmount),
                    stats.getDurationSeconds(),
                    stats.getPartCount()
            );
        }
        List<RecordHistoryPart> parts = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());
        List<Long> partIds = parts.stream()
                .map(RecordHistoryPart::getId)
                .filter(id -> id != null)
                .toList();
        long danmakuCount = partIds.isEmpty() ? 0L : msgRepository.countByPartIdIn(partIds);
        long durationSeconds = fallbackDurationSeconds(history, parts);
        return new ArchiveNotificationSummary(danmakuCount, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, durationSeconds, parts.size());
    }

    private long fallbackDurationSeconds(RecordHistory history, List<RecordHistoryPart> parts) {
        if (history != null && history.getStartTime() != null && history.getEndTime() != null) {
            return Math.max(0L, Duration.between(history.getStartTime(), history.getEndTime()).getSeconds());
        }
        double seconds = 0.0d;
        if (parts != null) {
            for (RecordHistoryPart part : parts) {
                if (part != null && part.getDuration() > 0) {
                    seconds += part.getDuration();
                }
            }
        }
        return Math.max(0L, Math.round(seconds));
    }

    private BigDecimal safeMoney(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String formatMoney(BigDecimal value) {
        return "¥" + safeMoney(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String formatDuration(long seconds) {
        long safe = Math.max(0L, seconds);
        long hours = safe / 3600L;
        long minutes = (safe % 3600L) / 60L;
        long remainSeconds = safe % 60L;
        if (hours > 0) {
            return hours + "小时" + minutes + "分" + remainSeconds + "秒";
        }
        if (minutes > 0) {
            return minutes + "分" + remainSeconds + "秒";
        }
        return remainSeconds + "秒";
    }

    private String archiveStatusLabel(int code) {
        return switch (code) {
            case 0 -> "公开浏览";
            case -50 -> "仅自己可见";
            case -2 -> "审核退回";
            case -4 -> "稿件锁定";
            default -> "Code:" + code;
        };
    }

    boolean shouldKeepPendingReviewAfterRecentEdit(RecordHistory history, int apiState) {
        if (history == null || history.getCode() != -1 || apiState != -2 || history.getUpdateTime() == null) {
            return false;
        }
        return Duration.between(history.getUpdateTime(), LocalDateTime.now()).toMinutes() < 120;
    }

    private boolean isSkippedPart(RecordHistoryPart part) {
        if (part == null || StringUtils.isBlank(part.getDeleteFailType())) {
            return false;
        }
        String type = part.getDeleteFailType();
        return "SKIPPED_THRESHOLD".equals(type) || "MANUAL_SKIP".equals(type);
    }

    private static class OnlinePartSnapshot {
        private final int page;
        private final String title;
        private final String fileName;
        private final long cid;
        private final int duration;

        private OnlinePartSnapshot(int page, String title, String fileName, long cid, int duration) {
            this.page = page;
            this.title = title;
            this.fileName = fileName;
            this.cid = cid;
            this.duration = duration;
        }

        private String label() {
            String titleText = StringUtils.defaultIfBlank(title, fileName);
            if (StringUtils.isBlank(titleText)) {
                return "P" + page;
            }
            return "P" + page + " " + titleText;
        }
    }

    public static class SyncStatusResult {
        private final Long historyId;
        private boolean success;
        private String type = "info";
        private String msg = "";
        private boolean statusSynced;
        private boolean partOrderSynced;
        private boolean partOrderChanged;
        private boolean partOrderAnomaly;
        private Integer oldArchiveCode;
        private Integer archiveCode;
        private Integer videoInfoCode;
        private Integer memberPartInfoCode;
        private Integer auditDetailCode;
        private Integer auditDetailState;
        private String videoInfoMessage;
        private String memberPartInfoMessage;
        private String auditDetailMessage;
        private String auditReason;
        private String authSource;
        private String memberPartInfoLockSignal;
        private String auditDetailLockSignal;
        private boolean locked;
        private boolean forceArchived;
        private String lockReason;
        private int onlinePartCount;
        private int matchedPartCount;
        private int unmatchedOnlineCount;
        private int unmatchedLocalCount;
        private int sourceMismatchCount;
        private final List<String> unmatchedOnlineParts = new ArrayList<>();
        private final List<String> sourceMismatchParts = new ArrayList<>();

        private SyncStatusResult(Long historyId) {
            this.historyId = historyId;
        }

        private void mergePartOrder(SyncStatusResult other) {
            if (other == null) {
                return;
            }
            this.partOrderSynced = other.partOrderSynced;
            this.partOrderChanged = other.partOrderChanged;
            this.partOrderAnomaly = other.partOrderAnomaly;
            this.onlinePartCount = other.onlinePartCount;
            this.matchedPartCount = other.matchedPartCount;
            this.unmatchedOnlineCount = other.unmatchedOnlineCount;
            this.unmatchedLocalCount = other.unmatchedLocalCount;
            this.sourceMismatchCount = other.sourceMismatchCount;
            this.unmatchedOnlineParts.clear();
            this.unmatchedOnlineParts.addAll(other.unmatchedOnlineParts);
            this.sourceMismatchParts.clear();
            this.sourceMismatchParts.addAll(other.sourceMismatchParts);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("historyId", historyId);
            data.put("success", success);
            data.put("type", type);
            data.put("msg", msg);
            data.put("statusSynced", statusSynced);
            data.put("partOrderSynced", partOrderSynced);
            data.put("partOrderChanged", partOrderChanged);
            data.put("partOrderAnomaly", partOrderAnomaly);
            data.put("oldArchiveCode", oldArchiveCode);
            data.put("archiveCode", archiveCode);
            data.put("videoInfoCode", videoInfoCode);
            data.put("videoInfoMessage", videoInfoMessage);
            data.put("memberPartInfoCode", memberPartInfoCode);
            data.put("memberPartInfoMessage", memberPartInfoMessage);
            data.put("auditDetailCode", auditDetailCode);
            data.put("auditDetailMessage", auditDetailMessage);
            data.put("auditDetailState", auditDetailState);
            data.put("auditReason", auditReason);
            data.put("authSource", authSource);
            data.put("memberPartInfoLockSignal", memberPartInfoLockSignal);
            data.put("auditDetailLockSignal", auditDetailLockSignal);
            data.put("locked", locked);
            data.put("forceArchived", forceArchived);
            data.put("lockReason", lockReason);
            data.put("onlinePartCount", onlinePartCount);
            data.put("matchedPartCount", matchedPartCount);
            data.put("unmatchedOnlineCount", unmatchedOnlineCount);
            data.put("unmatchedLocalCount", unmatchedLocalCount);
            data.put("sourceMismatchCount", sourceMismatchCount);
            data.put("unmatchedOnlineParts", new ArrayList<>(unmatchedOnlineParts));
            data.put("sourceMismatchParts", new ArrayList<>(sourceMismatchParts));
            return data;
        }
    }

    private record LockedArchiveSnapshot(boolean locked, String reason) {
        private static LockedArchiveSnapshot unlocked() {
            return new LockedArchiveSnapshot(false, null);
        }

        private static LockedArchiveSnapshot locked(String reason) {
            return new LockedArchiveSnapshot(true, reason);
        }
    }

    private record ArchiveNotificationSummary(long danmakuCount,
                                              BigDecimal giftAmountCny,
                                              BigDecimal scAmount,
                                              BigDecimal totalRevenueCny,
                                              long durationSeconds,
                                              int partCount) {
        private static ArchiveNotificationSummary empty() {
            return new ArchiveNotificationSummary(0L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0L, 0);
        }
    }

    private record PublishAuthContext(String source, BiliBiliUser user) {
    }
}
