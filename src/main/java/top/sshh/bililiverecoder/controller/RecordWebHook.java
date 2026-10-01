package top.sshh.bililiverecoder.controller;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import top.sshh.bililiverecoder.entity.BlrecData;
import top.sshh.bililiverecoder.entity.RecordEventDTO;
import top.sshh.bililiverecoder.service.DatabaseMaintenanceService;
import top.sshh.bililiverecoder.service.RecordWebhookInboxService;
import top.sshh.bililiverecoder.service.WebhookRequestGuard;
import top.sshh.bililiverecoder.util.LogKvs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Slf4j
@RestController
@RequestMapping("/recordWebHook")
public class RecordWebHook {

    private static final int MAX_PAYLOAD_BYTES = 1_048_576;
    private final RecordWebhookInboxService webhookInboxService;
    private final DatabaseMaintenanceService databaseMaintenanceService;
    private final WebhookRequestGuard requestGuard;

    @Autowired
    public RecordWebHook(RecordWebhookInboxService webhookInboxService,
                         DatabaseMaintenanceService databaseMaintenanceService,
                         WebhookRequestGuard requestGuard) {
        this.webhookInboxService = webhookInboxService;
        this.databaseMaintenanceService = databaseMaintenanceService;
        this.requestGuard = requestGuard;
    }

    public RecordWebHook(RecordWebhookInboxService webhookInboxService,
                         DatabaseMaintenanceService databaseMaintenanceService, String webhookToken) {
        this(webhookInboxService, databaseMaintenanceService, new WebhookRequestGuard(webhookToken));
    }

    @PostMapping
    public ResponseEntity<String> processing(HttpServletRequest request) {
        String rejection = requestGuard.rejectionReason(request);
        if ("RATE_LIMITED".equals(rejection)) return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(rejection);
        if (rejection != null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(rejection);
        String payload;
        try {
            if (request.getContentLengthLong() > MAX_PAYLOAD_BYTES) {
                return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body("PAYLOAD_TOO_LARGE");
            }
            byte[] bytes = request.getInputStream().readNBytes(MAX_PAYLOAD_BYTES + 1);
            if (bytes.length > MAX_PAYLOAD_BYTES) {
                return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body("PAYLOAD_TOO_LARGE");
            }
            payload = new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException error) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("INVALID_BODY");
        }

        RecordEventDTO recordEvent;
        try {
            recordEvent = JSON.parseObject(payload, RecordEventDTO.class);
        } catch (Exception error) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("INVALID_JSON");
        }
        if (recordEvent == null) return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("INVALID_JSON");
        // Webhook 必须尽快响应：把耗时逻辑放到后台队列里执行，避免 servlet 线程被上传/限速/重试占满
        String lockKey = buildLockKey(recordEvent);
        String source = detectSource(recordEvent);
        String eventType = getEffectiveEventType(recordEvent);
        long delayMs = "SessionEnded".equals(eventType) ? 10000L : 0L;

        if (databaseMaintenanceService.spoolRecordWebhookIfMaintenance(payload, lockKey, delayMs)) {
            return ResponseEntity.ok("QUEUED");
        }

        try {
            RecordWebhookInboxService.Acceptance acceptance = webhookInboxService.accept(
                    payload, lockKey, delayMs, source, eventType, getUpstreamEventId(recordEvent));
            log.info("[BLR] {}", LogKvs.event("Webhook.Inbox.Accepted")
                    .add("eventId", acceptance.eventId())
                    .add("alreadyQueued", acceptance.alreadyQueued())
                    .add("source", source)
                    .add("type", eventType)
                    .add("roomId", getEffectiveRoomId(recordEvent)));
            return ResponseEntity.ok("OK");
        } catch (Exception e) {
            log.error("[BLR] {}", LogKvs.event("Webhook.Inbox.AcceptFailed")
                    .add("source", source).add("type", eventType)
                    .add("lockKeyHash", safeLockKeyHash(lockKey))
                    .addIfNotBlank("err", e.getMessage()).add("ex", e.getClass().getSimpleName()), e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("BUSY");
        }
    }

    private static String getUpstreamEventId(RecordEventDTO event) {
        if (event.getEventId() != null && !event.getEventId().isBlank()) return event.getEventId().trim();
        return event.getId() == null || event.getId().isBlank() ? null : event.getId().trim();
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
        // lockKey 可能包含 relativePath，不要原样打印，避免泄露本地路径/文件名
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

    @GetMapping
    public String processing() {
        return "这里是录播姬推送接口地址，把当前地址复制到录播姬 WebHookV2 里即可（前提是录播姬网络环境也可访问）。默认兼容旧版免令牌推送；配置 record.webhook.token 后需通过 X-Record-Webhook-Token 或 Authorization: Bearer 传递令牌";
    }
}
