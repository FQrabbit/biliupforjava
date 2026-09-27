package top.sshh.bililiverecoder.config.db;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.h2.jdbcx.JdbcDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseMigrationInitializerTest {
    @Test
    void migratesSeveralOldTablesAndCanBeReplayedAfterAnInterruptedUpgrade() throws Exception {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true).build();
        Path workPath = Files.createTempDirectory("biliup-db-migration-");
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            jdbc.execute("CREATE TABLE bili_bili_user (id BIGINT PRIMARY KEY, login BOOLEAN)");
            jdbc.execute("INSERT INTO bili_bili_user (id, login) VALUES (10, TRUE)");
            jdbc.execute("CREATE TABLE record_history (id BIGINT PRIMARY KEY, title VARCHAR(255))");
            jdbc.execute("INSERT INTO record_history (id, title) VALUES (20, 'existing')");
            jdbc.execute("CREATE TABLE record_history_part (id BIGINT PRIMARY KEY)");
            jdbc.execute("CREATE TABLE room_live_gift_catalog (id BIGINT PRIMARY KEY, gift_id BIGINT UNIQUE)");
            jdbc.execute("CREATE TABLE room_live_session_stats (id BIGINT PRIMARY KEY)");
            jdbc.execute("CREATE INDEX idx_room_id ON record_history (id)");

            DatabaseMigrationInitializer initializer = new DatabaseMigrationInitializer(database, jdbc,
                    workPath.toString());
            initializer.afterPropertiesSet();

            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM app_schema_migration WHERE success=TRUE", Integer.class));
            assertColumn(jdbc, "record_history", "force_archived");
            assertColumn(jdbc, "record_history_part", "source_part_order");
            assertColumn(jdbc, "room_live_session_stats", "imported_snapshot");
            assertColumn(jdbc, "bili_bili_user", "publish_captcha_probe_task_id");
            assertColumn(jdbc, "publish_task", "captcha_retry_pending");
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                    + "WHERE UPPER(TABLE_NAME)='ROOM_LIVE_GIFT_CATALOG' AND UPPER(CONSTRAINT_TYPE)='UNIQUE'",
                    Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES "
                    + "WHERE UPPER(INDEX_NAME)='IDX_ROOM_ID'", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM bili_bili_user WHERE id=10 AND login=TRUE",
                    Integer.class));
            assertEquals("existing", jdbc.queryForObject("SELECT title FROM record_history WHERE id=20", String.class));

            jdbc.update("INSERT INTO publish_task (history_id, account_id, operation, source, state, created_at, updated_at) "
                    + "VALUES (20, 10, 'NEW_PUBLISH', 'MANUAL', 'READY', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            Long taskId = jdbc.queryForObject("SELECT id FROM publish_task WHERE history_id=20", Long.class);
            jdbc.execute("DELETE FROM app_schema_migration WHERE version_id='20260928_02'");
            jdbc.execute("ALTER TABLE publish_task DROP COLUMN result_message");

            initializer.afterPropertiesSet();

            assertColumn(jdbc, "publish_task", "result_message");
            assertEquals(taskId, jdbc.queryForObject("SELECT id FROM publish_task WHERE history_id=20", Long.class));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM app_schema_migration WHERE success=TRUE", Integer.class));
        } finally {
            database.shutdown();
            try (var paths = Files.walk(workPath)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void entityManagerFactoryDependsOnDatabaseMigrationInitializer() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBeanDefinition("entityManagerFactory", new org.springframework.beans.factory.support.RootBeanDefinition(Object.class));
            BeanDefinition definition = context.getBeanFactory().getBeanDefinition("entityManagerFactory");

            new DatabaseMigrationJpaDependencyPostProcessor().postProcessBeanFactory(context.getBeanFactory());

            assertTrue(Arrays.asList(definition.getDependsOn()).contains("databaseMigrationInitializer"));
            assertArrayEquals(new String[]{"databaseMigrationInitializer"}, definition.getDependsOn());
        }
    }

    @Test
    void fileDatabaseIsBackedUpBeforeMigrationAndNotOnEveryNormalStartup() throws Exception {
        Path root = Files.createTempDirectory("biliup-db-file-migration-");
        Path databasePath = root.resolve("legacy-db");
        Path workPath = root.resolve("work");
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:file:" + databasePath.toString().replace("\\", "/"));
        dataSource.setUser("sa");
        dataSource.setPassword("");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try {
            jdbc.execute("CREATE TABLE app_probe (id BIGINT PRIMARY KEY)");
            DatabaseMigrationInitializer initializer = new DatabaseMigrationInitializer(dataSource, jdbc,
                    workPath.toString());

            initializer.afterPropertiesSet();

            Path backupDir = workPath.resolve("backup");
            assertEquals(1, countBackups(backupDir));
            initializer.afterPropertiesSet();
            assertEquals(1, countBackups(backupDir));
        } finally {
            try { jdbc.execute("SHUTDOWN"); } catch (RuntimeException ignored) { }
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    private static long countBackups(Path backupDir) throws Exception {
        if (!Files.exists(backupDir)) return 0;
        try (var files = Files.list(backupDir)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".zip")).count();
        }
    }

    private static void assertColumn(JdbcTemplate jdbc, String table, String column) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE UPPER(TABLE_NAME)=UPPER(?) AND UPPER(COLUMN_NAME)=UPPER(?)", Integer.class, table, column);
        assertEquals(1, count, table + "." + column + " should exist");
    }
}
