package top.sshh.bililiverecoder.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import top.sshh.bililiverecoder.entity.DiagnosticExportRequest;
import top.sshh.bililiverecoder.service.DiagnosticExportService;
import top.sshh.bililiverecoder.service.DiagnosticExportProgressService;
import top.sshh.bililiverecoder.service.LogArchiveService;

import java.io.IOException;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/diagnostics")
public class DiagnosticController {

    private final DiagnosticExportService diagnosticExportService;
    private final LogArchiveService logArchiveService;
    private final DiagnosticExportProgressService progressService;

    public DiagnosticController(DiagnosticExportService diagnosticExportService, LogArchiveService logArchiveService,
                                DiagnosticExportProgressService progressService) {
        this.diagnosticExportService = diagnosticExportService;
        this.logArchiveService = logArchiveService;
        this.progressService = progressService;
    }

    @GetMapping("/capabilities")
    public LogArchiveService.Inventory capabilities() {
        return logArchiveService.inventory();
    }

    @GetMapping("/histories")
    public java.util.List<Map<String, Object>> histories(@RequestParam(defaultValue = "") String query,
                                                           @RequestParam(defaultValue = "20") int limit) {
        return diagnosticExportService.searchHistories(query, limit);
    }

    @PostMapping("/export")
    public ResponseEntity<StreamingResponseBody> export(
            @RequestBody DiagnosticExportRequest request,
            @RequestHeader(value = "X-Diagnostic-Export-Id", required = false) String requestedExportId) {
        DiagnosticExportService.ExportPlan plan = diagnosticExportService.prepare(request);
        if (!diagnosticExportService.tryAcquire()) {
            throw new ExportBusyException("已有诊断包正在生成，请稍后重试");
        }
        String exportId = progressService.resolveExportId(requestedExportId);
        try {
            progressService.register(exportId, plan);
        } catch (RuntimeException e) {
            diagnosticExportService.release();
            throw e;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/zip"));
        headers.setCacheControl(CacheControl.noStore());
        headers.set("X-Diagnostic-Export-Id", exportId);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(diagnosticExportService.filename(plan))
                .build());
        StreamingResponseBody body = outputStream -> {
            try {
                diagnosticExportService.write(plan, outputStream, progressService.reporter(exportId));
                progressService.complete(exportId);
            } catch (DiagnosticExportProgressService.ExportCancelledException e) {
                progressService.cancel(exportId);
                throw e;
            } catch (IOException | RuntimeException e) {
                progressService.fail(exportId, e);
                throw e;
            } finally {
                diagnosticExportService.release();
            }
        };
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    @GetMapping("/exports/{exportId}/progress")
    public ResponseEntity<Map<String, Object>> progress(@PathVariable("exportId") String exportId) {
        Map<String, Object> status = progressService.status(exportId);
        if (status == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .cacheControl(CacheControl.noStore())
                    .body(Map.of("message", "诊断导出任务不存在或已过期"));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(status);
    }

    @PostMapping("/exports/{exportId}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable("exportId") String exportId) {
        Map<String, Object> status = progressService.cancel(exportId);
        if (status == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .cacheControl(CacheControl.noStore())
                    .body(Map.of("message", "诊断导出任务不存在或已过期"));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(status);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NoSuchElementException e) {
        return errorResponse(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return errorResponse(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(ExportBusyException.class)
    public ResponseEntity<Map<String, String>> handleExportBusy(ExportBusyException e) {
        return errorResponse(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    private ResponseEntity<Map<String, String>> errorResponse(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("message", message));
    }

    private static final class ExportBusyException extends RuntimeException {
        private ExportBusyException(String message) {
            super(message);
        }
    }
}
