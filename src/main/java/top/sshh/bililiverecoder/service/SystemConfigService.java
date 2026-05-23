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
    public static final String KEY_MERGE_INTERVAL_MINUTES = "bili.publish.merge-interval-minutes";
    public static final String KEY_UPLOAD_MAX_CONNECTIONS = "upload.max-concurrent-connections";
    public static final String KEY_UPLOAD_NEW_FLOW_ENABLED = "upload.new-flow-enabled";
    public static final String KEY_NORMAL_DANMAKU_INTERVAL_SECONDS = "bili.dm.normal-send-interval-seconds";
    public static final String KEY_HIGH_LEVEL_DANMAKU_INTERVAL_SECONDS = "bili.dm.high-level-send-interval-seconds";

    private static final long DEFAULT_NORMAL_DANMAKU_INTERVAL_SECONDS = 25L;
    private static final long DEFAULT_HIGH_LEVEL_DANMAKU_INTERVAL_SECONDS = 25L;

    @Autowired
    private SystemConfigRepository systemConfigRepository;

    @Autowired
    private RateLimiterService rateLimiterService;

    @Autowired
    private UploadConnectionBudgetService uploadConnectionBudgetService;

    @PostConstruct
    public void init() {
        log.info("[BLR] {}", LogKvs.event("SystemConfig.Init").add("msg", "Initializing system configurations"));
        
        // 加载API速率限制
        loadOrInitConfig(KEY_API_RATE_LIMIT, "5.0", "Bilibili API 请求限速 (QPS) 0:不限速");
        // 加载上传速度限制
        loadOrInitConfig(KEY_UPLOAD_SPEED_LIMIT, "0", "视频上传带宽限速 (MB/s) 0:不限速");
        // 加载短时间开播合并时间
        loadOrInitConfig(KEY_MERGE_INTERVAL_MINUTES, "20", "短时间开播合并时间 (分钟) - 下播后等待多长时间再投稿，同时间隔多少分钟内开播算同一次直播，避免短时间开播下播拆分稿件");
        // 加载上传最大并发连接数
        loadOrInitConfig(KEY_UPLOAD_MAX_CONNECTIONS, "3", "上传最大并发连接数 (1-16) 控制同时进行的分片上传连接数，值越小网络占用越少");
        loadOrInitConfig(KEY_UPLOAD_NEW_FLOW_ENABLED, "false", "是否使用浏览器 multipart 上传流程，失败后自动回退旧流程");
        loadOrInitConfig(KEY_NORMAL_DANMAKU_INTERVAL_SECONDS, String.valueOf(DEFAULT_NORMAL_DANMAKU_INTERVAL_SECONDS), "发送普通弹幕间隔(秒)");
        loadOrInitConfig(KEY_HIGH_LEVEL_DANMAKU_INTERVAL_SECONDS, String.valueOf(DEFAULT_HIGH_LEVEL_DANMAKU_INTERVAL_SECONDS), "发送高级弹幕(SC/上舰/礼物)间隔(秒)");
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
        value = normalizeConfigValue(key, value);
        Optional<SystemConfig> configOpt = systemConfigRepository.findById(key);
        SystemConfig config = configOpt.orElse(new SystemConfig());
        config.setConfigKey(key);
        config.setConfigValue(value);
        if (config.getDescription() == null) {
            if (KEY_API_RATE_LIMIT.equals(key)) config.setDescription("Bilibili API 请求限速 (QPS)");
            if (KEY_UPLOAD_SPEED_LIMIT.equals(key)) config.setDescription("视频上传带宽限速 (MB/s)");
            if (KEY_MERGE_INTERVAL_MINUTES.equals(key)) config.setDescription("短时间开播合并时间 (分钟)");
            if (KEY_UPLOAD_MAX_CONNECTIONS.equals(key)) config.setDescription("上传最大并发连接数 (1-16)");
            if (KEY_UPLOAD_NEW_FLOW_ENABLED.equals(key)) config.setDescription("是否使用浏览器 multipart 上传流程");
            if (KEY_NORMAL_DANMAKU_INTERVAL_SECONDS.equals(key)) config.setDescription("发送普通弹幕间隔(秒)");
            if (KEY_HIGH_LEVEL_DANMAKU_INTERVAL_SECONDS.equals(key)) config.setDescription("发送高级弹幕(SC/上舰/礼物)间隔(秒)");
        }
        systemConfigRepository.save(config);
        
        applyConfig(key, value);
        
        log.info("[BLR] {}", LogKvs.event("SystemConfig.Updated")
                .add("key", key)
                .add("value", value));
    }

    private String normalizeConfigValue(String key, String value) {
        if (value == null) {
            return null;
        }
        if (KEY_UPLOAD_NEW_FLOW_ENABLED.equals(key)) {
            String normalized = value.trim().toLowerCase();
            return ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized) || "on".equals(normalized)) ? "true" : "false";
        }
        try {
            double v = Double.parseDouble(value);
            if (KEY_UPLOAD_SPEED_LIMIT.equals(key)) {
                if (v < 0) {
                    return "0";
                }
                if (v > 200) {
                    return "200";
                }
                return String.valueOf(v);
            }
            if (KEY_API_RATE_LIMIT.equals(key)) {
                if (v < 0) {
                    return "0";
                }
                if (v > 1000) {
                    return "1000";
                }
                return String.valueOf(v);
            }
            if (KEY_MERGE_INTERVAL_MINUTES.equals(key)) {
                if (v < 1) {
                    return "1";
                }
                if (v > 1440) {
                    return "1440";
                }
                return String.valueOf((int) Math.round(v));
            }
            if (KEY_UPLOAD_MAX_CONNECTIONS.equals(key)) {
                int iv = (int) Math.round(v);
                if (iv < 1) {
                    return "1";
                }
                if (iv > 16) {
                    return "16";
                }
                return String.valueOf(iv);
            }
            if (KEY_NORMAL_DANMAKU_INTERVAL_SECONDS.equals(key) || KEY_HIGH_LEVEL_DANMAKU_INTERVAL_SECONDS.equals(key)) {
                long lv = Math.round(v);
                if (lv < 1) {
                    return "1";
                }
                if (lv > 600) {
                    return "600";
                }
                return String.valueOf(lv);
            }
            return value;
        } catch (NumberFormatException e) {
            return value;
        }
    }

    private void applyConfig(String key, String value) {
        if (KEY_UPLOAD_NEW_FLOW_ENABLED.equals(key)) {
            log.info("[BLR] {}", LogKvs.event("SystemConfig.ApplyBoolean")
                    .add("key", key)
                    .add("value", value));
            return;
        }
        try {
            double doubleValue = Double.parseDouble(value);
            if (KEY_API_RATE_LIMIT.equals(key)) {
                rateLimiterService.setApiRateLimit(doubleValue);
            } else if (KEY_UPLOAD_SPEED_LIMIT.equals(key)) {
                rateLimiterService.setUploadSpeedLimit(doubleValue);
            } else if (KEY_UPLOAD_MAX_CONNECTIONS.equals(key)) {
                uploadConnectionBudgetService.updateMaxConnections((int) Math.round(doubleValue));
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

    public boolean isNewUploadFlowEnabled() {
        return systemConfigRepository.findById(KEY_UPLOAD_NEW_FLOW_ENABLED)
                .map(SystemConfig::getConfigValue)
                .map(value -> "true".equalsIgnoreCase(value) || "1".equals(value))
                .orElse(false);
    }

    public long getNormalDanmakuIntervalMs() {
        return getLongConfig(KEY_NORMAL_DANMAKU_INTERVAL_SECONDS, DEFAULT_NORMAL_DANMAKU_INTERVAL_SECONDS, 1L, 600L) * 1000L;
    }

    public long getHighLevelDanmakuIntervalMs() {
        return getLongConfig(KEY_HIGH_LEVEL_DANMAKU_INTERVAL_SECONDS, DEFAULT_HIGH_LEVEL_DANMAKU_INTERVAL_SECONDS, 1L, 600L) * 1000L;
    }

    private long getLongConfig(String key, long defaultValue, long minValue, long maxValue) {
        return systemConfigRepository.findById(key)
                .map(SystemConfig::getConfigValue)
                .map(value -> {
                    try {
                        long parsed = Math.round(Double.parseDouble(value));
                        return Math.max(minValue, Math.min(maxValue, parsed));
                    } catch (NumberFormatException e) {
                        return defaultValue;
                    }
                })
                .orElse(defaultValue);
    }
}
