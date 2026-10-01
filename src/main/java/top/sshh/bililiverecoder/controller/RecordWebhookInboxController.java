package top.sshh.bililiverecoder.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.sshh.bililiverecoder.entity.RecordWebhookInboxStatusDto;
import top.sshh.bililiverecoder.service.RecordWebhookInboxService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/recordWebHook/inbox")
public class RecordWebhookInboxController {
    private final RecordWebhookInboxService inboxService;

    public RecordWebhookInboxController(RecordWebhookInboxService inboxService) {
        this.inboxService = inboxService;
    }

    @GetMapping
    public List<RecordWebhookInboxStatusDto> recent(@RequestParam(defaultValue = "100") int limit) {
        return inboxService.recent(limit);
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<Map<String, Object>> retryFailed(@PathVariable Long id) {
        boolean retried = inboxService.retryFailed(id);
        return ResponseEntity.status(retried ? HttpStatus.OK : HttpStatus.CONFLICT)
                .body(Map.of("success", retried, "eventId", id,
                        "message", retried ? "失败事件已重新排队" : "事件不存在或当前状态不能重试"));
    }
}
