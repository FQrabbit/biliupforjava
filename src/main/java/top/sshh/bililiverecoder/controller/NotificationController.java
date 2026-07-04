package top.sshh.bililiverecoder.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.sshh.bililiverecoder.entity.NotificationChannel;
import top.sshh.bililiverecoder.entity.NotificationRule;
import top.sshh.bililiverecoder.notification.NotificationLegacyMigrationService;
import top.sshh.bililiverecoder.notification.NotificationSendResult;
import top.sshh.bililiverecoder.notification.NotificationService;
import top.sshh.bililiverecoder.service.SystemConfigService;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/notification")
public class NotificationController {

    private final NotificationService notificationService;
    private final NotificationLegacyMigrationService legacyMigrationService;
    private final SystemConfigService systemConfigService;

    public NotificationController(NotificationService notificationService,
                                  NotificationLegacyMigrationService legacyMigrationService,
                                  SystemConfigService systemConfigService) {
        this.notificationService = notificationService;
        this.legacyMigrationService = legacyMigrationService;
        this.systemConfigService = systemConfigService;
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        return notificationService.configOverview();
    }

    @PostMapping("/config/enabled")
    public Map<String, Object> updateEnabled(@RequestBody Map<String, Object> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled")) || "true".equalsIgnoreCase(String.valueOf(body.get("enabled")));
        systemConfigService.updateConfig(SystemConfigService.KEY_NOTIFICATION_ENABLED, String.valueOf(enabled));
        return Map.of("success", true, "enabled", enabled);
    }

    @PostMapping("/channels/save")
    public NotificationChannel saveChannel(@RequestBody NotificationChannel channel) {
        return notificationService.saveChannel(channel);
    }

    @PostMapping("/rules/save")
    public NotificationRule saveRule(@RequestBody NotificationRule rule) {
        return notificationService.saveRule(rule);
    }

    @PostMapping("/rules/delete/{id}")
    public Map<String, Object> deleteRule(@PathVariable Long id) {
        notificationService.deleteRule(id);
        return Map.of("success", true);
    }

    @PostMapping("/test-send")
    public Map<String, Object> testSend(@RequestBody Map<String, Object> body) {
        Long channelId = Long.valueOf(String.valueOf(body.get("channelId")));
        NotificationSendResult result = notificationService.sendTest(channelId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", result.success());
        response.put("message", result.success() ? "测试通知已发送" : result.errorMessage());
        return response;
    }

    @GetMapping("/legacy-migration/status")
    public Map<String, Object> legacyStatus(@RequestParam(name = "revealSecrets", defaultValue = "false") boolean revealSecrets) {
        return legacyMigrationService.status(revealSecrets);
    }

    @PostMapping("/legacy-migration/apply")
    public Map<String, Object> applyLegacyMigration() {
        return legacyMigrationService.apply();
    }

    @PostMapping("/legacy-migration/discard")
    public Map<String, Object> discardLegacyMigration() {
        return legacyMigrationService.discard();
    }
}
