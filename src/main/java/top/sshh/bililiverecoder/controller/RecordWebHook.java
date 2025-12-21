package top.sshh.bililiverecoder.controller;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sshh.bililiverecoder.entity.BlrecData;
import top.sshh.bililiverecoder.entity.RecordEventDTO;
import top.sshh.bililiverecoder.service.RecordEventFactory;
import top.sshh.bililiverecoder.service.WebhookEventDispatcher;

@Slf4j
@RestController
@RequestMapping("/recordWebHook")
public class RecordWebHook {

    @Autowired
    private RecordEventFactory recordEventFactory;

    @Autowired
    private WebhookEventDispatcher webhookEventDispatcher;

    @PostMapping
    public ResponseEntity<String> processing(@RequestBody RecordEventDTO recordEvent) {
        // Webhook 必须尽快响应：把耗时逻辑放到后台队列里执行，避免 servlet 线程被上传/限速/重试占满
        String lockKey = buildLockKey(recordEvent);
        long delayMs = "SessionEnded".equals(recordEvent.getEventType()) ? 10000L : 0L;

        boolean accepted = webhookEventDispatcher.submit(lockKey, delayMs, () -> {
            try {
                if (recordEvent.getEventData() != null) {
                    log.info("[WEBHOOK] 收到录播姬推送 | Type: {} | RoomId: {} | Title: {}",
                            recordEvent.getEventType(),
                            recordEvent.getEventData().getRoomId(),
                            recordEvent.getEventData().getTitle());
                    log.debug("[WEBHOOK_DEBUG] Full Payload: {}", JSON.toJSONString(recordEvent));
                } else {
                    log.info("收到录播姬的推送信息(旧版/未知格式)==> {}", JSON.toJSONString(recordEvent));
                }
                recordEventFactory.processing(recordEvent);
            } catch (Exception e) {
                log.error("[WEBHOOK] 后台处理失败 | lockKey={} | err={}", lockKey, e.getMessage(), e);
            }
        });

        if (accepted) {
            return ResponseEntity.ok("OK");
        }
        // 队列满返回 503 让发送方重试，避免把 servlet 线程拖死
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("BUSY");
    }

    private static String buildLockKey(RecordEventDTO recordEvent) {
        String lock = "brec:unknown";
        if (recordEvent == null) {
            return lock;
        }
        if (recordEvent.getData() != null) {
            BlrecData data = recordEvent.getData();
            if (data.getRoomInfo() != null && data.getRoomInfo().getRoomId() != null) {
                lock = "blrec:" + data.getRoomInfo().getRoomId();
            } else if (data.getRoomId() != null) {
                lock = "blrec:" + data.getRoomId();
            }
            return lock;
        }
        if (recordEvent.getEventData() != null) {
            try {
                if ("SessionEnded".equals(recordEvent.getEventType())) {
                    lock = "brec:" + recordEvent.getEventType();
                } else if ("FileClosed".equals(recordEvent.getEventType())) {
                    lock = "brec:" + recordEvent.getEventData().getRelativePath();
                } else if ("FileOpening".equals(recordEvent.getEventType())) {
                    lock = "brec:" + recordEvent.getEventData().getRelativePath();
                } else {
                    lock = "brec:" + recordEvent.getEventData().getSessionId();
                }
            } catch (Exception e) {
                log.debug("Error building lock key from eventData | eventType={} | err={}",
                        recordEvent.getEventType(), e.getMessage(), e);
            }
        }
        return lock;
    }

    @GetMapping
    public String processing() {
        return "这里是录播姬推送的接口地址，把当前地址复制到录播姬WebHookV2里即可(前提是录播姬网络环境也可访问)";
    }
}
