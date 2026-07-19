package top.sshh.bililiverecoder.controller;

import org.junit.jupiter.api.Test;
import top.sshh.bililiverecoder.entity.PartFileOperation;
import top.sshh.bililiverecoder.entity.StorageRoot;
import top.sshh.bililiverecoder.service.PartFileOperationService;
import top.sshh.bililiverecoder.service.StorageRootService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StorageRootControllerTest {

    private final StorageRootService rootService = mock(StorageRootService.class);
    private final PartFileOperationService operationService = mock(PartFileOperationService.class);
    private final StorageRootController controller = new StorageRootController(rootService, operationService);

    @Test
    void workPathChangeExposesPendingRootAndH2Warning() {
        StorageRoot active = root(1L, "D:/record-old");
        when(rootService.workPathChange()).thenReturn(
                new StorageRootService.WorkPathChange(true, "E:/record-new", active));

        Map<String, Object> result = controller.workPathChange();

        assertEquals(true, result.get("pending"));
        assertEquals("E:/record-new", result.get("configuredPath"));
        assertSame(active, result.get("activeRoot"));
        assertTrue(String.valueOf(result.get("h2Warning")).contains("H2"));
        assertTrue(String.valueOf(result.get("h2Warning")).contains("work-path/db"));
    }

    @Test
    void resolveWorkPathChangeForwardsExplicitModeAndReturnsRoot() {
        StorageRoot relocated = root(1L, "E:/record-new");
        when(rootService.resolveWorkPathChange(StorageRootService.WorkPathChangeMode.RELOCATE_EXISTING))
                .thenReturn(relocated);

        Map<String, Object> result = controller.resolveWorkPathChange(
                Map.of("mode", "RELOCATE_EXISTING"));

        assertEquals(true, result.get("success"));
        assertSame(relocated, result.get("root"));
        verify(rootService).resolveWorkPathChange(StorageRootService.WorkPathChangeMode.RELOCATE_EXISTING);
    }

    @Test
    void retryTreatsWarningsAsSuccessAndReportsFailuresWithoutThrowing() {
        PartFileOperation warning = new PartFileOperation();
        warning.setStatus(PartFileOperation.OperationStatus.SUCCEEDED_WITH_WARNINGS);
        when(operationService.retry("warning-op")).thenReturn(warning);
        when(operationService.retry("missing-op")).thenThrow(new IllegalArgumentException("file operation missing"));

        Map<String, Object> warningResult = controller.retry("warning-op");
        Map<String, Object> failureResult = controller.retry("missing-op");

        assertEquals(true, warningResult.get("success"));
        assertSame(warning, warningResult.get("operation"));
        assertEquals(false, failureResult.get("success"));
        assertEquals("file operation missing", failureResult.get("message"));
    }

    @Test
    void remapReturnsValidationErrorAsStableResponse() {
        when(rootService.remap(7L, "Z:/offline"))
                .thenThrow(new IllegalArgumentException("storage root path is not readable"));

        Map<String, Object> result = controller.remap(7L, Map.of("path", "Z:/offline"));

        assertEquals(false, result.get("success"));
        assertEquals("storage root path is not readable", result.get("message"));
    }

    private static StorageRoot root(Long id, String path) {
        StorageRoot root = new StorageRoot();
        root.setId(id);
        root.setRootType(StorageRoot.RootType.WORK);
        root.setPath(path);
        root.setStatus(StorageRoot.RootStatus.ONLINE);
        return root;
    }
}
