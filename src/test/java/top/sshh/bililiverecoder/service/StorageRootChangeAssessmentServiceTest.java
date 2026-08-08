package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.task.SyncTaskExecutor;
import top.sshh.bililiverecoder.entity.PartFileLocation;
import top.sshh.bililiverecoder.entity.StorageRoot;
import top.sshh.bililiverecoder.repo.PartFileLocationRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StorageRootChangeAssessmentServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void completeMatchingFilesAllowDatabaseRemap() throws Exception {
        StorageRootService rootService = mock(StorageRootService.class);
        PartFileLocationRepository locationRepository = mock(PartFileLocationRepository.class);
        StorageRoot active = root(7L, tempDir.resolve("old"));
        Path target = Files.createDirectories(tempDir.resolve("new"));
        Path file = Files.createDirectories(target.resolve("room")).resolve("video.flv");
        Files.writeString(file, "video");

        when(rootService.workPathChange()).thenReturn(new StorageRootService.WorkPathChange(true, target.toString(), active));
        when(locationRepository.findByStorageRootIdOrderByIdAsc(7L))
                .thenReturn(List.of(location("room/video.flv", Files.size(file))));

        StorageRootChangeAssessmentService service = new StorageRootChangeAssessmentService(
                rootService, locationRepository, new SyncTaskExecutor());
        StorageRootChangeAssessmentService.Snapshot result = service.start();

        assertEquals(StorageRootChangeAssessmentService.State.SUCCEEDED, result.state());
        assertTrue(result.validForRemap());
        assertEquals(1, result.matched());
        assertTrue(service.isValidForRemap(result.changeId()));
    }

    @Test
    void missingOrMismatchedFilesBlockDatabaseRemap() throws Exception {
        StorageRootService rootService = mock(StorageRootService.class);
        PartFileLocationRepository locationRepository = mock(PartFileLocationRepository.class);
        StorageRoot active = root(8L, tempDir.resolve("old"));
        Path target = Files.createDirectories(tempDir.resolve("new"));
        Path file = target.resolve("video.flv");
        Files.writeString(file, "short");

        when(rootService.workPathChange()).thenReturn(new StorageRootService.WorkPathChange(true, target.toString(), active));
        when(locationRepository.findByStorageRootIdOrderByIdAsc(8L))
                .thenReturn(List.of(location("video.flv", Files.size(file) + 3), location("missing.flv", 1)));

        StorageRootChangeAssessmentService.Snapshot result = new StorageRootChangeAssessmentService(
                rootService, locationRepository, new SyncTaskExecutor()).start();

        assertEquals(StorageRootChangeAssessmentService.State.SUCCEEDED, result.state());
        assertFalse(result.validForRemap());
        assertEquals(1, result.missing());
        assertEquals(1, result.sizeMismatch());
    }

    private static StorageRoot root(Long id, Path path) {
        StorageRoot root = new StorageRoot();
        root.setId(id);
        root.setRootType(StorageRoot.RootType.WORK);
        root.setPath(path.toString());
        root.setStatus(StorageRoot.RootStatus.OFFLINE);
        root.setActiveForNewFiles(true);
        return root;
    }

    private static PartFileLocation location(String relativePath, long expectedSize) {
        PartFileLocation location = new PartFileLocation();
        location.setStorageRootId(7L);
        location.setPartId(1L);
        location.setRelativePath(relativePath);
        location.setExpectedSize(expectedSize);
        location.setState(PartFileLocation.LocationState.AVAILABLE);
        return location;
    }
}
