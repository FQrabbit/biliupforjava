package top.sshh.bililiverecoder.service.impl;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.repo.BiliUserRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.service.RecordEventService;
import top.sshh.bililiverecoder.service.UploadServiceFactory;
import top.sshh.bililiverecoder.util.LogKvs;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Slf4j
@Component
public class RecordEventFileClosedService implements RecordEventService {

    @Value("${record.work-path}")
    private String workPath;

    @Autowired
    private BiliUserRepository biliUserRepository;

    @Autowired
    private RecordRoomRepository roomRepository;

    @Autowired
    private RecordHistoryRepository historyRepository;

    @Autowired
    private RecordHistoryPartRepository historyPartRepository;

    @Autowired
    private UploadServiceFactory uploadServiceFactory;

    @Autowired
    private LiveMsgService liveMsgService;

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
        Optional<RecordHistory> historyOptional = historyRepository.findById(room.getHistoryId());
        if ("blrec".equals(sessionId)) {
            relativePath = relativePath.replace(workPath, "");
        }
        String filePath = workPath + File.separator + relativePath;
        if (historyOptional.isPresent()) {
            RecordHistory history = historyOptional.get();
            // 正常逻辑
            RecordHistoryPart part = historyPartRepository.findByFilePath(filePath);
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

            history.setFileSize(history.getFileSize() + part.getFileSize());
            history.setTitle(eventData.getTitle());
            history.setSessionId(sessionId);
            history.setRecording(eventData.isRecording());
            history.setStreaming(eventData.isStreaming());
            history.setUpdateTime(LocalDateTime.now());
            history.setEndTime(LocalDateTime.now());
            history = historyRepository.save(history);
            if (!vidleFile.exists()) {
                return;
            }
            if (StringUtils.isNotBlank(room.getMoveDir()) && (room.getDeleteType() == 6 || room.getDeleteType() == 7)) {
                String fileName = filePath.substring(filePath.lastIndexOf("/") + 1, filePath.lastIndexOf("."));
                String startDirPath = filePath.substring(0, filePath.lastIndexOf('/') + 1);
                String toDirPath = room.getMoveDir() + filePath.substring(0, filePath.lastIndexOf('/') + 1).replace(workPath, "");
                File toDir = new File(toDirPath);
                if (!toDir.exists()) {
                    toDir.mkdirs();
                }
                Long id = part.getId();
                new Thread(() -> {
                    RecordHistoryPart part2 = historyPartRepository.findById(id).get();
                    File startDir = new File(startDirPath);
                    File[] files = startDir.listFiles((file, s) -> s.startsWith(fileName));
                    if (files != null) {
                        for (File file : files) {
                            if (!filePath.startsWith(workPath)) {
                                part2.setFileDelete(true);
                                part2 = historyPartRepository.save(part2);
                                continue;
                            }
                            if (room.getDeleteType() == 6) {
                                try {
                                    Files.move(Paths.get(file.getPath()), Paths.get(toDirPath + file.getName()),
                                            StandardCopyOption.REPLACE_EXISTING);
                                        log.info("[BLR] {}", LogKvs.event("FileClosed.MoveSuccess")
                                            .add("roomId", room.getRoomId())
                                            .add("uname", room.getUname())
                                            .add("fileName", file.getName())
                                            .add("toDir", toDirPath));
                                } catch (Exception e) {
                                        log.error("[BLR] {}", LogKvs.event("FileClosed.MoveFailed")
                                            .add("roomId", room.getRoomId())
                                            .add("uname", room.getUname())
                                            .add("fileName", file.getName())
                                            .add("toDir", toDirPath)
                                            .add("err", e.getMessage()), e);
                                }
                            } else if (room.getDeleteType() == 7) {
                                try {
                                    Files.copy(Paths.get(file.getPath()), Paths.get(toDirPath + file.getName()),
                                            StandardCopyOption.REPLACE_EXISTING);
                                        log.info("[BLR] {}", LogKvs.event("FileClosed.CopySuccess")
                                            .add("roomId", room.getRoomId())
                                            .add("uname", room.getUname())
                                            .add("fileName", file.getName())
                                            .add("toDir", toDirPath));
                                } catch (Exception e) {
                                        log.error("[BLR] {}", LogKvs.event("FileClosed.CopyFailed")
                                            .add("roomId", room.getRoomId())
                                            .add("uname", room.getUname())
                                            .add("fileName", file.getName())
                                            .add("toDir", toDirPath)
                                            .add("err", e.getMessage()), e);
                                }
                            }

                        }
                    }

                    part2.setFilePath(toDirPath + filePath.substring(filePath.lastIndexOf("/") + 1));
                    part2.setFileDelete(true);
                    part2 = historyPartRepository.save(part2);
                }).start();
            }
            // 文件上传操作
            //开始上传该视频分片，异步上传任务。
            // 小于设定文件大小和时长不上传
            if (fileSize > 1024 * 1024 * room.getFileSizeLimit() && part.getDuration() > room.getDurationLimit()) {
                uploadServiceFactory.getUploadService(room.getLine()).asyncUpload(part);
            } else {
                double fileSizeMb = fileSize / 1024d / 1024d;
                double durationSec = part.getDuration();
                log.info("[BLR] {}", LogKvs.event("Upload.SkipBelowThreshold")
                    .add("roomId", room.getRoomId())
                    .add("uname", room.getUname())
                    .add("historyId", history.getId())
                    .add("bvid", history.getBvId())
                    .add("partId", part.getId())
                    .add("fileSizeMb", String.format(java.util.Locale.ROOT, "%.3f", fileSizeMb))
                    .add("limitMb", room.getFileSizeLimit())
                    .add("durationSec", String.format(java.util.Locale.ROOT, "%.3f", durationSec))
                    .add("limitSec", room.getDurationLimit()));
                historyPartRepository.delete(part);
                return;
            }
        } else {
                log.error("[BLR] {}", LogKvs.event("FileClosed.MissingHistory")
                    .add("roomId", eventData.getRoomId())
                    .add("title", eventData.getTitle())
                    .add("filePath", relativePath));
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
            part.setStartTime(LocalDateTime.now());
            part.setEndTime(LocalDateTime.now());
            historyPartRepository.save(part);
        }

    }
}
