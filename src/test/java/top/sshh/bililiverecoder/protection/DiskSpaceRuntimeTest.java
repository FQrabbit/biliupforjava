package top.sshh.bililiverecoder.protection;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericApplicationContext;
import top.sshh.bililiverecoder.config.db.MaintenanceDataSource;
import top.sshh.bililiverecoder.lifecycle.ShutdownState;
import top.sshh.bililiverecoder.service.*;
import top.sshh.bililiverecoder.service.backup.BackupService;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiskSpaceRuntimeTest {
    @TempDir Path directory;
    DiskSpaceGuard guard;
    @AfterEach void cleanup() { if (guard != null) guard.close(); }

    @Test void closesRealH2AndEntireCoreBeforeClaimingPaused() throws Exception {
        var settings = DiskSpaceGuardTest.settings(directory);
        guard = new DiskSpaceGuard(settings, new DiskSpaceProbe(), new DiskProtectionMarker(settings.marker()), System::currentTimeMillis);
        DiskSpaceGuard.install(guard);
        var state = new DatabaseMaintenanceState(); var pool = new HikariDataSource();
        pool.setJdbcUrl("jdbc:h2:" + directory.resolve("db").toString().replace('\\','/')); pool.setUsername("sa");
        try (var source = new MaintenanceDataSource(pool, state); var context = new GenericApplicationContext()) {
            try (var connection = source.getConnection()) { connection.createStatement().execute("CREATE TABLE recorded(id INT)"); }
            var preview = mock(PartPreviewService.class); when(preview.isStopped()).thenReturn(true);
            var backup = mock(BackupService.class); when(backup.isStopped()).thenReturn(true);
            context.registerBean(PartPreviewService.class, () -> preview); context.registerBean(BackupService.class, () -> backup);
            context.registerBean(PublishAccountScheduler.class, () -> mock(PublishAccountScheduler.class));
            context.registerBean(UploadPauseService.class, () -> mock(UploadPauseService.class));
            context.registerBean(UploadUserSerialScheduler.class, () -> mock(UploadUserSerialScheduler.class));
            context.refresh();
            guard.protect("test");
            new DiskSpaceRuntime(context, state, source, new ShutdownState(), mock(DiskProtectionNotifications.class)).stopCore(guard);
            assertTrue(source.isProtectionComplete()); assertFalse(context.isActive());
            assertEquals(DiskSpaceGuard.Phase.PAUSED, guard.phase());
            assertEquals(true, guard.status().get("databaseClosed")); assertTrue(java.nio.file.Files.exists(settings.marker()));
            verify(preview).shutdown(); verify(backup).stopForDiskProtection();
        }
    }
}
