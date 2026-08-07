package top.sshh.bililiverecoder.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sshh.bililiverecoder.service.WorkspaceUsageService;
import top.sshh.bililiverecoder.service.CoreRestartService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@RestController
@RequestMapping("/system-status")
public class SystemStatusController {

    @Autowired
    private WorkspaceUsageService workspaceUsageService;

    @Autowired
    private CoreRestartService coreRestartService;

    @GetMapping("/workspace-usage")
    public Map<String, Object> workspaceUsage() {
        return workspaceUsageService.getLatestSnapshot();
    }

    @PostMapping("/restart-core")
    public ResponseEntity<Map<String, Object>> restartCore(@RequestBody(required = false) Map<String, Object> body) {
        boolean force = body != null && (Boolean.TRUE.equals(body.get("force"))
                || "true".equalsIgnoreCase(String.valueOf(body.get("force"))));
        Map<String, Object> result = coreRestartService.requestRestart(force);
        if (Boolean.TRUE.equals(result.get("requiresForce"))) {
            return ResponseEntity.status(409).body(result);
        }
        return ResponseEntity.ok(result);
    }
}
