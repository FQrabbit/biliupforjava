package top.sshh.bililiverecoder.config.db;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sshh.bililiverecoder.service.DatabaseMaintenanceState;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class MaintenanceDataSourceTest {
    @TempDir Path directory;

    @Test
    void drainsExistingConnectionsAndBlocksNewReadersUntilResume() throws Exception {
        DatabaseMaintenanceState state = new DatabaseMaintenanceState();
        try (MaintenanceDataSource source = source(state)) {
            var executor = Executors.newFixedThreadPool(2);
            try {
                Connection existing = source.getConnection();
                var paused = executor.submit(() -> { state.pauseAndDrain(5000); return true; });
                awaitPaused(state);
                CountDownLatch attempting = new CountDownLatch(1);
                var reader = executor.submit(() -> {
                    attempting.countDown();
                    try (Connection connection = source.getConnection(); var query = connection.createStatement();
                         var result = query.executeQuery("SELECT 1")) {
                        return result.next() && result.getInt(1) == 1;
                    }
                });
                assertTrue(attempting.await(2, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> reader.get(100, TimeUnit.MILLISECONDS));
                // 持有事务连接的线程仍能借第二个连接，不会互相等到超时
                try (Connection nested = source.getConnection()) {
                    assertTrue(nested.isValid(1));
                    assertSame(nested, nested.unwrap(Connection.class));
                }
                existing.close();
                existing.close();
                assertTrue(paused.get(2, TimeUnit.SECONDS));
                assertEquals(0, state.activeConnectionCount());
                state.resumeDatabase();
                assertTrue(reader.get(2, TimeUnit.SECONDS));
            } finally {
                state.resumeDatabase();
                executor.shutdownNow();
            }
        }
    }

    @Test
    void timeoutCancelsPauseWithoutClosingAnActiveTransaction() throws Exception {
        DatabaseMaintenanceState state = new DatabaseMaintenanceState();
        try (MaintenanceDataSource source = source(state); Connection existing = source.getConnection()) {
            var executor = Executors.newSingleThreadExecutor();
            try {
                var pause = executor.submit(() -> assertThrows(IllegalStateException.class, () -> state.pauseAndDrain(50)));
                assertNotNull(pause.get(2, TimeUnit.SECONDS));
                assertFalse(state.isDatabasePaused());
                assertTrue(existing.isValid(1));
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void failedBorrowDoesNotLeaveAPhantomConnection() throws Exception {
        DatabaseMaintenanceState state = new DatabaseMaintenanceState();
        HikariDataSource pool = new HikariDataSource();
        pool.close();
        try (MaintenanceDataSource source = new MaintenanceDataSource(pool, state)) {
            assertThrows(java.sql.SQLException.class, source::getConnection);
            assertEquals(0, state.activeConnectionCount());
            state.pauseAndDrain(0);
            state.resumeDatabase();
        }
    }

    private MaintenanceDataSource source(DatabaseMaintenanceState state) {
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl("jdbc:h2:" + directory.resolve("db").toString().replace('\\', '/'));
        pool.setUsername("sa");
        pool.setMaximumPoolSize(4);
        return new MaintenanceDataSource(pool, state);
    }

    private static void awaitPaused(DatabaseMaintenanceState state) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!state.isDatabasePaused() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(state.isDatabasePaused());
    }
}
