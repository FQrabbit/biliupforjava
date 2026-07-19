package top.sshh.bililiverecoder.service.impl;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.lifecycle.ShutdownState;
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.service.RecordEventService;
import top.sshh.bililiverecoder.service.PartFileCleanupPolicy;
import top.sshh.bililiverecoder.service.PartFileLocationService;
import top.sshh.bililiverecoder.service.PartFileOperationService;
import top.sshh.bililiverecoder.service.StatsAggregationService;
import top.sshh.bililiverecoder.service.UploadServiceFactory;
import top.sshh.bililiverecoder.util.LogKvs;

import java.io.File;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Component
public class RecordEventFileClosedService implements RecordEventService {

    @Value("${record.work-path}")
    private String workPath;

    @Autowired
    private RecordRoomRepository roomRepository;

    @Autowired
    private RecordHistoryRepository historyRepository;

    @Autowired
    private RecordHistoryPartRepository historyPartRepository;

    @Lazy
    @Autowired
    private UploadServiceFactory uploadServiceFactory;

    @Autowired
    @Qualifier("taskExecutor")
    private TaskExecutor taskExecutor;

    @Autowired
    private ShutdownState shutdownState;

    @Autowired
    private StatsAggregationService statsAggregationService;

    @Autowired
    private PartFileLocationService partFileLocationService;

    @Autowired
    private PartFileOperationService partFileOperationService;

    @Autowired
    private PartFileCleanupPolicy partFileCleanupPolicy;

    @Autowired
    private top.sshh.bililiverecoder.service.RecordPartPathService partPathService;

    @PostConstruct
    public void initWorkPath() {
        workPath = workPath.replaceAll("\\\\\\\\", "\\\\");
        workPath = workPath.replace("\\", "/");
    }


    @Override
    public void processing(RecordEventDTO event) {
        RecordEventData eventData = event.getEventData();
        String sessionId = eventData.getSessionId();
        String relativePath = eventData.getRelativePath();
        log.info("[BLR] {}", LogKvs.event("FileClosed")
            .add("roomId", eventData.getRoomId())
            .add("title", eventData.getTitle())
            .add("filePath", relativePath)
            .add("durationSec", eventData.getDuration())
            .add("fileSizeBytes", eventData.getFileSize()));
        RecordRoom room = roomRepository.findByRoomId(eventData.getRoomId());
        if (room == null) {
            log.info("[BLR] {}", LogKvs.event("FileClosed.NoRecording")
                    .add("roomId", eventData.getRoomId())
                    .add("filePath", relativePath)
                    .add("msg", "收到文件关闭事件但本地无房间配置记录，已忽略。"));
            return;
        }
        if (room.getHistoryId() == null) {
            log.info("[BLR] {}", LogKvs.event("FileClosed.NoRecording")
                    .add("roomId", eventData.getRoomId())
                    .add("filePath", relativePath)
                    .add("msg", "收到文件关闭事件但本地无活跃录制记录。请检查录播姬是否开启了自动录制。"));
            return;
        }
        String filePath = partPathService.resolveWebhookPath(relativePath);
        RecordHistoryPart partByPath = historyPartRepository.findByFilePath(filePath);
        boolean matchedByCanonicalPath = false;
        if (partByPath == null) {
            partByPath = findByCanonicalPath(historyPartRepository.findOpenCandidatesByRoomId(eventData.getRoomId()), filePath);
            matchedByCanonicalPath = partByPath != null;
        }
        if (partByPath != null && partByPath.getHistoryId() != null && !partByPath.getHistoryId().equals(room.getHistoryId())) {
            log.info("[BLR] {}", LogKvs.event("FileClosed.HistoryRecovered.ByPart")
                    .add("roomId", eventData.getRoomId())
                    .add("oldHistoryId", room.getHistoryId())
                    .add("newHistoryId", partByPath.getHistoryId())
                    .add("partId", partByPath.getId())
                    .add("filePath", relativePath));
            room.setHistoryId(partByPath.getHistoryId());
            roomRepository.save(room);
        }
        Optional<RecordHistory> historyOptional = historyRepository.findById(room.getHistoryId());
        if (!historyOptional.isPresent()) {
            if (partByPath != null && partByPath.getHistoryId() != null) {
                Optional<RecordHistory> historyFromPart = historyRepository.findById(partByPath.getHistoryId());
                if (historyFromPart.isPresent()) {
                    if (!partByPath.getHistoryId().equals(room.getHistoryId())) {
                        log.info("[BLR] {}", LogKvs.event("FileClosed.HistoryRecovered.ByPart")
                                .add("roomId", eventData.getRoomId())
                                .add("oldHistoryId", room.getHistoryId())
                                .add("newHistoryId", partByPath.getHistoryId())
                                .add("partId", partByPath.getId())
                                .add("filePath", relativePath));
                        room.setHistoryId(partByPath.getHistoryId());
                        roomRepository.save(room);
                    }
                    historyOptional = historyFromPart;
                }
            }
        }
        if (!historyOptional.isPresent()) {
            List<RecordHistory> activeHistoryList = historyRepository.findByRoomIdAndRecordingTrueOrderByStartTimeDesc(eventData.getRoomId());
            if (activeHistoryList != null && !activeHistoryList.isEmpty()) {
                RecordHistory active = activeHistoryList.get(0);
                if (active != null) {
                    log.warn("[BLR] {}", LogKvs.event("FileClosed.HistoryRecovered.ByActiveHistory")
                            .add("roomId", eventData.getRoomId())
                            .add("oldHistoryId", room.getHistoryId())
                            .add("newHistoryId", active.getId())
                            .add("filePath", relativePath));
                    room.setHistoryId(active.getId());
                    roomRepository.save(room);
                    historyOptional = Optional.of(active);
                }
            }
        }
        if (historyOptional.isPresent()) {
            RecordHistory history = historyOptional.get();
            // 正常逻辑
            if (!Objects.equals(history.getRoomId(), room.getRoomId()) || history.isForceArchived()) {
                if (history.isForceArchived() && Objects.equals(room.getHistoryId(), history.getId())) {
                    room.setHistoryId(-1L);
                    room.setSessionId(null);
                    room.setRecording(false);
                    room.setStreaming(false);
                    roomRepository.save(room);
                }
                log.info("[BLR] {}", LogKvs.event("FileClosed.SkipHistoryUpdate")
                        .add("roomId", eventData.getRoomId())
                        .add("historyId", history.getId())
                        .add("forceArchived", history.isForceArchived())
                        .add("filePath", relativePath));
                return;
            }
            RecordHistoryPart part = partByPath != null && Objects.equals(partByPath.getHistoryId(), history.getId())
                    ? partByPath
                    : findByCanonicalPath(historyPartRepository.findByHistoryId(history.getId()), filePath);
            if (part != null && matchedByCanonicalPath) {
                log.info("[BLR] {}", LogKvs.event("FileClosed.PartMatchedNormalizedPath")
                        .add("roomId", eventData.getRoomId())
                        .add("historyId", history.getId())
                        .add("partId", part.getId())
                        .add("filePath", relativePath));
            }
            if (part == null) {
                log.info("[BLR] {}", LogKvs.event("FileClosed.PartMissing")
                        .add("roomId", eventData.getRoomId())
                        .add("filePath", relativePath));
                part = new RecordHistoryPart();
                part.setStartTime(LocalDateTime.now().minusSeconds((long) eventData.getDuration()));
                part.setEventId(event.getEventId());
                part.setTitle(LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM月dd日HH点mm分ss秒")));
                part.setLiveTitle(eventData.getTitle());
                part.setAreaName(eventData.getAreaNameChild());
                part.setRoomId(history.getRoomId());
                part.setHistoryId(history.getId());
                part.setFilePath(filePath);
                part.setFileSize(0L);
                part.setPartOrder(historyPartRepository.countByHistoryId(history.getId()) + 1);
                part.setSessionId(sessionId);
                part.setRecording(eventData.isRecording());
                // startTime 优先使用 fileOpenTime（blrec 也可能提供），避免 duration=0 时 startTime=now 导致计算结果为 0
                if (eventData.getFileOpenTime() != null) {
                    try {
                        part.setStartTime(LocalDateTime.ofInstant(eventData.getFileOpenTime().toInstant(), ZoneId.of("Asia/Shanghai")));
                    } catch (Exception ignored) {
                    }
                }
                part.setEndTime(LocalDateTime.now());
            }
            File vidleFile = new File(filePath);
            long fileSize = 0;
            if (vidleFile.exists()) {
                fileSize = vidleFile.length();
            } else {
                log.error("[BLR] {}", LogKvs.event("FileClosed.FileMissing")
                        .add("roomId", eventData.getRoomId())
                        .add("filePath", filePath)
                        .add("hint", "check work-path or docker volume mapping"));
                fileSize = eventData.getFileSize();
            }
            LocalDateTime startTime = part.getStartTime();
            LocalDateTime endTime = LocalDateTime.now();
            long durationSeconds = 0L;
            if (startTime != null) {
                try {
                    durationSeconds = Duration.between(startTime, endTime).getSeconds();
                    if (durationSeconds < 0) {
                        durationSeconds = 0L;
                    }
                } catch (Exception ignored) {
                }
            }
            part.setRecording(false);
            part.setFileSize(fileSize);
            float durationFromEvent = eventData.getDuration();
            float durationToSave = durationFromEvent > 0.0f ? durationFromEvent : (float) durationSeconds;
            part.setDuration(durationToSave);
            part.setEndTime(endTime);
            part.setAreaName(eventData.getAreaNameChild());
            part.setUpdateTime(LocalDateTime.now());
            part = historyPartRepository.save(part);
            partFileLocationService.registerPrimary(part);

            history.setFileSize(history.getFileSize() + part.getFileSize());
            history.setTitle(eventData.getTitle());
            history.setSessionId(sessionId);
            history.setRecording(eventData.isRecording());
            history.setStreaming(eventData.isStreaming());
            history.setUpdateTime(LocalDateTime.now());
            history.setEndTime(LocalDateTime.now());
            history = historyRepository.save(history);
            statsAggregationService.refreshHistoryStatsAsync(history.getId());
            if (!vidleFile.exists()) {
                return;
            }
            if (StringUtils.isNotBlank(room.getMoveDir())
                    && partFileCleanupPolicy.isPostRecordCloseCleanupType(room.getDeleteType())) {
                Long id = part.getId();
                if (!shutdownState.isShuttingDown()) {
                    Long historyId = history.getId();
                    long closedFileSize = fileSize;
                    taskExecutor.execute(() -> {
                        if (shutdownState.isShuttingDown() || Thread.currentThread().isInterrupted()) return;
                        RecordHistoryPart currentPart = historyPartRepository.findById(id).orElse(null);
                        RecordHistory currentHistory = historyRepository.findById(historyId).orElse(null);
                        if (currentPart == null || currentHistory == null) return;
                        if (!partFileCleanupPolicy.shouldSkipProtectedArchive(room, currentHistory, currentPart,
                                currentPart.getFilePath(), "FileClosed", "postRecordCleanup")) {
                            if (room.getDeleteType() == 6) partFileOperationService.move(id, room.getMoveDir());
                            else partFileOperationService.copy(id, room.getMoveDir());
                        }
                        currentPart = historyPartRepository.findById(id).orElse(currentPart);
                        startUploadOrMarkSkipped(room, currentHistory, currentPart, closedFileSize);
                    });
                }
                return;
            }
            startUploadOrMarkSkipped(room, history, part, fileSize);
        } else {
                log.error("[BLR] {}", LogKvs.event("FileClosed.MissingHistory")
                    .add("roomId", eventData.getRoomId())
                    .add("title", eventData.getTitle())
                    .add("filePath", relativePath)
                    .add("historyId", room.getHistoryId()));
            RecordHistoryPart part = new RecordHistoryPart();
            part.setStartTime(LocalDateTime.now().minusSeconds((long) eventData.getDuration()));
            part.setEventId(event.getEventId());
            part.setTitle(LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM月dd日HH点mm分ss秒")));
            part.setLiveTitle(eventData.getTitle());
            part.setAreaName(eventData.getAreaNameChild());
            part.setRoomId(eventData.getRoomId());
            part.setFilePath(filePath);
            part.setFileSize(0L);
            part.setSessionId(sessionId);
            part.setRecording(eventData.isRecording());
            part.setEndTime(LocalDateTime.now());
            part = historyPartRepository.save(part);
            partFileLocationService.registerPrimary(part);
        }

    }

    private void startUploadOrMarkSkipped(RecordRoom room, RecordHistory history,
                                          RecordHistoryPart part, long fileSize) {
        if (fileSize > 1024 * 1024 * room.getFileSizeLimit() && part.getDuration() > room.getDurationLimit()) {
            if (!shutdownState.isShuttingDown()) {
                uploadServiceFactory.getUploadService(room.getLine()).asyncUpload(part);
            }
            return;
        }
        double fileSizeMb = fileSize / 1024d / 1024d;
        double durationSec = part.getDuration();
        log.info("[BLR] {}", LogKvs.event("Upload.SkipBelowThreshold")
                .add("roomId", room.getRoomId()).add("uname", room.getUname())
                .add("historyId", history.getId()).add("bvid", history.getBvId())
                .add("partId", part.getId())
                .add("fileSizeMb", String.format(java.util.Locale.ROOT, "%.3f", fileSizeMb))
                .add("limitMb", room.getFileSizeLimit())
                .add("durationSec", String.format(java.util.Locale.ROOT, "%.3f", durationSec))
                .add("limitSec", room.getDurationLimit()));
        part.setUpload(false);
        part.setUploadRetryCount(9999);
        part.setDeleteFailType("SKIPPED_THRESHOLD");
        part.setDeleteFailReason("文件低于阈值(大小/时长)已跳过上传，可在前端手动补救");
        historyPartRepository.save(part);
    }

    private RecordHistoryPart findByCanonicalPath(Iterable<RecordHistoryPart> parts, String filePath) {
        if (parts == null) {
            return null;
        }
        for (RecordHistoryPart part : parts) {
            if (part != null && partPathService.sameFile(part.getFilePath(), filePath)) {
                return part;
            }
        }
        return null;
    }
}
