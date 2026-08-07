package top.sshh.bililiverecoder.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import top.sshh.bililiverecoder.entity.SystemConfig;
import top.sshh.bililiverecoder.job.BrecCookieSyncJob;
import top.sshh.bililiverecoder.service.SystemConfigService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/system-config")
public class SystemConfigController {

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private BrecCookieSyncJob brecCookieSyncJob;

    @GetMapping("/list")
    public List<SystemConfig> list() {
        return systemConfigService.getAllConfigsForApi();
    }

    @PostMapping("/update")
    public boolean update(@RequestBody Map<String, String> params) {
        String key = params.get("key");
        String value = params.get("value");
        if (key != null && value != null) {
            systemConfigService.updateConfig(key, value);
            return true;
        }
        return false;
    }

    /**
     * 批量更新配置：在单个事务内执行，任意一条失败则整体回滚，
     * 供前端一次性保存多项关联配置（如录播姬同步设置）使用，避免半保存
     */
    @PostMapping("/update-batch")
    public boolean updateBatch(@RequestBody Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return false;
        }
        systemConfigService.updateConfigs(params);
        return true;
    }

    /**
     * 保存录播姬同步配置并立即触发一次 Cookie 推送
     * 先在事务内批量保存（避免半保存），再读取最新配置执行同步，
     * 返回结构化结果供前端展示成功/失败原因。一次请求完成“保存+同步”，避免竞态
     */
    @PostMapping("/brec/sync-now")
    public Map<String, Object> brecSyncNow(@RequestBody(required = false) Map<String, String> params) {
        Map<String, Object> resp = new HashMap<>();
        try {
            if (params != null && !params.isEmpty()) {
                systemConfigService.updateConfigs(params);
            }
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("status", "SAVE_FAILED");
            resp.put("message", "保存配置失败：" + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            return resp;
        }

        BrecCookieSyncJob.SyncResult result = brecCookieSyncJob.performSync();
        resp.put("success", result.success);
        resp.put("status", result.status.name());
        resp.put("message", result.message);
        if (result.httpCode != null) {
            resp.put("httpCode", result.httpCode);
        }
        return resp;
    }
}
