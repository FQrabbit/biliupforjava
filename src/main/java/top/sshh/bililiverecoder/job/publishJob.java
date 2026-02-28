package top.sshh.bililiverecoder.job;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
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
import top.sshh.bililiverecoder.lifecycle.ShutdownState;
import top.sshh.bililiverecoder.util.LogKvs;
import top.sshh.bililiverecoder.util.TaskUtil;

import java.io.File;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

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
    top.sshh.bililiverecoder.service.SystemConfigService systemConfigService;

    @Autowired
    ShutdownState shutdownState;

    private static final java.util.concurrent.ConcurrentHashMap<Long, Long> uploadFailureMap = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, FileProbe> fileProbeMap = new java.util.concurrent.ConcurrentHashMap<>();

    private static final int UPLOAD_RETRY_GIVE_UP = 9999;

    private static final class FileProbe {
        private final long size;
        private final long timeMs;

        private FileProbe(long size, long timeMs) {
            this.size = size;
            this.timeMs = timeMs;
        }
    }

    private static boolean isFileStillWriting(File file, String filePath, long nowMs) {
        if (filePath == null || filePath.isBlank()) {
            return false;
        }
        if (file == null || !file.exists()) {
            fileProbeMap.remove(filePath);
            return false;
        }
        long lastModified = -1;
        try {
            lastModified = file.lastModified();
        } catch (Exception ignored) {
        }
        long recentModifiedMs = 10L * 60L * 1000L;
        if (lastModified > 0 && lastModified > nowMs - recentModifiedMs) {
            return true;
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
            return true;
        }
        if (prev.size == size && nowMs - prev.timeMs >= stableWindowMs) {
            fileProbeMap.remove(filePath);
            return false;
        }
        fileProbeMap.put(filePath, new FileProbe(size, nowMs));
        return true;
    }


    // 定时查询直播历史，如果下一次直播开始时间和上一次结束时间小于5min，视为同一次直播
    @Scheduled(fixedDelay = 60000, initialDelay = 5000)
    public void publish() {
        if (shutdownState.isShuttingDown() || Thread.currentThread().isInterrupted()) {
            return;
        }
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

        for (RecordHistory history : historyList) {
            if (shutdownState.isShuttingDown() || Thread.currentThread().isInterrupted()) {
                return;
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
                            String fp = p.getFilePath();
                            long lm = -1;
                            try {
                                if (fp != null) {
                                    File f = new File(fp);
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
                    String fp = p.getFilePath();
                    if (fp == null || fp.isBlank()) {
                        continue;
                    }
                    File f = new File(fp);
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

            publishService.publishRecordHistory(history);
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
    }

    // 独立的分P上传补偿任务，每分钟检查未上传的分P（不依赖整个录制历史状态）
    @Scheduled(fixedDelay = 60000, initialDelay = 10000)
    public void retryFailedPartUpload() {
        if (shutdownState.isShuttingDown() || Thread.currentThread().isInterrupted()) {
            return;
        }
        List<RecordRoom> roomList = roomRepository.findByUpload(true);
        LocalDateTime now = LocalDateTime.now();
        
        for (RecordRoom room : roomList) {
            if (shutdownState.isShuttingDown() || Thread.currentThread().isInterrupted()) {
                return;
            }
            // 查询该房间下所有已录制完成（endTime > 5分钟前）但未上传的分P
            // 仅扫描所属history存在且已开启上传(upload=true)的分P，避免无意义重复扫描
            List<RecordHistoryPart> pendingParts = partRepository.findPendingUploadPartsWithHistoryUploadEnabled(
                room.getRoomId(), now.minusMonths(1L), now.minusMinutes(5L));

            int triggeredCount = 0;
            for (RecordHistoryPart part : pendingParts) {
                if (shutdownState.isShuttingDown() || Thread.currentThread().isInterrupted()) {
                    return;
                }
                // 检查失败冷却时间
                if (uploadFailureMap.containsKey(part.getId())) {
                    long nextRetry = uploadFailureMap.get(part.getId());
                    if (System.currentTimeMillis() < nextRetry) {
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

                // 检查文件是否存在
                File file = new File(part.getFilePath());
                if (!file.exists()) {
                    log.error("[BLR] {}", LogKvs.event("PublishJob.PartCompensate.FileMissing")
                            .add("roomId", room.getRoomId())
                            .add("uname", room.getUname())
                            .add("partId", part.getId())
                            .add("filePath", part.getFilePath()));
                    part.setUploadRetryCount(UPLOAD_RETRY_GIVE_UP);
                    String errorMsg = "稿件分P文件不存在，已放弃补偿上传: " + part.getFilePath();
                    part.setDeleteFailReason(errorMsg);
                    part.setDeleteFailType("FILE_MISSING");
                    try {
                        partRepository.save(part);
                        LogAnalyzeService.getInstance().processLog(errorMsg, "ERROR");
                    } catch (Exception ignored) {
                    }
                    continue;
                }

                if (isFileStillWriting(file, part.getFilePath(), System.currentTimeMillis())) {
                    log.info("[BLR] {}", LogKvs.event("PublishJob.PartCompensate.Skip.FileStillWriting")
                            .add("roomId", room.getRoomId())
                            .add("uname", room.getUname())
                            .add("partId", part.getId())
                            .add("filePath", part.getFilePath())
                            .add("lastModified", file.lastModified())
                            .add("fileSizeBytes", file.length()));
                    uploadFailureMap.put(part.getId(), System.currentTimeMillis() + 5 * 60 * 1000);
                    continue;
                }

                // 以磁盘真实文件为准，避免历史数据 fileSize/duration 写入异常导致误判
                long actualFileSize = file.length();
                if (actualFileSize <= 0) {
                    log.warn("[BLR] {}", LogKvs.event("PublishJob.PartCompensate.FileSizeUnreadableRetryLater")
                            .add("roomId", room.getRoomId())
                            .add("uname", room.getUname())
                            .add("partId", part.getId())
                            .add("filePath", part.getFilePath())
                            .add("fileSizeBytes", actualFileSize));
                    uploadFailureMap.put(part.getId(), System.currentTimeMillis() + 10 * 60 * 1000);
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
                            .add("filePath", part.getFilePath())
                            .add("durationSec", actualDuration));
                    uploadFailureMap.put(part.getId(), System.currentTimeMillis() + 10 * 60 * 1000);
                    continue;
                }
                
                // 检查文件大小和时长是否符合要求
                long minBytes = 1024L * 1024L * room.getFileSizeLimit();
                if (actualFileSize < minBytes) {
                    log.info("[BLR] {}", LogKvs.event("PublishJob.PartCompensate.SkipBelowSizeLimit")
                            .add("roomId", room.getRoomId())
                            .add("uname", room.getUname())
                            .add("partId", part.getId())
                            .add("filePath", part.getFilePath())
                            .add("fileSizeBytes", actualFileSize)
                            .add("limitMB", room.getFileSizeLimit()));
                    continue;
                }
                if (actualDuration < room.getDurationLimit()) {
                    log.info("[BLR] {}", LogKvs.event("PublishJob.PartCompensate.SkipBelowDurationLimit")
                            .add("roomId", room.getRoomId())
                            .add("uname", room.getUname())
                            .add("partId", part.getId())
                            .add("filePath", part.getFilePath())
                            .add("durationSec", actualDuration)
                            .add("limitSec", room.getDurationLimit()));
                    continue;
                }
                
                log.info("[BLR] {}", LogKvs.event("PublishJob.PartCompensate.TriggerUpload")
                        .add("roomId", room.getRoomId())
                        .add("uname", room.getUname())
                        .add("partId", part.getId())
                        .add("filePath", part.getFilePath()));
                triggeredCount++;
                try {
                    uploadServiceFactory.getUploadService(room.getLine()).asyncUpload(part);
                } catch (Exception e) {
                    log.error("[BLR] {}", LogKvs.event("PublishJob.PartCompensate.UploadFailed")
                            .add("roomId", room.getRoomId())
                            .add("uname", room.getUname())
                            .add("partId", part.getId())
                            .add("filePath", part.getFilePath())
                            .addIfNotBlank("err", e.getMessage())
                            .add("ex", e.getClass().getSimpleName()), e);
                    uploadFailureMap.put(part.getId(), System.currentTimeMillis() + 20 * 60 * 1000);
                }

                try {
                    // 避免同时触发过多线程
                    Thread.sleep(5000);
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
                        .add("pending", pendingParts.size()));
            }
        }
    }
}
