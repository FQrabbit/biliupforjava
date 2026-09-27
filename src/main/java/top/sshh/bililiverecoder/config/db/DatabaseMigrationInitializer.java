package top.sshh.bililiverecoder.config.db;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.File;
import java.nio.file.Files;
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

/**
 * 在 Hibernate 创建 EntityManagerFactory 前完成数据库兼容升级，重复执行也不会重复修改
 * 迁移一旦发布，就不要再修改它的版本号和签名，后续改动需要新增一条迁移
 */
@Slf4j
@Component("databaseMigrationInitializer")
public class DatabaseMigrationInitializer implements InitializingBean {
    private static final String HISTORY_TABLE = "app_schema_migration";
    private static final String LEGACY_SIGNATURE = "history-force-archive-skip-order-gift-room-snapshot-v1";
    private static final String PUBLISH_SIGNATURE = "publish-task-cooldown-and-captcha-retry-columns-v1";
    private static final String[] PUBLISH_INDEXES = {
            "idx_publish_task_account_state_order",
            "idx_publish_task_history_state",
            "idx_publish_task_state_due"
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
            ensureMigrationTable();
            applyMigration(connection, "20260928_01", "历史数据库兼容修复", LEGACY_SIGNATURE,
                    () -> applyLegacyCompatibility(connection, h2));
            applyMigration(connection, "20260928_02", "投稿任务及验证码恢复字段", PUBLISH_SIGNATURE,
                    () -> applyPublishTaskSchema(connection, h2));
            validateRequiredSchema(connection);
            log.info("Database compatibility migrations completed for {}", product);
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
        Files.createDirectories(backupDir.toPath());
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        String path = new File(backupDir, "biliupforjava-DBbackup-" + stamp + ".zip")
                .getAbsolutePath().replace("\\", "/").replace("'", "''");
        jdbc.execute("BACKUP TO '" + path + "'");
        log.info("Created pre-migration H2 backup at {}", path);
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

    private void ensureColumn(Connection connection, String table, String column, String definition) {
        if (!tableExists(connection, table)) return;
        if (columnExists(connection, table, column)) return;
        jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
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
