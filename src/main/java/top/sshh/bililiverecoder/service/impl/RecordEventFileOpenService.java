package top.sshh.bililiverecoder.service.impl;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.repo.*;
import top.sshh.bililiverecoder.service.RecordEventService;
import top.sshh.bililiverecoder.service.SystemConfigService;
import top.sshh.bililiverecoder.util.LogKvs;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.springframework.util.CollectionUtils;

@Slf4j
@Component
public class RecordEventFileOpenService implements RecordEventService {

    @Value("${record.work-path}")
    private String workPath;

    @PostConstruct
    public void initWorkPath() {
        workPath = workPath.replaceAll("\\\\\\\\", "\\\\");
        workPath = workPath.replace("\\", "/");
    }

    @Autowired
    private RecordRoomRepository roomRepository;

    @Autowired
    private RecordHistoryRepository historyRepository;

    @Autowired
    private RecordHistoryPartRepository historyPartRepository;


    @Autowired
    private SystemConfigService systemConfigService;

    @Override
    public void processing(RecordEventDTO event) {
        RecordEventData eventData = event.getEventData();
        String roomId = eventData.getRoomId();
        if (roomId == null || roomId.isBlank()) {
            log.error("[BLR] {}", LogKvs.event("Webhook.InvalidPayload").add("reason", "RoomId is null or blank"));
            return;
        }

        // 使用 roomId 字符串的 intern() 方法作为锁对象
        // 这能确保所有对同一个 roomId 的操作都是串行的，而不同 roomId 的操作可以并行
        synchronized (roomId.intern()) {
            try {
                String relativePath = eventData.getRelativePath();
                String incomingSessionId = eventData.getSessionId();

                log.info("[BLR] {}", LogKvs.event("FileOpen.Received")
                        .add("roomId", roomId)
                        .add("sessionId", incomingSessionId)
                        .add("filePath", relativePath));

                RecordRoom room = roomRepository.findByRoomId(roomId);
                if (room == null) {
                    log.warn("[BLR] {}", LogKvs.event("FileOpen.RoomNotFound.AutoCreate")
                            .add("roomId", roomId));
                    room = new RecordRoom();
                    room.setRoomId(roomId);
                    room.setUname(eventData.getName());
                    room.setTitle(eventData.getTitle());
                    room.setCreateTime(LocalDateTime.now());
                    room.setHistoryId(-1L); 
                }

                // --- 自愈式检查核心逻辑 ---
                String currentSessionId = room.getSessionId();
                if (incomingSessionId != null && !incomingSessionId.equals(currentSessionId)) {
                    log.debug("[BLR] {}", LogKvs.event("SessionMismatch.Detected")
                            .add("roomId", roomId)
                            .add("reason", "Missed RecordStarted event or session changed")
                            .add("currentSessionId", currentSessionId)
                            .add("incomingSessionId", incomingSessionId));

                    // 尝试复用最近 20 分钟内的历史记录
                    LocalDateTime now = LocalDateTime.now();
                    RecordHistory history = null;

                    // 优先复用正在录制中的记录
                    List<RecordHistory> activeHistoryList = historyRepository.findByRoomIdAndRecordingTrueOrderByStartTimeDesc(roomId);
                    if (!CollectionUtils.isEmpty(activeHistoryList)) {
                        RecordHistory activeHistory = activeHistoryList.get(0);
                        // 增加活跃度检查：如果录制中的记录在过去 24 小时内没有任何更新（以 endTime 为准），则视为过时记录
                        // 额外检查：如果稿件已经发布（publish=true），说明已经提交审核，无法再通过投稿接口增加分P，必须拆分新稿件
                        if (activeHistory.getEndTime() != null && activeHistory.getEndTime().isAfter(now.minusHours(24)) && !activeHistory.isPublish()) {
                            history = activeHistory;
                            log.info("[BLR] {}", LogKvs.event("SessionMismatch.Merged")
                                    .add("type", "ActiveHistory")
                                    .add("roomId", roomId)
                                    .add("oldSessionId", history.getSessionId())
                                    .add("newSessionId", incomingSessionId)
                                    .add("historyId", history.getId()));
                        } else if (activeHistory.isPublish()) {
                            log.info("[BLR] {}", LogKvs.event("SessionMismatch.SkipPublished")
                                    .add("roomId", roomId)
                                    .add("newSessionId", incomingSessionId)
                                    .add("historyId", activeHistory.getId()));
                        } else {
                            log.warn("[BLR] {}", LogKvs.event("SessionMismatch.ActiveHistoryStale")
                            .add("roomId", roomId)
                            .add("historyId", activeHistory.getId())
                            .add("lastActivity", activeHistory.getEndTime())
                            .add("reason", "Recording marked true but no activity for 24h"));
                    // 如果是过时记录，级联清理其所有状态
                    activeHistory.setRecording(false);
                    activeHistory.setStreaming(false);
                    if (activeHistory.getEndTime() == null) {
                        activeHistory.setEndTime(now);
                    }
                    historyRepository.save(activeHistory);

                    // 同步清理该历史下的所有僵尸分P
                    historyPartRepository.findByHistoryId(activeHistory.getId()).stream()
                            .filter(p -> p.isRecording() || p.getEndTime() == null)
                            .forEach(p -> {
                                p.setRecording(false);
                                if (p.getEndTime() == null) {
                                    p.setEndTime(now);
                                }
                                historyPartRepository.save(p);
                            });
                        }
                    }

                    if (history == null) {
                        // 其次复用最近结束的记录
                        // 读取用户配置的断线合并时间（默认20分钟），防止用户配置大数值时这里只查20分钟导致遗漏
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
                            log.warn("[BLR] {}", LogKvs.event("SessionMismatch.ParseMergeIntervalConfigFailed")
                                    .add("error", e.getMessage()));
                        }
                        
                        // 同样需要检查 publish 状态，已发布的稿件无法追加分P
                        // 增加上限缓冲(plusMinutes(5))，防止并发处理时FileClosed更新的endTime略晚于当前now()导致查询遗漏
                        List<RecordHistory> historyList = historyRepository.findByRoomIdAndEndTimeBetweenOrderByEndTimeAsc(roomId, now.minusMinutes((long) mergeIntervalMinutes), now.plusMinutes(5L));
                        if (!CollectionUtils.isEmpty(historyList)) {
                            // 过滤掉已经发布的稿件
                            historyList = historyList.stream().filter(h -> !h.isPublish()).collect(java.util.stream.Collectors.toList());
                            if (!historyList.isEmpty()) {
                                // 复用已有的历史记录（取最近一条）
                                history = historyList.get(historyList.size() - 1);
                                log.info("[BLR] {}", LogKvs.event("SessionMismatch.Merged")
                                        .add("type", "RecentHistory")
                                        .add("roomId", roomId)
                                        .add("oldSessionId", history.getSessionId())
                                        .add("newSessionId", incomingSessionId)
                                        .add("historyId", history.getId()));
                            }
                        }
                    }

                    if (history == null) {
                        // 创建新的历史记录
                        history = new RecordHistory();
                        history.setRoomId(roomId);
                        history.setStartTime(eventData.getFileOpenTime() != null ? eventData.getFileOpenTime().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime() : LocalDateTime.now());
                        history.setEndTime(history.getStartTime());
                        history.setEventId(event.getEventId());
                        history.setTitle(eventData.getTitle());
                        history.setUpload(room.isUpload());
                        
                        log.info("[BLR] {}", LogKvs.event("SessionMismatch.CreatedNew")
                                .add("roomId", roomId)
                                .add("newSessionId", incomingSessionId));
                    }
                    
                    // 更新 History 和 Room 信息
                    history.setSessionId(incomingSessionId);
                    history.setRecording(true);
                    history.setStreaming(eventData.isStreaming());
                    history = historyRepository.save(history);

                    room.setHistoryId(history.getId());
                    room.setSessionId(incomingSessionId);
                    room.setRecording(true);
                    room.setStreaming(eventData.isStreaming());
                    room.setUname(eventData.getName());
                    room.setTitle(eventData.getTitle());
                    roomRepository.save(room);
                    
                    log.info("[BLR] {}", LogKvs.event("SessionMismatch.Recovered")
                            .add("roomId", roomId)
                            .add("historyId", history.getId())
                            .add("newSessionId", incomingSessionId));
                }
                
                Optional<RecordHistory> historyOptional = historyRepository.findById(room.getHistoryId());
                if (!historyOptional.isPresent()) {
                    log.error("[BLR] {}", LogKvs.event("FileOpen.FATAL.HistoryStillNotFound")
                            .add("roomId", roomId)
                            .add("historyId", room.getHistoryId())
                            .add("sessionId", incomingSessionId));
                    return;
                }
                RecordHistory history = historyOptional.get();
                
                int partCount = historyPartRepository.countByHistoryId(history.getId());
                if(partCount >= 99){
                    log.warn("[BLR] {}", LogKvs.event("FileOpen.PartLimitReached")
                            .add("historyId", history.getId())
                            .add("limit", 100));
                    return; 
                }

                if ("blrec".equals(incomingSessionId)) {
                    relativePath = relativePath.replace(workPath, "");
                }
                String filePath = workPath + File.separator + relativePath;

                if (historyPartRepository.existsByFilePath(filePath)) {
                    log.warn("[BLR] {}", LogKvs.event("FileOpen.PartExists.Skip")
                            .add("roomId", roomId)
                            .add("historyId", history.getId())
                            .add("filePath", filePath));
                    return;
                }

                RecordHistoryPart part = new RecordHistoryPart();
                part.setEventId(event.getEventId());
                part.setTitle(LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM月dd日HH点mm分ss秒")));
                part.setLiveTitle(eventData.getTitle());
                part.setAreaName(eventData.getAreaNameChild());
                part.setRoomId(history.getRoomId());
                part.setHistoryId(history.getId());
                part.setFilePath(filePath);
                part.setFileSize(0L);
                part.setSessionId(incomingSessionId);
                part.setRecording(true);
                part.setStartTime(LocalDateTime.now());
                part.setEndTime(null);
                part = historyPartRepository.save(part);

                log.info("[BLR] {}", LogKvs.event("FileOpen.PartSaved")
                    .add("roomId", roomId)
                    .add("historyId", history.getId())
                    .add("partId", part.getId())
                    .add("partTitle", part.getTitle()));

                history.setTitle(eventData.getTitle());
                history.setRecording(true);
                history.setStreaming(eventData.isStreaming());
                history.setFilePath(workPath + File.separator + relativePath.substring(0, relativePath.lastIndexOf('/')));
                history.setEndTime(LocalDateTime.now());
                historyRepository.save(history);

            } catch (Exception e) {
                log.error("[BLR] {}", LogKvs.event("FileOpen.UnhandledException")
                        .add("roomId", eventData.getRoomId())
                        .add("sessionId", eventData.getSessionId())
                        .add("err", e.getMessage())
                        .add("ex", e.getClass().getSimpleName()), e);
            }
        }
    }
}
