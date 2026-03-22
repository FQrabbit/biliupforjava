package top.sshh.bililiverecoder.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sshh.bililiverecoder.service.WorkspaceUsageService;

import java.util.Map;

@RestController
@RequestMapping("/system-status")
public class SystemStatusController {

    @Autowired
    private WorkspaceUsageService workspaceUsageService;

    @GetMapping("/workspace-usage")
    public Map<String, Object> workspaceUsage() {
        return workspaceUsageService.getLatestSnapshot();
    }
}
