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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import top.sshh.bililiverecoder.entity.DiagnosticExportRequest;
import top.sshh.bililiverecoder.service.DiagnosticExportService;
import top.sshh.bililiverecoder.service.LogArchiveService;

import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/diagnostics")
public class DiagnosticController {

    private final DiagnosticExportService diagnosticExportService;
    private final LogArchiveService logArchiveService;

    public DiagnosticController(DiagnosticExportService diagnosticExportService, LogArchiveService logArchiveService) {
        this.diagnosticExportService = diagnosticExportService;
        this.logArchiveService = logArchiveService;
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
    public ResponseEntity<StreamingResponseBody> export(@RequestBody DiagnosticExportRequest request) {
        DiagnosticExportService.ExportPlan plan = diagnosticExportService.prepare(request);
        if (!diagnosticExportService.tryAcquire()) {
            throw new ExportBusyException("已有诊断包正在生成，请稍后重试");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/zip"));
        headers.setCacheControl(CacheControl.noStore());
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(diagnosticExportService.filename(plan))
                .build());
        StreamingResponseBody body = outputStream -> {
            try {
                diagnosticExportService.write(plan, outputStream);
            } finally {
                diagnosticExportService.release();
            }
        };
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
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
