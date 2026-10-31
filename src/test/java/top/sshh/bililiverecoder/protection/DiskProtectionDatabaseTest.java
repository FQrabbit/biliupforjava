package top.sshh.bililiverecoder.protection;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.sshh.bililiverecoder.config.db.MaintenanceDataSource;
import top.sshh.bililiverecoder.service.DatabaseMaintenanceState;
import java.nio.file.Path;
import java.sql.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DiskProtectionDatabaseTest {
    @TempDir Path directory;
    DiskSpaceGuard guard;
    DatabaseMaintenanceState state = new DatabaseMaintenanceState();
    @BeforeEach void start() {
        var settings = DiskSpaceGuardTest.settings(directory);
        guard = new DiskSpaceGuard(settings, new DiskSpaceProbe(), new DiskProtectionMarker(settings.marker()), System::currentTimeMillis);
        DiskSpaceGuard.install(guard);
    }
    @AfterEach void cleanup() { guard.close(); }

    private MaintenanceDataSource source() {
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl("jdbc:h2:" + directory.resolve("db").toString().replace('\\','/') + ";DB_CLOSE_DELAY=-1");
        pool.setUsername("sa"); pool.setMaximumPoolSize(4);
        return new MaintenanceDataSource(pool, state);
    }

    @Test void existingTransactionCanCommitWhileNewBorrowersFailImmediately() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        try (MaintenanceDataSource source = source()) {
            Connection existing = source.getConnection();
            existing.createStatement().execute("CREATE TABLE data(id INT)"); existing.setAutoCommit(false);
            existing.createStatement().execute("INSERT INTO data VALUES(1)");
            guard.protect("low");
            var rejected = worker.submit(() -> assertThrows(SQLNonTransientConnectionException.class, source::getConnection));
            assertNotNull(rejected.get(2, TimeUnit.SECONDS));
            try (Connection nested = source.getConnection()) { assertTrue(nested.isValid(1)); }
            existing.commit(); existing.close();
            worker.submit(() -> { state.pauseForDiskProtection(1000); source.shutdownForDiskProtection(); return true; }).get(3, TimeUnit.SECONDS);
            assertTrue(source.isProtectionComplete());
            try (Connection reopened = DriverManager.getConnection(source.jdbcUrl(), "sa", "");
                 var result = reopened.createStatement().executeQuery("SELECT COUNT(*) FROM data")) {
                assertTrue(result.next()); assertEquals(1, result.getInt(1));
                reopened.createStatement().execute("SHUTDOWN");
            }
        } finally { worker.shutdownNow(); }
    }

    @Test void rollbackIsPreservedAndProtectionTimeoutDoesNotResumeBusiness() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        try (MaintenanceDataSource source = source()) {
            Connection existing = source.getConnection(); existing.createStatement().execute("CREATE TABLE data(id INT)");
            existing.setAutoCommit(false); existing.createStatement().execute("INSERT INTO data VALUES(1)"); guard.protect("low");
            worker.submit(() -> assertThrows(IllegalStateException.class, () -> state.pauseForDiskProtection(20))).get(2, TimeUnit.SECONDS);
            assertTrue(state.isDatabasePaused()); assertTrue(existing.isValid(1));
            existing.rollback(); existing.close();
            worker.submit(() -> { state.pauseForDiskProtection(1000); source.shutdownForDiskProtection(); return true; }).get(3, TimeUnit.SECONDS);
            assertThrows(SQLException.class, source::getConnection);
            try (Connection reopened = DriverManager.getConnection(source.jdbcUrl(), "sa", "");
                 var result = reopened.createStatement().executeQuery("SELECT COUNT(*) FROM data")) {
                assertTrue(result.next()); assertEquals(0, result.getInt(1)); reopened.createStatement().execute("SHUTDOWN");
            }
        } finally { worker.shutdownNow(); }
    }

    @Test void newMaintenanceIsRefusedDuringProtection() {
        guard.protect("low"); assertFalse(state.tryBeginMaintenance());
    }
}
