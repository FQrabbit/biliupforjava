package top.sshh.bililiverecoder.controller;

import com.alibaba.fastjson.JSONReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.bililiverecoder.entity.PartFileLocation;
import top.sshh.bililiverecoder.entity.StorageRoot;
import top.sshh.bililiverecoder.repo.PartFileLocationRepository;
import top.sshh.bililiverecoder.repo.StorageRootRepository;

import java.io.StringReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RoomControllerStorageImportTest {

    private final StorageRootRepository rootRepository = mock(StorageRootRepository.class);
    private final PartFileLocationRepository locationRepository = mock(PartFileLocationRepository.class);
    private final RoomController controller = new RoomController();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "storageRootRepository", rootRepository);
        ReflectionTestUtils.setField(controller, "partFileLocationRepository", locationRepository);
    }

    @Test
    void importedRootUsesRootKeyMappingAndDefaultsOffline() {
        when(rootRepository.findByRootKey("root-key-1")).thenReturn(Optional.empty());
        when(rootRepository.save(any(StorageRoot.class))).thenAnswer(invocation -> {
            StorageRoot root = invocation.getArgument(0);
            root.setId(99L);
            return root;
        });
        Map<Long, Long> rootIdMap = new HashMap<>();
        String json = "[{\"id\":10,\"rootKey\":\"root-key-1\",\"rootType\":\"ARCHIVE\","
                + "\"path\":\"X:/old-archive\",\"status\":\"ONLINE\","
                + "\"activeForNewFiles\":true,\"writable\":true}]";

        Integer imported;
        try (JSONReader reader = new JSONReader(new StringReader(json))) {
            imported = ReflectionTestUtils.invokeMethod(
                    controller, "importStorageRootSection", reader, rootIdMap);
        }

        assertEquals(1, imported);
        assertEquals(99L, rootIdMap.get(10L));
        StorageRoot saved = captureSavedRoot();
        assertEquals(StorageRoot.RootStatus.OFFLINE, saved.getStatus());
        assertFalse(saved.isWritable());
        assertFalse(saved.isActiveForNewFiles());
        assertNull(saved.getLastCheckedAt());
    }

    @Test
    void importedLocationsRemapIdsRejectUnsafeStatesAndKeepOnePrimary() {
        PartFileLocation existingPrimary = new PartFileLocation();
        existingPrimary.setId(1L);
        existingPrimary.setPartId(200L);
        existingPrimary.setRole(PartFileLocation.LocationRole.PRIMARY);
        when(locationRepository.findByPartIdOrderByIdAsc(200L)).thenReturn(List.of(existingPrimary));
        when(locationRepository.findByPartIdAndStorageRootIdAndRelativePath(
                eq(200L), eq(99L), eq("session/video.flv"))).thenReturn(Optional.empty());
        when(locationRepository.save(any(PartFileLocation.class))).thenAnswer(invocation -> invocation.getArgument(0));
        Map<Long, Long> partIdMap = Map.of(20L, 200L);
        Map<Long, Long> rootIdMap = Map.of(10L, 99L);
        String json = "["
                + "{\"partId\":20,\"storageRootId\":10,\"relativePath\":\"../escape.flv\","
                + "\"role\":\"REPLICA\",\"state\":\"AVAILABLE\"},"
                + "{\"partId\":20,\"storageRootId\":10,\"relativePath\":\"session/pending.flv\","
                + "\"role\":\"REPLICA\",\"state\":\"PROCESSING\"},"
                + "{\"id\":30,\"partId\":20,\"storageRootId\":10,"
                + "\"relativePath\":\"session/./video.flv\",\"role\":\"PRIMARY\","
                + "\"state\":\"AVAILABLE\",\"absolutePathSnapshot\":\"X:/old-archive/session/video.flv\"}"
                + "]";

        Integer imported;
        try (JSONReader reader = new JSONReader(new StringReader(json))) {
            imported = ReflectionTestUtils.invokeMethod(
                    controller, "importPartFileLocationSection", reader, partIdMap, rootIdMap);
        }

        assertEquals(1, imported);
        assertEquals(PartFileLocation.LocationRole.REPLICA, existingPrimary.getRole());
        PartFileLocation importedLocation = captureImportedLocation(existingPrimary);
        assertNull(importedLocation.getId());
        assertEquals(200L, importedLocation.getPartId());
        assertEquals(99L, importedLocation.getStorageRootId());
        assertEquals("session/video.flv", importedLocation.getRelativePath());
        assertEquals(PartFileLocation.LocationRole.PRIMARY, importedLocation.getRole());
    }

    private StorageRoot captureSavedRoot() {
        var captor = org.mockito.ArgumentCaptor.forClass(StorageRoot.class);
        verify(rootRepository).save(captor.capture());
        return captor.getValue();
    }

    private PartFileLocation captureImportedLocation(PartFileLocation existingPrimary) {
        var captor = org.mockito.ArgumentCaptor.forClass(PartFileLocation.class);
        verify(locationRepository, atLeast(2)).save(captor.capture());
        return captor.getAllValues().stream()
                .filter(location -> location != existingPrimary)
                .findFirst().orElseThrow();
    }
}
