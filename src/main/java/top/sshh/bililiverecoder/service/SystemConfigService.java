package top.sshh.bililiverecoder.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.SystemConfig;
import top.sshh.bililiverecoder.repo.SystemConfigRepository;
import top.sshh.bililiverecoder.util.LogKvs;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
public class SystemConfigService {

    public static final String KEY_API_RATE_LIMIT = "bili.limit.api-qps";
    // Historical persisted key name. The value is an upload speed limit in MB/s.
    public static final String KEY_UPLOAD_SPEED_LIMIT = "bili.limit.upload-mb";
    public static final String KEY_MERGE_INTERVAL_MINUTES = "bili.publish.merge-interval-minutes";
    public static final String KEY_UPLOAD_MAX_CONNECTIONS = "upload.max-concurrent-connections";
    public static final String KEY_UPLOAD_NEW_FLOW_ENABLED = "upload.new-flow-enabled";
    public static final String KEY_NORMAL_DANMAKU_INTERVAL_SECONDS = "bili.dm.normal-send-interval-seconds";
    public static final String KEY_HIGH_LEVEL_DANMAKU_INTERVAL_SECONDS = "bili.dm.high-level-send-interval-seconds";
    public static final String KEY_DANMAKU_RECONCILE_INTERVAL_SECONDS = "bili.dm.reconcile-interval-seconds";
    public static final String KEY_DANMAKU_DISPATCH_BATCH_SIZE = "bili.dm.dispatch-batch-size";
    public static final String KEY_DANMAKU_MAX_NORMAL_WORKERS = "bili.dm.max-normal-workers";
    public static final String KEY_NOTIFICATION_ENABLED = "notification.enabled";

    // 录播姬 Cookie 自动同步配置
    public static final String KEY_BREC_SYNC_ENABLED = "brec.cookie-sync.enabled";
    public static final String KEY_BREC_SYNC_HOST = "brec.cookie-sync.host";
    public static final String KEY_BREC_SYNC_PORT = "brec.cookie-sync.port";
    public static final String KEY_BREC_SYNC_HTTPS = "brec.cookie-sync.https";
    public static final String KEY_BREC_SYNC_USERNAME = "brec.cookie-sync.username";
    public static final String KEY_BREC_SYNC_PASSWORD = "brec.cookie-sync.password";
    public static final String KEY_BREC_SYNC_UID = "brec.cookie-sync.uid";

    private static final String OBSOLETE_KEY_DANMAKU_DISPATCH_ENABLED = "bili.dm.dispatch-enabled";
    private static final String OBSOLETE_KEY_DANMAKU_ACCOUNT_API_INTERVAL_SECONDS = "bili.dm.account-api-interval-seconds";

    private static final long DEFAULT_NORMAL_DANMAKU_INTERVAL_SECONDS = 25L;
    private static final long DEFAULT_HIGH_LEVEL_DANMAKU_INTERVAL_SECONDS = 25L;
    private static final long DEFAULT_DANMAKU_RECONCILE_INTERVAL_SECONDS = 300L;
    private static final int DEFAULT_DANMAKU_DISPATCH_BATCH_SIZE = 200;
    private static final int DEFAULT_DANMAKU_MAX_NORMAL_WORKERS = 2;

    @Autowired
    private SystemConfigRepository systemConfigRepository;

    @Autowired
    private RateLimiterService rateLimiterService;

    @Autowired
    private UploadConnectionBudgetService uploadConnectionBudgetService;

    @PostConstruct
    public void init() {
        log.info("[BLR] {}", LogKvs.event("SystemConfig.Init").add("msg", "Initializing system configurations"));
        removeObsoleteConfig(OBSOLETE_KEY_DANMAKU_DISPATCH_ENABLED);
        removeObsoleteConfig(OBSOLETE_KEY_DANMAKU_ACCOUNT_API_INTERVAL_SECONDS);
        
        // 加载API速率限制
        loadOrInitConfig(KEY_API_RATE_LIMIT, "5.0", "Bilibili API 请求限速 (QPS) 0:不限速");
        // 加载上传速度限制
        loadOrInitConfig(KEY_UPLOAD_SPEED_LIMIT, "0", "视频上传带宽限速 (MB/s) 0:不限速");
        // 加载短时间开播合并时间
        loadOrInitConfig(KEY_MERGE_INTERVAL_MINUTES, "20", "短时间开播合并时间 (分钟) - 下播后等待多长时间再投稿，同时间隔多少分钟内开播算同一次直播，避免短时间开播下播拆分稿件");
        // 加载上传最大并发连接数
        loadOrInitConfig(KEY_UPLOAD_MAX_CONNECTIONS, "3", "上传最大并发连接数 (1-16) 控制同时进行的分片上传连接数，值越小网络占用越少");
        loadOrInitConfig(KEY_UPLOAD_NEW_FLOW_ENABLED, "false", "是否使用浏览器 multipart 上传流程，失败后自动回退旧流程");
        loadOrInitConfig(KEY_NORMAL_DANMAKU_INTERVAL_SECONDS, String.valueOf(DEFAULT_NORMAL_DANMAKU_INTERVAL_SECONDS), "弹幕发送间隔(秒)：普通弹幕与SC/上舰高级弹幕共用的全局发送节拍");
        loadOrInitConfig(KEY_HIGH_LEVEL_DANMAKU_INTERVAL_SECONDS, String.valueOf(DEFAULT_HIGH_LEVEL_DANMAKU_INTERVAL_SECONDS), "评论发送间隔(秒)：SC/上舰列表评论与礼物评论共用的全局发送节拍");
        initDanmakuDispatchConfig();
        initBrecCookieSyncConfig();
        initNotificationConfig();
    }

    private void initNotificationConfig() {
        loadOrInitConfig(KEY_NOTIFICATION_ENABLED, "true", "是否启用统一推送通知模块");
    }

    private void initBrecCookieSyncConfig() {
        loadOrInitConfig(KEY_BREC_SYNC_ENABLED, "false", "是否启用录播姬 Cookie 自动同步");
        loadOrInitConfig(KEY_BREC_SYNC_HOST, "", "录播姬地址 (IP 或域名，不含协议和端口)");
        loadOrInitConfig(KEY_BREC_SYNC_PORT, "2356", "录播姬 WebApi 端口");
        loadOrInitConfig(KEY_BREC_SYNC_HTTPS, "false", "录播姬是否使用 HTTPS 连接");
        loadOrInitConfig(KEY_BREC_SYNC_USERNAME, "", "录播姬 Basic 认证用户名 (留空表示无认证)");
        loadOrInitConfig(KEY_BREC_SYNC_PASSWORD, "", "录播姬 Basic 认证密码");
        loadOrInitConfig(KEY_BREC_SYNC_UID, "", "提供同步 Cookie 的 B站账号 UID");
    }

    private void initDanmakuDispatchConfig() {
        loadOrInitConfig(KEY_DANMAKU_RECONCILE_INTERVAL_SECONDS, String.valueOf(DEFAULT_DANMAKU_RECONCILE_INTERVAL_SECONDS), "Queued danmaku reconciliation interval seconds");
        loadOrInitConfig(KEY_DANMAKU_DISPATCH_BATCH_SIZE, String.valueOf(DEFAULT_DANMAKU_DISPATCH_BATCH_SIZE), "Queued danmaku reconciliation batch size");
        loadOrInitConfig(KEY_DANMAKU_MAX_NORMAL_WORKERS, String.valueOf(DEFAULT_DANMAKU_MAX_NORMAL_WORKERS), "Max concurrent normal danmaku workers");
    }

    private void removeObsoleteConfig(String key) {
        if (systemConfigRepository.existsById(key)) {
            systemConfigRepository.deleteById(key);
            log.info("[BLR] {}", LogKvs.event("SystemConfig.RemoveObsolete")
                    .add("key", key));
        }
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

    /**
     * 在单个事务内批量更新配置：任意一条失败都会整体回滚，避免出现“半保存”
     * 导致启用状态、目标 UID、主机、密码彼此不一致的脏配置。
     *
     * 敏感字段（密码）的空值会被跳过——前端读取时拿到的是空串，若用户未重新输入，
     * 提交上来的空值视为“保持原值不变”，不会清掉数据库里已有的密码。
     */
    @Transactional
    public void updateConfigs(Map<String, String> configs) {
        if (configs == null || configs.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : configs.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || value == null) {
                continue;
            }
            if (isSensitiveConfigKey(key) && value.isEmpty()) {
                // 留空表示不修改密码，跳过以保留原值
                continue;
            }
            updateConfig(key, value);
        }
    }

    public void updateConfig(String key, String value) {
        if (isObsoleteConfigKey(key)) {
            log.info("[BLR] {}", LogKvs.event("SystemConfig.IgnoreObsolete")
                    .add("key", key));
            return;
        }
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
            if (KEY_NORMAL_DANMAKU_INTERVAL_SECONDS.equals(key)) config.setDescription("弹幕发送间隔(秒)：普通弹幕与SC/上舰高级弹幕共用的全局发送节拍");
            if (KEY_HIGH_LEVEL_DANMAKU_INTERVAL_SECONDS.equals(key)) config.setDescription("评论发送间隔(秒)：SC/上舰列表评论与礼物评论共用的全局发送节拍");
            if (KEY_NOTIFICATION_ENABLED.equals(key)) config.setDescription("是否启用统一推送通知模块");
        }
        systemConfigRepository.save(config);
        
        applyConfig(key, value);

        log.info("[BLR] {}", LogKvs.event("SystemConfig.Updated")
                .add("key", key)
                .add("value", KEY_BREC_SYNC_PASSWORD.equals(key) ? "***" : value));
    }

    private String normalizeConfigValue(String key, String value) {
        if (value == null) {
            return null;
        }
        if (KEY_UPLOAD_NEW_FLOW_ENABLED.equals(key) || KEY_NOTIFICATION_ENABLED.equals(key)) {
            String normalized = value.trim().toLowerCase();
            return ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized) || "on".equals(normalized)) ? "true" : "false";
        }
        if (KEY_BREC_SYNC_ENABLED.equals(key) || KEY_BREC_SYNC_HTTPS.equals(key)) {
            String normalized = value.trim().toLowerCase();
            return ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized) || "on".equals(normalized)) ? "true" : "false";
        }
        // 录播姬地址、账号、密码、UID 等为纯字符串，原样保存
        if (isBrecSyncConfigKey(key)) {
            return value.trim();
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
            if (KEY_DANMAKU_RECONCILE_INTERVAL_SECONDS.equals(key)) {
                long lv = Math.round(v);
                if (lv < 60) {
                    return "60";
                }
                if (lv > 3600) {
                    return "3600";
                }
                return String.valueOf(lv);
            }
            if (KEY_DANMAKU_DISPATCH_BATCH_SIZE.equals(key)) {
                int iv = (int) Math.round(v);
                if (iv < 10) {
                    return "10";
                }
                if (iv > 1000) {
                    return "1000";
                }
                return String.valueOf(iv);
            }
            if (KEY_DANMAKU_MAX_NORMAL_WORKERS.equals(key)) {
                int iv = (int) Math.round(v);
                if (iv < 1) {
                    return "1";
                }
                if (iv > 8) {
                    return "8";
                }
                return String.valueOf(iv);
            }
            return value;
        } catch (NumberFormatException e) {
            return value;
        }
    }

    private void applyConfig(String key, String value) {
        if (KEY_UPLOAD_NEW_FLOW_ENABLED.equals(key) || KEY_NOTIFICATION_ENABLED.equals(key)) {
            log.info("[BLR] {}", LogKvs.event("SystemConfig.ApplyBoolean")
                    .add("key", key)
                    .add("value", value));
            return;
        }
        // 录播姬同步相关配置为字符串/布尔型，无需热应用，Job 运行时直接从 DB 读取
        if (isBrecSyncConfigKey(key)) {
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

    /**
     * 供前端 /list 接口使用的配置列表：敏感字段（如录播姬 Basic 认证密码）的值会被抹除，
     * 避免明文密码下发到浏览器。返回的是游离副本，不会影响数据库中的真实值。
     */
    public List<SystemConfig> getAllConfigsForApi() {
        List<SystemConfig> source = systemConfigRepository.findAll();
        List<SystemConfig> result = new ArrayList<>(source.size());
        for (SystemConfig sc : source) {
            SystemConfig copy = new SystemConfig();
            copy.setConfigKey(sc.getConfigKey());
            copy.setDescription(sc.getDescription());
            if (isSensitiveConfigKey(sc.getConfigKey())) {
                // 抹除真实值；前端据此把密码框留空，留空提交即视为“保持原密码不变”
                copy.setConfigValue("");
            } else {
                copy.setConfigValue(sc.getConfigValue());
            }
            result.add(copy);
        }
        return result;
    }

    private boolean isSensitiveConfigKey(String key) {
        return KEY_BREC_SYNC_PASSWORD.equals(key);
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
        return getDanmakuSendIntervalMs();
    }

    public long getHighLevelDanmakuIntervalMs() {
        return getCommentSendIntervalMs();
    }

    public long getDanmakuSendIntervalMs() {
        return getLongConfig(KEY_NORMAL_DANMAKU_INTERVAL_SECONDS, DEFAULT_NORMAL_DANMAKU_INTERVAL_SECONDS, 1L, 600L) * 1000L;
    }

    public long getCommentSendIntervalMs() {
        return getLongConfig(KEY_HIGH_LEVEL_DANMAKU_INTERVAL_SECONDS, DEFAULT_HIGH_LEVEL_DANMAKU_INTERVAL_SECONDS, 1L, 600L) * 1000L;
    }

    public long getDanmakuReconcileIntervalMs() {
        return getLongConfig(KEY_DANMAKU_RECONCILE_INTERVAL_SECONDS, DEFAULT_DANMAKU_RECONCILE_INTERVAL_SECONDS, 60L, 3600L) * 1000L;
    }

    public int getDanmakuDispatchBatchSize() {
        return (int) getLongConfig(KEY_DANMAKU_DISPATCH_BATCH_SIZE, DEFAULT_DANMAKU_DISPATCH_BATCH_SIZE, 10L, 1000L);
    }

    public int getDanmakuMaxNormalWorkers() {
        return (int) getLongConfig(KEY_DANMAKU_MAX_NORMAL_WORKERS, DEFAULT_DANMAKU_MAX_NORMAL_WORKERS, 1L, 8L);
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

    private boolean isObsoleteConfigKey(String key) {
        return OBSOLETE_KEY_DANMAKU_DISPATCH_ENABLED.equals(key)
                || OBSOLETE_KEY_DANMAKU_ACCOUNT_API_INTERVAL_SECONDS.equals(key);
    }

    private boolean isBrecSyncConfigKey(String key) {
        return KEY_BREC_SYNC_ENABLED.equals(key)
                || KEY_BREC_SYNC_HOST.equals(key)
                || KEY_BREC_SYNC_PORT.equals(key)
                || KEY_BREC_SYNC_HTTPS.equals(key)
                || KEY_BREC_SYNC_USERNAME.equals(key)
                || KEY_BREC_SYNC_PASSWORD.equals(key)
                || KEY_BREC_SYNC_UID.equals(key);
    }

    public String getStringConfig(String key, String defaultValue) {
        return systemConfigRepository.findById(key)
                .map(SystemConfig::getConfigValue)
                .orElse(defaultValue);
    }

    public boolean getBooleanConfig(String key, boolean defaultValue) {
        return systemConfigRepository.findById(key)
                .map(SystemConfig::getConfigValue)
                .map(value -> "true".equalsIgnoreCase(value) || "1".equals(value))
                .orElse(defaultValue);
    }

    public boolean isBrecCookieSyncEnabled() {
        return getBooleanConfig(KEY_BREC_SYNC_ENABLED, false);
    }
}
