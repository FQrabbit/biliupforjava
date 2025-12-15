package top.sshh.bililiverecoder.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.entity.LogAlert;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@Slf4j
public class LogAnalyzeService {

    private static LogAnalyzeService instance;
    private final List<LogAlert> alerts = new CopyOnWriteArrayList<>();
    private static final int MAX_ALERTS = 100;

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
            addAlert(new LogAlert(type, message, level));
        }
    }

    private void addAlert(LogAlert alert) {
        if (alerts.size() >= MAX_ALERTS) {
            alerts.remove(0);
        }
        alerts.add(alert);
    }

    public List<LogAlert> getAlerts() {
        return new ArrayList<>(alerts);
    }
    
    public void clearAlerts() {
        alerts.clear();
    }
}
