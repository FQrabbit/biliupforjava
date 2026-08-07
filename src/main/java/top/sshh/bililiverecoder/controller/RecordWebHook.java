package top.sshh.bililiverecoder.controller;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sshh.bililiverecoder.entity.BlrecData;
import top.sshh.bililiverecoder.entity.RecordEventDTO;
import top.sshh.bililiverecoder.service.DatabaseMaintenanceService;
import top.sshh.bililiverecoder.service.RecordEventFactory;
import top.sshh.bililiverecoder.service.WebhookEventDispatcher;
import top.sshh.bililiverecoder.util.LogKvs;

@Slf4j
@RestController
@RequestMapping("/recordWebHook")
public class RecordWebHook {

    @Autowired
    private RecordEventFactory recordEventFactory;

    @Autowired
    private WebhookEventDispatcher webhookEventDispatcher;

    @Autowired
    private DatabaseMaintenanceService databaseMaintenanceService;

    @PostMapping
    public ResponseEntity<String> processing(@RequestBody String payload) {
        RecordEventDTO recordEvent = JSON.parseObject(payload, RecordEventDTO.class);
        // Webhook 必须尽快响应：把耗时逻辑放到后台队列里执行，避免 servlet 线程被上传/限速/重试占满
        String lockKey = buildLockKey(recordEvent);
        String source = detectSource(recordEvent);
        String eventType = getEffectiveEventType(recordEvent);
        long delayMs = "SessionEnded".equals(eventType) ? 10000L : 0L;

        if (databaseMaintenanceService.spoolRecordWebhookIfMaintenance(payload, lockKey, delayMs)) {
            return ResponseEntity.ok("QUEUED");
        }

        boolean accepted = webhookEventDispatcher.submit(lockKey, delayMs, () -> {
            long dispatchStartNs = System.nanoTime();
            try {
                String roomId = getEffectiveRoomId(recordEvent);
                String title = getEffectiveTitle(recordEvent);
                if (eventType != null || roomId != null || title != null) {
                    log.info("[BLR] {}", LogKvs.event("Webhook.Received")
                        .add("source", source)
                        .add("endpoint", "/recordWebHook")
                        .add("type", eventType)
                        .add("roomId", roomId)
                        .add("title", title)
                        .addStageCostMs("total", dispatchStartNs));
                    log.debug("[BLR] {}", LogKvs.event("Webhook.Payload.Debug")
                            .add("source", source)
                            .add("endpoint", "/recordWebHook")
                            .add("type", eventType)
                            .add("roomId", roomId)
                            .add("payloadLen", JSON.toJSONString(recordEvent).length())
                            .addStageCostMs("total", dispatchStartNs));
                } else {
                    log.info("[BLR] {}", LogKvs.event("Webhook.ReceivedLegacy")
                        .add("source", source)
                        .add("endpoint", "/recordWebHook")
                        .add("payload", JSON.toJSONString(recordEvent))
                        .addStageCostMs("total", dispatchStartNs));
                }
                recordEventFactory.processing(recordEvent);
            } catch (Exception e) {
                log.error("[BLR] {}", LogKvs.event("Webhook.ProcessFailed")
                        .add("source", source)
                        .add("endpoint", "/recordWebHook")
                        .add("type", eventType)
                        .add("roomId", getEffectiveRoomId(recordEvent))
                        .add("lockKeyHash", safeLockKeyHash(lockKey))
                        .add("err", e.getMessage())
                        .add("ex", e.getClass().getSimpleName())
                        .addStageCostMs("total", dispatchStartNs), e);
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
                // 同一直播间的生命周期事件必须进入同一串行队列。此前 File* 用 roomId、
                // Session*/Stream* 用 sessionId（SessionEnded 甚至全局共用一个 key），会使
                // 重连前后的事件并发交错，进而把已结束稿件重新写成 recording=true
                String roomId = recordEvent.getEventData().getRoomId();
                if (roomId != null && !roomId.isBlank()) {
                    lock = "brec:room:" + roomId;
                } else if (recordEvent.getEventData().getSessionId() != null
                        && !recordEvent.getEventData().getSessionId().isBlank()) {
                    lock = "brec:session:" + recordEvent.getEventData().getSessionId();
                } else if (recordEvent.getEventData().getRelativePath() != null
                        && !recordEvent.getEventData().getRelativePath().isBlank()) {
                    lock = "brec:path:" + recordEvent.getEventData().getRelativePath();
                }
            } catch (Exception e) {
                log.debug("[BLR] {}", LogKvs.event("Webhook.LockKey.BuildFailed")
                        .add("eventType", recordEvent.getEventType())
                        .addIfNotBlank("err", e.getMessage())
                        .add("ex", e.getClass().getSimpleName()), e);
            }
        }
        return lock;
    }

    private static String safeLockKeyHash(String lockKey) {
        if (lockKey == null) {
            return null;
        }
        // lockKey 可能包含 relativePath，不要原样打印，避免泄露本地路径/文件名。
        return Integer.toHexString(lockKey.hashCode());
    }

    private static String detectSource(RecordEventDTO recordEvent) {
        if (recordEvent == null) {
            return "unknown";
        }
        if (recordEvent.getData() != null || recordEvent.getType() != null) {
            return "blrec";
        }
        if (recordEvent.getEventData() != null || recordEvent.getEventType() != null) {
            return "brec";
        }
        return "unknown";
    }

    private static String getEffectiveEventType(RecordEventDTO recordEvent) {
        if (recordEvent == null) {
            return null;
        }
        if (recordEvent.getEventType() != null) {
            return recordEvent.getEventType();
        }
        return recordEvent.getType();
    }

    private static String getEffectiveRoomId(RecordEventDTO recordEvent) {
        if (recordEvent == null) {
            return null;
        }
        if (recordEvent.getEventData() != null && recordEvent.getEventData().getRoomId() != null) {
            return recordEvent.getEventData().getRoomId();
        }
        if (recordEvent.getData() != null) {
            if (recordEvent.getData().getRoomInfo() != null && recordEvent.getData().getRoomInfo().getRoomId() != null) {
                return recordEvent.getData().getRoomInfo().getRoomId();
            }
            return recordEvent.getData().getRoomId();
        }
        return null;
    }

    private static String getEffectiveTitle(RecordEventDTO recordEvent) {
        if (recordEvent == null) {
            return null;
        }
        if (recordEvent.getEventData() != null && recordEvent.getEventData().getTitle() != null) {
            return recordEvent.getEventData().getTitle();
        }
        if (recordEvent.getData() != null && recordEvent.getData().getRoomInfo() != null) {
            return recordEvent.getData().getRoomInfo().getTitle();
        }
        return null;
    }

    @GetMapping
    public String processing() {
        return "这里是录播姬推送的接口地址，把当前地址复制到录播姬WebHookV2里即可(前提是录播姬网络环境也可访问)";
    }
}
