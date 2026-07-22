package top.sshh.bililiverecoder.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.sshh.bililiverecoder.entity.LogAlert;
import top.sshh.bililiverecoder.service.LogAnalyzeService;
import top.sshh.bililiverecoder.service.LogArchiveService;
import top.sshh.bililiverecoder.service.LogWebSocketTicketService;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/log")
public class LogController {

    private static final int MAX_HISTORY_LINES = 10_000;
    private static final int MAX_CONTEXT_RANGE = 500;

    private final LogAnalyzeService logAnalyzeService;
    private final LogArchiveService logArchiveService;
    private final LogWebSocketTicketService ticketService;

    public LogController(LogAnalyzeService logAnalyzeService, LogArchiveService logArchiveService,
                         LogWebSocketTicketService ticketService) {
        this.logAnalyzeService = logAnalyzeService;
        this.logArchiveService = logArchiveService;
        this.ticketService = ticketService;
    }

    @GetMapping("/alerts")
    public List<LogAlert> getAlerts() {
        return logAnalyzeService.getAlerts();
    }

    @DeleteMapping("/alerts")
    public Map<String, Object> clearAlerts() {
        logAnalyzeService.clearAlerts();
        return Collections.singletonMap("success", true);
    }

    @GetMapping("/history")
    public List<String> getHistoryLogs(@RequestParam(defaultValue = "2000") int lines) {
        try {
            List<String> result = logArchiveService.tailLines(clamp(lines, 1, MAX_HISTORY_LINES));
            return result.isEmpty() ? Collections.singletonList("日志文件未找到") : result;
        } catch (IOException e) {
            return Collections.singletonList("读取日志文件失败: " + e.getMessage());
        }
    }

    @GetMapping("/context")
    public List<String> getContextLogs(@RequestParam String keyword, @RequestParam(defaultValue = "50") int range) {
        if (keyword == null || keyword.isBlank()) {
            return Collections.singletonList("搜索关键字为空");
        }
        try {
            List<String> result = logArchiveService.contextLines(keyword, clamp(range, 0, MAX_CONTEXT_RANGE));
            return result.isEmpty() ? Collections.singletonList("最近日志文件中未找到该条目") : result;
        } catch (IOException e) {
            return Collections.singletonList("读取日志文件失败: " + e.getMessage());
        }
    }

    @PostMapping("/ws-ticket")
    public LogWebSocketTicketService.Ticket issueWebSocketTicket() {
        return ticketService.issue();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
