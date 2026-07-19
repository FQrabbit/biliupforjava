package top.sshh.bililiverecoder.controller;

import org.springframework.web.bind.annotation.*;
import top.sshh.bililiverecoder.entity.PartFileOperation;
import top.sshh.bililiverecoder.entity.StorageRoot;
import top.sshh.bililiverecoder.service.PartFileOperationService;
import top.sshh.bililiverecoder.service.StorageRootService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/storage-roots")
public class StorageRootController {

    private final StorageRootService rootService;
    private final PartFileOperationService operationService;

    public StorageRootController(StorageRootService rootService, PartFileOperationService operationService) {
        this.rootService = rootService;
        this.operationService = operationService;
    }

    @GetMapping
    public List<StorageRoot> list() {
        return rootService.findAll();
    }

    @GetMapping("/work-path-change")
    public Map<String, Object> workPathChange() {
        StorageRootService.WorkPathChange change = rootService.workPathChange();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pending", change.pending());
        result.put("configuredPath", change.configuredPath());
        result.put("activeRoot", change.activeRoot());
        result.put("h2Warning", "本地H2数据库仍位于旧 work-path/db；修改工作目录前请手动迁移数据库或使用MySQL");
        return result;
    }

    @PostMapping("/work-path-change/resolve")
    public Map<String, Object> resolveWorkPathChange(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String value = String.valueOf(request == null ? null : request.get("mode"));
            StorageRootService.WorkPathChangeMode mode = StorageRootService.WorkPathChangeMode.valueOf(value);
            result.put("success", true);
            result.put("root", rootService.resolveWorkPathChange(mode));
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    @PostMapping("/operations/{operationKey}/retry")
    public Map<String, Object> retry(@PathVariable String operationKey) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            PartFileOperation operation = operationService.retry(operationKey);
            result.put("success", operation.getStatus() == PartFileOperation.OperationStatus.SUCCEEDED
                    || operation.getStatus() == PartFileOperation.OperationStatus.SUCCEEDED_WITH_WARNINGS);
            result.put("operation", operation);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    @PostMapping("/{rootId}/remap")
    public Map<String, Object> remap(@PathVariable Long rootId, @RequestBody Map<String, Object> request) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String path = request == null ? null : String.valueOf(request.get("path"));
            result.put("success", true);
            result.put("root", rootService.remap(rootId, path));
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }
}
