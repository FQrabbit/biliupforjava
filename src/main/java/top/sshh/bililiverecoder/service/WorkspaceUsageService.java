package top.sshh.bililiverecoder.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.notification.NotificationEvent;
import top.sshh.bililiverecoder.notification.NotificationEventPublisher;
import top.sshh.bililiverecoder.notification.NotificationEventType;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.util.TaskUtil;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

@Slf4j
@Service
public class WorkspaceUsageService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Value("${record.work-path}")
    private String workPath;

    @Value("${record.workspace-usage-alert-threshold:90}")
    private int alertThresholdPercent;

    private final UploadUserSerialScheduler uploadUserSerialScheduler;
    private final RecordHistoryPartRepository partRepository;
    private final DataSource dataSource;
    private final Environment environment;
    private final NotificationEventPublisher notificationEventPublisher;
    private final SystemConfigService systemConfigService;
    private volatile WorkspaceUsageSnapshot latestSnapshot;

    public WorkspaceUsageService(UploadUserSerialScheduler uploadUserSerialScheduler,
                                 RecordHistoryPartRepository partRepository,
                                 DataSource dataSource,
                                 Environment environment,
                                 NotificationEventPublisher notificationEventPublisher,
                                 SystemConfigService systemConfigService) {
        this.uploadUserSerialScheduler = uploadUserSerialScheduler;
        this.partRepository = partRepository;
        this.dataSource = dataSource;
        this.environment = environment;
        this.notificationEventPublisher = notificationEventPublisher;
        this.systemConfigService = systemConfigService;
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
        return snapshot == null ? WorkspaceUsageSnapshot.empty(workPath, currentAlertThresholdPercent()).toMap() : snapshot.toMap();
    }

    private void refreshSnapshotSafely() {
        try {
            WorkspaceUsageSnapshot previous = this.latestSnapshot;
            WorkspaceUsageSnapshot snapshot = collectSnapshot();
            this.latestSnapshot = snapshot;
            if (shouldPublishWorkspaceAlert(previous, snapshot)) {
                publishWorkspaceAlert(snapshot);
            }
        } catch (Exception e) {
            log.warn("[BLR] collect workspace usage failed, workPath={}, err={}", workPath, e.getMessage());
            this.latestSnapshot = WorkspaceUsageSnapshot.error(workPath, currentAlertThresholdPercent(), e.getMessage());
        }
    }

    private boolean shouldPublishWorkspaceAlert(WorkspaceUsageSnapshot previous, WorkspaceUsageSnapshot current) {
        return current != null
                && current.valid
                && current.alert
                && (previous == null || !previous.alert);
    }

    private void publishWorkspaceAlert(WorkspaceUsageSnapshot snapshot) {
        NotificationEvent event = new NotificationEvent();
        event.setEventType(NotificationEventType.WORKSPACE_USAGE_ALERT);
        event.add("workPath", snapshot.workPath)
                .add("probePath", snapshot.probePath)
                .add("usedPercent", snapshot.usedPercent)
                .add("alertThresholdPercent", snapshot.alertThresholdPercent)
                .add("totalBytes", snapshot.totalBytes)
                .add("usedBytes", snapshot.usedBytes)
                .add("freeBytes", snapshot.freeBytes)
                .add("totalSize", formatBytes(snapshot.totalBytes))
                .add("freeSize", formatBytes(snapshot.freeBytes))
                .add("pendingUploadCount", snapshot.pendingUploadCount)
                .add("queuedUploadCount", snapshot.queuedUploadCount)
                .add("activeUploadCount", snapshot.activeUploadCount);
        notificationEventPublisher.publish(event, null);
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

        int currentAlertThresholdPercent = currentAlertThresholdPercent();
        boolean alert = usedPercent >= currentAlertThresholdPercent;
        int pendingUploadCount = partRepository.countPendingUploadPartsWithHistoryUploadEnabled();
        int queuedUploadCount = uploadUserSerialScheduler.getTotalPendingUploadCount();
        int activeUploadCount = TaskUtil.partUploadTask.size();
        DatabaseSize databaseSize = collectDatabaseSize(normalizedWorkPath);
        return WorkspaceUsageSnapshot.success(
                normalizedWorkPath,
                probePath.toString(),
                totalBytes,
                usedBytes,
                freeBytes,
                usedPercent,
                currentAlertThresholdPercent,
                alert,
                pendingUploadCount,
                queuedUploadCount,
                activeUploadCount,
                databaseSize
        );
    }

    private DatabaseSize collectDatabaseSize(String normalizedWorkPath) {
        Path basePath = resolveH2DatabaseBasePath(normalizedWorkPath);
        if (basePath == null) {
            return DatabaseSize.unavailable("仅本地 H2 数据库可显示文件大小");
        }
        long bytes = 0L;
        int existingFiles = 0;
        for (Path file : new Path[]{basePath.resolveSibling(basePath.getFileName() + ".mv.db"), basePath.resolveSibling(basePath.getFileName() + ".h2.db")}) {
            try {
                if (Files.isRegularFile(file)) {
                    bytes += Files.size(file);
                    existingFiles++;
                }
            } catch (IOException ignored) {
            }
        }
        if (existingFiles == 0) {
            return DatabaseSize.unavailable("数据库文件暂未生成");
        }
        return DatabaseSize.available(bytes, formatBytes(bytes), basePath.toString().replace("\\", "/"));
    }

    private Path resolveH2DatabaseBasePath(String normalizedWorkPath) {
        String jdbcUrl = environment.getProperty("spring.datasource.hikari.jdbc-url");
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            jdbcUrl = environment.getProperty("spring.datasource.url");
        }
        try {
            if ((jdbcUrl == null || jdbcUrl.isBlank()) && dataSource instanceof com.zaxxer.hikari.HikariDataSource hikariDataSource) {
                jdbcUrl = hikariDataSource.getJdbcUrl();
            }
        } catch (Exception ignored) {
        }
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return Paths.get(normalizedWorkPath, "db").toAbsolutePath().normalize();
        }
        String prefix = "jdbc:h2:";
        if (!jdbcUrl.startsWith(prefix)) {
            return null;
        }
        String location = jdbcUrl.substring(prefix.length());
        int optionIndex = location.indexOf(';');
        if (optionIndex >= 0) {
            location = location.substring(0, optionIndex);
        }
        while (location.startsWith("retry:")) {
            location = location.substring("retry:".length());
        }
        if (location.startsWith("mem:") || location.startsWith("tcp:") || location.startsWith("ssl:")) {
            return null;
        }
        if (location.startsWith("file:")) {
            location = location.substring("file:".length());
        }
        if (location.startsWith("~/")) {
            location = System.getProperty("user.home") + location.substring(1);
        }
        if (location.isBlank()) {
            return Paths.get(normalizedWorkPath, "db").toAbsolutePath().normalize();
        }
        return Paths.get(location).toAbsolutePath().normalize();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0L) {
            return "--";
        }
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double value = bytes;
        int unitIndex = 0;
        while (value >= 1024.0d && unitIndex < units.length - 1) {
            value = value / 1024.0d;
            unitIndex++;
        }
        if (unitIndex == 0) {
            return bytes + " B";
        }
        return String.format(java.util.Locale.ROOT, "%.2f %s", value, units[unitIndex]);
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

    private int currentAlertThresholdPercent() {
        if (systemConfigService == null) {
            return alertThresholdPercent;
        }
        return systemConfigService.getWorkspaceUsageAlertThresholdPercent();
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
        private final int queuedUploadCount;
        private final int activeUploadCount;
        private final long databaseBytes;
        private final String databaseDisplaySize;
        private final String databasePath;
        private final String databaseSizeNote;
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
                                       int queuedUploadCount,
                                       int activeUploadCount,
                                       DatabaseSize databaseSize,
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
            this.queuedUploadCount = queuedUploadCount;
            this.activeUploadCount = activeUploadCount;
            this.databaseBytes = databaseSize.bytes();
            this.databaseDisplaySize = databaseSize.displaySize();
            this.databasePath = databaseSize.path();
            this.databaseSizeNote = databaseSize.note();
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
                                                      int pendingUploadCount,
                                                      int queuedUploadCount,
                                                      int activeUploadCount,
                                                      DatabaseSize databaseSize) {
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
                    queuedUploadCount,
                    activeUploadCount,
                    databaseSize,
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
                    0,
                    0,
                    DatabaseSize.unavailable("状态不可用"),
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
                    0,
                    0,
                    DatabaseSize.unavailable("状态尚未准备好"),
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
            map.put("queuedUploadCount", queuedUploadCount);
            map.put("activeUploadCount", activeUploadCount);
            map.put("databaseBytes", databaseBytes);
            map.put("databaseDisplaySize", databaseDisplaySize);
            map.put("databasePath", databasePath);
            map.put("databaseSizeNote", databaseSizeNote);
            map.put("updatedAt", updatedAt);
            map.put("error", error);
            return map;
        }
    }

    private record DatabaseSize(long bytes, String displaySize, String path, String note) {
        private static DatabaseSize available(long bytes, String displaySize, String path) {
            return new DatabaseSize(bytes, displaySize, path, "统计当前 H2 数据库文件，压缩数据库后大小可能变化");
        }

        private static DatabaseSize unavailable(String note) {
            return new DatabaseSize(-1L, "--", null, note);
        }
    }
}
