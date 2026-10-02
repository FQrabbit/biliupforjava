package top.sshh.bililiverecoder.service;

import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 统一检查首次投稿前的录制状态和短时开播合并等待 */
@Service
public class PublishReadinessService {
    private final RecordHistoryPartRepository parts;
    private final SystemConfigService configs;

    public PublishReadinessService(RecordHistoryPartRepository parts, SystemConfigService configs) {
        this.parts = parts;
        this.configs = configs;
    }

    public int mergeIntervalMinutes() {
        return mergeIntervalMinutes(configs.getAllConfigsMap());
    }

    public static int mergeIntervalMinutes(Map<String, String> config) {
        String value = config.get(SystemConfigService.KEY_MERGE_INTERVAL_MINUTES);
        try {
            return value == null || value.isBlank() ? 20 : Math.max(1, Math.min(1440, Integer.parseInt(value)));
        } catch (NumberFormatException ignored) {
            return 20;
        }
    }

    public Check check(RecordHistory history) {
        if (history.isPublish() || hasOnlineIdentity(history)) return Check.ready();
        List<RecordHistoryPart> all = parts.findByHistoryIdOrderByStartTimeAsc(history.getId());
        int recordingCount = (int) all.stream().filter(p -> p.isRecording() || p.getEndTime() == null).count();
        LocalDateTime latestEnd = all.stream().map(RecordHistoryPart::getEndTime)
                .filter(java.util.Objects::nonNull).max(LocalDateTime::compareTo).orElse(null);
        return check(history, recordingCount, latestEnd, configs.getAllConfigsMap(), LocalDateTime.now());
    }

    // 列表使用已有的批量统计，避免每个稿件再单独查一次分P
    public Check check(RecordHistory history, int recordingCount, LocalDateTime latestEnd,
                       Map<String, String> config, LocalDateTime now) {
        if (history.isPublish() || hasOnlineIdentity(history)) return Check.ready();
        if (history.isRecording() || history.isStreaming() || recordingCount > 0) {
            return new Check(false, "RECORDING", "录制中，暂不能投稿；录制结束后会按合并等待设置继续处理", null);
        }
        LocalDateTime end = history.getEndTime();
        if (latestEnd != null && (end == null || latestEnd.isAfter(end))) end = latestEnd;
        if (end == null) {
            return new Check(false, "RECORDING_END_UNKNOWN", "尚未确认录制结束时间，请刷新状态或检查录制记录", null);
        }
        LocalDateTime earliest = end.plusMinutes(mergeIntervalMinutes(config));
        if (now.isBefore(earliest)) {
            return new Check(false, "MERGE_INTERVAL", "等待短时开播合并，合并等待结束后才能投稿", earliest);
        }
        return Check.ready();
    }

    private static boolean hasOnlineIdentity(RecordHistory history) {
        return (history.getBvId() != null && !history.getBvId().isBlank())
                || (history.getAvId() != null && !history.getAvId().isBlank());
    }

    public record Check(boolean allowed, String reason, String message, LocalDateTime earliestAt) {
        public static Check ready() { return new Check(true, null, null, null); }
    }

    public static class Deferred extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final Check check;

        public Deferred(Check check) {
            super(check.message());
            this.check = check;
        }

        public Check check() { return check; }
    }
}
