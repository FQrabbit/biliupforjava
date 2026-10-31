package top.sshh.bililiverecoder.protection;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class DiskSpaceGuardTest {
    @TempDir Path directory;
    private DiskSpaceGuard guard;
    private final AtomicLong clock = new AtomicLong();
    private final AtomicReference<List<DiskSpaceProbe.Volume>> sample = new AtomicReference<>();
    private final AtomicBoolean unavailable = new AtomicBoolean();

    @AfterEach void cleanup() { if (guard != null) guard.close(); }

    static DiskSpaceSettings settings(Path directory) {
        return DiskSpaceSettings.from(new MockEnvironment().withProperty("record.work-path", directory.toString()));
    }

    private DiskSpaceGuard start() {
        if (sample.get() == null) volume(100, 20);
        guard = new DiskSpaceGuard(settings(directory), new DiskSpaceProbe() {
            @Override public List<Volume> collect(List<Path> paths) throws IOException {
                if (unavailable.get()) throw new IOException("挂载断开");
                return sample.get();
            }
        }, new DiskProtectionMarker(settings(directory).marker()), clock::get);
        guard.initialize(); DiskSpaceGuard.install(guard);
        return guard;
    }

    private void volume(long totalGiB, long freeGiB) {
        long total = totalGiB * DiskSpaceSettings.GIB, free = freeGiB * DiskSpaceSettings.GIB;
        sample.set(List.of(new DiskSpaceProbe.Volume(directory.toString(), total, free, (total - free) * 100d / total)));
    }

    @Test void defaultsMatchUserDecisions() {
        DiskSpaceSettings settings = settings(directory);
        assertEquals(98, settings.stopPercent()); assertEquals(2 * DiskSpaceSettings.GIB, settings.stopBytes());
        assertEquals(95, settings.resumePercent()); assertEquals(10 * DiskSpaceSettings.GIB, settings.resumeBytes());
        assertEquals(1000, settings.intervalMillis()); assertEquals(30000, settings.stableMillis());
    }

    @Test void percentageThresholdTriggersEvenWithMoreThanTwoGiBFree() {
        volume(1000, 20); start(); assertEquals(DiskSpaceGuard.Phase.QUIESCING, guard.phase());
    }

    @Test void byteThresholdIsInclusiveEvenBelowNinetyEightPercent() {
        volume(10, 2); start(); assertEquals(DiskSpaceGuard.Phase.QUIESCING, guard.phase());
    }

    @Test void noRoundingOfPercentageBeforeThreshold() {
        long total = 1000 * DiskSpaceSettings.GIB;
        long free = 20 * DiskSpaceSettings.GIB + 1;
        sample.set(List.of(new DiskSpaceProbe.Volume("x", total, free, (total - free) * 100d / total)));
        start(); assertEquals(DiskSpaceGuard.Phase.RUNNING, guard.phase());
    }

    @Test void anyVolumeCanTriggerAndAllMustRecover() {
        start();
        sample.set(List.of(new DiskSpaceProbe.Volume("work", 100 * DiskSpaceSettings.GIB, 20 * DiskSpaceSettings.GIB, 80),
                new DiskSpaceProbe.Volume("database", 100 * DiskSpaceSettings.GIB, 2 * DiskSpaceSettings.GIB, 98)));
        guard.poll(); assertTrue(DiskSpaceGuard.blocksConnections());
        clock.set(31000); guard.poll(); assertFalse(guard.canResume());
    }

    @Test void recoveryRequiresBothConditionsThirtySecondsAndManualAction() {
        start(); guard.protect("test"); guard.paused(true);
        AtomicInteger resumes = new AtomicInteger(); guard.attach(null, resumes::incrementAndGet, null);
        volume(100, 9); guard.poll(); clock.set(40000); guard.poll(); assertFalse(guard.canResume());
        volume(1000, 40); guard.poll(); clock.set(80000); guard.poll(); assertFalse(guard.canResume());
        volume(100, 10); guard.poll(); clock.set(109999); guard.poll(); assertFalse(guard.canResume());
        clock.set(110000); guard.poll(); assertTrue(guard.canResume()); assertEquals(0, resumes.get());
        assertEquals(DiskSpaceGuard.Phase.PAUSED, guard.phase());
        assertTrue(guard.requestResume()); assertEquals(1, resumes.get());
        assertFalse(guard.requestResume());
    }

    @Test void probingFailureResetsRecoveryAndDoesNotPretendSpaceIsHealthy() {
        start(); guard.protect("test"); guard.paused(true); guard.poll();
        clock.set(30000); guard.poll(); assertTrue(guard.canResume());
        unavailable.set(true); guard.poll(); assertFalse(guard.canResume());
        unavailable.set(false); clock.set(31000); guard.poll(); assertFalse(guard.canResume());
        clock.set(61000); guard.poll(); assertTrue(guard.canResume());
    }

    @Test void staleSamplesCannotEnableResume() {
        start(); guard.protect("test"); guard.paused(true);
        clock.set(30000); guard.poll(); assertTrue(guard.canResume());
        clock.set(32000); assertFalse(guard.canResume());
    }

    @Test void persistentProtectionSurvivesNewGuardAndNeedsManualRecovery() throws Exception {
        DiskProtectionMarker marker = new DiskProtectionMarker(settings(directory).marker()); marker.write();
        start(); assertEquals(DiskSpaceGuard.Phase.QUIESCING, guard.phase());
        assertTrue(marker.exists()); assertFalse(guard.canResume());
    }

    @Test void malformedMarkerBlocksStartupRatherThanBeingIgnored() throws Exception {
        Files.writeString(settings(directory).marker(), "bad marker");
        start(); assertEquals(DiskSpaceGuard.Phase.QUIESCING, guard.phase());
    }

    @Test void closingFailureNeverClaimsDatabaseClosedAndDoesNotUnblockConnections() {
        start(); guard.protect("test"); guard.failed(new IOException("关闭失败"));
        assertEquals(DiskSpaceGuard.Phase.FAILED, guard.phase());
        assertEquals(false, guard.status().get("databaseClosed")); assertTrue(DiskSpaceGuard.blocksConnections());
    }

    @Test void closedCoreCallbackIsNotUsedWhenLowSpaceAppearsBetweenRestarts() {
        start(); AtomicInteger stops = new AtomicInteger();
        guard.attach(stops::incrementAndGet, () -> {}, null); guard.detach();
        volume(100, 1); guard.poll(); assertEquals(0, stops.get());
        assertTrue(DiskSpaceGuard.blocksConnections());
        guard.attach(stops::incrementAndGet, () -> {}, null); assertEquals(1, stops.get());
    }

    @Test void markerRemainsUntilBusinessApplicationIsReady() throws Exception {
        start(); guard.protect("test"); assertTrue(guard.saveProtection()); guard.paused(true);
        clock.set(30000); guard.poll(); guard.beginRecovery();
        assertTrue(Files.exists(settings(directory).marker()));
        assertTrue(DiskSpaceGuard.blocksBusiness()); assertFalse(DiskSpaceGuard.blocksConnections());
        guard.applicationReady(); assertEquals(DiskSpaceGuard.Phase.RUNNING, guard.phase());
        assertFalse(Files.exists(settings(directory).marker()));
    }

    @Test void lowSpaceDuringRecoveryClosesAdmissionAgain() throws Exception {
        start(); guard.protect("test"); guard.paused(true); clock.set(30000); guard.poll(); guard.beginRecovery();
        volume(100, 1); guard.poll(); assertTrue(DiskSpaceGuard.blocksConnections());
        assertThrows(IllegalStateException.class, guard::applicationReady);
    }

    @Test void failedMarkerWriteIsReportedWithoutClaimingFullSuccess() throws Exception {
        start(); Files.createDirectory(settings(directory).marker());
        guard.protect("test"); assertFalse(guard.saveProtection()); guard.paused(false);
        assertEquals(DiskSpaceGuard.Phase.FAILED, guard.phase());
        assertEquals(true, guard.status().get("databaseClosed"));
        assertFalse(guard.status().get("error").toString().isBlank());
    }

    @Test void settingsResolveLocalH2AndKeepRemoteDatabasesRemote() {
        assertNull(DiskSpaceSettings.localDatabasePath("jdbc:mysql://host/db"));
        assertNull(DiskSpaceSettings.localDatabasePath("jdbc:h2:tcp://host/db"));
        assertNull(DiskSpaceSettings.localDatabasePath("jdbc:h2:mem:db"));
        assertEquals(directory.resolve("db"), DiskSpaceSettings.localDatabasePath("jdbc:h2:retry:" + directory.resolve("db") + ";DB_CLOSE_DELAY=0"));
    }

    @Test void invalidAndNonFiniteThresholdsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> DiskSpaceSettings.from(new MockEnvironment().withProperty("record.disk-protection.stop-percent", "NaN")));
        assertThrows(IllegalArgumentException.class, () -> DiskSpaceSettings.from(new MockEnvironment().withProperty("record.disk-protection.resume-percent", "99")));
        assertThrows(IllegalArgumentException.class, () -> DiskSpaceSettings.from(new MockEnvironment().withProperty("record.disk-protection.resume-bytes", "1")));
    }
}
