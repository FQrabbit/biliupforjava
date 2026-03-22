package top.sshh.bililiverecoder.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
public class WorkspaceUsageService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Value("${record.work-path}")
    private String workPath;

    @Value("${record.workspace-usage-alert-threshold:95}")
    private int alertThresholdPercent;

    private final UploadUserSerialScheduler uploadUserSerialScheduler;
    private volatile WorkspaceUsageSnapshot latestSnapshot;

    public WorkspaceUsageService(UploadUserSerialScheduler uploadUserSerialScheduler) {
        this.uploadUserSerialScheduler = uploadUserSerialScheduler;
    }

    @PostConstruct
    public void init() {
        refreshSnapshotSafely();
    }

    @Scheduled(
            fixedDelayString = "${record.workspace-usage-check-interval-ms:60000}",
            initialDelayString = "${record.workspace-usage-check-initial-delay-ms:5000}"
    )
    public void refreshScheduled() {
        refreshSnapshotSafely();
    }

    public Map<String, Object> getLatestSnapshot() {
        WorkspaceUsageSnapshot snapshot = latestSnapshot;
        if (snapshot == null) {
            refreshSnapshotSafely();
            snapshot = latestSnapshot;
        }
        return snapshot == null ? WorkspaceUsageSnapshot.empty(workPath, alertThresholdPercent).toMap() : snapshot.toMap();
    }

    private void refreshSnapshotSafely() {
        try {
            this.latestSnapshot = collectSnapshot();
        } catch (Exception e) {
            log.warn("[BLR] collect workspace usage failed, workPath={}, err={}", workPath, e.getMessage());
            this.latestSnapshot = WorkspaceUsageSnapshot.error(workPath, alertThresholdPercent, e.getMessage());
        }
    }

    private WorkspaceUsageSnapshot collectSnapshot() throws Exception {
        String normalizedWorkPath = normalizeWorkPath(workPath);
        Path work = Paths.get(normalizedWorkPath).toAbsolutePath().normalize();
        Path probePath = pickExistingPath(work);
        FileStore store = Files.getFileStore(probePath);

        long totalBytes = store.getTotalSpace();
        long freeBytes = store.getUsableSpace();
        long usedBytes = Math.max(0L, totalBytes - freeBytes);
        double usedPercent = totalBytes <= 0L ? 0.0d : (usedBytes * 100.0d / totalBytes);
        usedPercent = round2(usedPercent);

        boolean alert = usedPercent >= alertThresholdPercent;
        int pendingUploadCount = uploadUserSerialScheduler.getTotalPendingUploadCount();
        return WorkspaceUsageSnapshot.success(
                normalizedWorkPath,
                probePath.toString(),
                totalBytes,
                usedBytes,
                freeBytes,
                usedPercent,
                alertThresholdPercent,
                alert,
                pendingUploadCount
        );
    }

    private static Path pickExistingPath(Path original) {
        Path current = original;
        while (current != null && !Files.exists(current)) {
            current = current.getParent();
        }
        if (current != null) {
            return current;
        }
        Path root = original.getRoot();
        if (root != null) {
            return root;
        }
        Path cwd = Paths.get(".").toAbsolutePath().normalize();
        if (Files.exists(cwd)) {
            return cwd;
        }
        Path cwdRoot = cwd.getRoot();
        return cwdRoot != null ? cwdRoot : Paths.get("/");
    }

    private static String normalizeWorkPath(String rawPath) {
        String value = rawPath == null ? "" : rawPath;
        value = value.replace("\\\\", "\\");
        return value.replace("\\", "/").trim();
    }

    private static double round2(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    private static final class WorkspaceUsageSnapshot {
        private final boolean valid;
        private final String workPath;
        private final String probePath;
        private final long totalBytes;
        private final long usedBytes;
        private final long freeBytes;
        private final double usedPercent;
        private final int alertThresholdPercent;
        private final boolean alert;
        private final int pendingUploadCount;
        private final String updatedAt;
        private final String error;

        private WorkspaceUsageSnapshot(boolean valid,
                                       String workPath,
                                       String probePath,
                                       long totalBytes,
                                       long usedBytes,
                                       long freeBytes,
                                       double usedPercent,
                                       int alertThresholdPercent,
                                       boolean alert,
                                       int pendingUploadCount,
                                       String updatedAt,
                                       String error) {
            this.valid = valid;
            this.workPath = workPath;
            this.probePath = probePath;
            this.totalBytes = totalBytes;
            this.usedBytes = usedBytes;
            this.freeBytes = freeBytes;
            this.usedPercent = usedPercent;
            this.alertThresholdPercent = alertThresholdPercent;
            this.alert = alert;
            this.pendingUploadCount = pendingUploadCount;
            this.updatedAt = updatedAt;
            this.error = error;
        }

        private static WorkspaceUsageSnapshot success(String workPath,
                                                      String probePath,
                                                      long totalBytes,
                                                      long usedBytes,
                                                      long freeBytes,
                                                      double usedPercent,
                                                      int alertThresholdPercent,
                                                      boolean alert,
                                                      int pendingUploadCount) {
            return new WorkspaceUsageSnapshot(
                    true,
                    workPath,
                    probePath,
                    totalBytes,
                    usedBytes,
                    freeBytes,
                    usedPercent,
                    alertThresholdPercent,
                    alert,
                    pendingUploadCount,
                    LocalDateTime.now().format(TIME_FMT),
                    null
            );
        }

        private static WorkspaceUsageSnapshot error(String workPath, int alertThresholdPercent, String error) {
            return new WorkspaceUsageSnapshot(
                    false,
                    workPath,
                    null,
                    0L,
                    0L,
                    0L,
                    0.0d,
                    alertThresholdPercent,
                    false,
                    0,
                    LocalDateTime.now().format(TIME_FMT),
                    error
            );
        }

        private static WorkspaceUsageSnapshot empty(String workPath, int alertThresholdPercent) {
            return new WorkspaceUsageSnapshot(
                    false,
                    workPath,
                    null,
                    0L,
                    0L,
                    0L,
                    0.0d,
                    alertThresholdPercent,
                    false,
                    0,
                    LocalDateTime.now().format(TIME_FMT),
                    "snapshot not ready"
            );
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("valid", valid);
            map.put("workPath", workPath);
            map.put("probePath", probePath);
            map.put("totalBytes", totalBytes);
            map.put("usedBytes", usedBytes);
            map.put("freeBytes", freeBytes);
            map.put("usedPercent", usedPercent);
            map.put("alertThresholdPercent", alertThresholdPercent);
            map.put("alert", alert);
            map.put("pendingUploadCount", pendingUploadCount);
            map.put("updatedAt", updatedAt);
            map.put("error", error);
            return map;
        }
    }
}
