package top.sshh.bililiverecoder.controller;

import org.springframework.web.bind.annotation.*;
import top.sshh.bililiverecoder.entity.PublishTaskOperation;
import top.sshh.bililiverecoder.entity.PublishTaskSource;
import top.sshh.bililiverecoder.entity.PublishTaskStatusDto;
import top.sshh.bililiverecoder.service.PublishAccountScheduler;
import top.sshh.bililiverecoder.service.PublishTaskService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/publish-tasks")
public class PublishTaskController {
    private final PublishAccountScheduler scheduler;

    public PublishTaskController(PublishAccountScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @PostMapping
    public Map<String, Object> accept(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Long historyId = longValue(request.get("historyId"));
            Long accountId = longValue(request.get("accountId"));
            PublishTaskOperation operation = PublishTaskOperation.valueOf(String.valueOf(request.get("operation")));
            PublishTaskSource source = request.get("source") == null
                    ? PublishTaskSource.MANUAL : PublishTaskSource.valueOf(String.valueOf(request.get("source")));
            Object payload = request.get("requestSnapshot");
            PublishTaskService.Admission admission = scheduler.accept(accountId, historyId, operation, source, payload);
            result.put("accepted", admission.isAccepted());
            result.put("alreadyQueued", admission.isAlreadyQueued());
            result.put("taskId", admission.getTask() == null ? null : admission.getTask().getId());
            result.put("message", admission.getMessage());
            result.put("status", admission.getTask() == null ? null
                    : scheduler.statusByTaskId(admission.getTask().getId()));
        } catch (RuntimeException e) {
            result.put("accepted", false);
            result.put("alreadyQueued", false);
            result.put("message", "任务请求无效：" + e.getMessage());
        }
        return result;
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        Map<String, Object> result = new LinkedHashMap<>();
        PublishTaskStatusDto status = scheduler.statusByTaskId(id);
        result.put("success", status != null);
        result.put("task", status);
        return result;
    }

    @PostMapping("/batch-state")
    public Map<String, Object> batchState(@RequestBody Map<String, Object> request) {
        List<Long> ids = new ArrayList<>();
        Object raw = request == null ? null : request.get("taskIds");
        if (raw instanceof List<?> list) {
            for (Object value : list) {
                Long id = longValue(value);
                if (id != null && id > 0 && ids.size() < 500) ids.add(id);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tasks", scheduler.statusByTaskIds(ids));
        return result;
    }

    @PostMapping("/{id}/retry")
    public Map<String, Object> retry(@PathVariable Long id,
                                     @RequestBody(required = false) Map<String, Object> request) {
        boolean confirmedNotSubmitted = request != null
                && Boolean.TRUE.equals(request.get("confirmedNotSubmitted"));
        var task = scheduler.retry(id, confirmedNotSubmitted);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", task != null);
        result.put("task", task == null ? scheduler.statusByTaskId(id) : scheduler.statusByTaskId(task.getId()));
        result.put("message", task == null ? "任务当前状态不允许重试；结果未知时需先确认线上未投稿"
                : task.getResultMessage());
        return result;
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable Long id) {
        var task = scheduler.cancel(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", task != null);
        result.put("task", task == null ? scheduler.statusByTaskId(id) : scheduler.statusByTaskId(task.getId()));
        result.put("message", task == null ? "任务当前不可取消或正在最终提交" : "任务已取消");
        return result;
    }

    @PostMapping("/{id}/confirm-bvid")
    public Map<String, Object> confirmBvid(@PathVariable Long id, @RequestBody Map<String, Object> request) {
        return scheduler.confirmBvid(id, request == null ? null : String.valueOf(request.get("bvid")));
    }

    private static Long longValue(Object value) {
        if (value == null) return null;
        try { return Long.valueOf(String.valueOf(value)); }
        catch (RuntimeException e) { return null; }
    }
}
