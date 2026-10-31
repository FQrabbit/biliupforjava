package top.sshh.bililiverecoder.protection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DiskSpaceProbeTest {
    @TempDir Path directory;
    @Test void deduplicatesWorkAndDatabaseOnSameFileSystem() throws Exception {
        Path a = Files.createDirectory(directory.resolve("a")), b = Files.createDirectory(directory.resolve("b"));
        assertEquals(1, new DiskSpaceProbe().collect(List.of(a, b)).size());
    }
    @Test void disappearedDirectoryIsNotReplacedWithHealthyParent() throws Exception {
        Path a = Files.createDirectory(directory.resolve("a")); DiskSpaceProbe probe = new DiskSpaceProbe();
        probe.collect(List.of(a)); Files.delete(a);
        assertThrows(IOException.class, () -> probe.collect(List.of(a)));
    }
    @Test void newInstallationCanCreateDirectoryButLaterRemovalIsDetected() throws Exception {
        Path a = directory.resolve("new"); DiskSpaceProbe probe = new DiskSpaceProbe();
        assertEquals(1, probe.collect(List.of(a)).size()); Files.createDirectory(a);
        probe.collect(List.of(a)); Files.delete(a);
        assertThrows(IOException.class, () -> probe.collect(List.of(a)));
    }

    @Test void persistedDirectoryIdentityDetectsMissingMountAfterRestart() throws Exception {
        Path history = directory.resolve("history.properties"), a = Files.createDirectory(directory.resolve("a"));
        new DiskSpaceProbe(history).collect(List.of(a)); Files.delete(a);
        assertThrows(IOException.class, () -> new DiskSpaceProbe(history).collect(List.of(a)));
    }

    @Test void invalidPersistedIdentityCannotBeIgnoredAsFirstInstallation() throws Exception {
        Path history = directory.resolve("history.properties"); Files.writeString(history, "invalid=true");
        assertThrows(IOException.class, () -> new DiskSpaceProbe(history).collect(List.of(directory)));
    }
}
