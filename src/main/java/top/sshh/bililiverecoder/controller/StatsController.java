package top.sshh.bililiverecoder.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import top.sshh.bililiverecoder.service.DatabaseMaintenanceService;
import top.sshh.bililiverecoder.service.StatsAggregationService;

import java.time.LocalDate;
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
}
