package top.sshh.bililiverecoder.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.zaxxer.hikari.HikariDataSource;
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

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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

    private final AtomicBoolean compactRunning = new AtomicBoolean(false);
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
        result.put("progress", phaseProgress(current.phase()));
        result.put("startedAt", current.startedAt());
        result.put("finishedAt", current.finishedAt());
        result.put("message", current.message());
        result.put("spooled", current.spooled());
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
        maintenanceState.setMaintenanceActive(true);
        LocalDateTime startedAt = LocalDateTime.now();
        snapshot = new MaintenanceSnapshot("QUEUING_WEBHOOK", startedAt, null, "已进入维护模式，新 webhook 将先写入本地队列", 0, 0, 0, null);
        taskExecutor.execute(this::runCompactMaintenance);

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
        try {
            interruptRecoverableLocalTasks();
            waitWebhookDispatcherIdle();

            snapshot = new MaintenanceSnapshot("BACKUP", startedAt, null, "正在备份数据库", countSpoolFiles(), replayed, failed, backupPath);
            backupPath = backupH2Database();

            snapshot = new MaintenanceSnapshot("COMPACT", startedAt, null, "正在执行 H2 SHUTDOWN COMPACT", countSpoolFiles(), replayed, failed, backupPath);
            executeShutdownCompact();

            snapshot = new MaintenanceSnapshot("RECONNECT", startedAt, null, "正在恢复数据库连接", countSpoolFiles(), replayed, failed, backupPath);
            verifyReconnect();

            snapshot = new MaintenanceSnapshot("REPLAY_WEBHOOK", startedAt, null, "正在按顺序回放维护期间收到的 webhook", countSpoolFiles(), replayed, failed, backupPath);
            ReplayResult replayResult;
            synchronized (webhookSpoolReplayLock) {
                maintenanceState.setMaintenanceActive(false);
                replayResult = replaySpooledWebhooks();
            }
            replayed = replayResult.replayed();
            failed = replayResult.failed();

            snapshot = new MaintenanceSnapshot("DONE", startedAt, LocalDateTime.now(), "数据库压缩完成", countSpoolFiles(), replayed, failed, backupPath);
            log.info("[BLR] {}", LogKvs.event("Database.Compact.Success")
                    .add("backupPath", backupPath)
                    .add("replayed", replayed)
                    .add("failed", failed));
        } catch (Exception e) {
            synchronized (webhookSpoolReplayLock) {
                maintenanceState.setMaintenanceActive(false);
            }
            snapshot = new MaintenanceSnapshot("FAILED", startedAt, LocalDateTime.now(), "数据库压缩失败：" + e.getMessage(), countSpoolFiles(), replayed, failed, backupPath);
            log.error("[BLR] {}", LogKvs.event("Database.Compact.Failed")
                    .add("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()), e);
        } finally {
            compactRunning.set(false);
        }
    }

    private void interruptRecoverableLocalTasks() {
        TaskUtil.partUploadTask.values().forEach(this::interruptQuietly);
        TaskUtil.publishTask.values().forEach(this::interruptQuietly);
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
        }
    }

    private String backupH2Database() throws IOException {
        Path backupDir = Path.of(workPath, "backup");
        Files.createDirectories(backupDir);
        String timestamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
        String backupPath = backupDir.resolve("biliupforjava-DBbackup-before-compact-" + timestamp + ".zip")
                .toAbsolutePath()
                .toString()
                .replace("\\", "/")
                .replace("'", "''");
        jdbcTemplate.execute("BACKUP TO '" + backupPath + "'");
        return backupPath;
    }

    private void executeShutdownCompact() {
        try {
            jdbcTemplate.execute("SHUTDOWN COMPACT");
        } catch (Exception e) {
            String message = e.getMessage();
            if (message == null || (!message.contains("Database is already closed") && !message.contains("The database has been closed"))) {
                throw e;
            }
        } finally {
            if (dataSource instanceof HikariDataSource hikariDataSource && hikariDataSource.getHikariPoolMXBean() != null) {
                hikariDataSource.getHikariPoolMXBean().softEvictConnections();
            }
        }
    }

    private void verifyReconnect() throws InterruptedException {
        Exception last = null;
        for (int i = 0; i < 10; i++) {
            try (Connection ignored = dataSource.getConnection()) {
                jdbcTemplate.queryForObject("SELECT 1", Integer.class);
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

            MaintenanceSnapshot current = snapshot;
            snapshot = new MaintenanceSnapshot(current.phase(), current.startedAt(), current.finishedAt(), current.message(),
                    current.spooled() + 1, current.replayed(), current.failed(), current.backupPath());
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

    private int phaseProgress(String phase) {
        if ("QUEUING_WEBHOOK".equals(phase)) {
            return 10;
        }
        if ("BACKUP".equals(phase)) {
            return 25;
        }
        if ("COMPACT".equals(phase)) {
            return 55;
        }
        if ("RECONNECT".equals(phase)) {
            return 75;
        }
        if ("REPLAY_WEBHOOK".equals(phase)) {
            return 90;
        }
        if ("DONE".equals(phase)) {
            return 100;
        }
        if ("FAILED".equals(phase)) {
            return 100;
        }
        return 0;
    }

    private String phaseLabel(String phase) {
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

    private static final class UUID_SEED {
        private static final java.util.concurrent.atomic.AtomicLong SEQ = new java.util.concurrent.atomic.AtomicLong();

        private static long next() {
            return SEQ.incrementAndGet();
        }
    }
}
