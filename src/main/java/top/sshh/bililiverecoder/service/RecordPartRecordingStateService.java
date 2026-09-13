package top.sshh.bililiverecoder.service;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一判断还在录制的分P，文件稳不稳定要连续观察，不能只看一次旧的修改时间
 */
@Service
public class RecordPartRecordingStateService {
    public enum State { ENDED, RECORDING, WAITING_STABLE, READY_TO_CLOSE, FILE_UNAVAILABLE }

    public record Assessment(State state, String message, boolean activeSession, long stableForMs,
                             long requiredStableMs, long fileSize, long fileModifiedAt) {
        public boolean readyToClose() { return state == State.READY_TO_CLOSE; }
    }

    private record Observation(String path, long size, long modifiedAt, long observedAt, long stableSince) { }

    private static final long OBSERVATION_GAP_MS = 2L * 60L * 1000L;
    private final Map<Long, Observation> observations = new ConcurrentHashMap<>();
    private final PartFileLocationService locationService;
    private final RecordHistoryPartRepository partRepository;
    private final RecordHistoryRepository historyRepository;
    private final RecordRoomRepository roomRepository;

    @Value("${record.part-stable-threshold-ms:600000}")
    private long endedSessionStableThresholdMs = 10L * 60L * 1000L;

    @Value("${record.part-active-stable-threshold-ms:1800000}")
    private long activeSessionStableThresholdMs = 30L * 60L * 1000L;

    public RecordPartRecordingStateService(PartFileLocationService locationService,
                                            RecordHistoryPartRepository partRepository,
                                            RecordHistoryRepository historyRepository,
                                            RecordRoomRepository roomRepository) {
        this.locationService = locationService;
        this.partRepository = partRepository;
        this.historyRepository = historyRepository;
        this.roomRepository = roomRepository;
    }

    public Assessment assess(RecordHistoryPart part) {
        if (part == null || (!part.isRecording() && part.getEndTime() != null)) {
            return new Assessment(State.ENDED, "分P已结束", false, 0, 0, 0, 0);
        }
        RecordHistory history = part.getHistoryId() == null ? null : historyRepository.findById(part.getHistoryId()).orElse(null);
        RecordRoom room = roomRepository.findByRoomId(part.getRoomId());
        boolean activeSession = isCurrentActiveSession(room, history, part);
        long required = Math.max(0L, activeSession ? activeSessionStableThresholdMs : endedSessionStableThresholdMs);
        if (activeSession && !part.isRecording() && part.getEndTime() == null) {
            // 数据对不上时，还是按活动会话的较长等待时间处理
            activeSession = true;
        }
        PartFileLocationService.FileResolution resolution = locationService.resolveReadable(part.getId());
        if (!resolution.available() || resolution.path() == null) {
            observations.remove(part.getId());
            return new Assessment(State.FILE_UNAVAILABLE, resolution.state() == PartFileLocationService.LocalFileState.ROOT_OFFLINE
                    ? "存储目录离线，暂时无法确认文件状态" : "本地文件不可读，暂时无法确认文件状态",
                    activeSession, 0, required, 0, 0);
        }
        try {
            Path path = resolution.path().toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                observations.remove(part.getId());
                return new Assessment(State.FILE_UNAVAILABLE, "本地文件不存在，暂时无法确认文件状态", activeSession, 0, required, 0, 0);
            }
            long size = Files.size(path);
            long modifiedAt = Files.getLastModifiedTime(path).toMillis();
            if (size <= 0 || modifiedAt <= 0) {
                observations.remove(part.getId());
                return new Assessment(State.WAITING_STABLE, "文件尚未写入有效内容，等待下一次检查", activeSession, 0, required, size, modifiedAt);
            }
            long now = System.currentTimeMillis();
            Observation previous = observations.get(part.getId());
            Observation current;
            if (previous == null || !previous.path.equals(path.toString()) || previous.size != size
                    || previous.modifiedAt != modifiedAt || now - previous.observedAt > OBSERVATION_GAP_MS) {
                current = new Observation(path.toString(), size, modifiedAt, now, now);
                observations.put(part.getId(), current);
                return new Assessment(activeSession ? State.RECORDING : State.WAITING_STABLE,
                        activeSession ? "分P仍在录制，已开始观察文件变化" : "等待文件连续稳定后收尾",
                        activeSession, 0, required, size, modifiedAt);
            }
            current = new Observation(previous.path, size, modifiedAt, now, previous.stableSince);
            observations.put(part.getId(), current);
            long stableFor = now - current.stableSince;
            if (stableFor < required) {
                return new Assessment(activeSession ? State.RECORDING : State.WAITING_STABLE,
                        activeSession ? "分P仍在录制，文件稳定观察中" : "等待文件连续稳定后收尾",
                        activeSession, stableFor, required, size, modifiedAt);
            }
            return new Assessment(State.READY_TO_CLOSE, "文件已连续稳定，可自动收尾", activeSession,
                    stableFor, required, size, modifiedAt);
        } catch (Exception e) {
            observations.remove(part.getId());
            return new Assessment(State.FILE_UNAVAILABLE, "读取本地文件失败，暂时无法确认文件状态", activeSession, 0, required, 0, 0);
        }
    }

    /** 后台补偿或手动重试时调用，连续观察还没满足条件就不改状态 */
    @Transactional
    public boolean closeIfReady(Long partId, String trigger) {
        RecordHistoryPart part = partRepository.findById(partId).orElse(null);
        if (part == null || (!part.isRecording() && part.getEndTime() != null)) return false;
        synchronized ((part.getRoomId() == null ? "unknown" : part.getRoomId()).intern()) {
            part = partRepository.findById(partId).orElse(null);
            if (part == null || (!part.isRecording() && part.getEndTime() != null)) return false;
            Assessment assessment = assess(part);
            if (!assessment.readyToClose()) return false;
            LocalDateTime observedChange = Instant.ofEpochMilli(assessment.fileModifiedAt())
                    .atZone(ZoneId.systemDefault()).toLocalDateTime();
            LocalDateTime endTime = observedChange;
            if (part.getStartTime() != null && endTime.isBefore(part.getStartTime())) endTime = part.getStartTime();
            part.setRecording(false);
            if (part.getEndTime() == null) part.setEndTime(endTime);
            part.setFileSize(assessment.fileSize());
            part.setUpdateTime(LocalDateTime.now());
            part.setCloseSource("STABLE_FALLBACK");
            part.setAutoCloseAt(LocalDateTime.now());
            part.setAutoCloseFileSize(assessment.fileSize());
            part.setAutoCloseFileModifiedAt(assessment.fileModifiedAt());
            partRepository.save(part);
            closeHistoryWhenSessionCompleted(part);
            return true;
        }
    }

    /**
     * 自动收尾过的文件，上传或投稿前要再检查有没有变化，文件又增长了就不能复用原来的上传结果
     */
    @Transactional
    public boolean verifyAutoClosedFiles(RecordHistory history) {
        if (history == null) return false;
        boolean valid = true;
        for (RecordHistoryPart part : partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId())) {
            if (!"STABLE_FALLBACK".equals(part.getCloseSource()) || part.getAutoCloseFileSize() == null
                    || part.getAutoCloseFileModifiedAt() == null) continue;
            try {
                PartFileLocationService.FileResolution resolution = locationService.resolveReadable(part.getId());
                if (!resolution.available() || resolution.path() == null || !Files.isRegularFile(resolution.path())) {
                    valid = false;
                    continue;
                }
                long size = Files.size(resolution.path());
                long modifiedAt = Files.getLastModifiedTime(resolution.path()).toMillis();
                if (size == part.getAutoCloseFileSize() && modifiedAt == part.getAutoCloseFileModifiedAt()) continue;
                valid = false;
                if (history.isPublish()) {
                    part.setDeleteFailType("AUTO_CLOSE_FILE_CHANGED");
                    part.setDeleteFailReason("自动收尾后文件再次变化，已投稿稿件不会自动覆盖，请人工检查");
                    part.setUpdateTime(LocalDateTime.now());
                    partRepository.save(part);
                    continue;
                }
                part.setRecording(true);
                part.setEndTime(null);
                part.setUpload(false);
                part.setFileName(null);
                part.setCid(null);
                part.setCloseSource(null);
                part.setAutoCloseAt(null);
                part.setAutoCloseFileSize(null);
                part.setAutoCloseFileModifiedAt(null);
                part.setUpdateTime(LocalDateTime.now());
                partRepository.save(part);
                observations.remove(part.getId());
            } catch (Exception ignored) {
                valid = false;
            }
        }
        return valid;
    }

    private void closeHistoryWhenSessionCompleted(RecordHistoryPart part) {
        if (part.getHistoryId() == null || StringUtils.isBlank(part.getRoomId())) return;
        RecordHistory history = historyRepository.findById(part.getHistoryId()).orElse(null);
        RecordRoom room = roomRepository.findByRoomId(part.getRoomId());
        if (history == null || room == null || history.isForceArchived() || history.isPublish()
                || !history.isUpload() || !part.getHistoryId().equals(room.getHistoryId())
                || (StringUtils.isNotBlank(part.getSessionId()) && StringUtils.isNotBlank(room.getSessionId())
                    && !part.getSessionId().equals(room.getSessionId()))
                || partRepository.countActuallyRecordingPartsByHistoryId(history.getId()) > 0) return;
        LocalDateTime now = LocalDateTime.now();
        history.setRecording(false);
        history.setStreaming(false);
        history.setEndTime(now);
        history.setUpdateTime(now);
        history.setCloseSource("STABLE_FALLBACK");
        history.setCloseAt(now);
        historyRepository.save(history);
        room.setRecording(false);
        room.setStreaming(false);
        room.setSessionId(null);
        room.setUpdateTime(now);
        roomRepository.save(room);
    }

    private boolean isCurrentActiveSession(RecordRoom room, RecordHistory history, RecordHistoryPart part) {
        if (room == null || history == null || !room.isRecording()
                || !part.getHistoryId().equals(room.getHistoryId())) return false;
        return StringUtils.isBlank(part.getSessionId()) || StringUtils.isBlank(room.getSessionId())
                || part.getSessionId().equals(room.getSessionId());
    }
}
