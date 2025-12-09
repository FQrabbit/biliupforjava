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

    @GetMapping("/history")
    public List<String> getHistoryLogs(@RequestParam(defaultValue = "2000") int lines) {
        // Handle variable resolution if needed, but @Value usually handles it if properties are set.
        // However, ${record.work-path} might need to be resolved by Spring.
        // If logPath comes in as literal "${record.work-path}/log/", we have a problem.
        // Spring @Value usually resolves nested placeholders.
        
        File logDir = new File(logPath);
        File logFile = new File(logDir, logName);
        
        if (!logFile.exists()) {
            // Try to find spring.log in the path if name is not explicitly set to something else
            logFile = new File(logDir, "spring.log");
            if (!logFile.exists()) {
                return Collections.singletonList("Log file not found at " + logFile.getAbsolutePath());
            }
        }

        return readLastLinesSafe(logFile, lines);
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
