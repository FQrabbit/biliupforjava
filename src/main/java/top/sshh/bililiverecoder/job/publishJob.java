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
import top.sshh.bililiverecoder.service.impl.RecordBiliPublishService;
import top.sshh.bililiverecoder.service.UploadServiceFactory;
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

    private static final java.util.concurrent.ConcurrentHashMap<Long, Long> uploadFailureMap = new java.util.concurrent.ConcurrentHashMap<>();

    private static final int UPLOAD_RETRY_GIVE_UP = 9999;


    // 定时查询直播历史，如果下一次直播开始时间和上一次结束时间小于5min，视为同一次直播
    @Scheduled(fixedDelay = 60000, initialDelay = 5000)
    public void publish() {
        //查询出所有需要上传的房间
        List<RecordRoom> roomList = roomRepository.findByUpload(true);

        List<RecordHistory> historyList = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (RecordRoom room : roomList) {
            // 查询不在录制,下播十分钟后的需要上传的历史
            Iterator<RecordHistory> iterator = historyRepository.findByRoomIdAndRecordingIsFalseAndUploadIsTrueAndPublishIsFalseAndUploadRetryCountLessThanAndEndTimeBetweenOrderByEndTimeAsc(room.getRoomId(), 5, now.minusMonths(1L), now.minusMinutes(11L)).iterator();
            iterator.forEachRemaining(historyList::add);
        }
        if (!historyList.isEmpty()) {
            log.info("视频发布定时任务 待发布视频数量 size=={}", historyList.size());
        }

        for (RecordHistory history : historyList) {
            // 二次校验：不信任 history.recording 单字段，避免被历史数据/列表纠偏误改后误触发投稿。
            // 只要存在未结束(endTime=null)或仍标记录制中的分P，就视为仍在录制，直接跳过。
            int actuallyRecordingParts = 0;
            try {
                actuallyRecordingParts = partRepository.countActuallyRecordingPartsByHistoryId(history.getId());
            } catch (Exception e) {
                log.warn("视频发布定时任务 统计录制中分P失败，跳过投稿 HistoryId={} Err={}", history.getId(), e.getMessage());
                continue;
            }

            // 兜底纠偏：有时录制结束事件丢失/顺序异常会导致分P长期残留 recording=true 或 endTime=null。
            // 若对应文件已超过10分钟未修改，按“录制已结束”处理，避免定时任务长期卡住。
            if (actuallyRecordingParts > 0) {
                long thresholdMs = 10L * 60L * 1000L;
                long nowMs = System.currentTimeMillis();
                int healed = 0;
                List<RecordHistoryPart> parts = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());
                for (RecordHistoryPart part : parts) {
                    if (!part.isRecording() && part.getEndTime() != null) {
                        continue;
                    }
                    String filePath = part.getFilePath();
                    if (filePath == null) {
                        continue;
                    }
                    File file = new File(filePath);
                    if (!file.exists()) {
                        continue;
                    }
                    if (file.lastModified() <= nowMs - thresholdMs) {
                        boolean changed = false;
                        if (part.isRecording()) {
                            part.setRecording(false);
                            changed = true;
                        }
                        if (part.getEndTime() == null) {
                            part.setEndTime(LocalDateTime.now());
                            changed = true;
                        }
                        if (changed) {
                            try {
                                partRepository.save(part);
                                healed++;
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
                if (healed > 0) {
                    try {
                        actuallyRecordingParts = partRepository.countActuallyRecordingPartsByHistoryId(history.getId());
                        log.info("视频发布定时任务 已纠偏分P录制状态 healed={} HistoryId={} remain={}", healed, history.getId(), actuallyRecordingParts);
                    } catch (Exception e) {
                        log.warn("视频发布定时任务 纠偏后再次统计失败，跳过投稿 HistoryId={} Err={}", history.getId(), e.getMessage());
                        continue;
                    }
                }
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
                            log.debug("视频发布定时任务 录制中分P详情 HistoryId={} PartId={} recording={} endTimeNull={} lastModified={} filePath={}",
                                history.getId(), p.getId(), p.isRecording(), p.getEndTime() == null, lm, fp);
                        });
                } catch (Exception ignored) {
                }

                log.info("视频发布定时任务 检测到仍在录制的分P，跳过投稿 HistoryId={} recordPartCount={}", history.getId(), actuallyRecordingParts);
                continue;
            }
            if (history.isStreaming()) {
                log.info("视频发布定时任务 history.streaming=true，跳过投稿 HistoryId={}", history.getId());
                continue;
            }

            publishService.publishRecordHistory(history);
            try {
                log.info("单个视频发布流程结束，等待30秒继续下一个任务...");
                Thread.sleep(30000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    // 独立的分P上传补偿任务，每分钟检查未上传的分P（不依赖整个录制历史状态）
    @Scheduled(fixedDelay = 60000, initialDelay = 10000)
    public void retryFailedPartUpload() {
        List<RecordRoom> roomList = roomRepository.findByUpload(true);
        LocalDateTime now = LocalDateTime.now();
        
        for (RecordRoom room : roomList) {
            // 查询该房间下所有已录制完成（endTime > 5分钟前）但未上传的分P
            // 仅扫描所属history存在且已开启上传(upload=true)的分P，避免无意义重复扫描
            List<RecordHistoryPart> pendingParts = partRepository.findPendingUploadPartsWithHistoryUploadEnabled(
                room.getRoomId(), now.minusMonths(1L), now.minusMinutes(5L));

            int triggeredCount = 0;
            for (RecordHistoryPart part : pendingParts) {
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
                    log.debug("分P上传补偿任务 PartId={} 正在上传中，跳过", part.getId());
                    continue;
                }

                // 检查文件是否存在
                File file = new File(part.getFilePath());
                if (!file.exists()) {
                    log.warn("分P上传补偿任务 跳过不存在的文件: {}", part.getFilePath());
                    continue;
                }

                // 以磁盘真实文件为准，避免历史数据 fileSize/duration 写入异常导致误判
                long actualFileSize = file.length();
                if (actualFileSize <= 0) {
                    log.warn("分P上传补偿任务 无法读取文件大小(size={})，可能文件格式/挂载异常，放弃且不再重试 PartId={} File={}",
                        actualFileSize, part.getId(), part.getFilePath());
                    part.setUploadRetryCount(UPLOAD_RETRY_GIVE_UP);
                    try {
                        partRepository.save(part);
                    } catch (Exception ignored) {
                    }
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
                    log.warn("分P上传补偿任务 无法读取文件时长(durationSec={})，可能文件格式/事件数据异常，放弃且不再重试 PartId={} File={}",
                        actualDuration, part.getId(), part.getFilePath());
                    part.setUploadRetryCount(UPLOAD_RETRY_GIVE_UP);
                    try {
                        partRepository.save(part);
                    } catch (Exception ignored) {
                    }
                    continue;
                }
                
                // 检查文件大小和时长是否符合要求
                long minBytes = 1024L * 1024L * room.getFileSizeLimit();
                if (actualFileSize < minBytes) {
                    log.info("分P上传补偿任务 文件大小小于设置的忽略大小，跳过: {} (sizeMB={} < limitMB={})", part.getFilePath(),
                        String.format("%.2f", actualFileSize / 1024.0 / 1024.0), room.getFileSizeLimit());
                    continue;
                }
                if (actualDuration < room.getDurationLimit()) {
                    log.info("分P上传补偿任务 文件时长小于设置的忽略时间，跳过: {} (durationSec={} < limitSec={})", part.getFilePath(),
                        actualDuration, room.getDurationLimit());
                    continue;
                }
                
                // 触发异步上传（使用新线程避免阻塞定时任务）
                log.info("分P上传补偿任务 触发上传 PartId={} File={}", part.getId(), part.getFilePath());
                triggeredCount++;
                new Thread(() -> {
                    try {
                        uploadServiceFactory.getUploadService(room.getLine()).asyncUpload(part);
                    } catch (Exception e) {
                        log.error("分P上传补偿任务 上传失败 PartId={} Error={}", part.getId(), e.getMessage());
                        // 失败后冷却20分钟
                        uploadFailureMap.put(part.getId(), System.currentTimeMillis() + 20 * 60 * 1000);
                    }
                }).start();

                try {
                    // 避免同时触发过多线程
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            if (triggeredCount > 0) {
                log.info("分P上传补偿任务 房间[{}] 本轮触发 {} 个分P上传（待处理未上传分P总数={}）", room.getUname(), triggeredCount, pendingParts.size());
            }
        }
    }
}
