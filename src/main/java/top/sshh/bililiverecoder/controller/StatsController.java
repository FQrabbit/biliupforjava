package top.sshh.bililiverecoder.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import top.sshh.bililiverecoder.service.DatabaseMaintenanceService;
import top.sshh.bililiverecoder.service.RoomLiveEventXmlIssueService;
import top.sshh.bililiverecoder.service.StatsAggregationService;
import top.sshh.bililiverecoder.util.XmlRepairTool;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/stats")
public class StatsController {

    /** 流式 XML 修复上限：保护磁盘和 CPU 不被无界请求耗尽 */
    private static final long MAX_STREAMING_XML_REPAIR_SIZE = 2L * 1024 * 1024 * 1024; // 2GB

    @Autowired
    private StatsAggregationService statsAggregationService;

    @Autowired
    private DatabaseMaintenanceService databaseMaintenanceService;

    @Autowired
    private RoomLiveEventXmlIssueService xmlIssueService;

    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return statsAggregationService.getOverview(from, to);
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return statsAggregationService.getStatsStatus();
    }

    @GetMapping("/task/status")
    public Map<String, Object> taskStatus() {
        return statsAggregationService.getStatsTaskStatus();
    }

    @GetMapping("/rooms")
    public List<Map<String, Object>> rooms(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return statsAggregationService.getRoomSummaries(from, to);
    }

    @GetMapping("/room/{roomId}")
    public Map<String, Object> room(@PathVariable String roomId,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return statsAggregationService.getRoomDetail(roomId, from, to);
    }

    @GetMapping("/room/{roomId}/session/{historyId}/buckets")
    public Object sessionBuckets(@PathVariable String roomId, @PathVariable Long historyId) {
        return statsAggregationService.getSessionBuckets(roomId, historyId);
    }

    @GetMapping("/room/{roomId}/session/{historyId}")
    public Object sessionDetail(@PathVariable String roomId, @PathVariable Long historyId) {
        return statsAggregationService.getSessionDetail(roomId, historyId);
    }

    @PostMapping("/backfill")
    public Map<String, Object> backfill() {
        return statsAggregationService.startBackfillMissingStats();
    }

    @PostMapping("/rebuild")
    public Map<String, Object> rebuild() {
        return statsAggregationService.startRebuildAllStats();
    }

    @PostMapping("/cleanup")
    public Map<String, Object> cleanup() {
        return statsAggregationService.startCleanupStats();
    }

    @PostMapping("/cleanup-event-raw-json")
    public Map<String, Object> cleanupEventRawJson() {
        return statsAggregationService.cleanupEventRawJson();
    }

    @PostMapping("/cleanup-stale-recording-state")
    public Map<String, Object> cleanupStaleRecordingState() {
        return statsAggregationService.cleanupStaleRecordingStates();
    }

    @GetMapping("/xml/issues/summary")
    public Map<String, Object> xmlIssueSummary() {
        return xmlIssueService.summary();
    }

    @GetMapping("/xml/issues")
    public Map<String, Object> xmlIssues(@RequestParam(required = false) String status,
                                         @RequestParam(required = false) String type,
                                         @RequestParam(required = false) String roomId,
                                         @RequestParam(required = false) Long historyId,
                                         @RequestParam(required = false) String keyword,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "25") int size) {
        return xmlIssueService.list(status, type, roomId, historyId, keyword, page, size);
    }

    @PostMapping("/xml/issues/ignore")
    public Map<String, Object> ignoreXmlIssues(@RequestBody(required = false) Map<String, Object> request) {
        try {
            return xmlIssueService.ignore(request == null ? Map.of() : request);
        } catch (IllegalArgumentException e) {
            return invalidXmlIssueRequest(e);
        }
    }

    @PostMapping("/xml/issues/resume")
    public Map<String, Object> resumeXmlIssues(@RequestBody(required = false) Map<String, Object> request) {
        try {
            return xmlIssueService.resume(request == null ? Map.of() : request);
        } catch (IllegalArgumentException e) {
            return invalidXmlIssueRequest(e);
        }
    }

    @PostMapping("/xml/issues/recheck")
    public Map<String, Object> recheckXmlIssues(@RequestBody(required = false) Map<String, Object> request) {
        try {
            return statsAggregationService.startXmlIssueRecheck(requestPartIds(request));
        } catch (IllegalArgumentException e) {
            return invalidXmlIssueRequest(e);
        }
    }

    @GetMapping("/maintenance/status")
    public Map<String, Object> maintenanceStatus() {
        return databaseMaintenanceService.status();
    }

    @PostMapping("/maintenance/compact")
    public Map<String, Object> compactDatabase() {
        return databaseMaintenanceService.compactAsync();
    }

    private Map<String, Object> invalidXmlIssueRequest(IllegalArgumentException e) {
        return Map.of("success", false, "message", e.getMessage());
    }

    private List<Long> requestPartIds(Map<String, Object> request) {
        if (request == null || !(request.get("partIds") instanceof Collection<?> values)) {
            return List.of();
        }
        List<Long> result = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof Number number) {
                result.add(number.longValue());
                continue;
            }
            try {
                result.add(Long.parseLong(String.valueOf(value)));
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    @PostMapping(value = "/xml/repair", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> repairXml(@RequestParam("file") MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "请选择需要修复的 XML 文件"
            ));
        }
        if (file.getSize() > MAX_STREAMING_XML_REPAIR_SIZE) {
            return sizeExceededResponse(file.getSize());
        }
        String originalName = file.getOriginalFilename() == null ? "danmaku.xml" : file.getOriginalFilename();
        return repairXmlStreaming(originalName, new LimitingInputStream(file.getInputStream(), MAX_STREAMING_XML_REPAIR_SIZE));
    }

    @PostMapping(value = "/xml/repair", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<?> repairXmlRaw(HttpServletRequest request,
                                          @RequestHeader(value = "X-File-Name", required = false) String encodedFileName) throws IOException {
        long contentLength = request.getContentLengthLong();
        if (contentLength > MAX_STREAMING_XML_REPAIR_SIZE) {
            return sizeExceededResponse(contentLength);
        }
        String originalName = decodeFileName(encodedFileName);
        return repairXmlStreaming(originalName, new LimitingInputStream(request.getInputStream(), MAX_STREAMING_XML_REPAIR_SIZE));
    }

    private ResponseEntity<?> sizeExceededResponse(long actualSize) {
        long actualMB = actualSize / 1024 / 1024;
        long limitMB = MAX_STREAMING_XML_REPAIR_SIZE / 1024 / 1024;
        return ResponseEntity.status(413).body(Map.of(
                "success", false,
                "message", "XML 文件 " + actualMB + "MB 超过上限 " + limitMB + "MB"
        ));
    }

    private ResponseEntity<?> repairXmlStreaming(String originalName, InputStream in) throws IOException {
        XmlRepairTool.StreamRepairResult result;
        try {
            result = XmlRepairTool.streamRepair(in);
        } catch (StreamSizeExceededException e) {
            return sizeExceededResponse(e.actualBytes);
        }

        Map<String, Object> diagnostic = buildXmlRepairDiagnostic(originalName, result);

        if (!result.after().valid()) {
            Files.deleteIfExists(result.tempFile());
            diagnostic.put("success", false);
            diagnostic.put("message", "暂时无法自动修复：修复后仍不是有效的录播姬 XML");
            return ResponseEntity.badRequest().body(diagnostic);
        }

        String outputName = repairedXmlFileName(originalName);
        String encodedName = URLEncoder.encode(outputName, StandardCharsets.UTF_8).replace("+", "%20");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName);
        headers.set("X-Xml-Repair-Success", "true");
        headers.set("X-Xml-Repair-Before-Valid", String.valueOf(result.before().valid()));
        headers.set("X-Xml-Repair-After-Valid", String.valueOf(result.after().valid()));
        headers.set("X-Xml-Repair-Changed", String.valueOf(result.changed()));
        headers.set("X-Xml-Repair-Actions", String.join(",", result.actions()));
        headers.set("X-Xml-Repair-Danmu", String.valueOf(result.counts().danmu()));
        headers.set("X-Xml-Repair-Gift", String.valueOf(result.counts().gift()));
        headers.set("X-Xml-Repair-Sc", String.valueOf(result.counts().sc()));
        headers.set("X-Xml-Repair-Guard", String.valueOf(result.counts().guard()));
        headers.set("X-Xml-Repair-Message", URLEncoder.encode((String) diagnostic.get("message"), StandardCharsets.UTF_8));
        headers.setContentLength(Files.size(result.tempFile()));

        Path tempFile = result.tempFile();
        StreamingResponseBody stream = outputStream -> {
            try (InputStream fileIn = Files.newInputStream(tempFile)) {
                fileIn.transferTo(outputStream);
            } finally {
                Files.deleteIfExists(tempFile);
            }
        };

        return ResponseEntity.ok().headers(headers).body(stream);
    }

    private String decodeFileName(String encodedFileName) {
        if (encodedFileName == null || encodedFileName.isBlank()) {
            return "danmaku.xml";
        }
        try {
            return URLDecoder.decode(encodedFileName, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "danmaku.xml";
        }
    }

    private Map<String, Object> buildXmlRepairDiagnostic(String originalName, XmlRepairTool.StreamRepairResult repair) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", repair.after().valid());
        result.put("fileName", originalName);
        result.put("beforeValid", repair.before().valid());
        result.put("afterValid", repair.after().valid());
        result.put("changed", repair.changed());
        result.put("actions", repair.actions());
        result.put("danmu", repair.counts().danmu());
        result.put("gift", repair.counts().gift());
        result.put("sc", repair.counts().sc());
        result.put("guard", repair.counts().guard());
        if (!repair.after().valid()) {
            result.put("error", repair.after().message());
            result.put("message", "修复失败，文件可能不是简单截断，或存在更复杂的 XML 结构损坏");
        } else if (repair.changed()) {
            result.put("message", "修复成功，已生成可下载的修复版 XML");
        } else {
            result.put("message", repair.before().valid() ? "文件本身已经是有效 XML，已原样提供下载" : "文件无需改动但通过了修复后校验");
        }
        return result;
    }

    private String repairedXmlFileName(String originalName) {
        String safeName = originalName.replace("\\", "_").replace("/", "_");
        String lower = safeName.toLowerCase();
        if (lower.endsWith(".xml")) {
            return safeName.substring(0, safeName.length() - 4) + ".repaired.xml";
        }
        return safeName + ".repaired.xml";
    }

    /**
     * Wraps an InputStream and throws {@link StreamSizeExceededException}
     * when the total bytes read exceeds {@code maxBytes}.
     */
    private static final class LimitingInputStream extends FilterInputStream {
        private final long maxBytes;
        private long count;

        LimitingInputStream(InputStream in, long maxBytes) {
            super(in);
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1) {
                count++;
                checkLimit();
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                count += n;
                checkLimit();
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(n);
            count += skipped;
            checkLimit();
            return skipped;
        }

        private void checkLimit() throws StreamSizeExceededException {
            if (count > maxBytes) {
                throw new StreamSizeExceededException(maxBytes, count);
            }
        }
    }

    private static final class StreamSizeExceededException extends IOException {
        final long actualBytes;
        StreamSizeExceededException(long maxBytes, long actualBytes) {
            super("stream exceeds configured maximum of " + maxBytes + " bytes (actual: " + actualBytes + ")");
            this.actualBytes = actualBytes;
        }
    }
}
