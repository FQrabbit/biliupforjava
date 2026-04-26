package top.sshh.bililiverecoder.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sshh.bililiverecoder.service.FrontendVersionService;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class VersionController {

    private final FrontendVersionService frontendVersionService;

    public VersionController(FrontendVersionService frontendVersionService) {
        this.frontendVersionService = frontendVersionService;
    }

    @GetMapping("/version")
    public ResponseEntity<Map<String, Object>> getVersion() {
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("version", frontendVersionService.getVersion());
        result.put("buildId", frontendVersionService.getBuildId());
        result.put("startupAt", frontendVersionService.getStartupAt());
        result.put("startupEpochMs", frontendVersionService.getStartupEpochMs());
        result.put("timestamp", System.currentTimeMillis());
        HttpHeaders headers = new HttpHeaders();
        headers.set("Cache-Control", "no-cache, no-store, must-revalidate");
        headers.set("Pragma", "no-cache");
        headers.set("Expires", "0");
        return ResponseEntity.ok().headers(headers).body(result);
    }

    @GetMapping("/version/check")
    public ResponseEntity<Map<String, Object>> checkVersion() {
        return getVersion();
    }
}
