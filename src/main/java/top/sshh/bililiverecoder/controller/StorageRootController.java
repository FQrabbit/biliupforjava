package top.sshh.bililiverecoder.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.*;
import top.sshh.bililiverecoder.entity.PartFileOperation;
import top.sshh.bililiverecoder.entity.StorageRoot;
import top.sshh.bililiverecoder.service.PartFileOperationService;
import top.sshh.bililiverecoder.service.StorageRootChangeAssessmentService;
import top.sshh.bililiverecoder.service.StorageRootService;
import top.sshh.bililiverecoder.util.ContainerUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/storage-roots")
public class StorageRootController {

    private final StorageRootService rootService;
    private final PartFileOperationService operationService;
    private final StorageRootChangeAssessmentService assessmentService;
    private final Environment environment;

    @Autowired
    public StorageRootController(StorageRootService rootService,
                                 PartFileOperationService operationService,
                                 StorageRootChangeAssessmentService assessmentService,
                                 Environment environment) {
        this.rootService = rootService;
        this.operationService = operationService;
        this.assessmentService = assessmentService;
        this.environment = environment;
    }

    /** 兼容轻量控制器测试使用的构造方法 */
    public StorageRootController(StorageRootService rootService, PartFileOperationService operationService) {
        this.rootService = rootService;
        this.operationService = operationService;
        this.assessmentService = null;
        this.environment = null;
    }

    @GetMapping
    public List<StorageRoot> list() {
        return rootService.findAll();
    }

    @GetMapping("/work-path-change")
    public Map<String, Object> workPathChange() {
        StorageRootService.WorkPathChange change = rootService.workPathChange();
        return status(change);
    }

    @PostMapping({"/work-path-change/assessment", "/work-path-change/assessment/start"})
    public Map<String, Object> startAssessment() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            result.put("success", true);
            result.put("assessment", assessmentService == null
                    ? null : assessmentService.start());
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    @GetMapping("/work-path-change/assessment")
    public Object assessment() {
        return assessmentService == null ? null : assessmentService.snapshot();
    }

    @PostMapping("/work-path-change/assessment/cancel")
    public Object cancelAssessment() {
        return assessmentService == null ? null : assessmentService.cancel();
    }

    @PostMapping("/work-path-change/resolve")
    public Map<String, Object> resolveWorkPathChange(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String value = String.valueOf(request == null ? null : request.get("mode"));
            StorageRootService.WorkPathChangeMode mode = parseMode(value);
            String requestedChangeId = request == null ? null : String.valueOf(request.get("changeId"));
            if (assessmentService != null
                    && requestedChangeId != null
                    && !requestedChangeId.isBlank()
                    && !requestedChangeId.equals(assessmentService.currentChangeId())) {
                throw new IllegalArgumentException("目录变更状态已更新，请刷新后重试");
            }
            boolean prevalidated = false;
            if (mode == StorageRootService.WorkPathChangeMode.REMAP_EXISTING
                    && assessmentService != null) {
                if (!assessmentService.isValidForRemap(requestedChangeId)) {
                    throw new IllegalArgumentException("请先完成全部历史文件校验，且不能存在缺失或大小不一致");
                }
                prevalidated = true;
            }
            result.put("success", true);
            result.put("root", assessmentService == null
                    ? rootService.resolveWorkPathChange(mode)
                    : rootService.resolveWorkPathChange(mode, prevalidated));
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

    private Map<String, Object> status(StorageRootService.WorkPathChange change) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pending", change.pending());
        result.put("changeId", assessmentService == null ? "" : assessmentService.currentChangeId());
        result.put("configuredPath", change.configuredPath());
        result.put("activeRoot", change.activeRoot());
        result.put("containerized", ContainerUtils.isRunningInContainer());
        String jdbcUrl = environment == null ? "" : environment.getProperty("spring.datasource.hikari.jdbc-url",
                environment.getProperty("spring.datasource.url", ""));
        boolean embeddedH2 = jdbcUrl != null && jdbcUrl.toLowerCase(java.util.Locale.ROOT).startsWith("jdbc:h2:");
        result.put("databaseKind", embeddedH2 ? "H2" : "EXTERNAL");
        result.put("h2Warning", embeddedH2 || environment == null
                ? "本功能只调整历史素材路径映射，不会移动或迁移 work-path/db 中的 H2 数据库。"
                : "当前使用外部数据库，本次只会处理历史素材路径映射。");
        result.put("oldRoot", rootStatus(change.activeRoot()));
        result.put("newRoot", rootStatus(change.configuredPath()));
        StorageRootChangeAssessmentService.Snapshot snapshot = assessmentService == null
                ? new StorageRootChangeAssessmentService.Snapshot("", StorageRootChangeAssessmentService.State.IDLE,
                0, 0, 0, 0, 0, "尚未开始检查", null, null)
                : assessmentService.snapshot();
        result.put("assessment", snapshot);
        result.put("recommendation", recommendation(change, snapshot));
        result.put("actions", actions(change, snapshot));
        return result;
    }

    private Map<String, Object> rootStatus(String configuredPath) {
        Map<String, Object> status = new LinkedHashMap<>();
        try {
            Path path = StorageRootService.normalizeAbsolute(configuredPath);
            status.put("exists", Files.exists(path));
            status.put("readable", Files.isDirectory(path) && Files.isReadable(path));
            status.put("writable", Files.isDirectory(path) && Files.isWritable(path));
            status.put("path", path.toString());
        } catch (Exception e) {
            status.put("exists", false);
            status.put("readable", false);
            status.put("writable", false);
            status.put("path", configuredPath);
        }
        return status;
    }

    private Map<String, Object> rootStatus(StorageRoot root) {
        Map<String, Object> status = rootStatus(root == null ? "" : root.getPath());
        status.put("status", root == null || root.getStatus() == null ? "UNKNOWN" : root.getStatus().name());
        status.put("online", root != null && root.getStatus() == StorageRoot.RootStatus.ONLINE);
        return status;
    }

    private Map<String, Object> recommendation(StorageRootService.WorkPathChange change,
                                               StorageRootChangeAssessmentService.Snapshot snapshot) {
        Map<String, Object> recommendation = new LinkedHashMap<>();
        Map<String, Object> target = rootStatus(change.configuredPath());
        boolean targetUsable = Boolean.TRUE.equals(target.get("readable"))
                && Boolean.TRUE.equals(target.get("writable"));
        boolean oldOnline = change.activeRoot() != null
                && change.activeRoot().getStatus() == StorageRoot.RootStatus.ONLINE;
        String mode = "FUTURE_ONLY";
        String reason = "新目录可用，保留旧素材映射并仅让后续新稿件使用新目录更安全。";
        if (!targetUsable) {
            mode = "RESTORE_CONFIG";
            reason = "新目录不可读写，请检查挂载、权限或恢复原工作目录。";
        } else if (snapshot.validForRemap() && (!oldOnline || ContainerUtils.isRunningInContainer())) {
            mode = "REMAP_EXISTING";
            reason = ContainerUtils.isRunningInContainer()
                    ? "Docker 中旧容器路径已变化，但新挂载点完整找到历史文件，适合只更新数据库映射。"
                    : "旧目录不可用且新目录完整找到历史文件，适合只更新数据库映射。";
        } else if (!oldOnline && snapshot.complete() && (snapshot.missing() > 0 || snapshot.sizeMismatch() > 0)) {
            mode = "RESTORE_CONFIG";
            reason = "旧目录不可用且新目录缺少历史文件或大小不一致，暂不允许更新数据库映射；请检查 Docker 卷、NAS 挂载或恢复旧配置。";
        }
        recommendation.put("mode", mode);
        recommendation.put("reason", reason);
        return recommendation;
    }

    private Map<String, Object> actions(StorageRootService.WorkPathChange change,
                                        StorageRootChangeAssessmentService.Snapshot snapshot) {
        Map<String, Object> actions = new LinkedHashMap<>();
        boolean targetUsable = Boolean.TRUE.equals(rootStatus(change.configuredPath()).get("readable"))
                && Boolean.TRUE.equals(rootStatus(change.configuredPath()).get("writable"));
        actions.put("FUTURE_ONLY", Map.of("enabled", targetUsable,
                "disabledReason", targetUsable ? "" : "新目录不可读写"));
        actions.put("REMAP_EXISTING", Map.of("enabled", snapshot.validForRemap(),
                "disabledReason", snapshot.validForRemap() ? "" : "需完成全部校验且不存在缺失或大小不一致"));
        actions.put("RESTORE_CONFIG", Map.of("enabled", true, "disabledReason", ""));
        return actions;
    }

    private static StorageRootService.WorkPathChangeMode parseMode(String value) {
        if ("RELOCATE_EXISTING".equalsIgnoreCase(value)) {
            return StorageRootService.WorkPathChangeMode.RELOCATE_EXISTING;
        }
        return StorageRootService.WorkPathChangeMode.valueOf(value);
    }
}
