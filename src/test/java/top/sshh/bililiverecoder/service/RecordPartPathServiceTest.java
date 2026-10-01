package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.bililiverecoder.entity.StorageRoot;
import top.sshh.bililiverecoder.repo.PartFileLocationRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.StorageRootRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecordPartPathServiceTest {
    @TempDir Path workRoot;

    @Test
    void webhookPathMustStayInsideConfiguredStorageRoot() throws Exception {
        Path video = Files.createFile(workRoot.resolve("record.mp4"));
        StorageRoot root = new StorageRoot();
        root.setPath(workRoot.toString());
        root.setStatus(StorageRoot.RootStatus.ONLINE);
        StorageRootRepository roots = mock(StorageRootRepository.class);
        when(roots.findAllByOrderByIdAsc()).thenReturn(List.of(root));
        StorageRootService storage = new StorageRootService(roots, mock(PartFileLocationRepository.class),
                mock(RecordHistoryPartRepository.class), workRoot.toString(), "", "", "",
                mock(ApplicationEventPublisher.class));
        RecordPartPathService paths = new RecordPartPathService();
        ReflectionTestUtils.setField(paths, "workPath", workRoot.toString());
        ReflectionTestUtils.setField(paths, "storageRootService", storage);
        paths.init();

        assertEquals(video.toAbsolutePath().normalize().toString().replace('\\', '/'),
                paths.resolveWebhookPath("record.mp4"));
        assertThrows(IllegalArgumentException.class, () -> paths.resolveWebhookPath("../outside.mp4"));
        assertThrows(IllegalArgumentException.class, () -> paths.resolveWebhookPath("https://example.test/video.mp4"));
    }
}
