package top.sshh.bililiverecoder.config.db;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.util.LogKvs;

import javax.sql.DataSource;
import java.sql.Connection;

@Slf4j
@Component
@Order(10)
public class DatabaseSchemaColumnPatchRunner implements ApplicationRunner {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public DatabaseSchemaColumnPatchRunner(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection()) {
            String url = connection.getMetaData().getURL();
            if (url == null || !url.contains(":h2:")) {
                return;
            }

            ensureColumnIfMissing("record_history", "force_archived", "BOOLEAN DEFAULT FALSE");
            ensureColumnIfMissing("record_history", "publish_user_id", "BIGINT");
            ensureColumnIfMissing("record_history_part", "manual_skip", "BOOLEAN DEFAULT FALSE");
            ensureColumnIfMissing("record_history_part", "skip_reason", "VARCHAR(255)");
        } catch (Exception e) {
            log.warn("[BLR] {}", LogKvs.event("Database.Schema.ColumnPatch.Failed")
                    .add("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()), e);
        }
    }

    private void ensureColumnIfMissing(String tableName, String columnName, String definition) {
        if (!tableExists(tableName)) {
            log.info("[BLR] {}", LogKvs.event("Database.Schema.ColumnPatch.Skip")
                    .add("table", tableName)
                    .add("column", columnName)
                    .add("reason", "table-not-found"));
            return;
        }

        Integer columnCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE UPPER(TABLE_NAME) = ? AND UPPER(COLUMN_NAME) = ?",
                Integer.class,
                tableName.toUpperCase(),
                columnName.toUpperCase()
        );

        if (columnCount != null && columnCount > 0) {
            return;
        }

        String sql = "ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + definition;
        log.info("[BLR] {}", LogKvs.event("Database.Schema.ColumnPatch.Start")
                .add("table", tableName)
                .add("column", columnName)
                .add("sql", sql));
        jdbcTemplate.execute(sql);
        log.info("[BLR] {}", LogKvs.event("Database.Schema.ColumnPatch.Success")
                .add("table", tableName)
                .add("column", columnName));
    }

    private boolean tableExists(String tableName) {
        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME) = ?",
                Integer.class,
                tableName.toUpperCase()
        );
        return tableCount != null && tableCount > 0;
    }
}
