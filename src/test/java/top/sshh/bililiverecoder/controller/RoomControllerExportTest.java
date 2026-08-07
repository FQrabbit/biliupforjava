package top.sshh.bililiverecoder.controller;

import com.alibaba.fastjson.JSON;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.bililiverecoder.entity.ExportConfigParams;
import top.sshh.bililiverecoder.entity.PartFileLocation;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.StorageRoot;
import top.sshh.bililiverecoder.repo.PartFileLocationRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.StorageRootRepository;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoomControllerExportTest {

    private final RecordHistoryRepository historyRepository = mock(RecordHistoryRepository.class);
    private final RecordHistoryPartRepository partRepository = mock(RecordHistoryPartRepository.class);
    private final StorageRootRepository storageRootRepository = mock(StorageRootRepository.class);
    private final PartFileLocationRepository partFileLocationRepository = mock(PartFileLocationRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final RoomController controller = new RoomController();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "historyRepository", historyRepository);
        ReflectionTestUtils.setField(controller, "partRepository", partRepository);
        ReflectionTestUtils.setField(controller, "storageRootRepository", storageRootRepository);
        ReflectionTestUtils.setField(controller, "partFileLocationRepository", partFileLocationRepository);
        ReflectionTestUtils.setField(controller, "entityManager", entityManager);

        when(historyRepository.findByIdGreaterThanOrderByIdAsc(anyLong(), any(Pageable.class)))
                .thenReturn(List.of());
        when(partRepository.findByIdGreaterThanOrderByIdAsc(anyLong(), any(Pageable.class)))
                .thenReturn(List.of());
        when(partFileLocationRepository.findByStateNotAndIdGreaterThanOrderByIdAsc(
                eq(PartFileLocation.LocationState.PROCESSING), anyLong(), any(Pageable.class)))
                .thenReturn(List.of());
    }

    @Test
    void exportsStorageRootsAsEntitiesAndWritesCompletionMarker() throws Exception {
        StorageRoot root = new StorageRoot();
        root.setId(7L);
        root.setRootKey("archive-1");
        root.setRootType(StorageRoot.RootType.ARCHIVE);
        root.setPath("D:/archive");
        when(storageRootRepository.findAllByOrderByIdAsc()).thenReturn(List.of(root));
        RecordHistory history = new RecordHistory();
        history.setId(11L);
        when(historyRepository.count()).thenReturn(1L);
        when(historyRepository.findByIdGreaterThanOrderByIdAsc(anyLong(), any(Pageable.class)))
                .thenReturn(List.of(history));

        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.exportConfig(historyExportParams(), response);

        Map<String, Object> exported = JSON.parseObject(response.getContentAsString());
        List<?> roots = (List<?>) exported.get("storageRootList");
        assertEquals(1, roots.size());
        List<?> histories = (List<?>) exported.get("historyList");
        assertEquals(1, histories.size());
        assertEquals(1L, ((Number) exported.get("recordCount")).longValue());
        Map<?, ?> sectionCounts = (Map<?, ?>) exported.get("sectionCounts");
        assertEquals(1L, ((Number) sectionCounts.get("historyList")).longValue());
        assertEquals(Boolean.TRUE, exported.get("exportCompleted"));
        verify(storageRootRepository).findAllByOrderByIdAsc();
    }

    @Test
    void failedExportDoesNotWriteCompletionMarkerAndPublishesFailureReason() throws Exception {
        when(storageRootRepository.findAllByOrderByIdAsc()).thenReturn(List.of());
        when(historyRepository.findByIdGreaterThanOrderByIdAsc(anyLong(), any(Pageable.class)))
                .thenThrow(new IllegalStateException("录制历史读取失败"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> controller.exportConfig(historyExportParams(), response));

        assertEquals("录制历史读取失败", error.getMessage());
        assertTrue(!response.getContentAsString().contains("\"exportCompleted\":true"));
        Map<String, Object> status = controller.configTaskStatus();
        assertEquals("FAILED", status.get("phase"));
        assertTrue(String.valueOf(status.get("message")).contains("录制历史读取失败"));
    }

    @Test
    void legacyImportProgressKeepsBytesAndRecordsInSeparateUnits() {
        ReflectionTestUtils.invokeMethod(controller, "startConfigImportTask", "导入配置", 1_000L);
        AtomicLong bytesRead = (AtomicLong) ReflectionTestUtils.getField(controller, "configImportBytesRead");
        bytesRead.set(500L);
        ReflectionTestUtils.invokeMethod(controller, "updateConfigTask", "导入弹幕", "正在处理", 100_000L);

        Map<String, Object> status = controller.configTaskStatus();
        assertEquals("bytes", status.get("unit"));
        assertEquals(500L, status.get("processed"));
        assertEquals(1_000L, status.get("total"));
        assertEquals(50, status.get("percent"));
        assertEquals(100_000L, status.get("recordsProcessed"));
    }

    @Test
    void importProgressUsesRecordTotalWhenBackupContainsMetadata() {
        ReflectionTestUtils.invokeMethod(controller, "startConfigImportTask", "导入配置", 10_000L);
        ReflectionTestUtils.invokeMethod(controller, "setConfigTaskRecordTotal", 2_000L);
        ReflectionTestUtils.invokeMethod(controller, "updateConfigTask", "导入弹幕", "正在处理", 1_000L);

        Map<String, Object> status = controller.configTaskStatus();
        assertEquals("records", status.get("unit"));
        assertEquals(1_000L, status.get("processed"));
        assertEquals(2_000L, status.get("total"));
        assertEquals(50, status.get("percent"));
    }

    private ExportConfigParams historyExportParams() {
        ExportConfigParams params = new ExportConfigParams();
        params.setExportHistory(true);
        return params;
    }
}
