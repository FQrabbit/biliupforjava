package top.sshh.bililiverecoder.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.entity.LogAlert;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class LogAnalyzeService {

    private static LogAnalyzeService instance;
    private final List<LogAlert> alerts = new CopyOnWriteArrayList<>();
    private static final int MAX_ALERTS = 100;
    private static final Pattern KV_PATTERN = Pattern.compile("(?:^|[|\\s])([A-Za-z][A-Za-z0-9.]*)=([^|]*)");

    @PostConstruct
    public void init() {
        instance = this;
    }

    public static LogAnalyzeService getInstance() {
        return instance;
    }

    public void processLog(String message, String level) {
        if (message == null) return;

        // 忽略登录失败的异常
        if (message.contains("[AUTH_FAILED]") || message.contains("登录失败")) {
            return;
        }

        String type = null;
        // 增加空格或特定前缀判断，避免匹配到端口号 (如 44122)
        if (message.contains("[RISK_CONTROL]") || message.contains(" 412 ") || message.contains("code: 412")) {
            type = "RISK_CONTROL";
        } else if (message.contains("验证码")) {
            type = "CAPTCHA_REQUIRED";
        } else if ("WARN".equalsIgnoreCase(level)) {
            type = "WARN";
        } else if ("ERROR".equalsIgnoreCase(level)) {
            type = "ERROR";
        }

        if (type != null) {
            Map<String, String> fields = parseFields(message);
            String event = fields.get("event");
            String retryCategory = fields.getOrDefault("retryCategory", fields.get("category"));
            String host = fields.getOrDefault("host", fields.get("uploadHost"));
            String fingerprint = buildFingerprint(type, message, event, retryCategory, host);
            addAlert(new LogAlert(type, message, level, fingerprint, event, retryCategory, host));
        }
    }

    private synchronized void addAlert(LogAlert newAlert) {
        // 检查是否存在重复日志
        for (LogAlert alert : alerts) {
            if (java.util.Objects.equals(alert.getFingerprint(), newAlert.getFingerprint())) {
                alerts.remove(alert); // 移除旧日志
                if ("ERROR".equalsIgnoreCase(newAlert.getLevel())
                        && !"ERROR".equalsIgnoreCase(alert.getLevel())) {
                    alert.setLevel(newAlert.getLevel());
                    alert.setType(newAlert.getType());
                }
                alert.setMessage(newAlert.getMessage());
                alert.setEvent(newAlert.getEvent());
                alert.setRetryCategory(newAlert.getRetryCategory());
                alert.setHost(newAlert.getHost());
                alert.incrementCount(); // 更新计数和时间
                alerts.add(alert); // 重新添加到末尾（表示最新）
                return;
            }
        }

        if (alerts.size() >= MAX_ALERTS) {
            alerts.remove(0);
        }
        alerts.add(newAlert);
    }

    public List<LogAlert> getAlerts() {
        return new ArrayList<>(alerts);
    }
    
    public void clearAlerts() {
        alerts.clear();
    }

    private static Map<String, String> parseFields(String message) {
        Map<String, String> fields = new LinkedHashMap<>();
        Matcher matcher = KV_PATTERN.matcher(message);
        while (matcher.find()) {
            fields.put(matcher.group(1), matcher.group(2).trim());
        }
        return fields;
    }

    private static String buildFingerprint(String type, String message, String event,
                                           String retryCategory, String host) {
        if (event != null && !event.isBlank()) {
            return String.join("|", event,
                    valueOrUnknown(retryCategory), valueOrUnknown(host));
        }
        return type + "|" + normalizeMessage(message);
    }

    private static String normalizeMessage(String message) {
        return message.replaceAll("\\b(roomId|historyId|partId|chunkIndex|chunkRetryCount|globalFailCount|backoffMs|timestamp)=([^| ]+)", "$1=?")
                .replaceAll("\\s+", " ").trim();
    }

    private static String valueOrUnknown(String value) {
        return value == null || value.isBlank() ? "?" : value;
    }
}
