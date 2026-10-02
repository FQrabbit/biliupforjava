package top.sshh.bililiverecoder.service;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationContext;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.bililiverecoder.config.db.DatabaseFileTransfer;
import top.sshh.bililiverecoder.config.db.MaintenanceDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DatabaseMaintenanceServiceTest {
    @TempDir Path directory;

    @Test
    void compactKeepsLocationAndDataWhileConcurrentReadsWaitAndWebhooksReplay() throws Exception {
        try (Fixture fixture = fixture()) {
            var executor = Executors.newFixedThreadPool(2);
            CountDownLatch saving = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            doAnswer(call -> {
                saving.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return call.callRealMethod();
            }).when(fixture.service).replaceDatabaseFile(any(), any());
            try {
                var maintenance = executor.submit(fixture.service::compactAsync);
                assertTrue(saving.await(10, TimeUnit.SECONDS));
                assertTrue(fixture.state.isDatabasePaused());
                assertFalse((Boolean) fixture.service.status().get("progressKnown"));
                var reader = executor.submit(() -> fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sample", Integer.class));
                assertThrows(TimeoutException.class, () -> reader.get(100, TimeUnit.MILLISECONDS));
                String payload = "{\"id\":\"event-1\",\"type\":\"FileClosed\",\"data\":{}}";
                assertTrue(fixture.service.spoolRecordWebhookIfMaintenance(payload, "brec:room:123", 0));
                release.countDown();
                maintenance.get(10, TimeUnit.SECONDS);
                assertEquals(10, reader.get(5, TimeUnit.SECONDS));
                assertEquals("DONE", fixture.service.status().get("phase"));
                assertFalse(fixture.state.isMaintenanceActive());
                assertFalse(fixture.state.isDatabasePaused());
                assertEquals("保留的数据", fixture.jdbc.queryForObject("SELECT name FROM sample WHERE id=1", String.class));
                assertEquals(8192, fixture.jdbc.queryForObject("SELECT OCTET_LENGTH(content) FROM sample WHERE id=1", Integer.class));
                assertTrue(Files.isRegularFile(fixture.work.resolve("db.mv.db")));
                assertTrue((Long) fixture.service.status().get("databaseBytesAfter") > 0);
                Path backup = Path.of((String) fixture.service.status().get("backupPath"));
                assertEquals(fixture.work.resolve("backup"), backup.getParent());
                verify(fixture.inbox).accept(payload, "brec:room:123", 0, "blrec", "FileClosed", "event-1");
                assertBackupData(backup);
                assertNull(fixture.service.status().get("recoveryPath"));
                try (var children = Files.list(fixture.work)) {
                    assertTrue(children.noneMatch(path -> path.getFileName().toString().contains(".pending-")));
                }
                // 再次压缩也要使用重建后的连接池
                doCallRealMethod().when(fixture.service).replaceDatabaseFile(any(), any());
                fixture.service.compactAsync();
                assertEquals("DONE", fixture.service.status().get("phase"));
                assertEquals(10, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sample", Integer.class));
            } finally {
                release.countDown();
                executor.shutdownNow();
            }
        }
    }

    @Test
    void encryptedDatabaseCanBeCompactedWithoutChangingItsPassword() throws Exception {
        try (Fixture fixture = fixture(true)) {
            fixture.service.compactAsync();
            assertEquals("DONE", fixture.service.status().get("phase"));
            assertEquals(10, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sample", Integer.class));
            assertEquals("保留的数据", fixture.jdbc.queryForObject("SELECT name FROM sample WHERE id=1", String.class));
        }
    }

    @Test
    void unfinishedWebhookWorkCancelsBeforeBackupOrShutdown() throws Exception {
        try (Fixture fixture = fixture()) {
            when(fixture.dispatcher.isIdle()).thenReturn(false);
            fixture.service.compactAsync();
            assertEquals("FAILED", fixture.service.status().get("phase"));
            assertFalse(fixture.state.isDatabasePaused());
            assertFalse(fixture.state.isMaintenanceActive());
            assertFalse(Files.exists(fixture.work.resolve("backup")));
            assertEquals(10, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sample", Integer.class));
        }
    }

    @Test
    void failedBackupSaveLeavesOriginalDatabaseOpen() throws Exception {
        try (Fixture fixture = fixture()) {
            Files.writeString(fixture.work.resolve("backup"), "占用备份目录");
            fixture.service.compactAsync();
            assertEquals("FAILED", fixture.service.status().get("phase"));
            assertTrue((Boolean) fixture.service.status().get("databaseAvailable"));
            assertFalse(fixture.state.isDatabasePaused());
            assertEquals(10, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sample", Integer.class));
        }
    }

    @Test
    void failedUploadBeforeReplacementReopensOriginalDatabase() throws Exception {
        try (Fixture fixture = fixture()) {
            doThrow(new IOException("传输失败")).when(fixture.service).replaceDatabaseFile(any(), any());
            fixture.service.compactAsync();
            assertEquals("FAILED", fixture.service.status().get("phase"));
            assertTrue((Boolean) fixture.service.status().get("databaseAvailable"));
            assertFalse(fixture.state.isDatabasePaused());
            assertEquals(10, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sample", Integer.class));
            assertTrue(Files.exists(Path.of((String) fixture.service.status().get("backupPath"))));
        }
    }

    @Test
    void failureAfterReplacementRestoresBackupBeforeReadersResume() throws Exception {
        try (Fixture fixture = fixture()) {
            AtomicInteger replacements = new AtomicInteger();
            doAnswer(call -> {
                Object result = call.callRealMethod();
                if (replacements.incrementAndGet() == 1) throw new IOException("替换后连接恢复失败");
                return result;
            }).when(fixture.service).replaceDatabaseFile(any(), any());
            fixture.service.compactAsync();
            assertEquals(2, replacements.get());
            assertEquals("FAILED", fixture.service.status().get("phase"));
            assertTrue((Boolean) fixture.service.status().get("databaseAvailable"));
            assertFalse(fixture.state.isDatabasePaused());
            assertEquals(10, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sample", Integer.class));
            assertEquals("保留的数据", fixture.jdbc.queryForObject("SELECT name FROM sample WHERE id=1", String.class));
        }
    }

    @Test
    void unrecoverableFailureKeepsPauseBackupAndScratchForRecovery() throws Exception {
        try (Fixture fixture = fixture()) {
            AtomicInteger replacements = new AtomicInteger();
            doAnswer(call -> {
                if (replacements.incrementAndGet() == 1) call.callRealMethod();
                throw new IOException("存储连接中断");
            }).when(fixture.service).replaceDatabaseFile(any(), any());
            fixture.service.compactAsync();
            assertEquals("FAILED", fixture.service.status().get("phase"));
            assertFalse((Boolean) fixture.service.status().get("databaseAvailable"));
            assertTrue(fixture.state.isDatabasePaused());
            assertTrue(fixture.state.isMaintenanceActive());
            Path recovery = Path.of((String) fixture.service.status().get("recoveryPath"));
            assertTrue(Files.exists(recovery.resolve("backup.zip")));
            assertTrue(Files.exists(Path.of((String) fixture.service.status().get("backupPath"))));
            assertThrows(org.springframework.web.server.ResponseStatusException.class, fixture.service::compactAsync);
        }
    }

    @Test
    void rejectedMaintenanceTaskDoesNotLeaveMaintenanceEnabled() throws Exception {
        try (Fixture fixture = fixture()) {
            TaskExecutor rejected = task -> { throw new java.util.concurrent.RejectedExecutionException(); };
            ReflectionTestUtils.setField(fixture.service, "taskExecutor", rejected);
            assertThrows(java.util.concurrent.RejectedExecutionException.class, fixture.service::compactAsync);
            assertFalse(fixture.state.isMaintenanceActive());
            assertFalse((Boolean) fixture.service.status().get("running"));
            assertEquals("FAILED", fixture.service.status().get("phase"));
        }
    }

    private Fixture fixture() throws Exception {
        return fixture(false);
    }

    private Fixture fixture(boolean encrypted) throws Exception {
        Path work = Files.createDirectories(directory.resolve("work'path"));
        DatabaseMaintenanceState state = new DatabaseMaintenanceState();
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl("jdbc:h2:retry:" + work.resolve("db").toString().replace('\\', '/') + ";DB_CLOSE_DELAY=-1"
                + (encrypted ? ";CIPHER=AES" : ""));
        pool.setUsername("sa");
        pool.setPassword(encrypted ? "file_password 123456" : "123456");
        pool.setMaximumPoolSize(3);
        MaintenanceDataSource source = new MaintenanceDataSource(pool, state);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE sample(id INT PRIMARY KEY, name VARCHAR, content BLOB)");
        for (int i = 0; i < 100; i++) jdbc.update("INSERT INTO sample VALUES(?,?,?)", i, "保留的数据", new byte[8192]);
        jdbc.update("DELETE FROM sample WHERE id >= 10");
        ApplicationContext context = mock(ApplicationContext.class);
        RecordWebhookInboxService inbox = mock(RecordWebhookInboxService.class);
        when(context.getBean(RecordWebhookInboxService.class)).thenReturn(inbox);
        WebhookEventDispatcher dispatcher = mock(WebhookEventDispatcher.class);
        when(dispatcher.isIdle()).thenReturn(true);
        DatabaseMaintenanceService service = spy(new DatabaseMaintenanceService(source, jdbc, dispatcher, context, state, Runnable::run));
        ReflectionTestUtils.setField(service, "workPath", work.toString());
        return new Fixture(source, jdbc, state, service, dispatcher, inbox, work);
    }

    private void assertBackupData(Path backup) throws Exception {
        Path restored = Files.createDirectory(directory.resolve("backup-check"));
        DatabaseFileTransfer.restoreSnapshot(backup, restored.resolve("db.mv.db"));
        try (var connection = DriverManager.getConnection("jdbc:h2:" + restored.resolve("db").toString().replace('\\', '/'), "sa", "123456");
             var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM sample")) {
            assertTrue(rows.next());
            assertEquals(10, rows.getInt(1));
        }
    }

    private record Fixture(MaintenanceDataSource source, JdbcTemplate jdbc, DatabaseMaintenanceState state,
                           DatabaseMaintenanceService service, WebhookEventDispatcher dispatcher,
                           RecordWebhookInboxService inbox, Path work) implements AutoCloseable {
        @Override public void close() throws Exception {
            source.close();
            try (var connection = source.openDirect(source.jdbcUrl()); var statement = connection.createStatement()) {
                statement.execute("SHUTDOWN");
            }
            String recoveryPath = (String) service.status().get("recoveryPath");
            if (recoveryPath != null) DatabaseFileTransfer.cleanWorkspace(Path.of(recoveryPath));
        }
    }
}
