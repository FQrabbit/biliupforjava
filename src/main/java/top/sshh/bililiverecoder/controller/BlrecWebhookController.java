package top.sshh.bililiverecoder.controller;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sshh.bililiverecoder.entity.blrec.BlrecEventDTO;
import top.sshh.bililiverecoder.service.DatabaseMaintenanceService;
import top.sshh.bililiverecoder.util.LogKvs;

@Slf4j
@RestController
@RequestMapping("/webhook/blrec")
public class BlrecWebhookController {

    @Autowired
    private DatabaseMaintenanceService databaseMaintenanceService;

    @PostMapping
    public void handleWebhook(@RequestBody String payload) {
        long totalStartNs = System.nanoTime();
        BlrecEventDTO event = JSON.parseObject(payload, BlrecEventDTO.class);
        if (event == null || event.getType() == null || event.getData() == null) {
            log.error("[BLR] {}", LogKvs.event("BlrecWebhook.InvalidPayload")
                    .add("reason", "Event or type or data is null")
                    .addStageCostMs("total", totalStartNs));
            return;
        }

        String roomId = getRoomId(event);
        if (roomId == null) {
            log.error("[BLR] {}", LogKvs.event("BlrecWebhook.InvalidPayload")
                    .add("reason", "room_id is missing in event data")
                    .addStageCostMs("total", totalStartNs));
            return;
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
            return;
        }

        try {
            databaseMaintenanceService.dispatchBlrecEvent(roomId, event);
        } catch (Exception e) {
            log.error("[BLR] {}", LogKvs.event("BlrecWebhook.DispatchError")
                    .add("eventType", event.getType())
                    .add("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName())
                    .addStageCostMs("total", totalStartNs), e);
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
