package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.repo.*;
import top.sshh.bililiverecoder.util.UploadProgressTracker;

import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HistoryVideoCleanupServiceTest {
    @TempDir Path directory;
    private final RecordHistoryRepository histories = mock(RecordHistoryRepository.class);
    private final RecordHistoryPartRepository parts = mock(RecordHistoryPartRepository.class);
    private final PartFileLocationRepository locations = mock(PartFileLocationRepository.class);
    private final PartFileOperationRepository operations = mock(PartFileOperationRepository.class);
    private final StorageRootRepository rootRepository = mock(StorageRootRepository.class);
    private final PublishTaskService publish = mock(PublishTaskService.class);
    private final PartActivityCoordinator activities = new PartActivityCoordinator();
    private final PartFileStorageAdapter storage = spy(new PartFileStorageAdapter());
    private final Map<Long, RecordHistoryPart> partData = new LinkedHashMap<>();
    private final Map<Long, PartFileLocation> locationData = new LinkedHashMap<>();
    private final Map<Long, StorageRoot> rootData = new LinkedHashMap<>();
    private final Map<Long, PartFileOperation> operationData = new LinkedHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private RecordHistory history;
    private StorageRootService roots;
    private PartFileLocationService locationService;
    private HistoryVideoCleanupService service;
    private StorageRoot work;
    private StorageRoot archive;

    @BeforeEach
    void setup() throws Exception {
        history = new RecordHistory();
        history.setId(1L); history.setPublish(true); history.setCode(0); history.setTitle("保留的历史");
        work = root(10L, StorageRoot.RootType.WORK, directory.resolve("工作目录"));
        archive = root(11L, StorageRoot.RootType.ARCHIVE, directory.resolve("归档目录"));
        when(histories.findById(1L)).thenReturn(Optional.of(history));
        when(parts.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(partData.get(i.getArgument(0))));
        when(parts.findByHistoryId(anyLong())).thenAnswer(i -> partData.values().stream()
                .filter(p -> Objects.equals(p.getHistoryId(), i.getArgument(0))).toList());
        when(parts.save(any())).thenAnswer(i -> i.getArgument(0));
        when(parts.findByFilePathEndingWithIgnoreCase(anyString())).thenAnswer(i -> partData.values().stream()
                .filter(p -> p.getFilePath() != null && p.getFilePath().toLowerCase(Locale.ROOT).endsWith(i.<String>getArgument(0).toLowerCase(Locale.ROOT))).toList());
        when(locations.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(locationData.get(i.getArgument(0))));
        when(locations.findByPartIdOrderByIdAsc(anyLong())).thenAnswer(i -> new ArrayList<>(locationData.values().stream()
                .filter(l -> Objects.equals(l.getPartId(), i.getArgument(0))).toList()));
        when(locations.findByRelativePathEndingWithIgnoreCase(anyString())).thenAnswer(i -> locationData.values().stream()
                .filter(l -> l.getRelativePath().toLowerCase(Locale.ROOT).endsWith(i.<String>getArgument(0).toLowerCase(Locale.ROOT))).toList());
        when(locations.save(any())).thenAnswer(i -> i.getArgument(0));
        when(rootRepository.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(rootData.get(i.getArgument(0))));
        when(rootRepository.findAllByOrderByIdAsc()).thenAnswer(i -> new ArrayList<>(rootData.values()));
        when(rootRepository.findByRootTypeAndActiveForNewFilesIsTrue(StorageRoot.RootType.WORK)).thenReturn(List.of(work));
        when(rootRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(operations.save(any())).thenAnswer(i -> {
            PartFileOperation op = i.getArgument(0);
            if (op.getId() == null) op.setId(sequence.incrementAndGet());
            if (op.getCreatedAt() == null) op.setCreatedAt(LocalDateTime.now());
            operationData.put(op.getId(), op);
            return op;
        });
        when(operations.findFirstByPartIdAndStatusInOrderByCreatedAtDesc(anyLong(), any())).thenAnswer(i ->
                operationData.values().stream().filter(op -> Objects.equals(op.getPartId(), i.getArgument(0))
                        && i.<Collection<?>>getArgument(1).contains(op.getStatus())).findFirst());
        when(operations.findByStatusInOrderByCreatedAtAsc(any())).thenAnswer(i -> operationData.values().stream()
                .filter(op -> i.<Collection<?>>getArgument(0).contains(op.getStatus())).toList());
        when(operations.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(operationData.get(i.getArgument(0))));
        when(publish.getActiveForHistory(anyLong())).thenReturn(List.of());
        roots = new StorageRootService(rootRepository, locations, parts, work.getPath(), "", "", "", mock(ApplicationEventPublisher.class));
        locationService = new PartFileLocationService(locations, parts, roots);
        service = new HistoryVideoCleanupService(histories, parts, locations, operations, locationService,
                roots, storage, activities, publish, mock(UploadProgressTracker.class));
    }

    @Test
    void deletesOnlyConfirmedVideoAndPreservesArchiveCompanionsAndHistory() throws Exception {
        RecordHistoryPart part = part(2L, 1L);
        Path video = file(part, work, "P1.mp4", "source");
        Path copy = file(part, archive, "P1.mp4", "source");
        Path xml = Files.writeString(video.resolveSibling("P1.xml"), "<i/>");
        Path cover = Files.writeString(video.resolveSibling("P1.jpg"), "cover");
        var preview = service.preview(1L);
        var selected = preview.files().stream().filter(f -> f.scope().equals("WORK")).findFirst().orElseThrow();
        var result = service.delete(1L, preview.token(), selected.id());
        assertEquals("DELETED", result.status());
        assertEquals(6, result.deletedBytes());
        assertFalse(Files.exists(video));
        assertTrue(Files.exists(copy)); assertTrue(Files.exists(xml)); assertTrue(Files.exists(cover));
        assertEquals("AVAILABLE_ARCHIVE", locationService.describe(2L).state().name());
        assertEquals("保留的历史", history.getTitle()); assertTrue(history.isPublish());
        verify(histories, never()).delete(any());
        var again = service.delete(1L, preview.token(), selected.id());
        assertEquals(result, again);
        verify(storage, times(1)).delete(video);
        assertTrue(operationData.values().stream().allMatch(op -> op.getOperationSource() == PartFileOperation.OperationSource.MANUAL_CLEANUP
                && op.getStatus() == PartFileOperation.OperationStatus.SUCCEEDED));
    }

    @Test
    void allCopiesRemovedAreExpectedManualCleanupRatherThanMissing() throws Exception {
        file(part(2L, 1L), work, "P1.flv", "recording");
        var preview = service.preview(1L);
        assertEquals("DELETED", service.delete(1L, preview.token(), preview.files().get(0).id()).status());
        var local = locationService.describe(2L);
        assertEquals(PartFileLocationService.LocalFileState.DELETED_BY_POLICY, local.state());
        assertFalse(local.expected()); assertEquals("视频已手动清理", local.message());
    }

    @Test
    void replacementAfterConfirmationIsNeverDeleted() throws Exception {
        Path video = file(part(2L, 1L), work, "P1.mp4", "before");
        var preview = service.preview(1L);
        Files.writeString(video, "after replacement has a different size");
        assertEquals("SKIPPED", service.delete(1L, preview.token(), preview.files().get(0).id()).status());
        assertTrue(Files.exists(video)); verify(storage, never()).delete(any());
    }

    @Test
    void fileChangedWhileSavingIntentIsRecheckedImmediatelyBeforeDelete() throws Exception {
        Path video = file(part(2L, 1L), work, "P1.mp4", "before");
        var preview = service.preview(1L);
        doAnswer(i -> {
            PartFileOperation op = i.getArgument(0);
            if (op.getId() == null) {
                op.setId(sequence.incrementAndGet()); operationData.put(op.getId(), op);
                Files.writeString(video, "changed while persisting deletion intent");
            }
            return op;
        }).when(operations).save(any());
        assertEquals("SKIPPED", service.delete(1L, preview.token(), preview.files().get(0).id()).status());
        assertTrue(Files.exists(video)); verify(storage, never()).delete(any());
        assertEquals(PartFileOperation.OperationStatus.FAILED, operationData.values().iterator().next().getStatus());
    }

    @Test
    void rootRemapAfterConfirmationCannotDeleteNewOrOldFiles() throws Exception {
        Path video = file(part(2L, 1L), work, "P1.mp4", "before");
        var preview = service.preview(1L);
        work.setPath(Files.createDirectory(directory.resolve("新的映射")).toString());
        assertEquals("SKIPPED", service.delete(1L, preview.token(), preview.files().get(0).id()).status());
        assertTrue(Files.exists(video)); verify(storage, never()).delete(any());
    }

    @Test
    void previewAndFileIdsCannotBeReusedForAnotherHistoryOrExpandedToNewFiles() throws Exception {
        Path video = file(part(2L, 1L), work, "P1.mp4", "recording");
        var preview = service.preview(1L);
        file(part(3L, 1L), work, "P2.mp4", "later");
        assertThrows(IllegalArgumentException.class, () -> service.delete(99L, preview.token(), preview.files().get(0).id()));
        assertThrows(IllegalArgumentException.class, () -> service.delete(1L, preview.token(), "any-new-file"));
        assertTrue(Files.exists(video)); verify(storage, never()).delete(any());
    }

    @Test
    void rejectedAndForcedArchivedIdleFilesCanBeExplicitlyCleaned() throws Exception {
        history.setForceArchived(true); history.setCode(-2);
        file(part(2L, 1L), work, "P1.mkv", "recording");
        var preview = service.preview(1L);
        assertNull(preview.disabledReason());
        assertEquals("DELETED", service.delete(1L, preview.token(), preview.files().get(0).id()).status());
        assertTrue(history.isForceArchived()); assertEquals(-2, history.getCode());
    }

    @Test
    void newRecordingOrQueuedPublishTaskBlocksPreviouslyConfirmedDeletion() throws Exception {
        Path video = file(part(2L, 1L), work, "P1.mp4", "recording");
        var preview = service.preview(1L);
        history.setRecording(true);
        assertEquals("SKIPPED", service.delete(1L, preview.token(), preview.files().get(0).id()).status());
        history.setRecording(false);
        var other = service.preview(1L);
        when(publish.getActiveForHistory(1L)).thenReturn(List.of(new PublishTask()));
        assertEquals("SKIPPED", service.delete(1L, other.token(), other.files().get(0).id()).status());
        assertTrue(Files.exists(video)); verify(storage, never()).delete(any());
    }

    @Test
    void uploadAdmissionAfterPreviewBlocksDelete() throws Exception {
        Path video = file(part(2L, 1L), work, "P1.mp4", "recording");
        var preview = service.preview(1L);
        assertTrue(activities.tryRegisterUpload(2L, 1L));
        try {
            assertEquals("SKIPPED", service.delete(1L, preview.token(), preview.files().get(0).id()).status());
            assertTrue(Files.exists(video));
        } finally { activities.releaseUpload(2L, 1L); }
    }

    @Test
    void anotherHistoryReferenceIsNotCleaned() throws Exception {
        Path video = file(part(2L, 1L), work, "P1.mp4", "recording");
        RecordHistoryPart other = part(3L, 9L);
        other.setFilePath(video.toString());
        var preview = service.preview(1L);
        assertFalse(preview.files().get(0).available());
        assertEquals("SKIPPED", service.delete(1L, preview.token(), preview.files().get(0).id()).status());
        verify(storage, never()).delete(any()); assertTrue(Files.exists(video));
    }

    @Test
    void referenceAddedAfterPreviewIsAlsoProtected() throws Exception {
        Path video = file(part(2L, 1L), work, "P1.mp4", "recording");
        var preview = service.preview(1L);
        part(3L, 9L).setFilePath(video.toString());
        assertEquals("SKIPPED", service.delete(1L, preview.token(), preview.files().get(0).id()).status());
        verify(storage, never()).delete(any()); assertTrue(Files.exists(video));
    }

    @Test
    void workAndArchiveAliasesOfOneFileRequireArchiveScope() throws Exception {
        file(part(2L, 1L), work, "P1.mp4", "recording");
        archive.setPath(work.getPath());
        file(partData.get(2L), archive, "P1.mp4", "recording");
        var preview = service.preview(1L);
        assertEquals(1, preview.files().size());
        assertEquals("ARCHIVE", preview.files().get(0).scope());
    }

    @Test
    void duplicatePathsWithinCardAreCountedOnceAndAllReferencesMarked() throws Exception {
        Path video = file(part(2L, 1L), work, "P1.mp4", "recording");
        file(part(3L, 1L), work, "P1.mp4", "recording");
        var preview = service.preview(1L);
        assertEquals(1, preview.files().size());
        assertEquals(List.of(2L, 3L), preview.files().get(0).partIds());
        assertEquals("DELETED", service.delete(1L, preview.token(), preview.files().get(0).id()).status());
        verify(storage, times(1)).delete(video);
        assertTrue(locationData.values().stream().allMatch(l -> l.getState() == PartFileLocation.LocationState.DELETED_BY_POLICY));
    }

    @Test
    void offlineDirectoryIsSkippedWithoutCreatingPendingDelete() throws Exception {
        Path video = file(part(2L, 1L), work, "P1.mp4", "recording");
        var preview = service.preview(1L);
        work.setLastCheckedAt(null);
        assertEquals("SKIPPED", service.delete(1L, preview.token(), preview.files().get(0).id()).status());
        assertTrue(Files.exists(video)); assertTrue(operationData.isEmpty());
    }

    @Test
    void failedDeleteKeepsFileAndOtherSelectedFileCanStillBeDeleted() throws Exception {
        Path first = file(part(2L, 1L), work, "P1.mp4", "first");
        Path second = file(part(3L, 1L), work, "P2.mp4", "second");
        doThrow(new AccessDeniedException(first.toString())).when(storage).delete(first);
        var preview = service.preview(1L);
        assertEquals("FAILED", service.delete(1L, preview.token(), preview.files().get(0).id()).status());
        assertEquals("DELETED", service.delete(1L, preview.token(), preview.files().get(1).id()).status());
        assertTrue(Files.exists(first)); assertFalse(Files.exists(second));
        assertTrue(operationData.values().stream().noneMatch(op -> op.getStatus() == PartFileOperation.OperationStatus.PENDING));
    }

    @Test
    void interruptedManualCleanupRecoveryNeverDeletesExistingFile() throws Exception {
        Path video = file(part(2L, 1L), work, "P1.mp4", "keep");
        var location = locationData.values().iterator().next();
        PartFileOperation operation = new PartFileOperation();
        operation.setPartId(2L); operation.setSourceLocationId(location.getId());
        operation.setTargetRootId(work.getId()); operation.setTargetRelativePath("P1.mp4");
        operation.setSourcePathSnapshot(video.toString());
        operation.setOperationType(PartFileOperation.OperationType.DELETE);
        operation.setOperationSource(PartFileOperation.OperationSource.MANUAL_CLEANUP);
        operation.setStatus(PartFileOperation.OperationStatus.RUNNING);
        operations.save(operation);
        var existing = new PartFileOperationService(operations, locations, locationService, roots, storage);
        ReflectionTestUtils.setField(existing, "partActivityCoordinator", activities);
        existing.recoverPendingOperations();
        assertEquals(PartFileOperation.OperationStatus.FAILED, operation.getStatus());
        assertTrue(Files.exists(video)); verify(storage, never()).delete(any());
        when(operations.findByOperationKey(operation.getOperationKey())).thenReturn(Optional.of(operation));
        assertThrows(IllegalArgumentException.class, () -> existing.retry(operation.getOperationKey()));
    }

    @Test
    void interruptedManualCleanupRepairsMetadataForAlreadyRemovedFile() throws Exception {
        Path video = file(part(2L, 1L), work, "P1.mp4", "removed");
        var location = locationData.values().iterator().next();
        PartFileOperation operation = new PartFileOperation();
        operation.setPartId(2L); operation.setSourceLocationId(location.getId());
        operation.setTargetRootId(work.getId()); operation.setTargetRelativePath("P1.mp4");
        operation.setSourcePathSnapshot(video.toString());
        operation.setOperationType(PartFileOperation.OperationType.DELETE);
        operation.setOperationSource(PartFileOperation.OperationSource.MANUAL_CLEANUP);
        operation.setStatus(PartFileOperation.OperationStatus.RUNNING); operations.save(operation);
        Files.delete(video);
        var existing = new PartFileOperationService(operations, locations, locationService, roots, storage);
        ReflectionTestUtils.setField(existing, "partActivityCoordinator", activities);
        existing.recoverPendingOperations();
        assertEquals(PartFileOperation.OperationStatus.SUCCEEDED, operation.getStatus());
        assertEquals("视频已手动清理", locationService.describe(2L).message());
        verify(storage, never()).delete(any());
    }

    private StorageRoot root(long id, StorageRoot.RootType type, Path path) throws Exception {
        Files.createDirectories(path);
        StorageRoot root = new StorageRoot();
        root.setId(id); root.setRootType(type); root.setPath(path.toString());
        root.setStatus(StorageRoot.RootStatus.ONLINE); root.setLastCheckedAt(LocalDateTime.now());
        root.setActiveForNewFiles(type == StorageRoot.RootType.WORK);
        rootData.put(id, root);
        return root;
    }

    private RecordHistoryPart part(long id, long historyId) {
        RecordHistoryPart part = new RecordHistoryPart();
        part.setId(id); part.setHistoryId(historyId); part.setPage((int) id - 1);
        part.setEndTime(LocalDateTime.now().minusDays(1)); part.setUpload(true);
        partData.put(id, part);
        return part;
    }

    private Path file(RecordHistoryPart part, StorageRoot root, String name, String value) throws Exception {
        Path path = Path.of(root.getPath()).resolve(name);
        Files.writeString(path, value);
        if (part.getFilePath() == null) part.setFilePath(path.toString());
        PartFileLocation location = new PartFileLocation();
        location.setId(sequence.incrementAndGet()); location.setPartId(part.getId());
        location.setStorageRootId(root.getId()); location.setRelativePath(name);
        location.setAbsolutePathSnapshot(path.toString()); location.setExpectedSize(Files.size(path));
        location.setRole(root.getRootType() == StorageRoot.RootType.WORK
                ? PartFileLocation.LocationRole.PRIMARY : PartFileLocation.LocationRole.REPLICA);
        locationData.put(location.getId(), location);
        return path;
    }
}
