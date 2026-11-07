package top.sshh.bililiverecoder.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sshh.bililiverecoder.service.HistoryVideoCleanupService;

@RestController
@RequestMapping("/history/{historyId}/video-cleanup")
public class HistoryVideoCleanupController {
    private final HistoryVideoCleanupService cleanup;
    public HistoryVideoCleanupController(HistoryVideoCleanupService cleanup) { this.cleanup = cleanup; }
    public record DeleteRequest(String token, String fileId) {}

    @GetMapping("/preview")
    public ResponseEntity<HistoryVideoCleanupService.Preview> preview(@PathVariable Long historyId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(cleanup.preview(historyId));
    }

    @PostMapping("/delete")
    public HistoryVideoCleanupService.FileResult delete(@PathVariable Long historyId, @RequestBody DeleteRequest request) {
        return cleanup.delete(historyId, request.token(), request.fileId());
    }

    @PostMapping("/result")
    public HistoryVideoCleanupService.FileResult result(@PathVariable Long historyId, @RequestBody DeleteRequest request) {
        return cleanup.result(historyId, request.token(), request.fileId());
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public java.util.Map<String, String> conflict(RuntimeException error) {
        return java.util.Map.of("message", error.getMessage() == null ? "清理请求无效，请重新选择" : error.getMessage());
    }
}
