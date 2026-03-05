package top.sshh.bililiverecoder.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.sshh.bililiverecoder.entity.LogAlert;
import top.sshh.bililiverecoder.service.LogAnalyzeService;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/log")
public class LogController {

    @Autowired
    private LogAnalyzeService logAnalyzeService;

    @Value("${logging.file.path:${record.work-path}/log/}")
    private String logPath;

    @Value("${logging.file.name:spring.log}")
    private String logName;

    @GetMapping("/alerts")
    public List<LogAlert> getAlerts() {
        return logAnalyzeService.getAlerts();
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/alerts")
    public java.util.Map<String, Object> clearAlerts() {
        logAnalyzeService.clearAlerts();
        return java.util.Collections.singletonMap("success", true);
    }

    @GetMapping("/history")
    public List<String> getHistoryLogs(@RequestParam(defaultValue = "2000") int lines) {
        // 如果 logPath 包含未解析的占位符，Spring 通常会自动处理
        // 如果是 literal "${record.work-path}/log/"，则需要确保属性已正确加载
        
        File logDir = new File(logPath);
        File logFile = new File(logDir, logName);
        
        if (!logFile.exists()) {
            // 尝试在路径中查找默认的 spring.log
            logFile = new File(logDir, "spring.log");
            if (!logFile.exists()) {
                return Collections.singletonList("Log file not found at " + logFile.getAbsolutePath());
            }
        }

        return readLastLinesSafe(logFile, lines);
    }

    @GetMapping("/context")
    public List<String> getContextLogs(@RequestParam String keyword, @RequestParam(defaultValue = "50") int range) {
        File logDir = new File(logPath);
        File logFile = new File(logDir, logName);
        if (!logFile.exists()) {
            logFile = new File(logDir, "spring.log");
            if (!logFile.exists()) {
                return Collections.singletonList("日志文件未找到");
            }
        }

        // 读取所有行（处理 UTF-8 编码）
        List<String> lines = new ArrayList<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(logFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } catch (Exception e) {
            return Collections.singletonList("读取文件错误: " + e.getMessage());
        }

        // 查找关键字最后一次出现的位置
        int index = -1;
        // 从后往前搜，获取最新的日志条目
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i).contains(keyword)) {
                index = i;
                break;
            }
        }

        if (index == -1) {
            return Collections.singletonList("当前日志文件中未找到该条目");
        }

        int start = Math.max(0, index - range);
        int end = Math.min(lines.size(), index + range + 1);

        return lines.subList(start, end);
    }

    private List<String> readLastLinesSafe(File file, int numLines) {
         try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
             long len = raf.length();
             // Read last 1MB or full file if smaller
             long start = Math.max(0, len - 1024 * 1024); 
             raf.seek(start);
             byte[] bytes = new byte[(int) (len - start)];
             raf.readFully(bytes);
             String content = new String(bytes, StandardCharsets.UTF_8);
             String[] allLines = content.split("\r?\n");
             
             List<String> result = new ArrayList<>();
             int startIdx = Math.max(0, allLines.length - numLines);
             for(int i=startIdx; i<allLines.length; i++) {
                 result.add(allLines[i]);
             }
             return result;
         } catch (Exception e) {
             return Collections.singletonList("Error reading log file: " + e.getMessage());
         }
    }
}
