package top.sshh.bililiverecoder.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sshh.bililiverecoder.entity.blrec.BlrecEventDTO;
import top.sshh.bililiverecoder.service.blrec.BlrecEventService;
import top.sshh.bililiverecoder.util.LogKvs;

@Slf4j
@RestController
@RequestMapping("/webhook/blrec")
public class BlrecWebhookController {

    @Autowired
    private ApplicationContext applicationContext;

    @PostMapping
    public void handleWebhook(@RequestBody BlrecEventDTO event) {
        if (event == null || event.getType() == null || event.getData() == null) {
            log.error("[BLR] {}", LogKvs.event("BlrecWebhook.InvalidPayload")
                    .add("reason", "Event or type or data is null"));
            return;
        }

        // 从 data 或 room_info 中安全地获取 roomId
        String roomId = getRoomId(event);
        if (roomId == null) {
            log.error("[BLR] {}", LogKvs.event("BlrecWebhook.InvalidPayload")
                    .add("reason", "room_id is missing in event data"));
            return;
        }

        // 使用房间ID进行同步，确保同一房间的事件串行处理
        synchronized (roomId.intern()) {
            try {
                // 将 blrec 的事件类型（如 RecordingStartedEvent）转换为 bean 的名称（如 blrecRecordingStartedEventService）
                String serviceName = "blrec" + event.getType() + "Service";
                BlrecEventService service = applicationContext.getBean(serviceName, BlrecEventService.class);
                service.processing(event);
            } catch (Exception e) {
                log.error("[BLR] {}", LogKvs.event("BlrecWebhook.DispatchError")
                        .add("eventType", event.getType())
                        .add("err", e.getMessage())
                        .add("ex", e.getClass().getSimpleName()), e);
            }
        }
    }

    private String getRoomId(BlrecEventDTO event) {
        if (event.getData() != null && event.getData().getRoomInfo() != null && event.getData().getRoomInfo().getRoomId() != null) {
            return String.valueOf(event.getData().getRoomInfo().getRoomId());
        }
        // 兼容那些 data.room_info 不存在，但 data.room_id 存在的事件 (虽然 blrec 格式里不常见)
        if (event.getData() != null && event.getData().getRoomInfo() != null && event.getData().getRoomInfo().getRoomId() != null) {
            return String.valueOf(event.getData().getRoomInfo().getRoomId());
        }
        return null;
    }
}
