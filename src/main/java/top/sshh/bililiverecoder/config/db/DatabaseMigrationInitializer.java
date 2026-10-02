package top.sshh.bililiverecoder.config.db;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 在 Hibernate 创建 EntityManagerFactory 前完成数据库兼容升级，重复执行也不会重复修改
 * 迁移一旦发布，就不要再修改它的版本号和签名，后续改动需要新增一条迁移
 */
@Slf4j
@Component("databaseMigrationInitializer")
public class DatabaseMigrationInitializer implements InitializingBean {
    private static final Object INITIALIZATION_LOCK = new Object();
    private static final String HISTORY_TABLE = "app_schema_migration";
    private static final String LEGACY_SIGNATURE = "history-force-archive-skip-order-gift-room-snapshot-v1";
    private static final String PUBLISH_SIGNATURE = "publish-task-cooldown-and-captcha-retry-columns-v1";
    private static final String WEBHOOK_INBOX_SIGNATURE = "durable-record-webhook-inbox-v1";
    private static final String POST_PUBLISH_SIGNATURE = "durable-comment-progress-and-visibility-recovery-v1";
    private static final String DELETE_AND_INSTANCE_SIGNATURE = "recoverable-history-delete-and-single-instance-lease-v1";
    private static final String[] PUBLISH_INDEXES = {
            "idx_publish_task_account_state_order",
            "idx_publish_task_history_state",
            "idx_publish_task_state_due"
    };
    private static final String[] WEBHOOK_INDEXES = {
            "idx_webhook_inbox_due",
            "idx_webhook_inbox_updated"
    };
    private static final String[] COMMENT_INDEXES = {
            "idx_video_comment_history_state",
            "uk_video_comment_history_sequence"
    };
    private static final String[] VISIBILITY_INDEXES = {
            "idx_visibility_restore_due",
            "uk_visibility_restore_history"
    };
    private static final String[] HISTORY_DELETE_INDEXES = {
            "idx_history_delete_due",
            "uk_history_delete_history"
    };

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final String workPath;

    public DatabaseMigrationInitializer(DataSource dataSource, JdbcTemplate jdbc,
                                        @Value("${record.work-path:.}") String workPath) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.workPath = workPath;
    }

    @Override
    public void afterPropertiesSet() {
        synchronized (INITIALIZATION_LOCK) {
            initializeWithProcessLock();
        }
    }

    private void initializeWithProcessLock() {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            boolean h2 = product != null && product.toLowerCase(Locale.ROOT).contains("h2");
            boolean mysql = product != null && product.toLowerCase(Locale.ROOT).contains("mysql");
            if (!h2 && !mysql) {
                throw new IllegalStateException("不支持的数据库类型：" + product + "；数据库迁移仅支持 H2 和 MySQL");
            }
            if (h2 && migrationPending(connection, "20260928_01", "历史数据库兼容修复", LEGACY_SIGNATURE)) {
                backupExistingFileDatabase(connection);
            }
            if (h2 && !migrationPending(connection, "20260928_01", "历史数据库兼容修复", LEGACY_SIGNATURE)
                    && migrationPending(connection, "20260928_02", "投稿任务及验证码恢复字段", PUBLISH_SIGNATURE)) {
                backupExistingFileDatabase(connection);
            }
            if (h2 && !migrationPending(connection, "20260928_05", "稿件删除恢复与单实例租约", DELETE_AND_INSTANCE_SIGNATURE)
                    && migrationPending(connection, "20260928_06", "删除任务安全取消标记", "history-deletion-cancel-fence-v1")) {
                backupExistingFileDatabase(connection);
            }
            if (h2 && !migrationPending(connection, "20260928_01", "历史数据库兼容修复", LEGACY_SIGNATURE)
                    && !migrationPending(connection, "20260928_02", "投稿任务及验证码恢复字段", PUBLISH_SIGNATURE)
                    && migrationPending(connection, "20260928_03", "录播 webhook 持久化收件箱", WEBHOOK_INBOX_SIGNATURE)) {
                backupExistingFileDatabase(connection);
            }
            if (h2 && !migrationPending(connection, "20260928_01", "历史数据库兼容修复", LEGACY_SIGNATURE)
                    && !migrationPending(connection, "20260928_02", "投稿任务及验证码恢复字段", PUBLISH_SIGNATURE)
                    && !migrationPending(connection, "20260928_03", "录播 webhook 持久化收件箱", WEBHOOK_INBOX_SIGNATURE)
                    && migrationPending(connection, "20260928_04", "评论进度与稿件状态恢复", POST_PUBLISH_SIGNATURE)) {
                backupExistingFileDatabase(connection);
            }
            if (h2 && !migrationPending(connection, "20260928_01", "历史数据库兼容修复", LEGACY_SIGNATURE)
                    && !migrationPending(connection, "20260928_02", "投稿任务及验证码恢复字段", PUBLISH_SIGNATURE)
                    && !migrationPending(connection, "20260928_03", "录播 webhook 持久化收件箱", WEBHOOK_INBOX_SIGNATURE)
                    && !migrationPending(connection, "20260928_04", "评论进度与稿件状态恢复", POST_PUBLISH_SIGNATURE)
                    && migrationPending(connection, "20260928_05", "稿件删除恢复与单实例租约", DELETE_AND_INSTANCE_SIGNATURE)) {
                backupExistingFileDatabase(connection);
            }
            log.info("正在检查并升级数据库结构……");
            ensureMigrationTable();
            applyMigration(connection, "20260928_01", "历史数据库兼容修复", LEGACY_SIGNATURE,
                    () -> applyLegacyCompatibility(connection, h2));
            applyMigration(connection, "20260928_02", "投稿任务及验证码恢复字段", PUBLISH_SIGNATURE,
                    () -> applyPublishTaskSchema(connection, h2));
            applyMigration(connection, "20260928_03", "录播 webhook 持久化收件箱", WEBHOOK_INBOX_SIGNATURE,
                    () -> applyWebhookInboxSchema(connection, h2));
            applyMigration(connection, "20260928_04", "评论进度与稿件状态恢复", POST_PUBLISH_SIGNATURE,
                    () -> applyPostPublishSchema(connection, h2));
            applyMigration(connection, "20260928_05", "稿件删除恢复与单实例租约", DELETE_AND_INSTANCE_SIGNATURE,
                    () -> applyDeleteAndInstanceSchema(connection, h2));
            applyMigration(connection, "20260928_06", "删除任务安全取消标记", "history-deletion-cancel-fence-v1",
                    () -> applyHistoryDeletionCancellationSchema(connection));
            validateRequiredSchema(connection);
            log.info("数据库检查与升级完成，继续启动。数据库类型：{}", product);
        } catch (Exception e) {
            log.error("Database compatibility migration failed; application startup is blocked", e);
            throw new BeanCreationException("databaseMigrationInitializer",
                    "数据库兼容升级失败，业务尚未启动。请保留数据库与启动日志，修复失败迁移后重新启动："
                            + e.getMessage(), e);
        }
    }

    private void backupExistingFileDatabase(Connection connection) throws Exception {
        String url = connection.getMetaData().getURL();
        if (url == null || url.toLowerCase(Locale.ROOT).contains(":mem:")) return;
        if (!hasApplicationTables(connection.getMetaData(), connection)) return;
        File backupDir = new File(workPath == null || workPath.isBlank() ? "." : workPath, "backup");
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        Path backupPath = new File(backupDir, "biliupforjava-DBbackup-" + stamp + ".zip")
                .toPath().toAbsolutePath();
        long startedAt = System.nanoTime();
        log.info("正在备份数据库，完成后将继续启动，请勿关闭程序。备份位置：{}", backupPath);
        Path localDirectory = null;
        try {
            Files.createDirectories(backupDir.toPath());
            localDirectory = DatabaseFileTransfer.localWorkspace();
            Path localBackup = localDirectory.resolve("backup.zip");
            try (BackupProgress progress = new BackupProgress(localBackup, startedAt)) {
                String sqlPath = localBackup.toString().replace("\\", "/").replace("'", "''");
                jdbc.execute("BACKUP TO '" + sqlPath + "'");
            }
            log.info("数据库备份已生成，正在保存到工作目录。备份大小 {}，备份位置：{}", backupSize(localBackup), backupPath);
            long[] lastReport = {System.nanoTime()};
            DatabaseFileTransfer.publishBackup(localBackup, backupPath, (written, total) -> {
                long now = System.nanoTime();
                if (now - lastReport[0] >= TimeUnit.SECONDS.toNanos(5)) {
                    log.info("数据库备份保存中：已耗时 {} 秒，已传输 {} / {} MB，进度 {}%",
                            elapsedSeconds(startedAt), written / (1024 * 1024), total / (1024 * 1024),
                            total == 0 ? 0 : (int) (100.0 * written / total));
                    lastReport[0] = now;
                }
            });
        } catch (Exception e) {
            log.error("数据库备份失败，启动已中止：已耗时 {} 秒，备份位置：{}，失败原因：{}",
                    elapsedSeconds(startedAt), backupPath, e.getMessage());
            throw e;
        } finally {
            try {
                DatabaseFileTransfer.cleanWorkspace(localDirectory);
            } catch (IOException cleanupError) {
                log.warn("数据库备份临时文件清理失败，临时文件位置：{}", localDirectory, cleanupError);
            }
        }
        log.info("数据库备份完成：耗时 {} 秒，备份大小 {}，备份位置：{}",
                elapsedSeconds(startedAt), backupSize(backupPath), backupPath);
        File[] backups = backupDir.listFiles((dir, name) -> name.startsWith("biliupforjava-DBbackup-")
                && name.endsWith(".zip"));
        if (backups != null && backups.length > 10) {
            Arrays.sort(backups, Comparator.comparingLong(File::lastModified));
            for (int i = 0; i < backups.length - 10; i++) {
                try {
                    Files.deleteIfExists(backups[i].toPath());
                } catch (Exception e) {
                    log.warn("Could not remove old H2 backup {}: {}", backups[i].getName(), e.getMessage());
                }
            }
        }
    }

    private static long elapsedSeconds(long startedAt) {
        return TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAt);
    }

    private static String backupSize(Path backupPath) {
        try {
            if (!Files.exists(backupPath)) return "0.00 MB（等待备份文件写入）";
            return String.format(Locale.ROOT, "%.2f MB", Files.size(backupPath) / (1024.0 * 1024.0));
        } catch (IOException | SecurityException e) {
            return "暂无法读取（等待备份完成）";
        }
    }

    /** 只报告备份状态，备份完成后才继续迁移 */
    private static final class BackupProgress implements AutoCloseable {
        private final ScheduledExecutorService executor;
        private boolean closed;

        private BackupProgress(Path backupPath, long startedAt) {
            executor = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "database-backup-progress");
                thread.setDaemon(true);
                return thread;
            });
            executor.scheduleWithFixedDelay(() -> report(backupPath, startedAt), 5, 5, TimeUnit.SECONDS);
        }

        private synchronized void report(Path backupPath, long startedAt) {
            if (closed) return;
            log.info("数据库备份进行中：已耗时 {} 秒，备份文件已写入 {}",
                    elapsedSeconds(startedAt), backupSize(backupPath));
        }

        @Override
        public synchronized void close() {
            closed = true;
            executor.shutdownNow();
        }
    }

    private boolean hasApplicationTables(DatabaseMetaData meta, Connection connection) throws SQLException {
        try (ResultSet tables = meta.getTables(connection.getCatalog(), connection.getSchema(), "%",
                new String[]{"TABLE"})) {
            while (tables.next()) {
                String name = tables.getString("TABLE_NAME");
                if (name != null && !name.toUpperCase(Locale.ROOT).startsWith("INFORMATION_SCHEMA")
                        && !name.equalsIgnoreCase(HISTORY_TABLE)) return true;
            }
        }
        return false;
    }

    private boolean migrationPending(Connection connection, String version, String description,
                                     String signature) throws Exception {
        if (!tableExists(connection, HISTORY_TABLE)) return true;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT checksum, success FROM " + HISTORY_TABLE + " WHERE version_id = ?", version);
        if (rows.isEmpty()) return true;
        Map<String, Object> row = rows.get(0);
        String expectedChecksum = sha256(version + "\n" + description + "\n" + signature);
        boolean successful = Boolean.TRUE.equals(row.get("success"))
                || "1".equals(String.valueOf(row.get("success")))
                || "true".equalsIgnoreCase(String.valueOf(row.get("success")));
        return !successful || !expectedChecksum.equalsIgnoreCase(String.valueOf(row.get("checksum")));
    }

    private void ensureMigrationTable() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + HISTORY_TABLE + " ("
                + "version_id VARCHAR(40) PRIMARY KEY, description VARCHAR(255) NOT NULL, "
                + "checksum VARCHAR(64) NOT NULL, installed_at TIMESTAMP NULL, "
                + "success BOOLEAN NOT NULL DEFAULT FALSE, error_message VARCHAR(2000) NULL)");
    }

    private void applyMigration(Connection connection, String version, String description,
                                String signature, Runnable migration) throws Exception {
        String checksum = sha256(version + "\n" + description + "\n" + signature);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT checksum, success FROM " + HISTORY_TABLE + " WHERE version_id = ?", version);
        if (!rows.isEmpty()) {
            Map<String, Object> row = rows.get(0);
            if (!checksum.equalsIgnoreCase(String.valueOf(row.get("checksum")))) {
                throw new IllegalStateException("迁移 " + version + " 的校验值变化；已发布迁移不可修改，请新增迁移编号");
            }
            if (Boolean.TRUE.equals(row.get("success")) || "1".equals(String.valueOf(row.get("success")))) return;
            jdbc.update("UPDATE " + HISTORY_TABLE
                            + " SET description=?, installed_at=?, success=?, error_message=NULL WHERE version_id=?",
                    description, java.sql.Timestamp.valueOf(LocalDateTime.now()), false, version);
        } else {
            jdbc.update("INSERT INTO " + HISTORY_TABLE
                            + " (version_id, description, checksum, installed_at, success, error_message)"
                            + " VALUES (?, ?, ?, ?, ?, NULL)", version, description, checksum,
                    java.sql.Timestamp.valueOf(LocalDateTime.now()), false);
        }
        try {
            migration.run();
            jdbc.update("UPDATE " + HISTORY_TABLE
                            + " SET installed_at=?, success=?, error_message=NULL WHERE version_id=?",
                    java.sql.Timestamp.valueOf(LocalDateTime.now()), true, version);
            log.info("Applied database migration {} ({})", version, description);
        } catch (RuntimeException e) {
            jdbc.update("UPDATE " + HISTORY_TABLE
                            + " SET installed_at=?, success=?, error_message=? WHERE version_id=?",
                    java.sql.Timestamp.valueOf(LocalDateTime.now()), false, truncate(e.toString(), 1900), version);
            throw new IllegalStateException("迁移 " + version + " 失败：" + e.getMessage(), e);
        }
    }

    private void applyLegacyCompatibility(Connection connection, boolean h2) {
        ensureColumn(connection, "record_history", "force_archived", "BOOLEAN DEFAULT FALSE");
        ensureColumn(connection, "record_history", "publish_user_id", "BIGINT");
        ensureColumn(connection, "record_history", "edit_parts_uploading", "BOOLEAN DEFAULT FALSE");
        ensureColumn(connection, "record_history", "publish_issue_type", "VARCHAR(64)");
        ensureColumn(connection, "record_history", "publish_issue_reason", "VARCHAR(512)");
        ensureColumn(connection, "record_history", "publish_issue_part_count", "INT DEFAULT 0");
        ensureColumn(connection, "record_history_part", "manual_skip", "BOOLEAN DEFAULT FALSE");
        ensureColumn(connection, "record_history_part", "skip_reason", "VARCHAR(255)");
        ensureColumn(connection, "record_history_part", "source_part_order", "INT");
        ensureColumn(connection, "room_live_gift_catalog", "room_id", "VARCHAR(255)");
        String snapshotFlag = h2 ? "BOOLEAN DEFAULT FALSE NOT NULL" : "BOOLEAN NOT NULL DEFAULT FALSE";
        ensureColumn(connection, "room_live_session_stats", "imported_snapshot", snapshotFlag);
        dropSingleColumnUniqueGiftId(connection, h2);
        dropLegacyRoomIdIndex(connection, h2);
    }

    private void applyPublishTaskSchema(Connection connection, boolean h2) {
        ensureColumn(connection, "bili_bili_user", "publish_risk_failures", "INT DEFAULT 0");
        ensureColumn(connection, "bili_bili_user", "publish_cooldown_until", "TIMESTAMP NULL");
        ensureColumn(connection, "bili_bili_user", "publish_last_risk_at", "TIMESTAMP NULL");
        ensureColumn(connection, "bili_bili_user", "publish_success_streak", "INT DEFAULT 0");
        ensureColumn(connection, "bili_bili_user", "publish_next_allowed_at", "TIMESTAMP NULL");
        ensureColumn(connection, "bili_bili_user", "publish_captcha_probe_task_id", "BIGINT NULL");
        ensureColumn(connection, "bili_bili_user", "publish_captcha_retry_at", "TIMESTAMP NULL");
        backfillZero("bili_bili_user", "publish_risk_failures");
        backfillZero("bili_bili_user", "publish_success_streak");
        String snapshotType = h2 ? "CLOB" : "LONGTEXT";
        String idDefinition = h2 ? "BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY"
                : "BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY";
        jdbc.execute("CREATE TABLE IF NOT EXISTS publish_task (id " + idDefinition + ")");
        ensureColumn(connection, "publish_task", "history_id", "BIGINT NULL");
        ensureColumn(connection, "publish_task", "account_id", "BIGINT NULL");
        ensureColumn(connection, "publish_task", "operation", "VARCHAR(32) NOT NULL DEFAULT 'NEW_PUBLISH'");
        ensureColumn(connection, "publish_task", "source", "VARCHAR(16) NOT NULL DEFAULT 'AUTOMATIC'");
        ensureColumn(connection, "publish_task", "state", "VARCHAR(24) NOT NULL DEFAULT 'READY'");
        ensureColumn(connection, "publish_task", "wait_reason", "VARCHAR(64) NULL");
        ensureColumn(connection, "publish_task", "created_at", "TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP");
        ensureColumn(connection, "publish_task", "updated_at", "TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP");
        ensureColumn(connection, "publish_task", "next_attempt_at", "TIMESTAMP NULL");
        ensureColumn(connection, "publish_task", "retry_count", "INT NOT NULL DEFAULT 0");
        ensureColumn(connection, "publish_task", "result_message", "VARCHAR(2000) NULL");
        ensureColumn(connection, "publish_task", "request_snapshot", snapshotType + " NULL");
        ensureColumn(connection, "publish_task", "claim_token", "VARCHAR(80) NULL");
        ensureColumn(connection, "publish_task", "lease_until", "TIMESTAMP NULL");
        ensureColumn(connection, "publish_task", "task_version", "BIGINT NOT NULL DEFAULT 0");
        ensureColumn(connection, "publish_task", "captcha_retry_count", "INT NOT NULL DEFAULT 0");
        ensureColumn(connection, "publish_task", "captcha_retry_pending", "BOOLEAN NOT NULL DEFAULT FALSE");
        backfillZero("publish_task", "retry_count");
        backfillZero("publish_task", "task_version");
        backfillZero("publish_task", "captcha_retry_count");
        backfillFalse("publish_task", "captcha_retry_pending");
        if (!columnExists(connection, "publish_task", "id")) {
            throw new IllegalStateException("publish_task 表缺少主键 id，无法安全自动修复");
        }
        if (!primaryKeyExists(connection, "publish_task", "id")) {
            throw new IllegalStateException("publish_task.id 不是主键，无法安全领取投稿任务");
        }
        ensureIndex(connection, "publish_task", PUBLISH_INDEXES[0], "account_id,state,created_at");
        ensureIndex(connection, "publish_task", PUBLISH_INDEXES[1], "history_id,state");
        ensureIndex(connection, "publish_task", PUBLISH_INDEXES[2], "state,next_attempt_at");
    }

    private void applyWebhookInboxSchema(Connection connection, boolean h2) {
        String payloadType = h2 ? "CLOB" : "LONGTEXT";
        String idDefinition = h2 ? "BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY"
                : "BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY";
        jdbc.execute("CREATE TABLE IF NOT EXISTS record_webhook_inbox (id " + idDefinition + ")");
        ensureColumn(connection, "record_webhook_inbox", "event_key", "VARCHAR(64) NULL");
        ensureColumn(connection, "record_webhook_inbox", "lock_key", "VARCHAR(512) NOT NULL DEFAULT 'unknown'");
        ensureColumn(connection, "record_webhook_inbox", "event_type", "VARCHAR(128) NULL");
        ensureColumn(connection, "record_webhook_inbox", "source_name", "VARCHAR(32) NOT NULL DEFAULT 'unknown'");
        ensureColumn(connection, "record_webhook_inbox", "delay_ms", "BIGINT NOT NULL DEFAULT 0");
        ensureColumn(connection, "record_webhook_inbox", "payload", payloadType + " NULL");
        ensureColumn(connection, "record_webhook_inbox", "state", "VARCHAR(16) NOT NULL DEFAULT 'PENDING'");
        ensureColumn(connection, "record_webhook_inbox", "attempt_count", "INT NOT NULL DEFAULT 0");
        ensureColumn(connection, "record_webhook_inbox", "next_attempt_at", "TIMESTAMP NULL");
        ensureColumn(connection, "record_webhook_inbox", "received_at", "TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP");
        ensureColumn(connection, "record_webhook_inbox", "updated_at", "TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP");
        ensureColumn(connection, "record_webhook_inbox", "error_message", "VARCHAR(2000) NULL");
        ensureColumn(connection, "record_webhook_inbox", "event_version", "BIGINT NOT NULL DEFAULT 0");
        ensureIndex(connection, "record_webhook_inbox", WEBHOOK_INDEXES[0], "state,next_attempt_at,id");
        ensureIndex(connection, "record_webhook_inbox", WEBHOOK_INDEXES[1], "updated_at");
        if (!primaryKeyExists(connection, "record_webhook_inbox", "id")) {
            throw new IllegalStateException("record_webhook_inbox.id 不是主键，无法安全恢复 webhook");
        }
        ensureUniqueIndex(connection, "record_webhook_inbox", "uk_webhook_inbox_event_key", "event_key");
    }

    private void applyPostPublishSchema(Connection connection, boolean h2) {
        String textType = h2 ? "CLOB" : "LONGTEXT";
        String idDefinition = h2 ? "BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY"
                : "BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY";

        jdbc.execute("CREATE TABLE IF NOT EXISTS video_comment_task (id " + idDefinition + ")");
        ensureColumn(connection, "video_comment_task", "history_id", "BIGINT NOT NULL DEFAULT 0");
        ensureColumn(connection, "video_comment_task", "account_id", "BIGINT NOT NULL DEFAULT 0");
        ensureColumn(connection, "video_comment_task", "sequence_no", "INT NOT NULL DEFAULT 0");
        ensureColumn(connection, "video_comment_task", "request_hash", "VARCHAR(64) NOT NULL DEFAULT ''");
        ensureColumn(connection, "video_comment_task", "aid", "VARCHAR(32) NOT NULL DEFAULT ''");
        ensureColumn(connection, "video_comment_task", "content", textType + " NULL");
        ensureColumn(connection, "video_comment_task", "state", "VARCHAR(20) NOT NULL DEFAULT 'READY'");
        ensureColumn(connection, "video_comment_task", "remote_rpid", "VARCHAR(40) NULL");
        ensureColumn(connection, "video_comment_task", "root_rpid", "VARCHAR(40) NULL");
        ensureColumn(connection, "video_comment_task", "pin_state", "VARCHAR(16) NOT NULL DEFAULT 'NONE'");
        ensureColumn(connection, "video_comment_task", "error_message", "VARCHAR(2000) NULL");
        ensureColumn(connection, "video_comment_task", "created_at", "TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP");
        ensureColumn(connection, "video_comment_task", "updated_at", "TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP");
        ensureColumn(connection, "video_comment_task", "task_version", "BIGINT NOT NULL DEFAULT 0");
        ensureIndex(connection, "video_comment_task", COMMENT_INDEXES[0], "history_id,state");
        ensureUniqueIndex(connection, "video_comment_task", COMMENT_INDEXES[1], "history_id,sequence_no");

        jdbc.execute("CREATE TABLE IF NOT EXISTS video_visibility_restore_task (id " + idDefinition + ")");
        ensureColumn(connection, "video_visibility_restore_task", "history_id", "BIGINT NOT NULL DEFAULT 0");
        ensureColumn(connection, "video_visibility_restore_task", "account_id", "BIGINT NOT NULL DEFAULT 0");
        ensureColumn(connection, "video_visibility_restore_task", "aid", "VARCHAR(32) NOT NULL DEFAULT ''");
        ensureColumn(connection, "video_visibility_restore_task", "restore_visibility", "INT NOT NULL DEFAULT 1");
        ensureColumn(connection, "video_visibility_restore_task", "state", "VARCHAR(16) NOT NULL DEFAULT 'PENDING'");
        ensureColumn(connection, "video_visibility_restore_task", "attempt_count", "INT NOT NULL DEFAULT 0");
        ensureColumn(connection, "video_visibility_restore_task", "next_attempt_at", "TIMESTAMP NULL");
        ensureColumn(connection, "video_visibility_restore_task", "error_message", "VARCHAR(2000) NULL");
        ensureColumn(connection, "video_visibility_restore_task", "created_at", "TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP");
        ensureColumn(connection, "video_visibility_restore_task", "updated_at", "TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP");
        ensureColumn(connection, "video_visibility_restore_task", "task_version", "BIGINT NOT NULL DEFAULT 0");
        ensureIndex(connection, "video_visibility_restore_task", VISIBILITY_INDEXES[0], "state,next_attempt_at");
        ensureUniqueIndex(connection, "video_visibility_restore_task", VISIBILITY_INDEXES[1], "history_id");
    }

    private void applyDeleteAndInstanceSchema(Connection connection, boolean h2) {
        ensureColumn(connection, "record_history", "delete_pending", "BOOLEAN NOT NULL DEFAULT FALSE");
        String idDefinition = h2 ? "BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY"
                : "BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY";

        jdbc.execute("CREATE TABLE IF NOT EXISTS history_deletion_task (id " + idDefinition + ")");
        ensureColumn(connection, "history_deletion_task", "history_id", "BIGINT NOT NULL DEFAULT 0");
        ensureColumn(connection, "history_deletion_task", "room_id", "VARCHAR(255) NULL");
        ensureColumn(connection, "history_deletion_task", "delete_video", "BOOLEAN NOT NULL DEFAULT FALSE");
        ensureColumn(connection, "history_deletion_task", "delete_danmaku", "BOOLEAN NOT NULL DEFAULT FALSE");
        ensureColumn(connection, "history_deletion_task", "delete_cover", "BOOLEAN NOT NULL DEFAULT FALSE");
        ensureColumn(connection, "history_deletion_task", "state", "VARCHAR(24) NOT NULL DEFAULT 'PENDING'");
        ensureColumn(connection, "history_deletion_task", "attempt_count", "INT NOT NULL DEFAULT 0");
        ensureColumn(connection, "history_deletion_task", "next_attempt_at", "TIMESTAMP NULL");
        ensureColumn(connection, "history_deletion_task", "last_error", "VARCHAR(2000) NULL");
        ensureColumn(connection, "history_deletion_task", "created_at", "TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP");
        ensureColumn(connection, "history_deletion_task", "updated_at", "TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP");
        ensureColumn(connection, "history_deletion_task", "task_version", "BIGINT NOT NULL DEFAULT 0");
        ensureIndex(connection, "history_deletion_task", HISTORY_DELETE_INDEXES[0], "state,next_attempt_at,id");
        ensureUniqueIndex(connection, "history_deletion_task", HISTORY_DELETE_INDEXES[1], "history_id");

    }

    private void applyHistoryDeletionCancellationSchema(Connection connection) {
        ensureColumn(connection, "history_deletion_task", "deletion_started",
                "BOOLEAN NOT NULL DEFAULT TRUE");
    }

    private void validateRequiredSchema(Connection connection) throws SQLException {
        for (String column : List.of("id", "history_id", "account_id", "operation", "source", "state",
                "wait_reason", "created_at", "updated_at", "next_attempt_at", "retry_count", "result_message",
                "request_snapshot", "claim_token", "lease_until", "task_version", "captcha_retry_count",
                "captcha_retry_pending")) {
            if (!columnExists(connection, "publish_task", column)) {
                throw new IllegalStateException("投稿任务表缺少必要字段 publish_task." + column);
            }
        }
        for (String index : PUBLISH_INDEXES) {
            if (!indexExists(connection, "publish_task", index)) {
                throw new IllegalStateException("投稿任务表缺少必要索引 " + index);
            }
        }
        for (String column : List.of("id", "event_key", "lock_key", "event_type", "source_name", "delay_ms",
                "payload", "state", "attempt_count", "next_attempt_at", "received_at", "updated_at",
                "error_message", "event_version")) {
            if (!columnExists(connection, "record_webhook_inbox", column)) {
                throw new IllegalStateException("webhook 收件箱缺少必要字段 record_webhook_inbox." + column);
            }
        }
        for (String index : WEBHOOK_INDEXES) {
            if (!indexExists(connection, "record_webhook_inbox", index)) {
                throw new IllegalStateException("webhook 收件箱缺少必要索引 " + index);
            }
        }
        validateColumns(connection, "video_comment_task", List.of("id", "history_id", "account_id", "sequence_no",
                "request_hash", "aid", "content", "state", "remote_rpid", "root_rpid", "pin_state",
                "error_message", "created_at", "updated_at", "task_version"));
        validateIndexes(connection, "video_comment_task", COMMENT_INDEXES);
        validateColumns(connection, "video_visibility_restore_task", List.of("id", "history_id", "account_id",
                "aid", "restore_visibility", "state", "attempt_count", "next_attempt_at", "error_message",
                "created_at", "updated_at", "task_version"));
        validateIndexes(connection, "video_visibility_restore_task", VISIBILITY_INDEXES);
        validateColumns(connection, "history_deletion_task", List.of("id", "history_id", "room_id",
                "delete_video", "delete_danmaku", "delete_cover", "state", "attempt_count",
                "next_attempt_at", "last_error", "created_at", "updated_at", "task_version", "deletion_started"));
        validateIndexes(connection, "history_deletion_task", HISTORY_DELETE_INDEXES);
        if (tableExists(connection, "record_history")
                && !columnExists(connection, "record_history", "delete_pending")) {
            throw new IllegalStateException("稿件表缺少必要字段 record_history.delete_pending");
        }
        if (tableExists(connection, "bili_bili_user")) {
            for (String column : List.of("publish_risk_failures", "publish_cooldown_until",
                    "publish_last_risk_at", "publish_success_streak", "publish_next_allowed_at",
                    "publish_captcha_probe_task_id", "publish_captcha_retry_at")) {
                if (!columnExists(connection, "bili_bili_user", column)) {
                    throw new IllegalStateException("账号表缺少必要字段 bili_bili_user." + column);
                }
            }
        }
    }

    private void validateColumns(Connection connection, String table, List<String> columns) {
        for (String column : columns) {
            if (!columnExists(connection, table, column)) {
                throw new IllegalStateException("兼容表缺少必要字段 " + table + "." + column);
            }
        }
    }

    private void validateIndexes(Connection connection, String table, String[] indexes) {
        for (String index : indexes) {
            if (!indexExists(connection, table, index)) {
                throw new IllegalStateException("兼容表缺少必要索引 " + index);
            }
        }
    }

    private void ensureColumn(Connection connection, String table, String column, String definition) {
        if (!tableExists(connection, table)) return;
        if (columnExists(connection, table, column)) return;
        try {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        } catch (RuntimeException error) {
            if (!columnExists(connection, table, column)) throw error;
        }
    }

    private void backfillZero(String table, String column) {
        if (tableExistsCurrent(table) && columnExistsCurrent(table, column)) {
            jdbc.update("UPDATE " + table + " SET " + column + " = 0 WHERE " + column + " IS NULL");
        }
    }

    private void backfillFalse(String table, String column) {
        if (tableExistsCurrent(table) && columnExistsCurrent(table, column)) {
            jdbc.update("UPDATE " + table + " SET " + column + " = FALSE WHERE " + column + " IS NULL");
        }
    }

    private void ensureIndex(Connection connection, String table, String index, String columns) {
        if (!tableExists(connection, table) || indexExists(connection, table, index)) return;
        jdbc.execute("CREATE INDEX " + index + " ON " + table + " (" + columns + ")");
    }

    private void ensureUniqueIndex(Connection connection, String table, String index, String columns) {
        if (!tableExists(connection, table)) return;
        if (indexIsUnique(connection, table, index)) return;
        if (indexExists(connection, table, index)) {
            if (isH2(connection)) {
                jdbc.execute("DROP INDEX " + quote(connection, index));
            } else {
                jdbc.execute("ALTER TABLE " + table + " DROP INDEX " + quote(connection, index));
            }
        }
        jdbc.execute("CREATE UNIQUE INDEX " + index + " ON " + table + " (" + columns + ")");
    }

    private boolean isH2(Connection connection) {
        try {
            String product = connection.getMetaData().getDatabaseProductName();
            return product != null && product.toLowerCase(Locale.ROOT).contains("h2");
        } catch (SQLException e) {
            throw new IllegalStateException("无法识别数据库类型", e);
        }
    }

    private boolean indexIsUnique(Connection connection, String table, String expectedName) {
        try (ResultSet indexes = connection.getMetaData().getIndexInfo(connection.getCatalog(),
                connection.getSchema(), metadataTableName(connection, table), false, false)) {
            while (indexes.next()) {
                String name = indexes.getString("INDEX_NAME");
                if (name != null && expectedName.equalsIgnoreCase(name)) return indexes.getBoolean("NON_UNIQUE") == false;
            }
            return false;
        } catch (SQLException e) {
            throw new IllegalStateException("无法检查唯一索引 " + expectedName, e);
        }
    }

    private void dropLegacyRoomIdIndex(Connection connection, boolean h2) {
        for (String table : List.of("record_history", "record_history_part")) {
            if (!tableExists(connection, table)) continue;
            for (String index : indexNames(connection, table)) {
                if (!"idx_room_id".equalsIgnoreCase(index)) continue;
                if (h2) jdbc.execute("DROP INDEX " + quote(connection, index));
                else jdbc.execute("ALTER TABLE " + table + " DROP INDEX " + quote(connection, index));
            }
        }
    }

    private void dropSingleColumnUniqueGiftId(Connection connection, boolean h2) {
        String table = "room_live_gift_catalog";
        if (!tableExists(connection, table)) return;
        DatabaseMetaData meta;
        try {
            meta = connection.getMetaData();
            Map<String, List<String>> unique = new java.util.LinkedHashMap<>();
            try (ResultSet indexes = meta.getIndexInfo(connection.getCatalog(), connection.getSchema(),
                    metadataTableName(connection, table),
                    true, false)) {
                while (indexes.next()) {
                    String name = indexes.getString("INDEX_NAME");
                    String column = indexes.getString("COLUMN_NAME");
                    if (name != null && column != null) {
                        unique.computeIfAbsent(name, ignored -> new ArrayList<>()).add(column);
                    }
                }
            }
            boolean uniqueGiftId = unique.values().stream().anyMatch(columns -> columns.size() == 1
                    && "gift_id".equalsIgnoreCase(columns.get(0)));
            if (!uniqueGiftId) return;
            if (h2) {
                List<String> constraints = uniqueConstraintNames(connection, table);
                for (String constraint : constraints) {
                    jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT " + quote(connection, constraint));
                }
                if (constraints.isEmpty()) {
                    unique.forEach((name, columns) -> {
                        if (columns.size() == 1 && "gift_id".equalsIgnoreCase(columns.get(0))) {
                            jdbc.execute("DROP INDEX " + quote(connection, name));
                        }
                    });
                }
            } else {
                unique.forEach((name, columns) -> {
                    if (columns.size() == 1 && "gift_id".equalsIgnoreCase(columns.get(0))) {
                        jdbc.execute("ALTER TABLE " + table + " DROP INDEX " + quote(connection, name));
                    }
                });
            }
        } catch (SQLException e) {
            throw new IllegalStateException("无法检查礼物目录 gift_id 唯一索引", e);
        }
    }

    private List<String> uniqueConstraintNames(Connection connection, String table) {
        try {
            return jdbc.queryForList("SELECT T.CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS T "
                            + "JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE K "
                            + "ON T.CONSTRAINT_CATALOG=K.CONSTRAINT_CATALOG "
                            + "AND T.CONSTRAINT_SCHEMA=K.CONSTRAINT_SCHEMA "
                            + "AND T.CONSTRAINT_NAME=K.CONSTRAINT_NAME "
                            + "WHERE UPPER(T.TABLE_NAME)=UPPER(?) AND UPPER(T.CONSTRAINT_TYPE)='UNIQUE' "
                            + "GROUP BY T.CONSTRAINT_NAME HAVING COUNT(*)=1 "
                            + "AND MAX(UPPER(K.COLUMN_NAME))='GIFT_ID'", String.class, table);
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private List<String> indexNames(Connection connection, String table) {
        try {
            List<String> result = new ArrayList<>();
            try (ResultSet indexes = connection.getMetaData().getIndexInfo(connection.getCatalog(),
                    connection.getSchema(), metadataTableName(connection, table), false, false)) {
                while (indexes.next()) {
                    String name = indexes.getString("INDEX_NAME");
                    if (name != null) result.add(name);
                }
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("无法检查表索引 " + table, e);
        }
    }

    private boolean indexExists(Connection connection, String table, String index) {
        return indexNames(connection, table).stream().anyMatch(name -> name.equalsIgnoreCase(index));
    }

    private boolean primaryKeyExists(Connection connection, String table, String column) {
        try (ResultSet keys = connection.getMetaData().getPrimaryKeys(connection.getCatalog(),
                connection.getSchema(), metadataTableName(connection, table))) {
            while (keys.next()) {
                if (column.equalsIgnoreCase(keys.getString("COLUMN_NAME"))) return true;
            }
            return false;
        } catch (SQLException e) {
            throw new IllegalStateException("无法检查投稿任务主键", e);
        }
    }

    private String metadataTableName(Connection connection, String table) throws SQLException {
        try (ResultSet tables = connection.getMetaData().getTables(connection.getCatalog(), connection.getSchema(),
                "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                String name = tables.getString("TABLE_NAME");
                if (name != null && table.equalsIgnoreCase(name)) return name;
            }
        }
        return table;
    }

    private boolean tableExists(Connection connection, String table) {
        try (ResultSet tables = connection.getMetaData().getTables(connection.getCatalog(), connection.getSchema(),
                "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                if (table.equalsIgnoreCase(tables.getString("TABLE_NAME"))) return true;
            }
            return false;
        } catch (SQLException e) {
            throw new IllegalStateException("无法检查数据库表 " + table, e);
        }
    }

    private boolean tableExistsCurrent(String table) {
        try (Connection c = dataSource.getConnection()) { return tableExists(c, table); }
        catch (SQLException e) { throw new IllegalStateException(e); }
    }

    private boolean columnExists(Connection connection, String table, String column) {
        try (ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), connection.getSchema(),
                "%", "%")) {
            while (columns.next()) {
                if (table.equalsIgnoreCase(columns.getString("TABLE_NAME"))
                        && column.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) return true;
            }
            return false;
        } catch (SQLException e) {
            throw new IllegalStateException("无法检查数据库字段 " + table + "." + column, e);
        }
    }

    private boolean columnExistsCurrent(String table, String column) {
        try (Connection c = dataSource.getConnection()) { return columnExists(c, table, column); }
        catch (SQLException e) { throw new IllegalStateException(e); }
    }

    private String quote(Connection connection, String identifier) {
        try {
            String quote = connection.getMetaData().getIdentifierQuoteString();
            if (quote == null || quote.isBlank()) return identifier;
            String marker = quote.trim();
            return marker + identifier.replace(marker, marker + marker) + marker;
        } catch (SQLException e) {
            return identifier;
        }
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

}
