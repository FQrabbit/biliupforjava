package top.sshh.bililiverecoder.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.entity.SystemConfig;
import top.sshh.bililiverecoder.repo.SystemConfigRepository;
import top.sshh.bililiverecoder.util.LogKvs;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
public class SystemConfigService {

    public static final String KEY_API_RATE_LIMIT = "bili.limit.api-qps";
    public static final String KEY_UPLOAD_SPEED_LIMIT = "bili.limit.upload-mb";

    @Autowired
    private SystemConfigRepository systemConfigRepository;

    @Autowired
    private RateLimiterService rateLimiterService;

    @PostConstruct
    public void init() {
        log.info("[BLR] {}", LogKvs.event("SystemConfig.Init").add("msg", "Initializing system configurations"));
        
        // 加载API速率限制
        loadOrInitConfig(KEY_API_RATE_LIMIT, "5.0", "Bilibili API 请求限速 (QPS) 0:不限速");
        // 加载上传速度限制
        loadOrInitConfig(KEY_UPLOAD_SPEED_LIMIT, "0", "视频上传带宽限速 (MB/s) 0:不限速");
    }

    private void loadOrInitConfig(String key, String defaultValue, String description) {
        Optional<SystemConfig> configOpt = systemConfigRepository.findById(key);
        if (configOpt.isPresent()) {
            applyConfig(key, configOpt.get().getConfigValue());
        } else {
            log.info("[BLR] {}", LogKvs.event("SystemConfig.CreateDefault")
                    .add("key", key)
                    .add("value", defaultValue));
            SystemConfig config = new SystemConfig();
            config.setConfigKey(key);
            config.setConfigValue(defaultValue);
            config.setDescription(description);
            systemConfigRepository.save(config);
            applyConfig(key, defaultValue);
        }
    }

    public void updateConfig(String key, String value) {
        Optional<SystemConfig> configOpt = systemConfigRepository.findById(key);
        SystemConfig config = configOpt.orElse(new SystemConfig());
        config.setConfigKey(key);
        config.setConfigValue(value);
        if (config.getDescription() == null) {
            if (KEY_API_RATE_LIMIT.equals(key)) config.setDescription("Bilibili API 请求限速 (QPS)");
            if (KEY_UPLOAD_SPEED_LIMIT.equals(key)) config.setDescription("视频上传带宽限速 (MB/s)");
        }
        systemConfigRepository.save(config);
        
        applyConfig(key, value);
        
        log.info("[BLR] {}", LogKvs.event("SystemConfig.Updated")
                .add("key", key)
                .add("value", value));
    }

    private void applyConfig(String key, String value) {
        try {
            double doubleValue = Double.parseDouble(value);
            if (KEY_API_RATE_LIMIT.equals(key)) {
                rateLimiterService.setApiRateLimit(doubleValue);
            } else if (KEY_UPLOAD_SPEED_LIMIT.equals(key)) {
                rateLimiterService.setUploadSpeedLimit(doubleValue);
            }
        } catch (NumberFormatException e) {
            log.error("[BLR] {}", LogKvs.event("SystemConfig.ApplyFailed")
                    .add("key", key)
                    .add("value", value)
                    .add("error", "Invalid number format"));
        }
    }

    public List<SystemConfig> getAllConfigs() {
        return systemConfigRepository.findAll();
    }
    
    public Map<String, String> getAllConfigsMap() {
        List<SystemConfig> list = systemConfigRepository.findAll();
        Map<String, String> map = new HashMap<>();
        for (SystemConfig sc : list) {
            map.put(sc.getConfigKey(), sc.getConfigValue());
        }
        return map;
    }
}
