package top.sshh.bililiverecoder.controller;

import com.alibaba.fastjson.JSON;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sshh.bililiverecoder.entity.blrec.BlrecEventDTO;
import top.sshh.bililiverecoder.service.DatabaseMaintenanceService;
import top.sshh.bililiverecoder.service.RecordWebhookInboxService;
import top.sshh.bililiverecoder.service.WebhookRequestGuard;
import top.sshh.bililiverecoder.util.LogKvs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Slf4j
@RestController
@RequestMapping("/webhook/blrec")
public class BlrecWebhookController {
    private static final int MAX_PAYLOAD_BYTES = 1_048_576;

    private final DatabaseMaintenanceService databaseMaintenanceService;
    private final RecordWebhookInboxService webhookInboxService;
    private final WebhookRequestGuard requestGuard;

    public BlrecWebhookController(DatabaseMaintenanceService databaseMaintenanceService,
                                  RecordWebhookInboxService webhookInboxService,
                                  WebhookRequestGuard requestGuard) {
        this.databaseMaintenanceService = databaseMaintenanceService;
        this.webhookInboxService = webhookInboxService;
        this.requestGuard = requestGuard;
    }

    @PostMapping
    public ResponseEntity<String> handleWebhook(HttpServletRequest request) {
        long totalStartNs = System.nanoTime();
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

        BlrecEventDTO event;
        try {
            event = JSON.parseObject(payload, BlrecEventDTO.class);
        } catch (Exception error) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("INVALID_JSON");
        }
        if (event == null || event.getType() == null || event.getData() == null) {
            log.error("[BLR] {}", LogKvs.event("BlrecWebhook.InvalidPayload")
                    .add("reason", "Event or type or data is null")
                    .addStageCostMs("total", totalStartNs));
            return ResponseEntity.badRequest().body("INVALID_EVENT");
        }

        String roomId = getRoomId(event);
        if (roomId == null) {
            log.error("[BLR] {}", LogKvs.event("BlrecWebhook.InvalidPayload")
                    .add("reason", "room_id is missing in event data")
                    .addStageCostMs("total", totalStartNs));
            return ResponseEntity.badRequest().body("ROOM_ID_MISSING");
        }

        String title = null;
        if (event.getData() != null && event.getData().getRoomInfo() != null) {
            title = event.getData().getRoomInfo().getTitle();
        }
        log.info("[BLR] {}", LogKvs.event("Webhook.Received")
                .add("source", "blrec")
                .add("endpoint", "/webhook/blrec")
                .add("type", event.getType())
                .add("roomId", roomId)
                .add("title", title)
                .addStageCostMs("total", totalStartNs));

        if (databaseMaintenanceService.spoolBlrecWebhookIfMaintenance(payload, "blrec:" + roomId)) {
            return ResponseEntity.ok().build();
        }

        try {
            RecordWebhookInboxService.Acceptance acceptance = webhookInboxService.accept(
                    payload, "blrec:" + roomId, 0L, "blrec-native", event.getType(), event.getId());
            log.info("[BLR] {}", LogKvs.event("Webhook.Inbox.Accepted")
                    .add("eventId", acceptance.eventId()).add("alreadyQueued", acceptance.alreadyQueued())
                    .add("source", "blrec-native").add("type", event.getType()).add("roomId", roomId));
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("[BLR] {}", LogKvs.event("BlrecWebhook.DispatchError")
                    .add("eventType", event.getType())
                    .add("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName())
                    .addStageCostMs("total", totalStartNs), e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("BUSY");
        }
    }

    private String getRoomId(BlrecEventDTO event) {
        if (event.getData() != null && event.getData().getRoomInfo() != null && event.getData().getRoomInfo().getRoomId() != null) {
            return String.valueOf(event.getData().getRoomInfo().getRoomId());
        }
        if (event.getData() != null && event.getData().getRoomId() != null) {
            return String.valueOf(event.getData().getRoomId());
        }
        return null;
    }
}
