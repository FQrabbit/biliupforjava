package top.sshh.bililiverecoder.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import top.sshh.bililiverecoder.service.DatabaseMaintenanceService;
import top.sshh.bililiverecoder.service.StatsAggregationService;
import top.sshh.bililiverecoder.util.XmlRepairTool;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/stats")
public class StatsController {

    @Autowired
    private StatsAggregationService statsAggregationService;

    @Autowired
    private DatabaseMaintenanceService databaseMaintenanceService;

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

    @GetMapping("/maintenance/status")
    public Map<String, Object> maintenanceStatus() {
        return databaseMaintenanceService.status();
    }

    @PostMapping("/maintenance/compact")
    public Map<String, Object> compactDatabase() {
        return databaseMaintenanceService.compactAsync();
    }

    @PostMapping(value = "/xml/repair", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> repairXml(@RequestParam("file") MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "请选择需要修复的 XML 文件"
            ));
        }
        String originalName = file.getOriginalFilename() == null ? "danmaku.xml" : file.getOriginalFilename();
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        XmlRepairTool.ContentRepairResult repair = XmlRepairTool.repairContent(content);
        Map<String, Object> diagnostic = buildXmlRepairDiagnostic(originalName, repair);
        if (!repair.after().valid()) {
            diagnostic.put("success", false);
            diagnostic.put("message", "暂时无法自动修复：修复后仍不是有效的录播姬 XML");
            return ResponseEntity.badRequest().body(diagnostic);
        }

        String outputName = repairedXmlFileName(originalName);
        String encodedName = URLEncoder.encode(outputName, StandardCharsets.UTF_8).replace("+", "%20");
        byte[] bytes = repair.repairedText().getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName);
        headers.set("X-Xml-Repair-Success", "true");
        headers.set("X-Xml-Repair-Before-Valid", String.valueOf(repair.before().valid()));
        headers.set("X-Xml-Repair-After-Valid", String.valueOf(repair.after().valid()));
        headers.set("X-Xml-Repair-Changed", String.valueOf(repair.changed()));
        headers.set("X-Xml-Repair-Actions", String.join(",", repair.actions()));
        headers.set("X-Xml-Repair-Danmu", String.valueOf(repair.counts().danmu()));
        headers.set("X-Xml-Repair-Gift", String.valueOf(repair.counts().gift()));
        headers.set("X-Xml-Repair-Sc", String.valueOf(repair.counts().sc()));
        headers.set("X-Xml-Repair-Guard", String.valueOf(repair.counts().guard()));
        headers.set("X-Xml-Repair-Message", URLEncoder.encode((String) diagnostic.get("message"), StandardCharsets.UTF_8));
        headers.setContentLength(bytes.length);
        return ResponseEntity.ok().headers(headers).body(bytes);
    }

    private Map<String, Object> buildXmlRepairDiagnostic(String originalName, XmlRepairTool.ContentRepairResult repair) {
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
}
