package top.sshh.bililiverecoder.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import top.sshh.bililiverecoder.entity.SystemConfig;
import top.sshh.bililiverecoder.service.SystemConfigService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/system-config")
public class SystemConfigController {

    @Autowired
    private SystemConfigService systemConfigService;

    @GetMapping("/list")
    public List<SystemConfig> list() {
        return systemConfigService.getAllConfigs();
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
}
