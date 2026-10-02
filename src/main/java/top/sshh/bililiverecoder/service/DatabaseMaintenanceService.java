package top.sshh.bililiverecoder.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import top.sshh.bililiverecoder.entity.RecordEventDTO;
import top.sshh.bililiverecoder.entity.blrec.BlrecEventDTO;
import top.sshh.bililiverecoder.service.blrec.BlrecEventService;
import top.sshh.bililiverecoder.util.LogKvs;
import top.sshh.bililiverecoder.util.TaskUtil;
import top.sshh.bililiverecoder.config.db.DatabaseFileTransfer;
import top.sshh.bililiverecoder.config.db.MaintenanceDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class DatabaseMaintenanceService {

    private static final String ENDPOINT_RECORD_WEBHOOK = "/recordWebHook";
    private static final String ENDPOINT_BLREC_WEBHOOK = "/webhook/blrec";
    private static final int BLREC_DISPATCH_LOCK_COUNT = 256;
    private static final Object[] BLREC_DISPATCH_LOCKS = createBlrecDispatchLocks();

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final WebhookEventDispatcher webhookEventDispatcher;
    private final ApplicationContext applicationContext;
    private final TaskExecutor taskExecutor;
    private final DatabaseMaintenanceState maintenanceState;

    @Value("${record.work-path:.}")
    private String workPath;

    @Value("${record.maintenance.compact.wait-webhook-idle-millis:5000}")
    private long waitWebhookIdleMillis;

    @Value("${record.maintenance.compact.wait-database-idle-millis:30000}")
    private long waitDatabaseIdleMillis = 30000L;

    private volatile long operationStartedNanos;
    private volatile long phaseStartedNanos;
    private volatile long operationFinishedNanos;
    private volatile Path progressFile;
    private volatile TransferProgress transferProgress = new TransferProgress(0, 0);
    private volatile long databaseBytesBefore;
    private volatile long databaseBytesAfter;
    private volatile boolean databaseAvailable = true;
    private volatile String recoveryPath;
    private boolean replacementMayHaveChanged;
    private final Map<String, Long> phaseElapsedMillis = new ConcurrentHashMap<>();

    private final AtomicBoolean compactRunning = new AtomicBoolean(false);
    private final AtomicInteger spooledCount = new AtomicInteger();
    private final Object webhookSpoolReplayLock = new Object();
    private volatile MaintenanceSnapshot snapshot = new MaintenanceSnapshot("IDLE", null, null, null, 0, 0, 0, null);

    public DatabaseMaintenanceService(DataSource dataSource,
                                      JdbcTemplate jdbcTemplate,
                                      WebhookEventDispatcher webhookEventDispatcher,
                                      ApplicationContext applicationContext,
                                      DatabaseMaintenanceState maintenanceState,
                                      @org.springframework.beans.factory.annotation.Qualifier("myAsyncPool") TaskExecutor taskExecutor) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
        this.webhookEventDispatcher = webhookEventDispatcher;
        this.applicationContext = applicationContext;
        this.maintenanceState = maintenanceState;
        this.taskExecutor = taskExecutor;
    }

    public boolean isMaintenanceActive() {
        return maintenanceState.isMaintenanceActive();
    }

    public Map<String, Object> status() {
        MaintenanceSnapshot current = snapshot;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("maintenance", maintenanceState.isMaintenanceActive());
        result.put("running", compactRunning.get());
        result.put("phase", current.phase());
        result.put("phaseLabel", phaseLabel(current.phase()));
        TransferProgress transfer = transferProgress;
        boolean known = isTransferPhase(current.phase()) && transfer.total() > 0;
        result.put("progressKnown", known || "DONE".equals(current.phase()));
        Integer progress = null;
        if ("DONE".equals(current.phase())) progress = 100;
        else if (known) progress = (int) (100.0 * transfer.written() / transfer.total());
        result.put("progress", progress);
        result.put("transferredBytes", transfer.written());
        result.put("transferTotalBytes", transfer.total());
        result.put("elapsedSeconds", elapsedSeconds(operationStartedNanos));
        result.put("phaseElapsedSeconds", elapsedSeconds(phaseStartedNanos));
        result.put("phaseElapsedMillis", new LinkedHashMap<>(phaseElapsedMillis));
        result.put("fileBytes", currentFileBytes());
        result.put("databaseBytesBefore", databaseBytesBefore);
        result.put("databaseBytesAfter", databaseBytesAfter);
        result.put("databaseAvailable", databaseAvailable);
        result.put("databasePaused", maintenanceState.isDatabasePaused());
        result.put("activeConnections", maintenanceState.activeConnectionCount());
        result.put("recoveryPath", recoveryPath);
        result.put("startedAt", current.startedAt());
        result.put("finishedAt", current.finishedAt());
        result.put("message", current.message());
        result.put("spooled", spooledCount.get());
        result.put("replayed", current.replayed());
        result.put("failed", current.failed());
        result.put("pendingWebhookTasks", webhookEventDispatcher.pendingTaskCount());
        result.put("spoolPendingFiles", countSpoolFiles());
        result.put("backupPath", current.backupPath());
        return result;
    }

    public Map<String, Object> compactAsync() {
        if (!compactRunning.compareAndSet(false, true)) {
            Map<String, Object> busy = status();
            busy.put("success", false);
            busy.put("busy", true);
            busy.put("message", "数据库压缩任务正在执行中");
            return busy;
        }
        if (!databaseAvailable) {
            compactRunning.set(false);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "数据库尚未恢复，请先根据维护日志恢复数据库");
        }
        synchronized (webhookSpoolReplayLock) {
            spooledCount.set(0);
            maintenanceState.setMaintenanceActive(true);
        }
        LocalDateTime startedAt = LocalDateTime.now();
        operationStartedNanos = System.nanoTime();
        operationFinishedNanos = 0;
        phaseStartedNanos = operationStartedNanos;
        phaseElapsedMillis.clear();
        databaseBytesBefore = 0;
        databaseBytesAfter = 0;
        recoveryPath = null;
        replacementMayHaveChanged = false;
        progressFile = null;
        transferProgress = new TransferProgress(0, 0);
        snapshot = new MaintenanceSnapshot("QUEUING_WEBHOOK", startedAt, null, "已进入维护模式，新 webhook 将先写入本地队列", 0, 0, 0, null);
        try {
            taskExecutor.execute(this::runCompactMaintenance);
        } catch (RuntimeException error) {
            operationFinishedNanos = System.nanoTime();
            maintenanceState.setMaintenanceActive(false);
            compactRunning.set(false);
            snapshot = new MaintenanceSnapshot("FAILED", startedAt, LocalDateTime.now(), "无法启动数据库压缩任务", 0, 0, 0, null);
            throw error;
        }

        Map<String, Object> result = status();
        result.put("success", true);
        result.put("message", "数据库压缩已开始，期间 webhook 会先写入本地队列");
        return result;
    }

    public boolean spoolRecordWebhookIfMaintenance(String payload, String lockKey, long delayMs) {
        synchronized (webhookSpoolReplayLock) {
            if (!maintenanceState.isMaintenanceActive() && countSpoolFiles() == 0) return false;
            spoolWebhook(ENDPOINT_RECORD_WEBHOOK, lockKey, delayMs, payload);
            return true;
        }
    }

    public boolean spoolBlrecWebhookIfMaintenance(String payload, String lockKey) {
        synchronized (webhookSpoolReplayLock) {
            if (!maintenanceState.isMaintenanceActive() && countSpoolFiles() == 0) return false;
            spoolWebhook(ENDPOINT_BLREC_WEBHOOK, lockKey, 0L, payload);
            return true;
        }
    }

    public boolean dispatchBlrecEvent(String roomId, BlrecEventDTO event) {
        if (event == null || event.getType() == null || event.getData() == null) {
            return false;
        }
        synchronized (blrecDispatchLock(roomId)) {
            String serviceName = "blrec" + event.getType() + "Service";
            if (!applicationContext.containsBean(serviceName)) {
                log.info("[BLR] {}", LogKvs.event("Webhook.EventIgnored")
                        .add("source", "blrec").add("eventType", event.getType()).add("roomId", roomId));
                return false;
            }
            BlrecEventService service = applicationContext.getBean(serviceName, BlrecEventService.class);
            service.processing(event);
            return true;
        }
    }

    private Object blrecDispatchLock(String roomId) {
        return BLREC_DISPATCH_LOCKS[Math.floorMod(roomId == null ? 0 : roomId.hashCode(), BLREC_DISPATCH_LOCK_COUNT)];
    }

    private static Object[] createBlrecDispatchLocks() {
        Object[] locks = new Object[BLREC_DISPATCH_LOCK_COUNT];
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
        return locks;
    }

    private void runCompactMaintenance() {
        LocalDateTime startedAt = snapshot.startedAt() == null ? LocalDateTime.now() : snapshot.startedAt();
        int replayed = 0;
        int failed = 0;
        String backupPath = null;
        Path localDirectory = null;
        Path originalFile = null;
        Path localBackup = null;
        boolean poolClosed = false;
        boolean preserveLocalFiles = false;
        int expectedTables = 0;
        MaintenanceDataSource managed = dataSource instanceof MaintenanceDataSource source ? source : null;
        ScheduledExecutorService reporter = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "database-maintenance-progress");
            thread.setDaemon(true);
            return thread;
        });
        reporter.scheduleWithFixedDelay(this::logProgress, 5, 5, TimeUnit.SECONDS);
        try {
            if (managed == null) throw new IllegalStateException("当前连接池不支持安全的数据库维护");
            validateFileDatabase(managed.jdbcUrl());
            interruptRecoverableLocalTasks();
            waitWebhookDispatcherIdle();
            updatePhase("WAIT_DATABASE", "正在等待已有数据库操作结束");
            maintenanceState.pauseAndDrain(waitDatabaseIdleMillis);
            String location = jdbcTemplate.queryForObject("SELECT DATABASE_PATH()", String.class);
            while (location != null && location.startsWith("retry:")) location = location.substring(6);
            if (location != null && location.startsWith("file:")) location = location.substring(5);
            if (location == null || location.isBlank()) throw new IllegalStateException("无法确定数据库文件位置");
            originalFile = Path.of(location + ".mv.db").toAbsolutePath().normalize();
            if (!Files.isRegularFile(originalFile)) throw new IllegalStateException("数据库文件不存在：" + originalFile);
            databaseBytesBefore = Files.size(originalFile);
            expectedTables = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES", Integer.class);
            localDirectory = DatabaseFileTransfer.localWorkspace();
            localBackup = localDirectory.resolve("backup.zip");
            recoveryPath = localDirectory.toString();

            updatePhase("BACKUP", "正在生成数据库备份");
            progressFile = localBackup;
            jdbcTemplate.execute("BACKUP TO '" + sqlPath(localBackup) + "'");
            Path backupTarget = Path.of(workPath, "backup", "biliupforjava-DBbackup-before-compact-"
                    + new SimpleDateFormat("yyyyMMddHHmmssSSS").format(new Date()) + ".zip").toAbsolutePath();
            updatePhase("SAVE_BACKUP", "正在保存数据库备份");
            DatabaseFileTransfer.publishBackup(localBackup, backupTarget, this::transferProgress);
            backupPath = backupTarget.toString();
            snapshot = new MaintenanceSnapshot(snapshot.phase(), startedAt, null, snapshot.message(),
                    snapshot.spooled(), replayed, failed, backupPath);

            updatePhase("RESTORE_LOCAL", "正在准备待压缩的数据库");
            Path candidate = localDirectory.resolve("db.mv.db");
            progressFile = candidate;
            DatabaseFileTransfer.restoreSnapshot(localBackup, candidate);
            String localUrl = localJdbcUrl(localDirectory.resolve("db"), managed.jdbcUrl());
            updatePhase("COMPACT", "正在压缩数据库");
            progressFile = candidate.resolveSibling(candidate.getFileName() + ".tempFile");
            try (Connection local = managed.openDirect(localUrl); var statement = local.createStatement()) {
                statement.execute("SHUTDOWN COMPACT");
            }
            updatePhase("VERIFY_LOCAL", "正在检查压缩后的数据库");
            progressFile = candidate;
            verifyLocalDatabase(managed, localUrl, expectedTables);
            databaseBytesAfter = Files.size(candidate);

            updatePhase("SAVE_DATABASE", "正在保存压缩后的数据库");
            // 从这里开始彻底停止连接池，替换结束前不允许任何连接重新打开原库
            poolClosed = true;
            databaseAvailable = false;
            managed.closePoolForMaintenance();
            replaceDatabaseFile(candidate, originalFile);
            updatePhase("RECONNECT", "正在恢复数据库连接");
            managed.reopenPool();
            verifyReconnect();
            if (jdbcTemplate.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES", Integer.class) != expectedTables) {
                throw new IllegalStateException("保存后的数据库表结构检查未通过");
            }
            databaseAvailable = true;
            poolClosed = false;
            maintenanceState.resumeDatabase();

            updatePhase("REPLAY_WEBHOOK", "正在按顺序回放维护期间收到的 webhook");
            ReplayResult replayResult;
            synchronized (webhookSpoolReplayLock) {
                maintenanceState.setMaintenanceActive(false);
                replayResult = replaySpooledWebhooks();
            }
            replayed = replayResult.replayed();
            failed = replayResult.failed();
            finishPhase();
            snapshot = new MaintenanceSnapshot("DONE", startedAt, LocalDateTime.now(), "数据库压缩完成", countSpoolFiles(), replayed, failed, backupPath);
            log.info("[BLR] {}", LogKvs.event("Database.Compact.Success")
                    .add("backupPath", backupPath)
                    .add("elapsedSeconds", elapsedSeconds(operationStartedNanos))
                    .add("bytesBefore", databaseBytesBefore).add("bytesAfter", databaseBytesAfter)
                    .add("replayed", replayed)
                    .add("failed", failed));
        } catch (Exception e) {
            if (poolClosed) {
                try {
                    updatePhase("ROLLBACK", "正在恢复原数据库");
                    if (replacementMayHaveChanged) {
                        managed.stopPoolForMaintenance();
                        Path restored = localDirectory.resolve("rollback.mv.db");
                        DatabaseFileTransfer.restoreSnapshot(localBackup, restored);
                        replaceDatabaseFile(restored, originalFile);
                    }
                    managed.reopenPool();
                    verifyReconnect();
                    databaseAvailable = true;
                } catch (Exception recoveryError) {
                    e.addSuppressed(recoveryError);
                    databaseAvailable = false;
                    preserveLocalFiles = true;
                    log.error("数据库自动恢复失败，请保留备份和临时文件。备份位置：{}，临时文件位置：{}",
                            backupPath, localDirectory, recoveryError);
                }
            }
            if (databaseAvailable) maintenanceState.resumeDatabase();
            synchronized (webhookSpoolReplayLock) {
                maintenanceState.setMaintenanceActive(!databaseAvailable);
            }
            finishPhase();
            snapshot = new MaintenanceSnapshot("FAILED", startedAt, LocalDateTime.now(), "数据库压缩失败：" + e.getMessage(), countSpoolFiles(), replayed, failed, backupPath);
            log.error("[BLR] {}", LogKvs.event("Database.Compact.Failed")
                    .add("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()), e);
        } finally {
            if (!databaseAvailable) maintenanceState.retainDatabasePause();
            operationFinishedNanos = System.nanoTime();
            reporter.shutdownNow();
            progressFile = null;
            if (!preserveLocalFiles) {
                try {
                    DatabaseFileTransfer.cleanWorkspace(localDirectory);
                    recoveryPath = null;
                } catch (IOException cleanupError) {
                    log.warn("维护临时文件清理失败，临时文件位置：{}", localDirectory, cleanupError);
                }
            }
            compactRunning.set(false);
        }
    }

    private void interruptRecoverableLocalTasks() throws InterruptedException {
        List<Thread> tasks = new ArrayList<>(TaskUtil.partUploadTask.values());
        tasks.addAll(TaskUtil.publishTask.values());
        tasks.forEach(this::interruptQuietly);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitDatabaseIdleMillis);
        for (Thread thread : tasks) {
            if (thread == null || !thread.isAlive()) continue;
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) TimeUnit.NANOSECONDS.timedJoin(thread, remaining);
            if (thread.isAlive()) throw new IllegalStateException("已有上传或投稿任务尚未退出，本次压缩已取消");
        }
    }

    private void interruptQuietly(Thread thread) {
        if (thread == null) {
            return;
        }
        try {
            thread.interrupt();
        } catch (Exception ignored) {
        }
    }

    private void waitWebhookDispatcherIdle() throws InterruptedException {
        long deadline = System.currentTimeMillis() + Math.max(0L, waitWebhookIdleMillis);
        while (!webhookEventDispatcher.isIdle() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100L);
        }
        if (!webhookEventDispatcher.isIdle()) {
            log.warn("[BLR] {}", LogKvs.event("Database.Compact.WebhookStillBusy")
                    .add("pending", webhookEventDispatcher.pendingTaskCount()));
            throw new IllegalStateException("Webhook 任务仍在处理中，本次压缩已取消");
        }
    }

    private static void validateFileDatabase(String url) {
        if (url == null || !url.startsWith("jdbc:h2:")) throw new IllegalStateException("数据库压缩只支持 H2 文件数据库");
        String location = url.substring(8).split(";", 2)[0];
        while (location.startsWith("retry:")) location = location.substring(6);
        if (location.startsWith("mem:") || location.startsWith("tcp:") || location.startsWith("ssl:")
                || (location.contains(":") && !location.startsWith("file:") && !location.matches("^[A-Za-z]:.*"))) {
            throw new IllegalStateException("数据库压缩只支持本机直接访问的 H2 文件数据库");
        }
    }

    private static String localJdbcUrl(Path base, String originalUrl) {
        StringBuilder url = new StringBuilder("jdbc:h2:").append(base.toAbsolutePath().toString().replace('\\', '/'))
                .append(";IFEXISTS=TRUE");
        for (String option : originalUrl.split(";")) {
            String upper = option.toUpperCase(Locale.ROOT);
            if (upper.startsWith("CIPHER=") || upper.startsWith("DATABASE_TO_UPPER=") || upper.startsWith("DATABASE_TO_LOWER=")) {
                url.append(';').append(option);
            }
        }
        return url.toString();
    }

    private static void verifyLocalDatabase(MaintenanceDataSource source, String url, int expectedTables) throws Exception {
        try (Connection connection = source.openDirect(url); var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES")) {
                if (!result.next() || result.getInt(1) != expectedTables) throw new IllegalStateException("压缩后的数据库表结构检查未通过");
            }
            statement.execute("SHUTDOWN");
        }
    }

    protected void replaceDatabaseFile(Path candidate, Path original) throws IOException {
        maintenanceState.requireMaintenanceOwner();
        Path pending = original.resolveSibling(original.getFileName() + ".pending-" + UUID.randomUUID());
        try {
            DatabaseFileTransfer.copy(candidate, pending, this::transferProgress);
            maintenanceState.requireMaintenanceOwner();
            // 不支持原子替换时直接取消，原库不能出现被移走的空档
            replacementMayHaveChanged = true;
            try {
                Files.move(pending, original, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                replacementMayHaveChanged = false;
                throw new IOException("目标存储不支持原子替换，本次压缩已取消，原数据库保持原样", unsupported);
            }
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    private void verifyReconnect() throws InterruptedException {
        Exception last = null;
        for (int i = 0; i < 10; i++) {
            try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT 1")) {
                if (!result.next() || result.getInt(1) != 1) throw new IllegalStateException("数据库连接验证失败");
                return;
            } catch (Exception e) {
                last = e;
                Thread.sleep(300L);
            }
        }
        throw new IllegalStateException("数据库压缩后连接恢复失败", last);
    }

    private void spoolWebhook(String endpoint, String lockKey, long delayMs, String payload) {
        try {
            Path spoolDir = spoolDir();
            Files.createDirectories(spoolDir);
            long now = System.currentTimeMillis();
            String safeEndpoint = endpoint.replace("/", "_").replaceAll("[^A-Za-z0-9._-]", "_");
            Path file = spoolDir.resolve(now + "-" + safeEndpoint + "-" + Math.abs(UUID_SEED.next()) + ".json");

            JSONObject item = new JSONObject(true);
            item.put("endpoint", endpoint);
            item.put("lockKey", lockKey);
            item.put("delayMs", delayMs);
            item.put("createdAt", now);
            item.put("payloadBase64", Base64.getEncoder().encodeToString(nullToEmpty(payload).getBytes(StandardCharsets.UTF_8)));
            Files.writeString(file, item.toJSONString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

            spooledCount.incrementAndGet();
            log.info("[BLR] {}", LogKvs.event("Database.Compact.WebhookSpooled")
                    .add("endpoint", endpoint)
                    .add("file", file.getFileName())
                    .add("lockKeyHash", lockKey == null ? null : Integer.toHexString(lockKey.hashCode())));
        } catch (Exception e) {
            log.error("[BLR] {}", LogKvs.event("Database.Compact.WebhookSpoolFailed")
                    .add("endpoint", endpoint)
                    .add("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()), e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "MAINTENANCE_SPOOL_FAILED", e);
        }
    }

    @Scheduled(fixedDelayString = "${record.webhook.spool-replay-ms:30000}",
            initialDelayString = "${record.webhook.spool-replay-initial-delay-ms:5000}")
    public void recoverSpooledWebhooks() {
        if (maintenanceState.isMaintenanceActive() || compactRunning.get()) return;
        synchronized (webhookSpoolReplayLock) {
            if (maintenanceState.isMaintenanceActive() || compactRunning.get()) return;
            try {
                ReplayResult result = replaySpooledWebhooks();
                if (result.replayed() > 0) {
                    MaintenanceSnapshot current = snapshot;
                    snapshot = new MaintenanceSnapshot(current.phase(), current.startedAt(), current.finishedAt(),
                            current.message(), countSpoolFiles(), current.replayed() + result.replayed(),
                            current.failed() + result.failed(), current.backupPath());
                }
            } catch (Exception error) {
                log.error("[BLR] {}", LogKvs.event("Database.Compact.WebhookSpoolRecoveryFailed")
                        .addIfNotBlank("err", error.getMessage()).add("ex", error.getClass().getSimpleName()), error);
            }
        }
    }

    private ReplayResult replaySpooledWebhooks() throws IOException {
        synchronized (webhookSpoolReplayLock) {
            int replayed = 0;
            int failed = 0;
            RecordWebhookInboxService inboxService = applicationContext.getBean(RecordWebhookInboxService.class);
            for (Path file : listSpoolFiles()) {
                String endpoint;
                String payload;
                String lockKey;
                long delayMs;
                String source;
                String eventType;
                String upstreamEventId;
                try {
                    JSONObject item = JSON.parseObject(Files.readString(file, StandardCharsets.UTF_8));
                    endpoint = item.getString("endpoint");
                    payload = new String(Base64.getDecoder().decode(item.getString("payloadBase64")), StandardCharsets.UTF_8);
                    lockKey = item.getString("lockKey");
                    delayMs = item.getLongValue("delayMs");
                    if (ENDPOINT_RECORD_WEBHOOK.equals(endpoint)) {
                        RecordEventDTO event = JSON.parseObject(payload, RecordEventDTO.class);
                        if (event == null) throw new IllegalArgumentException("record webhook 暂存事件为空");
                        source = recordEventSource(event);
                        eventType = recordEventType(event);
                        upstreamEventId = recordEventId(event);
                    } else if (ENDPOINT_BLREC_WEBHOOK.equals(endpoint)) {
                        BlrecEventDTO event = JSON.parseObject(payload, BlrecEventDTO.class);
                        if (event == null) throw new IllegalArgumentException("blrec webhook 暂存事件为空");
                        source = "blrec-native";
                        eventType = event.getType();
                        upstreamEventId = event.getId();
                    } else {
                        throw new IllegalArgumentException("Unknown webhook endpoint: " + endpoint);
                    }
                } catch (Exception invalidSpool) {
                    failed++;
                    moveFailedSpool(file);
                    log.error("[BLR] {}", LogKvs.event("Database.Compact.WebhookReplayInvalid")
                            .add("file", file.getFileName()).addIfNotBlank("err", invalidSpool.getMessage())
                            .add("ex", invalidSpool.getClass().getSimpleName()), invalidSpool);
                    continue;
                }
                try {
                    inboxService.accept(payload, lockKey, delayMs, source, eventType, upstreamEventId);
                    Files.deleteIfExists(file);
                    replayed++;
                } catch (Exception error) {
                    failed++;
                    log.error("[BLR] {}", LogKvs.event("Database.Compact.WebhookReplayFailed")
                            .add("file", file.getFileName()).addIfNotBlank("err", error.getMessage())
                            .add("ex", error.getClass().getSimpleName()), error);
                    break;
                }
            }
            return new ReplayResult(replayed, failed);
        }
    }

    private void moveFailedSpool(Path file) {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".failed"));
        } catch (Exception ignored) {
        }
    }

    private String recordEventType(RecordEventDTO event) {
        return event.getEventType() == null || event.getEventType().isBlank()
                ? event.getType() : event.getEventType();
    }

    private String recordEventSource(RecordEventDTO event) {
        if (event.getData() != null || event.getType() != null) return "blrec";
        if (event.getEventData() != null || event.getEventType() != null) return "brec";
        return "unknown";
    }

    private String recordEventId(RecordEventDTO event) {
        return event.getEventId() == null || event.getEventId().isBlank() ? event.getId() : event.getEventId();
    }

    private Path spoolDir() {
        return Path.of(workPath, "webhook-spool");
    }

    private int countSpoolFiles() {
        try {
            return listSpoolFiles().length;
        } catch (Exception e) {
            return 0;
        }
    }

    private Path[] listSpoolFiles() throws IOException {
        Path spoolDir = spoolDir();
        if (!Files.isDirectory(spoolDir)) {
            return new Path[0];
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(spoolDir, "*.json")) {
            return java.util.stream.StreamSupport.stream(stream.spliterator(), false)
                    .sorted()
                    .toArray(Path[]::new);
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private void updatePhase(String phase, String message) {
        finishPhase();
        phaseStartedNanos = System.nanoTime();
        progressFile = null;
        transferProgress = new TransferProgress(0, 0);
        MaintenanceSnapshot previous = snapshot;
        snapshot = new MaintenanceSnapshot(phase, previous.startedAt(), null, message,
                previous.spooled(), previous.replayed(), previous.failed(), previous.backupPath());
        log.info("数据库维护：{}，累计耗时 {} 秒", message, elapsedSeconds(operationStartedNanos));
    }

    private void finishPhase() {
        if (phaseStartedNanos != 0) phaseElapsedMillis.put(snapshot.phase(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - phaseStartedNanos));
    }

    private void transferProgress(long written, long total) {
        transferProgress = new TransferProgress(written, total);
    }

    private static boolean isTransferPhase(String phase) {
        return "SAVE_BACKUP".equals(phase) || "SAVE_DATABASE".equals(phase) || "ROLLBACK".equals(phase);
    }

    private long elapsedSeconds(long start) {
        return start == 0 ? 0 : TimeUnit.NANOSECONDS.toSeconds((operationFinishedNanos == 0
                ? System.nanoTime() : operationFinishedNanos) - start);
    }

    private long currentFileBytes() {
        Path file = progressFile;
        try {
            return file != null && Files.exists(file) ? Files.size(file) : 0;
        } catch (IOException | SecurityException ignored) {
            return 0;
        }
    }

    private void logProgress() {
        try {
            MaintenanceSnapshot current = snapshot;
            TransferProgress transfer = transferProgress;
            log.info("数据库维护进行中：{}，本阶段已耗时 {} 秒，文件大小 {} MB，已传输 {} / {} MB",
                    current.message(), elapsedSeconds(phaseStartedNanos), currentFileBytes() / (1024 * 1024),
                    transfer.written() / (1024 * 1024), transfer.total() / (1024 * 1024));
        } catch (RuntimeException ignored) {
            // 状态日志失败不能打断数据库维护
        }
    }

    private static String sqlPath(Path path) {
        return path.toAbsolutePath().toString().replace("\\", "/").replace("'", "''");
    }

    private String phaseLabel(String phase) {
        if ("WAIT_DATABASE".equals(phase)) return "等待数据库操作结束";
        if ("SAVE_BACKUP".equals(phase)) return "保存数据库备份";
        if ("RESTORE_LOCAL".equals(phase)) return "准备数据库";
        if ("VERIFY_LOCAL".equals(phase)) return "检查压缩结果";
        if ("SAVE_DATABASE".equals(phase)) return "保存压缩结果";
        if ("ROLLBACK".equals(phase)) return "恢复原数据库";
        if ("QUEUING_WEBHOOK".equals(phase)) {
            return "进入维护模式";
        }
        if ("BACKUP".equals(phase)) {
            return "备份数据库";
        }
        if ("COMPACT".equals(phase)) {
            return "压缩数据库";
        }
        if ("RECONNECT".equals(phase)) {
            return "恢复连接";
        }
        if ("REPLAY_WEBHOOK".equals(phase)) {
            return "回放 webhook";
        }
        if ("DONE".equals(phase)) {
            return "已完成";
        }
        if ("FAILED".equals(phase)) {
            return "失败";
        }
        return "等待中";
    }

    private record MaintenanceSnapshot(String phase,
                                       LocalDateTime startedAt,
                                       LocalDateTime finishedAt,
                                       String message,
                                       int spooled,
                                       int replayed,
                                       int failed,
                                       String backupPath) {
    }

    private record ReplayResult(int replayed, int failed) {
    }

    private record TransferProgress(long written, long total) { }

    private static final class UUID_SEED {
        private static final java.util.concurrent.atomic.AtomicLong SEQ = new java.util.concurrent.atomic.AtomicLong();

        private static long next() {
            return SEQ.incrementAndGet();
        }
    }
}
