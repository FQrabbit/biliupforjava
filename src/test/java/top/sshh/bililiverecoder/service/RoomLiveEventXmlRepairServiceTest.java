package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.RoomLiveEventXmlIssue;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.util.XmlRepairTool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoomLiveEventXmlRepairServiceTest {
    @TempDir Path directory;
    RecordHistoryPartRepository parts = mock(RecordHistoryPartRepository.class);
    PartFileLocationService locations = mock(PartFileLocationService.class);
    RoomLiveEventXmlIssueService issues = mock(RoomLiveEventXmlIssueService.class);
    RoomLiveEventXmlRepairService service = new RoomLiveEventXmlRepairService(parts, locations, issues);
    RecordHistoryPart part = new RecordHistoryPart();
    Path source;

    @BeforeEach
    void setup() throws IOException {
        part.setId(17L);
        part.setEndTime(LocalDateTime.now());
        source = directory.resolve("danmaku.xml");
        Files.writeString(source, "<i><d p=\"1\">hello</d>");
        RoomLiveEventXmlIssue issue = new RoomLiveEventXmlIssue();
        issue.setIssueType(RoomLiveEventXmlIssue.IssueType.INVALID_XML);
        when(issues.find(17L)).thenReturn(Optional.of(issue));
        when(parts.findById(17L)).thenReturn(Optional.of(part));
        when(locations.resolveCompanionState(17L, ".xml")).thenReturn(available(source));
    }

    @Test
    void replacesSelectedFileOnlyAfterValidationAndPreservesOriginalBackup() throws Exception {
        String original = Files.readString(source);
        Path unrelated = directory.resolve("other.xml");
        Files.writeString(unrelated, "unchanged");
        Path backup = service.repair(part);
        assertTrue(XmlRepairTool.validateSax(source).valid());
        assertEquals(original, Files.readString(backup));
        assertEquals("unchanged", Files.readString(unrelated));
        try (var files = Files.list(directory)) { assertEquals(3, files.count()); }
    }

    @Test
    void preservesEmojiAcrossStreamingChunkBoundary() throws Exception {
        String original = "<i><d>" + "a".repeat(65529) + "😀" + "b".repeat(65536) + "</d>";
        Files.writeString(source, original);
        Path backup = service.repair(part);
        assertTrue(XmlRepairTool.validateSax(source).valid());
        assertTrue(Files.readString(source).contains(original.substring(3)));
        assertEquals(original, Files.readString(backup));
    }

    @Test
    void invalidRepairLeavesOriginalIntactAndReturnsParserReason() throws Exception {
        Files.writeString(source, "<i><d>unclosed");
        String original = Files.readString(source);
        var error = assertThrows(IOException.class, () -> service.repair(part));
        assertTrue(error.getMessage().contains("无法自动修复"));
        assertEquals(original, Files.readString(source));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @Test
    void activeRecordingCannotBeRepaired() {
        part.setRecording(true);
        assertThrows(IllegalArgumentException.class, () -> service.repair(part));
        verifyNoInteractions(locations);
    }

    @Test
    void unavailableFileReturnsReason() {
        when(locations.resolveCompanionState(17L, ".xml")).thenReturn(
                new PartFileLocationService.CompanionResolution(null, null, source, null, "storage offline"));
        assertTrue(assertThrows(IOException.class, () -> service.repair(part))
                .getMessage().contains("storage offline"));
    }

    @Test
    void changedFileIsNotOverwritten() throws Exception {
        when(locations.resolveCompanionState(17L, ".xml")).thenReturn(available(source))
                .thenAnswer(invocation -> {
                    Files.writeString(source, "new contents written during repair");
                    return available(source);
                });
        assertTrue(assertThrows(IOException.class, () -> service.repair(part)).getMessage().contains("发生变化"));
        assertEquals("new contents written during repair", Files.readString(source));
    }

    @Test
    void changedLocationIsNotOverwritten() throws Exception {
        String original = Files.readString(source);
        when(locations.resolveCompanionState(17L, ".xml")).thenReturn(available(source))
                .thenReturn(available(directory.resolve("moved.xml")));
        assertTrue(assertThrows(IOException.class, () -> service.repair(part)).getMessage().contains("位置已变化"));
        assertEquals(original, Files.readString(source));
    }

    private PartFileLocationService.CompanionResolution available(Path path) {
        return new PartFileLocationService.CompanionResolution(
                PartFileLocationService.CompanionState.AVAILABLE, path, path, null, null);
    }
}
