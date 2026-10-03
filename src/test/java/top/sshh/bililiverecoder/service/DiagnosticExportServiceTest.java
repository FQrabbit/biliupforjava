package top.sshh.bililiverecoder.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.bililiverecoder.entity.DiagnosticExportRequest;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.repo.BiliUserRepository;
import top.sshh.bililiverecoder.repo.NotificationChannelRepository;
import top.sshh.bililiverecoder.repo.NotificationRuleRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.repo.SystemConfigRepository;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DiagnosticExportServiceTest {
    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final RecordHistoryRepository histories = mock(RecordHistoryRepository.class);
    private DiagnosticExportService service;

    @BeforeEach
    void setUp() {
        LogArchiveService archive = new LogArchiveService();
        ReflectionTestUtils.setField(archive, "logPath", tempDir.toString());
        ReflectionTestUtils.setField(archive, "logName", "spring.log");
        service = new DiagnosticExportService(archive, histories, mock(RecordHistoryPartRepository.class),
                mock(RecordRoomRepository.class), mock(BiliUserRepository.class),
                mock(SystemConfigRepository.class), mock(NotificationChannelRepository.class),
                mock(NotificationRuleRepository.class), mapper,
                new MockEnvironment().withProperty("record.password", "test-secret-password"));
    }

    @Test
    void globalDayExportIncludesErrorsStacksAndCompressedWarnings() throws Exception {
        writeArchive(LocalDate.now().minusDays(1), log("ERROR", "outside-selected-day"));
        writeArchive(LocalDate.now(), log("WARN", "compressed-warning"));
        String stack = "java.lang.IllegalStateException: restore failed\n"
                + "\tat example.Restore.run(Restore.java:195)\n"
                + "Caused by: jakarta.persistence.TransactionRequiredException: missing transaction\n";
        Files.writeString(tempDir.resolve("spring.log"), log("INFO", "before-error")
                + log("ERROR", "restore-error password=test-secret-password") + stack
                + log("INFO", "after-error"));

        ExportedPackage exported = export(request());
        String relevant = exported.entries().get("logs/relevant.log");

        assertEquals(1, exported.summary().path("errorRecords").asInt());
        assertEquals(1, exported.summary().path("warningRecords").asInt());
        assertEquals(4, exported.summary().path("relevantRecords").asInt());
        assertTrue(relevant.contains("compressed-warning"));
        assertTrue(relevant.contains("before-error"));
        assertTrue(relevant.contains("after-error"));
        assertTrue(relevant.contains(stack));
        assertFalse(relevant.contains("outside-selected-day"));
        assertFalse(relevant.contains("test-secret-password"));
        assertTrue(relevant.contains("password=[REDACTED]"));
        assertFalse(exported.entries().containsKey("logs/full-window.log"));
        assertTrue(exported.summary().path("warnings").isEmpty());
    }

    @Test
    void overlappingErrorContextIsBoundedAndWrittenOnlyOnce() throws Exception {
        StringBuilder content = new StringBuilder();
        appendInfo(content, "before-", 40);
        content.append(log("ERROR", "error-marker"));
        content.append(log("WARN", "warning-marker"));
        appendInfo(content, "after-", 40);
        Files.writeString(tempDir.resolve("spring.log"), content);

        ExportedPackage exported = export(request());
        String relevant = exported.entries().get("logs/relevant.log");

        assertEquals(1, exported.summary().path("errorRecords").asInt());
        assertEquals(1, exported.summary().path("warningRecords").asInt());
        assertEquals(62, exported.summary().path("relevantRecords").asInt());
        assertEquals(62, relevant.lines().count());
        assertFalse(relevant.contains("before-9\n"));
        assertTrue(relevant.contains("before-10\n"));
        assertTrue(relevant.contains("after-29\n"));
        assertFalse(relevant.contains("after-30\n"));
    }

    @Test
    void occurrenceTimeCanSelectInfoLogsWithoutErrors() throws Exception {
        Files.writeString(tempDir.resolve("spring.log"), log("INFO", "occurrence-marker"));
        DiagnosticExportRequest request = request();
        request.setOccurredAt(LocalDate.now().atTime(7, 51));
        request.setIncludeFullLogs(true);

        ExportedPackage exported = export(request);

        assertEquals(0, exported.summary().path("errorRecords").asInt());
        assertEquals(0, exported.summary().path("warningRecords").asInt());
        assertEquals(1, exported.summary().path("relevantRecords").asInt());
        assertEquals(log("INFO", "occurrence-marker"), exported.entries().get("logs/relevant.log"));
        assertEquals(exported.entries().get("logs/relevant.log"), exported.entries().get("logs/full-window.log"));
    }

    @Test
    void historyExportKeepsMatchingInfoAndExcludesDistantUnrelatedRecords() throws Exception {
        RecordHistory history = new RecordHistory();
        history.setId(3636L);
        history.setStartTime(LocalDate.now().atStartOfDay());
        history.setEndTime(LocalDateTime.now());
        when(histories.findById(history.getId())).thenReturn(Optional.of(history));
        StringBuilder content = new StringBuilder();
        appendInfo(content, "unrelated-before-", 40);
        content.append(log("INFO", "historyId=3636 | target-marker"));
        appendInfo(content, "unrelated-after-", 40);
        Files.writeString(tempDir.resolve("spring.log"), content);
        DiagnosticExportRequest request = request();
        request.setMode(DiagnosticExportRequest.Mode.HISTORY);
        request.setHistoryId(history.getId());

        ExportedPackage exported = export(request);
        String relevant = exported.entries().get("logs/relevant.log");

        assertEquals(61, exported.summary().path("relevantRecords").asInt());
        assertTrue(relevant.contains("target-marker"));
        assertFalse(relevant.contains("unrelated-before-9\n"));
        assertFalse(relevant.contains("unrelated-after-30\n"));
    }

    @Test
    void providedRealLogSampleProducesMatchingErrorAndWarningCounts() throws Exception {
        String sample = System.getProperty("diagnostics.log.sample");
        assumeTrue(sample != null);
        String content = Files.readString(Path.of(sample));
        long expectedErrors = content.lines().filter(line -> line.contains(" | ERROR | ")).count();
        long expectedWarnings = content.lines().filter(line -> line.contains(" | WARN  | ")).count();
        assertTrue(expectedErrors > 0, "真实样本必须包含错误日志");
        Files.writeString(tempDir.resolve("spring.log"), content);

        ExportedPackage exported = export(request());

        assertEquals(expectedErrors, exported.summary().path("errorRecords").asLong());
        assertEquals(expectedWarnings, exported.summary().path("warningRecords").asLong());
        assertFalse(exported.entries().get("logs/relevant.log").isBlank());
        assertTrue(exported.entries().get("logs/relevant.log").contains("TransactionRequiredException"));
    }

    private DiagnosticExportRequest request() {
        DiagnosticExportRequest request = new DiagnosticExportRequest();
        request.setDays(1);
        request.setIncludeRoomConfig(false);
        request.setIncludeSystemConfig(false);
        return request;
    }

    private ExportedPackage export(DiagnosticExportRequest request) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.write(service.prepare(request), output);
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(output.toByteArray()), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return new ExportedPackage(entries, mapper.readTree(entries.get("summary.json")));
    }

    private void writeArchive(LocalDate date, String content) throws Exception {
        Path path = tempDir.resolve("spring.log." + date + ".0.gz");
        try (GZIPOutputStream output = new GZIPOutputStream(Files.newOutputStream(path))) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void appendInfo(StringBuilder content, String prefix, int count) {
        for (int i = 0; i < count; i++) content.append(log("INFO", prefix + i));
    }

    private String log(String level, String message) {
        String time = LocalDate.now().atTime(7, 51).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
        return time + " | " + String.format("%-5s", level) + " | test-thread | example.Service | " + message + "\n";
    }

    private record ExportedPackage(Map<String, String> entries, JsonNode summary) { }
}
