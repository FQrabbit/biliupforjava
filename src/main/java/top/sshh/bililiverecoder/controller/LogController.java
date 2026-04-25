package top.sshh.bililiverecoder.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.sshh.bililiverecoder.entity.LogAlert;
import top.sshh.bililiverecoder.service.LogAnalyzeService;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

@RestController
@RequestMapping("/log")
public class LogController {

    private static final int MAX_HISTORY_LINES = 10_000;
    private static final int MAX_CONTEXT_RANGE = 500;
    private static final Pattern ARCHIVE_DATE_PATTERN = Pattern.compile("\\.(\\d{4}-\\d{2}-\\d{2})\\.\\d+(?:\\.gz)?$");
    private static final Pattern ARCHIVE_INDEX_PATTERN = Pattern.compile("\\.\\d{4}-\\d{2}-\\d{2}\\.(\\d+)(?:\\.gz)?$");

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

    @DeleteMapping("/alerts")
    public java.util.Map<String, Object> clearAlerts() {
        logAnalyzeService.clearAlerts();
        return java.util.Collections.singletonMap("success", true);
    }

    @GetMapping("/history")
    public List<String> getHistoryLogs(@RequestParam(defaultValue = "2000") int lines) {
        int safeLines = clamp(lines, 1, MAX_HISTORY_LINES);
        File logDir = new File(logPath);
        List<File> files = resolveReadableLogFiles(logDir);

        if (files.isEmpty()) {
            return Collections.singletonList("Log file not found at " + new File(logDir, logName).getAbsolutePath());
        }

        return readLastLinesAcrossFiles(files, safeLines);
    }

    @GetMapping("/context")
    public List<String> getContextLogs(@RequestParam String keyword, @RequestParam(defaultValue = "50") int range) {
        File logDir = new File(logPath);
        List<File> files = resolveReadableLogFiles(logDir);
        if (files.isEmpty()) {
            return Collections.singletonList("日志文件未找到");
        }

        if (keyword == null || keyword.isBlank()) {
            return Collections.singletonList("搜索关键字为空");
        }

        List<String> lines = new ArrayList<>();
        for (File file : files) {
            try {
                lines.addAll(readAllLines(file));
            } catch (IOException e) {
                return Collections.singletonList("读取日志文件失败: " + file.getName() + " - " + e.getMessage());
            }
        }

        int index = -1;
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i).contains(keyword)) {
                index = i;
                break;
            }
        }

        if (index == -1) {
            return Collections.singletonList("最近日志文件中未找到该条目");
        }

        int safeRange = clamp(range, 0, MAX_CONTEXT_RANGE);
        int start = Math.max(0, index - safeRange);
        int end = Math.min(lines.size(), index + safeRange + 1);

        return lines.subList(start, end);
    }

    private List<File> resolveReadableLogFiles(File logDir) {
        if (logDir == null || !logDir.isDirectory()) {
            return Collections.emptyList();
        }

        String activeLogName = resolveActiveLogName(logDir);
        Pattern archivePattern = buildArchivePattern(activeLogName);

        List<File> archiveFiles = new ArrayList<>();
        File[] candidates = logDir.listFiles();
        if (candidates != null) {
            for (File candidate : candidates) {
                if (candidate.isFile() && archivePattern.matcher(candidate.getName()).matches()) {
                    archiveFiles.add(candidate);
                }
            }
        }

        archiveFiles.sort(Comparator
                .comparing(LogController::archiveDate)
                .thenComparingInt(LogController::archiveIndex)
                .thenComparing(File::getName));

        List<File> files = new ArrayList<>(archiveFiles);
        File activeLogFile = new File(logDir, activeLogName);
        if (activeLogFile.isFile()) {
            files.add(activeLogFile);
        }
        return files;
    }

    private String resolveActiveLogName(File logDir) {
        File configuredName = new File(logName);
        File configuredLogFile = configuredName.isAbsolute() ? configuredName : new File(logDir, configuredName.getName());
        if (configuredLogFile.isFile()) {
            return configuredLogFile.getName();
        }

        File defaultLogFile = new File(logDir, "spring.log");
        if (defaultLogFile.isFile()) {
            return "spring.log";
        }

        return configuredName.getName();
    }

    private static Pattern buildArchivePattern(String activeLogName) {
        return Pattern.compile(Pattern.quote(activeLogName) + "\\.(\\d{4}-\\d{2}-\\d{2})\\.(\\d+)(?:\\.gz)?");
    }

    private static String archiveDate(File file) {
        Matcher matcher = ARCHIVE_DATE_PATTERN.matcher(file.getName());
        return matcher.find() ? matcher.group(1) : "";
    }

    private static int archiveIndex(File file) {
        Matcher matcher = ARCHIVE_INDEX_PATTERN.matcher(file.getName());
        if (!matcher.find()) {
            return 0;
        }

        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private List<String> readLastLinesAcrossFiles(List<File> files, int numLines) {
        Deque<String> result = new ArrayDeque<>(numLines);

        for (int i = files.size() - 1; i >= 0 && result.size() < numLines; i--) {
            File file = files.get(i);
            List<String> lines;
            try {
                lines = readAllLines(file);
            } catch (IOException e) {
                return Collections.singletonList("Error reading log file: " + file.getName() + " - " + e.getMessage());
            }

            for (int j = lines.size() - 1; j >= 0 && result.size() < numLines; j--) {
                result.addFirst(lines.get(j));
            }
        }

        return new ArrayList<>(result);
    }

    private static List<String> readAllLines(File file) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(openLogInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static InputStream openLogInputStream(File file) throws IOException {
        InputStream inputStream = new FileInputStream(file);
        if (file.getName().endsWith(".gz")) {
            return new GZIPInputStream(inputStream);
        }
        return inputStream;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
