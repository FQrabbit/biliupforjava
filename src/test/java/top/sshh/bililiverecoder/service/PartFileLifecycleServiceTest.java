package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import top.sshh.bililiverecoder.entity.PartFileLocation;
import top.sshh.bililiverecoder.entity.PartFileOperation;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.entity.StorageRoot;
import top.sshh.bililiverecoder.repo.PartFileLocationRepository;
import top.sshh.bililiverecoder.repo.PartFileOperationRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.repo.StorageRootRepository;
import top.sshh.bililiverecoder.repo.SystemConfigRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

@DataJpaTest(properties = {
        "spring.datasource.hikari.jdbc-url=jdbc:h2:mem:file-lifecycle;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;DATABASE_TO_UPPER=false",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({StorageRootService.class, PartFileLocationService.class,
        PartFileOperationService.class, PartFileStorageAdapter.class,
        StorageLifecycleMigrationService.class, RecordPartStaleReconciler.class})
class PartFileLifecycleServiceTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("record.work-path", () -> tempDir.resolve("work").toString());
    }

    @Autowired
    private StorageRootService rootService;
    @SpyBean
    private PartFileLocationService locationService;
    @SpyBean
    private PartFileStorageAdapter storageAdapter;
    @Autowired
    private PartFileOperationService operationService;
    @Autowired
    private RecordHistoryPartRepository partRepository;
    @Autowired
    private top.sshh.bililiverecoder.repo.RecordHistoryRepository historyRepository;
    @Autowired
    private PartFileLocationRepository locationRepository;
    @Autowired
    private PartFileOperationRepository operationRepository;
    @Autowired
    private StorageRootRepository rootRepository;
    @Autowired
    private RecordRoomRepository roomRepository;
    @Autowired
    private SystemConfigRepository configRepository;
    @Autowired
    private StorageLifecycleMigrationService migrationService;
    @Autowired
    private RecordPartStaleReconciler staleReconciler;

    @Test
    void moveVerifiesTargetSwitchesPrimaryAndIsIdempotent() throws Exception {
        Path source = createVideo("move/session/video.flv", "video-data");
        Files.writeString(source.resolveSibling("video.xml"), "xml");
        RecordHistoryPart part = createPart(source, false);
        Path archive = Files.createDirectories(tempDir.resolve("archive-move"));

        PartFileOperation first = operationService.move(part.getId(), archive.toString());
        PartFileOperation repeated = operationService.move(part.getId(), archive.toString());

        assertEquals(PartFileOperation.OperationStatus.SUCCEEDED, first.getStatus());
        assertEquals(first.getOperationKey(), repeated.getOperationKey());
        Path target = archive.resolve("move/session/video.flv");
        assertTrue(Files.isRegularFile(target));
        assertTrue(Files.isRegularFile(target.resolveSibling("video.xml")));
        assertFalse(Files.exists(source));
        RecordHistoryPart saved = partRepository.findById(part.getId()).orElseThrow();
        assertEquals(target.toString().replace('\\', '/'), saved.getFilePath());
        assertTrue(saved.isFileDelete());
        assertEquals(PartFileLocationService.LocalFileState.AVAILABLE_ARCHIVE,
                locationService.resolveReadable(part.getId()).state());
    }

    @Test
    void copyKeepsPrimaryAndCreatesReadableReplica() throws Exception {
        Path source = createVideo("copy/video.mp4", "copy-data");
        RecordHistoryPart part = createPart(source, false);
        Path archive = Files.createDirectories(tempDir.resolve("archive-copy"));

        PartFileOperation operation = operationService.copy(part.getId(), archive.toString());

        assertEquals(PartFileOperation.OperationStatus.SUCCEEDED, operation.getStatus());
        assertTrue(Files.isRegularFile(source));
        assertTrue(Files.isRegularFile(archive.resolve("copy/video.mp4")));
        assertEquals(1, locationRepository.countByPartIdAndRoleAndState(part.getId(),
                PartFileLocation.LocationRole.REPLICA, PartFileLocation.LocationState.AVAILABLE));
        assertEquals(source.toString().replace('\\', '/'),
                partRepository.findById(part.getId()).orElseThrow().getFilePath());

        PartFileOperation deletePrimary = operationService.delete(part.getId());
        PartFileOperation deleteReplica = operationService.delete(part.getId());
        assertNotEquals(deletePrimary.getOperationKey(), deleteReplica.getOperationKey());
        assertFalse(Files.exists(source));
        assertFalse(Files.exists(archive.resolve("copy/video.mp4")));
    }

    @Test
    void deleteRemovesOnlyVideoAndMarksIntentionalCleanup() throws Exception {
        Path source = createVideo("delete/video.flv", "delete-data");
        Path xml = source.resolveSibling("video.xml");
        Files.writeString(xml, "xml");
        RecordHistoryPart part = createPart(source, false);

        PartFileOperation operation = operationService.delete(part.getId());

        assertEquals(PartFileOperation.OperationStatus.SUCCEEDED, operation.getStatus());
        assertFalse(Files.exists(source));
        assertTrue(Files.isRegularFile(xml));
        assertEquals(PartFileLocationService.LocalFileState.DELETED_BY_POLICY,
                locationService.resolveReadable(part.getId()).state());
        assertTrue(partRepository.findById(part.getId()).orElseThrow().isFileDelete());
    }

    @Test
    void offlineRootLeavesLocationAvailableAndOperationPending() throws Exception {
        Path source = createVideo("offline/video.flv", "offline-data");
        RecordHistoryPart part = createPart(source, false);
        PartFileLocation location = locationService.findLocations(part.getId()).get(0);
        StorageRoot root = rootRepository.findById(location.getStorageRootId()).orElseThrow();
        Path workRoot = Path.of(root.getPath());
        Path relative = workRoot.relativize(source);
        Path detachedRoot = tempDir.resolve("detached-offline-root");
        Files.move(workRoot, detachedRoot);
        try {
            PartFileOperation operation = operationService.delete(part.getId());
            PartFileOperation repeated = operationService.delete(part.getId());

            assertEquals(PartFileOperation.OperationStatus.PENDING, operation.getStatus());
            assertEquals(operation.getOperationKey(), repeated.getOperationKey());
            assertEquals(PartFileLocation.LocationState.AVAILABLE,
                    locationRepository.findById(location.getId()).orElseThrow().getState());
            assertTrue(Files.isRegularFile(detachedRoot.resolve(relative)));
        } finally {
            Files.move(detachedRoot, workRoot);
        }
    }

    @Test
    void recoveryCompletesMoveWhenTargetExistsAndSourceAlreadyMoved() throws Exception {
        Path source = createVideo("recover/video.flv", "recover-data");
        RecordHistoryPart part = createPart(source, false);
        PartFileLocation sourceLocation = locationService.findLocations(part.getId()).get(0);
        Path archive = Files.createDirectories(tempDir.resolve("archive-recover"));
        StorageRoot targetRoot = rootService.getOrCreateArchiveRoot(archive.toString());
        Path target = archive.resolve(sourceLocation.getRelativePath());
        Files.createDirectories(target.getParent());
        long size = Files.size(source);
        PartFileLocation targetLocation = locationService.createProcessingTarget(
                part.getId(), targetRoot, sourceLocation.getRelativePath(), size);
        Files.move(source, target);

        PartFileOperation interrupted = new PartFileOperation();
        interrupted.setPartId(part.getId());
        interrupted.setOperationType(PartFileOperation.OperationType.MOVE);
        interrupted.setSourceLocationId(sourceLocation.getId());
        interrupted.setTargetRootId(targetRoot.getId());
        interrupted.setTargetRelativePath(sourceLocation.getRelativePath());
        interrupted.setStatus(PartFileOperation.OperationStatus.RUNNING);
        interrupted.setAttemptCount(1);
        interrupted = operationRepository.save(interrupted);

        operationService.recoverPendingOperations();

        assertEquals(PartFileOperation.OperationStatus.SUCCEEDED,
                operationRepository.findById(interrupted.getId()).orElseThrow().getStatus());
        PartFileLocation recoveredTarget = locationRepository.findById(targetLocation.getId()).orElseThrow();
        assertEquals(PartFileLocation.LocationRole.PRIMARY, recoveredTarget.getRole());
        assertEquals(PartFileLocation.LocationState.AVAILABLE, recoveredTarget.getState());
        assertEquals(target.toString().replace('\\', '/'),
                partRepository.findById(part.getId()).orElseThrow().getFilePath());
    }

    @Test
    void targetConflictFailsWithoutOverwritingSourceOrTarget() throws Exception {
        Path source = createVideo("conflict/video.flv", "source-content");
        RecordHistoryPart part = createPart(source, false);
        Path archive = Files.createDirectories(tempDir.resolve("archive-conflict"));
        Path target = archive.resolve("conflict/video.flv");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "different");

        PartFileOperation operation = operationService.move(part.getId(), archive.toString());

        assertEquals(PartFileOperation.OperationStatus.FAILED, operation.getStatus());
        assertEquals("source-content", Files.readString(source));
        assertEquals("different", Files.readString(target));
        assertEquals(PartFileLocation.LocationState.PROCESS_FAILED,
                locationRepository.findByPartIdAndStorageRootIdAndRelativePath(
                        part.getId(), operation.getTargetRootId(), operation.getTargetRelativePath())
                        .orElseThrow().getState());
    }

    @Test
    void validatesTraversalAndConservativelyClassifiesLegacyFiles() throws Exception {
        StorageRoot workRoot = rootService.activeWorkRoot().orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> rootService.resolve(workRoot, "../escape.flv"));
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            assertTrue(StorageRootService.samePath(workRoot.getPath(), workRoot.getPath().toUpperCase()));
        }
        Path outsideDir = Files.createDirectories(tempDir.resolve("outside-link-target"));
        Path link = Path.of(workRoot.getPath()).resolve("outside-link");
        try {
            Files.createSymbolicLink(link, outsideDir);
            Files.writeString(outsideDir.resolve("escaped.flv"), "escaped");
            assertThrows(IllegalArgumentException.class,
                    () -> rootService.resolve(workRoot, "outside-link/escaped.flv"));
        } catch (java.io.IOException | UnsupportedOperationException ignored) {
            // Symbolic-link creation may require an elevated Windows developer setting.
        }

        RecordHistoryPart deleted = createPartRecord(Path.of(workRoot.getPath()).resolve("legacy-deleted.flv"), true, 0);
        locationService.registerPrimary(deleted);
        assertEquals(PartFileLocation.LocationState.DELETED_BY_POLICY,
                locationService.findLocations(deleted.getId()).get(0).getState());

        RecordHistoryPart missing = createPartRecord(Path.of(workRoot.getPath()).resolve("legacy-missing.flv"), false, 0);
        locationService.registerPrimary(missing);
        assertEquals(PartFileLocation.LocationState.MISSING_UNEXPECTED,
                locationService.findLocations(missing.getId()).get(0).getState());

        Path external = tempDir.resolve("external-" + UUID.randomUUID() + ".flv");
        Files.writeString(external, "external");
        RecordHistoryPart untrusted = createPartRecord(external, false, Files.size(external));
        locationService.registerPrimary(untrusted);
        PartFileLocation untrustedLocation = locationService.findLocations(untrusted.getId()).get(0);
        assertNull(untrustedLocation.getStorageRootId());
        assertEquals(PartFileLocation.LocationState.PROCESS_FAILED, untrustedLocation.getState());
    }

    @Test
    void driveRootContainmentIsSegmentAwareOnWindows() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return;
        Path driveRoot = Path.of("D:\\");
        assertTrue(StorageRootService.isUnder(driveRoot, Path.of("D:\\recordings\\clip.flv")));
        assertTrue(StorageRootService.isUnder(driveRoot, Path.of("d:\\recordings\\clip.flv")));
        Path recordingsRoot = Path.of("D:\\recordings");
        assertFalse(StorageRootService.isUnder(recordingsRoot, Path.of("D:\\recordings2\\clip.flv")));
    }

    @Test
    void resolveReadableRepairsExistingLegacyProcessFailureUnderTrustedRoot() throws Exception {
        Path source = createVideo("legacy-repair/video.flv", "legacy-repair-data");
        RecordHistoryPart part = createPartRecord(source, false, Files.size(source));
        PartFileLocation broken = new PartFileLocation();
        broken.setPartId(part.getId());
        broken.setStorageRootId(null);
        broken.setRelativePath(source.getFileName().toString());
        broken.setAbsolutePathSnapshot(source.toString());
        broken.setRole(PartFileLocation.LocationRole.PRIMARY);
        broken.setState(PartFileLocation.LocationState.PROCESS_FAILED);
        broken.setExpectedSize(Files.size(source));
        locationRepository.save(broken);

        PartFileLocationService.FileResolution resolution = locationService.resolveReadable(part.getId());

        assertTrue(resolution.available());
        assertEquals(PartFileLocationService.LocalFileState.AVAILABLE_WORK, resolution.state());
        PartFileLocation repaired = locationRepository.findById(broken.getId()).orElseThrow();
        assertNotNull(repaired.getStorageRootId());
        assertEquals(PartFileLocation.LocationState.AVAILABLE, repaired.getState());
    }

    @Test
    void staleStablePartIsClosedAndHistoryIsClosed() throws Exception {
        Path source = createVideo("stale-repair/video.flv", "stale-repair-data");
        Files.setLastModifiedTime(source, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 11 * 60 * 1000L));
        String roomId = "stale-room-" + UUID.randomUUID();
        RecordHistory history = new RecordHistory();
        history.setRoomId(roomId);
        history.setEventId("history-" + UUID.randomUUID());
        history.setRecording(true);
        history.setUpload(true);
        history = historyRepository.save(history);
        RecordRoom room = new RecordRoom();
        room.setRoomId(roomId);
        room.setHistoryId(history.getId());
        room.setRecording(false);
        roomRepository.save(room);
        RecordHistoryPart part = createPartRecord(source, false, Files.size(source));
        part.setRoomId(roomId);
        part.setHistoryId(history.getId());
        part.setRecording(true);
        part.setStartTime(java.time.LocalDateTime.now().minusMinutes(20));
        part.setEndTime(null);
        part = partRepository.save(part);
        locationService.registerPrimary(part);

        assertEquals(1, staleReconciler.reconcileBatch());
        RecordHistoryPart repaired = partRepository.findById(part.getId()).orElseThrow();
        assertFalse(repaired.isRecording());
        assertNotNull(repaired.getEndTime());
        assertTrue(repaired.getDuration() > 0);
        assertFalse(historyRepository.findById(history.getId()).orElseThrow().isRecording());
    }

    @Test
    void futureOnlyKeepsOldRootAndActivatesConfiguredWorkRoot() throws Exception {
        Path oldRoot = Files.createDirectories(tempDir.resolve("work-future-old"));
        StorageRoot previous = repointActiveWorkRoot(oldRoot);
        Files.createDirectories(Path.of(rootService.configuredWorkPath()));

        assertTrue(rootService.hasPendingWorkPathChange());
        StorageRoot active = rootService.resolveWorkPathChange(StorageRootService.WorkPathChangeMode.FUTURE_ONLY);

        assertNotEquals(previous.getId(), active.getId());
        assertTrue(active.isActiveForNewFiles());
        assertTrue(StorageRootService.samePath(rootService.configuredWorkPath(), active.getPath()));
        StorageRoot savedPrevious = rootRepository.findById(previous.getId()).orElseThrow();
        assertFalse(savedPrevious.isActiveForNewFiles());
        assertTrue(StorageRootService.samePath(oldRoot.toString(), savedPrevious.getPath()));
        assertFalse(rootService.hasPendingWorkPathChange());
    }

    @Test
    void relocateExistingValidatesFilesAndSynchronizesLegacyPathCache() throws Exception {
        Path oldRoot = Files.createDirectories(tempDir.resolve("work-relocate-old"));
        StorageRoot previous = repointActiveWorkRoot(oldRoot);
        Path oldVideo = oldRoot.resolve("relocate/video.flv");
        Files.createDirectories(oldVideo.getParent());
        Files.writeString(oldVideo, "relocate-data");
        RecordHistoryPart part = createPart(oldVideo, false);

        Path configuredRoot = Files.createDirectories(Path.of(rootService.configuredWorkPath()));
        Path relocatedVideo = configuredRoot.resolve("relocate/video.flv");
        Files.createDirectories(relocatedVideo.getParent());
        Files.writeString(relocatedVideo, "relocate-data");

        StorageRoot relocated = rootService.resolveWorkPathChange(
                StorageRootService.WorkPathChangeMode.RELOCATE_EXISTING);

        assertEquals(previous.getId(), relocated.getId());
        assertTrue(StorageRootService.samePath(configuredRoot.toString(), relocated.getPath()));
        assertEquals(relocatedVideo.toString().replace('\\', '/'),
                partRepository.findById(part.getId()).orElseThrow().getFilePath());
        PartFileLocation location = locationService.findLocations(part.getId()).get(0);
        assertEquals(relocatedVideo.toString(), location.getAbsolutePathSnapshot());
    }

    @Test
    void relocateExistingRejectsIncompleteTargetWithoutChangingRoot() throws Exception {
        Path oldRoot = Files.createDirectories(tempDir.resolve("work-relocate-reject-old"));
        StorageRoot previous = repointActiveWorkRoot(oldRoot);
        Path oldVideo = oldRoot.resolve("missing-at-target/video.flv");
        Files.createDirectories(oldVideo.getParent());
        Files.writeString(oldVideo, "must-exist-at-target");
        createPart(oldVideo, false);
        Files.createDirectories(Path.of(rootService.configuredWorkPath()));

        assertThrows(IllegalArgumentException.class, () -> rootService.resolveWorkPathChange(
                StorageRootService.WorkPathChangeMode.RELOCATE_EXISTING));

        StorageRoot unchanged = rootRepository.findById(previous.getId()).orElseThrow();
        assertTrue(StorageRootService.samePath(oldRoot.toString(), unchanged.getPath()));
        assertTrue(unchanged.isActiveForNewFiles());
    }

    @Test
    void remapUpdatesLocationSnapshotAndLegacyPrimaryPath() throws Exception {
        Path archive = Files.createDirectories(tempDir.resolve("archive-remap-old"));
        StorageRoot archiveRoot = rootService.getOrCreateArchiveRoot(archive.toString());
        Path oldVideo = archive.resolve("remap/video.flv");
        Files.createDirectories(oldVideo.getParent());
        Files.writeString(oldVideo, "remap-data");
        RecordHistoryPart part = createPart(oldVideo, true);

        Path remappedRoot = Files.createDirectories(tempDir.resolve("archive-remap-new"));
        Path remappedVideo = remappedRoot.resolve("remap/video.flv");
        Files.createDirectories(remappedVideo.getParent());
        Files.writeString(remappedVideo, "remap-data");
        rootService.remap(archiveRoot.getId(), remappedRoot.toString());

        PartFileLocation location = locationService.findLocations(part.getId()).get(0);
        assertEquals(remappedVideo.toString(), location.getAbsolutePathSnapshot());
        assertEquals(remappedVideo.toString().replace('\\', '/'),
                partRepository.findById(part.getId()).orElseThrow().getFilePath());
        assertEquals(PartFileLocationService.LocalFileState.AVAILABLE_ARCHIVE,
                locationService.resolveReadable(part.getId()).state());
    }

    @Test
    void missingPrimaryPromotesAvailableReplicaAndSynchronizesLegacyPath() throws Exception {
        Path source = createVideo("promote/video.mp4", "promote-data");
        RecordHistoryPart part = createPart(source, false);
        Path archive = Files.createDirectories(tempDir.resolve("archive-promote"));
        operationService.copy(part.getId(), archive.toString());
        Files.delete(source);

        PartFileLocationService.FileResolution readable = locationService.resolveReadable(part.getId());

        Path replica = archive.resolve("promote/video.mp4");
        assertEquals(PartFileLocationService.LocalFileState.AVAILABLE_ARCHIVE, readable.state());
        assertEquals(replica, readable.path());
        assertEquals(PartFileLocation.LocationRole.PRIMARY, readable.location().getRole());
        assertEquals(replica.toString().replace('\\', '/'),
                partRepository.findById(part.getId()).orElseThrow().getFilePath());
    }

    @Test
    void copyFailureLeavesSourceReadableAndMarksOnlyTargetFailed() throws Exception {
        Path source = createVideo("copy-failure/video.mp4", "copy-failure-data");
        RecordHistoryPart part = createPart(source, false);
        Path archive = Files.createDirectories(tempDir.resolve("archive-copy-failure"));
        doThrow(new java.io.IOException("injected copy failure"))
                .when(storageAdapter).copy(any(Path.class), any(Path.class));

        PartFileOperation operation = operationService.copy(part.getId(), archive.toString());

        assertEquals(PartFileOperation.OperationStatus.FAILED, operation.getStatus());
        assertTrue(Files.isRegularFile(source));
        assertEquals(PartFileLocationService.LocalFileState.AVAILABLE_WORK,
                locationService.resolveReadable(part.getId()).state());
        PartFileLocation target = locationRepository.findByPartIdAndStorageRootIdAndRelativePath(
                part.getId(), operation.getTargetRootId(), operation.getTargetRelativePath()).orElseThrow();
        assertEquals(PartFileLocation.LocationState.PROCESS_FAILED, target.getState());
    }

    @Test
    void companionFailureProducesWarningAndBoundaryMatchExcludesSimilarStem() throws Exception {
        Path source = createVideo("companion/video.flv", "video-data");
        Path xml = source.resolveSibling("video.xml");
        Path similarStem = source.resolveSibling("video2.xml");
        Files.writeString(xml, "xml");
        Files.writeString(similarStem, "must-stay");
        RecordHistoryPart part = createPart(source, false);
        Path archive = Files.createDirectories(tempDir.resolve("archive-companion-warning"));
        doAnswer(invocation -> {
            Path movingSource = invocation.getArgument(0);
            if (movingSource.getFileName().toString().equals("video.xml")) {
                throw new java.io.IOException("injected companion failure");
            }
            return invocation.callRealMethod();
        }).when(storageAdapter).move(any(Path.class), any(Path.class));

        PartFileOperation operation = operationService.move(part.getId(), archive.toString());

        assertEquals(PartFileOperation.OperationStatus.SUCCEEDED_WITH_WARNINGS, operation.getStatus());
        assertTrue(Files.isRegularFile(archive.resolve("companion/video.flv")));
        assertTrue(Files.isRegularFile(xml));
        assertTrue(Files.isRegularFile(similarStem));
        assertFalse(Files.exists(archive.resolve("companion/video2.xml")));
    }

    @Test
    void databaseFailureAfterPhysicalMoveConvergesOnRetry() throws Exception {
        Path source = createVideo("db-recovery/video.flv", "db-recovery-data");
        RecordHistoryPart part = createPart(source, false);
        Path archive = Files.createDirectories(tempDir.resolve("archive-db-recovery"));
        doThrow(new IllegalStateException("injected database failure"))
                .doCallRealMethod()
                .when(locationService).completeMove(eq(part.getId()), any(PartFileLocation.class),
                        any(PartFileLocation.class), any(Path.class));

        PartFileOperation failed = operationService.move(part.getId(), archive.toString());
        assertEquals(PartFileOperation.OperationStatus.FAILED, failed.getStatus());
        assertFalse(Files.exists(source));
        assertTrue(Files.isRegularFile(archive.resolve("db-recovery/video.flv")));

        PartFileOperation recovered = operationService.retry(failed.getOperationKey());

        assertEquals(PartFileOperation.OperationStatus.SUCCEEDED, recovered.getStatus());
        assertEquals(PartFileLocationService.LocalFileState.AVAILABLE_ARCHIVE,
                locationService.resolveReadable(part.getId()).state());
    }

    @Test
    void legacyMigrationIsRepeatableAndPreservesSubmissionState() throws Exception {
        Path source = createVideo("migration/video.flv", "migration-data");
        RecordHistory history = new RecordHistory();
        history.setRoomId("migration-room");
        history.setEventId("migration-history-" + UUID.randomUUID());
        history.setUpload(true);
        history.setPublish(false);
        history = historyRepository.save(history);
        RecordHistoryPart part = createPartRecord(source, false, Files.size(source));
        part.setHistoryId(history.getId());
        part.setUpload(false);
        part.setDeleteFailType("NETWORK_ERROR");
        part = partRepository.save(part);
        Path archive = Files.createDirectories(tempDir.resolve("archive-from-room"));
        RecordRoom room = new RecordRoom();
        room.setRoomId("migration-room");
        room.setMoveDir(archive.toString());
        roomRepository.save(room);
        configRepository.deleteById(StorageLifecycleMigrationService.MIGRATION_KEY);
        configRepository.flush();

        migrationService.migrateAfterStartup();
        migrationService.migrateAfterStartup();

        assertEquals(StorageLifecycleMigrationService.MIGRATION_VERSION,
                configRepository.findById(StorageLifecycleMigrationService.MIGRATION_KEY)
                        .orElseThrow().getConfigValue());
        assertEquals(1, locationService.findLocations(part.getId()).size());
        assertEquals(PartFileLocation.LocationState.AVAILABLE,
                locationService.findLocations(part.getId()).get(0).getState());
        RecordHistoryPart unchanged = partRepository.findById(part.getId()).orElseThrow();
        assertFalse(unchanged.isUpload());
        assertEquals("NETWORK_ERROR", unchanged.getDeleteFailType());
        assertTrue(rootService.findAll().stream().anyMatch(root ->
                root.getRootType() == StorageRoot.RootType.ARCHIVE
                        && StorageRootService.samePath(root.getPath(), archive.toString())));
    }

    private Path createVideo(String relative, String content) throws Exception {
        Path work = Path.of(rootService.configuredWorkPath());
        Files.createDirectories(work);
        rootService.refreshAllHealth();
        Path file = work.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private StorageRoot repointActiveWorkRoot(Path path) {
        StorageRoot root = rootService.activeWorkRoot().orElseThrow();
        root.setPath(path.toAbsolutePath().normalize().toString());
        root.setStatus(StorageRoot.RootStatus.ONLINE);
        root.setWritable(true);
        return rootRepository.save(root);
    }

    private RecordHistoryPart createPart(Path file, boolean fileDelete) throws Exception {
        RecordHistoryPart part = createPartRecord(file, fileDelete, Files.size(file));
        locationService.registerPrimary(part);
        return part;
    }

    private RecordHistoryPart createPartRecord(Path file, boolean fileDelete, long size) {
        RecordHistoryPart part = new RecordHistoryPart();
        part.setRoomId("room-" + UUID.randomUUID());
        part.setEventId("event-" + UUID.randomUUID());
        part.setFilePath(file.toString().replace('\\', '/'));
        part.setFileSize(size);
        part.setFileDelete(fileDelete);
        part.setRecording(false);
        return partRepository.save(part);
    }
}
