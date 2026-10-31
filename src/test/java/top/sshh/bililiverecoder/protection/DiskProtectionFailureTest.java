package top.sshh.bililiverecoder.protection;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import top.sshh.bililiverecoder.config.db.MaintenanceDataSource;
import top.sshh.bililiverecoder.service.DatabaseMaintenanceState;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DiskProtectionFailureTest {
    @TempDir Path directory;
    DiskSpaceGuard guard;
    @AfterEach void cleanup() { if (guard != null) guard.close(); }

    @Test void aStuckFilesystemProbeCannotQueueUnlimitedWorkersOrEnableRecovery() throws Exception {
        var settings = DiskSpaceSettings.from(new MockEnvironment().withProperty("record.work-path", directory.toString())
                .withProperty("record.disk-protection.probe-timeout-ms", "20"));
        CountDownLatch release = new CountDownLatch(1); AtomicInteger attempts = new AtomicInteger();
        guard = new DiskSpaceGuard(settings, new DiskSpaceProbe() {
            @Override public List<Volume> collect(List<Path> paths) throws IOException {
                attempts.incrementAndGet();
                try { release.await(); } catch (InterruptedException interrupted) { throw new IOException(interrupted); }
                return List.of(new Volume("work", 100, 20, 80));
            }
        }, new DiskProtectionMarker(settings.marker()), System::currentTimeMillis);
        try {
            DiskSpaceGuard.install(guard); guard.initialize();
            for (int i = 0; i < 4; i++) guard.poll();
            assertEquals(1, attempts.get()); assertTrue(DiskSpaceGuard.blocksConnections()); assertFalse(guard.canResume());
        } finally { release.countDown(); }
    }

    @Test void closedPoolIsNotMistakenForConfirmedDatabaseShutdown() throws Exception {
        var settings = DiskSpaceGuardTest.settings(directory);
        guard = new DiskSpaceGuard(settings, new DiskSpaceProbe(), new DiskProtectionMarker(settings.marker()), System::currentTimeMillis);
        DiskSpaceGuard.install(guard);
        var state = new DatabaseMaintenanceState(); var pool = new HikariDataSource();
        pool.setJdbcUrl("jdbc:h2:" + directory.resolve("db").toString().replace('\\','/')); pool.setUsername("sa");
        try (var source = new MaintenanceDataSource(pool, state) {
            @Override public Connection openDirect(String url) throws SQLException { throw new SQLException("模拟最终关闭写入失败"); }
        }) {
            try (var connection = source.getConnection()) { connection.createStatement().execute("SELECT 1"); }
            guard.protect("low"); state.pauseForDiskProtection(1000);
            assertThrows(SQLException.class, source::shutdownForDiskProtection);
            assertTrue(source.isPoolClosed()); assertFalse(source.isProtectionComplete());
            assertFalse(DiskSpaceBootstrap.poolsClosed());
        }
    }
}
