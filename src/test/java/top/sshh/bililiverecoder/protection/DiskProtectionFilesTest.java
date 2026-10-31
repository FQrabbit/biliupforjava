package top.sshh.bililiverecoder.protection;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.sshh.bililiverecoder.service.PartFileStorageAdapter;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DiskProtectionFilesTest {
    @TempDir Path directory;
    DiskSpaceGuard guard;
    @BeforeEach void setup() {
        var settings = DiskSpaceGuardTest.settings(directory);
        guard = new DiskSpaceGuard(settings, new DiskSpaceProbe(), new DiskProtectionMarker(settings.marker()), System::currentTimeMillis);
        DiskSpaceGuard.install(guard);
    }
    @AfterEach void cleanup() { guard.close(); }

    @Test void copyInterruptedByProtectionPreservesOriginalAndLeavesPartialTargetUnconfirmed() throws Exception {
        Path source = directory.resolve("source"), target = directory.resolve("target");
        byte[] content = new byte[256 * 1024]; new java.util.Random(1).nextBytes(content); Files.write(source, content);
        AtomicInteger checks = new AtomicInteger();
        var adapter = new PartFileStorageAdapter() {
            @Override protected void requireRunning() throws IOException {
                if (checks.incrementAndGet() == 4) guard.protect("low");
                super.requireRunning();
            }
        };
        assertThrows(InterruptedIOException.class, () -> adapter.copy(source, target));
        assertArrayEquals(content, Files.readAllBytes(source));
        assertTrue(Files.size(target) < Files.size(source));
    }

    @Test void protectionBlocksDeletesAndMovesWithoutTouchingOriginal() throws Exception {
        Path source = Files.writeString(directory.resolve("source"), "original");
        var adapter = new PartFileStorageAdapter(); guard.protect("low");
        assertThrows(InterruptedIOException.class, () -> adapter.delete(source));
        assertThrows(InterruptedIOException.class, () -> adapter.move(source, directory.resolve("target")));
        assertEquals("original", Files.readString(source));
    }

    @Test void normalCopyRetainsContentsAndModificationTime() throws Exception {
        Path source = Files.writeString(directory.resolve("source"), "original"), target = directory.resolve("target");
        Files.setLastModifiedTime(source, java.nio.file.attribute.FileTime.fromMillis(1700000000000L));
        new PartFileStorageAdapter().copy(source, target);
        assertEquals("original", Files.readString(target)); assertEquals(Files.getLastModifiedTime(source), Files.getLastModifiedTime(target));
        assertThrows(FileAlreadyExistsException.class, () -> new PartFileStorageAdapter().copy(source, target));
    }
}
