package top.sshh.bililiverecoder.config.db;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.h2.jdbcx.JdbcDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

            assertEquals(6, jdbc.queryForObject("SELECT COUNT(*) FROM app_schema_migration WHERE success=TRUE", Integer.class));
            assertColumn(jdbc, "record_history", "force_archived");
            assertColumn(jdbc, "record_history_part", "source_part_order");
            assertColumn(jdbc, "room_live_session_stats", "imported_snapshot");
            assertColumn(jdbc, "bili_bili_user", "publish_captcha_probe_task_id");
            assertColumn(jdbc, "publish_task", "captcha_retry_pending");
            assertColumn(jdbc, "record_webhook_inbox", "payload");
            assertColumn(jdbc, "video_comment_task", "remote_rpid");
            assertColumn(jdbc, "video_visibility_restore_task", "restore_visibility");
            assertColumn(jdbc, "record_history", "delete_pending");
            assertColumn(jdbc, "history_deletion_task", "attempt_count");
            assertColumn(jdbc, "history_deletion_task", "deletion_started");
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                    + "WHERE UPPER(TABLE_NAME)='RECORD_WEBHOOK_INBOX'", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES "
                    + "WHERE UPPER(INDEX_NAME)='UK_WEBHOOK_INBOX_EVENT_KEY'", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES "
                    + "WHERE UPPER(INDEX_NAME)='UK_VIDEO_COMMENT_HISTORY_SEQUENCE'", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES "
                    + "WHERE UPPER(INDEX_NAME)='UK_VISIBILITY_RESTORE_HISTORY'", Integer.class));
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
            jdbc.update("INSERT INTO history_deletion_task (history_id, room_id, delete_video, delete_danmaku, delete_cover, "
                    + "state, attempt_count, created_at, updated_at, task_version, deletion_started) "
                    + "VALUES (99, 'room', FALSE, FALSE, FALSE, 'RETRY_WAIT', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, TRUE)");
            jdbc.execute("DELETE FROM app_schema_migration WHERE version_id='20260928_02'");
            jdbc.execute("ALTER TABLE publish_task DROP COLUMN result_message");
            jdbc.execute("DELETE FROM app_schema_migration WHERE version_id='20260928_06'");
            jdbc.execute("ALTER TABLE history_deletion_task DROP COLUMN deletion_started");

            initializer.afterPropertiesSet();

            assertColumn(jdbc, "publish_task", "result_message");
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM history_deletion_task "
                    + "WHERE history_id=99 AND deletion_started=TRUE", Integer.class));
            assertEquals(taskId, jdbc.queryForObject("SELECT id FROM publish_task WHERE history_id=20", Long.class));
            assertEquals(6, jdbc.queryForObject("SELECT COUNT(*) FROM app_schema_migration WHERE success=TRUE", Integer.class));
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
    void legacyRuntimeLeaseDoesNotBlockStartup() throws Exception {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true).build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        Path workPath = Files.createTempDirectory("biliup-db-legacy-lease-");
        createLegacyTables(jdbc);
        jdbc.execute("CREATE TABLE app_instance_lease (lock_id INT PRIMARY KEY, owner_token VARCHAR(64), lease_until TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO app_instance_lease (lock_id, owner_token, lease_until) VALUES (1, 'old-owner', DATEADD('DAY', 1, CURRENT_TIMESTAMP))");
        DatabaseMigrationInitializer first = new DatabaseMigrationInitializer(database, jdbc,
                workPath.toString());
        DatabaseMigrationInitializer second = new DatabaseMigrationInitializer(database, jdbc,
                workPath.toString());
        try {
            first.afterPropertiesSet();
            second.afterPropertiesSet();
            assertEquals("old-owner", jdbc.queryForObject(
                    "SELECT owner_token FROM app_instance_lease WHERE lock_id=1", String.class));
        } finally {
            database.shutdown();
            deleteTree(workPath);
        }
    }

    @Test
    void concurrentFirstStartsBothCompleteSchemaMigration() throws Exception {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true).build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        Path workPath = Files.createTempDirectory("biliup-db-concurrent-migration-");
        createLegacyTables(jdbc);
        DatabaseMigrationInitializer first = new DatabaseMigrationInitializer(database, jdbc,
                workPath.toString());
        DatabaseMigrationInitializer second = new DatabaseMigrationInitializer(database, jdbc,
                workPath.toString());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> firstStart = executor.submit(() -> initializeAfter(start, first));
            Future<String> secondStart = executor.submit(() -> initializeAfter(start, second));
            start.countDown();

            String firstError = firstStart.get();
            String secondError = secondStart.get();
            assertEquals("SUCCESS", firstError);
            assertEquals("SUCCESS", secondError);
        } finally {
            executor.shutdownNow();
            database.shutdown();
            deleteTree(workPath);
        }
    }

    private static String initializeAfter(CountDownLatch start, DatabaseMigrationInitializer initializer)
            throws InterruptedException {
        start.await();
        try {
            initializer.afterPropertiesSet();
            return "SUCCESS";
        } catch (BeanCreationException error) {
            Throwable cause = error.getCause();
            return cause == null ? error.getMessage() : cause.getMessage();
        }
    }

    private static void createLegacyTables(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE bili_bili_user (id BIGINT PRIMARY KEY, login BOOLEAN)");
        jdbc.execute("CREATE TABLE record_history (id BIGINT PRIMARY KEY, title VARCHAR(255))");
        jdbc.execute("CREATE TABLE record_history_part (id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE room_live_gift_catalog (id BIGINT PRIMARY KEY, gift_id BIGINT UNIQUE)");
        jdbc.execute("CREATE TABLE room_live_session_stats (id BIGINT PRIMARY KEY)");
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
        MigrationLogCapture logs = new MigrationLogCapture();
        try {
            jdbc.execute("CREATE TABLE app_probe (id BIGINT PRIMARY KEY)");
            jdbc.execute("CREATE TABLE record_history (id BIGINT PRIMARY KEY)");
            DatabaseMigrationInitializer initializer = new DatabaseMigrationInitializer(dataSource, jdbc,
                    workPath.toString());

            initializer.afterPropertiesSet();

            Path backupDir = workPath.resolve("backup");
            assertEquals(1, countBackups(backupDir));
            assertTrue(logs.messages.get(0).startsWith("正在备份数据库，完成后将继续启动，请勿关闭程序。备份位置："));
            assertTrue(logs.messages.get(0).contains(backupDir.toString()));
            int backupCompleted = logs.indexOf("数据库备份完成：耗时 ");
            int migrationStarted = logs.indexOf("正在检查并升级数据库结构");
            int migrationCompleted = logs.indexOf("数据库检查与升级完成，继续启动");
            assertTrue(backupCompleted > 0);
            assertTrue(logs.messages.get(backupCompleted).contains(" MB"));
            assertTrue(migrationStarted > backupCompleted);
            assertTrue(migrationCompleted > migrationStarted);
            assertEquals(-1, logs.indexOf("数据库备份进行中"));
            logs.messages.clear();
            initializer.afterPropertiesSet();
            assertEquals(1, countBackups(backupDir));
            assertEquals(-1, logs.indexOf("正在备份数据库"));
        } finally {
            logs.close();
            try { jdbc.execute("SHUTDOWN"); } catch (RuntimeException ignored) { }
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void slowFailedBackupReportsProgressAndStopsBeforeMigration() throws Exception {
        Path root = Files.createTempDirectory("biliup-db-backup-progress-");
        // 同时验证路径中的单引号会正确用于 SQL，但日志仍显示原始路径。
        Path workPath = root.resolve("work'path");
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:file:" + root.resolve("legacy-db").toString().replace("\\", "/"));
        dataSource.setUser("sa");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        MigrationLogCapture logs = new MigrationLogCapture();
        try {
            jdbc.execute("CREATE TABLE record_history (id BIGINT PRIMARY KEY)");
            JdbcTemplate slowBackupJdbc = spy(jdbc);
            doAnswer(invocation -> {
                String sql = invocation.getArgument(0);
                String sqlPath = sql.substring("BACKUP TO '".length(), sql.length() - 1).replace("''", "'");
                Files.write(Path.of(sqlPath), new byte[1024 * 1024]);
                assertTrue(logs.progressReported.await(10, TimeUnit.SECONDS), "Long backups should report progress");
                throw new DataAccessResourceFailureException("模拟备份写入失败");
            }).when(slowBackupJdbc).execute(startsWith("BACKUP TO '"));
            DatabaseMigrationInitializer initializer = new DatabaseMigrationInitializer(dataSource, slowBackupJdbc,
                    workPath.toString());

            BeanCreationException error = assertThrows(BeanCreationException.class, initializer::afterPropertiesSet);

            assertTrue(error.getCause() instanceof DataAccessResourceFailureException);
            String progress = logs.messages.get(logs.indexOf("数据库备份进行中"));
            assertTrue(progress.contains("已耗时 "));
            assertTrue(progress.contains("备份文件已写入 1.00 MB"));
            int failed = logs.indexOf("数据库备份失败，启动已中止");
            assertTrue(failed > logs.indexOf("数据库备份进行中"));
            assertTrue(logs.messages.get(failed).contains("模拟备份写入失败"));
            assertTrue(logs.messages.get(failed).contains(workPath.toString()));
            assertEquals(-1, logs.indexOf("数据库备份完成"));
            assertEquals(-1, logs.indexOf("正在检查并升级数据库结构"));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                    + "WHERE UPPER(TABLE_NAME)='APP_SCHEMA_MIGRATION'", Integer.class));
            logs.progressThread.join(2000);
            assertFalse(logs.progressThread.isAlive(), "Progress reporting must stop when backup fails");
        } finally {
            logs.close();
            try { jdbc.execute("SHUTDOWN"); } catch (RuntimeException ignored) { }
            deleteTree(root);
        }
    }

    private static final class MigrationLogCapture extends AppenderBase<ILoggingEvent> implements AutoCloseable {
        private final Logger logger = (Logger) LoggerFactory.getLogger(DatabaseMigrationInitializer.class);
        private final List<String> messages = new CopyOnWriteArrayList<>();
        private final CountDownLatch progressReported = new CountDownLatch(1);
        private volatile Thread progressThread;

        private MigrationLogCapture() {
            start();
            logger.addAppender(this);
        }

        @Override
        protected void append(ILoggingEvent event) {
            String message = event.getFormattedMessage();
            messages.add(message);
            if (message.startsWith("数据库备份进行中")) {
                progressThread = Thread.currentThread();
                progressReported.countDown();
            }
        }

        private int indexOf(String prefix) {
            for (int i = 0; i < messages.size(); i++) {
                if (messages.get(i).startsWith(prefix)) return i;
            }
            return -1;
        }

        @Override
        public void close() {
            logger.detachAppender(this);
            stop();
        }
    }

    private static long countBackups(Path backupDir) throws Exception {
        if (!Files.exists(backupDir)) return 0;
        try (var files = Files.list(backupDir)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".zip")).count();
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            });
        }
    }

    private static void assertColumn(JdbcTemplate jdbc, String table, String column) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE UPPER(TABLE_NAME)=UPPER(?) AND UPPER(COLUMN_NAME)=UPPER(?)", Integer.class, table, column);
        assertEquals(1, count, table + "." + column + " should exist");
    }
}
