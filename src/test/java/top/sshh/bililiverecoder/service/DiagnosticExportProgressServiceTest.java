package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.Test;
import top.sshh.bililiverecoder.entity.DiagnosticExportRequest;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticExportProgressServiceTest {

    @Test
    void reportsMonotonicByteAndFileProgressAndCompletes() {
        DiagnosticExportProgressService service = new DiagnosticExportProgressService();
        DiagnosticExportRequest request = new DiagnosticExportRequest();
        DiagnosticExportService.ExportPlan plan = plan(request,
                List.of(new LogArchiveService.LogFile(Path.of("a.log"), null, 0, false, 100, -1)),
                List.of());

        service.register("00000000-0000-4000-8000-000000000001", plan);
        DiagnosticExportProgressService.ProgressReporter reporter = service.reporter("00000000-0000-4000-8000-000000000001");
        reporter.phase("ANALYZING_RELEVANT", "正在分析相关日志", 5);
        try (DiagnosticExportProgressService.FileProgress file = reporter.file(100, "a.log")) {
            file.accept(40);
            Map<String, Object> first = service.status("00000000-0000-4000-8000-000000000001");
            file.accept(60);
            Map<String, Object> second = service.status("00000000-0000-4000-8000-000000000001");
            assertEquals(40L, first.get("processedBytes"));
            assertEquals(100L, second.get("processedBytes"));
            assertTrue((Integer) second.get("percent") >= (Integer) first.get("percent"));
        }
        service.complete("00000000-0000-4000-8000-000000000001");
        Map<String, Object> done = service.status("00000000-0000-4000-8000-000000000001");
        assertEquals("COMPLETED", done.get("state"));
        assertEquals(100, done.get("percent"));
        assertEquals(1, done.get("processedFiles"));
    }

    @Test
    void cancellationIsIdempotentAndStopsReporter() {
        DiagnosticExportProgressService service = new DiagnosticExportProgressService();
        DiagnosticExportService.ExportPlan plan = plan(new DiagnosticExportRequest(), List.of(), List.of());
        String id = "00000000-0000-4000-8000-000000000002";
        service.register(id, plan);

        assertEquals("CANCELLED", service.cancel(id).get("state"));
        assertEquals("CANCELLED", service.cancel(id).get("state"));
        assertThrows(DiagnosticExportProgressService.ExportCancelledException.class,
                () -> service.reporter(id).checkCancelled());
    }

    @Test
    void rejectsInvalidExportId() {
        DiagnosticExportProgressService service = new DiagnosticExportProgressService();
        assertThrows(IllegalArgumentException.class, () -> service.resolveExportId("not-a-uuid"));
        assertTrue(service.resolveExportId(null).length() > 20);
    }

    private static DiagnosticExportService.ExportPlan plan(DiagnosticExportRequest request,
                                                            List<LogArchiveService.LogFile> relevant,
                                                            List<LogArchiveService.LogFile> full) {
        return new DiagnosticExportService.ExportPlan(request, null, List.of(), null, relevant, full,
                new LogArchiveService.Inventory(null, null, 0, Map.of(), List.of(), 14, "512MB"), null, null);
    }
}
