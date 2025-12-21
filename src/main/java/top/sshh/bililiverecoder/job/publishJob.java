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
            List<RecordHistoryPart> pendingParts = partRepository.findByRoomIdAndRecordingIsFalseAndUploadIsFalseAndEndTimeBetweenOrderByEndTimeAsc(
                room.getRoomId(), now.minusMonths(1L), now.minusMinutes(5L));
            
            if (!pendingParts.isEmpty()) {
                log.info("分P上传补偿任务 房间[{}] 检测到 {} 个未上传的分P", room.getUname(), pendingParts.size());
            }
            
            for (RecordHistoryPart part : pendingParts) {
                // 如果该分P所属的录制历史本身未开启上传，则无需触发上传补偿，避免无意义的重复尝试
                RecordHistory history = null;
                try {
                    history = historyRepository.findById(part.getHistoryId()).orElse(null);
                } catch (Exception ignored) {
                }
                if (history == null) {
                    log.warn("分P上传补偿任务 找不到history，跳过 PartId={} HistoryId={}", part.getId(), part.getHistoryId());
                    continue;
                }
                if (!history.isUpload()) {
                    log.debug("分P上传补偿任务 所属history未开启上传，跳过 PartId={} HistoryId={}", part.getId(), part.getHistoryId());
                    continue;
                }

                // 检查失败冷却时间
                if (uploadFailureMap.containsKey(part.getId())) {
                    long nextRetry = uploadFailureMap.get(part.getId());
                    if (System.currentTimeMillis() < nextRetry) {
                        continue;
                    }
                    uploadFailureMap.remove(part.getId());
                }

                // 检查文件是否存在
                File file = new File(part.getFilePath());
                if (!file.exists()) {
                    log.warn("分P上传补偿任务 跳过不存在的文件: {}", part.getFilePath());
                    continue;
                }
                
                // 检查文件大小和时长是否符合要求
                if (part.getFileSize() < 1024 * 1024 * room.getFileSizeLimit()) {
                    log.info("分P上传补偿任务 文件大小小于设置的忽略大小，跳过: {}", part.getFilePath());
                    continue;
                }
                if (part.getDuration() < room.getDurationLimit()) {
                    log.info("分P上传补偿任务 文件时长小于设置的忽略时间，跳过: {}", part.getFilePath());
                    continue;
                }
                
                // 检查是否已经在上传队列中
                Thread uploadThread = TaskUtil.partUploadTask.get(part.getId());
                if (uploadThread != null && uploadThread.isAlive()) {
                    log.debug("分P上传补偿任务 PartId={} 正在上传中，跳过", part.getId());
                    continue;
                }
                
                // 触发异步上传（使用新线程避免阻塞定时任务）
                log.info("分P上传补偿任务 触发上传 PartId={} File={}", part.getId(), part.getFilePath());
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
        }
    }
}
