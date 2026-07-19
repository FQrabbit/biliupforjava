package top.sshh.bililiverecoder.job;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.service.LogAnalyzeService;
import top.sshh.bililiverecoder.service.impl.RecordBiliPublishService;
import top.sshh.bililiverecoder.service.UploadServiceFactory;
import top.sshh.bililiverecoder.service.UploadUserSerialScheduler;
import top.sshh.bililiverecoder.service.PartFileLocationService;
import top.sshh.bililiverecoder.service.StorageRootService;
import top.sshh.bililiverecoder.lifecycle.ShutdownState;
import top.sshh.bililiverecoder.util.LogKvs;
import top.sshh.bililiverecoder.util.TaskUtil;

import java.io.File;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
@Component
public class publishJob {

    @Autowired
    RecordBiliPublishService publishService;

    @Autowired
    RecordRoomRepository roomRepository;

    @Autowired
    RecordHistoryRepository historyRepository;

    @Autowired
    RecordHistoryPartRepository partRepository;

    @Autowired
    UploadServiceFactory uploadServiceFactory;

    @Autowired
    UploadUserSerialScheduler uploadUserSerialScheduler;

    @Autowired
    top.sshh.bililiverecoder.service.SystemConfigService systemConfigService;

    @Autowired
    ShutdownState shutdownState;

    @Autowired
    PartFileLocationService partFileLocationService;

    @Autowired
    StorageRootService storageRootService;

    @Autowired
    @Qualifier("myAsyncPool")
    ThreadPoolTaskExecutor asyncThreadPool;

    // value: [nextRetryTimestamp, compensateFailCount]
    private static final java.util.concurrent.ConcurrentHashMap<Long, long[]> uploadFailureMap = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, FileProbe> fileProbeMap = new java.util.concurrent.ConcurrentHashMap<>();

    private static final int UPLOAD_RETRY_GIVE_UP = 9999;
    private static final int ORPHAN_CLEANUP_LIMIT_PER_ROUND = 200;

    @Value("${publish.compensate.max-trigger-per-round:12}")
    private int compensateMaxTriggerPerRound;

    @Value("${publish.compensate.max-trigger-per-user-per-round:6}")
    private int compensateMaxTriggerPerUserPerRound;

    @Value("${publish.compensate.async-queue-usage-threshold:0.7}")
    private double compensateAsyncQueueUsageThreshold;

    @Value("${publish.compensate.async-active-usage-threshold:0.9}")
    private double compensateAsyncActiveUsageThreshold;

    @Value("${publish.compensate.trigger-throttle-ms:5000}")
    private long compensateTriggerThrottleMs;

    @Value("${publish.compensate.max-compensate-retries:8}")
    private int compensateMaxRetries;

    private static final class FileProbe {
        private final long size;
        private final long timeMs;
        private volatile long lastActiveTime;

        private FileProbe(long size, long timeMs) {
            this.size = size;
            this.timeMs = timeMs;
            this.lastActiveTime = timeMs;
        }

        void updateActiveTime() {
            this.lastActiveTime = System.currentTimeMillis();
        }
    }

    // 每天清理一次过期的 FileProbe
    @Scheduled(fixedDelay = 24 * 60 * 60 * 1000)
    public void cleanupFileProbeMap() {
        long now = System.currentTimeMillis();
        // 清理超过 24 小时未活跃的条目
        fileProbeMap.entrySet().removeIf(entry -> now - entry.getValue().lastActiveTime > 24 * 60 * 60 * 1000L);
    }

    private static boolean isFileStillWriting(File file, String filePath, long nowMs) {
        if (filePath == null || filePath.isBlank()) {
            return false;
        }
        if (file == null || !file.exists()) {
            FileProbe prev = fileProbeMap.get(filePath);
            // 如果文件丢失，但之前在监控中，可能是网络波动，给它一点时间（稳定窗口）
            if (prev != null) {
                long stableWindowMs = 60L * 1000L;
                if (nowMs - prev.timeMs >= stableWindowMs) {
                    fileProbeMap.remove(filePath);
                    log.warn("[BLR] {}", LogKvs.event("FileProbe.Remove.Missing")
                        .add("filePath", filePath));
                    return false;
                }
                log.warn("[BLR] {}", LogKvs.event("FileProbe.Missing.Waiting")
                    .add("filePath", filePath));
                return true;
            }
            return false;
        }

        long size = -1;
        try {
            size = file.length();
        } catch (Exception ignored) {
        }
        long stableWindowMs = 60L * 1000L;
        FileProbe prev = fileProbeMap.get(filePath);
        if (prev == null) {
            fileProbeMap.put(filePath, new FileProbe(size, nowMs));
            log.info("[BLR] {}", LogKvs.event("FileProbe.Start")
                .add("filePath", filePath)
                .add("size", size));
            return true;
        }
        prev.updateActiveTime();
        if (prev.size == size) {
            // 如果文件大小一直没变，且距离上次记录的时间已经超过了稳定窗口（60秒），说明文件已经稳定了
            if (nowMs - prev.timeMs >= stableWindowMs) {
                // 稳定后不要移除，否则下次检查又会变成"新文件"重新计时，导致一直在"不稳定"和"稳定"之间横跳
                // fileProbeMap.remove(filePath); 
                log.debug("[BLR] {}", LogKvs.event("FileProbe.Stable")
                    .add("filePath", filePath)
                    .add("size", size)
                    .add("duration", nowMs - prev.timeMs));
                return false;
            }
            // 文件大小没变，但还没到时间，继续等。ps：这里不要更新时间，否则倒计时会重置，导致一直过不去
            log.debug("[BLR] {}", LogKvs.event("FileProbe.Waiting")
                .add("filePath", filePath)
                .add("size", size)
                .add("duration", nowMs - prev.timeMs));
            return true;
        }
        log.info("[BLR] {}", LogKvs.event("FileProbe.SizeChanged")
            .add("filePath", filePath)
            .add("old", prev.size)
            .add("new", size));
        fileProbeMap.put(filePath, new FileProbe(size, nowMs));
        return true;
    }


    // 定时查询直播历史，如果下一次直播开始时间和上一次结束时间小于5min，视为同一次直播
    @Scheduled(fixedDelay = 60000, initialDelay = 5000)
    public void publish() {
        if (shutdownState.isShuttingDown() || Thread.currentThread().isInterrupted()) {
            return;
        }
        long roundStartNs = System.nanoTime();
        int pendingHistoryCount = 0;
        try {
        //查询出所有需要上传的房间
        List<RecordRoom> roomList = roomRepository.findByUpload(true);

        List<RecordHistory> historyList = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        // 从配置中读取短时间开播合并时间，默认20分钟
        int mergeIntervalMinutes = 20;
        try {
            String mergeIntervalConfig = systemConfigService.getAllConfigsMap().get(top.sshh.bililiverecoder.service.SystemConfigService.KEY_MERGE_INTERVAL_MINUTES);
            if (mergeIntervalConfig != null && !mergeIntervalConfig.isEmpty()) {
                mergeIntervalMinutes = Integer.parseInt(mergeIntervalConfig);
                if (mergeIntervalMinutes < 1) {
                    mergeIntervalMinutes = 1;
                } else if (mergeIntervalMinutes > 1440) {
                    mergeIntervalMinutes = 1440;
                }
            }
        } catch (Exception e) {
            log.warn("[BLR] {}", LogKvs.event("PublishJob.ParseMergeIntervalConfigFailed")
                    .add("error", e.getMessage()));
        }

        for (RecordRoom room : roomList) {
            // 查询不在录制,下播指定时间后的需要上传的历史
            Iterator<RecordHistory> iterator = historyRepository.findByRoomIdAndRecordingIsFalseAndUploadIsTrueAndPublishIsFalseAndUploadRetryCountLessThanAndEndTimeBetweenOrderByEndTimeAsc(room.getRoomId(), 5, now.minusMonths(1L), now.minusMinutes((long) mergeIntervalMinutes)).iterator();
            iterator.forEachRemaining(historyList::add);
        }
        if (!historyList.isEmpty()) {
            log.info("[BLR] {}", LogKvs.event("PublishJob.PendingCount")
                    .add("size", historyList.size()));
        }
        pendingHistoryCount = historyList.size();

        for (RecordHistory history : historyList) {
            if (shutdownState.isShuttingDown() || Thread.currentThread().isInterrupted()) {
                return;
            }
            if (history.isForceArchived()) {
                log.info("[BLR] {}", LogKvs.event("PublishJob.Skip.ForceArchived")
                        .add("historyId", history.getId())
                        .add("roomId", history.getRoomId())
                        .addIfNotBlank("title", history.getTitle()));
                continue;
            }
            // 二次校验：不信任 history.recording 单字段，避免被历史数据/列表纠偏误改后误触发投稿。
            // 只要存在未结束(endTime=null)或仍标记录制中的分P，就视为仍在录制，直接跳过。
            int actuallyRecordingParts = 0;
            try {
                actuallyRecordingParts = partRepository.countActuallyRecordingPartsByHistoryId(history.getId());
            } catch (Exception e) {
                log.warn("[BLR] {}", LogKvs.event("PublishJob.ActuallyRecordingParts.CountFailed")
                        .add("historyId", history.getId())
                        .add("roomId", history.getRoomId())
                        .addIfNotBlank("title", history.getTitle())
                        .addIfNotBlank("err", e.getMessage())
                        .add("ex", e.getClass().getSimpleName()), e);
                continue;
            }

            if (actuallyRecordingParts > 0) {
                // 打印最“可疑”的几个分P便于排查（优先 endTime=null / recording=true）
                try {
                    List<RecordHistoryPart> parts = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());
                    parts.stream()
                        .filter(p -> p.isRecording() || p.getEndTime() == null)
                        .sorted(Comparator.comparing((RecordHistoryPart p) -> p.getEndTime() == null ? 0 : 1)
                            .thenComparing(p -> p.isRecording() ? 0 : 1))
                        .limit(3)
                        .forEach(p -> {
                            PartFileLocationService.FileResolution resolution =
                                    partFileLocationService.resolveReadable(p.getId());
                            String fp = resolution.path() == null ? p.getFilePath() : resolution.path().toString();
                            long lm = -1;
                            try {
                                if (resolution.available()) {
                                    File f = resolution.path().toFile();
                                    if (f.exists()) {
                                        lm = f.lastModified();
                                    }
                                }
                            } catch (Exception ignored) {
                            }
                                log.debug("[BLR] {}", LogKvs.event("PublishJob.PartRecording.SuspectPart")
                                    .add("historyId", history.getId())
                                    .add("roomId", history.getRoomId())
                                    .add("partId", p.getId())
                                    .add("recording", p.isRecording())
                                    .add("endTimeNull", p.getEndTime() == null)
                                    .add("lastModified", lm)
                                    .add("filePath", fp));
                        });
                } catch (Exception ignored) {
                }

                        log.info("[BLR] {}", LogKvs.event("PublishJob.Skip.HasRecordingParts")
                            .add("historyId", history.getId())
                            .add("roomId", history.getRoomId())
                            .addIfNotBlank("title", history.getTitle())
                            .add("recordPartCount", actuallyRecordingParts));
                continue;
            }
            if (history.isStreaming()) {
                        log.info("[BLR] {}", LogKvs.event("PublishJob.Skip.HistoryStreaming")
                            .add("historyId", history.getId())
                            .add("roomId", history.getRoomId())
                            .addIfNotBlank("title", history.getTitle()));
                continue;
            }

            try {
                long nowMs = System.currentTimeMillis();
                List<RecordHistoryPart> parts = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());
                boolean stillWriting = false;
                for (RecordHistoryPart p : parts) {
                    // 已上传的分P不再检查文件稳定性，避免因文件移动或网络波动导致整个稿件卡住
                    if (p.isUpload()) {
                        continue;
                    }
                    PartFileLocationService.FileResolution resolution =
                            partFileLocationService.resolveReadable(p.getId());
                    if (!resolution.available()) {
                        continue;
                    }
                    String fp = resolution.path().toString();
                    File f = resolution.path().toFile();
                    if (isFileStillWriting(f, fp, nowMs)) {
                        log.info("[BLR] {}", LogKvs.event("PublishJob.Skip.FileStillWriting")
                                .add("historyId", history.getId())
                                .add("roomId", history.getRoomId())
                                .addIfNotBlank("title", history.getTitle())
                                .add("partId", p.getId())
                                .add("filePath", fp)
                                .add("lastModified", f.exists() ? f.lastModified() : -1)
                                .add("fileSizeBytes", f.exists() ? f.length() : -1));
                        stillWriting = true;
                        break;
                    }
                }
                if (stillWriting) {
                    continue;
                }
            } catch (Exception ignored) {
            }

            try {
                publishService.publishRecordHistory(history);
            } catch (Exception e) {
                log.error("[BLR] {}", LogKvs.event("PublishJob.PublishHistory.Error")
                        .add("historyId", history.getId())
                        .add("roomId", history.getRoomId())
                        .addIfNotBlank("title", history.getTitle())
                        .addIfNotBlank("err", e.getMessage()), e);
                history.setUploadRetryCount(history.getUploadRetryCount() + 1);
                historyRepository.save(history);
            }
            
            try {
                log.info("[BLR] {}", LogKvs.event("PublishJob.WaitNext")
                        .add("waitMs", 30000));
                Thread.sleep(30000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (shutdownState.isShuttingDown()) {
                    log.info("[BLR] {}", LogKvs.event("PublishJob.WaitNextInterrupted"));
                    return;
                }
                log.warn("[BLR] {}", LogKvs.event("PublishJob.WaitNextInterrupted"), e);
                return;
            }
        }
        } finally {
            log.info("[BLR] {}", LogKvs.event("PublishJob.Round.Done")
                    .addRoundCount("pendingHistory", pendingHistoryCount)
                    .addStageCostMs("total", roundStartNs));
        }
    }

    // 独立的分P上传补偿任务，每分钟检查未上传的分P（不依赖整个录制历史状态）
    @Scheduled(fixedDelay = 60000, initialDelay = 10000)
    public void retryFailedPartUpload() {
        if (shutdownState.isShuttingDown() || Thread.currentThread().isInterrupted()) {
            return;
        }
        if (storageRootService.hasPendingWorkPathChange()) return;
        long roundStartNs = System.nanoTime();
        int roomCount = 0;
        int roundTriggeredCount = 0;
        try {
        // 清理已投稿稿件中残留的未上传分P，防止对已投稿稿件继续触发上传
        try {
            List<RecordHistoryPart> orphanedParts = partRepository.findOrphanedPartsOfPublishedHistories(
                    PageRequest.of(0, ORPHAN_CLEANUP_LIMIT_PER_ROUND));
            if (!orphanedParts.isEmpty()) {
                log.info("[BLR] {}", LogKvs.event("PublishJob.PartCompensate.CleanOrphanedParts")
                        .add("count", orphanedParts.size()));
                for (RecordHistoryPart orphan : orphanedParts) {
                    if (orphan.getHistoryId() != null) {
                        Optional<RecordHistory> historyOptional = historyRepository.findById(orphan.getHistoryId());
                        if (historyOptional.isPresent() && RecordBiliPublishService.hasOnlineIdentity(historyOptional.get())) {
                            RecordHistory history = historyOptional.get();
                            uploadFailureMap.remove(orphan.getId());
                            log.info("[BLR] {}", LogKvs.event("PublishJob.PartCompensate.EditPublishedPart")
                                    .add("partId", orphan.getId())
                                    .add("historyId", orphan.getHistoryId())
                                    .add("roomId", history.getRoomId())
                                    .add("filePath", orphan.getFilePath()));
                            continue;
                        }
                    }
                    orphan.setUploadRetryCount(UPLOAD_RETRY_GIVE_UP);
                    orphan.setDeleteFailReason("所属稿件已投稿，不再补偿上传");
                    orphan.setDeleteFailType("HISTORY_ALREADY_PUBLISHED");
                    try {
                        partRepository.save(orphan);
                        // 同时清除内存中的冷却记录
                        uploadFailureMap.remove(orphan.getId());
                    } catch (Exception ignored) {
                    }
                    log.info("[BLR] {}", LogKvs.event("PublishJob.PartCompensate.OrphanedPartMarked")
                            .add("partId", orphan.getId())
                            .add("historyId", orphan.getHistoryId())
                            .add("filePath", orphan.getFilePath()));
                }
            }
        } catch (Exception e) {
            log.warn("[BLR] {}", LogKvs.event("PublishJob.PartCompensate.CleanOrphanedPartsFailed")
                    .addIfNotBlank("err", e.getMessage()));
        }

        List<RecordRoom> roomList = roomRepository.findByUpload(true);
        roomCount = roomList.size();
        LocalDateTime now = LocalDateTime.now();
        Map<Long, Integer> userTriggeredCount = new HashMap<>();
        Set<Long> editHistoryTriggered = new HashSet<>();
        
        for (RecordRoom room : roomList) {
            if (shutdownState.isShuttingDown() || Thread.currentThread().isInterrupted()) {
                return;
            }
            // 查询该房间下所有已录制完成（endTime > 5分钟前）但未上传的分P
            // 仅扫描所属history存在且已开启上传(upload=true)的分P，避免无意义重复扫描
            int roomQuota = Math.max(1, compensateMaxTriggerPerRound) - roundTriggeredCount;
            if (roomQuota <= 0) {
                log.info("[BLR] {}", LogKvs.event("PublishJob.PartCompensate.SkipByRoundQuota")
                        .add("roomId", room.getRoomId())
                        .add("uname", room.getUname())
                        .add("roundTriggered", roundTriggeredCount)
                        .add("roundQuota", Math.max(1, compensateMaxTriggerPerRound)));
                break;
            }
            List<RecordHistoryPart> pendingParts = partRepository.findPendingUploadPartsWithHistoryUploadEnabled(
                room.getRoomId(), now.minusMonths(1L), now.minusMinutes(5L), PageRequest.of(0, roomQuota));
            List<RecordHistoryPart> publishedPendingParts = partRepository.findPendingUploadPartsOfPublishedHistories(
                room.getRoomId(), now.minusMonths(1L), now.minusMinutes(5L), PageRequest.of(0, roomQuota));
            List<RecordHistoryPart> compensateParts = new ArrayList<>(pendingParts.size() + publishedPendingParts.size());
            compensateParts.addAll(pendingParts);
            compensateParts.addAll(publishedPendingParts);
            compensateParts.sort(Comparator.comparing(RecordHistoryPart::getEndTime, Comparator.nullsLast(Comparator.naturalOrder())));

            int triggeredCount = 0;
            for (RecordHistoryPart part : compensateParts) {
                if (roundTriggeredCount >= Math.max(1, compensateMaxTriggerPerRound)) {
                    log.info("[BLR] {}", LogKvs.event("PublishJob.PartCompensate.SkipByRoundQuota")
                            .add("roomId", room.getRoomId())
                            .add("uname", room.getUname())
                            .add("partId", part.getId())
                            .add("roundTriggered", roundTriggeredCount)
                            .add("roundQuota", Math.max(1, compensateMaxTriggerPerRound)));
                    break;
                }
                Long uploadUserId = room.getUploadUserId();
                int userTriggered = userTriggeredCount.getOrDefault(uploadUserId, 0);
                if (userTriggered >= Math.max(1, compensateMaxTriggerPerUserPerRound)) {
                    log.info("[BLR] {}", LogKvs.event("PublishJob.PartCompensate.SkipByUserQuota")
                            .add("roomId", room.getRoomId())
                            .add("uname", room.getUname())
                            .add("uploadUserId", uploadUserId)
                            .add("userTriggered", userTriggered)
                            .add("userQuota", Math.max(1, compensateMaxTriggerPerUserPerRound))
                            .add("remainingParts", compensateParts.size() - triggeredCount));
                    break;
                }
                if (isAsyncPoolBusy()) {
                    log.warn("[BLR] {}", LogKvs.event("PublishJob.PartCompensate.SkipByAsyncPoolPressure")
                            .add("roomId", room.getRoomId())
                            .add("uname", room.getUname())
                            .add("partId", part.getId())
                            .add("uploadUserId", uploadUserId)
                            .add("poolActive", asyncThreadPool.getActiveCount())
                            .add("poolMax", asyncThreadPool.getMaxPoolSize())
                            .add("queueSize", getAsyncQueueSize())
                            .add("queueCapacity", getAsyncQueueCapacity()));
                    break;
                }
                if (shutdownState.isShuttingDown() || Thread.currentThread().isInterrupted()) {
                    return;
                }
                if (uploadUserSerialScheduler.hasPendingPart(part.getId())) {
                    log.debug("[BLR] {}", LogKvs.event("PublishJob.PartCompensate.AlreadyQueued")
                            .add("roomId", room.getRoomId())
                            .add("uname", room.getUname())
                            .add("partId", part.getId())
                            .add("historyId", part.getHistoryId())
                            .add("filePath", part.getFilePath()));
                    continue;
                }
                // 检查失败冷却时间
                if (uploadFailureMap.containsKey(part.getId())) {
                    long[] entry = uploadFailureMap.get(part.getId());
                    if (entry != null && System.currentTimeMillis() < entry[0]) {
                        continue;
                    }
                    uploadFailureMap.remove(part.getId());
                }

                // 检查是否已经在上传队列中
                Thread uploadThread = TaskUtil.partUploadTask.get(part.getId());
                if (uploadThread != null && uploadThread.isAlive()) {
                    log.debug("[BLR] {}", LogKvs.event("PublishJob.PartCompensate.AlreadyUploading")
                            .add("roomId", room.getRoomId())
                            .add("uname", room.getUname())
                            .add("partId", part.getId())
                            .add("filePath", part.getFilePath()));
                    continue;
                }

                PartFileLocationService.FileResolution fileResolution = partFileLocationService.resolveReadable(part.getId());
                if (fileResolution.state() == PartFileLocationService.LocalFileState.DELETED_BY_POLICY
                        || fileResolution.state() == PartFileLocationService.LocalFileState.ROOT_OFFLINE
                        || fileResolution.state() == PartFileLocationService.LocalFileState.PROCESSING
                        || fileResolution.state() == PartFileLocationService.LocalFileState.PROCESS_FAILED) {
                    log.info("[BLR] {}", LogKvs.event("PublishJob.PartCompensate.SkipLocalFileState")
                            .add("roomId", room.getRoomId()).add("partId", part.getId())
                            .add("localFileState", fileResolution.state()));
                    continue;
                }
                if (!fileResolution.available()) {
                    String filePath = part.getFilePath();
                    log.error("[BLR] {}", LogKvs.event("PublishJob.PartCompensate.FileMissing")
                            .add("roomId", room.getRoomId())
                            .add("uname", room.getUname())
                            .add("partId", part.getId())
                            .add("filePath", filePath));
                    part.setUploadRetryCount(UPLOAD_RETRY_GIVE_UP);
                    String errorMsg = "稿件分P文件不存在，已放弃补偿上传: " + filePath;
                    part.setDeleteFailReason(errorMsg);
                    part.setDeleteFailType("FILE_MISSING");
                    try {
                        partRepository.save(part);
                        LogAnalyzeService.getInstance().processLog(errorMsg, "ERROR");
                    } catch (Exception ignored) {
                    }
                    continue;
                }
                String filePath = fileResolution.path().toString();
                File file = fileResolution.path().toFile();

                if (isFileStillWriting(file, filePath, System.currentTimeMillis())) {
                    log.info("[BLR] {}", LogKvs.event("PublishJob.PartCompensate.Skip.FileStillWriting")
                            .add("roomId", room.getRoomId())
                            .add("uname", room.getUname())
                            .add("partId", part.getId())
                            .add("filePath", filePath)
                            .add("lastModified", file.lastModified())
                            .add("fileSizeBytes", file.length()));
                    uploadFailureMap.put(part.getId(), new long[]{System.currentTimeMillis() + 5 * 60 * 1000, 0});
                    continue;
                }

                // 以磁盘真实文件为准，避免历史数据 fileSize/duration 写入异常导致误判
                long actualFileSize = file.length();
                if (actualFileSize <= 0) {
                    log.warn("[BLR] {}", LogKvs.event("PublishJob.PartCompensate.FileSizeUnreadableRetryLater")
                            .add("roomId", room.getRoomId())
                            .add("uname", room.getUname())
                            .add("partId", part.getId())
                            .add("filePath", filePath)
                            .add("fileSizeBytes", actualFileSize));
                    uploadFailureMap.put(part.getId(), new long[]{System.currentTimeMillis() + 10 * 60 * 1000, 0});
                    continue;
                }
                if (part.getFileSize() != actualFileSize && actualFileSize > 0) {
                    part.setFileSize(actualFileSize);
                    try {
                        partRepository.save(part);
                    } catch (Exception ignored) {
                    }
                }
                int actualDuration = Math.round(part.getDuration());
                if (actualDuration <= 0 && part.getStartTime() != null && part.getEndTime() != null) {
                    actualDuration = (int) java.time.Duration.between(part.getStartTime(), part.getEndTime()).getSeconds();
                    if (actualDuration > 0) {
                        part.setDuration(actualDuration);
                        try {
                            partRepository.save(part);
                        } catch (Exception ignored) {
                        }
                    }
                }
                if (actualDuration <= 0) {
                    log.warn("[BLR] {}", LogKvs.event("PublishJob.PartCompensate.DurationUnreadableRetryLater")
                            .add("roomId", room.getRoomId())
                            .add("uname", room.getUname())
                            .add("partId", part.getId())
                            .add("filePath", filePath)
                            .add("durationSec", actualDuration));
                    uploadFailureMap.put(part.getId(), new long[]{System.currentTimeMillis() + 10 * 60 * 1000, 0});
                    continue;
                }

                // 检查文件大小和时长是否符合要求
                long minBytes = 1024L * 1024L * room.getFileSizeLimit();
                if (actualFileSize < minBytes) {
                    log.info("[BLR] {}", LogKvs.event("PublishJob.PartCompensate.SkipBelowSizeLimit")
                            .add("roomId", room.getRoomId())
                            .add("uname", room.getUname())
                            .add("partId", part.getId())
                            .add("filePath", filePath)
                            .add("fileSizeBytes", actualFileSize)
                            .add("limitMB", room.getFileSizeLimit()));
                    continue;
                }
                if (actualDuration < room.getDurationLimit()) {
                    log.info("[BLR] {}", LogKvs.event("PublishJob.PartCompensate.SkipBelowDurationLimit")
                            .add("roomId", room.getRoomId())
                            .add("uname", room.getUname())
                            .add("partId", part.getId())
                            .add("filePath", filePath)
                            .add("durationSec", actualDuration)
                            .add("limitSec", room.getDurationLimit()));
                    continue;
                }

                // 已经在正常上传流程中失败过的分P，首次被补偿任务扫到时先设初始冷却，避免立即重试
                int retryCount = part.getUploadRetryCount();
                if (retryCount > 0 && !uploadFailureMap.containsKey(part.getId())) {
                    long initialBackoffMs = retryCount * 2L * 60 * 1000L;
                    uploadFailureMap.put(part.getId(), new long[]{System.currentTimeMillis() + initialBackoffMs, 0});
                    log.debug("[BLR] {}", LogKvs.event("PublishJob.PartCompensate.InitialBackoff")
                            .add("roomId", room.getRoomId())
                            .add("partId", part.getId())
                            .add("retryCount", retryCount)
                            .add("backoffMs", initialBackoffMs));
                    continue;
                }

                log.info("[BLR] {}", LogKvs.event("PublishJob.PartCompensate.TriggerUpload")
                        .add("roomId", room.getRoomId())
                        .add("uname", room.getUname())
                        .add("partId", part.getId())
                        .add("filePath", filePath));
                try {
                    Optional<RecordHistory> historyOptional = part.getHistoryId() == null
                            ? Optional.empty()
                            : historyRepository.findById(part.getHistoryId());
                    if (historyOptional.isPresent() && RecordBiliPublishService.hasOnlineIdentity(historyOptional.get())) {
                        RecordHistory history = historyOptional.get();
                        if (!editHistoryTriggered.add(history.getId())) {
                            log.debug("[BLR] {}", LogKvs.event("PublishJob.PartCompensate.AlreadyQueued")
                                    .add("roomId", room.getRoomId())
                                    .add("uname", room.getUname())
                                    .add("partId", part.getId())
                                    .add("historyId", part.getHistoryId())
                                    .add("filePath", filePath));
                            continue;
                        }
                        publishService.asyncPublishRecordHistory(history);
                        log.info("[BLR] {}", LogKvs.event("PublishJob.PartCompensate.EditPublishedTriggered")
                                .add("historyId", history.getId())
                                .add("roomId", history.getRoomId())
                                .addIfNotBlank("bvid", history.getBvId())
                                .addIfNotBlank("aid", history.getAvId()));
                        triggeredCount++;
                        roundTriggeredCount++;
                        userTriggeredCount.put(uploadUserId, userTriggered + 1);
                        continue;
                    }
                    boolean accepted = uploadServiceFactory.getUploadService(room.getLine()).asyncUploadIfNeeded(part);
                    if (!accepted) {
                        log.debug("[BLR] {}", LogKvs.event("PublishJob.PartCompensate.DuplicateRejected")
                                .add("roomId", room.getRoomId())
                                .add("uname", room.getUname())
                                .add("partId", part.getId())
                                .add("historyId", part.getHistoryId())
                                .add("filePath", filePath));
                        continue;
                    }
                    triggeredCount++;
                    roundTriggeredCount++;
                    userTriggeredCount.put(uploadUserId, userTriggered + 1);
                } catch (Exception e) {
                    long[] prev = uploadFailureMap.getOrDefault(part.getId(), new long[]{0, 0});
                    long failCount = prev[1] + 1;
                    int maxRetries = Math.max(1, compensateMaxRetries);

                    if (failCount >= maxRetries) {
                        // 补偿重试次数耗尽，持久化标记放弃
                        log.error("[BLR] {}", LogKvs.event("PublishJob.PartCompensate.CompensateExhausted")
                                .add("roomId", room.getRoomId())
                                .add("uname", room.getUname())
                                .add("partId", part.getId())
                                .add("filePath", filePath)
                                .add("failCount", failCount)
                                .add("maxRetries", maxRetries)
                                .addIfNotBlank("lastErr", e.getMessage())
                                .add("ex", e.getClass().getSimpleName()));
                        part.setUploadRetryCount(UPLOAD_RETRY_GIVE_UP);
                        part.setDeleteFailReason("补偿上传失败次数过多(" + failCount + "次)，已放弃");
                        part.setDeleteFailType("COMPENSATE_EXHAUSTED");
                        try {
                            partRepository.save(part);
                            LogAnalyzeService.getInstance().processLog(
                                "补偿上传失败次数过多，已放弃: partId=" + part.getId() + " file=" + filePath, "ERROR");
                        } catch (Exception ignored) {
                        }
                        uploadFailureMap.remove(part.getId());
                    } else {
                        // 指数退避: 2min → 4min → 8min → 16min → 32min → 60min(封顶)
                        long backoffMs = Math.min(60 * 60 * 1000L,
                                2 * 60 * 1000L * (1L << Math.min(failCount - 1, 5)));
                        if (isRejectedException(e)) {
                            log.warn("[BLR] {}", LogKvs.event("PublishJob.PartCompensate.UploadRejectedRetryLater")
                                    .add("roomId", room.getRoomId())
                                    .add("uname", room.getUname())
                                    .add("partId", part.getId())
                                    .add("filePath", filePath)
                                    .add("failCount", failCount)
                                    .add("backoffMs", backoffMs)
                                    .addIfNotBlank("err", e.getMessage())
                                    .add("ex", e.getClass().getSimpleName()));
                        } else {
                            log.error("[BLR] {}", LogKvs.event("PublishJob.PartCompensate.UploadFailed")
                                    .add("roomId", room.getRoomId())
                                    .add("uname", room.getUname())
                                    .add("partId", part.getId())
                                    .add("filePath", filePath)
                                    .add("failCount", failCount)
                                    .add("backoffMs", backoffMs)
                                    .addIfNotBlank("err", e.getMessage())
                                    .add("ex", e.getClass().getSimpleName()), e);
                        }
                        uploadFailureMap.put(part.getId(), new long[]{System.currentTimeMillis() + backoffMs, failCount});
                    }
                }

                try {
                    Thread.sleep(Math.max(0L, compensateTriggerThrottleMs));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (shutdownState.isShuttingDown()) {
                        log.info("[BLR] {}", LogKvs.event("PublishJob.PartCompensate.ThrottleSleepInterrupted"));
                        return;
                    }
                    log.warn("[BLR] {}", LogKvs.event("PublishJob.PartCompensate.ThrottleSleepInterrupted"), e);
                    return;
                }
            }

            if (triggeredCount > 0) {
                log.info("[BLR] {}", LogKvs.event("PublishJob.PartCompensate.TriggeredSummary")
                        .add("roomId", room.getRoomId())
                        .add("uname", room.getUname())
                        .add("triggered", triggeredCount)
                        .add("pending", compensateParts.size()));
            }
        }
            } finally {
                log.info("[BLR] {}", LogKvs.event("PublishJob.PartCompensate.Round.Done")
                        .addRoundCount("room", roomCount)
                        .addRoundCount("triggered", roundTriggeredCount)
                    .addStageCostMs("total", roundStartNs));
            }
    }

    private boolean isAsyncPoolBusy() {
        int max = Math.max(1, asyncThreadPool.getMaxPoolSize());
        int active = Math.max(0, asyncThreadPool.getActiveCount());
        int queueSize = getAsyncQueueSize();
        int queueCapacity = Math.max(1, getAsyncQueueCapacity());
        double activeUsage = (double) active / max;
        double queueUsage = (double) queueSize / queueCapacity;
        return activeUsage >= compensateAsyncActiveUsageThreshold || queueUsage >= compensateAsyncQueueUsageThreshold;
    }

    private int getAsyncQueueSize() {
        try {
            if (asyncThreadPool.getThreadPoolExecutor() == null || asyncThreadPool.getThreadPoolExecutor().getQueue() == null) {
                return 0;
            }
            return asyncThreadPool.getThreadPoolExecutor().getQueue().size();
        } catch (Exception e) {
            return 0;
        }
    }

    private int getAsyncQueueCapacity() {
        try {
            if (asyncThreadPool.getThreadPoolExecutor() == null || asyncThreadPool.getThreadPoolExecutor().getQueue() == null) {
                return 1;
            }
            return asyncThreadPool.getThreadPoolExecutor().getQueue().remainingCapacity() + asyncThreadPool.getThreadPoolExecutor().getQueue().size();
        } catch (Exception e) {
            return 1;
        }
    }

    private boolean isRejectedException(Throwable t) {
        if (t == null) {
            return false;
        }
        if (t instanceof TaskRejectedException || t instanceof RejectedExecutionException) {
            return true;
        }
        return isRejectedException(t.getCause());
    }
}
